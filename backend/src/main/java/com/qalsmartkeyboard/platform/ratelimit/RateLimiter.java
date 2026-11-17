package com.qalsmartkeyboard.platform.ratelimit;

/**
 * Consumes one request from the bucket of {@code (policy, key)}.
 *
 * <p>Keys identify the limited subject (an IP address, a device id, a keyed hash of a phone number). Prefer a hash or
 * an opaque id to raw personal data: keys stay in memory (and later in a shared store) for up to one period. Keys are
 * never logged or used as metric tags.
 */
public interface RateLimiter {

	/** Never throws for a rejection; check {@link RateLimitDecision#allowed()} or call {@link RateLimitDecision#orThrow()}. */
	RateLimitDecision tryConsume(RateLimitPolicy policy, String key);
}
