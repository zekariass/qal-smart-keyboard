package com.qalsmartkeyboard.personalization;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code qal.creative-backup.*} (T8.15, ADR 0050): the opt-in encrypted cloud backup of creative assets.
 *
 * @param enabled          product switch (env {@code QAL_CREATIVE_BACKUP_ENABLED}, default {@code true}: free for every
 *                         signed-in user who grants the {@code CREATIVE_BACKUP} consent). {@code false} answers 404
 *                         {@code NOT_FOUND} on every {@code /me/creative-backup} operation; stored items are kept, and
 *                         withdrawal and account deletion still delete them
 * @param maxItemBytes     largest item content in bytes, before encryption (default 2 MiB); more is 413
 *                         {@code BACKUP_ITEM_TOO_LARGE}
 * @param maxItemsPerKind  items per user and kind (default 100); one more is 409 {@code BACKUP_QUOTA_EXCEEDED}
 * @param maxTotalBytes    content bytes per user over all kinds (default 32 MiB, so the base64 copy in a data export
 *                         stays well inside the 64 MiB archive cap); more is 409 {@code BACKUP_QUOTA_EXCEEDED}
 * @param rateLimit        requests per user and {@code rateLimitPeriod} over all backup operations (default 600)
 * @param rateLimitPeriod  window of {@code rateLimit} (default 1 hour)
 */
@ConfigurationProperties("qal.creative-backup")
record CreativeBackupProperties(@DefaultValue("true") boolean enabled, @DefaultValue("2097152") int maxItemBytes,
		@DefaultValue("100") int maxItemsPerKind, @DefaultValue("33554432") long maxTotalBytes,
		@DefaultValue("600") int rateLimit, @DefaultValue("1h") Duration rateLimitPeriod) {

	/**
	 * The content travels as base64 inside a JSON string, parsed in memory: 8 MiB decoded is about 11 million
	 * characters, well inside Jackson's default 20 million character limit for one string.
	 */
	static final int ITEM_BYTES_CEILING = 8 * 1024 * 1024;

	CreativeBackupProperties {
		if (maxItemBytes < 1 || maxItemBytes > ITEM_BYTES_CEILING) {
			throw new IllegalArgumentException("qal.creative-backup.max-item-bytes must be between 1 and " + ITEM_BYTES_CEILING);
		}
		if (maxItemsPerKind < 1) {
			throw new IllegalArgumentException("qal.creative-backup.max-items-per-kind must be positive");
		}
		if (maxTotalBytes < maxItemBytes) {
			throw new IllegalArgumentException("qal.creative-backup.max-total-bytes must be at least max-item-bytes");
		}
		if (rateLimit < 1) {
			throw new IllegalArgumentException("qal.creative-backup.rate-limit must be positive");
		}
		if (rateLimitPeriod.compareTo(Duration.ofSeconds(1)) < 0) {
			throw new IllegalArgumentException("qal.creative-backup.rate-limit-period must be at least one second");
		}
	}

	/**
	 * The largest request body accepted for a PUT: the base64 of {@code maxItemBytes} plus room for the other members.
	 * A longer body is refused before it is parsed.
	 */
	int maxRequestBytes() {
		return (int) Math.min(Integer.MAX_VALUE - 16, 4L * ((maxItemBytes + 2L) / 3) + 4096);
	}
}
