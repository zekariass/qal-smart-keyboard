package com.qalsmartkeyboard.aigateway.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.DetectRequest;
import com.qalsmartkeyboard.aigateway.spi.ProviderException;
import com.qalsmartkeyboard.aigateway.spi.ProviderException.Kind;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.UserText;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/**
 * HTTP adapter mechanics (B5.1). Part one talks to an in-process WireMock that serves the real compose stubs
 * ({@code infra/compose/wiremock}, placeholders per OI-P5); part two uses a bare WireMock to force every error status.
 */
class HttpJsonAiProviderTest {

	static final String KEY = "sk-test-do-not-log-9f3c";
	static final Duration T = Duration.ofSeconds(3);

	/** Serves infra/compose/wiremock/mappings with response templating, like the compose service. */
	static WireMockServer compose;
	/** No mappings: each test declares what it needs. */
	static WireMockServer bare;

	@BeforeAll
	static void start() {
		Path dir = Path.of("..", "infra", "compose", "wiremock").toAbsolutePath().normalize();
		compose = new WireMockServer(options().dynamicPort().usingFilesUnderDirectory(dir.toString())
				.globalTemplating(true).notifier(new com.github.tomakehurst.wiremock.common.ConsoleNotifier(false)));
		compose.start();
		bare = new WireMockServer(options().dynamicPort());
		bare.start();
	}

	@AfterAll
	static void stop() {
		compose.stop();
		bare.stop();
	}

	@BeforeEach
	void reset() {
		bare.resetAll();
		compose.resetRequests();
	}

	static HttpJsonAiProvider provider(WireMockServer server, String scenario) {
		return new HttpJsonAiProvider(new HttpJsonAiProvider.Config("wiremock-http", URI.create(server.baseUrl()),
				Duration.ofSeconds(1), KEY, scenario));
	}

	// ------------------------------------------------------------------ compose stubs

	@Test
	void translateAgainstTheComposeStub() {
		var result = provider(compose, "").translate(TranslateRequest.of("Hello, how are you?", "en", "am"), T);

		assertThat(result.translatedText().value()).startsWith("ሰላም");
		assertThat(result.call().providerCode()).isEqualTo("wiremock-http");
		assertThat(result.call().modelCode()).isEqualTo("mock-mt-1");
		assertThat(result.call().latency()).isPositive();
		assertThat(result.usage().inputTokens()).isEqualTo(12);
		assertThat(result.usage().outputTokens()).isEqualTo(18);
		assertThat(result.usage().inputCharacters()).isEqualTo(19);
		assertThat(result.detectedSourceLanguage()).isNull();
		compose.verify(postRequestedFor(urlPathEqualTo("/ai/v1/translate")).withHeader("Authorization",
				equalTo("Bearer " + KEY)));
	}

	@Test
	void autoDetectedSourceComesFromTheResponse() {
		var result = provider(compose, "").translate(TranslateRequest.of("Hello", null, "am"), T);
		assertThat(result.detectedSourceLanguage()).isEqualTo("en");
	}

	@Test
	void llmTransformAgainstTheComposeStub() {
		var result = provider(compose, "").llmTransform(TransformRequest.of(AiFeature.REWRITE, "draft", "am"), T);
		assertThat(result.resultText().value()).startsWith("ሰላም");
		assertThat(result.call().modelCode()).isEqualTo("mock-llm-1");
		assertThat(result.call().providerRequestId()).hasSize(36); // templated UUID from the stub
		assertThat(result.usage().inputTokens()).isEqualTo(25);
		assertThat(result.usage().outputTokens()).isEqualTo(31);
	}

	/** ADR 0027: the output language and every further user text travel as their own JSON members. */
	@Test
	void anEmailReplyCarriesTheOutputLanguageAndTheContextPartsSeparately() {
		var request = new TransformRequest(AiFeature.DRAFT_DOCUMENT, UserText.of("Original e-mail"), null, "REPLY",
				"FORMAL", null, "am", null, "Rendered.",
				List.of(com.qalsmartkeyboard.aigateway.spi.ContextPart.of(
						com.qalsmartkeyboard.aigateway.spi.ContextPart.Kind.USER_NOTES, "say yes")),
				0, 2);
		provider(compose, "").llmTransform(request, T);
		compose.verify(postRequestedFor(urlPathEqualTo("/ai/v1/llm/chat"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.output_language",
						equalTo("am")))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.context[0].kind",
						equalTo("USER_NOTES")))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.context[0].text",
						equalTo("say yes")))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.variation",
						equalTo("2")))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.text",
						equalTo("Original e-mail"))));
	}

	@Test
	void detectLanguageAgainstTheComposeStub() {
		var result = provider(compose, "").detectLanguage(DetectRequest.of("ሰላም"), T);
		assertThat(result.languages()).extracting(l -> l.languageCode()).containsExactly("am", "ti");
		assertThat(result.languages().get(0).scriptCode()).isEqualTo("Ethi");
		assertThat(result.languages().get(0).confidence()).isEqualTo(0.97);
	}

	@Test
	void slowScenarioEndsInTimeoutWithinTheDeadline() {
		long start = System.nanoTime();
		assertThatThrownBy(() -> provider(compose, "slow").translate(TranslateRequest.of("x", "en", "am"),
				Duration.ofMillis(500))).isInstanceOf(ProviderException.Timeout.class);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
	}

	@Test
	void errorScenarioIsUnavailable() {
		assertThatThrownBy(() -> provider(compose, "error").detectLanguage(DetectRequest.of("x"), T))
				.isInstanceOf(ProviderException.Unavailable.class);
		assertThatThrownBy(() -> provider(compose, "error").llmTransform(TransformRequest.of(AiFeature.REWRITE, "x", "en"), T))
				.isInstanceOf(ProviderException.Unavailable.class);
	}

	// ------------------------------------------------------------------ status mapping (bare WireMock)

	private void stubStatus(int status, String retryAfter) {
		var response = aResponse().withStatus(status).withHeader("Content-Type", "application/json")
				.withBody("{\"error\":\"provider secret detail that must never surface\"}");
		if (retryAfter != null) {
			response.withHeader("Retry-After", retryAfter);
		}
		bare.stubFor(post(urlPathEqualTo("/ai/v1/translate")).willReturn(response));
	}

	private ProviderException translateFails() {
		var ex = org.junit.jupiter.api.Assertions.assertThrows(ProviderException.class,
				() -> provider(bare, "").translate(TranslateRequest.of("private words", "en", "am"), T));
		assertThat(ex.toString()).doesNotContain("secret detail").doesNotContain("private words").doesNotContain(KEY);
		assertThat(ex.getCause()).isNull();
		return ex;
	}

	@Test
	void httpStatusesMapToTheTaxonomy() {
		record Case(int status, Kind kind) {
		}
		for (Case c : List.of(new Case(401, Kind.AUTHENTICATION_FAILED), new Case(403, Kind.AUTHENTICATION_FAILED),
				new Case(402, Kind.QUOTA_EXHAUSTED_AT_PROVIDER), new Case(429, Kind.QUOTA_EXHAUSTED_AT_PROVIDER),
				new Case(408, Kind.TIMEOUT), new Case(504, Kind.TIMEOUT), new Case(422, Kind.CONTENT_REJECTED),
				new Case(451, Kind.CONTENT_REJECTED), new Case(400, Kind.UNAVAILABLE), new Case(404, Kind.UNAVAILABLE),
				new Case(500, Kind.UNAVAILABLE), new Case(502, Kind.UNAVAILABLE), new Case(503, Kind.UNAVAILABLE))) {
			stubStatus(c.status(), null);
			assertThat(translateFails().kind()).as("HTTP %d", c.status()).isEqualTo(c.kind());
		}
	}

	@Test
	void retryAfterIsHonouredWhenNumeric() {
		stubStatus(429, "17");
		assertThat(((ProviderException.QuotaExhaustedAtProvider) translateFails()).retryAfter())
				.isEqualTo(Duration.ofSeconds(17));
		stubStatus(429, "Wed, 21 Oct 2026 07:28:00 GMT");
		assertThat(((ProviderException.QuotaExhaustedAtProvider) translateFails()).retryAfter()).isNull();
	}

	@Test
	void malformedOrIncompleteBodiesAreInvalidResponses() {
		for (String body : List.of("not json", "{}", "{\"translation\":\"\"}", "[]", "")) {
			bare.stubFor(post(urlPathEqualTo("/ai/v1/translate")).willReturn(
					aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
			assertThat(translateFails().kind()).as("body [%s]", body).isEqualTo(Kind.INVALID_RESPONSE);
		}
	}

	@Test
	void llmFinishReasonsAreChecked() {
		for (var c : List.of(java.util.Map.entry("content_filter", Kind.CONTENT_REJECTED),
				java.util.Map.entry("length", Kind.INVALID_RESPONSE))) {
			bare.stubFor(post(urlPathEqualTo("/ai/v1/llm/chat")).willReturn(aResponse().withStatus(200)
					.withHeader("Content-Type", "application/json")
					.withBody("{\"output\":\"half an ans\",\"finish_reason\":\"" + c.getKey() + "\"}")));
			assertThatThrownBy(() -> provider(bare, "").llmTransform(TransformRequest.of(AiFeature.REWRITE, "x", "en"), T))
					.isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.kind()).isEqualTo(c.getValue()));
		}
	}

	@Test
	void connectionRefusedIsUnavailable() {
		var dead = new WireMockServer(options().dynamicPort());
		dead.start();
		String url = dead.baseUrl();
		dead.stop();
		var p = new HttpJsonAiProvider(new HttpJsonAiProvider.Config("wiremock-http", URI.create(url),
				Duration.ofSeconds(1), KEY, ""));
		assertThatThrownBy(() -> p.translate(TranslateRequest.of("x", "en", "am"), T))
				.isInstanceOf(ProviderException.Unavailable.class);
	}

	@Test
	void nothingIsLoggedAndTheKeyNeverLeaksIntoMessagesOrConfigText() {
		stubStatus(500, null);
		try (CapturedLogs logs = new CapturedLogs()) {
			translateFails();
			provider(compose, "").translate(TranslateRequest.of("private words", "en", "am"), T);
			assertThat(logs.texts()).noneMatch(t -> t.contains(KEY) || t.contains("private words")
					|| t.contains("secret detail") || t.contains("ሰላም"));
		}
		var config = new HttpJsonAiProvider.Config("p", URI.create("http://localhost:1"), null, KEY, "");
		assertThat(config.toString()).doesNotContain(KEY).contains("<redacted>");
	}

	@Test
	void noAuthorizationHeaderWithoutAKey() {
		bare.stubFor(post(urlPathEqualTo("/ai/v1/detect-language")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("{\"language\":\"en\",\"confidence\":0.9}")));
		new HttpJsonAiProvider(new HttpJsonAiProvider.Config("p", URI.create(bare.baseUrl()), null, "", ""))
				.detectLanguage(DetectRequest.of("hello"), T);
		bare.verify(postRequestedFor(urlPathEqualTo("/ai/v1/detect-language"))
				.withoutHeader("Authorization").withoutHeader("X-Mock-Scenario"));
	}

	@Test
	void timeoutMustBePositive() {
		assertThatThrownBy(() -> provider(bare, "").translate(TranslateRequest.of("x", "en", "am"), Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
