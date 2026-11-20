package com.qalsmartkeyboard.billing.spi;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * Start a hosted checkout for one payment attempt.
 *
 * @param merchantReference  our unique reference for this attempt (ArifPay {@code nonce}); also the idempotency key at
 *                           the provider: a second call with the same reference must not create a second payment
 * @param orderReference     the human-readable order reference ({@code payment_orders.order_reference})
 * @param amount             server-priced total to charge
 * @param itemName           short product label shown on the provider page (never user text)
 * @param methodCode         our public method code
 * @param providerMethodCode the provider's code for it, or null for "let the payer choose on the hosted page"
 * @param successUrl         where the provider sends the payer back (UX only; never proof of payment)
 * @param webhookUrl         our notification endpoint for this account, without any adapter-specific token (the adapter
 *                           adds its own authentication to it when the provider does not sign callbacks)
 * @param expiresAt          when the checkout should stop accepting payment
 * @param locale             payer UI locale (BCP 47), may be null
 */
public record CheckoutRequest(MerchantAccount account, String merchantReference, String orderReference, Money amount,
		String itemName, String methodCode, String providerMethodCode, URI successUrl, URI cancelUrl, URI errorUrl,
		URI webhookUrl, Instant expiresAt, String locale) {

	public CheckoutRequest {
		Objects.requireNonNull(account, "account");
		Objects.requireNonNull(merchantReference, "merchantReference");
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(webhookUrl, "webhookUrl");
	}
}
