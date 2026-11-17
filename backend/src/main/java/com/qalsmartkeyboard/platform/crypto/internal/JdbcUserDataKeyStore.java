package com.qalsmartkeyboard.platform.crypto.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.platform.crypto.kms.WrappedKey;

/** {@code user_data_keys} through {@link JdbcClient}; joins the caller's Spring transaction. */
final class JdbcUserDataKeyStore implements UserDataKeyStore {

	/** kms_key_id of a shred marker row (NOT NULL column; the row never had a wrapped key). */
	static final String SHRED_MARKER_KMS_KEY_ID = "none:shredded";

	private static final String COLUMNS = "key_version, key_status, kms_key_id, wrapped_dek";

	private final JdbcClient jdbc;

	JdbcUserDataKeyStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<KeyRecord> findAll(UUID userId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM user_data_keys WHERE user_id = :userId ORDER BY key_version")
				.param("userId", userId)
				.query(JdbcUserDataKeyStore::map)
				.list();
	}

	@Override
	public Optional<KeyRecord> find(UUID userId, int keyVersion) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM user_data_keys WHERE user_id = :userId AND key_version = :v")
				.param("userId", userId)
				.param("v", keyVersion)
				.query(JdbcUserDataKeyStore::map)
				.optional();
	}

	@Override
	public void lockUser(UUID userId) {
		// Transaction-scoped advisory lock; avoids locking identity's users row (no cross-module table writes).
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))")
				.param("k", "qal.user_data_keys:" + userId)
				.query((rs, n) -> 1)
				.list();
	}

	@Override
	public void insertActive(UUID userId, int keyVersion, WrappedKey wrapped) {
		jdbc.sql("""
				INSERT INTO user_data_keys (user_id, key_version, wrapped_dek, kms_key_id, key_status)
				VALUES (:userId, :v, :wrapped, :kmsKeyId, 'ACTIVE')""")
				.param("userId", userId)
				.param("v", keyVersion)
				.param("wrapped", wrapped.ciphertext())
				.param("kmsKeyId", wrapped.kmsKeyId())
				.update();
	}

	@Override
	public int markActiveRotated(UUID userId) {
		return jdbc.sql("""
				UPDATE user_data_keys SET key_status = 'ROTATED', rotated_at = now()
				WHERE user_id = :userId AND key_status = 'ACTIVE'""")
				.param("userId", userId)
				.update();
	}

	@Override
	public int destroyAll(UUID userId) {
		return jdbc.sql("""
				UPDATE user_data_keys SET key_status = 'DESTROYED', wrapped_dek = NULL, destroyed_at = now()
				WHERE user_id = :userId AND key_status <> 'DESTROYED'""")
				.param("userId", userId)
				.update();
	}

	@Override
	public int insertShredMarker(UUID userId) {
		// The EXISTS check only mirrors the FK (user_data_keys.user_id -> users.id) so that shredding an
		// already-deleted user is a no-op instead of an FK error that would abort the caller's transaction.
		return jdbc.sql("""
				INSERT INTO user_data_keys (user_id, key_version, wrapped_dek, kms_key_id, key_status, destroyed_at)
				SELECT :userId, 1, NULL, :marker, 'DESTROYED', now()
				WHERE EXISTS (SELECT 1 FROM users WHERE id = :userId)""")
				.param("userId", userId)
				.param("marker", SHRED_MARKER_KMS_KEY_ID)
				.update();
	}

	private static KeyRecord map(ResultSet rs, int rowNum) throws SQLException {
		KeyStatus status = KeyStatus.valueOf(rs.getString("key_status"));
		byte[] wrapped = rs.getBytes("wrapped_dek");
		return new KeyRecord(rs.getInt("key_version"), status,
				wrapped == null ? null : new WrappedKey(rs.getString("kms_key_id"), wrapped));
	}
}
