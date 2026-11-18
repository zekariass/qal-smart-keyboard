package com.qalsmartkeyboard.aigateway.spi;

import java.util.Set;

/**
 * What a request demands of a provider data handling: ZDR for BUSINESS / zero-retention organisations (SD 13) and an
 * optional processing-region allow-list (empty = any). "Does not train on customer data" is not optional and is not
 * part of this requirement: it is always enforced.
 */
public record DataPolicyRequirement(boolean zeroRetentionRequired, Set<String> allowedRegions) {

	public static final DataPolicyRequirement STANDARD = new DataPolicyRequirement(false, Set.of());
	public static final DataPolicyRequirement ZERO_RETENTION = new DataPolicyRequirement(true, Set.of());

	public DataPolicyRequirement {
		allowedRegions = allowedRegions == null ? Set.of() : Set.copyOf(allowedRegions);
	}
}
