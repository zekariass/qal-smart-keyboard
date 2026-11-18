package com.qalsmartkeyboard.privacy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.AccountDeletion;
import com.qalsmartkeyboard.identity.UserDeletionParticipant;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Carries out due account deletions (SD 19.4, plan B6.2, ADR 0015). A request whose grace period has ended goes
 * through three steps, <b>each its own transaction</b> that also records the step on the request row, so a crash
 * between steps resumes at the next one and never repeats a finished one:
 * <ol>
 * <li><b>SHREDDED</b> ({@code SCHEDULED} → {@code PROCESSING}, {@code shredded_at}): close the account
 * ({@code users.deleted_at}: from this commit every token of the account is refused and sign-in no longer finds it),
 * revoke every session, and crypto-shred the user's data keys ({@link UserDataEncryption#cryptoShred}), which makes
 * every encrypted column of the user unreadable at once, in every table and every backup that holds only the wrapped
 * key.</li>
 * <li><b>PURGED</b> ({@code purged_at}): every {@link UserDeletionParticipant} deletes or detaches its module's rows,
 * in ascending order, keeping append-only and financial records attached to the account id.</li>
 * <li><b>DONE</b> ({@code COMPLETED}, {@code completed_at}): anonymise the {@code users} row and mark it
 * {@code DELETED}, publish {@value #DELETED_EVENT}.</li>
 * </ol>
 * <b>Concurrency:</b> every step locks its request row with {@code FOR UPDATE SKIP LOCKED} and re-reads its state
 * under the lock, so two executors (instances, or a scheduler run and a manual one) never work on one request at the
 * same time and never repeat a step; the other one skips the request. Requests are independent, so instances share
 * the work. <b>Failures:</b> a failing step rolls back completely; the request records the failure (exception class
 * only) and is retried after an exponential backoff, forever (a deletion is never abandoned; alert on
 * {@code qal.privacy.account_deletion{outcome=failed}}).
 */
class DeletionExecutor {

	private static final Logger log = LoggerFactory.getLogger(DeletionExecutor.class);

	static final String DELETED_EVENT = "privacy.account.deleted";

	/** What one {@link #step} did. */
	enum Step {
		/** Step 1 committed. */
		SHREDDED,
		/** Step 2 committed. */
		PURGED,
		/** Step 3 committed: the request is {@code COMPLETED}. */
		DONE,
		/** Nothing to do now: locked by another executor, not due, waiting for a retry, cancelled or finished. */
		SKIPPED,
		/** The step failed and rolled back; the failure was recorded for a later retry. */
		FAILED
	}

	private record Request(UUID id, UUID userId, String status, Instant scheduledPurgeAt, Instant shreddedAt,
			Instant purgedAt, Instant nextAttemptAt, Instant requestedAt) {
	}

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;
	private final AccountDeletion accounts;
	private final UserDataEncryption encryption;
	private final ObjectProvider<UserDeletionParticipant> participants;
	private final OutboxPublisher outbox;
	private final DeletionProperties properties;
	private final Clock clock;
	private final MeterRegistry meters;

	DeletionExecutor(JdbcClient jdbc, TransactionTemplate tx, AccountDeletion accounts, UserDataEncryption encryption,
			ObjectProvider<UserDeletionParticipant> participants, OutboxPublisher outbox, DeletionProperties properties,
			Clock clock, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.accounts = accounts;
		this.encryption = encryption;
		this.participants = participants;
		this.outbox = outbox;
		this.properties = properties;
		this.clock = clock;
		this.meters = meters;
	}

	/** One scheduler run at the current time. @return the number of requests completed */
	int runOnce() {
		return runOnce(clock.instant());
	}

	/**
	 * Works through the requests due at {@code now}: batches of {@code batch-size}, continuing while a full batch was
	 * completed entirely (a batch with skipped or failed requests ends the run; the next run picks them up).
	 *
	 * @return the number of requests completed
	 */
	int runOnce(Instant now) {
		int completed = 0;
		while (true) {
			List<UUID> due = jdbc.sql("""
					SELECT id FROM user_deletion_requests
					WHERE request_status IN ('SCHEDULED', 'PROCESSING') AND scheduled_purge_at <= :now
					  AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
					ORDER BY scheduled_purge_at, id
					LIMIT :limit
					""").param("now", Timestamp.from(now)).param("limit", properties.batchSize()).query(UUID.class)
					.list();
			int batchCompleted = 0;
			for (UUID id : due) {
				if (process(id, now)) {
					batchCompleted++;
				}
			}
			completed += batchCompleted;
			if (due.size() < properties.batchSize() || batchCompleted < due.size()) {
				return completed;
			}
		}
	}

	/**
	 * Runs the remaining steps of one request. @return whether it is now {@code COMPLETED} by this call
	 */
	boolean process(UUID requestId, Instant now) {
		for (int i = 0; i < 3; i++) {
			Step step = step(requestId, now);
			if (step == Step.DONE) {
				return true;
			}
			if (step == Step.SKIPPED || step == Step.FAILED) {
				return false;
			}
		}
		return false;
	}

	/** Runs the next step of one request in its own transaction (see the class comment). */
	Step step(UUID requestId, Instant now) {
		String[] attempted = { "lock" };
		try {
			return tx.execute(status -> {
				Request r = lock(requestId);
				if (r == null || !("SCHEDULED".equals(r.status()) || "PROCESSING".equals(r.status()))
						|| r.scheduledPurgeAt().isAfter(now)
						|| (r.nextAttemptAt() != null && r.nextAttemptAt().isAfter(now))) {
					return Step.SKIPPED;
				}
				if (r.shreddedAt() == null) {
					attempted[0] = "shred";
					return shred(r, now);
				}
				if (r.purgedAt() == null) {
					attempted[0] = "purge";
					return purge(r, now);
				}
				attempted[0] = "finish";
				return finish(r, now);
			});
		}
		catch (RuntimeException e) {
			recordFailure(requestId, attempted[0], now, e);
			return Step.FAILED;
		}
	}

	private Step shred(Request r, Instant now) {
		int sessions = accounts.closeAccount(r.userId(), now);
		int keys = encryption.cryptoShred(r.userId());
		markStep("request_status = 'PROCESSING', shredded_at = :now", r.id(), now);
		log.info("Account deletion {} (user {}): account closed, {} session row(s) revoked, {} data key(s) shredded",
				r.id(), r.userId(), sessions, keys);
		return Step.SHREDDED;
	}

	private Step purge(Request r, Instant now) {
		List<UserDeletionParticipant> ordered = participants.stream()
				.sorted(Comparator.comparingInt(UserDeletionParticipant::deletionOrder)).toList();
		int rows = 0;
		for (UserDeletionParticipant participant : ordered) {
			rows += participant.purgeUser(r.userId(), now);
		}
		markStep("purged_at = :now", r.id(), now);
		log.info("Account deletion {} (user {}): {} participant(s) purged {} row(s)", r.id(), r.userId(),
				ordered.size(), rows);
		return Step.PURGED;
	}

	private Step finish(Request r, Instant now) {
		accounts.anonymiseAccount(r.userId(), now);
		markStep("request_status = 'COMPLETED', completed_at = :now", r.id(), now);
		outbox.publish("user", r.userId().toString(), DELETED_EVENT, Map.of("requestId", r.id().toString(), "userId",
				r.userId().toString(), "requestedAt", r.requestedAt().toString(), "completedAt", now.toString()));
		count("completed");
		log.info("Account deletion {} (user {}) completed", r.id(), r.userId());
		return Step.DONE;
	}

	/** Records a finished step: resets the retry state of the request. */
	private void markStep(String assignments, UUID requestId, Instant now) {
		jdbc.sql("UPDATE user_deletion_requests SET " + assignments
				+ ", attempts = 0, next_attempt_at = NULL, processing_error = NULL, updated_at = :now WHERE id = :id")
				.param("now", Timestamp.from(now)).param("id", requestId).update();
	}

	private Request lock(UUID requestId) {
		return jdbc.sql("""
				SELECT id, user_id, request_status, requested_at, scheduled_purge_at, shredded_at, purged_at,
				       next_attempt_at
				FROM user_deletion_requests WHERE id = :id
				FOR UPDATE SKIP LOCKED
				""").param("id", requestId)
				.query((rs, n) -> new Request(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
						rs.getString("request_status"), instant(rs.getTimestamp("scheduled_purge_at")),
						instant(rs.getTimestamp("shredded_at")), instant(rs.getTimestamp("purged_at")),
						instant(rs.getTimestamp("next_attempt_at")), instant(rs.getTimestamp("requested_at"))))
				.optional().orElse(null);
	}

	/**
	 * After a failed step (rolled back): one more attempt, retry no earlier than the backoff. Only the exception class
	 * is recorded and logged; messages can carry SQL values (a constraint violation names the conflicting key).
	 */
	private void recordFailure(UUID requestId, String step, Instant now, RuntimeException e) {
		String error = (step + ": " + e.getClass().getName());
		String recorded = error.length() > 500 ? error.substring(0, 500) : error;
		Integer attempts = null;
		try {
			attempts = tx.execute(status -> {
				Integer previous = jdbc.sql("""
						SELECT attempts FROM user_deletion_requests
						WHERE id = :id AND request_status IN ('SCHEDULED', 'PROCESSING') FOR UPDATE
						""").param("id", requestId).query(Integer.class).optional().orElse(null);
				if (previous == null) {
					return null;
				}
				int next = previous + 1;
				jdbc.sql("""
						UPDATE user_deletion_requests SET attempts = :attempts, next_attempt_at = :retryAt,
						       processing_error = :error, updated_at = :now
						WHERE id = :id
						""").param("attempts", next).param("retryAt", Timestamp.from(now.plus(properties.backoff(next))))
						.param("error", recorded).param("now", Timestamp.from(now)).param("id", requestId).update();
				return next;
			});
		}
		catch (RuntimeException recordingFailed) {
			log.error("Account deletion {}: the failure could not be recorded ({})", requestId,
					recordingFailed.getClass().getName());
		}
		count("failed");
		log.error("Account deletion {} failed at step {} (attempt {}): {}; it will be retried", requestId, step,
				attempts, e.getClass().getName());
	}

	private void count(String outcome) {
		if (meters != null) {
			meters.counter("qal.privacy.account_deletion", "outcome", outcome).increment();
		}
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}
}
