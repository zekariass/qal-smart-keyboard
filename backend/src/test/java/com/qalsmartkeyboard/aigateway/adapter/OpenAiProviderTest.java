package com.qalsmartkeyboard.aigateway.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.ProviderException;
import com.qalsmartkeyboard.aigateway.spi.ProviderException.Kind;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.UserText;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/** OpenAI Chat Completions wire format and failure mapping against a local WireMock (ADR 0023). */
class OpenAiProviderTest {

	static final String KEY = "sk-proj-test-do-not-log-7d1e";
	static final String MODEL = "test-model-1";
	static final String SECRET_TEXT = "my private draft ሰላም";
	static final Duration T = Duration.ofSeconds(3);
	static final String PATH = "/v1/chat/completions";

	static WireMockServer server;

	@BeforeAll
	static void start() {
		server = new WireMockServer(options().dynamicPort());
		server.start();
	}

	@AfterAll
	static void stop() {
		server.stop();
	}

	@BeforeEach
	void reset() {
		server.resetAll();
	}

	static OpenAiProvider provider() {
		return new OpenAiProvider(new OpenAiProvider.Config(URI.create(server.baseUrl() + "/v1"),
				Duration.ofSeconds(1), KEY, MODEL, 1000, ""));
	}

	static String completion(String contentJson, String finishReason, String refusalJson) {
		return """
				{"id":"chatcmpl-abc123","object":"chat.completion","created":1763452800,"model":"test-model-1-2026",
				 "choices":[{"index":0,"message":{"role":"assistant","content":%s,"refusal":%s},"finish_reason":"%s"}],
				 "usage":{"prompt_tokens":42,"completion_tokens":17,"total_tokens":59}}""".formatted(contentJson,
				refusalJson, finishReason);
	}

	static void answer(ResponseDefinitionBuilder response) {
		server.stubFor(post(urlPathEqualTo(PATH)).willReturn(response));
	}

	static ResponseDefinitionBuilder ok(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
				.withHeader("x-request-id", "req_123").withBody(body);
	}

	static String developerMessage(LoggedRequest request) {
		String body = request.getBodyAsString();
		return com.jayway.jsonpath.JsonPath.read(body, "$.messages[0].content");
	}

	// ------------------------------------------------------------------ success

	@Test
	void transformSendsStoreFalseTheModelAndTheTextAsTheWholeUserMessage() {
		answer(ok(completion("\"  corrected text\\n\"", "stop", "null")));
		String instructions = "Correct the grammar. Keep the meaning.";
		var request = new TransformRequest(AiFeature.GRAMMAR_CORRECTION, UserText.of(SECRET_TEXT), "am", null, null,
				null, null, null, instructions);

		var result = provider().llmTransform(request, T);

		assertThat(result.resultText().value()).isEqualTo("corrected text");
		assertThat(result.call().providerCode()).isEqualTo("openai");
		assertThat(result.call().modelCode()).isEqualTo(OpenAiProvider.MODEL_CODE);
		assertThat(result.call().providerRequestId()).isEqualTo("req_123");
		assertThat(result.call().latency()).isPositive();
		assertThat(result.usage().inputTokens()).isEqualTo(42);
		assertThat(result.usage().outputTokens()).isEqualTo(17);
		assertThat(result.usage().inputCharacters()).isEqualTo(SECRET_TEXT.codePointCount(0, SECRET_TEXT.length()));
		assertThat(result.usage().outputCharacters()).isEqualTo(14);

		server.verify(postRequestedFor(urlPathEqualTo(PATH))
				.withHeader("Authorization", equalTo("Bearer " + KEY))
				.withHeader("Content-Type", com.github.tomakehurst.wiremock.client.WireMock.containing("application/json"))
				.withRequestBody(matchingJsonPath("$.model", equalTo(MODEL)))
				.withRequestBody(matchingJsonPath("$.store", equalTo("false")))
				.withRequestBody(matchingJsonPath("$.max_completion_tokens", equalTo("1000")))
				.withRequestBody(notContaining("\"reasoning_effort\""))
				.withRequestBody(notContaining("\"tools\""))
				.withRequestBody(matchingJsonPath("$.messages.length()", equalTo("2")))
				.withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("developer")))
				.withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
				.withRequestBody(matchingJsonPath("$.messages[1].content", equalTo(SECRET_TEXT))));
		String developer = developerMessage(server.getAllServeEvents().get(0).getRequest());
		assertThat(developer).startsWith(OpenAiProvider.FRAME).endsWith(instructions).doesNotContain(SECRET_TEXT);
	}

	@Test
	void translateAsksForTheTranslationOnlyAndReportsNoDetectedLanguage() {
		answer(ok(completion("\"ሰላም\"", "stop", "null")));
		var result = provider().translate(TranslateRequest.of("Hello", "en", "am"), T);

		assertThat(result.translatedText().value()).isEqualTo("ሰላም");
		assertThat(result.detectedSourceLanguage()).isNull();
		String developer = developerMessage(server.getAllServeEvents().get(0).getRequest());
		assertThat(developer).contains("from English (en) into Amharic (am)")
				.contains("Output only the translation").contains("Keep personal names");
	}

	@Test
	void renderedTranslateInstructionsAreAppendedAndAutoDetectNamesNoSource() {
		var r = new TranslateRequest(UserText.of("x"), null, "ti", "FORMAL", true, null, "Use formal address.");
		String developer = OpenAiProvider.translateInstructions(r);
		assertThat(developer).contains("translate the text into Tigrinya (ti)").doesNotContain("from ")
				.endsWith("Use formal address.").doesNotContain("Keep personal names");
	}

	@Test
	void transformWithoutTemplateFallsBackToATaskLineFromTheRequestFields() {
		var r = new TransformRequest(AiFeature.TONE_ADJUST, UserText.of(SECRET_TEXT), "om", null, "POLITE", null, null,
				null, null);
		assertThat(OpenAiProvider.transformInstructions(r)).startsWith(OpenAiProvider.FRAME)
				.contains("Tone: POLITE").contains("Afaan Oromoo (om)").doesNotContain(SECRET_TEXT);
	}

	@Test
	void reasoningEffortAndTokenCapAreOptional() {
		answer(ok(completion("\"ok\"", "stop", "null")));
		new OpenAiProvider(new OpenAiProvider.Config(URI.create(server.baseUrl() + "/v1"), null, KEY, MODEL, 0, "low"))
				.llmTransform(TransformRequest.of(AiFeature.REWRITE, "x", "en"), T);
		server.verify(postRequestedFor(urlPathEqualTo(PATH))
				.withRequestBody(matchingJsonPath("$.reasoning_effort", equalTo("low")))
				.withRequestBody(notContaining("\"max_completion_tokens\"")));
	}

	// ------------------------------------------------------------------ failures

	private ProviderException fails(Supplier<?> call) {
		var ex = org.junit.jupiter.api.Assertions.assertThrows(ProviderException.class, call::get);
		assertThat(ex.toString()).doesNotContain(SECRET_TEXT).doesNotContain("secret detail").doesNotContain(KEY)
				.doesNotContain("I cannot help");
		assertThat(ex.getMessage()).doesNotContain(SECRET_TEXT);
		assertThat(ex.getCause()).isNull();
		assertThat(ex.providerCode()).isEqualTo("openai");
		return ex;
	}

	private ProviderException transformFails() {
		return fails(() -> provider().llmTransform(
				TransformRequest.of(AiFeature.REWRITE, SECRET_TEXT, "am"), T));
	}

	@Test
	void httpStatusesMapToTheTaxonomyWithoutReadingTheBody() {
		record Case(int status, Kind kind) {
		}
		for (Case c : List.of(new Case(400, Kind.UNAVAILABLE), new Case(401, Kind.AUTHENTICATION_FAILED),
				new Case(403, Kind.AUTHENTICATION_FAILED), new Case(404, Kind.UNAVAILABLE),
				new Case(429, Kind.QUOTA_EXHAUSTED_AT_PROVIDER), new Case(500, Kind.UNAVAILABLE),
				new Case(502, Kind.UNAVAILABLE), new Case(503, Kind.UNAVAILABLE), new Case(504, Kind.TIMEOUT))) {
			server.resetAll();
			answer(aResponse().withStatus(c.status()).withHeader("Content-Type", "application/json")
					.withBody("{\"error\":{\"message\":\"secret detail " + SECRET_TEXT + "\"}}"));
			assertThat(transformFails().kind()).as("HTTP %d", c.status()).isEqualTo(c.kind());
		}
	}

	@Test
	void retryAfterSecondsOrMillisecondsIsCarried() {
		answer(aResponse().withStatus(429).withHeader("Retry-After", "20"));
		assertThat(((ProviderException.QuotaExhaustedAtProvider) transformFails()).retryAfter())
				.isEqualTo(Duration.ofSeconds(20));
		server.resetAll();
		answer(aResponse().withStatus(429).withHeader("retry-after-ms", "1500"));
		assertThat(((ProviderException.QuotaExhaustedAtProvider) transformFails()).retryAfter())
				.isEqualTo(Duration.ofMillis(1500));
		server.resetAll();
		answer(aResponse().withStatus(429));
		assertThat(((ProviderException.QuotaExhaustedAtProvider) transformFails()).retryAfter()).isNull();
	}

	@Test
	void refusalsAndContentFilterAreContentRejected() {
		answer(ok(completion("null", "stop", "\"I cannot help with that.\"")));
		assertThat(transformFails().kind()).isEqualTo(Kind.CONTENT_REJECTED);
		server.resetAll();
		answer(ok(completion("\"partial\"", "content_filter", "null")));
		assertThat(transformFails().kind()).isEqualTo(Kind.CONTENT_REJECTED);
	}

	@Test
	void truncatedEmptyOrMalformedAnswersAreInvalidResponses() {
		for (String body : List.of(completion("\"half an ans\"", "length", "null"),
				completion("\"x\"", "tool_calls", "null"), completion("null", "stop", "null"),
				completion("\"   \"", "stop", "null"), "{\"choices\":[]}", "{}", "not json", "[]", "")) {
			server.resetAll();
			answer(ok(body));
			assertThat(transformFails().kind()).as("body [%s]", body).isEqualTo(Kind.INVALID_RESPONSE);
		}
	}

	@Test
	void slowAnswerEndsInTimeoutWithinTheDeadline() {
		answer(ok(completion("\"late\"", "stop", "null")).withFixedDelay(8000));
		long start = System.nanoTime();
		ProviderException ex = fails(() -> provider().translate(TranslateRequest.of(SECRET_TEXT, "en", "am"),
				Duration.ofMillis(300)));
		assertThat(ex.kind()).isEqualTo(Kind.TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6)); // cold JVM start included; the stub waits 8 s
	}

	@Test
	void interruptCancelsTheCallAndKeepsTheFlag() throws Exception {
		answer(ok(completion("\"late\"", "stop", "null")).withFixedDelay(5000));
		AtomicBoolean flagKept = new AtomicBoolean();
		CompletableFuture<Throwable> outcome = new CompletableFuture<>();
		Thread worker = Thread.ofVirtual().start(() -> {
			try {
				provider().llmTransform(TransformRequest.of(AiFeature.REWRITE, SECRET_TEXT, "en"), Duration.ofSeconds(4));
				outcome.complete(null);
			} catch (Throwable t) {
				flagKept.set(Thread.currentThread().isInterrupted());
				outcome.complete(t);
			}
		});
		Thread.sleep(300);
		worker.interrupt();
		Throwable t = outcome.get(3, TimeUnit.SECONDS);
		assertThat(t).isInstanceOf(ProviderException.Cancelled.class);
		assertThat(t.toString()).doesNotContain(SECRET_TEXT);
		assertThat(flagKept).isTrue();
	}

	@Test
	void alreadyInterruptedThreadIsCancelledWithoutACall() {
		Thread.currentThread().interrupt();
		try {
			assertThat(transformFails().kind()).isEqualTo(Kind.CANCELLED);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
		assertThat(server.getAllServeEvents()).isEmpty();
	}

	@Test
	void connectionRefusedIsUnavailable() {
		var dead = new WireMockServer(options().dynamicPort());
		dead.start();
		String url = dead.baseUrl();
		dead.stop();
		var p = new OpenAiProvider(new OpenAiProvider.Config(URI.create(url + "/v1"), Duration.ofSeconds(1), KEY,
				MODEL, null, null));
		assertThatThrownBy(() -> p.translate(TranslateRequest.of("x", "en", "am"), T))
				.isInstanceOf(ProviderException.Unavailable.class);
	}

	// ------------------------------------------------------------------ hygiene and config

	@Test
	void nothingIsLoggedAndNeitherKeyNorTextLeaks() {
		try (CapturedLogs logs = new CapturedLogs()) {
			answer(aResponse().withStatus(500).withBody("secret detail"));
			transformFails();
			server.resetAll();
			answer(ok(completion("\"ሰላም ok\"", "stop", "null")));
			provider().llmTransform(TransformRequest.of(AiFeature.REWRITE, SECRET_TEXT, "am"), T);
			assertThat(logs.texts()).noneMatch(t -> t.contains(KEY) || t.contains(SECRET_TEXT)
					|| t.contains("secret detail") || t.contains("ሰላም ok"));
		}
		assertThat(provider().toString()).doesNotContain(KEY).contains("<redacted>").contains(MODEL);
	}

	@Test
	void configNeedsAKeyAndAModel() {
		assertThatThrownBy(() -> new OpenAiProvider.Config(null, null, KEY, " ", null, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("QAL_OPENAI_MODEL")
				.hasMessageNotContaining(KEY);
		assertThatThrownBy(() -> new OpenAiProvider.Config(null, null, "", MODEL, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(new OpenAiProvider.Config(null, null, KEY, MODEL, null, null).baseUrl())
				.isEqualTo(URI.create("https://api.openai.com/v1"));
	}

	@Test
	void timeoutMustBePositive() {
		assertThatThrownBy(() -> provider().translate(TranslateRequest.of("x", "en", "am"), Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
