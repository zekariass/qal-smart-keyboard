package com.qalsmartkeyboard.aigateway.idempotency;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

import com.qalsmartkeyboard.aigateway.AiCommand;
import com.qalsmartkeyboard.aigateway.spi.Addressee;

/**
 * The request fingerprint of B5.4 (SD 8.3 "keys are stored with a hash of the request body"): SHA-256 over a canonical,
 * versioned encoding of everything the client put in the request body, so the same {@code Idempotency-Key} with
 * another body is recognised as {@code IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>Covered: the feature (task), every option field <b>as the client sent it</b> (not the defaults resolved from
 * preferences or a destination profile, which may change between two retries of the same body), and the exact input
 * text. Translate: source and destination language, style, preserveNames, glossary, addressee formality and gender,
 * the four cultural options, destination profile. Transform: task, language, mode, tone, target length, addressee,
 * glossary. Detect is unmetered and never fingerprinted. Headers (device, surface, data policy) are not part of the
 * body and not covered.
 *
 * <p>Encoding: the tag {@code qal.ai.request-fingerprint.v1}, then each field as a 4-byte big-endian length
 * ({@code -1} for an absent value) followed by its UTF-8 bytes, in a fixed order. Lengths make it unambiguous: no
 * combination of field values can collide with another by shifting bytes between fields.
 *
 * <p><b>The digest is never stored as it is</b>: an unkeyed hash of a short text would let anyone with the table guess
 * and confirm the text. {@link EncryptedReplayCache} seals it with the user's data key ({@code UserDataEncryption}), so
 * the stored value is useless without the user's key and is crypto-shredded with it.
 */
final class RequestFingerprint {

	static final String TAG = "qal.ai.request-fingerprint.v1";

	private RequestFingerprint() {
	}

	/** The 32-byte digest of the command's request body. */
	static byte[] of(AiCommand command) {
		Encoder e = new Encoder();
		e.add(TAG);
		e.add(command.feature().name());
		switch (command) {
			case AiCommand.Translate t -> {
				e.add(t.sourceLanguage());
				e.add(t.destinationLanguage());
				e.add(t.style());
				e.add(t.preserveNames());
				e.add(t.glossaryId());
				addressee(e, t.addressee());
				e.add(t.culturalOptions().calendar());
				e.add(t.culturalOptions().timeNotation());
				e.add(t.culturalOptions().numerals());
				e.add(t.culturalOptions().adaptIdioms());
				e.add(t.destinationProfileId());
			}
			case AiCommand.Transform t -> {
				e.add(t.language());
				e.add(t.mode());
				e.add(t.tone());
				e.add(t.targetLength());
				addressee(e, t.addressee());
				e.add(t.glossaryId());
			}
			case AiCommand.Detect d -> {
				for (String candidate : d.candidates()) {
					e.add(candidate);
				}
			}
		}
		e.add(command.text().value());
		return e.digest();
	}

	/** Constant-time comparison of two digests. */
	static boolean matches(byte[] a, byte[] b) {
		return a != null && b != null && MessageDigest.isEqual(a, b);
	}

	private static void addressee(Encoder e, Addressee a) {
		e.add(a.formality() == null ? null : a.formality().name());
		e.add(a.gender() == null ? null : a.gender().name());
	}

	private static final class Encoder {

		private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

		void add(String value) {
			if (value == null) {
				length(-1);
				return;
			}
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			length(bytes.length);
			out.writeBytes(bytes);
			Arrays.fill(bytes, (byte) 0);
		}

		void add(Boolean value) {
			add(value == null ? null : value.toString());
		}

		void add(UUID value) {
			add(value == null ? null : value.toString());
		}

		private void length(int n) {
			out.writeBytes(ByteBuffer.allocate(4).putInt(n).array());
		}

		byte[] digest() {
			byte[] bytes = out.toByteArray();
			try {
				return MessageDigest.getInstance("SHA-256").digest(bytes);
			} catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException("SHA-256 unavailable", ex);
			} finally {
				Arrays.fill(bytes, (byte) 0);
				out.reset();
			}
		}
	}
}
