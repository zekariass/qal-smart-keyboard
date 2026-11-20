package com.qalsmartkeyboard.billing.spi;

import java.util.Objects;

/**
 * Return (part of) a verified capture to the payer (ADR 0045 step A2: duplicate captures).
 *
 * @param refundReference              our unique reference for this refund ({@code payment_refunds.refund_reference});
 *                                     the provider's idempotency key where it has one
 * @param merchantReference            our reference of the paid attempt (Chapa {@code tx_ref})
 * @param providerReference            the provider's checkout id of the paid attempt
 * @param providerTransactionReference the provider's payment id of the capture (Chapa {@code reference})
 * @param amount                       what to return (never more than the capture)
 * @param reason                       a short code-like reason (no payer data)
 */
public record RefundRequest(MerchantAccount account, String refundReference, String merchantReference,
		String providerReference, String providerTransactionReference, Money amount, String reason) {

	public RefundRequest {
		Objects.requireNonNull(account, "account");
		Objects.requireNonNull(refundReference, "refundReference");
		Objects.requireNonNull(amount, "amount");
	}
}
