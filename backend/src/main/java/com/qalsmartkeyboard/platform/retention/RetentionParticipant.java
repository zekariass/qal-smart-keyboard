package com.qalsmartkeyboard.platform.retention;

import java.time.Instant;
import java.util.List;

/**
 * Port through which the retention job (privacy module, plan B6.3, ADR 0020) purges the data classes of every module.
 * Like {@code UserDeletionParticipant}, each module implements it for its own tables; the job has no access to them.
 * Implementations are Spring beans.
 *
 * <p>Contract for {@link #purge}:
 * <ul>
 * <li>Runs in the caller's transaction (one transaction per batch) and never opens another. The transaction holds a
 * per-rule advisory lock, so two instances never purge the same rule at once; at row level, delete with
 * {@code WHERE key IN (SELECT key ... LIMIT n FOR UPDATE SKIP LOCKED)} (see {@link RetentionSupport}) so rows another
 * transaction holds are left for the next run.</li>
 * <li><b>Bounded</b>: at most about {@code limit} rows per call (a rule that deletes whole groups, for example a
 * session family, may exceed it by the group size).</li>
 * <li><b>Idempotent and resumable</b>: a call only removes rows that are past the cutoff, so a repeat finds nothing,
 * and a run that stopped half-way continues where it left off.</li>
 * <li>Rows of an append-only ledger are deleted after {@link RetentionSupport#allowAppendOnlyPurge}.</li>
 * <li>Never log row content: counts and the rule code only.</li>
 * </ul>
 */
public interface RetentionParticipant {

	/** Position among participants (lower first). */
	int order();

	/** Every data class this module owns, executable or documentary, with its default period. */
	List<RetentionRule> rules();

	/**
	 * Purges one batch of an executable rule.
	 *
	 * @param rule   one of {@link #rules()}, with the effective period
	 * @param now    the run's time (one value for every rule of the run)
	 * @param cutoff {@code now - rule.period()}: data older than this is due
	 * @param limit  the batch size
	 * @return the number of rows deleted or changed; fewer than {@code limit} means the rule is done for this run
	 */
	int purge(RetentionRule rule, Instant now, Instant cutoff, int limit);
}
