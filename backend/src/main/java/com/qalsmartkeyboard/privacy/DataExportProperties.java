package com.qalsmartkeyboard.privacy;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Data export (B6.1, ADR 0022).
 *
 * @param linkTtl         how long a finished export can be downloaded (SD 19.4: 7 days); the object is deleted then and
 *                        the bucket's 7-day lifecycle rule is the backstop (must not be lower than this)
 * @param maxPerWindow    exports one user may request per {@code window} (the rate limit; a request answered with the
 *                        user's open request does not count)
 * @param window          the rate-limit window
 * @param maxArchiveBytes largest archive (plaintext JSON) the job builds; larger fails the export with
 *                        {@code ARCHIVE_TOO_LARGE} (the archive is built in memory)
 * @param jobEnabled      run the export job and the expiry sweeper as db-scheduler recurring tasks
 * @param pollInterval    delay between job runs (an export starts at most this long after the request)
 * @param staleAfter      a {@code PROCESSING} export not touched for this long is taken to be abandoned by a crashed
 *                        instance and queued again
 * @param publicBaseUrl   base URL clients reach the API under, to build {@code downloadUrl}; empty = the request's own
 * @param storage         the private export bucket
 */
@ConfigurationProperties("qal.privacy.export")
record DataExportProperties(@DefaultValue("7d") Duration linkTtl, @DefaultValue("3") int maxPerWindow,
		@DefaultValue("24h") Duration window, @DefaultValue("67108864") long maxArchiveBytes,
		@DefaultValue("true") boolean jobEnabled, @DefaultValue("10s") Duration pollInterval,
		@DefaultValue("15m") Duration staleAfter, String publicBaseUrl, @DefaultValue Storage storage) {

	DataExportProperties {
		if (linkTtl.isNegative() || linkTtl.isZero() || linkTtl.compareTo(Duration.ofDays(7)) > 0) {
			throw new IllegalArgumentException(
					"qal.privacy.export.link-ttl must be positive and at most 7 days (the bucket lifecycle)");
		}
		if (maxPerWindow < 1) {
			throw new IllegalArgumentException("qal.privacy.export.max-per-window must be positive");
		}
		if (window.isNegative() || window.isZero()) {
			throw new IllegalArgumentException("qal.privacy.export.window must be positive");
		}
		if (maxArchiveBytes < 1024) {
			throw new IllegalArgumentException("qal.privacy.export.max-archive-bytes is too small");
		}
		if (pollInterval.isNegative() || pollInterval.isZero() || staleAfter.isNegative() || staleAfter.isZero()) {
			throw new IllegalArgumentException("qal.privacy.export.poll-interval and stale-after must be positive");
		}
	}

	/**
	 * The export bucket and its read-write credential (the backend is the only client; env
	 * {@code QAL_EXPORTS_S3_*}).
	 *
	 * @param endpoint  object store URL, for example {@code http://localhost:9000} (https outside development)
	 * @param bucket    the private bucket (infra/compose: {@code qal-exports}, 7-day lifecycle)
	 * @param region    signing region ({@code us-east-1} for MinIO)
	 * @param accessKey credential with read, write and delete on this bucket only
	 * @param secretKey its secret; never logged and never part of {@link #toString()}
	 * @param pathStyle {@code true}: {@code endpoint/bucket/key} (MinIO); {@code false}: {@code bucket.endpoint/key}
	 */
	record Storage(String endpoint, @DefaultValue("qal-exports") String bucket, @DefaultValue("us-east-1") String region,
			String accessKey, String secretKey, @DefaultValue("true") boolean pathStyle) {

		boolean configured() {
			return notBlank(endpoint) && notBlank(accessKey) && notBlank(secretKey);
		}

		private static boolean notBlank(String s) {
			return s != null && !s.isBlank();
		}

		@Override
		public String toString() {
			return "Storage[endpoint=" + endpoint + ", bucket=" + bucket + ", secretKey=***]";
		}
	}
}
