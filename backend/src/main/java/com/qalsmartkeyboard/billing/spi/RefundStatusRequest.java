package com.qalsmartkeyboard.billing.spi;

import java.util.Objects;

/**
 * Ask the provider what happened to a refund we started.
 *
 * @param providerRefundReference the provider's id from {@link RefundResult#providerRefundReference()}
 */
public record RefundStatusRequest(MerchantAccount account, String refundReference, String providerRefundReference) {

	public RefundStatusRequest {
		Objects.requireNonNull(account, "account");
		Objects.requireNonNull(refundReference, "refundReference");
	}
}
