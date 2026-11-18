package com.qalsmartkeyboard.identity.retention;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.platform.retention.RetentionAction;
import com.qalsmartkeyboard.platform.retention.RetentionParticipant;
import com.qalsmartkeyboard.platform.retention.RetentionRule;
import com.qalsmartkeyboard.platform.retention.RetentionSupport;

/**
 * Retention (B6.3, ADR 0020) of the identity tables.
 *
 * <ul>
 * <li>{@code otp_challenges}: 24 h after {@code expires_at}. A challenge is useless once expired; the day is a margin for
 * abuse investigation (SD gives no period; V1 note 10 lists these rows for purging).</li>
 * <li>{@code attestation_nonces}: 1 h after expiry (V5 "purged shortly after expiry").</li>
 * <li>{@code password_reset_tokens}: 24 h after expiry (V1 note 10; no writer yet).</li>
 * <li>{@code user_sessions}: 30 days after the <b>whole token family</b> expired. Rows of a live family are never
 * touched (a ROTATED row is what detects refresh-token reuse), and a family goes as a unit because
 * {@code replaced_by_session_id ON DELETE SET NULL} would otherwise break {@code ck_session_rotation} on the rows left
 * behind.</li>
 * <li>MERGED guest rows: 30 days after the merge (SD 19.2 "life of account + 30 days"; ADR 0010 open item). Everything
 * that still hangs on the row (sessions, revoked devices, consents, counters) goes with it by cascade.</li>
 * <li>DELETED tombstones (ADR 0015): 30 days after {@code deleted_at}, for the same reason and with the backup cap of
 * ADR 0007 D1.</li>
 * </ul>
 * A {@code users} row is deleted only if no {@code ON DELETE RESTRICT} (or NO ACTION) foreign key points at it: a person
 * with payment, subscription or credit records keeps the anonymised tombstone for the legal period of those rows (SD 19.2,
 * ADR 0015 D3). The blocking tables are read from the catalogue, so a table added later is respected without a code
 * change.
 */
@Component
class IdentityRetention implements RetentionParticipant {

	static final String OTP = "otp-challenges";
	static final String NONCES = "attestation-nonces";
	static final String RESET_TOKENS = "password-reset-tokens";
	static final String SESSIONS = "user-sessions";
	static final String MERGED_GUESTS = "merged-guest-accounts";
	static final String DELETED_TOMBSTONES = "deleted-account-tombstones";

	private final JdbcClient jdbc;

	IdentityRetention(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int order() {
		return 900;
	}

	@Override
	public List<RetentionRule> rules() {
		return List.of(
				new RetentionRule(OTP, "identity", RetentionAction.DELETE, Duration.ofHours(24), "UNDEFINED",
						"otp_challenges, counted from expires_at"),
				new RetentionRule(NONCES, "identity", RetentionAction.DELETE, Duration.ofHours(1), "UNDEFINED",
						"attestation_nonces, counted from expires_at (V5)"),
				new RetentionRule(RESET_TOKENS, "identity", RetentionAction.DELETE, Duration.ofHours(24), "UNDEFINED",
						"password_reset_tokens, counted from expires_at"),
				new RetentionRule(SESSIONS, "identity", RetentionAction.DELETE, Duration.ofDays(30), "UNDEFINED",
						"user_sessions of a token family whose every session expired, counted from the latest expires_at"),
				new RetentionRule(MERGED_GUESTS, "identity", RetentionAction.DELETE, Duration.ofDays(30),
						"SD 19.2 (life of account + 30 days); ADR 0010",
						"users rows of guests merged into an account, counted from the merge; kept while a RESTRICT foreign key points at them"),
				new RetentionRule(DELETED_TOMBSTONES, "identity", RetentionAction.DELETE, Duration.ofDays(30),
						"SD 19.2 (life of account + 30 days); ADR 0015, ADR 0007 D1",
						"anonymised users rows of deleted accounts, counted from deleted_at; kept while a RESTRICT foreign key (financial records) points at them"));
	}

	@Override
	public int purge(RetentionRule rule, Instant now, Instant cutoff, int limit) {
		return switch (rule.code()) {
			case OTP -> RetentionSupport.deleteBatch(jdbc, "otp_challenges", "id", "expires_at < :cutoff", now, cutoff,
					limit);
			case NONCES -> RetentionSupport.deleteBatch(jdbc, "attestation_nonces", "id", "expires_at < :cutoff", now,
					cutoff, limit);
			case RESET_TOKENS -> RetentionSupport.deleteBatch(jdbc, "password_reset_tokens", "id",
					"expires_at < :cutoff", now, cutoff, limit);
			case SESSIONS -> purgeSessions(cutoff, limit);
			case MERGED_GUESTS -> purgeUsers("account_status = 'MERGED' AND u.updated_at < :cutoff", "u.updated_at",
					cutoff, limit);
			case DELETED_TOMBSTONES -> purgeUsers("account_status = 'DELETED' AND u.deleted_at < :cutoff",
					"u.deleted_at", cutoff, limit);
			default -> throw new IllegalArgumentException("Not an executable identity rule: " + rule.code());
		};
	}

	/** Whole families whose newest session expired before the cutoff; the advisory lock of the job keeps purgers apart. */
	private int purgeSessions(Instant cutoff, int limit) {
		return jdbc.sql("""
				DELETE FROM user_sessions WHERE token_family_id IN (
				    SELECT DISTINCT s.token_family_id FROM user_sessions s
				    WHERE s.expires_at < :cutoff
				      AND NOT EXISTS (SELECT 1 FROM user_sessions o
				                      WHERE o.token_family_id = s.token_family_id AND o.expires_at >= :cutoff)
				    LIMIT :limit)
				""").param("cutoff", Timestamp.from(cutoff)).param("limit", limit).update();
	}

	private int purgeUsers(String condition, String orderBy, Instant cutoff, int limit) {
		String unblocked = blockingTables().stream()
				.map(b -> " AND NOT EXISTS (SELECT 1 FROM " + b.table() + " r WHERE r." + b.column() + " = u.id)")
				.collect(Collectors.joining());
		return jdbc.sql("DELETE FROM users WHERE id IN (SELECT u.id FROM users u WHERE u." + condition + unblocked
				+ " ORDER BY " + orderBy + " LIMIT :limit FOR UPDATE OF u SKIP LOCKED)")
				.param("cutoff", Timestamp.from(cutoff)).param("limit", limit).update();
	}

	record Blocking(String table, String column) {
	}

	/** Every column that references {@code users(id)} with RESTRICT or NO ACTION, read from the catalogue. */
	List<Blocking> blockingTables() {
		return jdbc.sql("""
				SELECT c.conrelid::regclass::text AS tbl, quote_ident(a.attname) AS col
				FROM pg_constraint c
				CROSS JOIN LATERAL unnest(c.conkey, c.confkey) AS k(conattnum, confattnum)
				JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.conattnum
				JOIN pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = k.confattnum AND ra.attname = 'id'
				WHERE c.contype = 'f' AND c.confrelid = 'users'::regclass AND c.confdeltype IN ('r', 'a')
				ORDER BY 1, 2
				""").query((rs, n) -> new Blocking(rs.getString(1), rs.getString(2))).list();
	}
}
