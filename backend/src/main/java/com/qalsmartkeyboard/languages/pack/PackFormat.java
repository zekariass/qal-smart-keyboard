package com.qalsmartkeyboard.languages.pack;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Constants of the container format v1 ({@code docs/specs/pack-format-v1.md}). */
public final class PackFormat {

	/** {@code QALPACK\0}. */
	public static final byte[] MAGIC = { 'Q', 'A', 'L', 'P', 'A', 'C', 'K', 0 };
	public static final int FORMAT_VERSION = 1;
	/** Domain-separation prefix: the signed message is {@code SIGNATURE_CONTEXT || manifest bytes}. */
	public static final byte[] SIGNATURE_CONTEXT = "QAL-PACK-MANIFEST-v1\0".getBytes(StandardCharsets.US_ASCII);
	public static final int MAX_KEY_ID_LENGTH = 120;
	/** Magic, version, 1-byte key id, signature, 1-byte manifest, artifact length. */
	public static final int MIN_CONTAINER_LENGTH = 8 + 2 + 1 + 1 + Ed25519.SIGNATURE_BYTES + 4 + 1 + 8;

	/** The {@code language_resources.resource_type} CHECK list, which the manifest's {@code resourceType} must be in. */
	public static final List<String> RESOURCE_TYPES = List.of("KEYBOARD_LAYOUT", "TRANSLITERATION_RULES", "DICTIONARY",
			"FREQUENCY_LIST", "PREDICTION_MODEL", "AUTOCORRECTION_MODEL", "PROTECTED_TERMS", "PRONUNCIATION",
			"EMOJI_KEYWORDS", "GLOSSARY", "SPELL_MODEL", "LANGUAGE_ID_MODEL", "TRANSLATION_MODEL", "ASR_MODEL", "TTS_VOICE",
			"ON_DEVICE_LLM", "OTHER");
	public static final List<String> PLATFORMS = List.of("SHARED", "ANDROID", "IOS");
	public static final List<String> RELEASE_CHANNELS = List.of("INTERNAL", "BETA", "STABLE");

	private PackFormat() {
	}

	/** 1-120 bytes of {@code [A-Za-z0-9._-]}. */
	public static boolean isValidKeyId(byte[] id) {
		if (id == null || id.length == 0 || id.length > MAX_KEY_ID_LENGTH) {
			return false;
		}
		for (byte b : id) {
			boolean ok = (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '.' || b == '_'
					|| b == '-';
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	public static boolean isValidKeyId(String id) {
		return id != null && isValidKeyId(id.getBytes(StandardCharsets.UTF_8));
	}

	/** {@code SIGNATURE_CONTEXT || manifest}: the exact message a pack signature covers. */
	public static byte[] signingInput(byte[] manifest) {
		byte[] out = new byte[SIGNATURE_CONTEXT.length + manifest.length];
		System.arraycopy(SIGNATURE_CONTEXT, 0, out, 0, SIGNATURE_CONTEXT.length);
		System.arraycopy(manifest, 0, out, SIGNATURE_CONTEXT.length, manifest.length);
		return out;
	}
}
