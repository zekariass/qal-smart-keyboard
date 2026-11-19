package com.qalsmartkeyboard.aigateway.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.Behavior;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.FailureMode;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.DetectRequest;
import com.qalsmartkeyboard.aigateway.spi.MeteredUnit;
import com.qalsmartkeyboard.aigateway.spi.ProviderException;
import com.qalsmartkeyboard.aigateway.spi.ProviderException.Kind;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.UserText;

class MockAiProviderTest {

	static final Duration T = Duration.ofSeconds(2);

	MockAiProvider mock = new MockAiProvider("mock", Behavior.ok());

	@Test
	void translateIsDeterministicAndReportsUsageAndMetadata() {
		var r1 = mock.translate(TranslateRequest.of("Hello world", "en", "am"), T);
		var r2 = mock.translate(TranslateRequest.of("Hello world", "en", "am"), T);

		assertThat(r1.translatedText().value()).isEqualTo("[mock:am] Hello world").isEqualTo(r2.translatedText().value());
		assertThat(r1.detectedSourceLanguage()).isNull();
		assertThat(r1.call().providerCode()).isEqualTo("mock");
		assertThat(r1.call().modelCode()).isEqualTo("mock-mt-1");
		assertThat(r1.call().providerRequestId()).isEqualTo("mock-1");
		assertThat(r2.call().providerRequestId()).isEqualTo("mock-2");
		assertThat(r1.usage().inputCharacters()).isEqualTo(11);
		assertThat(r1.usage().outputCharacters()).isEqualTo(21);
		assertThat(r1.usage().inputTokens()).isEqualTo(3);
		assertThat(r1.usage().units(MeteredUnit.CHARACTER)).isEqualTo(11);
		assertThat(r1.usage().units(MeteredUnit.REQUEST)).isEqualTo(1);
		assertThat(mock.callCount()).isEqualTo(2);
	}

	@Test
	void autoDetectedSourceIsReported() {
		var r = mock.translate(TranslateRequest.of("ሰላም", null, "en"), T);
		assertThat(r.detectedSourceLanguage()).isEqualTo("am");
		assertThat(r.detectionConfidence()).isEqualTo(0.97);
		assertThat(r.translatedText().value()).isEqualTo("[mock:en] ሰላም");
	}

	@Test
	void unsupportedOrIdenticalLanguagesAreRejected() {
		assertThatThrownBy(() -> mock.translate(TranslateRequest.of("x", "en", "not a code"), T))
				.isInstanceOf(ProviderException.UnsupportedLanguagePair.class);
		// ADR 0040: any catalogue-shaped pair is translated; the registry decides which pairs are routed
		assertThat(mock.translate(TranslateRequest.of("x", "fr", "am"), T).translatedText().value())
				.isEqualTo("[mock:am] x");
		assertThatThrownBy(() -> mock.translate(TranslateRequest.of("x", "en", "en"), T))
				.isInstanceOf(ProviderException.UnsupportedLanguagePair.class);
	}

	@Test
	void transformsAndDetection() {
		assertThat(mock.llmTransform(TransformRequest.of(AiFeature.GRAMMAR_CORRECTION, "  too   many  spaces ", "en"), T)
				.resultText().value()).isEqualTo("too many spaces");
		assertThat(mock.llmTransform(TransformRequest.of(AiFeature.REWRITE, "Hi", "en"), T).resultText().value())
				.isEqualTo("[mock:rewrite] Hi");

		var ethiopic = mock.detectLanguage(DetectRequest.of("ሰላም ነው"), T);
		assertThat(ethiopic.languages().get(0).languageCode()).isEqualTo("am");
		assertThat(ethiopic.languages().get(0).scriptCode()).isEqualTo("Ethi");
		assertThat(ethiopic.languages()).extracting(l -> l.languageCode()).containsExactly("am", "ti");
		assertThat(mock.detectLanguage(DetectRequest.of("hello"), T).languages().get(0).languageCode()).isEqualTo("en");
		assertThat(mock.detectLanguage(new DetectRequest(UserText.of("ሰላም"), List.of("ti")), T).languages())
				.extracting(l -> l.languageCode()).containsExactly("ti");
		assertThat(mock.detectLanguage(DetectRequest.of("12345"), T).languages().get(0).languageCode()).isEqualTo("und");
	}

	@Test
	void transformRejectsNonTransformFeatures() {
		assertThatThrownBy(() -> TransformRequest.of(AiFeature.TRANSLATE, "x", "en"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void everyFailureModeThrowsItsTypedError() {
		var expected = java.util.Map.of(FailureMode.UNAVAILABLE, Kind.UNAVAILABLE, FailureMode.TIMEOUT, Kind.TIMEOUT,
				FailureMode.CONTENT_REJECTED, Kind.CONTENT_REJECTED, FailureMode.UNSUPPORTED_LANGUAGE_PAIR,
				Kind.UNSUPPORTED_LANGUAGE_PAIR, FailureMode.QUOTA_EXHAUSTED, Kind.QUOTA_EXHAUSTED_AT_PROVIDER,
				FailureMode.AUTHENTICATION_FAILED, Kind.AUTHENTICATION_FAILED, FailureMode.INVALID_RESPONSE,
				Kind.INVALID_RESPONSE);
		for (var e : expected.entrySet()) {
			mock.setBehavior(Behavior.failing(e.getKey()));
			assertThatThrownBy(() -> mock.translate(TranslateRequest.of("x", "en", "am"), T))
					.isInstanceOfSatisfying(ProviderException.class, ex -> {
						assertThat(ex.kind()).isEqualTo(e.getValue());
						assertThat(ex.providerCode()).isEqualTo("mock");
					});
			assertThatThrownBy(() -> mock.llmTransform(TransformRequest.of(AiFeature.REWRITE, "x", "en"), T))
					.isInstanceOf(ProviderException.class);
			assertThatThrownBy(() -> mock.detectLanguage(DetectRequest.of("x"), T)).isInstanceOf(ProviderException.class);
		}
	}

	@Test
	void failsTheConfiguredNumberOfCallsThenRecovers() {
		mock.setBehavior(Behavior.failingFirst(2, FailureMode.UNAVAILABLE));
		TranslateRequest req = TranslateRequest.of("x", "en", "am");
		assertThatThrownBy(() -> mock.translate(req, T)).isInstanceOf(ProviderException.Unavailable.class);
		assertThatThrownBy(() -> mock.translate(req, T)).isInstanceOf(ProviderException.Unavailable.class);
		assertThat(mock.translate(req, T).translatedText().value()).isEqualTo("[mock:am] x");
		assertThat(mock.callCount()).isEqualTo(3);
	}

	@Test
	void latencyAboveTheTimeoutEndsInTimeoutAfterWaitingOnlyTheTimeout() {
		mock.setBehavior(Behavior.slow(Duration.ofSeconds(30)));
		long start = System.nanoTime();
		assertThatThrownBy(() -> mock.translate(TranslateRequest.of("x", "en", "am"), Duration.ofMillis(150)))
				.isInstanceOf(ProviderException.Timeout.class);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(100), Duration.ofSeconds(5));
	}

	@Test
	void latencyBelowTheTimeoutSucceedsAndIsMeasured() {
		mock.setBehavior(Behavior.slow(Duration.ofMillis(80)));
		var r = mock.translate(TranslateRequest.of("x", "en", "am"), Duration.ofSeconds(5));
		assertThat(r.call().latency()).isGreaterThanOrEqualTo(Duration.ofMillis(70));
	}

	@Test
	void interruptionCancelsAndKeepsTheInterruptFlag() throws Exception {
		mock.setBehavior(Behavior.slow(Duration.ofSeconds(30)));
		var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
		var flag = new java.util.concurrent.atomic.AtomicBoolean();
		Thread t = Thread.ofVirtual().start(() -> {
			try {
				mock.translate(TranslateRequest.of("x", "en", "am"), Duration.ofMinutes(1));
			} catch (Throwable e) {
				result.set(e);
				flag.set(Thread.currentThread().isInterrupted());
			}
		});
		Thread.sleep(100);
		t.interrupt();
		t.join(5000);
		assertThat(result.get()).isInstanceOf(ProviderException.Cancelled.class);
		assertThat(flag).isTrue();
	}

	@Test
	void timeoutMustBePositive() {
		assertThatThrownBy(() -> mock.translate(TranslateRequest.of("x", "en", "am"), Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> mock.translate(TranslateRequest.of("x", "en", "am"), null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void contentNeverAppearsInToStringOrExceptionMessages() {
		TranslateRequest req = TranslateRequest.of("top secret draft", "en", "am");
		assertThat(req.toString()).doesNotContain("top secret");
		assertThat(mock.translate(req, T).toString()).doesNotContain("top secret");
		mock.setBehavior(Behavior.failing(FailureMode.CONTENT_REJECTED));
		assertThatThrownBy(() -> mock.translate(req, T)).satisfies(e -> assertThat(e.toString()).doesNotContain("secret"));
	}

	@Test
	void errorKindsCarryTheContractCodes() {
		assertThat(Kind.UNAVAILABLE.contractCode()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(Kind.TIMEOUT.contractCode()).isEqualTo("PROVIDER_TIMEOUT");
		assertThat(Kind.CONTENT_REJECTED.contractCode()).isEqualTo("CONTENT_REJECTED");
		assertThat(Kind.UNSUPPORTED_LANGUAGE_PAIR.contractCode()).isEqualTo("LANGUAGE_PAIR_UNAVAILABLE");
		assertThat(Kind.CANCELLED.contractCode()).isNull();
		assertThat(Kind.CONTENT_REJECTED.countsAgainstHealth()).isFalse();
		assertThat(Kind.TIMEOUT.countsAgainstHealth()).isTrue();
	}
}
