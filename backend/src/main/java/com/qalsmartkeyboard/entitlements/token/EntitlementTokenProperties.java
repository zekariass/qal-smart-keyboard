package com.qalsmartkeyboard.entitlements.token;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code qal.entitlements.token.*} (SD 15.5, ADR 0013).
 *
 * @param issuer          {@code iss} of entitlement tokens
 * @param audience        {@code aud}: the keyboard clients (Android IME, iOS extension, companions)
 * @param ttl             online validity, {@code exp - iat}; SD 15.5: 24 h, so at most 24 h
 * @param offlineGrace    how long after {@code exp} an offline client may keep using the token
 *                        ({@code offlineGraceUntil - exp}); SD 15.5: 72 h, so at most 72 h
 * @param offlineFeatures the feature codes the token carries as boolean gates. SD 15.5 and 11.9: only gates a client
 *                        enforces offline (on-device models). Only gated, unmetered ({@code NONE}) catalogue features are
 *                        ever written; a configured metered or unknown code is left out (server AI allowances are never
 *                        in the token)
 * @param signingKey      the Ed25519 key that signs new tokens
 * @param previousKeys    public keys still published (and accepted by verifiers) during a rotation: retired keys until
 *                        every token they signed is past its offline grace ({@code ttl + offlineGrace}, 96 h)
 */
@ConfigurationProperties("qal.entitlements.token")
public record EntitlementTokenProperties(@DefaultValue("qal-smart-keyboard") String issuer,
		@DefaultValue("qal-keyboard") String audience, @DefaultValue("24h") Duration ttl,
		@DefaultValue("72h") Duration offlineGrace, @DefaultValue("ON_DEVICE_AI_MODELS") List<String> offlineFeatures,
		@DefaultValue SigningKey signingKey, List<VerificationKey> previousKeys) {

	/** SD 15.5: tokens are valid for 24 h. */
	public static final Duration MAX_TTL = Duration.ofHours(24);
	/** SD 15.5: offline grace of 72 h after {@code exp}. */
	public static final Duration MAX_OFFLINE_GRACE = Duration.ofHours(72);

	public EntitlementTokenProperties {
		if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
			throw new IllegalArgumentException("qal.entitlements.token.issuer and audience must not be blank");
		}
		if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
			throw new IllegalArgumentException("qal.entitlements.token.ttl must be > 0 and <= 24h");
		}
		if (offlineGrace.isNegative() || offlineGrace.compareTo(MAX_OFFLINE_GRACE) > 0) {
			throw new IllegalArgumentException("qal.entitlements.token.offline-grace must be between 0 and 72h");
		}
		offlineFeatures = offlineFeatures == null ? List.of() : List.copyOf(offlineFeatures);
		for (String code : offlineFeatures) {
			if (code == null || !code.matches("^[A-Z][A-Z0-9_]{1,39}$")) {
				throw new IllegalArgumentException("qal.entitlements.token.offline-features must be feature codes");
			}
		}
		previousKeys = previousKeys == null ? List.of() : List.copyOf(previousKeys);
	}

	/**
	 * @param keyId          {@code kid} header (env {@code QAL_ENTITLEMENT_TOKEN_KEY_ID})
	 * @param privateKey     the Ed25519 private key: PKCS#8 PEM (or bare base64 DER), or an OKP JWK with {@code d} and
	 *                       {@code x} (env {@code QAL_ENTITLEMENT_TOKEN_PRIVATE_KEY})
	 * @param publicKey      X.509 SubjectPublicKeyInfo PEM (or a public OKP JWK) of the same key (env
	 *                       {@code QAL_ENTITLEMENT_TOKEN_PUBLIC_KEY}); optional when the private key is a JWK
	 * @param allowEphemeral DEV/TEST ONLY: without a configured key, generate one per JVM (its tokens verify only against
	 *                       this JVM's key set, so no client pins it); refused under prod/production/staging
	 */
	public record SigningKey(@DefaultValue("ent-dev-1") String keyId, String privateKey, String publicKey,
			@DefaultValue("false") boolean allowEphemeral) {

		/** Never prints the private key. */
		@Override
		public String toString() {
			return "SigningKey[keyId=" + keyId + ", privateKey="
					+ (privateKey == null || privateKey.isBlank() ? "<unset>" : "<redacted>") + ", allowEphemeral="
					+ allowEphemeral + "]";
		}
	}

	/** @param publicKey SPKI PEM (or public OKP JWK) of a retired Ed25519 signing key */
	public record VerificationKey(String keyId, String publicKey) {
	}
}
