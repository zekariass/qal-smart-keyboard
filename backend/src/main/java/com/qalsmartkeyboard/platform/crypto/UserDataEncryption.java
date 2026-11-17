package com.qalsmartkeyboard.platform.crypto;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Envelope encryption of user content under a per-user data key (DEK), see ADR 0005.
 *
 * <p>Every value is AES-256-GCM sealed with the user's current DEK and bound (as associated data) to the
 * user id, the key version and an {@link EncryptionContext} naming where the value is stored. Opening it
 * with any other user, context or version fails. The DEK is created on first use and is stored only
 * KMS-wrapped in {@code user_data_keys}.
 *
 * <p>Implementations never log keys, plaintext or ciphertext, and exception messages never contain them.
 */
public interface UserDataEncryption {

	/**
	 * Seals {@code plaintext} for {@code userId}, creating the user's first data key if needed.
	 *
	 * @return the self-describing ciphertext (store it in the {@code *_ciphertext} column) and the key
	 *         version it was sealed with (store it in the row's {@code key_version} column)
	 * @throws CryptoShreddedException if the user's keys were destroyed
	 * @throws IllegalArgumentException if no {@code users} row exists for {@code userId}
	 * @throws KeyManagementException if the KMS fails or refuses to unwrap the data key
	 */
	EncryptedValue encrypt(UUID userId, EncryptionContext context, byte[] plaintext);

	/** UTF-8 convenience for {@link #encrypt(UUID, EncryptionContext, byte[])}. */
	default EncryptedValue encryptString(UUID userId, EncryptionContext context, String plaintext) {
		return encrypt(userId, context, plaintext.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Opens a value sealed by {@link #encrypt}; {@code userId} and {@code context} must be the ones used to
	 * seal it.
	 *
	 * @throws CryptoShreddedException if the key version that sealed the value was destroyed
	 * @throws CiphertextRejectedException if the value is malformed, names an unknown key version, or fails
	 *                                     authentication (tampered, or a different user/context/row)
	 * @throws KeyManagementException if the KMS fails or refuses to unwrap the data key
	 */
	byte[] decrypt(UUID userId, EncryptionContext context, byte[] ciphertext);

	/** UTF-8 convenience for {@link #decrypt(UUID, EncryptionContext, byte[])}. */
	default String decryptString(UUID userId, EncryptionContext context, byte[] ciphertext) {
		return new String(decrypt(userId, context, ciphertext), StandardCharsets.UTF_8);
	}

	/**
	 * Starts a new data key version for the user. The previous version becomes {@code ROTATED} and stays
	 * usable for decryption; new values are sealed with the new version. Existing rows are not re-encrypted
	 * (out of scope for B1.5). Creates version 1 if the user has no key yet. If another rotation completes
	 * concurrently, that rotation's key counts and its version is returned (no second version is created).
	 *
	 * @return the new active key version
	 * @throws CryptoShreddedException if the user's keys were destroyed
	 */
	int rotateKey(UUID userId);

	/**
	 * Crypto-shreds the user: every data key becomes {@code DESTROYED} with its wrapped DEK erased, so all
	 * ciphertext sealed for the user (including copies in backups) is unreadable. Idempotent. A shredded user
	 * can never get a new data key: encrypt and rotate fail with {@link CryptoShreddedException} afterwards.
	 * Joins the caller's transaction if there is one (so it commits with the rest of the account deletion).
	 *
	 * @return number of keys destroyed by this call (0 if already shredded or the user had no key)
	 */
	int cryptoShred(UUID userId);

	/** Whether {@link #cryptoShred} has run for the user. */
	boolean isShredded(UUID userId);
}
