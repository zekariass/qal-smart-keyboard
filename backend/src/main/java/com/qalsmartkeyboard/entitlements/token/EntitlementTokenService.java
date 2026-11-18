package com.qalsmartkeyboard.entitlements.token;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.qalsmartkeyboard.entitlements.AccessState;
import com.qalsmartkeyboard.entitlements.EffectivePlan;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.entitlements.FeatureEntitlement;
import com.qalsmartkeyboard.entitlements.MeteringUnit;
import com.qalsmartkeyboard.entitlements.ResolvedEntitlements;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

/**
 * Issues entitlement tokens (SD 15.5, plan step B4.4, ADR 0013): compact JWS, {@code alg EdDSA} (Ed25519), header
 * {@code typ} {@value #TYPE_VALUE} and {@code kid}. The token is a signed statement of the boolean <b>offline</b> gates
 * of {@link EntitlementResolver#resolve(UUID)} at issue time; clients use it only where they cannot ask the server
 * (on-device models, SD 11.9). Every server feature is re-checked by the server on every call.
 *
 * <h2>Claims</h2> No personal data:
 * <ul>
 * <li>{@code iss}, {@code aud} (configured; the keyboard clients), {@code sub} (user id), {@code deviceId} (the access
 * token's device, when it has one), {@code jti} (random);</li>
 * <li>{@code planCode}, {@code features} (object of feature code to boolean, only the configured offline features that
 * are gated and unmetered), {@code policyVersion} (the quota policy version that produced the gates), {@code ver}
 * ({@value #FORMAT_VERSION}, the token format);</li>
 * <li>{@code iat}, {@code exp} = iat + 24 h, at most the winning source's end plus the renewal lag (ADR 0007 D27),
 * {@code offlineGraceUntil} = exp + 72 h (NumericDate seconds).</li>
 * </ul>
 *
 * <h2>Who gets one</h2> Any ACTIVE account, guests included (their plan opens no offline gate, so every gate is
 * false). Suspended accounts get 403 {@code ACCOUNT_SUSPENDED}, deletion-pending or deleted ones 403
 * {@code DELETION_PENDING}, a merged guest 401 {@code GUEST_SESSION_INVALID}, a pending or unknown account 401
 * {@code UNAUTHENTICATED}. A client that gets 401 or 403 here must drop its cached token.
 */
public final class EntitlementTokenService {

	public static final String TYPE_VALUE = "qal-ent+jwt";
	static final JOSEObjectType TYPE = new JOSEObjectType(TYPE_VALUE);
	/** Token format version ({@code ver}); verifiers reject other values. */
	public static final int FORMAT_VERSION = 1;

	private static final Duration DEFAULT_RENEWAL_LAG = Duration.ofHours(24);

	private static final Logger log = LoggerFactory.getLogger(EntitlementTokenService.class);

	/**
	 * @param jws the compact JWS; never log it
	 */
	public record EntitlementToken(String jws, String keyId, Instant expiresAt, Instant offlineGraceUntil) {
		@Override
		public String toString() {
			return "EntitlementToken[keyId=" + keyId + ", expiresAt=" + expiresAt + ", offlineGraceUntil="
					+ offlineGraceUntil + "]";
		}
	}

	private final EntitlementResolver resolver;
	private final EntitlementTokenKeys keys;
	private final EntitlementTokenProperties properties;
	private final Clock clock;
	private final Duration renewalLag;
	private final JdkEd25519Signer signer;
	private final Set<String> warnedFeatures = ConcurrentHashMap.newKeySet();

	EntitlementTokenService(EntitlementResolver resolver, EntitlementTokenKeys keys,
			EntitlementTokenProperties properties, Clock clock) {
		this(resolver, keys, properties, clock, DEFAULT_RENEWAL_LAG);
	}

	/**
	 * @param renewalLag {@code qal.entitlements.renewal-lag}: how long the resolver keeps an ACTIVE source past its period
	 *                   end; a token may outlive the end by the same time (ADR 0007 D27)
	 */
	EntitlementTokenService(EntitlementResolver resolver, EntitlementTokenKeys keys,
			EntitlementTokenProperties properties, Clock clock, Duration renewalLag) {
		this.renewalLag = Objects.requireNonNull(renewalLag, "renewalLag");
		this.resolver = Objects.requireNonNull(resolver, "resolver");
		this.keys = Objects.requireNonNull(keys, "keys");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.signer = new JdkEd25519Signer(keys.signingKey());
	}

	/** Same keys and settings, another clock (tests issue tokens at fixed instants with it). */
	EntitlementTokenService withClock(Clock otherClock) {
		return new EntitlementTokenService(resolver, keys, properties, otherClock, renewalLag);
	}

	/**
	 * A fresh token for the caller, reflecting their entitlements now.
	 *
	 * @param deviceId the device of the caller's access token, or null
	 * @throws ApiException for an account that may not hold entitlements (see the class comment)
	 */
	public EntitlementToken issue(UUID userId, UUID deviceId) {
		ResolvedEntitlements resolved = resolver.resolve(userId);
		if (resolved.access() != AccessState.GRANTED) {
			throw denied(resolved.access());
		}
		EffectivePlan plan = resolved.plan().orElseThrow(() -> new IllegalStateException("granted without a plan"));

		Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = expiry(issuedAt, plan);
		Instant graceUntil = expiresAt.plus(properties.offlineGrace());

		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(properties.issuer())
				.audience(properties.audience()).subject(userId.toString()).claim("planCode", plan.planCode())
				.claim("features", offlineGates(resolved)).claim("policyVersion", resolved.quotaPolicyVersion())
				.claim("ver", FORMAT_VERSION).issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt))
				.claim("offlineGraceUntil", graceUntil.getEpochSecond()).jwtID(UUID.randomUUID().toString());
		if (deviceId != null) {
			claims.claim("deviceId", deviceId.toString());
		}
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA).type(TYPE).keyID(keys.signingKeyId()).build();
		SignedJWT jwt = new SignedJWT(header, claims.build());
		try {
			jwt.sign(signer);
		}
		catch (JOSEException e) {
			throw new IllegalStateException("Cannot sign the entitlement token", e);
		}
		return new EntitlementToken(jwt.serialize(), keys.signingKeyId(), expiresAt, graceUntil);
	}

	/**
	 * {@code exp} = the TTL after issue, but never later than the winning source's end plus the renewal lag (ADR 0007
	 * D27): a token must not outlive the entitlement it states. A source in its grace period has no lag (the resolver
	 * grants nothing after the grace end); a source without an end (DEFAULT, open-ended grants) keeps the full TTL.
	 */
	private Instant expiry(Instant issuedAt, EffectivePlan plan) {
		Instant expiresAt = issuedAt.plus(properties.ttl());
		if (plan.effectiveUntil() == null) {
			return expiresAt;
		}
		Instant cap = plan.effectiveUntil().plus(plan.inGracePeriod() ? Duration.ZERO : renewalLag)
				.truncatedTo(ChronoUnit.SECONDS);
		if (cap.isBefore(expiresAt)) {
			expiresAt = cap.isAfter(issuedAt) ? cap : issuedAt.plusSeconds(1);
		}
		return expiresAt;
	}

	/** The published key set: the signing key and the previous keys, public parts only. */
	public JWKSet publicKeys() {
		return keys.jwkSet();
	}

	/**
	 * The configured offline features as booleans, in configuration order. Only gated features with unit NONE (boolean
	 * access) qualify: a metered feature is a server allowance and is never put into a token (SD 15.5).
	 */
	private Map<String, Boolean> offlineGates(ResolvedEntitlements resolved) {
		Map<String, Boolean> gates = new LinkedHashMap<>();
		for (String code : properties.offlineFeatures()) {
			FeatureEntitlement feature = resolved.features().get(code);
			if (feature == null || !feature.gated() || feature.unit() != MeteringUnit.NONE) {
				if (warnedFeatures.add(code)) {
					log.warn("qal.entitlements.token.offline-features: {} is not an active, gated, unmetered feature; "
							+ "it is left out of entitlement tokens", code);
				}
				continue;
			}
			gates.put(code, feature.accessEnabled());
		}
		return gates;
	}

	private static ApiException denied(AccessState access) {
		return switch (access) {
			case SUSPENDED -> new ApiException(ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN, null);
			case DELETED -> new ApiException(ApiErrorCode.DELETION_PENDING, HttpStatus.FORBIDDEN, null);
			case MERGED -> new ApiException(ApiErrorCode.GUEST_SESSION_INVALID, HttpStatus.UNAUTHORIZED, null);
			case PENDING, UNKNOWN, GRANTED -> new ApiException(ApiErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED,
					null);
		};
	}
}
