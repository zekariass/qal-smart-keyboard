package com.qalsmartkeyboard.aigateway;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;

/**
 * @param registryCacheTtl how long the provider descriptor snapshot is reused before the next lookup reloads it
 * @param mock deterministic in-process provider (dev/test only; refused under prod, production, staging)
 * @param http HTTP/JSON adapter pointed at the local WireMock stubs (dev/test only, same refusal)
 */
@ConfigurationProperties("qal.ai")
record AiGatewayProperties(@DefaultValue("30s") Duration registryCacheTtl, @DefaultValue Mock mock,
		@DefaultValue Http http) {

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
}
