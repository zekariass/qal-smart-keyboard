package com.qalsmartkeyboard.billing.spi;

/**
 * A store-managed billing integration (Google Play / App Store through RevenueCat; ADR 0045, ADR 0046). Store purchases
 * never use gateway orders (N6): they are verified into {@code store_purchases} and STORE_MANAGED subscription terms.
 * Registering a bean of this type is what makes a store provider's route eligible (SD 16.3 rule 2). Implementations
 * keep their secrets, log ids and codes only and never return or log payloads.
 */
public interface StoreBillingProvider {

	/** {@code payment_providers.provider_code}, e.g. {@code REVENUECAT}. */
	String providerCode();

	/** Whether this adapter instance honours purchases of that environment. */
	boolean supports(PaymentEnvironment environment);

	/** Authenticates a notification (shared header value, signature). */
	WebhookAuthentication authenticateNotification(StoreNotificationRequest request);

	/**
	 * Parses an authenticated notification.
	 *
	 * @throws PaymentProviderException {@code INVALID_RESPONSE} when the body is not a recognisable event
	 */
	StoreNotification parseNotification(StoreNotificationRequest request);

	/**
	 * Reads the customer's store subscriptions and one-time purchases server to server: the only input a grant may come
	 * from.
	 *
	 * @param appUserId our user id as the provider knows it
	 * @throws PaymentProviderException when the provider cannot be reached ({@code UNAVAILABLE}), refuses our
	 *                                  credentials ({@code MISCONFIGURED}) or answers nonsense
	 */
	StoreCustomer customer(String appUserId);
}
