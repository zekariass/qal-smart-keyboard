package com.qalsmartkeyboard.billing.spi;

import java.util.Objects;

/**
 * Ask the provider, server to server, what happened to one attempt.
 *
 * @param providerReference the provider's checkout id, or null when the start call's answer was lost (the adapter then
 *                          looks the payment up by {@code merchantReference} if the provider supports it)
 * @param merchantReference our reference sent at checkout start
 * @param expectedAmount    what the order costs; adapters report the provider's amount, the core compares
 */
public record VerificationRequest(MerchantAccount account, String providerReference, String merchantReference,
		Money expectedAmount) {

	public VerificationRequest {
		Objects.requireNonNull(account, "account");
		Objects.requireNonNull(merchantReference, "merchantReference");
		Objects.requireNonNull(expectedAmount, "expectedAmount");
	}
}
