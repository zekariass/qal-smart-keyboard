package com.qalsmartkeyboard.platform.ratelimit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;

import io.github.bucket4j.TimeMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LocalBucket4jRateLimiterTest {

	/** Manually advanced clock for the buckets. */
	static final class FakeTime implements TimeMeter {
		final AtomicLong nanos = new AtomicLong(1_000_000_000L);

		@Override
		public long currentTimeNanos() {
			return nanos.get();
		}

		@Override
		public boolean isWallClockBased() {
			return false;
		}

		void advance(Duration d) {
			nanos.addAndGet(d.toNanos());
		}
	}

	final FakeTime time = new FakeTime();
	final SimpleMeterRegistry meters = new SimpleMeterRegistry();
	final LocalBucket4jRateLimiter limiter = new LocalBucket4jRateLimiter(1000, time, meters);
	final RateLimitPolicy threePerMinute = new RateLimitPolicy("test.policy", 3, Duration.ofMinutes(1));

	@Test
	void allowsCapacityThenRejectsWithRetryAfterAndHeaders() {
		assertThat(limiter.tryConsume(threePerMinute, "k").remaining()).isEqualTo(2);
		assertThat(limiter.tryConsume(threePerMinute, "k").remaining()).isEqualTo(1);
		RateLimitDecision last = limiter.tryConsume(threePerMinute, "k");
		assertThat(last.allowed()).isTrue();
		assertThat(last.remaining()).isZero();

		time.advance(Duration.ofSeconds(20));
		RateLimitDecision rejected = limiter.tryConsume(threePerMinute, "k");
		assertThat(rejected.allowed()).isFalse();
		assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(40));
		assertThat(rejected.headers()).containsEntry("RateLimit-Limit", "3").containsEntry("RateLimit-Remaining", "0")
				.containsEntry("Retry-After", "40").containsEntry("RateLimit-Reset", "40");
		assertThat(meters.counter("qal.ratelimit.rejected", "policy", "test.policy").count()).isEqualTo(1.0);
	}

	@Test
	void windowRefillsCompletelyAfterThePeriod() {
		for (int i = 0; i < 3; i++) {
			limiter.tryConsume(threePerMinute, "k");
		}
		assertThat(limiter.tryConsume(threePerMinute, "k").allowed()).isFalse();
		time.advance(Duration.ofMinutes(1));
		RateLimitDecision decision = limiter.tryConsume(threePerMinute, "k");
		assertThat(decision.allowed()).isTrue();
		assertThat(decision.remaining()).isEqualTo(2);
	}

	@Test
	void keysAndPoliciesAreIndependent() {
		for (int i = 0; i < 3; i++) {
			limiter.tryConsume(threePerMinute, "a");
		}
		assertThat(limiter.tryConsume(threePerMinute, "a").allowed()).isFalse();
		assertThat(limiter.tryConsume(threePerMinute, "b").allowed()).isTrue();
		RateLimitPolicy other = new RateLimitPolicy("other.policy", 1, Duration.ofMinutes(1));
		assertThat(limiter.tryConsume(other, "a").allowed()).isTrue();
		assertThat(limiter.tryConsume(other, "a").allowed()).isFalse();
	}

	@Test
	void rejectionBecomesRateLimitedProblemWithHeaders() {
		RateLimitPolicy one = new RateLimitPolicy("one", 1, Duration.ofSeconds(30));
		limiter.tryConsume(one, "k").orThrow();
		assertThatThrownBy(() -> limiter.tryConsume(one, "k").orThrow()).isInstanceOfSatisfying(ApiException.class,
				e -> {
					assertThat(e.code()).isEqualTo(ApiErrorCode.RATE_LIMITED);
					assertThat(e.status().value()).isEqualTo(429);
					assertThat(e.headers()).containsEntry("Retry-After", "30").containsKey("RateLimit-Reset");
				});
	}

	@Test
	void retryAfterIsAtLeastOneSecondAndRoundedUp() {
		ApiException e = RateLimitDecision.retryAfterProblem(Duration.ofMillis(1200));
		assertThat(e.headers()).containsExactlyEntriesOf(java.util.Map.of("Retry-After", "2"));
		assertThat(RateLimitDecision.retryAfterProblem(Duration.ZERO).headers()).containsEntry("Retry-After", "1");
	}

	@Test
	void policyValidation() {
		assertThatThrownBy(() -> new RateLimitPolicy("Bad Name", 1, Duration.ofMinutes(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimitPolicy("ok", 0, Duration.ofMinutes(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RateLimitPolicy("ok", 1, Duration.ofMillis(10)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void memoryIsBoundedPerPolicy() {
		LocalBucket4jRateLimiter small = new LocalBucket4jRateLimiter(10, time, null);
		RateLimitPolicy one = new RateLimitPolicy("one", 1, Duration.ofMinutes(1));
		for (int i = 0; i < 10_000; i++) {
			small.tryConsume(one, "key-" + i); // never fails, never grows without bound
		}
		assertThat(small.tryConsume(one, "fresh").allowed()).isTrue();
	}
}
