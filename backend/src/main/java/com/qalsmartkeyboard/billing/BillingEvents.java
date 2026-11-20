package com.qalsmartkeyboard.billing;

/**
 * Outbox event types of the billing module. Payloads carry ids only.
 * <ul>
 * <li>{@link #SUBSCRIPTION_CHANGED} {@code {userId, subscriptionId, orderId}}: a user's subscription sources changed;
 * the entitlements module drops its cached resolution (it reads the tables itself, the event carries no plan).</li>
 * <li>{@link #WEBHOOK_RECEIVED} {@code {webhookEventId}}: process a stored gateway webhook (verify with the provider).</li>
 * <li>{@link #VERIFY_REQUESTED} {@code {attemptId, trigger}}: verify one attempt (checkout return URL).</li>
 * <li>{@link #STORE_NOTIFICATION_RECEIVED} {@code {webhookEventId}}: process a stored store notification (re-read the
 * customer from the store provider).</li>
 * </ul>
 */
final class BillingEvents {

	static final String SUBSCRIPTION_CHANGED = "billing.subscription.changed";
	static final String WEBHOOK_RECEIVED = "billing.webhook.received";
	static final String VERIFY_REQUESTED = "billing.attempt.verify-requested";
	static final String STORE_NOTIFICATION_RECEIVED = "billing.store-notification.received";

	private BillingEvents() {
	}
}
