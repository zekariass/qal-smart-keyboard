package com.qalsmartkeyboard.entitlements;

import java.time.Instant;
import java.util.UUID;

/**
 * The plan that applies to a user at an instant (contract {@code Entitlements.plan}).
 *
 * @param subscriptionId the {@code user_subscriptions} row for SUBSCRIPTION, STORE_PURCHASE and PARTNER, else null
 * @param organizationId the organisation for ORGANIZATION_SEAT, else null
 * @param effectiveUntil end of the winning source's validity (period end, or grace end while in grace); null when it has
 *                       no end (DEFAULT, open-ended grants)
 * @param inGracePeriod  the winning source is in its grace period (SD 16.5)
 */
public record EffectivePlan(UUID planId, String planCode, String displayName, int planRank, PlanSource source,
		UUID subscriptionId, UUID organizationId, Instant effectiveUntil, boolean inGracePeriod) {
}
