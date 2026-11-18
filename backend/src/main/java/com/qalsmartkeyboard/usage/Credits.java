package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * AI credit lots and their append-only ledger (SD 15.4, B4.3, ADR 0011). This is the API billing (M4) and support tools
 * call to grant, refund and adjust credits; consumption happens through the metering engine's {@link CreditWallet}.
 *
 * <p>Every method here runs in its own transaction unless the caller already has one. Amounts are credits with at most
 * 6 decimal places and must be positive (except {@link #adjust}). A lot is active until {@code expiresAt} (exclusive
 * end: at that instant it no longer counts); a lot without expiry never expires.
 */
public interface Credits {

	/**
	 * Creates a lot of {@code units} credits for the user and appends its opening ledger row (PURCHASE, GRANT, REFUND or
	 * ADJUSTMENT by source). Idempotent by {@code (user, source, sourceRef)}: a replay returns the existing lot with
	 * {@code created == false} and changes nothing; the same reference with other units or expiry is an
	 * {@link IllegalArgumentException}. For {@link CreditSource#PURCHASE} {@code sourceRef} must be the id of the
	 * {@code payment_orders} row (V1).
	 *
	 * @param expiresAt null for credits that never expire (SD 15.4 default validity is the caller's pack setting)
	 */
	CreditLot grant(UUID userId, BigDecimal units, CreditSource source, String sourceRef, Instant expiresAt);

	/**
	 * Credit usable at {@code at}: lots with no expiry or {@code expiresAt > at}. Reads the lots, so a lot that expired
	 * but is not yet swept does not count.
	 */
	CreditBalance balance(UUID userId, Instant at);

	/**
	 * Reverses what is left of a purchase lot when the purchase is refunded (SD 15.4): a REVERSAL row for the whole
	 * remainder, the lot is emptied. Consumed credits are never driven negative. Idempotent by {@code sourceRef}.
	 *
	 * @param sourceRef the reference the lot was granted with
	 * @return the credits taken back (0 when nothing remained or the lot is unknown for the user)
	 */
	BigDecimal reverse(UUID userId, CreditSource source, String sourceRef);

	/**
	 * Corrects a lot by {@code delta} (positive up to the lot's {@code initial_units}, negative down to 0) with an
	 * ADJUSTMENT row. Idempotent by {@code adjustmentRef} per lot.
	 *
	 * @return the credits actually moved (positive or negative); 0 for a replay
	 */
	BigDecimal adjust(UUID userId, UUID lotId, BigDecimal delta, String adjustmentRef, String description);

	/**
	 * Writes EXPIRY rows for every lot past its expiry that still has credits and zeroes it. Idempotent and safe to run
	 * on several instances.
	 *
	 * @return the number of lots expired
	 */
	int expireDue();
}
