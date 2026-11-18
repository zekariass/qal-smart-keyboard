package com.qalsmartkeyboard.privacy;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Account deletion (B6.2, ADR 0015).
 *
 * @param gracePeriod       the cancellable window between the request and the purge (SD 19.4: 14 days)
 * @param executorEnabled   run the deletion executor as a db-scheduler recurring task
 * @param executorInterval  delay between executor runs
 * @param batchSize         due requests examined per batch (a run continues while batches are full)
 * @param retryBackoff      wait after the first failed step; doubles per further failure
 * @param maxRetryBackoff   upper bound of that wait (the executor retries forever: a deletion is never given up)
 */
@ConfigurationProperties("qal.privacy.deletion")
record DeletionProperties(@DefaultValue("14d") Duration gracePeriod, @DefaultValue("true") boolean executorEnabled,
		@DefaultValue("5m") Duration executorInterval, @DefaultValue("20") int batchSize,
		@DefaultValue("1m") Duration retryBackoff, @DefaultValue("1h") Duration maxRetryBackoff) {

	DeletionProperties {
		if (gracePeriod.isNegative()) {
			throw new IllegalArgumentException("qal.privacy.deletion.grace-period must not be negative");
		}
		if (executorInterval.isNegative() || executorInterval.isZero()) {
			throw new IllegalArgumentException("qal.privacy.deletion.executor-interval must be positive");
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("qal.privacy.deletion.batch-size must be positive");
		}
		if (retryBackoff.isNegative() || retryBackoff.isZero() || maxRetryBackoff.compareTo(retryBackoff) < 0) {
			throw new IllegalArgumentException(
					"qal.privacy.deletion.retry-backoff must be positive and at most max-retry-backoff");
		}
	}

	/** The wait after the {@code attempts}-th consecutive failure (1-based). */
	Duration backoff(int attempts) {
		Duration wait = retryBackoff;
		for (int i = 1; i < attempts && wait.compareTo(maxRetryBackoff) < 0; i++) {
			wait = wait.multipliedBy(2);
		}
		return wait.compareTo(maxRetryBackoff) > 0 ? maxRetryBackoff : wait;
	}
}
