package com.qalsmartkeyboard.platform.ratelimit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

/**
 * Outcome of one {@link RateLimiter#tryConsume} call.
 *
 * @param policy     the policy that was checked
 * @param allowed    whether the request may proceed (a token was consumed)
 * @param remaining  requests left in the current window after this one
 * @param retryAfter when rejected, how long until one request is allowed again; {@link Duration#ZERO} when allowed
 * @param reset      how long until the window is full again
 */
public record RateLimitDecision(RateLimitPolicy policy, boolean allowed, long remaining, Duration retryAfter,
		Duration reset) {

	public static final String RETRY_AFTER = "Retry-After";
	public static final String LIMIT = "RateLimit-Limit";
	public static final String REMAINING = "RateLimit-Remaining";
	public static final String RESET = "RateLimit-Reset";

	public RateLimitDecision {
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(retryAfter, "retryAfter");
		Objects.requireNonNull(reset, "reset");
		remaining = Math.max(0, remaining);
	}

	/**
	 * The contract's {@code RateLimit-Limit}, {@code RateLimit-Remaining} and {@code RateLimit-Reset} headers (integer
	 * seconds, rounded up), plus {@code Retry-After} (at least 1 second) when rejected.
	 */
	public Map<String, String> headers() {
		Map<String, String> h = new LinkedHashMap<>();
		h.put(LIMIT, Long.toString(policy.capacity()));
		h.put(REMAINING, Long.toString(remaining));
		h.put(RESET, Long.toString(ceilSeconds(reset)));
		if (!allowed) {
			h.put(RETRY_AFTER, Long.toString(Math.max(1, ceilSeconds(retryAfter))));
		}
		return h;
	}

	/** Returns this decision when allowed; otherwise throws {@link #toProblem()}. */
	public RateLimitDecision orThrow() {
		if (!allowed) {
			throw toProblem();
		}
		return this;
	}

	/** {@code RATE_LIMITED} 429 Problem with this decision's headers. Says nothing about which key was limited. */
	public ApiException toProblem() {
		return new ApiException(ApiErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
				"Too many requests. Try again later.", List.of(), Map.of(), headers());
	}

	/** A {@code RATE_LIMITED} 429 Problem with only {@code Retry-After} (for limits that are not token buckets, e.g. a cooldown). */
	public static ApiException retryAfterProblem(Duration retryAfter) {
		return new ApiException(ApiErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
				"Too many requests. Try again later.", List.of(), Map.of(),
				Map.of(RETRY_AFTER, Long.toString(Math.max(1, ceilSeconds(retryAfter)))));
	}

	static long ceilSeconds(Duration d) {
		if (d.isNegative() || d.isZero()) {
			return 0;
		}
		long seconds = d.getSeconds();
		return d.getNano() > 0 ? seconds + 1 : seconds;
	}
}
