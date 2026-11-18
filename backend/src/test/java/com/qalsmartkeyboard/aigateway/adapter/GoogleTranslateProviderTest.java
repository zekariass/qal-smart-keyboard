package com.qalsmartkeyboard.aigateway.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.qalsmartkeyboard.aigateway.spi.DetectRequest;
import com.qalsmartkeyboard.aigateway.spi.ProviderException;
import com.qalsmartkeyboard.aigateway.spi.ProviderException.Kind;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/** Cloud Translation - Basic (v2) wire format and failure mapping against a local WireMock (ADR 0023). */
class GoogleTranslateProviderTest {

	static final String KEY = "AIzaTestKeyDoNotLog-55a1";
	static final String SECRET_TEXT = "my private draft";
	static final Duration T = Duration.ofSeconds(3);
	static final String TRANSLATE = "/language/translate/v2";
	static final String DETECT = "/language/translate/v2/detect";

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

	static GoogleTranslateProvider provider() {
		return new GoogleTranslateProvider(new GoogleTranslateProvider.Config(URI.create(server.baseUrl()),
				Duration.ofSeconds(1), KEY));
	}

	static ResponseDefinitionBuilder ok(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json; charset=utf-8").withBody(body);
	}

	static void answer(String path, ResponseDefinitionBuilder response) {
		server.stubFor(post(urlPathEqualTo(path)).willReturn(response));
	}

	@Test
	void translateSendsAJsonBodyAndTheKeyInAHeaderNeverInTheUrl() {
		answer(TRANSLATE, ok("{\"data\":{\"translations\":[{\"translatedText\":\"ሰላም ዓለም\"}]}}"));

		var result = provider().translate(TranslateRequest.of(SECRET_TEXT, "en", "am"), T);

		assertThat(result.translatedText().value()).isEqualTo("ሰላም ዓለም");
		assertThat(result.detectedSourceLanguage()).isNull();
		assertThat(result.call().providerCode()).isEqualTo("google-translate");
		assertThat(result.call().modelCode()).isEqualTo(GoogleTranslateProvider.MT_MODEL_CODE);
		assertThat(result.usage().inputCharacters()).isEqualTo(SECRET_TEXT.length());
		assertThat(result.usage().outputCharacters()).isEqualTo(7);
		assertThat(result.usage().inputTokens()).isNull();
		server.verify(postRequestedFor(urlEqualTo(TRANSLATE)) // exact URL: no query string at all
				.withHeader("x-goog-api-key", equalTo(KEY))
				.withoutHeader("Authorization")
				.withRequestBody(matchingJsonPath("$.q", equalTo(SECRET_TEXT)))
				.withRequestBody(matchingJsonPath("$.target", equalTo("am")))
				.withRequestBody(matchingJsonPath("$.source", equalTo("en")))
				.withRequestBody(matchingJsonPath("$.format", equalTo("text"))));
	}

	@Test
	void autoDetectOmitsTheSourceAndReportsTheDetectedLanguage() {
		answer(TRANSLATE, ok("""
				{"data":{"translations":[{"translatedText":"Hello","detectedSourceLanguage":"am"}]}}"""));
		var result = provider().translate(TranslateRequest.of("ሰላም", null, "en"), T);
		assertThat(result.detectedSourceLanguage()).isEqualTo("am");
		server.verify(postRequestedFor(urlEqualTo(TRANSLATE)).withRequestBody(notContaining("\"source\"")));
	}

	@Test
	void detectRanksTheCandidatesAndFiltersToTheRequestedOnes() {
		answer(DETECT, ok("""
				{"data":{"detections":[[{"language":"ti","isReliable":false,"confidence":0.81},
				                        {"language":"am","isReliable":false,"confidence":0.12}]]}}"""));
		var all = provider().detectLanguage(DetectRequest.of("ሰላም"), T);
		assertThat(all.languages()).extracting(l -> l.languageCode()).containsExactly("ti", "am");
		assertThat(all.languages().get(0).confidence()).isEqualTo(0.81);
		assertThat(all.call().modelCode()).isEqualTo(GoogleTranslateProvider.LID_MODEL_CODE);

		var onlyAm = provider().detectLanguage(new DetectRequest(com.qalsmartkeyboard.aigateway.spi.UserText.of("ሰላም"),
				List.of("am")), T);
		assertThat(onlyAm.languages()).extracting(l -> l.languageCode()).containsExactly("am");
		var none = provider().detectLanguage(new DetectRequest(com.qalsmartkeyboard.aigateway.spi.UserText.of("ሰላም"),
				List.of("om")), T);
		assertThat(none.languages()).extracting(l -> l.languageCode()).containsExactly("und");
		server.verify(3, postRequestedFor(urlEqualTo(DETECT)).withHeader("x-goog-api-key", equalTo(KEY)));
	}

	// ------------------------------------------------------------------ failures

	private ProviderException fails(Supplier<?> call) {
		var ex = org.junit.jupiter.api.Assertions.assertThrows(ProviderException.class, call::get);
		assertThat(ex.toString()).doesNotContain(SECRET_TEXT).doesNotContain("secret detail").doesNotContain(KEY);
		assertThat(ex.getCause()).isNull();
		assertThat(ex.providerCode()).isEqualTo("google-translate");
		return ex;
	}

	private ProviderException translateFails() {
		return fails(() -> provider().translate(TranslateRequest.of(SECRET_TEXT, "en", "am"), T));
	}

	@Test
	void httpStatusesMapToTheTaxonomy() {
		record Case(int status, Kind kind) {
		}
		for (Case c : List.of(new Case(400, Kind.UNAVAILABLE), new Case(401, Kind.AUTHENTICATION_FAILED),
				new Case(403, Kind.AUTHENTICATION_FAILED), new Case(429, Kind.QUOTA_EXHAUSTED_AT_PROVIDER),
				new Case(500, Kind.UNAVAILABLE), new Case(503, Kind.UNAVAILABLE))) {
			server.resetAll();
			answer(TRANSLATE, aResponse().withStatus(c.status()).withHeader("Content-Type", "application/json")
					.withBody("{\"error\":{\"message\":\"secret detail " + SECRET_TEXT + "\"}}"));
			assertThat(translateFails().kind()).as("HTTP %d", c.status()).isEqualTo(c.kind());
		}
	}

	@Test
	void retryAfterIsCarried() {
		answer(TRANSLATE, aResponse().withStatus(429).withHeader("Retry-After", "30"));
		assertThat(((ProviderException.QuotaExhaustedAtProvider) translateFails()).retryAfter())
				.isEqualTo(Duration.ofSeconds(30));
	}

	@Test
	void malformedOrEmptyAnswersAreInvalidResponses() {
		for (String body : List.of("{}", "{\"data\":{}}", "{\"data\":{\"translations\":[]}}",
				"{\"data\":{\"translations\":[{\"translatedText\":\"\"}]}}", "{\"data\":[]}", "not json", "")) {
			server.resetAll();
			answer(TRANSLATE, ok(body));
			assertThat(translateFails().kind()).as("body [%s]", body).isEqualTo(Kind.INVALID_RESPONSE);
		}
		for (String body : List.of("{\"data\":{\"detections\":[]}}", "{\"data\":{\"detections\":[[]]}}", "{}")) {
			server.resetAll();
			answer(DETECT, ok(body));
			assertThat(fails(() -> provider().detectLanguage(DetectRequest.of(SECRET_TEXT), T)).kind())
					.as("body [%s]", body).isEqualTo(Kind.INVALID_RESPONSE);
		}
	}

	@Test
	void slowAnswerEndsInTimeout() {
		answer(TRANSLATE, ok("{}").withFixedDelay(8000));
		long start = System.nanoTime();
		assertThat(fails(() -> provider().translate(TranslateRequest.of(SECRET_TEXT, "en", "am"),
				Duration.ofMillis(300))).kind()).isEqualTo(Kind.TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6)); // cold JVM start included; the stub waits 8 s
	}

	@Test
	void interruptCancelsTheCall() throws Exception {
		answer(TRANSLATE, ok("{}").withFixedDelay(5000));
		CompletableFuture<Throwable> outcome = new CompletableFuture<>();
		Thread worker = Thread.ofVirtual().start(() -> {
			try {
				provider().translate(TranslateRequest.of(SECRET_TEXT, "en", "am"), Duration.ofSeconds(4));
				outcome.complete(null);
			} catch (Throwable t) {
				outcome.complete(t);
			}
		});
		Thread.sleep(300);
		worker.interrupt();
		assertThat(outcome.get(3, TimeUnit.SECONDS)).isInstanceOf(ProviderException.Cancelled.class);
	}

	@Test
	void nothingIsLoggedAndTheKeyNeverLeaks() {
		try (CapturedLogs logs = new CapturedLogs()) {
			answer(TRANSLATE, aResponse().withStatus(500).withBody("secret detail"));
			translateFails();
			server.resetAll();
			answer(TRANSLATE, ok("{\"data\":{\"translations\":[{\"translatedText\":\"ሰላም ok\"}]}}"));
			provider().translate(TranslateRequest.of(SECRET_TEXT, "en", "am"), T);
			assertThat(logs.texts()).noneMatch(t -> t.contains(KEY) || t.contains(SECRET_TEXT)
					|| t.contains("secret detail") || t.contains("ሰላም ok"));
		}
		assertThat(provider().toString()).doesNotContain(KEY).contains("<redacted>");
		assertThatThrownBy(() -> new GoogleTranslateProvider.Config(null, null, " "))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
