package com.qalsmartkeyboard.platform.ratelimit;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A named limit: at most {@code capacity} requests per {@code period} for one key (fixed window that starts with the
 * key's first request and refills completely at the end of each period).
 *
 * @param name     stable, low-cardinality id used in metrics and as the bucket namespace, e.g. {@code otp.start.ip}
 * @param capacity requests allowed per period (at least 1)
 * @param period   window length (at least one second)
 */
public record RateLimitPolicy(String name, long capacity, Duration period) {

	private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

	public RateLimitPolicy {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(period, "period");
		if (!NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("Rate-limit policy name must match " + NAME.pattern());
		}
		if (capacity < 1) {
			throw new IllegalArgumentException("Rate-limit capacity must be at least 1");
		}
		if (period.compareTo(Duration.ofSeconds(1)) < 0) {
			throw new IllegalArgumentException("Rate-limit period must be at least one second");
		}
	}
}
