package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Response bodies of {@code getEntitlements} and {@code getUsage} (contract {@code Entitlements}, {@code UsageReport}). */
final class UsageReadDtos {

	private UsageReadDtos() {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record EntitlementsDto(PlanDto plan, List<FeatureDto> features, Long creditBalance, TokenDto entitlementToken) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PlanDto(String planCode, String displayName, int planRank, String source, UUID subscriptionId,
			UUID organizationId, Instant effectiveUntil) {
	}

	/** {@code FeatureEntitlement}; {@code used}, {@code remaining} and {@code resetsAt} only for metered features. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record FeatureDto(String featureCode, boolean accessEnabled, Long includedUnits, String unit, String resetPeriod,
			Long used, Long remaining, Instant resetsAt) {
	}

	record TokenDto(String jws, String keyId, Instant expiresAt, Instant offlineGraceUntil) {
	}

	record UsageReportDto(List<UsageItemDto> items) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record UsageItemDto(String featureCode, Instant periodStart, Instant periodEnd, long usedUnits, Long includedUnits,
			BigDecimal creditsConsumed, String unit) {
	}
}
