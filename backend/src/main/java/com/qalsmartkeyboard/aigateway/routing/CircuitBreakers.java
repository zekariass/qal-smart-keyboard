package com.qalsmartkeyboard.aigateway.routing;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * One {@link CircuitBreaker} per provider code. State is shared by every request in this JVM and is not shared across
 * instances: each node learns a failing provider after its own failures, which is acceptable at pilot scale (a node
 * needs only {@code minimumCalls} calls). The path to shared state is a different implementation of this class that
 * keeps the window and open-until in Redis (or PostgreSQL) per provider; the router only uses {@link #forProvider}.
 */
public class CircuitBreakers {

	/** A transition of one provider breaker, for metrics and the operator log. */
	public record Transition(String providerCode, CircuitBreaker.State from, CircuitBreaker.State to) {
	}

	private final CircuitBreaker.Config config;
	private final Clock clock;
	private final Consumer<Transition> listener;
	private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

	public CircuitBreakers(CircuitBreaker.Config config, Clock clock, Consumer<Transition> listener) {
		this.config = config;
		this.clock = clock;
		this.listener = listener == null ? t -> {
		} : listener;
	}

	/** How long a breaker stays open after tripping on its own failure rate. */
	public java.time.Duration openDuration() {
		return config.openDuration();
	}

	public CircuitBreaker forProvider(String providerCode) {
		return breakers.computeIfAbsent(providerCode, code -> new CircuitBreaker(code, config, clock,
				(from, to) -> listener.accept(new Transition(code, from, to))));
	}

	/** Operator override: closes every breaker. */
	public void resetAll() {
		breakers.values().forEach(CircuitBreaker::reset);
	}

	/** Providers that currently have a breaker (for gauges). */
	public Iterable<String> knownProviders() {
		return breakers.keySet();
	}
}
