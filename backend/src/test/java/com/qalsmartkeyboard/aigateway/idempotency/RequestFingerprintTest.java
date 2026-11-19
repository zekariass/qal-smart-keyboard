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
import com.qalsmartkeyboard.aigateway.AiCommand.CulturalOptions;
import com.qalsmartkeyboard.aigateway.spi.Addressee;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.UserText;

/** B5.4: the fingerprint covers the whole request body and nothing else. */
class RequestFingerprintTest {

	static final UUID USER = UUID.randomUUID();
	static final UUID PROFILE = UUID.randomUUID();

	static AiCommand.Translate translate(String text, String src, String dst, String style, Boolean preserve,
			Addressee addressee, CulturalOptions cultural, UUID profile) {
		return new AiCommand.Translate(Caller.of(USER, "k"), UserText.of(text), src, dst, style, preserve, null,
				addressee, cultural, profile);
	}

	static AiCommand.Translate base() {
		return translate("Selam", "en", "am", null, null, null, null, null);
	}

	static String hex(AiCommand c) {
		byte[] d = RequestFingerprint.of(c);
		assertThat(d).hasSize(32);
		return HexFormat.of().formatHex(d);
	}

	@Test
	void theSameBodyGivesTheSameDigestWhateverTheHeaders() {
		AiCommand.Translate other = new AiCommand.Translate(
				new Caller(USER, UUID.randomUUID(), AiCommand.Surface.COMPANION, "another-key", null),
				UserText.of("Selam"), "en", "am", null, null, null, null, null, null);
		assertThat(hex(base())).isEqualTo(hex(base())).isEqualTo(hex(other));
		assertThat(RequestFingerprint.matches(RequestFingerprint.of(base()), RequestFingerprint.of(other))).isTrue();
	}

	@Test
	void everyBodyFieldChangesTheDigest() {
		List<AiCommand> variants = List.of(base(),
				translate("Selam!", "en", "am", null, null, null, null, null), // text
				translate("selam", "en", "am", null, null, null, null, null), // case is not folded
				translate("Selam", null, "am", null, null, null, null, null), // source (detect)
				translate("Selam", "en", "ti", null, null, null, null, null), // destination
				translate("Selam", "en", "am", "POLITE", null, null, null, null), // style
				translate("Selam", "en", "am", null, false, null, null, null), // preserveNames
				translate("Selam", "en", "am", null, true, null, null, null),
				translate("Selam", "en", "am", null, null, new Addressee(Addressee.Formality.FORMAL, null), null, null),
				translate("Selam", "en", "am", null, null, new Addressee(null, Addressee.Gender.FEMININE), null, null),
				translate("Selam", "en", "am", null, null, null, new CulturalOptions("ETHIOPIAN", null, null, null),
						null),
				translate("Selam", "en", "am", null, null, null, new CulturalOptions(null, "ETHIOPIAN", null, null),
						null),
				translate("Selam", "en", "am", null, null, null, new CulturalOptions(null, null, "ETHIOPIC", null),
						null),
				translate("Selam", "en", "am", null, null, null, new CulturalOptions(null, null, null, false), null),
				translate("Selam", "en", "am", null, null, null, null, PROFILE), // destination profile
				AiCommand.Transform.of(Caller.of(USER, "k"), AiFeature.GRAMMAR_CORRECTION, "Selam", "am"), // feature
				AiCommand.Transform.of(Caller.of(USER, "k"), AiFeature.REWRITE, "Selam", "am"),
				new AiCommand.Transform(Caller.of(USER, "k"), AiFeature.REWRITE, UserText.of("Selam"), "am", "SHORTEN",
						null, null, null, null), // mode
				new AiCommand.Transform(Caller.of(USER, "k"), AiFeature.TONE_ADJUST, UserText.of("Selam"), "am", null,
						"POLITE", null, null, null), // tone
				new AiCommand.Transform(Caller.of(USER, "k"), AiFeature.TONE_ADJUST, UserText.of("Selam"), "am", null,
						"FRIENDLY", null, null, null),
				new AiCommand.Transform(Caller.of(USER, "k"), AiFeature.REWRITE, UserText.of("Selam"), "am", null, null,
						"SHORT", null, null), // target length
				new AiCommand.Transform(Caller.of(USER, "k"), AiFeature.REWRITE, UserText.of("Selam"), null, null, null,
						null, null, null)); // language (detect)
		Set<String> digests = new HashSet<>();
		for (AiCommand c : variants) {
			digests.add(hex(c));
		}
		assertThat(digests).hasSize(variants.size());
	}

	/** ADR 0027: a transform without an output language keeps its B5.4 digest (keys in flight stay valid). */
	@Test
	void aTransformWithoutOutputLanguageKeepsTheB54Encoding() throws Exception {
		java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
		for (String field : java.util.Arrays.asList(RequestFingerprint.TAG, "REWRITE", "am", "SHORTEN", null, null,
				null, null, null, "Selam")) {
			byte[] bytes = field == null ? new byte[0] : field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			b.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(field == null ? -1 : bytes.length).array());
			b.writeBytes(bytes);
		}
		byte[] expected = java.security.MessageDigest.getInstance("SHA-256").digest(b.toByteArray());
		AiCommand.Transform t = new AiCommand.Transform(Caller.of(USER, "k"), AiFeature.REWRITE, UserText.of("Selam"),
				"am", "SHORTEN", null, null, null, null);
		assertThat(RequestFingerprint.of(t)).isEqualTo(expected);
		AiCommand.Transform withOutput = new AiCommand.Transform(Caller.of(USER, "k"), AiFeature.REWRITE,
				UserText.of("Selam"), "am", "SHORTEN", null, null, null, null, false, "en");
		assertThat(RequestFingerprint.of(withOutput)).isNotEqualTo(expected);
	}

	@Test
	void everyReplyAndDraftFieldChangesTheDigest() {
		Caller c = Caller.of(USER, "k");
		UserText msg = UserText.of("Can we meet?");
		UserText notes = UserText.of("yes, Thursday");
		Addressee formal = new Addressee(Addressee.Formality.FORMAL, null);
		List<AiCommand> variants = List.of(
				new AiCommand.Reply(c, msg, null, "am", null, null, null, null, null, false),
				new AiCommand.Reply(c, UserText.of("Can we meet!"), null, "am", null, null, null, null, null, false),
				new AiCommand.Reply(c, msg, "en", "am", null, null, null, null, null, false),
				new AiCommand.Reply(c, msg, null, "en", null, null, null, null, null, false),
				new AiCommand.Reply(c, msg, null, "am", 2, null, null, null, null, false),
				new AiCommand.Reply(c, msg, null, "am", null, "POLITE", null, null, null, false),
				new AiCommand.Reply(c, msg, null, "am", null, null, formal, null, null, false),
				new AiCommand.Reply(c, msg, null, "am", null, null, null, notes, null, false),
				new AiCommand.Reply(c, msg, null, "am", null, null, null, null, 1, false),
				draft(null, null, null, null, null, null, null),
				draft("REPLY", null, null, null, null, null, null),
				draft(null, "am", null, null, null, null, null),
				draft(null, null, "FORMAL", null, null, null, null),
				draft(null, null, null, "SHORT", null, null, null),
				draft(null, null, null, null, false, null, null),
				draft(null, null, null, null, null, 1, null),
				draft(null, null, null, null, null, null, UserText.of("Sarah")),
				new AiCommand.Draft(c, "EMAIL", "IMPROVE", "en", null, null, UserText.of("draft"), null, null, null,
						null, null, null, null, null, null, false),
				new AiCommand.Draft(c, "EMAIL", "REPLY", "en", notes, UserText.of("original"), null, null, null, null,
						null, null, null, null, null, null, false),
				new AiCommand.Draft(c, "EMAIL", "REPLY", "en", notes, UserText.of("original"), null, null, null, null,
						null, null, null, UserText.of("Abebe"), null, null, false),
				new AiCommand.Draft(c, "EMAIL", "REPLY", "en", notes, UserText.of("original"), null, null, null, null,
						null, UserText.of("HR"), null, null, null, null, false),
				new AiCommand.Draft(c, "EMAIL", "NEW", "en", notes, null, null, null, null, null, null, null, formal,
						null, new CulturalOptions("ETHIOPIAN", null, null, null), null, false));
		Set<String> digests = new HashSet<>();
		for (AiCommand v : variants) {
			digests.add(hex(v));
		}
		assertThat(digests).hasSize(variants.size());
		assertThat(variants.get(7).toString()).doesNotContain("Thursday");
	}

	static AiCommand.Draft draft(String mode, String language, String tone, String length, Boolean subject,
			Integer variation, UserText recipient) {
		return new AiCommand.Draft(Caller.of(USER, "k"), "EMAIL", mode, language == null ? "en" : language,
				UserText.of("ask for leave"), null, null, tone, length, subject, recipient, null, null, null, null,
				variation, false);
	}

	@Test
	void fieldBoundariesCannotBeShifted() {
		// "en"+"am" vs "ena"+"m" and an absent value vs an empty one must not collide
		AiCommand a = translate("x", "en", "am", null, null, null, null, null);
		AiCommand b = translate("x", "ena", "m", null, null, null, null, null);
		AiCommand c = translate("x", "en", "am", "", null, null, null, null);
		assertThat(Set.of(hex(a), hex(b), hex(c))).hasSize(3);
	}

	@Test
	void theDigestIsNotAPlainHashOfTheText() throws Exception {
		byte[] plain = java.security.MessageDigest.getInstance("SHA-256")
				.digest("Selam".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertThat(RequestFingerprint.of(base())).isNotEqualTo(plain);
		assertThat(RequestFingerprint.matches(null, plain)).isFalse();
	}
}
