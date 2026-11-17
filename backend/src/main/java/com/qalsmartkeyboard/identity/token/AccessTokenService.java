package com.qalsmartkeyboard.identity.token;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.qalsmartkeyboard.identity.AuthenticatedUser;

/**
 * Issues and verifies access tokens (SD 9.3): ES256 JWTs with header {@code typ=at+jwt} (RFC 9068) and {@code kid},
 * and only the claims the API needs: {@code iss}, {@code aud}, {@code sub} (user id), {@code sid} (refresh-token
 * family), {@code did} (device, when known), {@code accountType} (contract: guest tokens carry {@code GUEST}),
 * {@code iat}, {@code exp}, {@code jti}. No personal data. SD's {@code plan} hint and {@code scope} are omitted until
 * entitlements (B4.x) and scoped tokens need them.
 *
 * <p>Verification: signature with a configured public key (ES256 only, so {@code none}/HS256 tokens and unknown
 * {@code kid}s fail), then {@link #validator()}: {@code typ}, issuer, audience, {@code exp} (with clock skew, reported
 * as {@value #EXPIRED_ERROR_CODE} so the API answers {@code TOKEN_EXPIRED}), {@code iat} not in the future and the
 * shape of the custom claims. Expiry is only examined after the signature has been verified.
 */
public final class AccessTokenService {

	/** OAuth2Error code of an expired (but otherwise valid) access token. */
	public static final String EXPIRED_ERROR_CODE = "qal_token_expired";
	static final JOSEObjectType TYPE = new JOSEObjectType("at+jwt");

	/** @param value the compact JWT; never log it */
	public record AccessToken(String value, Instant expiresAt) {
		@Override
		public String toString() {
			return "AccessToken[expiresAt=" + expiresAt + "]";
		}
	}

	private final AccessTokenKeys keys;
	private final TokenProperties properties;
	private final Clock clock;
	private final ECDSASigner signer;

	AccessTokenService(AccessTokenKeys keys, TokenProperties properties, Clock clock) {
		this.keys = Objects.requireNonNull(keys, "keys");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.clock = Objects.requireNonNull(clock, "clock");
		try {
			this.signer = new ECDSASigner(keys.signingKey());
		}
		catch (JOSEException e) {
			throw new IllegalStateException("Cannot create the ES256 signer", e);
		}
	}

	/** Same keys and settings, another clock (tests mint expired tokens with it). */
	AccessTokenService withClock(Clock otherClock) {
		return new AccessTokenService(keys, properties, otherClock);
	}

	public AccessToken issue(UUID userId, UUID sessionId, UUID deviceId, String accountType) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(properties.accessTokenTtl());
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(properties.issuer())
				.audience(properties.audience()).subject(userId.toString()).claim("sid", sessionId.toString())
				.claim("accountType", accountType).issueTime(Date.from(now)).expirationTime(Date.from(expiresAt))
				.jwtID(UUID.randomUUID().toString());
		if (deviceId != null) {
			claims.claim("did", deviceId.toString());
		}
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).type(TYPE).keyID(keys.signingKeyId()).build();
		SignedJWT jwt = new SignedJWT(header, claims.build());
		try {
			jwt.sign(signer);
		}
		catch (JOSEException e) {
			throw new IllegalStateException("Cannot sign the access token", e);
		}
		// JWT seconds precision: report the instant the token really expires
		return new AccessToken(jwt.serialize(), Instant.ofEpochSecond(expiresAt.getEpochSecond()));
	}

	/** Decoder for the security chain: ES256 signatures with the configured public keys plus {@link #validator()}. */
	public JwtDecoder decoder() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(keys.verificationKeys()))
				.jwsAlgorithm(SignatureAlgorithm.ES256).build();
		decoder.setJwtValidator(validator());
		return decoder;
	}

	OAuth2TokenValidator<Jwt> validator() {
		return jwt -> {
			List<OAuth2Error> errors = new ArrayList<>();
			Instant now = clock.instant();
			Duration skew = properties.clockSkew();
			Object typ = jwt.getHeaders().get("typ");
			if (typ == null || !TYPE.getType().equalsIgnoreCase(typ.toString())) {
				errors.add(invalid("typ"));
			}
			if (!properties.issuer().equals(jwt.getClaimAsString("iss"))) {
				errors.add(invalid("iss"));
			}
			if (jwt.getAudience() == null || !jwt.getAudience().contains(properties.audience())) {
				errors.add(invalid("aud"));
			}
			if (jwt.getIssuedAt() == null || jwt.getIssuedAt().isAfter(now.plus(skew))) {
				errors.add(invalid("iat"));
			}
			if (!isUuid(jwt.getSubject()) || !isUuid(jwt.getClaimAsString("sid"))
					|| (jwt.hasClaim("did") && !isUuid(jwt.getClaimAsString("did")))) {
				errors.add(invalid("sub/sid/did"));
			}
			String accountType = jwt.getClaimAsString("accountType");
			if (!AuthenticatedUser.REGISTERED.equals(accountType) && !AuthenticatedUser.GUEST.equals(accountType)) {
				errors.add(invalid("accountType"));
			}
			if (jwt.getId() == null || jwt.getId().isBlank()) {
				errors.add(invalid("jti"));
			}
			if (jwt.getExpiresAt() == null) {
				errors.add(invalid("exp"));
			}
			else if (!now.minus(skew).isBefore(jwt.getExpiresAt())) {
				errors.add(new OAuth2Error(EXPIRED_ERROR_CODE, "Access token expired", null));
			}
			return errors.isEmpty() ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(errors);
		};
	}

	/** Maps verified claims to the principal. Only call with a token that passed {@link #validator()}. */
	public static AuthenticatedUser principal(Jwt jwt) {
		return new AuthenticatedUser(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")),
				jwt.hasClaim("did") ? UUID.fromString(jwt.getClaimAsString("did")) : null,
				jwt.getClaimAsString("accountType").toUpperCase(Locale.ROOT));
	}

	private static OAuth2Error invalid(String what) {
		return new OAuth2Error("invalid_token", "Access token claim or header rejected: " + what, null);
	}

	private static boolean isUuid(String value) {
		if (value == null) {
			return false;
		}
		try {
			UUID.fromString(value);
			return value.length() == 36;
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}
}
