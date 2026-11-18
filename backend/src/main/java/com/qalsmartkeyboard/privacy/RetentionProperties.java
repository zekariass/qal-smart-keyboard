package com.qalsmartkeyboard.privacy;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retention (B6.3, ADR 0020).
 *
 * @param enabled           run the retention job as a db-scheduler recurring task
 * @param cron              when the job runs (Spring cron, six fields, UTC); daily at 03:00 by default
 * @param batchSize         rows (or groups) per batch; each batch is one transaction
 * @param maxBatchesPerRule batches per rule and run; a rule with more backlog continues in the next run
 * @param periods           per-rule overrides of the default period, keyed by rule code
 *                          ({@code qal.retention.periods.otp-challenges=48h}); only rules the job executes can be
 *                          overridden, and an unknown key stops the application at start
 */
@ConfigurationProperties("qal.retention")
record RetentionProperties(@DefaultValue("true") boolean enabled, @DefaultValue("0 0 3 * * *") String cron,
		@DefaultValue("1000") int batchSize, @DefaultValue("1000") int maxBatchesPerRule,
		@DefaultValue Map<String, Duration> periods) {

	RetentionProperties {
		if (batchSize < 1) {
			throw new IllegalArgumentException("qal.retention.batch-size must be positive");
		}
		if (maxBatchesPerRule < 1) {
			throw new IllegalArgumentException("qal.retention.max-batches-per-rule must be positive");
		}
		for (Map.Entry<String, Duration> e : periods.entrySet()) {
			if (e.getValue() == null || e.getValue().isNegative()) {
				throw new IllegalArgumentException("qal.retention.periods." + e.getKey() + " must not be negative");
			}
		}
	}
}
