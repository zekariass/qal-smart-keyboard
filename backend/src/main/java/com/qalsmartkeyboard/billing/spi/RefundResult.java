package com.qalsmartkeyboard.billing.spi;

import java.util.Objects;

/**
 * A provider's answer to a refund call, as data; the core writes the ledger.
 *
 * @param providerRefundReference the provider's id of the refund (for status checks and the REFUND ledger row)
 * @param amount                  the amount the provider says it refunds, or null when it did not say
 * @param failureCode             a short safe code for FAILED / UNKNOWN (never provider prose)
 */
public record RefundResult(ProviderRefundStatus status, String providerRefundReference, Money amount,
		String failureCode) {

	public RefundResult {
		Objects.requireNonNull(status, "status");
	}
}
