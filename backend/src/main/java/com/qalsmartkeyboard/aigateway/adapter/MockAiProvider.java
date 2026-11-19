package com.qalsmartkeyboard.aigateway.adapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.qalsmartkeyboard.aigateway.spi.AiProvider;
import com.qalsmartkeyboard.aigateway.spi.ContextPart;
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
 * <p>Behaviour: translation returns {@code "[mock:<dst>] <text>"} for the languages {@code am ti om en ar}; any other
 * language, or source equal to destination, is an {@code UnsupportedLanguagePair}. Grammar correction collapses
 * whitespace; GRAMMAR_CORRECTION, REWRITE and TONE_ADJUST return {@code "[mock:<task>] <text>"} (with an output language
 * other than the text's: a fixed word in that language, then the text). Language detection reads the script: Ethiopic
 * is Amharic (Tigrinya as the alternative), Arabic is Arabic, Latin is English, anything else is undetermined ({@code und}).
 * Tokens are estimated as one per four characters.
 *
 * <p>The ADR 0027 features answer in the requested output language with fixed phrases, so the output guardrails pass
 * and a client can develop against them (all deterministic; {@code variation} changes the result):
 * <ul>
 * <li>SUMMARIZE: a label ({@code Summary:}, {@code ማጠቃለያ፦}...) and the first 280 characters of the text;
 * EXPLAIN_TEXT: a label ({@code Explanation:}...) and the text.</li>
 * <li>SMART_REPLY: {@code count} fixed replies in the reply language, separated by {@code ---} lines, rotated by
 * {@code variation}.</li>
 * <li>DRAFT_DOCUMENT: {@code Subject: ...} (mode REPLY: {@code Re: ...}; IMPROVE: none), a greeting with the recipient
 * name or the {@code [recipient name]} placeholder, the user's text, a {@code [date]} placeholder line (mode NEW) and
 * a closing with the sender name or the {@code [your name]} placeholder.</li>
 * </ul>
 */
public final class MockAiProvider implements AiProvider {

	public static final String DEFAULT_CODE = "mock";
	public static final Set<String> LANGUAGES = Set.of("am", "ti", "om", "en", "ar");

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
		String output = request.outputLanguage();
		boolean otherLanguage = output != null && LANGUAGES.contains(output) && !output.equals(request.language());
		// the language the fixed phrases are written in: the output language, else the text's
		String language = output != null ? output
				: request.language() != null ? request.language() : detect(text, List.of()).get(0).languageCode();
		String result = switch (request.task()) {
			case GRAMMAR_CORRECTION -> text.strip().replaceAll("\\s+", " ");
			case SUMMARIZE -> phrase(language, Phrase.SUMMARY) + " " + first(text, 280);
			case EXPLAIN_TEXT -> phrase(language, Phrase.EXPLANATION) + " " + text;
			case SMART_REPLY -> replies(request, language);
			case DRAFT_DOCUMENT -> email(request, language);
			default -> otherLanguage ? phrase(output, Phrase.MOCK) + " " + text
					: "[mock:" + request.task().name().toLowerCase(Locale.ROOT) + "] " + text;
		};
		UserText out = UserText.of(result);
		int in = request.inputCharacters();
		return new TransformResult(out, List.of(), meta("mock-llm-1", start),
				new ProviderUsage(in, out.characters(), (long) Math.ceilDiv(in, 4), tokens(out), null));
	}

	// ---------------------------------------------------------------- fixed phrases (ADR 0027)

	private enum Phrase {
		MOCK, SUMMARY, EXPLANATION, SUBJECT, GREETING, DATE_LINE, CLOSING, YOUR_NAME, RECIPIENT
	}

	/** Fixed phrases per output language (en when unknown). Mock text only: not reviewed translations. */
	private static final Map<String, Map<Phrase, String>> PHRASES = Map.of(
			"en", Map.of(Phrase.MOCK, "[mock]", Phrase.SUMMARY, "Summary:", Phrase.EXPLANATION, "Explanation:",
					Phrase.SUBJECT, "Mock email subject", Phrase.GREETING, "Dear", Phrase.DATE_LINE,
					"We can meet on [date].", Phrase.CLOSING, "Best regards,", Phrase.YOUR_NAME, "[your name]",
					Phrase.RECIPIENT, "[recipient name]"),
			"am", Map.of(Phrase.MOCK, "[ሙከራ]", Phrase.SUMMARY, "ማጠቃለያ፦", Phrase.EXPLANATION, "ማብራሪያ፦",
					Phrase.SUBJECT, "የሙከራ ኢሜይል ርዕስ", Phrase.GREETING, "ውድ", Phrase.DATE_LINE, "በ[ቀን] መገናኘት እንችላለን።",
					Phrase.CLOSING, "ከሰላምታ ጋር፣", Phrase.YOUR_NAME, "[ስምዎ]", Phrase.RECIPIENT, "[የተቀባይ ስም]"),
			"ti", Map.of(Phrase.MOCK, "[ፈተና]", Phrase.SUMMARY, "ጽማቝ፦", Phrase.EXPLANATION, "መብርሂ፦",
					Phrase.SUBJECT, "ናይ ፈተና ኢመይል ኣርእስቲ", Phrase.GREETING, "ክቡር", Phrase.DATE_LINE,
					"ብ[ዕለት] ክንራኸብ ንኽእል ኢና።", Phrase.CLOSING, "ምስ ሰላምታ፣", Phrase.YOUR_NAME, "[ስምኩም]",
					Phrase.RECIPIENT, "[ስም ተቐባላይ]"),
			"om", Map.of(Phrase.MOCK, "[yaalii]", Phrase.SUMMARY, "Cuunfaa:", Phrase.EXPLANATION, "Ibsa:",
					Phrase.SUBJECT, "Mata duree imeelii yaalii", Phrase.GREETING, "Kabajamoo", Phrase.DATE_LINE,
					"Guyyaa [guyyaa] wal arguu dandeenya.", Phrase.CLOSING, "Nagaa wajjin,", Phrase.YOUR_NAME,
					"[maqaa keessan]", Phrase.RECIPIENT, "[maqaa fudhataa]"),
			"ar", Map.of(Phrase.MOCK, "[تجريبي]", Phrase.SUMMARY, "ملخص:", Phrase.EXPLANATION, "شرح:",
					Phrase.SUBJECT, "موضوع بريد تجريبي", Phrase.GREETING, "عزيزي", Phrase.DATE_LINE,
					"يمكننا اللقاء في [التاريخ].", Phrase.CLOSING, "مع أطيب التحيات،", Phrase.YOUR_NAME, "[اسمك]",
					Phrase.RECIPIENT, "[اسم المستلم]"));

	private static final Map<String, List<String>> REPLIES = Map.of(
			"en", List.of("Thank you for your message.", "Sounds good, I will get back to you soon.", "Thanks, noted.",
					"That is a good idea.", "Let us talk about it."),
			"am", List.of("ስለ መልእክትዎ እናመሰግናለን።", "እሺ፣ በቅርቡ እመልስልዎታለሁ።", "ተቀብያለሁ፣ አመሰግናለሁ።", "ጥሩ ሀሳብ ነው።",
					"እንነጋገርበታለን።"),
			"ti", List.of("ስለ መልእኽትኹም የቐንየልና።", "ሕራይ፣ ቀልጢፈ ክምልሰልኩም እየ።", "ተቐቢለዮ፣ የቐንየለይ።", "ጽቡቕ ሓሳብ እዩ።",
					"ክንዘራረበሉ ኢና።"),
			"om", List.of("Ergaa keessaniif galatoomaa.", "Tole, dafee isinii deebisa.", "Fudhadheera, galatoomaa.",
					"Yaada gaarii dha.", "Itti haasofna."),
			"ar", List.of("شكرا على رسالتك.", "حسنا، سأرد عليك قريبا.", "تم، شكرا لك.", "هذه فكرة جيدة.",
					"لنتحدث عن ذلك."));

	private static String phrase(String language, Phrase p) {
		return PHRASES.getOrDefault(language == null ? "en" : language, PHRASES.get("en")).get(p);
	}

	/** Links, e-mail addresses and numbers of 3+ digits in the user's notes, which a real reply would keep. */
	private static final Pattern KEPT = Pattern.compile(
			"(?i)\\b(?:https?://|www\\.)\\S+|[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.\\p{L}{2,}|\\d{3,}");

	private static String kept(TransformRequest r) {
		String notes = part(r, ContextPart.Kind.USER_NOTES);
		if (notes == null) {
			return "";
		}
		List<String> tokens = new ArrayList<>();
		Matcher m = KEPT.matcher(notes);
		while (m.find()) {
			tokens.add(m.group());
		}
		return tokens.isEmpty() ? "" : " " + String.join(" ", tokens);
	}

	private static String replies(TransformRequest r, String language) {
		List<String> all = REPLIES.getOrDefault(language, REPLIES.get("en"));
		List<String> out = new ArrayList<>();
		for (int i = 0; i < Math.min(Math.max(1, r.count()), all.size()); i++) {
			out.add(all.get((i + r.variation()) % all.size()) + kept(r));
		}
		return String.join("\n---\n", out);
	}

	private static String email(TransformRequest r, String language) {
		String mode = r.mode() == null ? "NEW" : r.mode();
		String recipient = part(r, ContextPart.Kind.RECIPIENT_NAME);
		String sender = part(r, ContextPart.Kind.SENDER_NAME);
		StringBuilder sb = new StringBuilder();
		String subject = phrase(language, Phrase.SUBJECT) + (r.variation() > 0 ? " (" + r.variation() + ")" : "");
		switch (mode) {
			case "REPLY" -> sb.append("Subject: Re: ").append(subject).append("\n\n");
			case "IMPROVE" -> {
			}
			default -> sb.append("Subject: ").append(subject).append("\n\n");
		}
		sb.append(phrase(language, Phrase.GREETING)).append(' ')
				.append(recipient != null ? recipient : phrase(language, Phrase.RECIPIENT)).append(",\n\n");
		// a reply answers with a fixed sentence (the notes may be in another language) plus their links and numbers
		sb.append("REPLY".equals(mode) ? REPLIES.getOrDefault(language, REPLIES.get("en")).get(0) + kept(r)
				: r.text().value().strip()).append("\n\n");
		if ("NEW".equals(mode)) {
			sb.append(phrase(language, Phrase.DATE_LINE)).append("\n\n");
		}
		sb.append(phrase(language, Phrase.CLOSING)).append('\n')
				.append(sender != null ? sender : phrase(language, Phrase.YOUR_NAME));
		return sb.toString();
	}

	private static String part(TransformRequest r, ContextPart.Kind kind) {
		return r.context().stream().filter(p -> p.kind() == kind).map(p -> p.text().value().strip()).findFirst()
				.orElse(null);
	}

	private static String first(String text, int codePoints) {
		String t = text.strip();
		if (t.codePointCount(0, t.length()) <= codePoints) {
			return t;
		}
		return t.substring(0, t.offsetByCodePoints(0, codePoints)) + "…";
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
		boolean arabic = text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.ARABIC);
		List<DetectedLanguage> all = new ArrayList<>();
		if (arabic && !ethiopic) {
			all.add(new DetectedLanguage("ar", "Arab", 0.97));
		} else if (ethiopic) {
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
