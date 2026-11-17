package com.qalsmartkeyboard.identity.guest;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.AttestationChecker;
import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;
import com.qalsmartkeyboard.identity.DeviceAttestation;
import com.qalsmartkeyboard.identity.DeviceInfo;
import com.qalsmartkeyboard.identity.DeviceRegistrar;
import com.qalsmartkeyboard.identity.account.UserAccount;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.session.IssuedSession;
import com.qalsmartkeyboard.identity.session.SessionContext;
import com.qalsmartkeyboard.identity.session.SessionIssuer;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code POST /auth/guest} (SD 9.1, ADR 0008): an anonymous {@code GUEST} account for the small AI trial, created only
 * after a valid Play Integrity / App Attest verdict over a fresh server nonce.
 *
 * <p>Order: platform (native only, else 400) → per-IP and per-installation rate limits (429) → attestation (consumes
 * the nonce in its own committed transaction, calls the platform's verifier outside any transaction) → one transaction
 * that claims the installation, creates the {@code GUEST} user, registers the device with the VALID verdict and issues the
 * session through the {@link SessionIssuer} (tokens carry {@code accountType=GUEST} and the device).
 *
 * <p><b>Every failure to attest is the same 403 {@code GUEST_NOT_ALLOWED}</b> with a generic detail: a missing or blank
 * attestation, an unknown, expired or replayed nonce, a verdict for the wrong provider or nonce, a rejected verdict, no
 * verifier configured or reachable, and an installation that already has a guest. The client cannot learn which, so the
 * endpoint is no oracle for probing nonces or installation ids.
 *
 * <p><b>One guest per installation, no resume.</b> The contract says "creates (or resumes)", but the installation id is a
 * random value the client sends in clear inside TLS and the attestation does not bind to it, so resuming on the id alone
 * would let anyone who learns an id take over that guest. A second request for the same installation is refused.
 *
 * <p><b>Plan:</b> the {@code GUEST} plan is the effective plan of a {@code GUEST} account when it has no subscription
 * (SD 15.5), so no subscription row is written; the seeded {@code subscription_plans} row and its
 * {@code plan_entitlements} are what the entitlement resolver (B4.1) reads.
 */
public class GuestService {

	private static final Logger log = LoggerFactory.getLogger(GuestService.class);
	static final String DETAIL = "A guest session could not be created.";

	/** @param user the new guest account */
	public record GuestSession(UserAccount user, IssuedSession session) {
	}

	private sealed interface Outcome {
		record Created(GuestSession guest) implements Outcome {
		}

		record InstallationTaken() implements Outcome {
		}
	}

	private final AttestationChecker attestation;
	private final ObjectProvider<DeviceRegistrar> devices;
	private final UserAccountStore accounts;
	private final SessionIssuer sessions;
	private final RateLimiter limiter;
	private final RateLimitPolicy perIp;
	private final RateLimitPolicy perInstallation;
	private final TransactionTemplate transactions;
	private final Clock clock;
	private final MeterRegistry meters;

	GuestService(AttestationChecker attestation, ObjectProvider<DeviceRegistrar> devices, UserAccountStore accounts,
			SessionIssuer sessions, RateLimiter limiter, RateLimitPolicy perIp, RateLimitPolicy perInstallation,
			TransactionTemplate transactions, Clock clock, MeterRegistry meters) {
		this.attestation = attestation;
		this.devices = devices;
		this.accounts = accounts;
		this.sessions = sessions;
		this.limiter = limiter;
		this.perIp = perIp;
		this.perInstallation = perInstallation;
		this.transactions = transactions;
		this.clock = clock;
		this.meters = meters;
	}

	/**
	 * @param evidence may be {@code null} (the client sent none)
	 * @throws ApiException 400 for a non-native platform, 429 over a limit, 403 {@code GUEST_NOT_ALLOWED} otherwise
	 */
	public GuestSession create(UUID installationId, String platform, Evidence evidence, String clientIp,
			String userAgent, String appVersion) {
		if (!"ANDROID".equals(platform) && !"IOS".equals(platform)) {
			count("invalid_platform");
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
					List.of(new ApiFieldError("platform", "UNSUPPORTED_PLATFORM",
							"Guest sessions exist for ANDROID and IOS only.")), Map.of());
		}
		try {
			limiter.tryConsume(perIp, clientIp).orThrow();
			limiter.tryConsume(perInstallation, installationId.toString()).orThrow();
		}
		catch (ApiException e) {
			count("rate_limited");
			throw e;
		}
		if (evidence == null) {
			count("no_attestation");
			throw notAllowed();
		}
		AttestationChecker.Result verdict = attestation.check(null, platform, evidence);
		if (verdict.status() != AttestationChecker.Status.VALID) {
			count("attestation_" + verdict.status().name().toLowerCase(java.util.Locale.ROOT));
			throw notAllowed();
		}
		DeviceRegistrar registrar = devices.getIfAvailable();
		if (registrar == null) {
			count("no_device_registry");
			throw notAllowed();
		}
		Outcome outcome = transactions.execute(status -> {
			if (!registrar.claimGuestInstallation(installationId)) {
				return new Outcome.InstallationTaken();
			}
			UserAccount user = accounts.createGuest(clock.instant());
			UUID deviceId = registrar.register(user.id(),
					new DeviceInfo(installationId, platform, null, appVersion, null, null, null),
					new DeviceAttestation(verdict.provider(), "VALID", verdict.checkedAt()));
			IssuedSession session = sessions.issue(user, new SessionContext(deviceId, clientIp, userAgent));
			return new Outcome.Created(new GuestSession(user, session));
		});
		return switch (outcome) {
			case Outcome.Created c -> {
				count("created");
				log.info("Guest account {} created", c.guest().user().id());
				yield c.guest();
			}
			case Outcome.InstallationTaken t -> {
				count("installation_taken");
				throw notAllowed();
			}
		};
	}

	static ApiException notAllowed() {
		return new ApiException(ApiErrorCode.GUEST_NOT_ALLOWED, HttpStatus.FORBIDDEN, DETAIL);
	}

	private void count(String outcome) {
		if (meters != null) {
			meters.counter("qal.identity.guest", "outcome", outcome).increment();
		}
	}
}
