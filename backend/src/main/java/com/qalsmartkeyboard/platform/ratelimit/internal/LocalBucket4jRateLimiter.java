package com.qalsmartkeyboard.platform.ratelimit.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * In-memory Bucket4j token buckets, one per {@code (policy, key)}, held in a bounded Caffeine cache per policy.
 *
 * <ul>
 * <li>Each bucket refills its whole capacity once per period ({@code refillIntervally}), i.e. a fixed window that
 * starts with the key's first request.</li>
 * <li>A bucket idle for a whole period is full again, so it is dropped ({@code expireAfterAccess(period)}) without
 * changing behaviour. At most {@code maxKeysPerPolicy} buckets are kept per policy: under a flood of distinct keys
 * the least recently used buckets are evicted, which resets their window. That bounds memory at the cost of
 * precision under attack.</li>
 * <li>Per instance only (see the package documentation). Not a security boundary on its own for multi-instance
 * deployments until a distributed store replaces it.</li>
 * </ul>
 * Rejections are counted in {@code qal.ratelimit.rejected{policy}}; keys never reach logs or metrics.
 */
public final class LocalBucket4jRateLimiter implements RateLimiter {

	private final ConcurrentMap<RateLimitPolicy, Cache<String, Bucket>> buckets = new ConcurrentHashMap<>();
	private final long maxKeysPerPolicy;
	private final TimeMeter timeMeter;
	private final MeterRegistry meters;

	public LocalBucket4jRateLimiter(long maxKeysPerPolicy, TimeMeter timeMeter, MeterRegistry meters) {
		if (maxKeysPerPolicy < 1) {
			throw new IllegalArgumentException("maxKeysPerPolicy must be at least 1");
		}
		this.maxKeysPerPolicy = maxKeysPerPolicy;
		this.timeMeter = Objects.requireNonNull(timeMeter, "timeMeter");
		this.meters = meters;
	}

	@Override
	public RateLimitDecision tryConsume(RateLimitPolicy policy, String key) {
		Objects.requireNonNull(policy, "policy");
		Objects.requireNonNull(key, "key");
		Cache<String, Bucket> cache = buckets.computeIfAbsent(policy, p -> Caffeine.newBuilder()
				.maximumSize(maxKeysPerPolicy).expireAfterAccess(p.period()).build());
		Bucket bucket = cache.get(key, k -> newBucket(policy));
		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		RateLimitDecision decision = new RateLimitDecision(policy, probe.isConsumed(), probe.getRemainingTokens(),
				probe.isConsumed() ? Duration.ZERO : Duration.ofNanos(probe.getNanosToWaitForRefill()),
				Duration.ofNanos(probe.getNanosToWaitForReset()));
		if (!decision.allowed() && meters != null) {
			meters.counter("qal.ratelimit.rejected", "policy", policy.name()).increment();
		}
		return decision;
	}

	private Bucket newBucket(RateLimitPolicy policy) {
		return Bucket.builder()
				.addLimit(limit -> limit.capacity(policy.capacity()).refillIntervally(policy.capacity(), policy.period()))
				.withCustomTimePrecision(timeMeter).build();
	}
}
