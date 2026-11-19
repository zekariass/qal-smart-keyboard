package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.aigateway.ProviderRegistryTest.MutableClock;
import com.qalsmartkeyboard.aigateway.RouteResult.Outcome;
import com.qalsmartkeyboard.aigateway.RouteResult.SkipReason;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.Behavior;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.FailureMode;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreaker;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreakers;
import com.qalsmartkeyboard.aigateway.routing.CostControl;
import com.qalsmartkeyboard.aigateway.routing.CostStore;
import com.qalsmartkeyboard.aigateway.routing.KillSwitch;
import com.qalsmartkeyboard.aigateway.routing.RoutingMetrics;
import com.qalsmartkeyboard.aigateway.routing.RoutingPolicies;
import com.qalsmartkeyboard.aigateway.routing.RoutingPolicy;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.DataPolicy;
import com.qalsmartkeyboard.aigateway.spi.DataPolicyRequirement;
import com.qalsmartkeyboard.aigateway.spi.ProviderDescriptor;
import com.qalsmartkeyboard.aigateway.spi.ProviderException;
import com.qalsmartkeyboard.aigateway.spi.ProviderException.Kind;
import com.qalsmartkeyboard.aigateway.spi.ProviderStatus;
import com.qalsmartkeyboard.aigateway.spi.QualityTier;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateResult;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The B5.2 failover test: two mock providers (alpha is tried first by registry order, beta is the fallback) through the
 * real registry, router, breaker, kill switch and cost control with an injected clock and in-memory stores. No database.
 */
class AiRouterTest {

	static final DataPolicy CLEAN = new DataPolicy(false, false, Set.of("eu"), true);
	static final DataPolicy ZDR = new DataPolicy(true, false, Set.of("eu"), true);

	MutableClock clock;
	MockAiProvider alpha;
	MockAiProvider beta;
	List<ProviderDescriptor> descriptors;
	List<RoutingPolicy> policyRows;
	AtomicReference<KillSwitch.Switches> switches;
	MemoryCostStore costStore;
	SimpleMeterRegistry meters;
	CircuitBreakers breakers;
	AiRouter router;

	static ProviderDescriptor descriptor(String code, ProviderStatus status, DataPolicy policy) {
		return ProviderRegistryTest.provider(code, status, policy,
				ProviderRegistryTest.translate("en", "am", QualityTier.BETA, true, null));
	}

	@BeforeEach
	void setUp() {
		clock = new MutableClock();
		alpha = new MockAiProvider("alpha", Behavior.ok());
		beta = new MockAiProvider("beta", Behavior.ok());
		descriptors = new ArrayList<>(List.of(descriptor("alpha", ProviderStatus.ACTIVE, CLEAN),
				descriptor("beta", ProviderStatus.ACTIVE, ZDR)));
		policyRows = new ArrayList<>();
		switches = new AtomicReference<>(KillSwitch.Switches.NONE);
		costStore = new MemoryCostStore();
		meters = new SimpleMeterRegistry();
		build(Set.of(), Set.of());
	}

	/** Window 4, at least 2 calls, 50 percent failures open it for 30 s, 2 probes; 1 s attempts inside a 3 s deadline. */
	void build(Set<String> killedProviders, Set<String> killedFeatures) {
		ProviderRegistry registry = new ProviderRegistry(() -> descriptors, List.of(alpha, beta), Duration.ZERO, clock);
		RoutingMetrics metrics = new RoutingMetrics(meters);
		breakers = new CircuitBreakers(new CircuitBreaker.Config(4, 2, 50, Duration.ofSeconds(3), 80,
				Duration.ofSeconds(30), 2), clock, metrics::circuitTransition);
		router = new AiRouter(registry, new RoutingPolicies(() -> policyRows, Duration.ZERO, clock, () -> 0.5),
				new KillSwitch(switches::get, killedProviders, killedFeatures, Duration.ZERO, clock), breakers,
				new CostControl(costStore, Duration.ZERO, clock, a -> metrics.costAlert(a.providerCode(), a.level().name())),
				new AiRouter.Settings(3, Duration.ofSeconds(1), Duration.ofSeconds(3)), metrics);
	}

	RouteResult<TranslateResult> translate() {
		return router.route(RouteRequest.translate(TranslateRequest.of("hello", "en", "am"), null, null));
	}

	// ------------------------------------------------------------------------------------------------ ADR 0027

	@Test
	void aRequestWithALongerAttemptReportsAScaledLatencyToTheBreaker() {
		// configured attempt 1 s: a 1 s call is as slow as a 5 s call of a request allowed 5 s per attempt
		assertThat(router.healthLatency(Duration.ofMillis(800), Duration.ofSeconds(1))).isEqualTo(Duration.ofMillis(800));
		assertThat(router.healthLatency(Duration.ofMillis(800), Duration.ofMillis(500)))
				.isEqualTo(Duration.ofMillis(800));
		assertThat(router.healthLatency(Duration.ofSeconds(5), Duration.ofSeconds(5))).isEqualTo(Duration.ofSeconds(1));
		assertThat(router.healthLatency(Duration.ofSeconds(12), Duration.ofSeconds(20)))
				.isEqualTo(Duration.ofMillis(600));
	}

	@Test
	void aRequestMayAllowALongerAttemptThanTheConfiguredOne() {
		alpha.setBehavior(Behavior.slow(Duration.ofMillis(1300)));
		RouteRequest<TranslateResult> base = RouteRequest.translate(TranslateRequest.of("hello", "en", "am"), null,
				null);
		// the configured 1 s attempt cuts alpha off
		assertThat(router.route(base.pinnedTo("alpha")).succeeded()).isFalse();
		breakers.resetAll();
		// a 2 s attempt (an e-mail draft, say) lets alpha finish
		RouteResult<TranslateResult> longer = router.route(base.withLimits(Duration.ofSeconds(3),
				Duration.ofSeconds(2), 0).pinnedTo("alpha"));
		assertThat(longer.succeeded()).isTrue();
		assertThat(longer.providerCode()).isEqualTo("alpha");
	}

	// ------------------------------------------------------------------------------------------------ B5.3 filters

	@Test
	void requestFiltersPinOrExcludeProvidersAndTheServedCapabilityIsReported() {
		RouteRequest<TranslateResult> base = RouteRequest.translate(TranslateRequest.of("hello", "en", "am"), null,
				null);
		RouteResult<TranslateResult> pinned = router.route(base.pinnedTo("beta"));
		assertThat(pinned.providerCode()).isEqualTo("beta");
		assertThat(pinned.capability().qualityTier()).isEqualTo(QualityTier.BETA);
		assertThat(alpha.callCount()).isZero();

		alpha.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		RouteResult<TranslateResult> pinnedFailing = router.route(base.pinnedTo("alpha"));
		assertThat(pinnedFailing.outcome()).isEqualTo(Outcome.PROVIDER_UNAVAILABLE);
		assertThat(pinnedFailing.attempts()).extracting(RouteResult.Attempt::providerCode).containsExactly("alpha");
		assertThat(pinnedFailing.capability()).isNull();
		assertThat(beta.callCount()).isEqualTo(1);

		alpha.setBehavior(Behavior.ok());
		assertThat(router.route(base.excluding(List.of("alpha"))).providerCode()).isEqualTo("beta");
		RouteResult<TranslateResult> none = router.route(base.excluding(List.of("alpha", "beta")));
		assertThat(none.outcome()).isEqualTo(Outcome.PROVIDER_UNAVAILABLE);
		assertThat(none.attempts()).isEmpty();
	}

	// ------------------------------------------------------------------------------------------------ failover

	@Test
	void healthyPrimaryServesAndFallbackIsNotTouched() {
		RouteResult<TranslateResult> r = translate();
		assertThat(r.succeeded()).isTrue();
		assertThat(r.providerCode()).isEqualTo("alpha");
		assertThat(r.attempts()).hasSize(1);
		assertThat(r.attempts().get(0).errorKind()).isNull();
		assertThat(r.attempts().get(0).latency()).isNotNull();
		assertThat(r.contractCode()).isNull();
		assertThat(r.value().translatedText().value()).isEqualTo("[mock:am] hello");
		assertThat(beta.callCount()).isZero();
	}

	@Test
	void unavailableOrTimingOutPrimaryFailsOverToTheFallback() {
		for (FailureMode mode : List.of(FailureMode.UNAVAILABLE, FailureMode.TIMEOUT, FailureMode.INVALID_RESPONSE)) {
			setUp(); // fresh breakers and counters per failure kind
			alpha.setBehavior(Behavior.failing(mode));
			RouteResult<TranslateResult> r = translate();
			assertThat(r.succeeded()).as(mode.name()).isTrue();
			assertThat(r.providerCode()).isEqualTo("beta");
			assertThat(r.attempts()).extracting(a -> a.providerCode()).containsExactly("alpha", "beta");
			assertThat(r.attempts().get(0).errorKind()).isNotNull();
			assertThat(r.attempts().get(1).errorKind()).isNull();
			assertThat(r.servedByLastResort()).isFalse();
			assertThat(meters.get("qal.ai.route.failovers").tag("feature", "TRANSLATE").counter().count())
					.isEqualTo(1.0);
		}
	}

	@Test
	void breakerOpensAfterTheConfiguredFailuresAndRoutesStraightToTheFallback() {
		alpha.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		translate();
		translate(); // minimumCalls = 2, both failed: open
		assertThat(breakers.forProvider("alpha").state()).isEqualTo(CircuitBreaker.State.OPEN);
		int callsBefore = alpha.callCount();

		RouteResult<TranslateResult> r = translate();
		assertThat(r.providerCode()).isEqualTo("beta");
		assertThat(r.attempts()).hasSize(1);
		assertThat(r.skipped()).containsExactly(new RouteResult.Skip("alpha", SkipReason.CIRCUIT_OPEN));
		assertThat(alpha.callCount()).isEqualTo(callsBefore); // the open breaker shields the provider
		assertThat(meters.get("qal.ai.circuit.transitions").tag("provider", "alpha").tag("to", "OPEN").counter().count())
				.isEqualTo(1.0);
		assertThat(meters.get("qal.ai.circuit.state").tag("provider", "alpha").gauge().value()).isEqualTo(1.0);
	}

	@Test
	void halfOpenProbesRecoverTheProviderOrReopenTheBreaker() {
		alpha.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		translate();
		translate();
		assertThat(breakers.forProvider("alpha").state()).isEqualTo(CircuitBreaker.State.OPEN);

		clock.advance(Duration.ofSeconds(29));
		assertThat(translate().skipped()).hasSize(1); // still open

		// the wait is over but the provider is still down: the probe fails, the fallback serves, the breaker reopens
		clock.advance(Duration.ofSeconds(2));
		RouteResult<TranslateResult> failedProbe = translate();
		assertThat(failedProbe.attempts()).extracting(a -> a.providerCode()).containsExactly("alpha", "beta");
		assertThat(breakers.forProvider("alpha").state()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(translate().skipped()).hasSize(1);

		// recovered: two successful probes close it
		alpha.setBehavior(Behavior.ok());
		clock.advance(Duration.ofSeconds(31));
		assertThat(translate().providerCode()).isEqualTo("alpha");
		assertThat(breakers.forProvider("alpha").state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
		assertThat(translate().providerCode()).isEqualTo("alpha");
		assertThat(breakers.forProvider("alpha").state()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(translate().skipped()).isEmpty();
	}

	@Test
	void slowCallsOpenTheBreakerEvenWhenTheyStillSucceed() {
		alpha.setBehavior(Behavior.slow(Duration.ofMillis(60)));
		breakers = new CircuitBreakers(new CircuitBreaker.Config(4, 2, 50, Duration.ofMillis(20), 80,
				Duration.ofSeconds(30), 2), clock, null);
		router = new AiRouter(new ProviderRegistry(() -> descriptors, List.of(alpha, beta), Duration.ZERO, clock),
				new RoutingPolicies(() -> policyRows, Duration.ZERO, clock, () -> 0.5),
				new KillSwitch(switches::get, Set.of(), Set.of(), Duration.ZERO, clock), breakers,
				new CostControl(costStore, Duration.ZERO, clock, null),
				new AiRouter.Settings(3, Duration.ofSeconds(1), Duration.ofSeconds(3)), new RoutingMetrics(meters));
		assertThat(translate().providerCode()).isEqualTo("alpha");
		assertThat(translate().providerCode()).isEqualTo("alpha");
		assertThat(breakers.forProvider("alpha").state()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(translate().providerCode()).isEqualTo("beta");
	}

	// ----------------------------------------------------------------------------------- fallback rules by kind

	@Test
	void contentRejectedEndsTheRouteWithoutFallbackOrHealthImpact() {
		alpha.setBehavior(Behavior.failing(FailureMode.CONTENT_REJECTED));
		for (int i = 0; i < 3; i++) {
			RouteResult<TranslateResult> r = translate();
			assertThat(r.outcome()).isEqualTo(Outcome.CONTENT_REJECTED);
			assertThat(r.contractCode()).isEqualTo("CONTENT_REJECTED");
			assertThat(r.finalErrorKind()).isEqualTo(Kind.CONTENT_REJECTED);
			assertThat(r.value()).isNull();
		}
		assertThat(beta.callCount()).isZero();
		assertThat(breakers.forProvider("alpha").state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void providerRefusingThePairFallsThroughToTheNextWithoutHealthImpactAndFailsAsPairUnavailableWhenAllRefuse() {
		alpha.setBehavior(Behavior.failing(FailureMode.UNSUPPORTED_LANGUAGE_PAIR));
		RouteResult<TranslateResult> r = translate();
		assertThat(r.providerCode()).isEqualTo("beta");
		assertThat(breakers.forProvider("alpha").state()).isEqualTo(CircuitBreaker.State.CLOSED);

		beta.setBehavior(Behavior.failing(FailureMode.UNSUPPORTED_LANGUAGE_PAIR));
		r = translate();
		assertThat(r.outcome()).isEqualTo(Outcome.LANGUAGE_PAIR_UNAVAILABLE);
		assertThat(r.contractCode()).isEqualTo("LANGUAGE_PAIR_UNAVAILABLE");
		assertThat(r.attempts()).hasSize(2);
	}

	@Test
	void quotaExhaustedAndAuthenticationFailedFallBackAndTripTheBreakerAtOnce() {
		alpha.setBehavior(Behavior.failing(FailureMode.QUOTA_EXHAUSTED));
		assertThat(translate().providerCode()).isEqualTo("beta");
		assertThat(breakers.forProvider("alpha").state()).isEqualTo(CircuitBreaker.State.OPEN); // one failure only
		clock.advance(Duration.ofSeconds(61)); // the mock reports a vendor Retry-After of 60 s, which the breaker honours
		assertThat(breakers.forProvider("alpha").tryAcquire()).isTrue();
		breakers.forProvider("alpha").release();

		AiRouterTest fresh = new AiRouterTest();
		fresh.setUp();
		fresh.alpha.setBehavior(Behavior.failing(FailureMode.AUTHENTICATION_FAILED));
		assertThat(fresh.translate().providerCode()).isEqualTo("beta");
		assertThat(fresh.breakers.forProvider("alpha").state()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void allProvidersFailingYieldsTheContractCodeOfTheLastFailure() {
		alpha.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		beta.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		RouteResult<TranslateResult> r = translate();
		assertThat(r.outcome()).isEqualTo(Outcome.PROVIDER_UNAVAILABLE);
		assertThat(r.contractCode()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(r.finalErrorKind()).isEqualTo(Kind.UNAVAILABLE);
		assertThat(r.attempts()).hasSize(2);

		alpha.setBehavior(Behavior.failing(FailureMode.TIMEOUT));
		beta.setBehavior(Behavior.failing(FailureMode.TIMEOUT));
		r = translate();
		assertThat(r.outcome()).isEqualTo(Outcome.PROVIDER_TIMEOUT);
		assertThat(r.contractCode()).isEqualTo("PROVIDER_TIMEOUT");
	}

	@Test
	void anAdapterThrowingARawExceptionCountsAsUnavailableAndFailsOver() {
		ProviderRegistry registry = new ProviderRegistry(() -> descriptors, List.of(new ThrowingProvider("alpha"), beta), Duration.ZERO, clock);
		AiRouter r = new AiRouter(registry, new RoutingPolicies(() -> policyRows, Duration.ZERO, clock, () -> 0.5),
				new KillSwitch(switches::get, Set.of(), Set.of(), Duration.ZERO, clock), breakers,
				new CostControl(costStore, Duration.ZERO, clock, null),
				new AiRouter.Settings(3, Duration.ofSeconds(1), Duration.ofSeconds(3)), new RoutingMetrics(meters));
		RouteResult<TranslateResult> result = r
				.route(RouteRequest.translate(TranslateRequest.of("hello", "en", "am"), null, null));
		assertThat(result.providerCode()).isEqualTo("beta");
		assertThat(result.attempts().get(0).errorKind()).isEqualTo(Kind.UNAVAILABLE);
	}

	static final class ThrowingProvider implements com.qalsmartkeyboard.aigateway.spi.AiProvider {
		private final String code;

		ThrowingProvider(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

		@Override
		public TranslateResult translate(TranslateRequest request, Duration timeout) {
			throw new IllegalStateException("boom " + request.text().value());
		}
	}

	@Test
	void interruptIsAClientAbortAndNeverFallsBack() throws Exception {
		alpha.setBehavior(Behavior.slow(Duration.ofSeconds(5)));
		AtomicReference<RouteResult<TranslateResult>> out = new AtomicReference<>();
		Thread t = Thread.ofVirtual().start(() -> out.set(translate()));
		Thread.sleep(150);
		t.interrupt();
		t.join(3000);
		assertThat(out.get().outcome()).isEqualTo(Outcome.CANCELLED);
		assertThat(out.get().contractCode()).isNull();
		assertThat(beta.callCount()).isZero();
		assertThat(breakers.forProvider("alpha").state()).isEqualTo(CircuitBreaker.State.CLOSED);

		Thread.currentThread().interrupt();
		try {
			assertThat(translate().outcome()).isEqualTo(Outcome.CANCELLED);
			assertThat(alpha.callCount()).isEqualTo(1);
		} finally {
			Thread.interrupted();
		}
	}

	// --------------------------------------------------------------------------------- deadline and attempts

	@Test
	void overallDeadlineIsRespectedAndShortenedAttemptsDoNotBlameTheProvider() {
		alpha.setBehavior(Behavior.slow(Duration.ofSeconds(5)));
		beta.setBehavior(Behavior.slow(Duration.ofSeconds(5)));
		RouteRequest<TranslateResult> req = RouteRequest
				.translate(TranslateRequest.of("hello", "en", "am"), null, null)
				.withLimits(Duration.ofMillis(500), Duration.ofMillis(300), 0);
		long t0 = System.nanoTime();
		RouteResult<TranslateResult> r = router.route(req);
		Duration took = Duration.ofNanos(System.nanoTime() - t0);
		assertThat(r.outcome()).isEqualTo(Outcome.PROVIDER_TIMEOUT);
		assertThat(r.contractCode()).isEqualTo("PROVIDER_TIMEOUT");
		assertThat(r.attempts()).hasSize(2); // 300 ms, then the 200 ms that were left
		assertThat(took).isLessThan(Duration.ofMillis(900));
		// beta's attempt was cut short by the deadline, which says nothing about beta's health
		clock.advance(Duration.ZERO);
		assertThat(breakers.forProvider("beta").state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void maxAttemptsBoundsTheFailover() {
		descriptors.add(descriptor("gamma", ProviderStatus.ACTIVE, CLEAN));
		MockAiProvider gamma = new MockAiProvider("gamma", Behavior.ok());
		alpha.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		beta.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		ProviderRegistry registry = new ProviderRegistry(() -> descriptors, List.of(alpha, beta, gamma), Duration.ZERO, clock);
		AiRouter r = new AiRouter(registry, new RoutingPolicies(() -> policyRows, Duration.ZERO, clock, () -> 0.5),
				new KillSwitch(switches::get, Set.of(), Set.of(), Duration.ZERO, clock), breakers,
				new CostControl(costStore, Duration.ZERO, clock, null),
				new AiRouter.Settings(2, Duration.ofSeconds(1), Duration.ofSeconds(3)), new RoutingMetrics(meters));
		RouteResult<TranslateResult> result = r
				.route(RouteRequest.translate(TranslateRequest.of("hello", "en", "am"), null, null));
		assertThat(result.outcome()).isEqualTo(Outcome.PROVIDER_UNAVAILABLE);
		assertThat(result.attempts()).hasSize(2);
		assertThat(gamma.callCount()).isZero();
	}

	// ------------------------------------------------------------------------------------------ kill switches

	@Test
	void providerKillSwitchRemovesTheProviderImmediatelyAndRestoresItWhenCleared() {
		assertThat(translate().providerCode()).isEqualTo("alpha");
		switches.set(new KillSwitch.Switches(Set.of("ALPHA"), Set.of()));
		RouteResult<TranslateResult> r = translate(); // no restart, no cache wait (TTL zero here; 5 s by default)
		assertThat(r.providerCode()).isEqualTo("beta");
		assertThat(r.skipped()).containsExactly(new RouteResult.Skip("alpha", SkipReason.KILL_SWITCH_PROVIDER));
		assertThat(alpha.callCount()).isEqualTo(1);
		switches.set(KillSwitch.Switches.NONE);
		assertThat(translate().providerCode()).isEqualTo("alpha");
	}

	@Test
	void killingEveryProviderOrTheFeatureYieldsProviderUnavailableWithoutCalls() {
		switches.set(new KillSwitch.Switches(Set.of("alpha", "beta"), Set.of()));
		RouteResult<TranslateResult> r = translate();
		assertThat(r.outcome()).isEqualTo(Outcome.PROVIDER_UNAVAILABLE);
		assertThat(r.attempts()).isEmpty();
		assertThat(r.skipped()).hasSize(2);

		switches.set(new KillSwitch.Switches(Set.of(), Set.of("translate")));
		r = translate();
		assertThat(r.outcome()).isEqualTo(Outcome.PROVIDER_UNAVAILABLE);
		assertThat(r.skipped()).containsExactly(new RouteResult.Skip(null, SkipReason.KILL_SWITCH_FEATURE));
		assertThat(alpha.callCount() + beta.callCount()).isZero();
	}

	@Test
	void configurationKillSwitchAppliesAndAnUnreadableDatabaseKeepsTheLastSnapshot() {
		build(Set.of("alpha"), Set.of());
		assertThat(translate().providerCode()).isEqualTo("beta");

		build(Set.of(), Set.of());
		switches.set(new KillSwitch.Switches(Set.of("alpha"), Set.of()));
		assertThat(translate().providerCode()).isEqualTo("beta");
		boolean[] fail = { false };
		KillSwitch ks = new KillSwitch(() -> {
			if (fail[0]) {
				throw new IllegalStateException("db down");
			}
			return new KillSwitch.Switches(Set.of("alpha"), Set.of());
		}, Set.of(), Set.of(), Duration.ZERO, clock);
		assertThat(ks.providerKilled("alpha")).isTrue();
		fail[0] = true;
		assertThat(ks.providerKilled("alpha")).as("stale snapshot").isTrue();
		assertThat(new KillSwitch(() -> {
			throw new IllegalStateException("db down");
		}, Set.of(), Set.of(), Duration.ZERO, clock).providerKilled("alpha")).as("never loaded: fail open").isFalse();
	}

	// ----------------------------------------------------------------------------------------------- cost cap

	@Test
	void hardCostCapBlocksTheProviderSoftLimitAlertsOnceAndNextDayResets() {
		costStore.prices.put("alpha", List.of(new CostStore.Price(null, CostStore.PriceUnit.INPUT_CHARACTER,
				new BigDecimal("20")))); // 20 micro-USD per character; "hello" = 100 micros per call
		costStore.budgets.put("alpha", new CostStore.Budget(new BigDecimal("150"), new BigDecimal("250")));

		assertThat(translate().providerCode()).isEqualTo("alpha"); // 100
		assertThat(meters.find("qal.ai.cost.alerts").counters()).isEmpty();
		assertThat(translate().providerCode()).isEqualTo("alpha"); // 200: soft limit crossed
		assertThat(meters.get("qal.ai.cost.alerts").tag("level", "SOFT").counter().count()).isEqualTo(1.0);
		assertThat(translate().providerCode()).isEqualTo("alpha"); // 300: hard limit crossed, soft not repeated
		assertThat(meters.get("qal.ai.cost.alerts").tag("level", "HARD").counter().count()).isEqualTo(1.0);
		assertThat(meters.get("qal.ai.cost.alerts").tag("level", "SOFT").counter().count()).isEqualTo(1.0);

		RouteResult<TranslateResult> r = translate();
		assertThat(r.providerCode()).isEqualTo("beta");
		assertThat(r.skipped()).containsExactly(new RouteResult.Skip("alpha", SkipReason.COST_CAP));
		assertThat(alpha.callCount()).isEqualTo(3);
		assertThat(meters.get("qal.ai.cost.spend.micros").tag("provider", "alpha").gauge().value()).isEqualTo(300.0);
		assertThat(costStore.spend.get("alpha")).isEqualByComparingTo("300");

		// raising the limit reopens the provider; so does the next UTC day
		costStore.budgets.put("alpha", new CostStore.Budget(null, new BigDecimal("1000")));
		assertThat(translate().providerCode()).isEqualTo("alpha");
		costStore.budgets.put("alpha", new CostStore.Budget(null, new BigDecimal("100")));
		assertThat(translate().providerCode()).isEqualTo("beta");
		clock.advance(Duration.ofDays(1));
		assertThat(translate().providerCode()).isEqualTo("alpha");
	}

	@Test
	void failedCallsCostNothingAndUnpricedOrUncappedProvidersAreNeverBlocked() {
		costStore.prices.put("alpha", List.of(new CostStore.Price(null, CostStore.PriceUnit.REQUEST, BigDecimal.TEN)));
		costStore.budgets.put("alpha", new CostStore.Budget(null, new BigDecimal("15")));
		alpha.setBehavior(Behavior.failing(FailureMode.CONTENT_REJECTED));
		translate();
		assertThat(costStore.spend).isEmpty();
		alpha.setBehavior(Behavior.ok());
		translate(); // 10
		translate(); // 20 >= 15
		assertThat(translate().providerCode()).isEqualTo("beta");
		for (int i = 0; i < 5; i++) {
			assertThat(translate().providerCode()).isEqualTo("beta"); // beta has no price and no budget
		}
		assertThat(costStore.spend).doesNotContainKey("beta");
	}

	@Test
	void featureSpecificPriceOverridesTheGenericOne() {
		CostControl cost = new CostControl(costStore, Duration.ZERO, clock, null);
		costStore.prices.put("alpha", List.of(new CostStore.Price(null, CostStore.PriceUnit.INPUT_CHARACTER, BigDecimal.ONE),
				new CostStore.Price("TRANSLATE", CostStore.PriceUnit.INPUT_CHARACTER, new BigDecimal("3")),
				new CostStore.Price(null, CostStore.PriceUnit.REQUEST, new BigDecimal("0.5")),
				new CostStore.Price(null, CostStore.PriceUnit.OUTPUT_TOKEN, new BigDecimal("2"))));
		var usage = new com.qalsmartkeyboard.aigateway.spi.ProviderUsage(10, 20, 4L, 7L, null);
		assertThat(cost.estimate("alpha", AiFeature.TRANSLATE, usage)).isEqualByComparingTo("44.5"); // 30 + 0.5 + 14
		assertThat(cost.estimate("alpha", AiFeature.REWRITE, usage)).isEqualByComparingTo("24.5"); // 10 + 0.5 + 14
		assertThat(cost.estimate("beta", AiFeature.TRANSLATE, usage)).isEqualByComparingTo("0");
	}

	// ------------------------------------------------------------------------------------- data policy, tiers

	@Test
	void zeroRetentionRequirementLeavesOnlyZdrProvidersAndYieldsZeroRetentionUnavailableWhenNone() {
		RouteRequest<TranslateResult> zdr = RouteRequest.translate(TranslateRequest.of("hello", "en", "am"), null,
				DataPolicyRequirement.ZERO_RETENTION);
		RouteResult<TranslateResult> r = router.route(zdr);
		assertThat(r.providerCode()).isEqualTo("beta"); // alpha is first by order but not ZDR
		assertThat(alpha.callCount()).isZero();

		beta.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE)); // ZDR provider down: fail, never leak to alpha
		r = router.route(zdr);
		assertThat(r.outcome()).isEqualTo(Outcome.PROVIDER_UNAVAILABLE);
		assertThat(alpha.callCount()).isZero();

		descriptors.set(1, descriptor("beta", ProviderStatus.ACTIVE, CLEAN)); // no ZDR provider at all
		r = router.route(zdr);
		assertThat(r.outcome()).isEqualTo(Outcome.ZERO_RETENTION_UNAVAILABLE);
		assertThat(r.contractCode()).isEqualTo("ZERO_RETENTION_UNAVAILABLE");
		assertThat(r.attempts()).isEmpty();
		assertThat(alpha.callCount()).isZero();
	}

	@Test
	void noCapabilityForThePairIsLanguagePairUnavailable() {
		RouteResult<TranslateResult> r = router
				.route(RouteRequest.translate(TranslateRequest.of("hello", "en", "ti"), null, null));
		assertThat(r.outcome()).isEqualTo(Outcome.LANGUAGE_PAIR_UNAVAILABLE);
		assertThat(r.contractCode()).isEqualTo("LANGUAGE_PAIR_UNAVAILABLE");
		r = router.route(RouteRequest.translate(TranslateRequest.of("hello", "en", "am"), QualityTier.PRODUCTION, null));
		assertThat(r.outcome()).isEqualTo(Outcome.LANGUAGE_PAIR_UNAVAILABLE); // both are BETA only
	}

	// ----------------------------------------------------------------------------------------------- DEGRADED

	@Test
	void degradedProviderIsUsedOnlyWhenNoActiveProviderFits() {
		descriptors.set(0, descriptor("alpha", ProviderStatus.DEGRADED, CLEAN)); // alpha is DEGRADED, beta ACTIVE
		RouteResult<TranslateResult> r = translate();
		assertThat(r.providerCode()).isEqualTo("beta");
		assertThat(r.servedByLastResort()).isFalse();
		assertThat(alpha.callCount()).isZero(); // healthy ACTIVE provider: the DEGRADED one is not even tried

		// the ACTIVE provider cannot serve: now the DEGRADED one is the last resort
		beta.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		r = translate();
		assertThat(r.providerCode()).isEqualTo("alpha");
		assertThat(r.servedByLastResort()).isTrue();
		assertThat(r.attempts()).extracting(a -> a.providerCode()).containsExactly("beta", "alpha");
		assertThat(r.attempts().get(1).lastResort()).isTrue();

		// no ACTIVE provider at all
		descriptors.set(1, descriptor("beta", ProviderStatus.DISABLED, CLEAN));
		r = translate();
		assertThat(r.providerCode()).isEqualTo("alpha");
		assertThat(r.servedByLastResort()).isTrue();
		assertThat(r.attempts()).hasSize(1);
	}

	@Test
	void degradedLastResortStillHonoursTheDataPolicyAndDisabledStaysOut() {
		descriptors.set(0, descriptor("alpha", ProviderStatus.DEGRADED, CLEAN)); // not ZDR
		descriptors.set(1, descriptor("beta", ProviderStatus.DISABLED, ZDR));
		RouteResult<TranslateResult> r = router.route(RouteRequest.translate(TranslateRequest.of("hello", "en", "am"),
				null, DataPolicyRequirement.ZERO_RETENTION));
		assertThat(r.outcome()).isEqualTo(Outcome.ZERO_RETENTION_UNAVAILABLE);
		assertThat(translate().providerCode()).isEqualTo("alpha");
		assertThat(beta.callCount()).isZero();
	}

	// ---------------------------------------------------------------------------------------------- policies

	static RoutingPolicy row(String src, String dst, String provider, int priority, int weight, boolean zdr) {
		return new RoutingPolicy(AiFeature.TRANSLATE, src, dst, provider, priority, weight, zdr,
				Instant.parse("2026-01-01T00:00:00Z"), null);
	}

	@Test
	void policyRowsOrderTheProvidersAndActAsAnAllowList() {
		policyRows.add(row("en", "am", "beta", 10, 100, false));
		policyRows.add(row("en", "am", "alpha", 20, 100, false));
		RouteResult<TranslateResult> r = translate();
		assertThat(r.providerCode()).isEqualTo("beta"); // policy beats registry (code) order
		beta.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		assertThat(translate().attempts()).extracting(a -> a.providerCode()).containsExactly("beta", "alpha");

		policyRows.remove(1); // alpha no longer named: the policy is an allow-list
		clock.advance(Duration.ofSeconds(31)); // beta's breaker (opened by the failure above) lets a probe through
		RouteResult<TranslateResult> only = translate();
		assertThat(only.outcome()).isEqualTo(Outcome.PROVIDER_UNAVAILABLE);
		assertThat(only.attempts()).extracting(a -> a.providerCode()).containsExactly("beta");
	}

	@Test
	void policyNamingAProviderTheRegistryRefusesNeverRoutesToIt() {
		descriptors.set(1, descriptor("beta", ProviderStatus.DISABLED, ZDR));
		policyRows.add(row("en", "am", "beta", 1, 100, false));
		policyRows.add(row("en", "am", "alpha", 2, 100, false));
		assertThat(translate().providerCode()).isEqualTo("alpha");
		assertThat(beta.callCount()).isZero();
	}

	@Test
	void noMatchingPolicyFallsBackToRegistryOrder() {
		policyRows.add(row("en", "ti", "beta", 1, 100, false)); // other pair
		policyRows.add(new RoutingPolicy(AiFeature.REWRITE, null, null, "beta", 1, 100, false,
				Instant.parse("2026-01-01T00:00:00Z"), null));
		assertThat(translate().providerCode()).isEqualTo("alpha");
	}

	@Test
	void expiredOrZeroWeightRowsAreIgnored() {
		policyRows.add(new RoutingPolicy(AiFeature.TRANSLATE, "en", "am", "beta", 1, 100, false,
				Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-11-17T00:00:00Z")));
		policyRows.add(row("en", "am", "beta", 1, 0, false));
		assertThat(translate().providerCode()).isEqualTo("alpha");
	}

	@Test
	void policyRowRequiringZeroRetentionSkipsNonZdrProviders() {
		policyRows.add(row("en", "am", "alpha", 1, 100, true)); // alpha is not ZDR: this row cannot use it
		policyRows.add(row("en", "am", "beta", 2, 100, true));
		assertThat(translate().providerCode()).isEqualTo("beta");
		assertThat(alpha.callCount()).isZero();
	}

	@Test
	void narrowerLanguageScopeReplacesTheBroaderPolicyAndEqualPrioritiesFollowTheWeights() {
		policyRows.add(row(null, null, "alpha", 1, 100, false));
		policyRows.add(row("en", "am", "beta", 1, 100, false));
		assertThat(translate().providerCode()).isEqualTo("beta");

		policyRows.clear();
		policyRows.add(row("en", "am", "alpha", 5, 90, false));
		policyRows.add(row("en", "am", "beta", 5, 10, false));
		double[] draws = { 0.5 };
		RoutingPolicies lowDraw = new RoutingPolicies(() -> policyRows, Duration.ZERO, clock, () -> draws[0]);
		var query = com.qalsmartkeyboard.aigateway.spi.RouteQuery.of(AiFeature.TRANSLATE, "en", "am");
		// the same draw for both rows: weight 90 gives the larger key u^(1/90) than u^(1/10)
		assertThat(lowDraw.planFor(query).get().providers()).containsExactly("alpha", "beta");
		int betaFirst = 0;
		java.util.Random random = new java.util.Random(7);
		RoutingPolicies seeded = new RoutingPolicies(() -> policyRows, Duration.ZERO, clock, random::nextDouble);
		for (int i = 0; i < 2000; i++) {
			if (seeded.planFor(query).get().providers().get(0).equals("beta")) {
				betaFirst++;
			}
		}
		assertThat(betaFirst).isBetween(100, 400); // weights 90:10 put beta first about 10 percent of the time
	}

	// ------------------------------------------------------------------------------------------------ hygiene

	@Test
	void logsCarryProviderCodesAndKindsButNeverUserText() {
		alpha.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		try (CapturedLogs logs = new CapturedLogs()) {
			router.route(RouteRequest.translate(TranslateRequest.of("SECRET-USER-TEXT-42", "en", "am"), null, null));
			assertThat(logs.texts()).isNotEmpty().anyMatch(t -> t.contains("alpha") && t.contains("UNAVAILABLE"));
			assertThat(logs.texts()).noneMatch(t -> t.contains("SECRET-USER-TEXT"));
		}
		var throwing = new ThrowingProvider("alpha");
		ProviderRegistry registry = new ProviderRegistry(() -> descriptors, List.of(throwing, beta), Duration.ZERO, clock);
		AiRouter r = new AiRouter(registry, new RoutingPolicies(() -> policyRows, Duration.ZERO, clock, () -> 0.5),
				new KillSwitch(switches::get, Set.of(), Set.of(), Duration.ZERO, clock), breakers,
				new CostControl(costStore, Duration.ZERO, clock, null),
				new AiRouter.Settings(3, Duration.ofSeconds(1), Duration.ofSeconds(3)), new RoutingMetrics(meters));
		try (CapturedLogs logs = new CapturedLogs()) {
			r.route(RouteRequest.translate(TranslateRequest.of("SECRET-USER-TEXT-42", "en", "am"), null, null));
			assertThat(logs.texts()).noneMatch(t -> t.contains("SECRET-USER-TEXT") || t.contains("boom"));
		}
	}

	@Test
	void transformAndDetectRoutesUseTheirOwnOperationAndCapability() {
		descriptors.clear();
		descriptors.add(ProviderRegistryTest.provider("alpha", ProviderStatus.ACTIVE, CLEAN,
				new com.qalsmartkeyboard.aigateway.spi.ProviderCapability(AiFeature.GRAMMAR_CORRECTION, "am", null,
						QualityTier.BETA, true, null, "m"),
				new com.qalsmartkeyboard.aigateway.spi.ProviderCapability(AiFeature.DETECT_LANGUAGE, null, null,
						QualityTier.BETA, true, null, "m")));
		var transform = router.route(RouteRequest.transform(
				com.qalsmartkeyboard.aigateway.spi.TransformRequest.of(AiFeature.GRAMMAR_CORRECTION, "a  b", "am"), null,
				null));
		assertThat(transform.succeeded()).isTrue();
		assertThat(transform.value().resultText().value()).isEqualTo("a b");
		var detect = router.route(
				RouteRequest.detect(com.qalsmartkeyboard.aigateway.spi.DetectRequest.of("hello"), null, null));
		assertThat(detect.value().languages().get(0).languageCode()).isEqualTo("en");
		assertThat(ProviderException.Kind.valueOf("CANCELLED").contractCode()).isNull();
	}

	/** In-memory prices, budgets and ledger for unit tests. */
	static final class MemoryCostStore implements CostStore {
		final Map<String, List<Price>> prices = new HashMap<>();
		final Map<String, Budget> budgets = new HashMap<>();
		final Map<String, BigDecimal> spend = new HashMap<>();
		LocalDate day;

		@Override
		public Map<String, List<Price>> prices() {
			return prices;
		}

		@Override
		public Map<String, Budget> budgets() {
			return budgets;
		}

		@Override
		public Map<String, BigDecimal> spend(LocalDate d) {
			return d.equals(day) ? spend : Map.of();
		}

		@Override
		public void addSpend(String providerCode, LocalDate d, BigDecimal micros) {
			if (!d.equals(day)) {
				day = d;
				spend.clear();
			}
			spend.merge(providerCode, micros, BigDecimal::add);
		}
	}
}
