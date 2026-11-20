package com.qalsmartkeyboard.billing.spi;

import java.util.List;

/**
 * One payment gateway (ADR 0045). Registered by {@link #providerCode()}; a {@code payment_providers} row without a
 * registered adapter is never offered (SD 16.3 rule 2). Implementations must:
 * <ul>
 * <li>keep secrets to themselves (from configuration/environment), never log them, never return them;</li>
 * <li>log outcomes, codes and ids only: no request/response bodies, phone numbers, names or card data;</li>
 * <li>use bounded timeouts and no internal retries (the core retries through the outbox and the reconciliation
 * job);</li>
 * <li>be safe to call {@link #verify} any number of times.</li>
 * </ul>
 */
public interface PaymentProvider {

	/** {@code payment_providers.provider_code}, e.g. {@code ARIFPAY}. */
	String providerCode();

	/** Whether this adapter instance is configured for that environment (sandbox key vs production key). */
	boolean supports(PaymentEnvironment environment);

	/**
	 * Starts a hosted checkout.
	 *
	 * @throws PaymentProviderException when the provider refuses or cannot be reached
	 */
	CheckoutSession initiateCheckout(CheckoutRequest request);

	/**
	 * Server-to-server status of one attempt.
	 *
	 * @throws PaymentProviderException when the provider cannot be reached or answers nonsense
	 */
	VerificationResult verify(VerificationRequest request);

	/** Authenticates a webhook (signature, or our notify-URL token for providers that do not sign). */
	WebhookAuthentication authenticate(WebhookRequest request);

	/**
	 * Parses an authenticated webhook.
	 *
	 * @throws PaymentProviderException {@code INVALID_RESPONSE} when the body is not a recognisable event
	 */
	WebhookEvent parseWebhook(WebhookRequest request);

	/** Whether {@link #discoverMethods} is implemented. */
	default boolean supportsMethodDiscovery() {
		return false;
	}

	/** Methods enabled on the merchant account, when the provider can list them. */
	default List<DiscoveredMethod> discoverMethods(MerchantAccount account) {
		return List.of();
	}
}
