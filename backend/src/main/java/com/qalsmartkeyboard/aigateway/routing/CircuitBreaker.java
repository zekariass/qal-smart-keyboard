package com.qalsmartkeyboard.aigateway.routing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BiConsumer;

/**
 * Count-based sliding-window circuit breaker for one provider (B5.2, ADR 0014). In-house rather than Resilience4j: the
 * project has no such dependency, the need is one small state machine, and the clock must be injectable so open and
 * half-open timing is testable without sleeping. State is per JVM; sharing it is the {@link CircuitBreakers} seam.
 *
 * <ul>
 * <li>CLOSED: every outcome goes into a window of the last {@code windowSize} calls. Once at least
 * {@code minimumCalls} are recorded, it opens when failures reach {@code failureRatePercent} or slow successes (slower
 * than {@code slowCallThreshold}) reach {@code slowCallRatePercent}.</li>
 * <li>OPEN: no call is permitted until {@code openDuration} has passed.</li>
 * <li>HALF_OPEN: at most {@code halfOpenCalls} probe calls are in flight or counted; that many successes close the
 * breaker (window cleared), any failure or slow probe reopens it.</li>
 * </ul>
 * Only failures that count against provider health are recorded as failures; the router reports everything else
 * (content rejected, unsupported pair, cancelled) with {@link #release()} so a probe permit is returned without
 * judging the provider.
 */
public final class CircuitBreaker {

	public enum State {
		CLOSED, OPEN, HALF_OPEN
	}

	/** @param halfOpenCalls probe calls permitted, and successes needed to close */
	public record Config(int windowSize, int minimumCalls, int failureRatePercent, Duration slowCallThreshold,
			int slowCallRatePercent, Duration openDuration, int halfOpenCalls) {

		public Config {
			if (windowSize < 1 || minimumCalls < 1 || minimumCalls > windowSize || halfOpenCalls < 1
					|| failureRatePercent < 1 || failureRatePercent > 100 || slowCallRatePercent < 1
					|| slowCallRatePercent > 100 || openDuration.isNegative() || slowCallThreshold.isNegative()) {
				throw new IllegalArgumentException("invalid circuit breaker configuration");
			}
		}
	}

	private static final byte OK = 0;
	private static final byte SLOW = 1;
	private static final byte FAILED = 2;

	private final String name;
	private final Config config;
	private final Clock clock;
	private final BiConsumer<State, State> onTransition;
	private final byte[] window;

	private State state = State.CLOSED;
	private int recorded;
	private int next;
	private Instant openUntil = Instant.MIN;
	private int probesIssued;
	private int probeSuccesses;

	public CircuitBreaker(String name, Config config, Clock clock, BiConsumer<State, State> onTransition) {
		this.name = name;
		this.config = config;
		this.clock = clock;
		this.onTransition = onTransition == null ? (a, b) -> {
		} : onTransition;
		this.window = new byte[config.windowSize()];
	}

	public String name() {
		return name;
	}

	/** Current state; an OPEN breaker whose wait is over still reports OPEN until the next {@link #tryAcquire()}. */
	public synchronized State state() {
		return state;
	}

	/** True when a call may go to the provider. A permitted call must end in exactly one record or release. */
	public synchronized boolean tryAcquire() {
		switch (state) {
			case CLOSED:
				return true;
			case OPEN:
				if (clock.instant().isBefore(openUntil)) {
					return false;
				}
				transition(State.HALF_OPEN);
				probesIssued = 1;
				return true;
			default:
				if (probesIssued < config.halfOpenCalls()) {
					probesIssued++;
					return true;
				}
				return false;
		}
	}

	public synchronized void recordSuccess(Duration latency) {
		boolean slow = latency.compareTo(config.slowCallThreshold()) > 0;
		if (state == State.HALF_OPEN) {
			if (slow) {
				open(config.openDuration());
			} else if (++probeSuccesses >= config.halfOpenCalls()) {
				transition(State.CLOSED);
			}
			return;
		}
		if (state == State.CLOSED) {
			push(slow ? SLOW : OK);
		}
	}

	public synchronized void recordFailure() {
		if (state == State.HALF_OPEN) {
			open(config.openDuration());
		} else if (state == State.CLOSED) {
			push(FAILED);
		}
	}

	/** The call ended without saying anything about provider health (client abort, content refusal, pair mismatch). */
	public synchronized void release() {
		if (state == State.HALF_OPEN && probesIssued > probeSuccesses) {
			probesIssued--;
		}
	}

	/** Opens now for {@code duration} (vendor quota exhausted, credentials refused) whatever the window says. */
	public synchronized void trip(Duration duration) {
		if (state != State.OPEN) {
			open(duration);
		} else {
			Instant until = clock.instant().plus(duration);
			if (until.isAfter(openUntil)) {
				openUntil = until;
			}
		}
	}

	/** Operator override: close the breaker and forget the window. */
	public synchronized void reset() {
		if (state != State.CLOSED) {
			transition(State.CLOSED);
		}
		recorded = 0;
		next = 0;
	}

	private void push(byte outcome) {
		window[next] = outcome;
		next = (next + 1) % window.length;
		if (recorded < window.length) {
			recorded++;
		}
		if (recorded < config.minimumCalls()) {
			return;
		}
		int failed = 0;
		int slow = 0;
		for (int i = 0; i < recorded; i++) {
			if (window[i] == FAILED) {
				failed++;
			} else if (window[i] == SLOW) {
				slow++;
			}
		}
		if (failed * 100 >= config.failureRatePercent() * recorded
				|| slow * 100 >= config.slowCallRatePercent() * recorded) {
			open(config.openDuration());
		}
	}

	private void open(Duration duration) {
		openUntil = clock.instant().plus(duration);
		transition(State.OPEN);
	}

	private void transition(State to) {
		State from = state;
		state = to;
		probesIssued = 0;
		probeSuccesses = 0;
		if (to != State.HALF_OPEN) {
			recorded = 0;
			next = 0;
		}
		if (from != to) {
			onTransition.accept(from, to);
		}
	}
}
