package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.aigateway.ProviderRegistryTest.MutableClock;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreaker;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreaker.State;

/** Breaker state machine with an injected clock (B5.2). */
class CircuitBreakerTest {

	final MutableClock clock = new MutableClock();
	final List<String> transitions = new ArrayList<>();

	CircuitBreaker breaker(int window, int min, int failPct) {
		return new CircuitBreaker("p", new CircuitBreaker.Config(window, min, failPct, Duration.ofSeconds(1), 80,
				Duration.ofSeconds(10), 2), clock, (a, b) -> transitions.add(a + ">" + b));
	}

	@Test
	void staysClosedBelowTheMinimumCallsAndTheFailureRate() {
		CircuitBreaker b = breaker(4, 3, 50);
		b.recordFailure();
		b.recordFailure();
		assertThat(b.state()).isEqualTo(State.CLOSED); // only two calls, three needed
		CircuitBreaker c = breaker(4, 3, 50);
		c.recordFailure();
		for (int i = 0; i < 4; i++) {
			c.recordSuccess(Duration.ZERO); // F S S S, then the failure slides out: 25 percent, then 0
		}
		assertThat(c.state()).isEqualTo(State.CLOSED);
		assertThat(transitions).isEmpty();
	}

	@Test
	void opensAtTheFailureRateAndTheSlidingWindowForgetsOldCalls() {
		CircuitBreaker b = breaker(4, 4, 50);
		b.recordFailure();
		b.recordSuccess(Duration.ZERO);
		b.recordSuccess(Duration.ZERO);
		b.recordFailure(); // F S S F = 50 percent
		assertThat(b.state()).isEqualTo(State.OPEN);
		assertThat(b.tryAcquire()).isFalse();
		assertThat(transitions).containsExactly("CLOSED>OPEN");
	}

	@Test
	void halfOpenAllowsOnlyTheConfiguredProbesAndReleaseGivesAPermitBack() {
		CircuitBreaker b = breaker(2, 2, 50);
		b.recordFailure();
		b.recordFailure();
		clock.advance(Duration.ofSeconds(10));
		assertThat(b.tryAcquire()).isTrue();
		assertThat(b.tryAcquire()).isTrue();
		assertThat(b.tryAcquire()).isFalse(); // two probes in flight
		b.release(); // one probe said nothing about health
		assertThat(b.tryAcquire()).isTrue();
		b.recordSuccess(Duration.ZERO);
		b.recordSuccess(Duration.ZERO);
		assertThat(b.state()).isEqualTo(State.CLOSED);
		assertThat(transitions).containsExactly("CLOSED>OPEN", "OPEN>HALF_OPEN", "HALF_OPEN>CLOSED");
	}

	@Test
	void aSlowProbeReopensAndTripExtendsButNeverShortensTheWait() {
		CircuitBreaker b = breaker(2, 2, 50);
		b.trip(Duration.ofSeconds(60));
		clock.advance(Duration.ofSeconds(59));
		assertThat(b.tryAcquire()).isFalse();
		b.trip(Duration.ofSeconds(1)); // shorter: ignored
		clock.advance(Duration.ofSeconds(2));
		assertThat(b.tryAcquire()).isTrue();
		b.recordSuccess(Duration.ofSeconds(2)); // slower than the 1 s threshold
		assertThat(b.state()).isEqualTo(State.OPEN);
	}

	@Test
	void invalidConfigurationIsRejected() {
		assertThatThrownBy(() -> new CircuitBreaker.Config(2, 3, 50, Duration.ZERO, 80, Duration.ZERO, 1))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
