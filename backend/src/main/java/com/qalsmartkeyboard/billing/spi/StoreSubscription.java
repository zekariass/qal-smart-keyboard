package com.qalsmartkeyboard.billing.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * One store subscription of a customer as the provider reports it server to server (RevenueCat
 * {@code subscriber.subscriptions.<product>}), as neutral data; the core derives the purchase state from it.
 *
 * @param productId               the store product id without a Play base plan suffix (RevenueCat subscriptions key)
 * @param basePlanId              the Play base plan, if the provider reports it, else null
 * @param store                   the store, lower case ({@code play_store}, {@code app_store}, ...)
 * @param environment             SANDBOX for test purchases, PRODUCTION otherwise
 * @param purchasedAt             start of the latest period
 * @param originalPurchasedAt     the first purchase
 * @param expiresAt               end of the latest period; null = does not expire
 * @param gracePeriodExpiresAt    billing-issue grace end, null outside a grace period
 * @param unsubscribeDetectedAt   when auto-renew was switched off (cancellation), null while renewing
 * @param billingIssuesDetectedAt when a renewal charge failed, null otherwise
 * @param refundedAt              when the store refunded it, null otherwise
 * @param autoResumeAt            when a paused subscription resumes, null otherwise
 * @param storeTransactionId      the store's id of the latest transaction
 * @param periodType              {@code normal}, {@code trial}, {@code intro}, ...
 * @param ownershipType           {@code PURCHASED} or {@code FAMILY_SHARED}
 */
public record StoreSubscription(String productId, String basePlanId, String store, PaymentEnvironment environment,
		Instant purchasedAt, Instant originalPurchasedAt, Instant expiresAt, Instant gracePeriodExpiresAt,
		Instant unsubscribeDetectedAt, Instant billingIssuesDetectedAt, Instant refundedAt, Instant autoResumeAt,
		String storeTransactionId, String periodType, String ownershipType) {

	public StoreSubscription {
		Objects.requireNonNull(productId, "productId");
		Objects.requireNonNull(environment, "environment");
	}
}
