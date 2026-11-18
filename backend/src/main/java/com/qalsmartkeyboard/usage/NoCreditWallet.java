package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The wallet until B4.3 ships credit lots: always empty, so nothing is ever paid with credits and
 * {@code QUOTA_EXCEEDED} reports a balance of 0. No purchase or grant path for credits exists yet either (B4.3, M4.4).
 */
final class NoCreditWallet implements CreditWallet {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);

	@Override
	public BigDecimal balance(UUID userId) {
		return ZERO;
	}

	@Override
	public boolean hold(UUID userId, UUID reservationId, BigDecimal amount) {
		return false;
	}

	@Override
	public void settle(UUID userId, UUID reservationId, BigDecimal held, BigDecimal charged) {
		if (held.signum() != 0) {
			throw new IllegalStateException("The empty wallet never holds credits");
		}
	}
}
