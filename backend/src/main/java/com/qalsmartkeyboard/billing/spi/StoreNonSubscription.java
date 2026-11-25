package com.qalsmartkeyboard.billing.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * One non-subscription (one-time, e.g. a consumable credit pack) store transaction of a customer as the provider reports
 * it server to server (RevenueCat {@code subscriber.non_subscriptions.<product>[]}), as neutral data. A refunded
 * transaction is either reported with {@link #refundedAt} or no longer listed at all (both are handled by the core).
 *
 * @param productId          the store product id (RevenueCat {@code non_subscriptions} key, e.g. {@code credits_500})
 * @param providerId         the provider's own id of the transaction (RevenueCat {@code id}), if any
 * @param storeTransactionId the store's transaction id (Play order id {@code GPA.xxxx}; RevenueCat
 *                           {@code store_transaction_id}); the core's idempotency key, a transaction without it is never
 *                           granted
 * @param store              the store, lower case ({@code play_store}, {@code app_store}, ...)
 * @param environment        SANDBOX for test purchases, PRODUCTION otherwise
 * @param purchasedAt        when the store charged it
 * @param refundedAt         when the store refunded it, null otherwise (or when the provider does not say)
 */
public record StoreNonSubscription(String productId, String providerId, String storeTransactionId, String store,
		PaymentEnvironment environment, Instant purchasedAt, Instant refundedAt) {

	public StoreNonSubscription {
		Objects.requireNonNull(productId, "productId");
		Objects.requireNonNull(environment, "environment");
	}
}
