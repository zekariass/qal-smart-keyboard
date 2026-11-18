package com.qalsmartkeyboard.usage;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param defaultTimeZone    window time zone when {@code users.time_zone} is missing or not a valid zone id (SD 15.3:
 *                           the market time zone, default {@code Africa/Addis_Ababa})
 * @param reservationTtl     how long a reservation lives before the sweeper may release it (SD 15.3: 15 minutes)
 * @param sweeperEnabled     run the expiry sweeper as a db-scheduler recurring task
 * @param sweepInterval      delay between sweeper runs
 * @param sweepBatchSize     reservations examined per sweeper batch (the run continues while batches are full)
 */
@ConfigurationProperties("qal.usage")
record UsageProperties(@DefaultValue("Africa/Addis_Ababa") String defaultTimeZone,
		@DefaultValue("15m") Duration reservationTtl, @DefaultValue("true") boolean sweeperEnabled,
		@DefaultValue("1m") Duration sweepInterval, @DefaultValue("100") int sweepBatchSize) {

	UsageProperties {
		try {
			ZoneId.of(defaultTimeZone);
		} catch (DateTimeException | NullPointerException e) {
			throw new IllegalArgumentException("qal.usage.default-time-zone must be a valid zone id", e);
		}
		if (reservationTtl.isNegative() || reservationTtl.isZero()) {
			throw new IllegalArgumentException("qal.usage.reservation-ttl must be positive");
		}
		if (sweepInterval.isNegative() || sweepInterval.isZero()) {
			throw new IllegalArgumentException("qal.usage.sweep-interval must be positive");
		}
		if (sweepBatchSize < 1) {
			throw new IllegalArgumentException("qal.usage.sweep-batch-size must be positive");
		}
	}

	ZoneId zone() {
		return ZoneId.of(defaultTimeZone);
	}
}
