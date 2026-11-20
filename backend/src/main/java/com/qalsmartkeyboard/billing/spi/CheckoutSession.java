package com.qalsmartkeyboard.billing.spi;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * A started hosted checkout.
 *
 * @param providerReference the provider's id for it (ArifPay {@code sessionId}); stored as
 *                          {@code payment_attempts.provider_payment_reference} and used for verification
 * @param checkoutUrl       the page the app opens
 * @param expiresAt         provider-side expiry if known, else null
 */
public record CheckoutSession(String providerReference, URI checkoutUrl, Instant expiresAt) {

	public CheckoutSession {
		Objects.requireNonNull(providerReference, "providerReference");
		Objects.requireNonNull(checkoutUrl, "checkoutUrl");
	}
}
