package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.qalsmartkeyboard.entitlements.MeteringUnit;

/**
 * A usage reservation ({@link Metering#reserve}).
 *
 * @param id             reservation id: the {@code usage_events} RESERVE row id, and {@code reservation_id} of its
 *                       SETTLE/RELEASE row
 * @param estimatedUnits units held in total ({@code allowanceUnits + creditUnits})
 * @param allowanceUnits units held against the plan allowance of {@code window}
 * @param creditUnits    units paid by AI credits because the allowance did not cover them (SD 15.3 step 3)
 * @param creditsHeld    {@code creditUnits x creditCostPerUnit}, exact to 6 decimal places, held in the wallet
 * @param expiresAt      after this the sweeper may release it (SD 15.3: 15 minutes)
 * @param window         the allowance window charged; {@link UsageWindow#resetAt()} is the reset time
 * @param status         RESERVED when new; a replay ({@code replayed}) returns the current status of the original
 * @param replayed       true when the idempotency key had already been used for this same request: nothing new was
 *                       reserved (at-most-once charging); a RESERVED replay means the original is still in flight
 */
public record Reservation(UUID id, UUID userId, String featureCode, MeteringUnit unit, long estimatedUnits,
		long allowanceUnits, long creditUnits, BigDecimal creditCostPerUnit, BigDecimal creditsHeld, Instant expiresAt,
		UsageWindow window, ReservationStatus status, boolean replayed) {

	/** The source the client sees for this reservation, once settled as estimated. */
	public UsageSource usageSource() {
		return creditUnits > 0 ? UsageSource.AI_CREDITS : UsageSource.PLAN_ALLOWANCE;
	}
}
