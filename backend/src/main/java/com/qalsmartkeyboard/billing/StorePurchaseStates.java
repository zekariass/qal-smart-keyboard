package com.qalsmartkeyboard.billing;

import java.time.Duration;
import java.time.Instant;

import com.qalsmartkeyboard.billing.spi.StoreSubscription;

/**
 * Store purchase state from what the store provider reports server to server (ADR 0046), provider-agnostic. Only
 * {@code ACTIVE} and {@code IN_GRACE_PERIOD} grant (through the STORE_MANAGED subscription row); everything else revokes.
 *
 * <ol>
 * <li>{@code refundedAt} set: REFUNDED.</li>
 * <li>No expiry: ACTIVE (a non-expiring entitlement).</li>
 * <li>Period not over: ACTIVE (also when auto-renew is off: the user paid until the end).</li>
 * <li>Period over, inside the store's billing grace period: IN_GRACE_PERIOD.</li>
 * <li>Period over, a resume date set: PAUSED (Play pause).</li>
 * <li>Period over less than {@code renewalPending} ago and still set to renew (no cancellation, no billing issue): ACTIVE.
 * The store reports a renewal minutes to hours after the period end (RevenueCat's Play renewal gap); the entitlement
 * resolver's renewal lag covers the same window.</li>
 * <li>Period over with a billing issue, less than {@link #ACCOUNT_HOLD} ago: ON_HOLD (Play account hold, recoverable).</li>
 * <li>Otherwise EXPIRED.</li>
 * </ol>
 */
final class StorePurchaseStates {

	/** The longest Play account hold; after it a billing issue is just an expiry. */
	static final Duration ACCOUNT_HOLD = Duration.ofDays(60);

	private StorePurchaseStates() {
	}

	/** {@code store_purchases.purchase_status}. */
	static String purchaseStatus(StoreSubscription s, Instant now, Duration renewalPending) {
		if (s.refundedAt() != null) {
			return "REFUNDED";
		}
		Instant end = s.expiresAt();
		if (end == null || now.isBefore(end)) {
			return "ACTIVE";
		}
		if (s.gracePeriodExpiresAt() != null && now.isBefore(s.gracePeriodExpiresAt())) {
			return "IN_GRACE_PERIOD";
		}
		if (s.autoResumeAt() != null) {
			return "PAUSED";
		}
		boolean renewing = s.unsubscribeDetectedAt() == null && s.billingIssuesDetectedAt() == null;
		if (renewing && now.isBefore(end.plus(renewalPending))) {
			return "ACTIVE";
		}
		if (s.billingIssuesDetectedAt() != null && now.isBefore(end.plus(ACCOUNT_HOLD))) {
			return "ON_HOLD";
		}
		return "EXPIRED";
	}

	/** Whether the store will charge again. */
	static boolean autoRenewing(StoreSubscription s, String purchaseStatus) {
		return s.unsubscribeDetectedAt() == null && s.refundedAt() == null && s.expiresAt() != null
				&& ("ACTIVE".equals(purchaseStatus) || "IN_GRACE_PERIOD".equals(purchaseStatus)
						|| "PAUSED".equals(purchaseStatus) || "ON_HOLD".equals(purchaseStatus));
	}

	/** {@code user_subscriptions.subscription_status} for a purchase status. */
	static String subscriptionStatus(String purchaseStatus) {
		return switch (purchaseStatus) {
			case "ACTIVE" -> "ACTIVE";
			case "IN_GRACE_PERIOD" -> "GRACE_PERIOD";
			case "ON_HOLD" -> "PAST_DUE";
			case "PAUSED" -> "PAUSED";
			case "CANCELLED" -> "CANCELLED";
			case "PENDING" -> "PENDING";
			case "REFUNDED", "REVOKED" -> "REVOKED";
			default -> "EXPIRED";
		};
	}

	/** {@code subscription_events.event_type} for a change, or null when nothing worth an event changed. */
	static String subscriptionEvent(String previousStatus, String newStatus, String purchaseStatus,
			boolean periodExtended, Boolean previousAutoRenew, boolean autoRenew) {
		if (previousStatus == null) {
			return "ACTIVE".equals(newStatus) || "GRACE_PERIOD".equals(newStatus) ? "ACTIVATED" : "CREATED";
		}
		if (!previousStatus.equals(newStatus)) {
			return switch (newStatus) {
				case "ACTIVE" -> "PAUSED".equals(previousStatus) ? "RESUMED" : "RENEWED";
				case "GRACE_PERIOD" -> "GRACE_STARTED";
				case "PAST_DUE" -> "PAST_DUE";
				case "PAUSED" -> "PAUSED";
				case "CANCELLED" -> "CANCELLED";
				case "REVOKED" -> "REFUNDED".equals(purchaseStatus) ? "REFUNDED" : "REVOKED";
				default -> "EXPIRED";
			};
		}
		if (periodExtended) {
			return "RENEWED";
		}
		if (previousAutoRenew != null && previousAutoRenew != autoRenew) {
			return autoRenew ? "AUTO_RENEW_ENABLED" : "AUTO_RENEW_DISABLED";
		}
		return null;
	}
}
