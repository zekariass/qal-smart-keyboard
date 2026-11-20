package com.qalsmartkeyboard.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.qalsmartkeyboard.billing.PaymentStates.AttemptStatus;
import com.qalsmartkeyboard.billing.PaymentStates.OrderStatus;
import com.qalsmartkeyboard.billing.spi.Money;
import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.ProviderPaymentStatus;
import com.qalsmartkeyboard.billing.spi.VerificationResult;

/** ADR 0045: amount math (SD 16.6), terms (SD 16.5), the attempt/order state machines and the verification match. */
class BillingMathAndStatesTest {

	@ParameterizedTest
	@CsvSource({ "29900, 0, INCLUSIVE, , 0, 29900", "29900, 0, NOT_APPLICABLE, , 0, 29900",
			"29900, 0, EXCLUSIVE, 1500, 4485, 34385", "29900, 900, EXCLUSIVE, 1500, 4350, 33350",
			"1, 0, EXCLUSIVE, 1500, 0, 1", "3, 0, EXCLUSIVE, 1500, 0, 3", "4, 0, EXCLUSIVE, 1500, 1, 5",
			"0, 0, EXCLUSIVE, 1500, 0, 0" })
	void amountsAreListMinusDiscountPlusExclusiveTaxRoundedHalfUp(long list, long discount, String behaviour,
			Integer rate, long tax, long total) {
		OrderPricing.Amounts a = OrderPricing.amounts(list, discount, behaviour, rate);
		assertThat(a.taxMinor()).isEqualTo(tax);
		assertThat(a.totalMinor()).isEqualTo(total);
		assertThat(a.totalMinor()).isEqualTo(a.listMinor() - a.discountMinor() + a.taxMinor());
	}

	@Test
	void invalidAmountInputsAreRefused() {
		assertThatThrownBy(() -> OrderPricing.amounts(100, 101, "INCLUSIVE", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OrderPricing.amounts(100, 0, "EXCLUSIVE", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OrderPricing.amounts(100, 0, "VAT", null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void manualRenewTerms() {
		assertThat(OrderPricing.term("MONTHLY")).isEqualTo(Duration.ofDays(30));
		assertThat(OrderPricing.term("ANNUAL")).isEqualTo(Duration.ofDays(365));
		assertThat(OrderPricing.term("ONE_TIME")).isNull();
		assertThat(OrderPricing.term("LIFETIME")).isNull();
	}

	@Test
	void moneyParsesProviderMajorUnitsExactly() {
		assertThat(Money.ofMajor(new BigDecimal("299.0"), "ETB", 2)).isEqualTo(new Money(29900, "ETB", 2));
		assertThat(Money.ofMajor(new BigDecimal("299"), "ETB", 2).amountMinor()).isEqualTo(29900);
		assertThat(Money.ofMajor(new BigDecimal("1500"), "UGX", 0).amountMinor()).isEqualTo(1500);
		assertThat(new Money(29900, "ETB", 2).toMajor()).isEqualByComparingTo("299.00");
		assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("299.001"), "ETB", 2))
				.isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("-1"), "ETB", 2)).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> new Money(1, "etb", 2)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void attemptStateMachine() {
		assertThat(AttemptStatus.INITIATED.canMoveTo(AttemptStatus.REQUIRES_ACTION)).isTrue();
		assertThat(AttemptStatus.REQUIRES_ACTION.canMoveTo(AttemptStatus.SUCCEEDED)).isTrue();
		assertThat(AttemptStatus.PENDING.canMoveTo(AttemptStatus.FAILED)).isTrue();
		assertThat(AttemptStatus.PENDING.canMoveTo(AttemptStatus.INITIATED)).isFalse();
		// money really taken is never ignored: a closed attempt can still be verified as paid
		assertThat(AttemptStatus.EXPIRED.canMoveTo(AttemptStatus.SUCCEEDED)).isTrue();
		assertThat(AttemptStatus.FAILED.canMoveTo(AttemptStatus.PENDING)).isFalse();
		// success is terminal: an out-of-order FAILED never downgrades it
		for (AttemptStatus s : AttemptStatus.values()) {
			assertThat(AttemptStatus.SUCCEEDED.canMoveTo(s)).isFalse();
		}
		assertThat(AttemptStatus.UNKNOWN.isOpen()).isTrue();
		assertThat(AttemptStatus.CANCELLED.isOpen()).isFalse();
		assertThat(AttemptStatus.fromProvider(ProviderPaymentStatus.NOT_STARTED)).isNull();
		assertThat(AttemptStatus.fromProvider(ProviderPaymentStatus.SUCCEEDED)).isEqualTo(AttemptStatus.SUCCEEDED);
	}

	@Test
	void orderStateMachine() {
		assertThat(OrderStatus.AWAITING_PAYMENT.canMoveTo(OrderStatus.PAID)).isTrue();
		assertThat(OrderStatus.CANCELLED.canMoveTo(OrderStatus.PAID)).isTrue();
		assertThat(OrderStatus.PAID.canMoveTo(OrderStatus.PAID)).isFalse();
		assertThat(OrderStatus.PAID.canMoveTo(OrderStatus.CANCELLED)).isFalse();
		assertThat(OrderStatus.PAID.canMoveTo(OrderStatus.REFUNDED)).isTrue();
		assertThat(OrderStatus.EXPIRED.isPayable()).isFalse();
		assertThat(OrderStatus.CREATED.isPayable()).isTrue();
	}

	static PaymentVerifier.Context ctx(String providerRef) {
		return new PaymentVerifier.Context(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				AttemptStatus.REQUIRES_ACTION, providerRef, "nonce-1", null, UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), UUID.randomUUID(), "ETB", PaymentEnvironment.SANDBOX, "ARIFPAY", "acct", "ET", null,
				29900, 2);
	}

	static VerificationResult paid(String providerRef, String nonce, Money amount) {
		return new VerificationResult(ProviderPaymentStatus.SUCCEEDED, providerRef, "TX-1", amount, nonce,
				"TELEBIRR_USSD", null, null);
	}

	@Test
	void verificationMustMatchReferenceNonceAmountAndCurrency() {
		Money expected = new Money(29900, "ETB", 2);
		assertThat(PaymentVerifier.mismatch(ctx("S1"), paid("S1", "nonce-1", expected), expected)).isNull();
		assertThat(PaymentVerifier.mismatch(ctx("S1"), paid("S2", "nonce-1", expected), expected))
				.isEqualTo("providerReference");
		assertThat(PaymentVerifier.mismatch(ctx("S1"), paid("S1", "other", expected), expected))
				.isEqualTo("merchantReference");
		assertThat(PaymentVerifier.mismatch(ctx("S1"), paid("S1", "nonce-1", new Money(100, "ETB", 2)), expected))
				.isEqualTo("amount");
		assertThat(PaymentVerifier.mismatch(ctx("S1"), paid("S1", "nonce-1", new Money(29900, "USD", 2)), expected))
				.isEqualTo("amount");
		assertThat(PaymentVerifier.mismatch(ctx("S1"), paid("S1", "nonce-1", null), expected)).isEqualTo("amount");
		// a lost start answer: the provider reference is adopted from a lookup by nonce
		assertThat(PaymentVerifier.mismatch(ctx(null), paid("S9", "nonce-1", expected), expected)).isNull();
		assertThat(PaymentVerifier.mismatch(ctx(null), paid(null, "nonce-1", expected), expected))
				.isEqualTo("providerReference");
	}

	@Test
	void channelsMapToSalesChannels() {
		assertThat(new Channel("ANDROID", "DIRECT_DOWNLOAD").salesChannel()).isEqualTo("ANDROID_DIRECT");
		assertThat(Channel.ofSalesChannel("ANDROID_GOOGLE_PLAY")).contains(new Channel("ANDROID", "GOOGLE_PLAY"));
		assertThat(Channel.of("ANDROID", "APPLE_APP_STORE")).isEmpty();
		assertThat(Channel.ofSalesChannel("ADMIN")).isEmpty();
	}
}
