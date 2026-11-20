package com.qalsmartkeyboard.billing.spi;

/**
 * A provider status mapped to our vocabulary. Only a {@link #SUCCEEDED} from {@link PaymentProvider#verify} (server to
 * server) can lead to a grant; a webhook's reported status is a hint that triggers verification.
 */
public enum ProviderPaymentStatus {
	/** Paid and confirmed by the provider. */
	SUCCEEDED,
	/** The payer started paying; not final. */
	PENDING,
	/** The session exists but the payer has not started (or the provider has no transaction yet). */
	NOT_STARTED,
	FAILED,
	CANCELLED,
	EXPIRED,
	/** The provider could not tell (auth error, inconsistent data): keep the attempt open and retry later. */
	UNKNOWN;

	public boolean isFinal() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
	}
}
