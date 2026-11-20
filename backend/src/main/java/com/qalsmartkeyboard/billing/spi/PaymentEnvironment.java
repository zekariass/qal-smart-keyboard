package com.qalsmartkeyboard.billing.spi;

/** {@code payment_provider_accounts.environment}: sandbox and production never mix (SD 16.1 rule 3). */
public enum PaymentEnvironment {
	SANDBOX, PRODUCTION
}
