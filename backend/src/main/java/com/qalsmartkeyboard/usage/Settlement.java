package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.qalsmartkeyboard.entitlements.MeteringUnit;

/**
 * The outcome of {@link Metering#settle}. Maps onto the contract's {@code UsageSummary} ({@code unitsCharged} =
 * {@link #chargedUnits}, {@code usageSource}, {@code remaining}, {@code creditBalance}, {@code resetsAt}) and onto
 * {@code ai_requests} ({@code units}, {@code usage_source}, {@code credits_charged}).
 *
 * @param actualUnits        what the caller measured (requests, seconds, characters)
 * @param chargedUnits       {@code allowanceUnits + creditUnits}: at most {@code actualUnits}
 * @param allowanceUnits     charged to the plan allowance
 * @param creditUnits        paid with credits
 * @param creditsCharged     {@code creditUnits x creditCostPerUnit} (6 dp)
 * @param uncoveredUnits     over-settle units neither the allowance nor credits could pay: not charged (ADR 0011)
 * @param status             SETTLED, or RELEASED when {@code actualUnits} was 0 ({@link ReleaseReason#NOTHING_USED})
 * @param remainingAllowance allowance left in the window now; null when unlimited
 * @param resetAt            end of the window; null for the lifetime window
 * @param creditBalance      the wallet balance now (exact; display rounds down)
 * @param replayed           true when this reservation had already been settled with the same units: nothing new
 *                           was charged
 */
public record Settlement(UUID reservationId, UUID userId, String featureCode, MeteringUnit unit, long actualUnits,
		long chargedUnits, long allowanceUnits, long creditUnits, BigDecimal creditsCharged, long uncoveredUnits,
		ReservationStatus status, Long remainingAllowance, Instant resetAt, BigDecimal creditBalance,
		boolean replayed) {

	public UsageSource usageSource() {
		return creditUnits > 0 ? UsageSource.AI_CREDITS : UsageSource.PLAN_ALLOWANCE;
	}
}
