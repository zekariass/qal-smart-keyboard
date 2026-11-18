package com.qalsmartkeyboard.languages;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code qal.languages.publishing.*}: admin pack publishing (B3.2, ADR 0019).
 *
 * @param maxContainerBytes largest accepted upload (default: the pack format's container limit, 32 MiB + 128 KiB)
 * @param licenseAllowList  file path of {@code language-data/licenses.json}; empty = the classpath copy
 *                          ({@code languages/licenses.json}, kept identical to the language-data file by a test)
 * @param signing           the server's pack signing key
 * @param storage           write credentials for the pack bucket (endpoint, bucket, region and path style are those of
 *                          {@code qal.languages.artifacts}; the endpoint may be overridden for server-side access)
 */
@ConfigurationProperties("qal.languages.publishing")
record PackPublishingProperties(@DefaultValue("33685504") long maxContainerBytes, String licenseAllowList,
		@DefaultValue Signing signing, @DefaultValue Storage storage) {

	/**
	 * @param provider       {@code local} (a configured 32-byte Ed25519 seed, or an ephemeral one with
	 *                       {@code allow-ephemeral}) or {@code kms} (needs a {@code KmsSigningClient} bean; none ships yet)
	 * @param keyId          {@code resource_signing_keys.key_id} of the key (env {@code QAL_PACK_SIGNING_KEY_ID})
	 * @param privateKey     base64 or hex of the 32-byte seed (env {@code QAL_PACK_SIGNING_PRIVATE_KEY}); never logged
	 * @param allowEphemeral development and tests only: a random key per JVM; refused under prod/production/staging
	 * @param kmsKeyName     the KMS key resource name for {@code provider=kms}
	 */
	record Signing(@DefaultValue("local") String provider, String keyId, String privateKey,
			@DefaultValue("false") boolean allowEphemeral, String kmsKeyName) {

		@Override
		public String toString() {
			return "Signing[provider=" + provider + ", keyId=" + keyId + ", privateKey=***]";
		}
	}

	/**
	 * @param endpoint  server-side object store URL; empty = {@code qal.languages.artifacts.endpoint}
	 * @param accessKey write credential for the pack bucket (env {@code QAL_PACKS_S3_WRITE_ACCESS_KEY})
	 * @param secretKey its secret (env {@code QAL_PACKS_S3_WRITE_SECRET_KEY}); never logged
	 */
	record Storage(String endpoint, String accessKey, String secretKey) {

		boolean configured() {
			return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
		}

		@Override
		public String toString() {
			return "Storage[endpoint=" + endpoint + ", secretKey=***]";
		}
	}
}
