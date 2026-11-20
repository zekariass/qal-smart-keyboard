package com.qalsmartkeyboard.billing.spi;

/** A provider refund's state in our vocabulary (ADR 0045 step A2). */
public enum ProviderRefundStatus {
	/** Accepted by the provider, money not returned yet. */
	PENDING,
	/** The provider reports the money returned to the payer. */
	SUCCEEDED,
	/** Refused or reversed by the provider: nothing was returned. */
	FAILED,
	/** The provider could not tell; ask again later. */
	UNKNOWN
}
