package com.qalsmartkeyboard.billing.spi;

/** {@code payment_webhook_events.signature_status} of a received webhook. */
public enum WebhookAuthentication {
	/** Signature (or our notify-URL token) checked and correct. */
	VALID,
	/** Present but wrong, or missing where required: 401 {@code WEBHOOK_SIGNATURE_INVALID}, stored as INVALID. */
	INVALID,
	/** The provider offers no authentication at all; the event is only a hint to verify. */
	NOT_APPLICABLE
}
