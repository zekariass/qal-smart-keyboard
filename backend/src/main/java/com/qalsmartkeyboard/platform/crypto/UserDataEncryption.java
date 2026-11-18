package com.qalsmartkeyboard.platform.crypto;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
	 * Moves a value to another user or row: opens it as {@code fromUserId}/{@code from} and seals the plaintext again
	 * for {@code toUserId}/{@code to} under the target's current key. Ciphertext is bound to its user and row (ADR 0005),
	 * so this is the only correct way to move it (guest merge, B2.6, ADR 0010); copying the bytes would make them
	 * unreadable. The plaintext copy is wiped before returning.
	 *
	 * @throws CryptoShreddedException     if the source key version or the target's keys were destroyed
	 * @throws CiphertextRejectedException if the source value does not open as {@code fromUserId}/{@code from}
	 * @throws KeyManagementException      if the KMS fails
	 */
	default EncryptedValue reencrypt(UUID fromUserId, EncryptionContext from, byte[] ciphertext, UUID toUserId,
			EncryptionContext to) {
		byte[] plaintext = decrypt(fromUserId, from, ciphertext);
		try {
			return encrypt(toUserId, to, plaintext);
		}
		finally {
			Arrays.fill(plaintext, (byte) 0);
		}
	}

	/**
	 * {@link #reencrypt} for every ciphertext column of one row, which keeps a single {@code key_version} column: all
	 * returned values are sealed with the same target key version (if the target's key rotates in between, the row is
	 * sealed again). Each column keeps its context; only the user changes.
	 *
	 * @param contexts    one per column, as used for the source row and the target row
	 * @param ciphertexts the source values in the same order; {@code null} entries stay {@code null}
	 * @return the target values in the same order ({@code null} where the source was {@code null})
	 */
	default List<EncryptedValue> reencryptRow(UUID fromUserId, UUID toUserId, List<EncryptionContext> contexts,
			List<byte[]> ciphertexts) {
		if (contexts.size() != ciphertexts.size()) {
			throw new IllegalArgumentException("one context per ciphertext");
		}
		for (int attempt = 0; attempt < 3; attempt++) {
			List<EncryptedValue> sealed = new ArrayList<>(ciphertexts.size());
			Set<Integer> versions = new HashSet<>();
			for (int i = 0; i < ciphertexts.size(); i++) {
				byte[] ciphertext = ciphertexts.get(i);
				EncryptedValue value = ciphertext == null ? null
						: reencrypt(fromUserId, contexts.get(i), ciphertext, toUserId, contexts.get(i));
				if (value != null) {
					versions.add(value.keyVersion());
				}
				sealed.add(value);
			}
			if (versions.size() <= 1) {
				return Collections.unmodifiableList(sealed);
			}
		}
		throw new IllegalStateException("The target's data key kept rotating during re-encryption");
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
