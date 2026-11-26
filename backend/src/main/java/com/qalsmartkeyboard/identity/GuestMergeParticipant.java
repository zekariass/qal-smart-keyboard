package com.qalsmartkeyboard.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Port through which identity moves a guest's data into an existing registered account when the guest signs in (SD
 * 9.1, plan B2.6, ADR 0010). Each module that owns per-user tables implements it for its own tables (identity has no
 * access to them, and the modules depend on identity, never the other way round). Implementations are Spring beans;
 * identity calls them in ascending {@link #mergeOrder()}.
 *
 * <p>Contract for implementations:
 * <ul>
 * <li>Run in the caller's transaction (the sign-in transaction); never open a new one for the moved rows, so the merge
 * commits or rolls back as a whole. The guest's {@code users} row is locked by the caller and is still
 * {@code GUEST}/{@code ACTIVE}; it becomes {@code MERGED} after every participant has run.</li>
 * <li>Move rows by changing their {@code user_id}; keep row ids so clients' references stay valid. Resolve conflicts
 * with the target's rows by the rule documented on the implementation (normally: the target's row wins).</li>
 * <li><b>Ciphertext must be re-encrypted</b> ({@code UserDataEncryption.reencrypt}, guest → target, same row context):
 * it is bound to the user id, so copied bytes would not open. The caller crypto-shreds the guest's keys afterwards, so
 * anything left sealed for the guest becomes unreadable.</li>
 * <li>Delete the guest's leftover rows (conflict losers) where the table allows it; append-only tables keep them.</li>
 * <li>Log ids and counts only.</li>
 * </ul>
 */
public interface GuestMergeParticipant {

	/** Values of {@link #mergeOrder()} (lower first). */
	int DEVICES = 100;
	int PREFERENCES = 200;
	int PRIVACY = 300;
	/** Runs after {@link #PRIVACY} so it sees the consent decisions that were just merged (ADR 0050). */
	int PERSONALIZATION = 350;
	int LANGUAGES = 400;
	int USAGE = 500;
	int GLOSSARIES = 600;

	/** Position of this participant: one of the constants above. */
	int mergeOrder();

	/**
	 * Moves the guest's rows of this module's tables to the target account.
	 *
	 * @param guestUserId  the {@code GUEST} account being merged (locked by the caller)
	 * @param targetUserId the existing {@code REGISTERED} account that receives the data
	 * @param now          the merge time (one value for every participant)
	 */
	void mergeGuest(UUID guestUserId, UUID targetUserId, Instant now);
}
