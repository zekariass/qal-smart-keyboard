package com.qalsmartkeyboard.billing.spi;

import java.util.List;
import java.util.Objects;

/**
 * A store-billing customer read server to server (RevenueCat {@code GET /v1/subscribers/{app_user_id}}).
 *
 * @param appUserId     the id the customer was read by (our user id)
 * @param managementUrl where the user manages the subscription in the store, if known
 * @param subscriptions    every subscription the provider knows for the customer, current or past
 * @param nonSubscriptions every one-time (non-subscription) transaction the provider lists for the customer
 */
public record StoreCustomer(String appUserId, String managementUrl, List<StoreSubscription> subscriptions,
		List<StoreNonSubscription> nonSubscriptions) {

	public StoreCustomer {
		Objects.requireNonNull(appUserId, "appUserId");
		subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
		nonSubscriptions = nonSubscriptions == null ? List.of() : List.copyOf(nonSubscriptions);
	}

	/** A customer without one-time purchases. */
	public StoreCustomer(String appUserId, String managementUrl, List<StoreSubscription> subscriptions) {
		this(appUserId, managementUrl, subscriptions, List.of());
	}
}
