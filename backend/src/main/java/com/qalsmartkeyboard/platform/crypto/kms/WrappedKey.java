package com.qalsmartkeyboard.platform.crypto.kms;

import java.util.Arrays;
import java.util.Objects;

/**
 * A data key wrapped by a KMS: the KEK identifier ({@code user_data_keys.kms_key_id}) and the opaque wrapped
 * bytes ({@code user_data_keys.wrapped_dek}).
 */
public record WrappedKey(String kmsKeyId, byte[] ciphertext) {

	public WrappedKey {
		Objects.requireNonNull(kmsKeyId, "kmsKeyId");
		Objects.requireNonNull(ciphertext, "ciphertext");
		if (kmsKeyId.isBlank() || kmsKeyId.length() > 255) {
			throw new IllegalArgumentException("kmsKeyId must be 1-255 characters");
		}
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof WrappedKey that && kmsKeyId.equals(that.kmsKeyId)
				&& Arrays.equals(ciphertext, that.ciphertext);
	}

	@Override
	public int hashCode() {
		return 31 * kmsKeyId.hashCode() + Arrays.hashCode(ciphertext);
	}

	@Override
	public String toString() {
		return "WrappedKey[kmsKeyId=" + kmsKeyId + ", length=" + ciphertext.length + "]";
	}
}
