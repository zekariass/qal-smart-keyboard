package com.qalsmartkeyboard.billing.spi;

import java.util.Map;
import java.util.Objects;

/**
 * A parsed webhook. Only a hint: processing always verifies with {@link PaymentProvider#verify} first.
 *
 * @param externalEventId   the provider's event id, or null when it sends none (the core then uses sha256 of the body)
 * @param eventType         short type for {@code payment_webhook_events.event_type} (max 120)
 * @param providerReference the checkout id the event is about, if present
 * @param merchantReference our reference (ArifPay {@code nonce}), if present
 * @param reportedStatus    what the event claims (never acted on without verification)
 * @param redactedPayload   what may be stored: ids, statuses and amounts only (no phone numbers, names or card data)
 */
public record WebhookEvent(String externalEventId, String eventType, String providerReference,
		String merchantReference, ProviderPaymentStatus reportedStatus, Map<String, Object> redactedPayload) {

	public WebhookEvent {
		Objects.requireNonNull(eventType, "eventType");
		Objects.requireNonNull(reportedStatus, "reportedStatus");
		redactedPayload = redactedPayload == null ? Map.of() : redactedPayload;
	}
}
