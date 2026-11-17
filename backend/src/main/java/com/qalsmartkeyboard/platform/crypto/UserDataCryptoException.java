package com.qalsmartkeyboard.platform.crypto;

/**
 * Base class of user-data encryption failures. Messages never contain keys, plaintext or ciphertext.
 * These are server-side conditions: callers translate them to a Problem (usually 500, or 410/404 for a
 * shredded user where the API defines one) and must not echo the message to clients.
 */
public abstract sealed class UserDataCryptoException extends RuntimeException
		permits CryptoShreddedException, CiphertextRejectedException, KeyManagementException {

	protected UserDataCryptoException(String message) {
		super(message);
	}

	protected UserDataCryptoException(String message, Throwable cause) {
		super(message, cause);
	}
}
