package com.qalsmartkeyboard.identity.attestation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.AttestationChecker;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Attestation nonces and verdict checks (SD 9.4, ADR 0008).
 *
 * <p><b>Nonces:</b> 256 random bits (43 base64url characters) from {@link SecureRandom}, valid
 * {@code qal.identity.attestation.nonce-ttl} (5 minutes), stored only as SHA-256 ({@code attestation_nonces}). A nonce
 * can be bound to the user who asked for it (bearer present) and to the platform header; it is consumed by one atomic
 * {@code UPDATE}, in its own transaction that commits whatever the verdict says, so a replayed verdict (the same nonce
 * again) fails and a failed verdict burns its nonce.
 *
 * <p><b>Check:</b> consume the nonce, check that the provider belongs to the platform, then ask the
 * {@link AttestationVerifier}, outside any transaction (SD 8.3: no external call inside a transaction), which must
 * confirm that the verdict covers this nonce. Without a usable verifier the answer is {@code UNSUPPORTED}, never
 * {@code VALID}.
 */
public class AttestationService implements AttestationChecker {

	private static final Logger log = LoggerFactory.getLogger(AttestationService.class);
	private static final int NONCE_BYTES = 32;

	/** @param nonce the clear nonce, on its way to the client only */
	public record IssuedNonce(String nonce, Instant expiresAt) {
		@Override
		public String toString() {
			return "IssuedNonce[expiresAt=" + expiresAt + "]";
		}
	}

	private final NonceStore nonces;
	private final AttestationVerifier verifier;
	private final RateLimiter limiter;
	private final RateLimitPolicy noncePerIp;
	private final java.time.Duration nonceTtl;
	private final TransactionTemplate ownTransaction;
	private final Clock clock;
	private final SecureRandom random;
	private final MeterRegistry meters;

	AttestationService(NonceStore nonces, AttestationVerifier verifier, RateLimiter limiter,
			AttestationProperties properties, TransactionTemplate transactions, Clock clock, SecureRandom random,
			MeterRegistry meters) {
		this.nonces = nonces;
		this.verifier = verifier;
		this.limiter = limiter;
		this.noncePerIp = properties.limits().noncePerIp().policy("attestation.nonce.ip");
		this.nonceTtl = properties.nonceTtl();
		this.ownTransaction = new TransactionTemplate(transactions.getTransactionManager());
		this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
		this.random = random;
		this.meters = meters;
	}

	/**
	 * Issues a nonce. Rate limited per client IP (429 {@code RATE_LIMITED}).
	 *
	 * @param userId   the caller if authenticated, else {@code null}
	 * @param platform the {@code X-Client-Platform} the nonce is for, or {@code null}
	 */
	public IssuedNonce issueNonce(UUID userId, String platform, String clientIp) {
		limiter.tryConsume(noncePerIp, clientIp).orThrow();
		byte[] raw = new byte[NONCE_BYTES];
		random.nextBytes(raw);
		String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		Instant now = clock.instant();
		Instant expiresAt = now.plus(nonceTtl);
		ownTransaction.executeWithoutResult(status -> nonces.insert(hash(nonce), userId, platform, now, expiresAt));
		count("attestation.nonces", "issued");
		return new IssuedNonce(nonce, expiresAt);
	}

	@Override
	public Result check(UUID userId, String platform, Evidence evidence) {
		Instant now = clock.instant();
		String provider = providerOf(platform);
		if (provider == null) {
			count("attestation.verdicts", "unsupported_platform");
			return new Result(Status.UNSUPPORTED, null, now, null);
		}
		if (evidence == null || evidence.nonce() == null || evidence.nonce().isBlank() || evidence.token() == null
				|| evidence.token().isBlank()) {
			count("attestation.verdicts", "incomplete");
			return failed(provider, now);
		}
		// Consume first and commit, whatever follows: the nonce is single use (replay) and a failed verdict burns it.
		Boolean consumed = ownTransaction.execute(status -> nonces.consume(hash(evidence.nonce()), userId, platform, now));
		if (!Boolean.TRUE.equals(consumed)) {
			count("attestation.verdicts", "nonce_rejected");
			return failed(provider, now);
		}
		if (!provider.equals(evidence.provider())) {
			count("attestation.verdicts", "provider_mismatch");
			return failed(provider, now);
		}
		AttestationVerdict verdict = verifier.verify(platform, evidence, evidence.nonce());
		return switch (verdict.outcome()) {
			case VALID -> {
				count("attestation.verdicts", "valid");
				yield new Result(Status.VALID, provider, now, verdict.validUntil());
			}
			case REJECTED -> {
				count("attestation.verdicts", "rejected");
				log.info("Attestation verdict rejected for platform {}", platform);
				yield failed(provider, now);
			}
			case UNAVAILABLE -> {
				count("attestation.verdicts", "unavailable");
				yield new Result(Status.UNSUPPORTED, provider, now, null);
			}
		};
	}

	private static Result failed(String provider, Instant now) {
		return new Result(Status.FAILED, provider, now, null);
	}

	/** The attestation provider of a platform (V1 {@code ck_device_attestation_provider}). */
	static String providerOf(String platform) {
		return "ANDROID".equals(platform) ? "PLAY_INTEGRITY" : "IOS".equals(platform) ? "APP_ATTEST" : null;
	}

	static String hash(String nonce) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(nonce.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	private void count(String name, String outcome) {
		if (meters != null) {
			meters.counter("qal.identity." + name, "outcome", outcome).increment();
		}
	}
}
