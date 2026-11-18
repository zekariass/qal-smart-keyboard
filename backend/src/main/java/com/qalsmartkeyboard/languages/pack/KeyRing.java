package com.qalsmartkeyboard.languages.pack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A validated set of trusted signing keys (pack spec section 7.1). Construction fails if any key has an invalid or
 * duplicate id, is not a valid large-order Ed25519 point, or repeats a public key listed under another id (so a
 * revocation cannot be bypassed through an alias). Revoked keys are kept so they are reported as revoked.
 */
public final class KeyRing {

	private final List<TrustedKey> keys;

	public KeyRing(Collection<TrustedKey> keys) {
		List<TrustedKey> out = new ArrayList<>(keys.size());
		for (TrustedKey k : keys) {
			if (!PackFormat.isValidKeyId(k.keyId().getBytes(StandardCharsets.UTF_8))) {
				throw new IllegalArgumentException("trusted key id must be 1-120 chars of [A-Za-z0-9._-]");
			}
			byte[] raw = k.publicKey();
			if (raw == null || raw.length != Ed25519.PUBLIC_KEY_BYTES) {
				throw new IllegalArgumentException("trusted public key must be exactly 32 bytes");
			}
			if (!Ed25519.isValidPublicKey(raw)) {
				throw new IllegalArgumentException("trusted public key is not a valid Ed25519 point of large order");
			}
			for (TrustedKey e : out) {
				if (e.keyId().equals(k.keyId())) {
					throw new IllegalArgumentException("duplicate trusted key id");
				}
				if (Arrays.equals(e.publicKey(), raw)) {
					throw new IllegalArgumentException("the same public key is listed twice");
				}
			}
			out.add(k);
		}
		this.keys = List.copyOf(out);
	}

	public static KeyRing of(TrustedKey... keys) {
		return new KeyRing(List.of(keys));
	}

	/** The key whose id equals the header bytes {@code keyId}, if trusted. */
	public Optional<TrustedKey> lookup(byte[] keyId) {
		String id = new String(keyId, StandardCharsets.US_ASCII);
		return keys.stream().filter(k -> k.keyId().equals(id)).findFirst();
	}

	public List<TrustedKey> keys() {
		return keys;
	}
}
