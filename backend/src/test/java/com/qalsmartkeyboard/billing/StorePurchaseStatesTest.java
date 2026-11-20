package com.qalsmartkeyboard.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.StoreSubscription;

/** ADR 0046: store purchase states from what RevenueCat reports. */
class StorePurchaseStatesTest {

	static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
	static final Duration PENDING = Duration.ofHours(6);

	static StoreSubscription sub(Instant expires, Instant grace, Instant unsubscribed, Instant billingIssue,
			Instant refunded, Instant autoResume) {
		return new StoreSubscription("qal_premium", "monthly", "play_store", PaymentEnvironment.SANDBOX,
				NOW.minus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(40)), expires, grace, unsubscribed,
				billingIssue, refunded, autoResume, "GPA.1", "normal", "PURCHASED");
	}

	static String status(StoreSubscription s) {
		return StorePurchaseStates.purchaseStatus(s, NOW, PENDING);
	}

	@Test
	void derivesEveryState() {
		Instant future = NOW.plus(Duration.ofDays(20));
		Instant past = NOW.minus(Duration.ofDays(2));
		assertThat(status(sub(future, null, null, null, null, null))).isEqualTo("ACTIVE");
		assertThat(status(sub(null, null, null, null, null, null))).isEqualTo("ACTIVE");
		// cancelled but paid until the end
		StoreSubscription cancelled = sub(future, null, NOW.minusSeconds(60), null, null, null);
		assertThat(status(cancelled)).isEqualTo("ACTIVE");
		assertThat(StorePurchaseStates.autoRenewing(cancelled, "ACTIVE")).isFalse();
		assertThat(status(sub(future, null, null, null, NOW, null))).isEqualTo("REFUNDED");
		assertThat(status(sub(past, NOW.plus(Duration.ofDays(1)), null, past, null, null))).isEqualTo("IN_GRACE_PERIOD");
		assertThat(status(sub(past, null, null, null, null, NOW.plus(Duration.ofDays(5))))).isEqualTo("PAUSED");
		assertThat(status(sub(past, null, null, past, null, null))).isEqualTo("ON_HOLD");
		assertThat(status(sub(NOW.minus(Duration.ofDays(90)), null, null, past, null, null))).isEqualTo("EXPIRED");
		assertThat(status(sub(past, null, NOW.minus(Duration.ofDays(5)), null, null, null))).isEqualTo("EXPIRED");
		// the Play renewal gap: still renewing, period ended an hour ago
		assertThat(status(sub(NOW.minus(Duration.ofHours(1)), null, null, null, null, null))).isEqualTo("ACTIVE");
		assertThat(status(sub(NOW.minus(Duration.ofHours(7)), null, null, null, null, null))).isEqualTo("EXPIRED");

		assertThat(StorePurchaseStates.subscriptionStatus("IN_GRACE_PERIOD")).isEqualTo("GRACE_PERIOD");
		assertThat(StorePurchaseStates.subscriptionStatus("ON_HOLD")).isEqualTo("PAST_DUE");
		assertThat(StorePurchaseStates.subscriptionStatus("REFUNDED")).isEqualTo("REVOKED");
		assertThat(StorePurchaseStates.subscriptionEvent(null, "ACTIVE", "ACTIVE", false, null, true))
				.isEqualTo("ACTIVATED");
		assertThat(StorePurchaseStates.subscriptionEvent("ACTIVE", "REVOKED", "REFUNDED", false, true, false))
				.isEqualTo("REFUNDED");
		assertThat(StorePurchaseStates.subscriptionEvent("ACTIVE", "ACTIVE", "ACTIVE", true, true, true))
				.isEqualTo("RENEWED");
		assertThat(StorePurchaseStates.subscriptionEvent("ACTIVE", "ACTIVE", "ACTIVE", false, true, true)).isNull();
		assertThat(StorePurchases.baseProduct("qal_premium:monthly")).isEqualTo("qal_premium");
	}
}
