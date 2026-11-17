package com.qalsmartkeyboard.identity.token;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code qal.identity.tokens.*} (SD 9.3, ADR 0006).
 *
 * @param issuer          {@code iss} of access tokens
 * @param audience        {@code aud} of access tokens
 * @param accessTokenTtl  access-token lifetime; the contract allows 10-15 minutes, so at most 15 minutes
 * @param clockSkew       tolerance for {@code exp}/{@code iat} checks
 * @param refreshTokenTtl sliding refresh-token lifetime (SD: 60 days); every rotation starts a new period
 * @param refreshReuseGrace how long after a rotation a retry with the rotated token may rotate again instead of revoking
 *                        the family, provided the successor was never used (ADR 0007 D8, lost responses on slow
 *                        networks). Default 30s, {@code 0} disables, at most 2 minutes
 * @param signingKey      the ES256 (P-256) key that signs access tokens
 * @param previousKeys    public keys of retired signing keys, still accepted for verification during a rotation
 */
@ConfigurationProperties("qal.identity.tokens")
public record TokenProperties(@DefaultValue("qal-smart-keyboard") String issuer, @DefaultValue("qal-api") String audience,
		@DefaultValue("15m") Duration accessTokenTtl, @DefaultValue("30s") Duration clockSkew,
		@DefaultValue("60d") Duration refreshTokenTtl, @DefaultValue("30s") Duration refreshReuseGrace,
		@DefaultValue SigningKey signingKey,
		List<VerificationKey> previousKeys) {

	/** Upper bound of {@link #refreshReuseGrace()}: a longer window would weaken reuse detection (SD 9.3). */
	public static final Duration MAX_REFRESH_REUSE_GRACE = Duration.ofMinutes(2);

	public TokenProperties {
		if (accessTokenTtl.isNegative() || accessTokenTtl.isZero() || accessTokenTtl.compareTo(Duration.ofMinutes(15)) > 0) {
			throw new IllegalArgumentException("qal.identity.tokens.access-token-ttl must be > 0 and <= 15m");
		}
		if (refreshTokenTtl.isNegative() || refreshTokenTtl.isZero()) {
			throw new IllegalArgumentException("qal.identity.tokens.refresh-token-ttl must be positive");
		}
		if (refreshReuseGrace.isNegative() || refreshReuseGrace.compareTo(MAX_REFRESH_REUSE_GRACE) > 0) {
			throw new IllegalArgumentException("qal.identity.tokens.refresh-reuse-grace must be between 0 and 2m");
		}
		if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(2)) > 0) {
			throw new IllegalArgumentException("qal.identity.tokens.clock-skew must be between 0 and 2m");
		}
		previousKeys = previousKeys == null ? List.of() : List.copyOf(previousKeys);
	}

	/**
	 * @param keyId          {@code kid} header (env {@code QAL_JWT_KEY_ID})
	 * @param privateKey     PKCS#8 PEM (or bare base64 DER) of a P-256 private key (env {@code QAL_JWT_PRIVATE_KEY})
	 * @param publicKey      X.509 SubjectPublicKeyInfo PEM of the same key (env {@code QAL_JWT_PUBLIC_KEY})
	 * @param allowEphemeral DEV/TEST ONLY: without a configured key, generate one per JVM (tokens die with the JVM and
	 *                       are not accepted by other instances); refused under prod/production/staging
	 */
	public record SigningKey(@DefaultValue("dev-1") String keyId, String privateKey, String publicKey,
			@DefaultValue("false") boolean allowEphemeral) {

		/** Never prints the private key. */
		@Override
		public String toString() {
			return "SigningKey[keyId=" + keyId + ", privateKey="
					+ (privateKey == null || privateKey.isBlank() ? "<unset>" : "<redacted>") + ", allowEphemeral="
					+ allowEphemeral + "]";
		}
	}

	/** @param publicKey X.509 SubjectPublicKeyInfo PEM of a retired P-256 signing key */
	public record VerificationKey(String keyId, String publicKey) {
	}
}
