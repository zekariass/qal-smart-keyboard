package com.qalsmartkeyboard.billing.spi;

import java.util.List;
import java.util.Objects;

/**
 * A store-billing customer read server to server (RevenueCat {@code GET /v1/subscribers/{app_user_id}}).
 *
 * @param appUserId     the id the customer was read by (our user id)
 * @param managementUrl where the user manages the subscription in the store, if known
 * @param subscriptions every subscription the provider knows for the customer, current or past
 */
public record StoreCustomer(String appUserId, String managementUrl, List<StoreSubscription> subscriptions) {

	public StoreCustomer {
		Objects.requireNonNull(appUserId, "appUserId");
		subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
	}
}
