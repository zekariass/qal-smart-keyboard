package com.qalsmartkeyboard.platform.crypto;

import java.util.Objects;

/** A ciphertext could not be opened. The {@link Reason} says why; the bytes are never included. */
public final class CiphertextRejectedException extends UserDataCryptoException {

	public enum Reason {
		/** Too short or a header field out of range. */
		MALFORMED,
		/** The format byte is not one this build understands. */
		UNSUPPORTED_FORMAT,
		/** The key version in the header has no {@code user_data_keys} row for this user. */
		UNKNOWN_KEY_VERSION,
		/**
		 * AES-GCM authentication failed: the bytes were altered, or the user, context or key version differs
		 * from the ones used to seal the value.
		 */
		AUTHENTICATION_FAILED
	}

	private final Reason reason;

	public CiphertextRejectedException(Reason reason) {
		super("Ciphertext rejected: " + Objects.requireNonNull(reason, "reason"));
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
