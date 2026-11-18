package com.qalsmartkeyboard.aigateway.routing;

import java.time.Duration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Router metrics (B5.2). Tags are provider code, feature code, outcome and reason only: bounded sets, never a language,
 * user or text. Names: {@code qal.ai.provider.calls} / {@code qal.ai.provider.latency} (per attempt),
 * {@code qal.ai.route.outcome}, {@code qal.ai.route.failovers}, {@code qal.ai.route.skipped},
 * {@code qal.ai.circuit.state} (0 closed, 1 open, 2 half-open) and {@code qal.ai.circuit.transitions},
 * {@code qal.ai.cost.micros}, {@code qal.ai.cost.spend.micros} (today's total) and {@code qal.ai.cost.alerts}.
 */
public class RoutingMetrics {

	private final MeterRegistry meters;

	public RoutingMetrics(MeterRegistry meters) {
		this.meters = meters;
	}

	public void attempt(String provider, String feature, String outcome, Duration latency) {
		meters.counter("qal.ai.provider.calls", "provider", provider, "feature", feature, "outcome", outcome)
				.increment();
		meters.timer("qal.ai.provider.latency", "provider", provider, "feature", feature, "outcome", outcome)
				.record(latency);
	}

	public void skipped(String provider, String reason) {
		meters.counter("qal.ai.route.skipped", "provider", provider, "reason", reason).increment();
	}

	public void outcome(String feature, String outcome) {
		meters.counter("qal.ai.route.outcome", "feature", feature, "outcome", outcome).increment();
	}

	public void failover(String feature) {
		meters.counter("qal.ai.route.failovers", "feature", feature).increment();
	}

	public void cost(String provider, double micros) {
		meters.counter("qal.ai.cost.micros", "provider", provider).increment(micros);
	}

	public void costAlert(String provider, String level) {
		meters.counter("qal.ai.cost.alerts", "provider", provider, "level", level).increment();
	}

	public void circuitTransition(CircuitBreakers.Transition t) {
		meters.counter("qal.ai.circuit.transitions", "provider", t.providerCode(), "to", t.to().name()).increment();
	}

	/** Registers (once) the per-provider gauges; the registry returns the existing meter on repeat calls. */
	public void watch(String provider, CircuitBreaker breaker, CostControl cost) {
		Gauge.builder("qal.ai.circuit.state", breaker, b -> b.state().ordinal()).tag("provider", provider)
				.register(meters);
		Gauge.builder("qal.ai.cost.spend.micros", cost, c -> c.spentToday(provider).doubleValue())
				.tag("provider", provider).register(meters);
	}
}
