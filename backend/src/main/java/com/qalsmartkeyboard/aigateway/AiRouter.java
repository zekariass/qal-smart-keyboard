package com.qalsmartkeyboard.aigateway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qalsmartkeyboard.aigateway.RouteResult.Attempt;
import com.qalsmartkeyboard.aigateway.RouteResult.Outcome;
import com.qalsmartkeyboard.aigateway.RouteResult.Skip;
import com.qalsmartkeyboard.aigateway.RouteResult.SkipReason;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreaker;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreakers;
import com.qalsmartkeyboard.aigateway.routing.CostControl;
import com.qalsmartkeyboard.aigateway.routing.KillSwitch;
import com.qalsmartkeyboard.aigateway.routing.RoutingMetrics;
import com.qalsmartkeyboard.aigateway.routing.RoutingPolicies;
import com.qalsmartkeyboard.aigateway.routing.RoutingPolicy;
import com.qalsmartkeyboard.aigateway.spi.DataPolicyRequirement;
import com.qalsmartkeyboard.aigateway.spi.ProviderException;
import com.qalsmartkeyboard.aigateway.spi.ProviderException.Kind;
import com.qalsmartkeyboard.aigateway.spi.ProviderUsage;
import com.qalsmartkeyboard.aigateway.spi.ResolvedProvider;
import com.qalsmartkeyboard.aigateway.spi.RouteQuery;

/**
 * Executes one AI call against an ordered list of providers (B5.2, ADR 0014 "Routing", SD 11 and 17/19).
 *
 * <ol>
 * <li><b>Candidates.</b> {@link ProviderRegistry} decides who may serve the query (ACTIVE, data policy, capability).
 * The enabled {@code ai_routing_policies} rows then order them: the most specific language scope that matches wins,
 * lowest priority first, equal priorities by weighted random draw; a matching policy is also an allow-list. With no
 * matching row the registry order (provider code) applies. DEGRADED providers that would otherwise qualify are
 * appended after every ACTIVE one (ADR 0007 D29: last resort only).</li>
 * <li><b>Gates per candidate</b> (a gated candidate is skipped, not an attempt): feature kill switch (whole route),
 * provider kill switch, hard cost cap, circuit breaker.</li>
 * <li><b>Attempt</b> with {@code min(attempt timeout, time left)}; at most {@code maxAttempts} calls and the overall
 * deadline. The provider is never retried within a request: the next attempt is the next provider.</li>
 * <li><b>Failure handling</b> by {@link Kind}: UNAVAILABLE, TIMEOUT, INVALID_RESPONSE, QUOTA_EXHAUSTED_AT_PROVIDER and
 * AUTHENTICATION_FAILED fall back (the last two also trip that provider's breaker at once); UNSUPPORTED_LANGUAGE_PAIR
 * falls back (every remaining candidate was matched to the pair by the registry) without touching health;
 * CONTENT_REJECTED ends the route (a refusal is not a provider fault and is not shopped around); CANCELLED ends it as a
 * client abort.</li>
 * </ol>
 * Nothing here sees user text: it is inside the request closure and the result value, and the router logs only
 * provider codes, attempt numbers and error kinds. A request thread interrupt is the cancellation signal (SPI contract).
 */
public class AiRouter {

	private static final Logger log = LoggerFactory.getLogger(AiRouter.class);

	/** Router limits; a request may override them per call. */
	public record Settings(int maxAttempts, Duration attemptTimeout, Duration deadline) {

		public Settings {
			if (maxAttempts < 1 || !attemptTimeout.isPositive() || !deadline.isPositive()) {
				throw new IllegalArgumentException("invalid router settings");
			}
		}
	}

	private record Candidate(ResolvedProvider provider, boolean lastResort) {
	}

	private final ProviderRegistry registry;
	private final RoutingPolicies policies;
	private final KillSwitch killSwitch;
	private final CircuitBreakers breakers;
	private final CostControl cost;
	private final Settings settings;
	private final RoutingMetrics metrics;

	public AiRouter(ProviderRegistry registry, RoutingPolicies policies, KillSwitch killSwitch,
			CircuitBreakers breakers, CostControl cost, Settings settings, RoutingMetrics metrics) {
		this.registry = registry;
		this.policies = policies;
		this.killSwitch = killSwitch;
		this.breakers = breakers;
		this.cost = cost;
		this.settings = settings;
		this.metrics = metrics;
	}

	public <T> RouteResult<T> route(RouteRequest<T> request) {
		long start = System.nanoTime();
		RouteQuery query = request.query();
		String feature = query.feature().name();
		List<Attempt> attempts = new ArrayList<>();
		List<Skip> skipped = new ArrayList<>();

		if (killSwitch.featureKilled(query.feature())) {
			skipped.add(new Skip(null, SkipReason.KILL_SWITCH_FEATURE));
			metrics.skipped("*", SkipReason.KILL_SWITCH_FEATURE.name());
			log.warn("AI feature {} is switched off", feature);
			return finish(Outcome.PROVIDER_UNAVAILABLE, null, null, false, attempts, skipped, null, start, feature);
		}

		List<ResolvedProvider> active = registry.resolve(query);
		List<ResolvedProvider> lastResort = registry.resolveLastResort(query);
		Optional<RoutingPolicy.Plan> plan = plan(query);
		List<Candidate> candidates = new ArrayList<>();
		order(active, plan).forEach(p -> candidates.add(new Candidate(p, false)));
		order(lastResort, plan).forEach(p -> candidates.add(new Candidate(p, true)));
		if (candidates.isEmpty()) {
			return finish(noRoute(query, active, lastResort), null, null, false, attempts, skipped, null, start,
					feature);
		}

		int maxAttempts = request.maxAttempts() > 0 ? request.maxAttempts() : settings.maxAttempts();
		Duration attemptCap = request.attemptTimeout() != null ? request.attemptTimeout() : settings.attemptTimeout();
		long deadlineNanos = (request.deadline() != null ? request.deadline() : settings.deadline()).toNanos();
		Kind lastHealthKind = null;
		boolean deadlineHit = false;

		for (Candidate c : candidates) {
			if (attempts.size() >= maxAttempts) {
				break;
			}
			if (Thread.currentThread().isInterrupted()) {
				return finish(Outcome.CANCELLED, null, null, false, attempts, skipped, Kind.CANCELLED, start, feature);
			}
			long remaining = deadlineNanos - (System.nanoTime() - start);
			if (remaining <= 0) {
				deadlineHit = true;
				break;
			}
			String code = c.provider().code();
			if (killSwitch.providerKilled(code)) {
				skip(skipped, code, SkipReason.KILL_SWITCH_PROVIDER);
				continue;
			}
			CircuitBreaker breaker = breakers.forProvider(code);
			metrics.watch(code, breaker, cost);
			if (cost.status(code) == CostControl.Status.HARD_EXCEEDED) {
				skip(skipped, code, SkipReason.COST_CAP);
				continue;
			}
			if (!breaker.tryAcquire()) {
				skip(skipped, code, SkipReason.CIRCUIT_OPEN);
				continue;
			}

			Duration timeout = Duration.ofNanos(Math.min(attemptCap.toNanos(), remaining));
			boolean shortened = timeout.compareTo(attemptCap) < 0;
			int number = attempts.size() + 1;
			long t0 = System.nanoTime();
			try {
				T value = request.call().execute(c.provider().adapter(), timeout);
				Duration latency = Duration.ofNanos(System.nanoTime() - t0);
				breaker.recordSuccess(latency);
				attempts.add(new Attempt(number, code, c.lastResort(), latency, null));
				metrics.attempt(code, feature, "SUCCEEDED", latency);
				book(code, query, request.usage().apply(value));
				if (number > 1) {
					metrics.failover(feature);
				}
				return finish(Outcome.SUCCEEDED, value, code, c.lastResort(), attempts, skipped, null, start, feature);
			} catch (ProviderException e) {
				Duration latency = Duration.ofNanos(System.nanoTime() - t0);
				Kind kind = e.kind();
				attempts.add(new Attempt(number, code, c.lastResort(), latency, kind));
				metrics.attempt(code, feature, kind.name(), latency);
				log.warn("AI provider {} attempt {} failed: {}", code, number, kind);
				switch (kind) {
					case CANCELLED -> {
						breaker.release();
						return finish(Outcome.CANCELLED, null, null, false, attempts, skipped, kind, start, feature);
					}
					case CONTENT_REJECTED -> {
						breaker.release();
						return finish(Outcome.CONTENT_REJECTED, null, null, false, attempts, skipped, kind, start,
								feature);
					}
					case UNSUPPORTED_LANGUAGE_PAIR -> breaker.release();
					case QUOTA_EXHAUSTED_AT_PROVIDER -> {
						breaker.recordFailure();
						Duration wait = e instanceof ProviderException.QuotaExhaustedAtProvider q
								&& q.retryAfter() != null ? q.retryAfter() : breakers.openDuration();
						breaker.trip(wait);
						lastHealthKind = kind;
					}
					case AUTHENTICATION_FAILED -> {
						breaker.recordFailure();
						breaker.trip(breakers.openDuration());
						log.error("AI provider {} refused our credentials; breaker tripped", code);
						lastHealthKind = kind;
					}
					default -> {
						if (kind == Kind.TIMEOUT && shortened) {
							breaker.release(); // our deadline cut the call short, not the provider being slow
						} else {
							breaker.recordFailure();
						}
						lastHealthKind = kind;
					}
				}
			} catch (RuntimeException e) {
				// An adapter broke the SPI contract (only ProviderException may escape): treat it as the provider being
				// unavailable and log the class only, never the message, which could carry request data.
				Duration latency = Duration.ofNanos(System.nanoTime() - t0);
				breaker.recordFailure();
				attempts.add(new Attempt(number, code, c.lastResort(), latency, Kind.UNAVAILABLE));
				metrics.attempt(code, feature, Kind.UNAVAILABLE.name(), latency);
				log.error("AI provider {} attempt {} threw {}", code, number, e.getClass().getSimpleName());
				lastHealthKind = Kind.UNAVAILABLE;
			}
		}

		if (!deadlineHit && !attempts.isEmpty() && System.nanoTime() - start >= deadlineNanos) {
			deadlineHit = true;
		}
		Outcome outcome;
		Kind finalKind;
		if (deadlineHit) {
			outcome = Outcome.PROVIDER_TIMEOUT;
			finalKind = Kind.TIMEOUT;
		} else if (lastHealthKind != null) {
			outcome = lastHealthKind == Kind.TIMEOUT ? Outcome.PROVIDER_TIMEOUT : Outcome.PROVIDER_UNAVAILABLE;
			finalKind = lastHealthKind;
		} else if (!attempts.isEmpty()) {
			outcome = Outcome.LANGUAGE_PAIR_UNAVAILABLE; // every call said it does not serve the pair
			finalKind = Kind.UNSUPPORTED_LANGUAGE_PAIR;
		} else {
			outcome = Outcome.PROVIDER_UNAVAILABLE; // every candidate was gated
			finalKind = null;
		}
		return finish(outcome, null, null, false, attempts, skipped, finalKind, start, feature);
	}

	private Optional<RoutingPolicy.Plan> plan(RouteQuery query) {
		try {
			return policies.planFor(query);
		} catch (RuntimeException e) {
			log.warn("Routing policies unreadable, using registry order: {}", e.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	/** Applies the policy order and allow-list; registry (code) order when no policy row matches. */
	private static List<ResolvedProvider> order(List<ResolvedProvider> resolved, Optional<RoutingPolicy.Plan> plan) {
		if (plan.isEmpty()) {
			return resolved;
		}
		List<String> order = plan.get().providers();
		return resolved.stream().filter(p -> order.contains(p.code()))
				.filter(p -> !plan.get().zeroRetentionOnly().contains(p.code())
						|| p.descriptor().dataPolicy().zeroDataRetention())
				.sorted(Comparator.comparingInt(p -> order.indexOf(p.code()))).toList();
	}

	/** Why nothing can even be tried: ZDR gap, no capability for the pair, or a policy that names nobody usable. */
	private Outcome noRoute(RouteQuery query, List<ResolvedProvider> active, List<ResolvedProvider> lastResort) {
		if (active.isEmpty() && lastResort.isEmpty()) {
			if (query.dataPolicy().zeroRetentionRequired()) {
				RouteQuery relaxed = query.withDataPolicy(DataPolicyRequirement.STANDARD);
				if (!registry.resolve(relaxed).isEmpty() || !registry.resolveLastResort(relaxed).isEmpty()) {
					return Outcome.ZERO_RETENTION_UNAVAILABLE;
				}
			}
			return Outcome.LANGUAGE_PAIR_UNAVAILABLE;
		}
		return Outcome.PROVIDER_UNAVAILABLE;
	}

	private void book(String code, RouteQuery query, ProviderUsage usage) {
		try {
			double micros = cost.record(code, query.feature(), usage).doubleValue();
			if (micros > 0) {
				metrics.cost(code, micros);
			}
		} catch (RuntimeException e) {
			log.warn("Cost accounting failed for provider {}: {}", code, e.getClass().getSimpleName());
		}
	}

	private void skip(List<Skip> skipped, String code, SkipReason reason) {
		skipped.add(new Skip(code, reason));
		metrics.skipped(code, reason.name());
	}

	private <T> RouteResult<T> finish(Outcome outcome, T value, String code, boolean lastResort, List<Attempt> attempts,
			List<Skip> skipped, Kind kind, long start, String feature) {
		metrics.outcome(feature, outcome.name());
		return new RouteResult<>(outcome, value, code, lastResort, List.copyOf(attempts), List.copyOf(skipped), kind,
				Duration.ofNanos(System.nanoTime() - start));
	}
}
