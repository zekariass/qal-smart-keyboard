package com.qalsmartkeyboard.billing.spi;

/**
 * A store-managed billing integration (Google Play / App Store, e.g. through RevenueCat; ADR 0045). Store purchases
 * never use gateway orders (N6): they are verified into {@code store_purchases}. Registering a bean of this type is what
 * makes a store provider's route eligible (SD 16.3 rule 2); step A2 adds the purchase-verification and notification
 * operations to this interface.
 */
public interface StoreBillingProvider {

	/** {@code payment_providers.provider_code}, e.g. {@code REVENUECAT}. */
	String providerCode();

	/** Whether this adapter instance is configured for that environment. */
	boolean supports(PaymentEnvironment environment);
}
