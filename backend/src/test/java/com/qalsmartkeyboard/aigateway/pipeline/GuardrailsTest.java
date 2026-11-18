package com.qalsmartkeyboard.aigateway.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.Character.UnicodeScript;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.aigateway.pipeline.Guardrails.Expectation;
import com.qalsmartkeyboard.aigateway.pipeline.Guardrails.Flag;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;

/** SD 11.4 output checks, one rule at a time. */
class GuardrailsTest {

	final Guardrails guardrails = new Guardrails(Guardrails.Config.DEFAULT);

	static Expectation translateTo(String language, UnicodeScript script) {
		return new Expectation(AiFeature.TRANSLATE, script, language, null, false, null);
	}

	static Expectation transform(AiFeature feature, UnicodeScript script, String mode) {
		return new Expectation(feature, script, null, mode, false, null);
	}

	@Test
	void ethiopicTranslationPasses() {
		assertThat(guardrails.check("Are you coming tomorrow?", "ነገ ትመጣለህ?", translateTo("am", UnicodeScript.ETHIOPIC)))
				.isEmpty();
	}

	@Test
	void latinOutputForAmharicIsAScriptMismatch() {
		assertThat(guardrails.check("hello my friend", "[mock:am] hello my friend",
				translateTo("am", UnicodeScript.ETHIOPIC))).contains(Flag.SCRIPT_MISMATCH);
	}

	@Test
	void namesAndUrlsCopiedFromTheInputDoNotCountAgainstTheScript() {
		String input = "Abebe, please read https://example.org/a before Monday";
		String output = "Abebe፣ እባክህ ከሰኞ በፊት https://example.org/a አንብብ";
		assertThat(guardrails.check(input, output, translateTo("am", UnicodeScript.ETHIOPIC))).isEmpty();
	}

	@Test
	void anEchoOfTheInputIsNotATranslation() {
		// every word copied: the copy exemption does not apply, and the script is wrong
		assertThat(guardrails.check("Hello, how are you?", "Hello how are you",
				translateTo("am", UnicodeScript.ETHIOPIC))).contains(Flag.SCRIPT_MISMATCH);
		// same script (en -> om): caught as a no-op
		assertThat(guardrails.check("Good morning my friend", "Good morning my friend",
				translateTo("om", UnicodeScript.LATIN))).contains(Flag.NO_OP);
	}

	@Test
	void mostlyEnglishOutputForOromoIsALanguageMismatch() {
		assertThat(guardrails.check("ሰላም ነህ ወይ? ነገ እንገናኝ", "Are you well? We will meet tomorrow at the office",
				translateTo("om", UnicodeScript.LATIN))).contains(Flag.LANGUAGE_MISMATCH);
		assertThat(guardrails.check("ሰላም ነህ ወይ? ነገ እንገናኝ", "Nagaa dha? Boru wal arguna",
				translateTo("om", UnicodeScript.LATIN))).isEmpty();
		assertThat(guardrails.check("ሰላም ነህ ወይ? ነገ እንገናኝ", "Are you well? We will meet tomorrow at the office",
				translateTo("en", UnicodeScript.LATIN))).isEmpty();
	}

	@Test
	void transformsKeepAboutTheInputsMixOfScripts() {
		// code-switched Amharic with an English word: grammar output with the same mix passes
		assertThat(guardrails.check("ሰላም  meeting ነገ ነው", "ሰላም meeting ነገ ነው።",
				transform(AiFeature.GRAMMAR_CORRECTION, UnicodeScript.ETHIOPIC, "GRAMMAR"))).isEmpty();
		// the same request answered in Latin script fails
		assertThat(guardrails.check("ሰላም  meeting ነገ ነው", "Hello, the meeting is tomorrow.",
				transform(AiFeature.GRAMMAR_CORRECTION, UnicodeScript.ETHIOPIC, "GRAMMAR")))
				.contains(Flag.SCRIPT_MISMATCH);
	}

	@Test
	void lengthBoundsDependOnTheFeature() {
		String input = "This sentence has a few words in it.";
		assertThat(guardrails.check(input, "x".repeat(200),
				transform(AiFeature.GRAMMAR_CORRECTION, UnicodeScript.LATIN, null))).contains(Flag.LENGTH_EXCEEDED);
		assertThat(guardrails.check(input, "Short.", transform(AiFeature.GRAMMAR_CORRECTION, UnicodeScript.LATIN,
				null))).contains(Flag.LENGTH_TOO_SHORT);
		assertThat(guardrails.check(input, "Short.", transform(AiFeature.REWRITE, UnicodeScript.LATIN, "SHORTEN")))
				.isEmpty();
		assertThat(guardrails.check("Hi", "Hello", translateTo("om", UnicodeScript.LATIN))).isEmpty();
	}

	@Test
	void emptyOutputFails() {
		assertThat(guardrails.check("hello", "   ", translateTo("am", UnicodeScript.ETHIOPIC)))
				.containsExactly(Flag.EMPTY_OUTPUT);
	}

	@Test
	void refusalsAreDetectedUnlessTheInputSaidTheSame() {
		assertThat(guardrails.check("ይህን ተርጉምልኝ እባክህ", "I'm sorry, but as an AI I cannot assist with that.",
				translateTo("en", UnicodeScript.LATIN))).contains(Flag.REFUSAL);
		assertThat(guardrails.check("I cannot help with the move tomorrow", "I can’t help with the move tomorrow.",
				transform(AiFeature.GRAMMAR_CORRECTION, UnicodeScript.LATIN, null))).doesNotContain(Flag.REFUSAL);
		assertThat(guardrails.check("I'm sorry, I can't come", "I'm sorry, I can't come.",
				transform(AiFeature.GRAMMAR_CORRECTION, UnicodeScript.LATIN, null))).isEmpty();
	}

	@Test
	void protectedTokensMustSurvive() {
		Expectation e = translateTo("am", UnicodeScript.ETHIOPIC);
		assertThat(guardrails.check("Call 0911223344 or mail abebe@example.com", "ደውል ወይም ኢሜይል ላክ", e))
				.contains(Flag.PROTECTED_TOKEN_LOST);
		assertThat(guardrails.check("Call 0911223344 or mail abebe@example.com",
				"0911223344 ደውል ወይም abebe@example.com ኢሜይል ላክ", e)).isEmpty();
		assertThat(guardrails.check("The price is 1,500 birr", "ዋጋው 1500 ብር ነው", e)).isEmpty();
		// a calendar/clock/numeral conversion may change numbers
		assertThat(guardrails.check("on 2026-11-18", "በኅዳር 9 2019",
				new Expectation(AiFeature.TRANSLATE, UnicodeScript.ETHIOPIC, "am", null, true, null))).isEmpty();
	}

	@Test
	void echoedInstructionsAreAPromptLeak() {
		String instructions = "You are a careful translator for Ethiopian languages.\nAddress the reader formally.";
		assertThat(guardrails.check("hello", "You are a careful translator for Ethiopian languages. ሰላም",
				new Expectation(AiFeature.TRANSLATE, UnicodeScript.ETHIOPIC, "am", null, false, instructions)))
				.contains(Flag.PROMPT_LEAK);
	}

	@Test
	void noScriptExpectationSkipsTheScriptCheck() {
		assertThat(guardrails.check("ሰላም", "hello", transform(AiFeature.REWRITE, null, null))).isEmpty();
	}
}
