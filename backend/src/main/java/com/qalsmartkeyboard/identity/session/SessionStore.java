package com.qalsmartkeyboard.identity.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL for {@code user_sessions} (V1, V4). One row per refresh token; rows of one chain share {@code token_family_id}.
 * Must run inside the caller's transaction.
 *
 * <p>{@code last_used_at} is {@code NULL} until the row's refresh token is first presented (rotation, expiry, or a
 * refresh refused for a suspended account). The refresh grace window (ADR 0007 D8) relies on it: only a successor that
 * was never presented may be superseded by a retry.
 *
 * <p>Lock order (no deadlocks): the family's advisory lock, then the presented row, then its successor row, then
 * (family revocation) the family's ACTIVE rows. Logout takes the family lock(s) before its update.
 */
class SessionStore {

	/** {@code revoked_reason} of a successor that a grace-window retry replaced (V4). */
	static final String SUPERSEDED_RETRY = "SUPERSEDED_RETRY";

	record SessionRow(UUID id, UUID userId, UUID deviceId, UUID familyId, String status, Instant expiresAt,
			String revokedReason, UUID replacedBy, Instant rotatedAt, Instant lastUsedAt, Instant reuseDetectedAt) {
	}

	private static final String COLUMNS = """
			id, user_id, device_id, token_family_id, session_status, expires_at, revoked_reason, replaced_by_session_id,
			rotated_at, last_used_at, reuse_detected_at""";

	private final JdbcClient jdbc;

	SessionStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** A new ACTIVE row; {@code last_used_at} stays {@code NULL} until the token is presented. */
	void insert(UUID id, UUID userId, UUID deviceId, UUID familyId, String tokenHash, String ip, String userAgent,
			Instant now, Instant expiresAt) {
		jdbc.sql("""
				INSERT INTO user_sessions (id, user_id, device_id, token_family_id, refresh_token_hash, session_status,
				                           ip_address, user_agent, created_at, expires_at)
				VALUES (:id, :userId, :deviceId, :familyId, :hash, 'ACTIVE', CAST(:ip AS inet), :userAgent, :now,
				        :expiresAt)
				""").param("id", id).param("userId", userId).param("deviceId", deviceId).param("familyId", familyId)
				.param("hash", tokenHash).param("ip", ip).param("userAgent", userAgent).param("now", Timestamp.from(now))
				.param("expiresAt", Timestamp.from(expiresAt)).update();
	}

	/** The family of a token, without locking anything (the family lock comes before any row lock). */
	Optional<UUID> findFamilyByHash(String tokenHash) {
		return jdbc.sql("SELECT token_family_id FROM user_sessions WHERE refresh_token_hash = :hash")
				.param("hash", tokenHash).query(UUID.class).optional();
	}

	/**
	 * Serialises every refresh, grace retry and logout of one family across instances for the rest of the transaction.
	 * Statements that run after it see everything earlier holders committed, so a family revocation cannot miss a
	 * successor inserted by a concurrent rotation (a READ COMMITTED {@code UPDATE} alone does not see such a row).
	 */
	void lockFamily(UUID familyId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
				.param("key", "qal.session-family:" + familyId).query().singleRow();
	}

	/** Locks the presented row. */
	Optional<SessionRow> findByHashForUpdate(String tokenHash) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM user_sessions WHERE refresh_token_hash = :hash FOR UPDATE")
				.param("hash", tokenHash).query(SessionStore::map).optional();
	}

	/** Locks a row by id (the successor of the presented row, always locked after it). */
	Optional<SessionRow> findByIdForUpdate(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM user_sessions WHERE id = :id FOR UPDATE").param("id", id)
				.query(SessionStore::map).optional();
	}

	/** Compare-and-set ACTIVE → ROTATED. @return whether this caller rotated it */
	boolean markRotated(UUID id, UUID replacedBy, Instant now) {
		return jdbc.sql("""
				UPDATE user_sessions SET session_status = 'ROTATED', rotated_at = :now, replaced_by_session_id = :next,
				       last_used_at = :now
				WHERE id = :id AND session_status = 'ACTIVE'
				""").param("now", Timestamp.from(now)).param("next", replacedBy).param("id", id).update() == 1;
	}

	/**
	 * Grace retry: compare-and-set of a ROTATED row's successor pointer from the superseded successor to the new one.
	 * {@code rotated_at} keeps the first rotation, so retries never extend the grace window.
	 */
	boolean repointRotated(UUID id, UUID previousNext, UUID next, Instant now) {
		return jdbc.sql("""
				UPDATE user_sessions SET replaced_by_session_id = :next, last_used_at = :now
				WHERE id = :id AND session_status = 'ROTATED' AND replaced_by_session_id = :previousNext
				""").param("next", next).param("now", Timestamp.from(now)).param("id", id)
				.param("previousNext", previousNext).update() == 1;
	}

	/** Grace retry: revokes a successor that was never presented ({@value #SUPERSEDED_RETRY}). */
	boolean supersedeUnused(UUID id, Instant now) {
		return jdbc.sql("""
				UPDATE user_sessions SET session_status = 'REVOKED', revoked_at = :now, revoked_reason = :reason
				WHERE id = :id AND session_status = 'ACTIVE' AND last_used_at IS NULL
				""").param("now", Timestamp.from(now)).param("reason", SUPERSEDED_RETRY).param("id", id).update() == 1;
	}

	/** Records that the token was presented without being rotated, so it no longer counts as an unused successor. */
	void markUsed(UUID id, Instant now) {
		jdbc.sql("UPDATE user_sessions SET last_used_at = :now WHERE id = :id").param("now", Timestamp.from(now))
				.param("id", id).update();
	}

	void markExpired(UUID id, Instant now) {
		jdbc.sql("""
				UPDATE user_sessions SET session_status = 'EXPIRED', last_used_at = :now
				WHERE id = :id AND session_status = 'ACTIVE'
				""").param("now", Timestamp.from(now)).param("id", id).update();
	}

	/** @return whether this is the first detection on this row */
	boolean markReuseDetected(UUID id, Instant now) {
		return jdbc.sql("UPDATE user_sessions SET reuse_detected_at = :now WHERE id = :id AND reuse_detected_at IS NULL")
				.param("now", Timestamp.from(now)).param("id", id).update() == 1;
	}

	/** Revokes every still-ACTIVE row of a family. Rotated rows keep their status (the chain stays readable). */
	int revokeFamily(UUID familyId, String reason, Instant now) {
		return jdbc.sql("""
				UPDATE user_sessions SET session_status = 'REVOKED', revoked_at = :now, revoked_reason = :reason
				WHERE token_family_id = :familyId AND session_status = 'ACTIVE'
				""").param("now", Timestamp.from(now)).param("reason", reason).param("familyId", familyId).update();
	}

	/** Revokes the family only if it belongs to {@code userId} (logout by access token). */
	int revokeFamilyOfUser(UUID familyId, UUID userId, String reason, Instant now) {
		return jdbc.sql("""
				UPDATE user_sessions SET session_status = 'REVOKED', revoked_at = :now, revoked_reason = :reason
				WHERE token_family_id = :familyId AND user_id = :userId AND session_status = 'ACTIVE'
				""").param("now", Timestamp.from(now)).param("reason", reason).param("familyId", familyId)
				.param("userId", userId).update();
	}

	/** Families of the user that still have an ACTIVE row, in a stable order (they are locked in this order). */
	List<UUID> activeFamiliesOfUser(UUID userId) {
		return jdbc.sql("""
				SELECT DISTINCT token_family_id FROM user_sessions
				WHERE user_id = :userId AND session_status = 'ACTIVE' ORDER BY token_family_id
				""").param("userId", userId).query(UUID.class).list();
	}

	/** Families of the user with an ACTIVE row bound to the device, in a stable order (they are locked in this order). */
	List<UUID> activeFamiliesOfDevice(UUID userId, UUID deviceId) {
		return jdbc.sql("""
				SELECT DISTINCT token_family_id FROM user_sessions
				WHERE user_id = :userId AND device_id = :deviceId AND session_status = 'ACTIVE'
				ORDER BY token_family_id
				""").param("userId", userId).param("deviceId", deviceId).query(UUID.class).list();
	}

	/** Binds the ACTIVE rows of a family that have no device yet. @return rows bound */
	int bindDevice(UUID familyId, UUID userId, UUID deviceId) {
		return jdbc.sql("""
				UPDATE user_sessions SET device_id = :deviceId
				WHERE token_family_id = :familyId AND user_id = :userId AND session_status = 'ACTIVE'
				  AND device_id IS NULL
				""").param("deviceId", deviceId).param("familyId", familyId).param("userId", userId).update();
	}

	int revokeAllOfUser(UUID userId, String reason, Instant now) {
		return jdbc.sql("""
				UPDATE user_sessions SET session_status = 'REVOKED', revoked_at = :now, revoked_reason = :reason
				WHERE user_id = :userId AND session_status = 'ACTIVE'
				""").param("now", Timestamp.from(now)).param("reason", reason).param("userId", userId).update();
	}

	private static SessionRow map(ResultSet rs, int row) throws SQLException {
		return new SessionRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getObject("device_id", UUID.class), rs.getObject("token_family_id", UUID.class),
				rs.getString("session_status"), rs.getTimestamp("expires_at").toInstant(), rs.getString("revoked_reason"),
				rs.getObject("replaced_by_session_id", UUID.class), instant(rs, "rotated_at"),
				instant(rs, "last_used_at"), instant(rs, "reuse_detected_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
}
