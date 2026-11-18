package com.qalsmartkeyboard.aigateway;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;

/**
 * @param registryCacheTtl how long the provider descriptor snapshot is reused before the next lookup reloads it
 * @param mock deterministic in-process provider (dev/test only; refused under prod, production, staging)
 * @param http HTTP/JSON adapter pointed at the local WireMock stubs (dev/test only, same refusal)
 * @param routing router limits, circuit breaker, kill switch and cost cap caches (B5.2)
 * @param pipeline the request pipeline (B5.3)
 */
@ConfigurationProperties("qal.ai")
record AiGatewayProperties(@DefaultValue("30s") Duration registryCacheTtl, @DefaultValue Mock mock,
		@DefaultValue Http http, @DefaultValue Routing routing, @DefaultValue Pipeline pipeline) {

	/**
	 * @param deadline                   time budget for all provider calls of one request (route, guardrail retry and
	 *                                   fallback together)
	 * @param requireAiProcessingConsent demand the AI_PROCESSING consent for server AI (off: the System Design does not
	 *                                   require it for explicit actions)
	 * @param catalogCacheTtl            how long the prompt template and language snapshots are reused
	 * @param minScriptRatio             share of output letters that must be in the destination script
	 * @param maxTextCharacters          translate/transform text limit (contract maxLength)
	 * @param maxDetectCharacters        detect-language text limit (contract maxLength)
	 */
	record Pipeline(@DefaultValue("8s") Duration deadline, @DefaultValue("false") boolean requireAiProcessingConsent,
			@DefaultValue("30s") Duration catalogCacheTtl, @DefaultValue("0.9") double minScriptRatio,
			@DefaultValue("5000") int maxTextCharacters, @DefaultValue("1000") int maxDetectCharacters) {
	}

	record Mock(@DefaultValue("false") boolean enabled, @DefaultValue("mock") String code,
			@DefaultValue("0ms") Duration latency, @DefaultValue("NONE") MockAiProvider.FailureMode failure) {
	}

	/** {@code apiKey} comes from the environment (QAL_AI_HTTP_API_KEY), never from the database; redacted in toString. */
	record Http(@DefaultValue("false") boolean enabled, @DefaultValue("wiremock-http") String code,
			@DefaultValue("http://localhost:8089") String baseUrl, @DefaultValue("2s") Duration connectTimeout,
			@DefaultValue("") String apiKey, @DefaultValue("") String mockScenario) {

		@Override
		public String toString() {
			return "Http[enabled=" + enabled + ", code=" + code + ", baseUrl=" + baseUrl + ", apiKey=<redacted>]";
		}
	}

	/**
	 * @param maxAttempts provider calls per request at most
	 * @param attemptTimeout cap for one provider call (adapters enforce it)
	 * @param deadline overall time budget of one routed request across all attempts
	 * @param policyCacheTtl how long the {@code ai_routing_policies} snapshot is reused
	 * @param killSwitchCacheTtl how long the kill switch row is reused: the longest a switch takes to bite
	 * @param costCacheTtl how long prices, budgets and the spend total are reused before re-reading the database
	 * @param killedProviders provider codes switched off by configuration (emergency, needs a restart)
	 * @param killedFeatures feature codes switched off by configuration
	 */
	record Routing(@DefaultValue("3") int maxAttempts, @DefaultValue("4s") Duration attemptTimeout,
			@DefaultValue("8s") Duration deadline, @DefaultValue("30s") Duration policyCacheTtl,
			@DefaultValue("5s") Duration killSwitchCacheTtl, @DefaultValue("10s") Duration costCacheTtl,
			@DefaultValue Set<String> killedProviders, @DefaultValue Set<String> killedFeatures,
			@DefaultValue Breaker breaker) {
	}

	/**
	 * @param windowSize number of most recent calls judged
	 * @param minimumCalls calls needed in the window before the breaker may open
	 * @param failureRatePercent failures (that count against provider health) in the window that open it
	 * @param slowCallThreshold a successful call slower than this is "slow"
	 * @param slowCallRatePercent slow calls in the window that open it
	 * @param openDuration how long it stays open before probing
	 * @param halfOpenCalls probe calls allowed, all of which must succeed to close it
	 */
	record Breaker(@DefaultValue("20") int windowSize, @DefaultValue("10") int minimumCalls,
			@DefaultValue("50") int failureRatePercent, @DefaultValue("3s") Duration slowCallThreshold,
			@DefaultValue("80") int slowCallRatePercent, @DefaultValue("30s") Duration openDuration,
			@DefaultValue("3") int halfOpenCalls) {
	}
}
