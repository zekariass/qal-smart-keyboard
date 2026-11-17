package com.qalsmartkeyboard.platform.crypto;

/**
 * The user's data key was destroyed by {@link UserDataEncryption#cryptoShred}: existing ciphertext can no
 * longer be decrypted and no new data can be encrypted for the user.
 */
public final class CryptoShreddedException extends UserDataCryptoException {

	public CryptoShreddedException() {
		super("The user's data keys have been destroyed (crypto-shredded)");
	}
}
