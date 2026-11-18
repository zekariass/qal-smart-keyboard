package com.qalsmartkeyboard.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Port through which the account deletion executor (privacy module, SD 19.4, plan B6.2, ADR 0015) purges a deleted
 * account's rows from every module that owns per-user tables. Each module implements it for its own tables (the
 * executor has no access to them; the modules depend on identity, never the other way round). Implementations are
 * Spring beans; the executor calls them in ascending {@link #deletionOrder()}.
 *
 * <p>Contract for implementations:
 * <ul>
 * <li>Run in the caller's transaction; never open a new one, so the purge step commits or rolls back as a whole.</li>
 * <li>When this runs, the account is closed ({@code users.deleted_at} set, every token refused, every session revoked)
 * and its data keys are already <b>crypto-shredded</b>: every ciphertext of the user is unreadable and nothing new can
 * be encrypted for it. Never try to decrypt; delete the rows.</li>
 * <li><b>Idempotent</b>: the step is retried after a crash or an error (and may run again after a partial earlier
 * attempt that rolled back), so a second call finds nothing left to do and must not fail.</li>
 * <li>Delete personal rows. Append-only tables (trigger-protected ledgers and {@code user_consents}) and financial
 * records under {@code ON DELETE RESTRICT} are kept: they stay attached to the anonymised {@code users} row, whose
 * random id no longer leads to a person (pseudonymised, SD 19.4 step 3). Document the rule of every table in ADR
 * 0015.</li>
 * <li>Log ids and counts only.</li>
 * </ul>
 * A module that adds a per-user table must extend its participant (or add one) in the same change.
 */
public interface UserDeletionParticipant {

	/** Values of {@link #deletionOrder()} (lower first). */
	int DEVICES = 100;
	int PREFERENCES = 200;
	int PRIVACY = 300;
	int LANGUAGES = 400;
	int USAGE = 500;
	int GLOSSARIES = 600;
	int ENTITLEMENTS = 700;
	/** Tables whose owning module has no code yet (privacy holds them until it does). */
	int UNOWNED = 800;
	int IDENTITY = 900;

	/** Position of this participant: one of the constants above. */
	int deletionOrder();

	/**
	 * Purges (or detaches) the user's rows of this module's tables.
	 *
	 * @param userId the account being deleted (closed and shredded)
	 * @param now    the purge time (one value for every participant)
	 * @return the number of rows deleted or changed (for logs and the event; 0 on a repeat)
	 */
	int purgeUser(UUID userId, Instant now);
}
