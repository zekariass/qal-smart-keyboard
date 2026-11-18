package com.qalsmartkeyboard.usage;

import java.util.UUID;

/**
 * The metering engine (SD 15.3, plan step B4.2, ADR 0011): reserve before calling a provider, settle with the actual
 * usage, release on failure. Every transition appends to {@code usage_events} and updates the window's
 * {@code usage_counters} row in the same transaction, so concurrent requests can never spend more than the plan
 * allowance plus the user's credits.
 *
 * <p><b>Transactions.</b> Each call is one short transaction (it joins the caller's if there is one). Never call
 * {@link #reserve} inside a transaction that stays open across the provider call: the counter row lock would be held
 * for the whole call.
 *
 * <p><b>Typical use</b> (AI gateway, B5.3):
 *
 * <pre>{@code
 * Reservation r = metering.reserve(user, "TRANSLATE", 1, idempotencyKey); // QuotaExceededException -> 429
 * if (r.replayed()) { ... B5.4: RESERVED -> REQUEST_IN_PROGRESS; SETTLED -> replay or recompute without charge }
 * try {
 *     result = provider.call(...);
 *     Settlement s = metering.settle(r.id(), 1);                        // UsageSummary
 * } catch (RuntimeException e) {
 *     metering.release(r.id(), ReleaseReason.FAILED);
 *     throw problem;
 * }
 * }</pre>
 */
public interface Metering {

	/**
	 * Holds {@code estimatedUnits} of a metered feature for the user: first against the plan allowance of the current
	 * window, the remainder against AI credits when the feature allows them (SD 15.3, 15.4). All or nothing.
	 *
	 * <p><b>Idempotency</b> ({@code Idempotency-Key}, at-most-once charging): a key already used by the user returns
	 * the original reservation with {@code replayed = true} and its current status, whatever happened since; nothing
	 * new is held. The same key with another feature or another estimate is {@code IDEMPOTENCY_KEY_REUSED} (409). A
	 * reserve that failed (for example QUOTA_EXCEEDED) stored nothing, so its key can be retried.
	 *
	 * @param estimatedUnits units in the feature's unit (REQUEST: 1 per text request; SECOND; CHARACTER), at least 1
	 * @param idempotencyKey the client's key (contract: a UUID), at most 128 characters
	 * @throws QuotaExceededException                     allowance and credits cannot cover the estimate (429
	 *                                                    {@code QUOTA_EXCEEDED} with featureCode, resetAt,
	 *                                                    creditBalance, upgradeOptions)
	 * @throws com.qalsmartkeyboard.platform.ApiException {@code FEATURE_NOT_ENTITLED} (403) when the plan does not give
	 *                                                    access; {@code IDEMPOTENCY_KEY_REUSED} (409)
	 * @throws IllegalArgumentException                   unknown or unmetered feature (typing, DETECT_LANGUAGE, boolean
	 *                                                    features), bad arguments
	 */
	Reservation reserve(UUID userId, String featureCode, long estimatedUnits, String idempotencyKey);

	/**
	 * Charges the actual usage and closes the reservation. Units up to the reservation are taken from what it holds,
	 * allowance first, so unused credits go back first. Units above the reservation (over-settle, for example audio
	 * longer than estimated) are charged to the remaining allowance of the window, then to credits; whatever neither
	 * covers is recorded as {@code uncoveredUnits} and not charged: allowances and credits are never overdrawn.
	 * {@code actualUnits == 0} releases with {@link ReleaseReason#NOTHING_USED}.
	 *
	 * <p>Idempotent: settling again with the same units returns the original settlement ({@code replayed}).
	 *
	 * @throws ReservationClosedException       already RELEASED (for example by the sweeper), or SETTLED with other
	 *                                          units
	 * @throws java.util.NoSuchElementException unknown reservation
	 */
	Settlement settle(UUID reservationId, long actualUnits);

	/**
	 * Gives everything the reservation holds back (SD 11.1: on failure usage is released). Idempotent: an already
	 * released reservation returns the original release; a settled one is left alone (status SETTLED,
	 * {@code replayed}).
	 *
	 * @throws java.util.NoSuchElementException unknown reservation
	 */
	Release release(UUID reservationId, ReleaseReason reason);
}
