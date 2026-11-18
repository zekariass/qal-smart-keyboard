package com.qalsmartkeyboard.entitlements;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The outcome of {@link EntitlementResolver#resolve}: the effective plan and one entry per active catalogue feature.
 *
 * @param access             {@link AccessState#GRANTED} or the reason no plan applies
 * @param plan               empty unless access is GRANTED
 * @param quotaPolicyVersion the version whose {@code plan_entitlements} rows were applied (config, PROVISIONAL_V2)
 * @param features           every active {@code feature_catalog} feature by code, in catalogue order; gated features of
 *                           a denied account are disabled, ungated ones stay allowed (typing is never gated)
 */
public record ResolvedEntitlements(UUID userId, Instant at, AccessState access, Optional<EffectivePlan> plan,
		String quotaPolicyVersion, Map<String, FeatureEntitlement> features) {

	public ResolvedEntitlements {
		features = Collections.unmodifiableMap(new LinkedHashMap<>(features));
	}

	public Optional<FeatureEntitlement> feature(String featureCode) {
		return Optional.ofNullable(features.get(featureCode));
	}

	/** True only for a known, active feature whose access is enabled. Unknown codes are denied. */
	public boolean isAllowed(String featureCode) {
		FeatureEntitlement f = features.get(featureCode);
		return f != null && f.accessEnabled();
	}
}
