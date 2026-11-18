package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Port to the user's AI credits (SD 15.4), used by the metering engine when the plan allowance is used up. Plan step
 * B4.3 implements it on {@code ai_credit_accounts} / {@code ai_credit_lots} (FIFO by expiry) /
 * {@code ai_credit_transactions}; until then the application uses a wallet that is always empty (ADR 0011).
 *
 * <p><b>Contract for implementations</b>
 * <ul>
 * <li>Every method joins the caller's database transaction (the engine's): a rollback of the reservation must undo the
 * wallet change too. Implementations therefore write to PostgreSQL through the application's {@code DataSource}.</li>
 * <li>{@link #hold} is atomic and all-or-nothing, and never lets the balance go below zero under concurrency (lock the
 * account row or use a conditional update).</li>
 * <li>The engine calls the methods under the reservation's row lock, at most once per state transition: {@code hold}
 * on reserve, possibly {@code hold} again on an over-settle, then exactly one {@code settle} ({@code charged} 0 for a
 * release). So the reservation id plus the call identifies each movement (ledger idempotency keys).</li>
 * <li>Amounts are credits with 6 decimal places ({@code NUMERIC(18,6)}); {@code hold} amounts are positive.</li>
 * </ul>
 */
public interface CreditWallet {

	/** The spendable balance now (exact, 6 decimal places; never negative). */
	BigDecimal balance(UUID userId);

	/**
	 * Takes {@code amount} from the balance for the reservation, entirely or not at all.
	 *
	 * @return false when the balance does not cover it (nothing changed)
	 */
	boolean hold(UUID userId, UUID reservationId, BigDecimal amount);

	/**
	 * Closes what the reservation holds: {@code charged} (at most {@code held}) is consumed for good, the rest goes back
	 * to the balance.
	 *
	 * @param held    everything held for the reservation (the sum of its holds)
	 * @param charged the part consumed; 0 when the reservation is released
	 */
	void settle(UUID userId, UUID reservationId, BigDecimal held, BigDecimal charged);
}
