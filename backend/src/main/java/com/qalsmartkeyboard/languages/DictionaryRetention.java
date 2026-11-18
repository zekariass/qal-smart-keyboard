package com.qalsmartkeyboard.languages;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.platform.retention.RetentionAction;
import com.qalsmartkeyboard.platform.retention.RetentionParticipant;
import com.qalsmartkeyboard.platform.retention.RetentionRule;
import com.qalsmartkeyboard.platform.retention.RetentionSupport;

/**
 * Retention (B6.3, ADR 0020) of the personal-dictionary sync tables (ADR 0018).
 *
 * <ul>
 * <li>{@code user_dictionary_applied_changes}: 30 days after {@code applied_at}. Every push already prunes the
 * pushing user's own rows; this covers users who stopped pushing.</li>
 * <li>Tombstones ({@code user_dictionary_entries.deleted_at}): <b>180 days</b> (ADR 0007 D31). A purged tombstone would
 * leave a device that never saw it holding the deleted word forever, so the same statement raises the user's
 * {@code tombstone_floor} (V16) to the highest {@code sync_version} it removed; {@code DictionarySyncService.pull}
 * answers a cursor behind that floor with 410 {@code SYNC_CURSOR_EXPIRED} and the client downloads the dictionary again.</li>
 * </ul>
 *
 * <p>Locking: a batch works on whole users. It locks the user's {@code user_dictionary_sync_state} row first ({@code FOR
 * UPDATE SKIP LOCKED}, the lock every push and merge takes) and only then the entries, the same order as a push, so a
 * purge never deadlocks with one and never removes a tombstone a push is reviving; a user whose push is in flight is
 * left for the next run.
 */
@Component
class DictionaryRetention implements RetentionParticipant {

	static final String APPLIED_CHANGES = "dictionary-applied-changes";
	static final String TOMBSTONES = "dictionary-tombstones";

	private final JdbcClient jdbc;

	DictionaryRetention(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int order() {
		return 500;
	}

	@Override
	public List<RetentionRule> rules() {
		return List.of(
				new RetentionRule(APPLIED_CHANGES, "languages", RetentionAction.DELETE, Duration.ofDays(30),
						"ADR 0018", "user_dictionary_applied_changes (change ids), counted from applied_at"),
				new RetentionRule(TOMBSTONES, "languages", RetentionAction.DELETE, Duration.ofDays(180),
						"ADR 0007 D31", "user_dictionary_entries with deleted_at set, counted from deleted_at; raises tombstone_floor"));
	}

	@Override
	public int purge(RetentionRule rule, Instant now, Instant cutoff, int limit) {
		return switch (rule.code()) {
			case APPLIED_CHANGES -> RetentionSupport.deleteBatchByCtid(jdbc, "user_dictionary_applied_changes",
					"applied_at < :cutoff", now, cutoff, limit);
			case TOMBSTONES -> purgeTombstones(cutoff, limit);
			default -> throw new IllegalArgumentException("Not an executable languages rule: " + rule.code());
		};
	}

	/** One batch of up to {@code limit} users; returns the number of tombstones removed. */
	private int purgeTombstones(Instant cutoff, int limit) {
		return jdbc.sql("""
				WITH due AS (
				    SELECT DISTINCT user_id FROM user_dictionary_entries
				    WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff LIMIT :limit),
				locked AS (
				    SELECT s.user_id FROM user_dictionary_sync_state s JOIN due d ON d.user_id = s.user_id
				    ORDER BY s.user_id FOR UPDATE OF s SKIP LOCKED),
				removed AS (
				    DELETE FROM user_dictionary_entries e USING locked l
				    WHERE e.user_id = l.user_id AND e.deleted_at IS NOT NULL AND e.deleted_at < :cutoff
				    RETURNING e.user_id, e.sync_version),
				raised AS (
				    UPDATE user_dictionary_sync_state s
				    SET tombstone_floor = GREATEST(s.tombstone_floor, m.highest), updated_at = NOW()
				    FROM (SELECT user_id, max(sync_version) AS highest FROM removed GROUP BY user_id) m
				    WHERE s.user_id = m.user_id
				    RETURNING s.user_id)
				SELECT count(*) FROM removed
				""").param("cutoff", Timestamp.from(cutoff)).param("limit", limit).query(Integer.class).single();
	}
}
