package com.qalsmartkeyboard.aigateway.pipeline;

import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.qalsmartkeyboard.aigateway.spi.AiFeature;

/**
 * Output checks on a provider result before it is shown or charged (SD 11.4, B5.3). Pure and stateless; the pipeline
 * decides what a failure means (one retry on the same provider, then one fallback, then
 * {@code OUTPUT_GUARDRAIL_FAILED} with no charge).
 *
 * <p>Checks, each reported as a {@link Flag} (the names go to {@code ai_requests.guardrail_flags}; text never does):
 * <ul>
 * <li><b>EMPTY_OUTPUT</b>: nothing but whitespace.</li>
 * <li><b>SCRIPT_MISMATCH</b>: fewer than {@code minScriptRatio} (default 90 %) of the output's letters are in the
 * expected script (Ethiopic for {@code am}/{@code ti}, Latin for {@code om}/{@code en}; from
 * {@code supported_languages.script_code}). Protected tokens are not counted: URLs, e-mail addresses, {@code @handles},
 * {@code #tags} and, for translations, words copied verbatim from the input (names, brand words) unless the output
 * consists only of copied words. Transforms keep the input's language, often mixed, so they only have to keep about
 * the input's share of the expected script.</li>
 * <li><b>LANGUAGE_MISMATCH</b>: a light language-ID sanity check for languages that share a script. The script check
 * cannot tell Afaan Oromoo from English, so a translation into a Latin-script language other than English whose words
 * are mostly English function words is treated as untranslated. (Amharic versus Tigrinya is not distinguishable here;
 * an on-device or provider language-ID model can be plugged in later.)</li>
 * <li><b>LENGTH_EXCEEDED</b> / <b>LENGTH_TOO_SHORT</b>: output length outside per-feature bounds relative to the input
 * (code points; generous, because Ge'ez is denser than Latin script).</li>
 * <li><b>NO_OP</b>: the output equals the input (whitespace-normalised) where a change was expected: translation, and
 * rewrites asked to shorten or expand, for inputs of three words or more.</li>
 * <li><b>REFUSAL</b>: a model refusal ("as an AI language model", "I can't assist with") that the input does not
 * itself contain.</li>
 * <li><b>PROTECTED_TOKEN_LOST</b>: a URL, e-mail address or number of three or more digits from the input is missing
 * from the output. Numbers are skipped when a calendar, clock or numeral conversion was requested.</li>
 * <li><b>PROMPT_LEAK</b>: the output repeats a line of the rendered instructions.</li>
 * </ul>
 */
public final class Guardrails {

	/** What a check found; names are stored, never the text that triggered them. */
	public enum Flag {
		EMPTY_OUTPUT, SCRIPT_MISMATCH, LANGUAGE_MISMATCH, LENGTH_EXCEEDED, LENGTH_TOO_SHORT, NO_OP, REFUSAL,
		PROTECTED_TOKEN_LOST, PROMPT_LEAK
	}

	/** @param minScriptRatio share of letters (0..1) that must be in the expected script */
	public record Config(double minScriptRatio) {

		public Config {
			if (!(minScriptRatio > 0 && minScriptRatio <= 1)) {
				throw new IllegalArgumentException("minScriptRatio must be in (0, 1]");
			}
		}

		public static final Config DEFAULT = new Config(0.9);
	}

	/**
	 * What the output should look like.
	 *
	 * @param expectedScript   script of the output language; null skips the script check (unknown language)
	 * @param expectedLanguage output language code, or null
	 * @param mode             transform mode (SHORTEN, EXPAND...) or null
	 * @param numbersMayChange a calendar, clock or numeral conversion was requested
	 * @param instructions     the rendered prompt (to detect it being echoed), or null
	 */
	public record Expectation(AiFeature feature, UnicodeScript expectedScript, String expectedLanguage, String mode,
			boolean numbersMayChange, String instructions) {
	}

	private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"]+");
	private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}");
	private static final Pattern HANDLE_OR_TAG = Pattern.compile("(?<![\\p{L}\\p{N}])[@#][\\p{L}\\p{N}_]+");
	/** A number, allowing grouping separators between digits (1,000 / 1.000 / 1 000). */
	private static final Pattern NUMBER = Pattern.compile("\\d(?:[\\d,.\\u00A0' ]*\\d)?");

	/** Refusal phrases, in the normalised form {@link #refusalForm} produces (contractions, straight apostrophes). */
	private static final List<String> REFUSALS = List.of("as an ai", "as a language model", "i can't assist",
			"i can't help with", "i'm unable to help", "i can't comply", "i won't be able to help",
			"i'm not able to help", "i can't fulfill", "i can't provide a translation", "i'm unable to translate",
			"i can't translate this");

	/** Very common English words that are not Afaan Oromoo words. */
	private static final Set<String> ENGLISH_FUNCTION_WORDS = Set.of("the", "and", "is", "are", "was", "were", "be",
			"to", "of", "in", "on", "at", "for", "with", "you", "your", "we", "they", "he", "she", "it", "this", "that",
			"will", "can", "do", "does", "not", "have", "has", "my", "me", "our", "please", "how", "what", "there",
			"from", "would", "should", "could", "about", "tomorrow", "today", "thank", "thanks");

	private final Config config;

	public Guardrails(Config config) {
		this.config = config == null ? Config.DEFAULT : config;
	}

	/** Every check that fails; empty = the output may be used. */
	public Set<Flag> check(String input, String output, Expectation e) {
		Set<Flag> flags = EnumSet.noneOf(Flag.class);
		if (output == null || output.isBlank()) {
			flags.add(Flag.EMPTY_OUTPUT);
			return flags;
		}
		int in = codePoints(input);
		int out = codePoints(output);
		LengthBounds bounds = LengthBounds.of(e.feature(), e.mode());
		if (out > bounds.max(in)) {
			flags.add(Flag.LENGTH_EXCEEDED);
		}
		if (in >= LengthBounds.MIN_CHECK_INPUT && out < bounds.min(in)) {
			flags.add(Flag.LENGTH_TOO_SHORT);
		}

		boolean translation = e.feature() == AiFeature.TRANSLATE;
		List<String> countedWords = unprotectedWords(output, translation ? words(input) : Set.of());
		if (e.expectedScript() != null && !scriptMatches(input, countedWords, e.expectedScript(), translation)) {
			flags.add(Flag.SCRIPT_MISMATCH);
		}
		if (e.feature() == AiFeature.TRANSLATE && e.expectedScript() == UnicodeScript.LATIN
				&& e.expectedLanguage() != null && !"en".equals(e.expectedLanguage())
				&& mostlyEnglish(countedWords)) {
			flags.add(Flag.LANGUAGE_MISMATCH);
		}
		if (changeExpected(e) && normalise(input).equals(normalise(output)) && wordCount(input) >= 3) {
			flags.add(Flag.NO_OP);
		}
		if (refusal(input, output)) {
			flags.add(Flag.REFUSAL);
		}
		if (lostProtected(input, output, e.numbersMayChange())) {
			flags.add(Flag.PROTECTED_TOKEN_LOST);
		}
		if (leaksPrompt(output, e.instructions())) {
			flags.add(Flag.PROMPT_LEAK);
		}
		return flags;
	}

	/**
	 * Translation: at least {@code minScriptRatio} of the counted letters are in the expected script. Transforms keep the
	 * language of the input, which is often mixed (code-switching), so the output only has to keep roughly the input's
	 * share: {@code min(minScriptRatio, input share - 0.15)}. No letters at all (digits, emoji, protected tokens) passes.
	 */
	private boolean scriptMatches(String input, List<String> words, UnicodeScript expected, boolean translation) {
		double share = share(words, expected);
		if (Double.isNaN(share)) {
			return true;
		}
		double required = config.minScriptRatio();
		if (!translation) {
			double inputShare = share(unprotectedWords(input, Set.of()), expected);
			required = Double.isNaN(inputShare) ? 0 : Math.min(required, inputShare - 0.15);
		}
		return share + 1e-9 >= required;
	}

	/** Share of letters (not COMMON/INHERITED) in {@code expected}; NaN without letters. */
	private static double share(List<String> words, UnicodeScript expected) {
		long letters = 0;
		long matching = 0;
		for (String w : words) {
			for (int i = 0; i < w.length();) {
				int cp = w.codePointAt(i);
				i += Character.charCount(cp);
				if (!Character.isLetter(cp)) {
					continue;
				}
				UnicodeScript s = UnicodeScript.of(cp);
				if (s == UnicodeScript.COMMON || s == UnicodeScript.INHERITED) {
					continue;
				}
				letters++;
				if (s == expected) {
					matching++;
				}
			}
		}
		return letters == 0 ? Double.NaN : (double) matching / letters;
	}

	private static boolean mostlyEnglish(List<String> words) {
		List<String> lower = words.stream().map(w -> core(w).toLowerCase(Locale.ROOT)).filter(w -> !w.isEmpty())
				.toList();
		if (lower.size() < 4) {
			return false;
		}
		long english = lower.stream().filter(ENGLISH_FUNCTION_WORDS::contains).count();
		return english * 2 >= lower.size();
	}

	private static boolean changeExpected(Expectation e) {
		return e.feature() == AiFeature.TRANSLATE
				|| (e.feature() == AiFeature.REWRITE && ("SHORTEN".equals(e.mode()) || "EXPAND".equals(e.mode())));
	}

	private static boolean refusal(String input, String output) {
		String out = refusalForm(output);
		String in = refusalForm(input);
		for (String r : REFUSALS) {
			if (out.contains(r) && !in.contains(r)) {
				return true;
			}
		}
		return false;
	}

	/** Lower case, straight apostrophes, one spelling per contraction ("I cannot" = "I can't"). */
	private static String refusalForm(String s) {
		return apostrophes(s.toLowerCase(Locale.ROOT)).replaceAll("\\s+", " ").replace("cannot", "can't")
				.replace("can not", "can't").replace("i am ", "i'm ").replace("will not", "won't");
	}

	private static boolean lostProtected(String input, String output, boolean numbersMayChange) {
		for (Pattern p : List.of(URL, EMAIL)) {
			Matcher m = p.matcher(input);
			while (m.find()) {
				if (!output.contains(trimTrailingPunctuation(m.group()))) {
					return true;
				}
			}
		}
		if (numbersMayChange) {
			return false;
		}
		Set<String> outNumbers = new HashSet<>();
		Matcher om = NUMBER.matcher(output);
		while (om.find()) {
			outNumbers.add(digits(om.group()));
		}
		String outDigits = String.join(" ", outNumbers);
		Matcher im = NUMBER.matcher(input);
		while (im.find()) {
			String d = digits(im.group());
			if (d.length() >= 3 && !outNumbers.contains(d) && !outDigits.contains(d)) {
				return true;
			}
		}
		return false;
	}

	private static boolean leaksPrompt(String output, String instructions) {
		if (instructions == null) {
			return false;
		}
		for (String line : instructions.split("\\R")) {
			String l = line.strip();
			if (l.length() >= 30 && output.contains(l)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Output words that count for the script share: without URLs, e-mail addresses, handles and tags, and without words
	 * copied verbatim from the input ({@code inputWords}; names, brand words), unless every word with letters was
	 * copied (an echo of the input must not pass as a translation).
	 */
	private static List<String> unprotectedWords(String output, Set<String> inputWords) {
		String stripped = HANDLE_OR_TAG.matcher(EMAIL.matcher(URL.matcher(output).replaceAll(" ")).replaceAll(" "))
				.replaceAll(" ");
		List<String> all = new ArrayList<>();
		List<String> fresh = new ArrayList<>();
		for (String w : stripped.split("\\s+")) {
			String c = core(w);
			if (c.isEmpty()) {
				continue;
			}
			all.add(w);
			if (!inputWords.contains(c.toLowerCase(Locale.ROOT))) {
				fresh.add(w);
			}
		}
		boolean freshLetters = fresh.stream().anyMatch(w -> w.codePoints().anyMatch(Character::isLetter));
		return freshLetters ? fresh : all;
	}

	private static Set<String> words(String text) {
		Set<String> set = new HashSet<>();
		for (String w : text.split("\\s+")) {
			String c = core(w);
			if (!c.isEmpty()) {
				set.add(c.toLowerCase(Locale.ROOT));
			}
		}
		return set;
	}

	/** The word without leading and trailing characters that are neither letters nor digits. */
	private static String core(String w) {
		int start = 0;
		int end = w.length();
		while (start < end && !Character.isLetterOrDigit(w.codePointAt(start))) {
			start += Character.charCount(w.codePointAt(start));
		}
		while (end > start && !Character.isLetterOrDigit(w.codePointBefore(end))) {
			end -= Character.charCount(w.codePointBefore(end));
		}
		return w.substring(start, end);
	}

	private static String normalise(String s) {
		return s.strip().replaceAll("\\s+", " ");
	}

	private static int wordCount(String s) {
		String n = normalise(s);
		return n.isEmpty() ? 0 : n.split(" ").length;
	}

	private static int codePoints(String s) {
		return s.codePointCount(0, s.length());
	}

	private static String digits(String s) {
		return s.replaceAll("\\D", "");
	}

	private static String apostrophes(String s) {
		return s.replace('’', '\'').replace('‘', '\'');
	}

	private static String trimTrailingPunctuation(String s) {
		int end = s.length();
		while (end > 0 && ".,;:!?)]}'\"".indexOf(s.charAt(end - 1)) >= 0) {
			end--;
		}
		return s.substring(0, end);
	}

	/** Output length bounds as {@code ratio x input + slack} (max) and {@code ratio x input} (min). */
	record LengthBounds(double maxRatio, int maxSlack, double minRatio) {

		/** Inputs shorter than this are never "too short" (a one-word answer is fine). */
		static final int MIN_CHECK_INPUT = 20;

		int max(int input) {
			return (int) Math.ceil(maxRatio * input) + maxSlack;
		}

		int min(int input) {
			return (int) Math.floor(minRatio * input);
		}

		static LengthBounds of(AiFeature feature, String mode) {
			return switch (feature) {
				case TRANSLATE -> new LengthBounds(4.0, 40, 0.15);
				case GRAMMAR_CORRECTION -> new LengthBounds(1.5, 20, 0.5);
				case REWRITE -> switch (mode == null ? "" : mode) {
					case "EXPAND" -> new LengthBounds(4.0, 80, 0.5);
					case "SHORTEN" -> new LengthBounds(1.2, 20, 0.05);
					default -> new LengthBounds(2.5, 40, 0.25);
				};
				case TONE_ADJUST -> new LengthBounds(3.0, 60, 0.25);
				default -> new LengthBounds(4.0, 80, 0.05);
			};
		}
	}
}
