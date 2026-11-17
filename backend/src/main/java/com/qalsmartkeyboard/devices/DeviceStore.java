package com.qalsmartkeyboard.devices;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL for {@code user_devices} (V1). Must run inside the caller's transaction. */
class DeviceStore {

	record DeviceRow(UUID id, UUID userId, UUID installationId, String platform, String deviceName, String appVersion,
			String osVersion, String status, String attestationStatus, Instant lastSeenAt, Instant createdAt) {
	}

	private static final String COLUMNS = """
			id, user_id, installation_id, platform, device_name, app_version, os_version, device_status,
			attestation_status, last_seen_at, created_at""";

	private final JdbcClient jdbc;

	DeviceStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Serialises registrations of one installation of one user (the unique key alone would turn a race into a 500). */
	void lockInstallation(UUID userId, UUID installationId) {
		advisoryLock("qal.device:" + userId + ":" + installationId);
	}

	/** Serialises guest creation per installation id (see {@link #guestOwnsInstallation}). */
	void lockInstallationId(UUID installationId) {
		advisoryLock("qal.device-installation:" + installationId);
	}

	/**
	 * Whether a live GUEST account already owns this installation id. Reads {@code users.account_type} only to classify
	 * the owner of a device row.
	 */
	boolean guestOwnsInstallation(UUID installationId) {
		return jdbc.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM user_devices d JOIN users u ON u.id = d.user_id
				    WHERE d.installation_id = :installationId AND u.account_type = 'GUEST'
				      AND u.deleted_at IS NULL AND u.account_status NOT IN ('DELETED', 'MERGED'))
				""").param("installationId", installationId).query(Boolean.class).single();
	}

	private void advisoryLock(String key) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", key).query().singleRow();
	}

	Optional<DeviceRow> findByInstallationForUpdate(UUID userId, UUID installationId) {
		return jdbc.sql("SELECT " + COLUMNS
				+ " FROM user_devices WHERE user_id = :userId AND installation_id = :installationId FOR UPDATE")
				.param("userId", userId).param("installationId", installationId).query(DeviceStore::map).optional();
	}

	/** Only the owner's rows are ever visible (another user's device id is "not found"). */
	Optional<DeviceRow> findOwnedForUpdate(UUID userId, UUID deviceId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM user_devices WHERE id = :id AND user_id = :userId FOR UPDATE")
				.param("id", deviceId).param("userId", userId).query(DeviceStore::map).optional();
	}

	Optional<DeviceRow> findOwned(UUID userId, UUID deviceId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM user_devices WHERE id = :id AND user_id = :userId")
				.param("id", deviceId).param("userId", userId).query(DeviceStore::map).optional();
	}

	/**
	 * One page of the owner's devices, oldest first ({@code created_at, id}), after the keyset position
	 * ({@code afterCreatedAt}/{@code afterId} both {@code null} for the first page).
	 */
	List<DeviceRow> listOwned(UUID userId, Instant afterCreatedAt, UUID afterId, int limit) {
		if (afterCreatedAt == null) {
			return jdbc.sql("SELECT " + COLUMNS
					+ " FROM user_devices WHERE user_id = :userId ORDER BY created_at, id LIMIT :limit")
					.param("userId", userId).param("limit", limit).query(DeviceStore::map).list();
		}
		return jdbc.sql("SELECT " + COLUMNS + """
				 FROM user_devices WHERE user_id = :userId AND (created_at, id) > (:ts, :afterId)
				ORDER BY created_at, id LIMIT :limit""").param("userId", userId)
				.param("ts", Timestamp.from(afterCreatedAt)).param("afterId", afterId).param("limit", limit)
				.query(DeviceStore::map).list();
	}

	void insert(UUID id, UUID userId, UUID installationId, String platform, String deviceName, String appVersion,
			String osVersion, Instant now) {
		jdbc.sql("""
				INSERT INTO user_devices (id, user_id, installation_id, platform, device_name, app_version, os_version,
				                          last_seen_at, created_at, updated_at)
				VALUES (:id, :userId, :installationId, :platform, :deviceName, :appVersion, :osVersion, :now, :now, :now)
				""").param("id", id).param("userId", userId).param("installationId", installationId)
				.param("platform", platform).param("deviceName", deviceName).param("appVersion", appVersion)
				.param("osVersion", osVersion).param("now", Timestamp.from(now)).update();
	}

	/**
	 * Refreshes the client-reported fields (a {@code null} keeps the stored value) and marks the device seen. A REVOKED
	 * device becomes ACTIVE again, with its attestation reset: a verdict from before the revocation proves nothing.
	 */
	void refresh(UUID id, String deviceName, String appVersion, String osVersion, Instant now) {
		jdbc.sql("""
				UPDATE user_devices SET
				    device_name = COALESCE(:deviceName, device_name),
				    app_version = COALESCE(:appVersion, app_version),
				    os_version = COALESCE(:osVersion, os_version),
				    attestation_status = CASE WHEN device_status = 'REVOKED' THEN 'NOT_CHECKED' ELSE attestation_status END,
				    attestation_provider = CASE WHEN device_status = 'REVOKED' THEN NULL ELSE attestation_provider END,
				    attestation_checked_at = CASE WHEN device_status = 'REVOKED' THEN NULL ELSE attestation_checked_at END,
				    device_status = 'ACTIVE', last_seen_at = :now, updated_at = :now
				WHERE id = :id
				""").param("id", id).param("deviceName", deviceName).param("appVersion", appVersion)
				.param("osVersion", osVersion).param("now", Timestamp.from(now)).update();
	}

	/**
	 * Stores the push token on the device. The same token on another ACTIVE device is cleared first: a token addresses
	 * one app install, and the unique index allows it on one active row only (the newest registration wins). Moves of
	 * one token are serialised by an advisory lock.
	 *
	 * @return how many other devices lost the token
	 */
	int setPushToken(UUID id, String provider, String token) {
		advisoryLock("qal.push-token:" + token);
		int moved = jdbc.sql("""
				UPDATE user_devices SET push_provider = NULL, push_token = NULL
				WHERE push_token = :token AND id <> :id AND device_status = 'ACTIVE'
				""").param("token", token).param("id", id).update();
		jdbc.sql("UPDATE user_devices SET push_provider = :provider, push_token = :token WHERE id = :id")
				.param("provider", provider).param("token", token).param("id", id).update();
		return moved;
	}

	void clearPushToken(UUID id) {
		jdbc.sql("UPDATE user_devices SET push_provider = NULL, push_token = NULL WHERE id = :id").param("id", id)
				.update();
	}

	void recordAttestation(UUID id, String provider, String status, Instant checkedAt) {
		jdbc.sql("""
				UPDATE user_devices SET attestation_provider = :provider, attestation_status = :status,
				       attestation_checked_at = :checkedAt, updated_at = :checkedAt
				WHERE id = :id
				""").param("id", id).param("provider", provider).param("status", status)
				.param("checkedAt", Timestamp.from(checkedAt)).update();
	}

	/** The push token is dropped with the device: nothing may be pushed to a revoked installation. */
	void markRevoked(UUID id, Instant now) {
		jdbc.sql("""
				UPDATE user_devices SET device_status = 'REVOKED', push_provider = NULL, push_token = NULL,
				       updated_at = :now
				WHERE id = :id
				""").param("id", id).param("now", Timestamp.from(now)).update();
	}

	private static DeviceRow map(ResultSet rs, int row) throws SQLException {
		return new DeviceRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getObject("installation_id", UUID.class), rs.getString("platform"), rs.getString("device_name"),
				rs.getString("app_version"), rs.getString("os_version"), rs.getString("device_status"),
				rs.getString("attestation_status"), instant(rs, "last_seen_at"), instant(rs, "created_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}
}
