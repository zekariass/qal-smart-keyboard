package com.qalsmartkeyboard.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * The account-row side of account deletion (SD 19.4, plan B6.2, ADR 0015), used by the privacy module, which owns the
 * deletion request and the executor. Every method joins the caller's transaction and locks the {@code users} row
 * ({@code FOR NO KEY UPDATE}, the lock sign-in and guest merge take) before changing it.
 *
 * <p>Lock order everywhere: the deletion request row (privacy) first, then the {@code users} row, then session-family
 * locks. {@link #requestDeletion} is the exception that proves it: it runs before any request row exists.
 */
public interface AccountDeletion {

	/** Outcome of {@link #requestDeletion}. */
	enum RequestOutcome {
		/** The account is now {@code DELETION_PENDING}; the other session families were ended. */
		STARTED,
		/** A deletion is already pending (or running) for the account; nothing changed. */
		ALREADY_PENDING,
		/** The account is {@code SUSPENDED}: deletion goes through support (an investigation may need the data). */
		SUSPENDED,
		/** Unknown, {@code PENDING}, {@code DELETED} or {@code MERGED} account; nothing changed. */
		NOT_ALLOWED
	}

	/**
	 * Marks an {@code ACTIVE} account {@code DELETION_PENDING} and ends every session family except the caller's
	 * ({@code ACCOUNT_DELETED}): the requesting device stays signed in so the person can cancel, other devices are
	 * signed out. Sign-in stays possible during the grace period (to cancel from a new device).
	 *
	 * @param keepSessionFamily the caller's session family ({@code sid})
	 */
	RequestOutcome requestDeletion(UUID userId, UUID keepSessionFamily, Instant now);

	/**
	 * Restores a {@code DELETION_PENDING} account (whose deletion has not started) to {@code ACTIVE}.
	 *
	 * @return whether the row changed
	 */
	boolean cancelDeletion(UUID userId, Instant now);

	/**
	 * Closes the account when the executor starts (step 1, before the crypto-shred): sets {@code deleted_at}, from
	 * which moment every access token of the account is refused, sign-in and refresh fail, and phone and Google
	 * sign-ins no longer find it; then revokes every session family. Idempotent.
	 *
	 * @return the number of session rows revoked
	 * @throws IllegalStateException if the user does not exist or is {@code MERGED}
	 */
	int closeAccount(UUID userId, Instant now);

	/**
	 * The last step: anonymises the {@code users} row (e-mail, phone, display name, market, locale, time zone, age
	 * confirmation and last login cleared) and sets it {@code DELETED}. The row and its random id stay, because
	 * financial records reference it under {@code ON DELETE RESTRICT} and append-only ledgers by id (pseudonymised).
	 * Idempotent.
	 */
	void anonymiseAccount(UUID userId, Instant now);
}
