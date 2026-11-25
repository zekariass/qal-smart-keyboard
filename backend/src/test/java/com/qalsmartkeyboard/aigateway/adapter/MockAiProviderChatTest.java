package com.qalsmartkeyboard.aigateway.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.Behavior;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.ChatTurn;
import com.qalsmartkeyboard.aigateway.spi.ContextPart;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.TransformResult;
import com.qalsmartkeyboard.aigateway.spi.UserText;

/** ADR 0048: the mock answers AI_CHAT deterministically, in the output language, so clients can develop against it. */
class MockAiProviderChatTest {

	static final Duration T = Duration.ofSeconds(2);
	final MockAiProvider mock = new MockAiProvider("mock", Behavior.ok());

	TransformResult chat(List<ChatTurn> turns, String output, List<ContextPart> parts) {
		ChatTurn last = turns.getLast();
		return mock.llmTransform(new TransformRequest(AiFeature.AI_CHAT, last.text(), null, null, null, null, output,
				null, null, parts, 0, 0, turns), T);
	}

	@Test
	void theReplyNamesTheTurnCountEchoesTheLastTurnAndIsDeterministic() {
		List<ChatTurn> turns = List.of(ChatTurn.user("Hello there"), ChatTurn.assistant("Hi"),
				ChatTurn.user("Write a greeting"));
		TransformResult r = chat(turns, null, List.of());
		assertThat(r.resultText().value()).isEqualTo("Chat reply 3: Write a greeting");
		assertThat(r.truncated()).isFalse();
		assertThat(chat(turns, null, List.of()).resultText()).isEqualTo(r.resultText());
		assertThat(r.usage().inputCharacters()).isEqualTo("Hello there".length() + "Hi".length()
				+ "Write a greeting".length()); // every turn counts, once
		assertThat(r.call().modelCode()).isEqualTo("mock-llm-1");
	}

	@Test
	void theLanguageFollowsTheOutputLanguageOrTheScriptOfTheLastTurn() {
		assertThat(chat(List.of(ChatTurn.user("Hello")), "am", List.of()).resultText().value())
				.isEqualTo("የውይይት ምላሽ 1: Hello");
		assertThat(chat(List.of(ChatTurn.user("Hello")), "om", List.of()).resultText().value())
				.isEqualTo("Deebii marii 1: Hello");
		assertThat(chat(List.of(ChatTurn.user("ሰላም")), null, List.of()).resultText().value())
				.isEqualTo("የውይይት ምላሽ 1: ሰላም");
		assertThat(chat(List.of(ChatTurn.user("hello")), "ar", List.of()).resultText().value())
				.startsWith("رد المحادثة 1:");
	}

	@Test
	void theFieldTextIsAContextPartAndShowsInASecondLine() {
		TransformResult r = chat(List.of(ChatTurn.user("Fix this")), null,
				List.of(ContextPart.of(ContextPart.Kind.FIELD_TEXT, "my draft message " + "x".repeat(100))));
		assertThat(r.resultText().value()).startsWith("Chat reply 1: Fix this\n> my draft message ")
				.endsWith("…");
		assertThat(r.usage().inputCharacters()).isEqualTo("Fix this".length() + 17 + 100);
	}

	@Test
	void theMarkersShapeTheLengthAndTheFinishReason() {
		TransformResult cut = chat(List.of(ChatTurn.user("story [cut]")), null, List.of());
		assertThat(cut.truncated()).isTrue();
		TransformResult long_ = chat(List.of(ChatTurn.user("story [long]")), null, List.of());
		assertThat(long_.resultText().characters()).isBetween(3000, 4000);
		assertThat(long_.truncated()).isFalse();
		assertThat(chat(List.of(ChatTurn.user("story [huge]")), null, List.of()).resultText().characters())
				.isGreaterThan(4000);
		// the other tasks are never reported as truncated
		assertThat(mock.llmTransform(new TransformRequest(AiFeature.REWRITE, UserText.of("a [cut]"), "en", null, null,
				null, null, null, null), T).truncated()).isFalse();
	}

	@Test
	void aChatRequestCarriesNoTurnsByDefaultAndCopiesTheOnesItGets() {
		TransformRequest plain = TransformRequest.of(AiFeature.REWRITE, "x", "en");
		assertThat(plain.turns()).isEmpty();
		assertThat(plain.inputCharacters()).isEqualTo(1);
		List<ChatTurn> turns = new java.util.ArrayList<>(List.of(ChatTurn.user("abc"), ChatTurn.user("de")));
		TransformRequest chat = new TransformRequest(AiFeature.AI_CHAT, UserText.of("de"), null, null, null, null, null,
				null, null, List.of(), 0, 0, turns);
		turns.clear();
		assertThat(chat.turns()).hasSize(2);
		assertThat(chat.inputCharacters()).isEqualTo(5); // both turns, the last one once
		assertThat(chat.withInstructions("x").turns()).isEqualTo(chat.turns());
		assertThat(chat.turns().get(0).toString()).doesNotContain("abc");
	}
}
