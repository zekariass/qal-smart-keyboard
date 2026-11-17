package com.qalsmartkeyboard.identity.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL for {@code user_sessions} (V1). One row per refresh token; rows of one chain share {@code token_family_id}.
 * Must run inside the caller's transaction.
 */
class SessionStore {

	record SessionRow(UUID id, UUID userId, UUID deviceId, UUID familyId, String status, Instant expiresAt,
			String revokedReason) {
	}

	private final JdbcClient jdbc;

	SessionStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(UUID id, UUID userId, UUID deviceId, UUID familyId, String tokenHash, String ip, String userAgent,
			Instant now, Instant expiresAt) {
		jdbc.sql("""
				INSERT INTO user_sessions (id, user_id, device_id, token_family_id, refresh_token_hash, session_status,
				                           ip_address, user_agent, created_at, last_used_at, expires_at)
				VALUES (:id, :userId, :deviceId, :familyId, :hash, 'ACTIVE', CAST(:ip AS inet), :userAgent, :now, :now,
				        :expiresAt)
				""").param("id", id).param("userId", userId).param("deviceId", deviceId).param("familyId", familyId)
				.param("hash", tokenHash).param("ip", ip).param("userAgent", userAgent).param("now", Timestamp.from(now))
				.param("expiresAt", Timestamp.from(expiresAt)).update();
	}

	/** Locks the row: concurrent refreshes of one token are serialised here. */
	Optional<SessionRow> findByHashForUpdate(String tokenHash) {
		return jdbc.sql("""
				SELECT id, user_id, device_id, token_family_id, session_status, expires_at, revoked_reason
				FROM user_sessions WHERE refresh_token_hash = :hash FOR UPDATE
				""").param("hash", tokenHash).query(SessionStore::map).optional();
	}

	/** Compare-and-set ACTIVE → ROTATED. @return whether this caller rotated it */
	boolean markRotated(UUID id, UUID replacedBy, Instant now) {
		return jdbc.sql("""
				UPDATE user_sessions SET session_status = 'ROTATED', rotated_at = :now, replaced_by_session_id = :next,
				       last_used_at = :now
				WHERE id = :id AND session_status = 'ACTIVE'
				""").param("now", Timestamp.from(now)).param("next", replacedBy).param("id", id).update() == 1;
	}

	void markExpired(UUID id) {
		jdbc.sql("UPDATE user_sessions SET session_status = 'EXPIRED' WHERE id = :id AND session_status = 'ACTIVE'")
				.param("id", id).update();
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

	int revokeAllOfUser(UUID userId, String reason, Instant now) {
		return jdbc.sql("""
				UPDATE user_sessions SET session_status = 'REVOKED', revoked_at = :now, revoked_reason = :reason
				WHERE user_id = :userId AND session_status = 'ACTIVE'
				""").param("now", Timestamp.from(now)).param("reason", reason).param("userId", userId).update();
	}

	private static SessionRow map(ResultSet rs, int row) throws SQLException {
		return new SessionRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getObject("device_id", UUID.class), rs.getObject("token_family_id", UUID.class),
				rs.getString("session_status"), rs.getTimestamp("expires_at").toInstant(), rs.getString("revoked_reason"));
	}
}
