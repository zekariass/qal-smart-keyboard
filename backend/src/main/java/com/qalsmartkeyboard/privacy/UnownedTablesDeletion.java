package com.qalsmartkeyboard.privacy;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.UserDeletionParticipant;

/**
 * Account deletion (B6.2, ADR 0015) for per-user tables whose owning module has no code yet (SD 20.2 domains AI,
 * Voice, Organisations, Admin). Privacy holds their rules until the owner exists; <b>when a module starts writing one
 * of these tables it takes the rule over into its own participant and removes it here</b> (repeating it is harmless:
 * every statement is idempotent).
 * <ul>
 * <li>{@code ai_feedback} (AI): deleted (rating linked to the person; a comment is already crypto-shredded).</li>
 * <li>{@code voice_jobs}, {@code realtime_sessions} (Voice): detached ({@code user_id = NULL}, as their
 * {@code ON DELETE SET NULL} prescribes); operational metadata, audio is deleted by its own lifecycle.</li>
 * <li>{@code organization_members} (Organisations): deleted; the seat assignments follow by cascade.</li>
 * <li>{@code user_admin_roles} (Admin): deleted.</li>
 * </ul>
 * {@code ai_requests} (metadata, logical user id) and {@code audit_events} are kept pseudonymised, see ADR 0015.
 */
@Component
class UnownedTablesDeletion implements UserDeletionParticipant {

	private static final Logger log = LoggerFactory.getLogger(UnownedTablesDeletion.class);

	private final JdbcClient jdbc;

	UnownedTablesDeletion(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int deletionOrder() {
		return UNOWNED;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int rows = 0;
		for (String sql : new String[] { "DELETE FROM ai_feedback WHERE user_id = :user",
				"UPDATE voice_jobs SET user_id = NULL WHERE user_id = :user",
				"UPDATE realtime_sessions SET user_id = NULL WHERE user_id = :user",
				"DELETE FROM organization_members WHERE user_id = :user",
				"DELETE FROM user_admin_roles WHERE user_id = :user" }) {
			rows += jdbc.sql(sql).param("user", userId).update();
		}
		if (rows > 0) {
			log.info("Account deletion {}: {} rows of module-less tables deleted or detached", userId, rows);
		}
		return rows;
	}
}
