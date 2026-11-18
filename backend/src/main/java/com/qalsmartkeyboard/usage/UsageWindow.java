package com.qalsmartkeyboard.usage;

import java.time.Instant;
import java.util.Objects;

/**
 * One metering window: the half-open interval {@code [start, end)} whose consumption one {@code usage_counters} row
 * holds.
 *
 * @param start inclusive
 * @param end   exclusive; for the lifetime window ({@link #LIFETIME}) a far-future sentinel
 */
public record UsageWindow(Instant start, Instant end) {

	/** Start of the lifetime window (reset period NONE). */
	public static final Instant LIFETIME_START = Instant.parse("1970-01-01T00:00:00Z");
	/** End of the lifetime window; never reached, so a NONE allowance never resets. */
	public static final Instant LIFETIME_END = Instant.parse("9999-12-31T00:00:00Z");
	/** The window of reset period NONE. */
	public static final UsageWindow LIFETIME = new UsageWindow(LIFETIME_START, LIFETIME_END);

	public UsageWindow {
		Objects.requireNonNull(start, "start");
		Objects.requireNonNull(end, "end");
		if (!end.isAfter(start)) {
			throw new IllegalArgumentException("A usage window must end after it starts");
		}
	}

	/** True for the window of reset period NONE. */
	public boolean lifetime() {
		return LIFETIME_END.equals(end);
	}

	/** When the allowance resets ({@code resetAt} of {@code QUOTA_EXCEEDED}): the end, or null for the lifetime window. */
	public Instant resetAt() {
		return lifetime() ? null : end;
	}

	public boolean contains(Instant at) {
		return !at.isBefore(start) && at.isBefore(end);
	}
}
