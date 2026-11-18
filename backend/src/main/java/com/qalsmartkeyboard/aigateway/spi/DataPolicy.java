package com.qalsmartkeyboard.aigateway.spi;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A provider contractual data handling ({@code ai_providers.zero_data_retention}, {@code trains_on_customer_data},
 * {@code processing_regions}, {@code dpa_signed_at IS NOT NULL}).
 */
public record DataPolicy(boolean zeroDataRetention, boolean trainsOnCustomerData, Set<String> processingRegions,
		boolean dpaSigned) {

	public DataPolicy {
		processingRegions = processingRegions == null ? Set.of()
				: processingRegions.stream().map(r -> r.trim().toLowerCase(Locale.ROOT))
						.collect(Collectors.toUnmodifiableSet());
	}

	/** Worst-case policy: what the schema defaults assume until a contract says otherwise. */
	public static DataPolicy unknown() {
		return new DataPolicy(false, true, Set.of(), false);
	}

	/**
	 * A provider that trains on customer data never qualifies (SD 11.2). ZDR is required only when asked. A region
	 * allow-list needs every declared processing region inside it and fails closed when the provider declares none.
	 */
	public boolean satisfies(DataPolicyRequirement requirement) {
		if (trainsOnCustomerData) {
			return false;
		}
		if (requirement.zeroRetentionRequired() && !zeroDataRetention) {
			return false;
		}
		Set<String> allowed = requirement.allowedRegions().stream().map(r -> r.trim().toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet());
		return allowed.isEmpty() || (!processingRegions.isEmpty() && allowed.containsAll(processingRegions));
	}
}
