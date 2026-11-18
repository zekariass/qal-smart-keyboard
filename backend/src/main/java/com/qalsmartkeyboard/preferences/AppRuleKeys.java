package com.qalsmartkeyboard.preferences;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Types;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;

/**
 * The per-account key behind {@code appPackageHmac = HMAC-SHA256(key, packageName)} (ADR 0012): 256 random bits, stored only
 * as ciphertext under the user's data key (row id = user id). It is created on first use: the first
 * {@code GET /me/app-destination-rules/key} (ADR 0007 D24, the way a device obtains it) or the first non-empty rules
 * document, whichever comes first, so "an account has rules" implies "an account has a key". The key is never regenerated.
 */
@Component
class AppRuleKeys {

	static final String TABLE = "user_app_rule_keys";
	private static final String COLUMN = "key_ciphertext";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;

	AppRuleKeys(JdbcClient jdbc, UserDataEncryption encryption) {
		this.jdbc = jdbc;
		this.encryption = encryption;
	}

	static EncryptionContext context(UUID userId) {
		return EncryptionContext.of(TABLE, COLUMN, userId);
	}

	/** The key as base64url without padding; created on first use. */
	String getOrCreate(UUID userId) {
		byte[] ciphertext = jdbc.sql("SELECT key_ciphertext FROM user_app_rule_keys WHERE user_id = :user")
				.param("user", userId).query(byte[].class).optional().orElse(null);
		if (ciphertext == null) {
			byte[] key = new byte[32];
			RANDOM.nextBytes(key);
			EncryptedValue sealed = encryption.encrypt(userId, context(userId), key);
			// the caller holds the per-user lock, so there is no second creator; ON CONFLICT keeps it safe anyway
			jdbc.sql("""
					INSERT INTO user_app_rule_keys (user_id, key_ciphertext, key_version) VALUES (:user, :ct, :version)
					ON CONFLICT (user_id) DO NOTHING
					""").param("user", userId).param("ct", sealed.ciphertext(), Types.BINARY)
					.param("version", sealed.keyVersion()).update();
			return getOrCreate(userId);
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(encryption.decrypt(userId, context(userId), ciphertext));
	}

	/** The wire form of the key: {@code key} is standard base64 of the 32 bytes, {@code keyId} an opaque id of this key. */
	record RuleKey(String keyId, String key) {
	}

	/** The key for the delivery operation; created on first read. The key and its id never reach a log. */
	RuleKey material(UUID userId) {
		byte[] raw = Base64.getUrlDecoder().decode(getOrCreate(userId));
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
			return new RuleKey(HexFormat.of().formatHex(digest, 0, 8), Base64.getEncoder().encodeToString(raw));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	boolean exists(UUID userId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM user_app_rule_keys WHERE user_id = :user)").param("user", userId)
				.query(Boolean.class).single();
	}
}
