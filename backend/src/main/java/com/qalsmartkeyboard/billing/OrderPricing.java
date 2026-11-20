package com.qalsmartkeyboard.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * Server-side amount math (SD 16.6, D21): integer minor units only, {@code total = list - discount + tax}, tax only for
 * {@code EXCLUSIVE} prices, computed on the discounted amount and rounded half-up to the minor unit. Also the term
 * lengths of {@code MANUAL_RENEW} subscriptions (SD 16.5: fixed 30-day or annual terms).
 */
final class OrderPricing {

	record Amounts(long listMinor, long discountMinor, long taxMinor, long totalMinor) {
	}

	private OrderPricing() {
	}

	static Amounts amounts(long listMinor, long discountMinor, String taxBehavior, Integer taxRateBasisPoints) {
		if (listMinor < 0 || discountMinor < 0 || discountMinor > listMinor) {
			throw new IllegalArgumentException("discount must be between 0 and the list amount");
		}
		long net = Math.subtractExact(listMinor, discountMinor);
		long tax = 0;
		if ("EXCLUSIVE".equals(taxBehavior)) {
			if (taxRateBasisPoints == null || taxRateBasisPoints < 0 || taxRateBasisPoints > 10_000) {
				throw new IllegalArgumentException("an EXCLUSIVE price needs a tax rate of 0-10000 basis points");
			}
			tax = BigDecimal.valueOf(net).multiply(BigDecimal.valueOf(taxRateBasisPoints))
					.divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
		}
		else if (!"INCLUSIVE".equals(taxBehavior) && !"NOT_APPLICABLE".equals(taxBehavior)) {
			throw new IllegalArgumentException("unknown tax behaviour");
		}
		return new Amounts(listMinor, discountMinor, tax, Math.addExact(net, tax));
	}

	/** The term a paid subscription order grants, by billing period; empty for periods that are not terms. */
	static Duration term(String billingPeriod) {
		return switch (billingPeriod) {
			case "WEEKLY" -> Duration.ofDays(7);
			case "MONTHLY" -> Duration.ofDays(30);
			case "QUARTERLY" -> Duration.ofDays(90);
			case "ANNUAL" -> Duration.ofDays(365);
			default -> null;
		};
	}
}
