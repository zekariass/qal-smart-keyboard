package com.qalsmartkeyboard.platform.crypto;

/**
 * The key management service failed, or refused to unwrap a data key (for example a wrapped DEK that was
 * moved to another user's row, or wrapped under an unknown key-encryption key).
 */
public final class KeyManagementException extends UserDataCryptoException {

	public KeyManagementException(String message) {
		super(message);
	}

	public KeyManagementException(String message, Throwable cause) {
		super(message, cause);
	}
}
