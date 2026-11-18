package com.qalsmartkeyboard.entitlements;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param quotaPolicyVersion which {@code plan_entitlements.configuration->>'quotaPolicyVersion'} rows are applied. The
 *                           seeded quotas are PROVISIONAL_V2 (SD 15.2); a new policy is a data change (new rows) plus
 *                           this setting, never code
 * @param guestPlanCode      plan of a guest without a valid source (V1 seed: GUEST)
 * @param defaultGracePeriod grace after the period end when a GRACE_PERIOD source has no explicit end (SD 16.5: 3 days)
 * @param renewalLag         how long an ACTIVE source stays valid past its period end (ADR 0007 D22: 24 h), so a late
 *                           store or gateway renewal notification does not cut off a paying user; 0 disables it
 * @param cacheTtl           how long {@code resolve(userId)} results are reused per user; 0 disables the cache
 * @param cacheMaxUsers      upper bound of cached users
 */
@ConfigurationProperties("qal.entitlements")
record EntitlementProperties(@DefaultValue("PROVISIONAL_V2") String quotaPolicyVersion,
		@DefaultValue("GUEST") String guestPlanCode, @DefaultValue("3d") Duration defaultGracePeriod,
		@DefaultValue("30s") Duration cacheTtl, @DefaultValue("10000") int cacheMaxUsers,
		@DefaultValue("24h") Duration renewalLag) {

	EntitlementProperties {
		if (quotaPolicyVersion == null || quotaPolicyVersion.isBlank()) {
			throw new IllegalArgumentException("qal.entitlements.quota-policy-version must not be blank");
		}
		if (guestPlanCode == null || guestPlanCode.isBlank()) {
			throw new IllegalArgumentException("qal.entitlements.guest-plan-code must not be blank");
		}
		if (defaultGracePeriod.isNegative() || cacheTtl.isNegative() || renewalLag.isNegative()) {
			throw new IllegalArgumentException("qal.entitlements durations must not be negative");
		}
		if (cacheMaxUsers < 1) {
			throw new IllegalArgumentException("qal.entitlements.cache-max-users must be positive");
		}
	}
}
