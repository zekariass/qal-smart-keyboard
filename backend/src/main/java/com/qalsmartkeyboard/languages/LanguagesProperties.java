package com.qalsmartkeyboard.languages;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code qal.languages.*}.
 *
 * @param internalChannelEnabled whether authenticated callers may see {@code INTERNAL}-channel packs (development and
 *                               internal builds only; anonymous callers never can). Default off.
 * @param artifacts              how pack download URLs are produced
 */
@ConfigurationProperties("qal.languages")
record LanguagesProperties(@DefaultValue("false") boolean internalChannelEnabled,
		@DefaultValue Artifacts artifacts) {

	/**
	 * Pre-signed download URLs for packs stored on S3-compatible object storage (MinIO locally).
	 *
	 * @param endpoint  client-reachable base URL of the object store, e.g. {@code http://localhost:9000} (https outside development)
	 * @param bucket    the only bucket URLs are signed for (a stored {@code s3://} URI naming another bucket is refused)
	 * @param region    signing region ({@code us-east-1} is what MinIO expects by default)
	 * @param accessKey read-only credential for the pack bucket
	 * @param secretKey its secret; never logged and never part of {@link #toString()}
	 * @param urlTtl    lifetime of a signed URL, 1 second to 7 days (SigV4 maximum)
	 * @param pathStyle {@code true}: {@code endpoint/bucket/key} (MinIO); {@code false}: {@code bucket.endpoint/key} (AWS S3)
	 */
	record Artifacts(String endpoint, @DefaultValue("qal-language-packs") String bucket,
			@DefaultValue("us-east-1") String region, String accessKey, String secretKey,
			@DefaultValue("15m") Duration urlTtl, @DefaultValue("true") boolean pathStyle) {

		boolean configured() {
			return notBlank(endpoint) && notBlank(accessKey) && notBlank(secretKey);
		}

		private static boolean notBlank(String s) {
			return s != null && !s.isBlank();
		}

		@Override
		public String toString() {
			return "Artifacts[endpoint=" + endpoint + ", bucket=" + bucket + ", secretKey=***]";
		}
	}
}
