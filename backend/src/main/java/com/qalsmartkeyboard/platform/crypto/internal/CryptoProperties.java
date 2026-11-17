package com.qalsmartkeyboard.platform.crypto.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code qal.crypto.*} settings (see backend/README.md and ADR 0005).
 *
 * @param kms      which KMS adapter wraps data keys
 * @param localKms settings of the development KMS ({@code provider=local})
 * @param cloudKms settings of the cloud KMS adapter ({@code provider=cloud})
 * @param dekCache in-memory cache of unwrapped data keys
 */
@ConfigurationProperties("qal.crypto")
public record CryptoProperties(
		@DefaultValue Kms kms,
		@DefaultValue LocalKms localKms,
		@DefaultValue CloudKms cloudKms,
		@DefaultValue DekCache dekCache) {

	/** @param provider {@code local} (development/tests only) or {@code cloud}; no default, startup fails if unset */
	public record Kms(String provider) {
	}

	/**
	 * @param kek               base64 of a 32-byte key-encryption key (env {@code QAL_LOCAL_KMS_KEK}); never commit one
	 * @param kekId             identifier stored as {@code local:<kekId>} in {@code kms_key_id}
	 * @param allowEphemeralKek DEV/TEST ONLY: when no KEK is set, generate a random one at startup (data is
	 *                          unreadable after a restart)
	 */
	public record LocalKms(String kek, @DefaultValue("dev-1") String kekId,
			@DefaultValue("false") boolean allowEphemeralKek) {

		/** Never prints the KEK. */
		@Override
		public String toString() {
			return "LocalKms[kek=" + (kek == null || kek.isBlank() ? "<unset>" : "<redacted>") + ", kekId=" + kekId
					+ ", allowEphemeralKek=" + allowEphemeralKek + "]";
		}
	}

	/** @param keyName KMS key resource name / ARN used for new wraps */
	public record CloudKms(String keyName) {
	}

	/**
	 * @param maxEntries maximum cached data keys; 0 disables the cache
	 * @param ttl        lifetime of a cached key; 0 disables the cache
	 */
	public record DekCache(@DefaultValue("10000") int maxEntries, @DefaultValue("5m") Duration ttl) {
	}
}
