package com.qalsmartkeyboard.aigateway.adapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.qalsmartkeyboard.aigateway.spi.AiProvider;
import com.qalsmartkeyboard.aigateway.spi.DetectRequest;
import com.qalsmartkeyboard.aigateway.spi.DetectResult;
import com.qalsmartkeyboard.aigateway.spi.DetectResult.DetectedLanguage;
import com.qalsmartkeyboard.aigateway.spi.ProviderCallMetadata;
import com.qalsmartkeyboard.aigateway.spi.ProviderException;
import com.qalsmartkeyboard.aigateway.spi.ProviderUsage;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.TransformResult;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateResult;
import com.qalsmartkeyboard.aigateway.spi.UserText;

/**
 * Deterministic in-process provider for development and tests (B5.1). Output depends only on the input, so tests can
 * assert exact values; latency and failures are configurable (and changeable at runtime) so B5.2 can test timeouts,
 * fallback and circuit breaking without a network. It has no credentials and logs nothing. The configuration refuses
 * to create it under a production-like profile.
 *
 * <p>Behaviour: translation returns {@code "[mock:<dst>] <text>"} for the languages {@code am ti om en}; any other
 * language, or source equal to destination, is an {@code UnsupportedLanguagePair}. Grammar correction collapses
 * whitespace; other transforms return {@code "[mock:<task>] <text>"}. Language detection reads the script: Ethiopic
 * is Amharic (Tigrinya as the alternative), Latin is English, anything else is undetermined ({@code und}).
 * Tokens are estimated as one per four characters.
 */
public final class MockAiProvider implements AiProvider {

	public static final String DEFAULT_CODE = "mock";
	public static final Set<String> LANGUAGES = Set.of("am", "ti", "om", "en");

	/** What goes wrong. */
	public enum FailureMode {
		NONE, UNAVAILABLE, TIMEOUT, CONTENT_REJECTED, UNSUPPORTED_LANGUAGE_PAIR, QUOTA_EXHAUSTED, AUTHENTICATION_FAILED,
		INVALID_RESPONSE
	}

	/**
	 * @param latency simulated processing time; a latency at or above the call timeout ends in a Timeout after waiting
	 *                only the timeout
	 * @param failure the failure raised by the next {@code failingCalls} calls
	 * @param failingCalls how many calls fail before the mock recovers (Integer.MAX_VALUE = always)
	 */
	public record Behavior(Duration latency, FailureMode failure, int failingCalls) {

		public Behavior {
			latency = latency == null ? Duration.ZERO : latency;
			failure = failure == null ? FailureMode.NONE : failure;
			if (latency.isNegative() || failingCalls < 0) {
				throw new IllegalArgumentException("negative latency or failing call count");
			}
		}

		public static Behavior ok() {
			return new Behavior(Duration.ZERO, FailureMode.NONE, 0);
		}

		public static Behavior slow(Duration latency) {
			return new Behavior(latency, FailureMode.NONE, 0);
		}

		public static Behavior failing(FailureMode mode) {
			return new Behavior(Duration.ZERO, mode, Integer.MAX_VALUE);
		}

		public static Behavior failingFirst(int calls, FailureMode mode) {
			return new Behavior(Duration.ZERO, mode, calls);
		}
	}

	private final String code;
	private final AtomicLong sequence = new AtomicLong();
	private final AtomicInteger calls = new AtomicInteger();
	private volatile Behavior behavior;
	private final AtomicInteger remainingFailures = new AtomicInteger();

	public MockAiProvider(String code, Behavior behavior) {
		this.code = code == null || code.isBlank() ? DEFAULT_CODE : code;
		setBehavior(behavior);
	}

	@Override
	public String code() {
		return code;
	}

	/** Replaces the behaviour and resets the failure countdown. Safe while other threads call the provider. */
	public void setBehavior(Behavior behavior) {
		Behavior b = behavior == null ? Behavior.ok() : behavior;
		this.behavior = b;
		this.remainingFailures.set(b.failure() == FailureMode.NONE ? 0 : b.failingCalls());
	}

	/** Calls received since creation (failed ones included), for failover assertions. */
	public int callCount() {
		return calls.get();
	}

	@Override
	public TranslateResult translate(TranslateRequest request, Duration timeout) {
		long start = begin(timeout);
		String source = request.sourceLanguage() != null ? request.sourceLanguage()
				: detect(request.text().value(), List.of()).get(0).languageCode();
		String destination = request.destinationLanguage();
		if (!LANGUAGES.contains(destination) || !LANGUAGES.contains(source) || source.equals(destination)) {
			throw new ProviderException.UnsupportedLanguagePair(code);
		}
		UserText out = UserText.of("[mock:" + destination + "] " + request.text().value());
		return new TranslateResult(out, request.sourceLanguage() == null ? source : null,
				request.sourceLanguage() == null ? 0.97 : null, List.of(), meta("mock-mt-1", start),
				usage(request.text(), out));
	}

	@Override
	public TransformResult llmTransform(TransformRequest request, Duration timeout) {
		long start = begin(timeout);
		String text = request.text().value();
		String result = switch (request.task()) {
			case GRAMMAR_CORRECTION -> text.strip().replaceAll("\\s+", " ");
			default -> "[mock:" + request.task().name().toLowerCase(Locale.ROOT) + "] " + text;
		};
		UserText out = UserText.of(result);
		return new TransformResult(out, List.of(), meta("mock-llm-1", start), usage(request.text(), out));
	}

	@Override
	public DetectResult detectLanguage(DetectRequest request, Duration timeout) {
		long start = begin(timeout);
		List<DetectedLanguage> languages = detect(request.text().value(), request.candidates());
		boolean romanized = false;
		return new DetectResult(languages, false, romanized, meta("mock-lid-1", start),
				ProviderUsage.ofCharacters(request.text().characters(), 0));
	}

	private static List<DetectedLanguage> detect(String text, List<String> candidates) {
		boolean ethiopic = text.codePoints().anyMatch(cp -> cp >= 0x1200 && cp <= 0x139F);
		boolean latin = text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN);
		List<DetectedLanguage> all = new ArrayList<>();
		if (ethiopic) {
			all.add(new DetectedLanguage("am", "Ethi", 0.97));
			all.add(new DetectedLanguage("ti", "Ethi", 0.02));
		} else if (latin) {
			all.add(new DetectedLanguage("en", "Latn", 0.97));
		} else {
			all.add(new DetectedLanguage("und", null, 0.0));
		}
		if (candidates.isEmpty()) {
			return all;
		}
		List<DetectedLanguage> filtered = all.stream().filter(l -> candidates.contains(l.languageCode())).toList();
		return filtered.isEmpty() ? List.of(new DetectedLanguage("und", null, 0.0)) : filtered;
	}

	/** Applies latency and failure injection; returns the start time for the latency measurement. */
	private long begin(Duration timeout) {
		AiProvider.requirePositive(timeout);
		long start = System.nanoTime();
		calls.incrementAndGet();
		Behavior b = behavior;
		try {
			if (!b.latency().isZero()) {
				boolean exceeds = b.latency().compareTo(timeout) >= 0;
				Thread.sleep(exceeds ? timeout : b.latency());
				if (exceeds) {
					throw new ProviderException.Timeout(code, null);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ProviderException.Cancelled(code);
		}
		if (b.failure() != FailureMode.NONE && remainingFailures.getAndUpdate(n -> n > 0 && n != Integer.MAX_VALUE ? n - 1 : n) > 0) {
			throw failure(b.failure());
		}
		return start;
	}

	private ProviderException failure(FailureMode mode) {
		return switch (mode) {
			case UNAVAILABLE -> new ProviderException.Unavailable(code, null);
			case TIMEOUT -> new ProviderException.Timeout(code, null);
			case CONTENT_REJECTED -> new ProviderException.ContentRejected(code);
			case UNSUPPORTED_LANGUAGE_PAIR -> new ProviderException.UnsupportedLanguagePair(code);
			case QUOTA_EXHAUSTED -> new ProviderException.QuotaExhaustedAtProvider(code, Duration.ofSeconds(60));
			case AUTHENTICATION_FAILED -> new ProviderException.AuthenticationFailed(code);
			case INVALID_RESPONSE -> new ProviderException.InvalidResponse(code, null);
			case NONE -> throw new IllegalStateException();
		};
	}

	private ProviderCallMetadata meta(String model, long startNanos) {
		return new ProviderCallMetadata(code, model, code + "-" + sequence.incrementAndGet(),
				Duration.ofNanos(System.nanoTime() - startNanos));
	}

	private static ProviderUsage usage(UserText in, UserText out) {
		return new ProviderUsage(in.characters(), out.characters(), tokens(in), tokens(out), null);
	}

	private static long tokens(UserText t) {
		return Math.ceilDiv(t.characters(), 4);
	}
}
