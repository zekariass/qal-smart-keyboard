package com.qalsmartkeyboard.platform.crypto;

import java.util.Arrays;
import java.util.Objects;

/**
 * Result of {@link UserDataEncryption#encrypt}: the self-describing ciphertext (ADR 0005 format) and the key
 * version that sealed it. Store both: {@code ciphertext} in the {@code *_ciphertext} column, {@code keyVersion}
 * in the row's {@code key_version} column. The array is not copied; do not modify it.
 */
public record EncryptedValue(int keyVersion, byte[] ciphertext) {

	public EncryptedValue {
		if (keyVersion < 1) {
			throw new IllegalArgumentException("keyVersion must be positive");
		}
		Objects.requireNonNull(ciphertext, "ciphertext");
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof EncryptedValue that && keyVersion == that.keyVersion
				&& Arrays.equals(ciphertext, that.ciphertext);
	}

	@Override
	public int hashCode() {
		return 31 * keyVersion + Arrays.hashCode(ciphertext);
	}

	/** Never prints the ciphertext bytes. */
	@Override
	public String toString() {
		return "EncryptedValue[keyVersion=" + keyVersion + ", length=" + ciphertext.length + "]";
	}
}
