package com.qalsmartkeyboard.platform.crypto.kms;

import java.security.GeneralSecurityException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a 96-bit nonce and 128-bit tag, using the JDK provider (no extra crypto library).
 * Output of {@link #seal} is {@code ciphertext || tag}.
 *
 * <p>{@link SecretKeySpec} copies the key; that copy cannot be wiped through JCA and lives until garbage
 * collection. Callers wipe their own key arrays.
 */
public final class Aes256Gcm {

	public static final int KEY_BYTES = 32;
	public static final int NONCE_BYTES = 12;
	public static final int TAG_BYTES = 16;

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private Aes256Gcm() {
	}

	public static byte[] seal(byte[] key, byte[] nonce, byte[] associatedData, byte[] plaintext) {
		try {
			return cipher(Cipher.ENCRYPT_MODE, key, nonce, associatedData).doFinal(plaintext);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("AES-256-GCM encryption failed", e);
		}
	}

	/**
	 * @throws AEADBadTagException if authentication fails (wrong key, nonce, associated data or altered bytes)
	 */
	public static byte[] open(byte[] key, byte[] nonce, byte[] associatedData, byte[] input, int offset, int length)
			throws AEADBadTagException {
		if (length < TAG_BYTES) {
			throw new AEADBadTagException("input shorter than the tag");
		}
		try {
			return cipher(Cipher.DECRYPT_MODE, key, nonce, associatedData).doFinal(input, offset, length);
		} catch (AEADBadTagException e) {
			throw e;
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("AES-256-GCM decryption failed", e);
		}
	}

	private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] associatedData)
			throws GeneralSecurityException {
		if (key == null || key.length != KEY_BYTES) {
			throw new IllegalArgumentException("AES-256 key must be 32 bytes");
		}
		if (nonce == null || nonce.length != NONCE_BYTES) {
			throw new IllegalArgumentException("GCM nonce must be 12 bytes");
		}
		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
		cipher.updateAAD(associatedData);
		return cipher;
	}
}
