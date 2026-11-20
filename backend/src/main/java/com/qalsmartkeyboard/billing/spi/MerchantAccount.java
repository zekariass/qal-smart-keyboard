package com.qalsmartkeyboard.billing.spi;

import java.util.Objects;

/**
 * The merchant account a call is made for ({@code payment_provider_accounts}); no secrets (adapters read their own).
 *
 * @param accountCode              the route segment of {@code /billing/webhooks/{providerCode}/{accountCode}}
 * @param merchantAccountReference the provider-side merchant id, if any
 */
public record MerchantAccount(String providerCode, String accountCode, String countryCode, String currencyCode,
		PaymentEnvironment environment, String merchantAccountReference) {

	public MerchantAccount {
		Objects.requireNonNull(providerCode, "providerCode");
		Objects.requireNonNull(accountCode, "accountCode");
		Objects.requireNonNull(environment, "environment");
	}
}
