package com.qalsmartkeyboard.billing.spi;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Integer minor units with the currency exponent (SD 16.1 rule 5; contract {@code Money}): {@code 29900 ETB 2} is 299.00.
 */
public record Money(long amountMinor, String currency, int exponent) {

	public Money {
		Objects.requireNonNull(currency, "currency");
		if (!currency.matches("[A-Z]{3}")) {
			throw new IllegalArgumentException("currency must be ISO 4217");
		}
		if (exponent < 0 || exponent > 4) {
			throw new IllegalArgumentException("exponent must be 0-4");
		}
		if (amountMinor < 0) {
			throw new IllegalArgumentException("amount must not be negative");
		}
	}

	/** The amount in major units, exactly ({@code 299.00}). */
	public BigDecimal toMajor() {
		return BigDecimal.valueOf(amountMinor, exponent);
	}

	/**
	 * Parses a provider's major-unit amount exactly: {@code 299}, {@code 299.0} and {@code 299.00} are 29900 for ETB;
	 * {@code 299.001} is rejected (never rounded).
	 *
	 * @throws ArithmeticException when the value has more precision than the exponent or is negative
	 */
	public static Money ofMajor(BigDecimal major, String currency, int exponent) {
		BigDecimal minor = major.movePointRight(exponent);
		if (minor.signum() < 0) {
			throw new ArithmeticException("negative amount");
		}
		return new Money(minor.stripTrailingZeros().toBigIntegerExact().longValueExact(), currency, exponent);
	}

	public boolean sameAmount(Money other) {
		return other != null && amountMinor == other.amountMinor && currency.equals(other.currency)
				&& exponent == other.exponent;
	}
}
