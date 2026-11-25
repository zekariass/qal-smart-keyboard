package com.qalsmartkeyboard.aigateway.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.aigateway.AiCommand;
import com.qalsmartkeyboard.aigateway.AiCommand.Caller;
import com.qalsmartkeyboard.aigateway.spi.UserText;

/** ADR 0048: the fingerprint of a chat request covers every turn, role, the reply language and the field text. */
class RequestFingerprintChatTest {

	static final UUID USER = UUID.randomUUID();

	static AiCommand.Chat.Turn turn(String role, String text) {
		return new AiCommand.Chat.Turn(role, UserText.of(text));
	}

	static AiCommand.Chat chat(String language, String field, AiCommand.Chat.Turn... turns) {
		return new AiCommand.Chat(Caller.of(USER, "k"), List.of(turns), language, field == null ? null : UserText.of(field),
				false);
	}

	static AiCommand.Chat base() {
		return chat("am", "field", turn("USER", "Hello"), turn("ASSISTANT", "Hi"), turn("USER", "Say it"));
	}

	static String hex(AiCommand c) {
		return HexFormat.of().formatHex(RequestFingerprint.of(c));
	}

	@Test
	void theSameBodyGivesTheSameDigestWhateverTheHeaders() {
		AiCommand.Chat other = new AiCommand.Chat(
				new Caller(USER, UUID.randomUUID(), AiCommand.Surface.COMPANION, "another-key", null),
				base().messages(), "am", UserText.of("field"), false);
		assertThat(hex(base())).isEqualTo(hex(base())).isEqualTo(hex(other));
	}

	@Test
	void everyFieldOfTheBodyChangesTheDigest() {
		List<AiCommand> variants = List.of(base(),
				chat("am", "field", turn("USER", "Hello"), turn("ASSISTANT", "Hi"), turn("USER", "Say it!")), // last text
				chat("am", "field", turn("USER", "Hello!"), turn("ASSISTANT", "Hi"), turn("USER", "Say it")), // earlier text
				chat("am", "field", turn("USER", "Hello"), turn("USER", "Hi"), turn("USER", "Say it")), // a role
				chat("am", "field", turn("ASSISTANT", "Hi"), turn("USER", "Say it")), // one turn fewer
				chat("am", "field", turn("USER", "Hello"), turn("ASSISTANT", "Hi"), turn("USER", "Say it"),
						turn("USER", "Say it")), // one more
				chat("ti", "field", turn("USER", "Hello"), turn("ASSISTANT", "Hi"), turn("USER", "Say it")), // language
				chat(null, "field", turn("USER", "Hello"), turn("ASSISTANT", "Hi"), turn("USER", "Say it")),
				chat("am", "fielD", turn("USER", "Hello"), turn("ASSISTANT", "Hi"), turn("USER", "Say it")), // field
				chat("am", null, turn("USER", "Hello"), turn("ASSISTANT", "Hi"), turn("USER", "Say it")),
				chat("am", "", turn("USER", "Hello"), turn("ASSISTANT", "Hi"), turn("USER", "Say it")));
		Set<String> digests = new HashSet<>();
		variants.forEach(v -> digests.add(hex(v)));
		assertThat(digests).hasSize(variants.size());
	}

	@Test
	void textCannotShiftBetweenTurnsOrIntoTheFieldWithoutChangingTheDigest() {
		assertThat(hex(chat(null, null, turn("USER", "ab"), turn("USER", "c"))))
				.isNotEqualTo(hex(chat(null, null, turn("USER", "a"), turn("USER", "bc"))));
		assertThat(hex(chat(null, "b", turn("USER", "a")))).isNotEqualTo(hex(chat(null, null, turn("USER", "ab"))));
		assertThat(hex(chat("am", null, turn("USER", "a")))).isNotEqualTo(hex(chat(null, "am", turn("USER", "a"))));
	}

	@Test
	void aChatDoesNotCollideWithTheOtherFeaturesAndItsToStringHoldsNoText() {
		AiCommand.Transform rewrite = AiCommand.Transform.of(Caller.of(USER, "k"),
				com.qalsmartkeyboard.aigateway.spi.AiFeature.REWRITE, "Hello", null);
		assertThat(hex(chat(null, null, turn("USER", "Hello")))).isNotEqualTo(hex(rewrite));
		assertThat(base().toString()).doesNotContain("Hello", "Say it", "chars]x");
		assertThat(base().text().value()).isEqualTo("Say it");
		assertThat(chat(null, null).text().value()).isEmpty();
	}
}
