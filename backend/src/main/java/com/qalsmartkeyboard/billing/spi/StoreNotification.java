package com.qalsmartkeyboard.billing.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A parsed store notification. Only a hint: processing re-reads the customer with
 * {@link StoreBillingProvider#customer(String)} and applies that state (never the body).
 *
 * @param eventId        the provider's event id (the dedupe key; RevenueCat {@code event.id})
 * @param eventType      short type, e.g. {@code RENEWAL} (max 120)
 * @param userReferences the provider-side user ids the event names, most specific first (RevenueCat
 *                       {@code app_user_id}, {@code original_app_user_id}, {@code aliases}, {@code transferred_to},
 *                       {@code transferred_from}); the core keeps those that are its own user ids
 * @param productId      the store product the event is about, if any (Play {@code subscriptionId:basePlanId})
 * @param environment    sandbox or production purchase, if stated
 * @param eventTime      when the provider generated the event
 * @param redactedEvent  what may be stored: the event without subscriber attributes (no e-mail, phone or name)
 * @param test           a dashboard test event: stored, never applied
 * @param transactionIds the store transaction ids the event names (RevenueCat {@code transaction_id},
 *                       {@code original_transaction_id}); a refund event of a one-time purchase is matched by them
 */
public record StoreNotification(String eventId, String eventType, List<String> userReferences, String productId,
		PaymentEnvironment environment, Instant eventTime, Map<String, Object> redactedEvent, boolean test,
		List<String> transactionIds) {

	public StoreNotification {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(eventType, "eventType");
		userReferences = userReferences == null ? List.of() : List.copyOf(userReferences);
		redactedEvent = redactedEvent == null ? Map.of() : redactedEvent;
		transactionIds = transactionIds == null ? List.of() : List.copyOf(transactionIds);
	}

	/** A notification that names no store transaction. */
	public StoreNotification(String eventId, String eventType, List<String> userReferences, String productId,
			PaymentEnvironment environment, Instant eventTime, Map<String, Object> redactedEvent, boolean test) {
		this(eventId, eventType, userReferences, productId, environment, eventTime, redactedEvent, test, List.of());
	}
}
