package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A credit lot.
 *
 * @param created false when the grant was a replay of an earlier one with the same source reference
 */
public record CreditLot(UUID id, UUID userId, CreditSource source, BigDecimal initialUnits, BigDecimal remainingUnits,
		Instant expiresAt, Instant createdAt, boolean created) {
}
