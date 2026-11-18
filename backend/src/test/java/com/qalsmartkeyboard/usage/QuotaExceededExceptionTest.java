package com.qalsmartkeyboard.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.usage.QuotaExceededException.UpgradeOption;

/** The contract's QuotaExceededProblem members (SD 14.4) and the empty wallet. */
class QuotaExceededExceptionTest {

	@Test
	void membersMatchTheContractExample() {
		QuotaExceededException q = new QuotaExceededException("TRANSLATE", Instant.parse("2026-11-17T21:00:00Z"),
				new BigDecimal("249.999999"), List.of(new UpgradeOption("PLAN", "PLAN_PREMIUM"),
						new UpgradeOption("CREDIT_PACK", "CREDITS_500")));
		assertThat(q.code()).isEqualTo(ApiErrorCode.QUOTA_EXCEEDED);
		assertThat(q.status().value()).isEqualTo(429);
		assertThat(q.extensions()).containsEntry("featureCode", "TRANSLATE")
				.containsEntry("resetAt", "2026-11-17T21:00:00Z")
				.containsEntry("upgradeOptions", List.of(Map.of("type", "PLAN", "productCode", "PLAN_PREMIUM"),
						Map.of("type", "CREDIT_PACK", "productCode", "CREDITS_500")));
		// balances are shown rounded down to whole credits (SD 15.4)
		assertThat((BigDecimal) q.extensions().get("creditBalance")).isEqualByComparingTo("249");
		assertThat(q.creditBalance()).isEqualByComparingTo("249");
		assertThat(q.detail()).isEqualTo("The allowance for TRANSLATE is used up until 2026-11-17T21:00:00Z.");
	}

	@Test
	void anAllowanceThatNeverResetsHasNoResetAt() {
		QuotaExceededException q = new QuotaExceededException("TONE_ADJUST", null, BigDecimal.ZERO, List.of());
		assertThat(q.extensions()).doesNotContainKey("resetAt").containsEntry("upgradeOptions", List.of());
		assertThat(q.resetAt()).isNull();
	}

	@Test
	void theEmptyWalletHasNothingAndHoldsNothing() {
		NoCreditWallet wallet = new NoCreditWallet();
		UUID user = UUID.randomUUID();
		assertThat(wallet.balance(user)).isEqualByComparingTo("0");
		assertThat(wallet.hold(user, UUID.randomUUID(), BigDecimal.ONE)).isFalse();
		wallet.settle(user, UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.ZERO);
		assertThatThrownBy(() -> wallet.settle(user, UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void creditAmountsAreExactToSixDecimals() {
		assertThat(JdbcMetering.credits(new BigDecimal("0.166667"), 60)).isEqualTo(new BigDecimal("10.000020"));
		assertThat(JdbcMetering.credits(new BigDecimal("0.010000"), 5000)).isEqualTo(new BigDecimal("50.000000"));
		assertThat(JdbcMetering.credits(new BigDecimal("0.333333"), 0)).isEqualTo(new BigDecimal("0.000000"));
		UUID v7 = JdbcMetering.uuid7(Instant.parse("2026-11-18T00:00:00Z"));
		assertThat(v7.version()).isEqualTo(7);
		assertThat(v7.getMostSignificantBits() >>> 16).isEqualTo(Instant.parse("2026-11-18T00:00:00Z").toEpochMilli());
	}
}
