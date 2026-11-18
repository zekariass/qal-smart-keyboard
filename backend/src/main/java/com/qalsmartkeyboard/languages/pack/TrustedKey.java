package com.qalsmartkeyboard.languages.pack;

import java.util.Arrays;
import java.util.Objects;

/**
 * A pinned Ed25519 public key: key id as written in the container header, the raw 32-byte public key and its status.
 */
public record TrustedKey(String keyId, byte[] publicKey, KeyStatus status) {

	public TrustedKey {
		Objects.requireNonNull(keyId, "keyId");
		Objects.requireNonNull(status, "status");
		publicKey = publicKey == null ? null : publicKey.clone();
	}

	@Override
	public byte[] publicKey() {
		return publicKey == null ? null : publicKey.clone();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof TrustedKey k && keyId.equals(k.keyId) && Arrays.equals(publicKey, k.publicKey)
				&& status == k.status;
	}

	@Override
	public int hashCode() {
		return Objects.hash(keyId, Arrays.hashCode(publicKey), status);
	}

	@Override
	public String toString() {
		return "TrustedKey[" + keyId + ", " + status + "]";
	}
}
