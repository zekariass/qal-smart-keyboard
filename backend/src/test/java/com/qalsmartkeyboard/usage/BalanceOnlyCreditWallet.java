package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Test wallet that honours the {@link CreditWallet} contract on {@code ai_credit_accounts.balance_units} only (no lots,
 * no ledger: that is B4.3). It joins the engine's transaction (same DataSource) and never overdraws: the hold is a
 * conditional UPDATE that the CHECK {@code balance_units >= 0} backs up.
 */
final class BalanceOnlyCreditWallet implements CreditWallet {

	private final JdbcClient jdbc;

	BalanceOnlyCreditWallet(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void fund(UUID userId, BigDecimal amount) {
		jdbc.sql("""
				INSERT INTO ai_credit_accounts (user_id, balance_units) VALUES (:user, :amount)
				ON CONFLICT (user_id, currency_unit) WHERE user_id IS NOT NULL
				DO UPDATE SET balance_units = ai_credit_accounts.balance_units + EXCLUDED.balance_units
				""").param("user", userId).param("amount", amount).update();
	}

	@Override
	public BigDecimal balance(UUID userId) {
		return jdbc.sql("SELECT balance_units FROM ai_credit_accounts WHERE user_id = :user AND currency_unit = 'AI_CREDIT'")
				.param("user", userId).query(BigDecimal.class).optional().orElse(new BigDecimal("0.000000"));
	}

	@Override
	public boolean hold(UUID userId, UUID reservationId, BigDecimal amount) {
		return jdbc.sql("""
				UPDATE ai_credit_accounts SET balance_units = balance_units - :amount, updated_at = now()
				WHERE user_id = :user AND currency_unit = 'AI_CREDIT' AND balance_units >= :amount
				""").param("amount", amount).param("user", userId).update() == 1;
	}

	@Override
	public void settle(UUID userId, UUID reservationId, BigDecimal held, BigDecimal charged) {
		if (charged.compareTo(held) > 0) {
			throw new IllegalArgumentException("charged exceeds held");
		}
		BigDecimal back = held.subtract(charged);
		if (back.signum() > 0) {
			jdbc.sql("""
					UPDATE ai_credit_accounts SET balance_units = balance_units + :back, updated_at = now()
					WHERE user_id = :user AND currency_unit = 'AI_CREDIT'
					""").param("back", back).param("user", userId).update();
		}
	}
}
