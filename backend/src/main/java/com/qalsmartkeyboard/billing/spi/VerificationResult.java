package com.qalsmartkeyboard.billing.spi;

import java.time.Instant;
import java.util.Objects;

/**
 * The provider's server-to-server answer, as data. The core (not the adapter) decides whether it matches the order:
 * {@code providerReference}, {@code merchantReference}, {@code amount} and currency must all equal the attempt's
 * before anything is granted (SD 16.1 rule 3, 16.9 step 4).
 *
 * @param providerReference            the provider's checkout id (may have been looked up by merchant reference)
 * @param providerTransactionReference the provider's payment/receipt id (ArifPay {@code transactionId}), if any
 * @param amount                       the amount the provider says was paid, or null when it did not say
 * @param providerMethodCode           the rail the payer used (e.g. {@code TELEBIRR_USSD}), if known
 * @param failureCode                  a short safe code for non-success outcomes (never provider prose)
 */
public record VerificationResult(ProviderPaymentStatus status, String providerReference,
		String providerTransactionReference, Money amount, String merchantReference, String providerMethodCode,
		Instant occurredAt, String failureCode) {

	public VerificationResult {
		Objects.requireNonNull(status, "status");
	}

	public static VerificationResult unknown(String providerReference, String merchantReference, String failureCode) {
		return new VerificationResult(ProviderPaymentStatus.UNKNOWN, providerReference, null, null, merchantReference,
				null, null, failureCode);
	}
}
