package com.qalsmartkeyboard.entitlements;

import java.math.BigDecimal;

/**
 * What one feature allows (contract {@code FeatureEntitlement}, without the usage fields that B4.2 adds).
 *
 * @param gated             false for typing features and unmetered server features (DETECT_LANGUAGE): they are never
 *                          entitlement-gated (SD R-2) and always allowed
 * @param includedUnits     allowance per reset period in {@code unit}; null = unlimited (metered feature) or boolean
 *                          access (unit NONE). 0 on a metered feature means "nothing included"
 * @param creditCostPerUnit {@code feature_catalog.credit_cost_per_unit}, for the credit fallback (SD 15.4)
 * @param creditsUsable     AI credits may pay for this feature when the allowance is used up: gated, access enabled,
 *                          metered and priced above zero
 * @param origin            where the values came from
 */
public record FeatureEntitlement(String featureCode, boolean gated, boolean accessEnabled, MeteringUnit unit,
		Long includedUnits, ResetPeriod resetPeriod, BigDecimal creditCostPerUnit, boolean creditsUsable,
		Origin origin) {

	public enum Origin {
		/** A {@code plan_entitlements} row of the effective plan. */
		PLAN,
		/** A {@code user_feature_overrides} row changed the access decision. */
		OVERRIDE,
		/** Not gated: always allowed. */
		UNGATED,
		/** The plan has no row for this feature (or the quota policy version): denied. */
		NO_PLAN_ROW,
		/** The account is not allowed to hold entitlements. */
		ACCOUNT_DENIED
	}

	/** True when the allowance has no upper bound. */
	public boolean unlimited() {
		return accessEnabled && unit != MeteringUnit.NONE && includedUnits == null;
	}
}
