package com.qalsmartkeyboard.aigateway.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.Behavior;
import com.qalsmartkeyboard.aigateway.pipeline.OutputFormats;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.ContextPart;
import com.qalsmartkeyboard.aigateway.spi.ContextPart.Kind;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.UserText;

/**
 * ADR 0027: the mock answers the text actions and the e-mail writer deterministically, in the requested output language
 * and in the formats the pipeline reads, so clients can develop against it.
 */
class MockAiProviderTextActionsTest {

	static final Duration T = Duration.ofSeconds(2);
	final MockAiProvider mock = new MockAiProvider("mock", Behavior.ok());

	String run(AiFeature task, String text, String language, String mode, String output, List<ContextPart> parts,
			int count, int variation) {
		return mock.llmTransform(new TransformRequest(task, UserText.of(text), language, mode, null, null, output, null,
				null, parts, count, variation), T).resultText().value();
	}

	@Test
	void summariesAndExplanationsAreLabelledInTheOutputLanguage() {
		assertThat(run(AiFeature.SUMMARIZE, "The meeting moved to Thursday.", "en", null, "am", List.of(), 0, 0))
				.isEqualTo("ማጠቃለያ፦ The meeting moved to Thursday.");
		assertThat(run(AiFeature.SUMMARIZE, "x".repeat(400), "en", null, null, List.of(), 0, 0))
				.startsWith("Summary: ").endsWith("…").hasSize("Summary: ".length() + 281);
		// no language given: the label follows the script of the text
		assertThat(run(AiFeature.EXPLAIN_TEXT, "ሰላም ነው", null, null, null, List.of(), 0, 0)).isEqualTo("ማብራሪያ፦ ሰላም ነው");
		assertThat(run(AiFeature.EXPLAIN_TEXT, "Sanyii", "om", null, "om", List.of(), 0, 0)).isEqualTo("Ibsa: Sanyii");
	}

	@Test
	void transformsIntoAnotherLanguageStartWithAWordOfThatLanguage() {
		assertThat(run(AiFeature.REWRITE, "Hello", "en", "SIMPLIFY", "am", List.of(), 0, 0)).isEqualTo("[ሙከራ] Hello");
		assertThat(run(AiFeature.TONE_ADJUST, "Hello", "en", null, "en", List.of(), 0, 0))
				.isEqualTo("[mock:tone_adjust] Hello");
		assertThat(run(AiFeature.REWRITE, "Hello", "en", "SIMPLIFY", null, List.of(), 0, 0))
				.isEqualTo("[mock:rewrite] Hello");
	}

	@Test
	void repliesComeInTheAskedNumberSeparatedByDashLinesAndRotateWithTheVariation() {
		String first = run(AiFeature.SMART_REPLY, "Can we meet?", "en", null, "am", List.of(), 3, 0);
		assertThat(OutputFormats.replies(first, 3)).containsExactly("ስለ መልእክትዎ እናመሰግናለን።", "እሺ፣ በቅርቡ እመልስልዎታለሁ።",
				"ተቀብያለሁ፣ አመሰግናለሁ።");
		String again = run(AiFeature.SMART_REPLY, "Can we meet?", "en", null, "am", List.of(), 3, 1);
		assertThat(OutputFormats.replies(again, 3)).first().isEqualTo("እሺ፣ በቅርቡ እመልስልዎታለሁ።");
		// the links and numbers of the user's notes are kept, as a real reply would
		String withNotes = run(AiFeature.SMART_REPLY, "How much?", "en", null, "en",
				List.of(ContextPart.of(Kind.USER_NOTES, "ዋጋው 2500 ብር ነው")), 2, 0);
		assertThat(OutputFormats.replies(withNotes, 2)).hasSize(2).allMatch(r -> r.endsWith(" 2500"));
	}

	@Test
	void aNewEmailHasASubjectGreetingTheDescriptionAPlaceholderAndASignature() {
		String out = run(AiFeature.DRAFT_DOCUMENT, "Ask for leave on Monday", null, "NEW", "en",
				List.of(ContextPart.of(Kind.SENDER_NAME, "Abebe Kebede")), 0, 0);
		OutputFormats.Email e = OutputFormats.email(out);
		assertThat(e.subject()).isEqualTo("Mock email subject");
		assertThat(e.body()).startsWith("Dear [recipient name],").contains("Ask for leave on Monday",
				"We can meet on [date].").endsWith("Best regards,\nAbebe Kebede");
		assertThat(OutputFormats.placeholders(e.subject(), e.body())).containsExactly("[recipient name]", "[date]");
		// regenerate: another subject
		assertThat(OutputFormats.email(run(AiFeature.DRAFT_DOCUMENT, "Ask for leave on Monday", null, "NEW", "en",
				List.of(), 0, 2)).subject()).isEqualTo("Mock email subject (2)");
	}

	@Test
	void aReplyAnswersInTheOutputLanguageWithReAndTheNamesGiven() {
		String out = run(AiFeature.DRAFT_DOCUMENT, "Dear Abebe, please confirm the order 7781. Sarah", null, "REPLY",
				"am", List.of(ContextPart.of(Kind.USER_NOTES, "confirm order 7781"),
						ContextPart.of(Kind.RECIPIENT_NAME, "Sarah")), 0, 0);
		OutputFormats.Email e = OutputFormats.email(out);
		assertThat(e.subject()).isEqualTo("Re: የሙከራ ኢሜይል ርዕስ");
		assertThat(e.body()).startsWith("ውድ Sarah,").contains("ስለ መልእክትዎ እናመሰግናለን። 7781")
				.endsWith("ከሰላምታ ጋር፣\n[ስምዎ]");
	}

	@Test
	void anImprovedDraftHasNoSubjectAndKeepsTheDraft() {
		String out = run(AiFeature.DRAFT_DOCUMENT, "hi i want leave", null, "IMPROVE", "om", List.of(), 0, 0);
		OutputFormats.Email e = OutputFormats.email(out);
		assertThat(e.subject()).isNull();
		assertThat(e.body()).startsWith("Kabajamoo [maqaa fudhataa],").contains("hi i want leave")
				.endsWith("Nagaa wajjin,\n[maqaa keessan]");
	}
}
