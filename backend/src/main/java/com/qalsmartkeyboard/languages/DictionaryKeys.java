package com.qalsmartkeyboard.languages;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Types;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;

/**
 * The per-user dictionary HMAC key and sync counter (ADR 0018, ADR 0007 D2/OI-P7), one row in
 * {@code user_dictionary_sync_state}.
 *
 * <p>The key is 256 random bits, stored only as ciphertext under the user's data key (context: that table,
 * {@code key_ciphertext}, row id = user id). It is <b>not derived from the data key</b>: rotating the data key never
 * changes a hash, and destroying the keys (crypto-shred) makes it unreadable. It is created on first use and never
 * regenerated.
 *
 * <p>{@code word_hmac = lower-case hex of HMAC-SHA256(key, "qal.dictionary.word.v1" 0x00 languageCode 0x00 NFC(word))},
 * all text as UTF-8. Case is not folded (the dictionary is exact; case policy belongs to the client).
 *
 * <p>{@link #lock} also serialises everything that writes a user's dictionary (pushes and guest merges).
 */
@Component
class DictionaryKeys {

	static final String TABLE = "user_dictionary_sync_state";
	private static final String COLUMN = "key_ciphertext";
	private static final byte[] DOMAIN = "qal.dictionary.word.v1".getBytes(StandardCharsets.UTF_8);
	private static final SecureRandom RANDOM = new SecureRandom();

	/** The unsealed key and the last sync version handed out. Hold it only for the duration of one operation. */
	record State(byte[] key, long counter) {
	}

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;

	DictionaryKeys(JdbcClient jdbc, UserDataEncryption encryption) {
		this.jdbc = jdbc;
		this.encryption = encryption;
	}

	static EncryptionContext context(UUID userId) {
		return EncryptionContext.of(TABLE, COLUMN, userId);
	}

	/**
	 * Creates the user's state row (key and counter 0) on first use, locks it {@code FOR UPDATE} until the caller's
	 * transaction ends and returns it. Must run inside a transaction.
	 */
	State lock(UUID userId) {
		boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_dictionary_sync_state WHERE user_id = :user)")
				.param("user", userId).query(Boolean.class).single();
		if (!exists) {
			byte[] fresh = new byte[32];
			RANDOM.nextBytes(fresh);
			EncryptedValue sealed = encryption.encrypt(userId, context(userId), fresh);
			Arrays.fill(fresh, (byte) 0);
			jdbc.sql("""
					INSERT INTO user_dictionary_sync_state (user_id, key_ciphertext, key_version) VALUES (:user, :ct, :version)
					ON CONFLICT (user_id) DO NOTHING
					""").param("user", userId).param("ct", sealed.ciphertext(), Types.BINARY)
					.param("version", sealed.keyVersion()).update();
		}
		return jdbc
				.sql("SELECT key_ciphertext, sync_counter FROM user_dictionary_sync_state WHERE user_id = :user FOR UPDATE")
				.param("user", userId)
				.query((rs, n) -> new State(encryption.decrypt(userId, context(userId), rs.getBytes(1)), rs.getLong(2)))
				.single();
	}

	/** Records the highest version handed out; the caller holds the {@link #lock}. */
	void saveCounter(UUID userId, long counter) {
		jdbc.sql("UPDATE user_dictionary_sync_state SET sync_counter = :counter, updated_at = NOW() WHERE user_id = :user")
				.param("counter", counter).param("user", userId).update();
	}

	/** The hash of one word; {@code nfcWord} must already be NFC-normalised. */
	static String hmac(byte[] key, String languageCode, String nfcWord) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			mac.update(DOMAIN);
			mac.update((byte) 0);
			mac.update(languageCode.getBytes(StandardCharsets.UTF_8));
			mac.update((byte) 0);
			return HexFormat.of().formatHex(mac.doFinal(nfcWord.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 unavailable", e);
		}
	}
}
