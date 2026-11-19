package com.qalsmartkeyboard.aigateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.qalsmartkeyboard.aigateway.RouteResult.Outcome;
import com.qalsmartkeyboard.aigateway.adapter.GoogleTranslateProvider;
import com.qalsmartkeyboard.aigateway.adapter.OpenAiProvider;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreakers;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.ProviderException.Kind;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.TransformResult;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateResult;

/**
 * ADR 0007 D46 end to end against PostgreSQL 18 and local vendor stubs (ADR 0023): the real OpenAI and Google adapters
 * (keys set, base URLs on WireMock), the V19 rows enabled the way an operator would (DPA recorded, status ACTIVE,
 * policies enabled). TRANSLATE goes to OpenAI first and to Google when OpenAI fails; transforms never reach Google.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.ai.registry-cache-ttl=0s",
		"qal.ai.routing.policy-cache-ttl=0s", "qal.ai.routing.kill-switch-cache-ttl=0s",
		"qal.ai.routing.cost-cache-ttl=0s", "qal.ai.routing.attempt-timeout=2s", "qal.ai.routing.deadline=4s" })
@Testcontainers
class AiVendorRoutingIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final WireMockServer VENDORS = new WireMockServer(options().dynamicPort());

	static {
		VENDORS.start();
	}

	@DynamicPropertySource
	static void vendors(DynamicPropertyRegistry registry) {
		registry.add("qal.ai.openai.api-key", () -> "sk-test-only");
		registry.add("qal.ai.openai.model", () -> "test-model");
		registry.add("qal.ai.openai.base-url", () -> VENDORS.baseUrl() + "/v1");
		registry.add("qal.ai.google-translate.api-key", () -> "AIza-test-only");
		registry.add("qal.ai.google-translate.base-url", VENDORS::baseUrl);
	}

	@AfterAll
	static void stopVendors() {
		VENDORS.stop();
	}

	static final String OPENAI = "/v1/chat/completions";
	static final String GOOGLE = "/language/translate/v2";

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AiRouter router;
	@Autowired
	CircuitBreakers breakers;
	@Autowired
	OpenAiProvider openAi;
	@Autowired
	GoogleTranslateProvider google;

	@BeforeEach
	void enableLikeAnOperator() {
		VENDORS.resetAll();
		breakers.resetAll();
		jdbc.update("UPDATE ai_providers SET provider_status = 'ACTIVE', dpa_signed_at = NOW() "
				+ "WHERE provider_code IN ('openai', 'google-translate')");
		jdbc.update("""
				UPDATE ai_routing_policies SET is_enabled = TRUE, valid_from = NOW() - INTERVAL '1 hour'
				 WHERE model_id IN (SELECT m.id FROM ai_models m JOIN ai_providers p ON p.id = m.provider_id
				                     WHERE p.provider_code IN ('openai', 'google-translate'))""");
	}

	@AfterEach
	void disable() {
		jdbc.update("UPDATE ai_routing_policies SET is_enabled = FALSE");
		jdbc.update("UPDATE ai_providers SET provider_status = 'DISABLED', dpa_signed_at = NULL "
				+ "WHERE provider_code IN ('openai', 'google-translate')");
	}

	void openAiAnswers(String content) {
		VENDORS.stubFor(post(urlPathEqualTo(OPENAI)).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("""
						{"id":"chatcmpl-1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant",
						 "content":"%s","refusal":null},"finish_reason":"stop"}],
						 "usage":{"prompt_tokens":30,"completion_tokens":5,"total_tokens":35}}""".formatted(content))));
	}

	void openAiDown() {
		VENDORS.stubFor(post(urlPathEqualTo(OPENAI)).willReturn(aResponse().withStatus(503)));
	}

	void googleAnswers(String text) {
		VENDORS.stubFor(post(urlPathEqualTo(GOOGLE)).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"data\":{\"translations\":[{\"translatedText\":\"" + text + "\"}]}}")));
	}

	RouteResult<TranslateResult> translate(String src, String dst) {
		return router.route(RouteRequest.translate(TranslateRequest.of("Good morning", src, dst), null, null));
	}

	@Test
	void translationGoesToOpenAiFirst() {
		openAiAnswers("እንደምን አደርክ");
		googleAnswers("ከ Google");

		RouteResult<TranslateResult> r = translate("en", "am");

		assertThat(r.outcome()).isEqualTo(Outcome.SUCCEEDED);
		assertThat(r.providerCode()).isEqualTo("openai");
		assertThat(r.value().translatedText().value()).isEqualTo("እንደምን አደርክ");
		assertThat(r.value().usage().inputTokens()).isEqualTo(30);
		assertThat(r.capability().modelCode()).isEqualTo("openai-llm");
		VENDORS.verify(0, postRequestedFor(urlPathEqualTo(GOOGLE)));
		VENDORS.verify(postRequestedFor(urlPathEqualTo(OPENAI)).withHeader("Authorization", equalTo("Bearer sk-test-only"))
				.withRequestBody(matchingJsonPath("$.store", equalTo("false")))
				.withRequestBody(matchingJsonPath("$.model", equalTo("test-model"))));
	}

	@Test
	void translationFallsBackToGoogleWhenOpenAiFails() {
		openAiDown();
		googleAnswers("ከ Google");

		RouteResult<TranslateResult> r = translate("am", "en");

		assertThat(r.outcome()).isEqualTo(Outcome.SUCCEEDED);
		assertThat(r.providerCode()).isEqualTo("google-translate");
		assertThat(r.attempts()).extracting(a -> a.providerCode()).containsExactly("openai", "google-translate");
		assertThat(r.attempts().get(0).errorKind()).isEqualTo(Kind.UNAVAILABLE);
		assertThat(r.value().translatedText().value()).isEqualTo("ከ Google");
		VENDORS.verify(postRequestedFor(urlPathEqualTo(GOOGLE)).withHeader("x-goog-api-key", equalTo("AIza-test-only")));
	}

	@Test
	void autoDetectedSourceIsServedByBoth() {
		openAiDown();
		VENDORS.stubFor(post(urlPathEqualTo(GOOGLE)).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(
						"{\"data\":{\"translations\":[{\"translatedText\":\"Hello\",\"detectedSourceLanguage\":\"am\"}]}}")));
		RouteResult<TranslateResult> r = translate(null, "en");
		assertThat(r.attempts()).extracting(a -> a.providerCode()).containsExactly("openai", "google-translate");
		assertThat(r.value().detectedSourceLanguage()).isEqualTo("am");
	}

	@Test
	void transformsGoToOpenAiOnlyAndNeverToGoogle() {
		openAiAnswers("fixed");
		RouteResult<TransformResult> ok = router.route(RouteRequest.transform(
				TransformRequest.of(AiFeature.GRAMMAR_CORRECTION, "speling", "en"), null, null));
		assertThat(ok.providerCode()).isEqualTo("openai");

		VENDORS.resetAll();
		breakers.resetAll();
		openAiDown();
		googleAnswers("never");
		RouteResult<TransformResult> down = router.route(RouteRequest.transform(
				TransformRequest.of(AiFeature.REWRITE, "text", "am"), null, null));
		assertThat(down.outcome()).isEqualTo(Outcome.PROVIDER_UNAVAILABLE);
		assertThat(down.attempts()).extracting(a -> a.providerCode()).containsExactly("openai");
		VENDORS.verify(0, postRequestedFor(urlPathEqualTo(GOOGLE)));
	}

	/** V23: Arabic routes both ways, OpenAI first and Google as the fallback, and the transforms reach OpenAI only. */
	@Test
	void arabicTranslationRoutesBothWaysOnBothVendors() {
		openAiAnswers("صباح الخير");
		googleAnswers("من Google");
		RouteResult<TranslateResult> enAr = translate("en", "ar");
		assertThat(enAr.outcome()).isEqualTo(Outcome.SUCCEEDED);
		assertThat(enAr.providerCode()).isEqualTo("openai");
		assertThat(enAr.value().translatedText().value()).isEqualTo("صباح الخير");

		openAiDown();
		for (String[] pair : new String[][] { { "en", "ar" }, { "ar", "en" } }) {
			breakers.resetAll();
			RouteResult<TranslateResult> r = translate(pair[0], pair[1]);
			assertThat(r.outcome()).as(pair[0] + ">" + pair[1]).isEqualTo(Outcome.SUCCEEDED);
			assertThat(r.attempts()).extracting(a -> a.providerCode()).containsExactly("openai", "google-translate");
		}
		// Afar is a (planned) typing language no provider lists (ADR 0040)
		assertThat(translate("ar", "aa").outcome()).isEqualTo(Outcome.LANGUAGE_PAIR_UNAVAILABLE);

		VENDORS.resetAll();
		breakers.resetAll();
		openAiAnswers("fixed");
		for (AiFeature f : new AiFeature[] { AiFeature.GRAMMAR_CORRECTION, AiFeature.REWRITE, AiFeature.TONE_ADJUST,
				AiFeature.SUMMARIZE, AiFeature.EXPLAIN_TEXT, AiFeature.SMART_REPLY, AiFeature.DRAFT_DOCUMENT }) {
			RouteResult<TransformResult> t = router.route(RouteRequest.transform(TransformRequest.of(f, "نص", "ar"),
					null, null));
			assertThat(t.providerCode()).as(f.name()).isEqualTo("openai");
		}
		VENDORS.verify(0, postRequestedFor(urlPathEqualTo(GOOGLE)));
	}

	@Test
	void disabledRowsRouteNothingEvenWithAdapters() {
		disable();
		openAiAnswers("x");
		RouteResult<TranslateResult> r = translate("en", "am");
		assertThat(r.outcome()).isEqualTo(Outcome.LANGUAGE_PAIR_UNAVAILABLE);
		assertThat(VENDORS.getAllServeEvents()).isEmpty();
		assertThat(openAi.code()).isEqualTo("openai");
		assertThat(google.code()).isEqualTo("google-translate");
	}
}
