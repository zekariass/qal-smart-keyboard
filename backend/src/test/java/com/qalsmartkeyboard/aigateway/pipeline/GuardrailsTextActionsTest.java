package com.qalsmartkeyboard.aigateway.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.Character.UnicodeScript;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.aigateway.pipeline.Guardrails.Expectation;
import com.qalsmartkeyboard.aigateway.pipeline.Guardrails.Flag;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;

/** ADR 0027: the output checks of the text actions, the reply suggestions and the e-mail writer. */
class GuardrailsTextActionsTest {

	final Guardrails guardrails = new Guardrails(Guardrails.Config.DEFAULT);

	static Expectation of(AiFeature feature, UnicodeScript script, String language, String mode, boolean cross) {
		return new Expectation(feature, script, language, mode, false, null, cross);
	}

	@Test
	void aTransformIntoAnotherLanguageIsCheckedLikeATranslation() {
		String input = "Please send the report before Friday";
		Expectation intoAmharic = of(AiFeature.REWRITE, UnicodeScript.ETHIOPIC, "am", "SIMPLIFY", true);
		assertThat(guardrails.check(input, "እባክዎ ሪፖርቱን ከአርብ በፊት ይላኩ።", intoAmharic)).isEmpty();
		// an echo is a no-op and the wrong script
		assertThat(guardrails.check(input, input, intoAmharic)).contains(Flag.SCRIPT_MISMATCH, Flag.NO_OP);
		// the same echo is fine for a same-language rewrite (no explicit output language)
		assertThat(guardrails.check(input, input, of(AiFeature.REWRITE, UnicodeScript.LATIN, "en", "SIMPLIFY",
				false))).isEmpty();
		// Afaan Oromoo asked for, English given back
		assertThat(guardrails.check("ሪፖርቱን ላክልኝ", "Please send me the report today if you can",
				of(AiFeature.TONE_ADJUST, UnicodeScript.LATIN, "om", null, true))).contains(Flag.LANGUAGE_MISMATCH);
	}

	@Test
	void aCrossLanguageTransformMayChangeLength() {
		String amharic = "ሪፖርቱን ቶሎ ላክልኝ";
		String english = "Could you please send me the report as soon as possible? Thank you very much, I do appreciate it. Thanks."; // 105 characters
		// 13 characters in: the same-language TONE_ADJUST bound is 3 x 13 + 60 = 99, the translation bound 4 x 13 + 60
		assertThat(guardrails.check(amharic, english, of(AiFeature.TONE_ADJUST, UnicodeScript.LATIN, "en",
				null, false))).contains(Flag.LENGTH_EXCEEDED);
		assertThat(guardrails.check(amharic, english, of(AiFeature.TONE_ADJUST, UnicodeScript.LATIN, "en",
				null, true))).isEmpty();
	}

	@Test
	void summariesAndExplanationsAreNotHeldToTheInputsLength() {
		String text = "word ".repeat(400);
		assertThat(guardrails.check(text, "A short summary.", of(AiFeature.SUMMARIZE, UnicodeScript.LATIN, "en",
				null, false))).isEmpty();
		assertThat(guardrails.check("Eat the elephant", "x".repeat(2200),
				of(AiFeature.EXPLAIN_TEXT, UnicodeScript.LATIN, "en", null, false))).contains(Flag.LENGTH_EXCEEDED);
		assertThat(guardrails.check("Eat the elephant", "An idiom: do a big task one small step at a time. "
				.repeat(10), of(AiFeature.EXPLAIN_TEXT, UnicodeScript.LATIN, "en", null, false))).isEmpty();
	}

	@Test
	void aComposedReplyMayDeclineWithoutBeingARefusal() {
		Expectation reply = of(AiFeature.SMART_REPLY, UnicodeScript.LATIN, "en", null, false);
		assertThat(guardrails.check("Can you help me move on Saturday?",
				"Sorry, I can't help with the move on Saturday.", reply)).isEmpty();
		assertThat(guardrails.check("Can you help me move on Saturday?",
				"As an AI language model, I cannot move furniture.", reply)).contains(Flag.REFUSAL);
		// a translation still treats it as a refusal
		assertThat(guardrails.check("Translate this", "I can't help with that.",
				new Expectation(AiFeature.TRANSLATE, UnicodeScript.LATIN, "en", null, false, null)))
				.contains(Flag.REFUSAL);
	}

	@Test
	void placeholdersInAComposedEmailDoNotCountAgainstTheScript() {
		Expectation email = of(AiFeature.DRAFT_DOCUMENT, UnicodeScript.ETHIOPIC, "am", "NEW", true);
		String body = "ውድ [recipient name]፣\n\nነገ ታምሜ ስለሆነ ወደ ስራ አልመጣም። ሪፖርቱን [date] እልካለሁ።\n\nከሰላምታ ጋር፣\n[your name]";
		assertThat(guardrails.check("Tell my boss I am sick tomorrow", body, email)).isEmpty();
	}

	@Test
	void onlyTheProtectedInputMustKeepItsNumbersAndLinks() {
		Expectation email = of(AiFeature.DRAFT_DOCUMENT, UnicodeScript.LATIN, "en", "REPLY", false);
		String original = "Dear Abebe, invoice 48213 is attached, see https://example.org/inv. Regards, Sarah";
		String notes = "Tell her I will pay 25000 birr on Monday";
		String all = original + "\n\n" + notes;
		String reply = "Dear Sarah,\n\nThank you. I will pay the 25000 birr on Monday.\n\nBest regards,\nAbebe";
		// the e-mail answered need not be repeated: only the user's notes are protected
		assertThat(guardrails.check(all, notes, reply, email)).isEmpty();
		assertThat(guardrails.check(all, notes, reply.replace("25000", "twenty-five thousand"), email))
				.contains(Flag.PROTECTED_TOKEN_LOST);
		// no protected input (a summary): nothing to keep
		assertThat(guardrails.check(original, null, "An invoice was sent.",
				of(AiFeature.SUMMARIZE, UnicodeScript.LATIN, "en", null, false))).isEmpty();
	}

	@Test
	void anEmailMayBeMuchLongerThanItsDescription() {
		Expectation email = of(AiFeature.DRAFT_DOCUMENT, UnicodeScript.LATIN, "en", "NEW", false);
		assertThat(guardrails.check("ask for leave monday", "Dear Ms. Smith, I would like to ask for leave. "
				.repeat(20), email)).isEmpty();
		// an improved draft may not lose most of it
		Expectation improve = of(AiFeature.DRAFT_DOCUMENT, UnicodeScript.LATIN, "en", "IMPROVE", false);
		assertThat(guardrails.check("This is my long draft about the project plan. ".repeat(10), "Hi.", improve))
				.contains(Flag.LENGTH_TOO_SHORT);
	}

	@Test
	void theSixArgumentExpectationKeepsTheB53Behaviour() {
		Expectation old = new Expectation(AiFeature.REWRITE, UnicodeScript.LATIN, "en", "NATURAL", false, null);
		assertThat(old.crossLanguage()).isFalse();
		assertThat(guardrails.check("hello there my friend", "hello there my friend", old)).isEmpty();
	}
}
