package com.qalsmartkeyboard.platform.crypto.kms;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical, unambiguous byte encoding of a KMS context map, for KMSs that take associated data as bytes
 * (local dev KMS, GCP Cloud KMS). Entries are sorted by key; every string is written as a 2-byte big-endian
 * length followed by its UTF-8 bytes, preceded by the entry count.
 */
public final class KmsContext {

	private KmsContext() {
	}

	public static byte[] canonicalBytes(Map<String, String> context) {
		TreeMap<String, String> sorted = new TreeMap<>(context);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeShort(out, sorted.size());
		sorted.forEach((key, value) -> {
			writeString(out, key);
			writeString(out, value);
		});
		return out.toByteArray();
	}

	/** Writes a 2-byte big-endian length and the UTF-8 bytes of {@code value}. */
	public static void writeString(ByteArrayOutputStream out, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		writeShort(out, bytes.length);
		out.writeBytes(bytes);
	}

	private static void writeShort(ByteArrayOutputStream out, int value) {
		if (value < 0 || value > 0xFFFF) {
			throw new IllegalArgumentException("context field too long");
		}
		out.write(value >>> 8);
		out.write(value);
	}
}
