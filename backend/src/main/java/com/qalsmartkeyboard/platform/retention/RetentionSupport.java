package com.qalsmartkeyboard.platform.retention;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL helpers shared by {@link RetentionParticipant}s. */
public final class RetentionSupport {

	private RetentionSupport() {
	}

	/**
	 * Lets the current transaction delete rows of an append-only ledger ({@code fn_forbid_update_delete}, SD 20.1): sets
	 * {@code app.retention_purge = 'on'} locally, so it ends with the transaction. Call it only inside a retention batch.
	 */
	public static void allowAppendOnlyPurge(JdbcClient jdbc) {
		jdbc.sql("SELECT set_config('app.retention_purge', 'on', true)").query().singleRow();
	}

	/**
	 * Deletes up to {@code limit} rows of {@code table} that match {@code condition}, locking them first with
	 * {@code FOR UPDATE SKIP LOCKED}. {@code table}, {@code keyColumn} and {@code condition} are source constants, never
	 * input; the condition may use {@code :cutoff} and {@code :now}.
	 */
	public static int deleteBatch(JdbcClient jdbc, String table, String keyColumn, String condition, Instant now,
			Instant cutoff, int limit) {
		return jdbc.sql("DELETE FROM " + table + " WHERE " + keyColumn + " IN (SELECT " + keyColumn + " FROM " + table
				+ " WHERE " + condition + " LIMIT :limit FOR UPDATE SKIP LOCKED)")
				.param("cutoff", Timestamp.from(cutoff)).param("now", Timestamp.from(now)).param("limit", limit)
				.update();
	}

	/** Like {@link #deleteBatch} for a table without a single-column key: rows are addressed by {@code ctid}. */
	public static int deleteBatchByCtid(JdbcClient jdbc, String table, String condition, Instant now, Instant cutoff,
			int limit) {
		return jdbc.sql("DELETE FROM " + table + " WHERE ctid = ANY (ARRAY(SELECT ctid FROM " + table + " WHERE "
				+ condition + " LIMIT :limit FOR UPDATE SKIP LOCKED))").param("cutoff", Timestamp.from(cutoff))
				.param("now", Timestamp.from(now)).param("limit", limit).update();
	}
}
