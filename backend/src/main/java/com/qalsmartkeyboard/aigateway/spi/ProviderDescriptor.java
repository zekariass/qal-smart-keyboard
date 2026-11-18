package com.qalsmartkeyboard.aigateway.spi;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A provider as recorded in the database (ai_providers + active models + enabled capabilities). Holds no secret: the
 * API key is resolved from configuration by the adapter and never read from, or stored in, the database.
 */
public record ProviderDescriptor(String code, String displayName, ProviderType type, ProviderStatus status,
		DataPolicy dataPolicy, List<ProviderCapability> capabilities) {

	public ProviderDescriptor {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(dataPolicy, "dataPolicy");
		capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
	}

	/**
	 * Routable at all: ACTIVE, not training on customer data and, unless self-hosted, under a signed DPA (the schema
	 * CHECK enforces the same; this repeats it so a hand-edited row cannot slip through).
	 */
	public boolean routable() {
		return status == ProviderStatus.ACTIVE && !dataPolicy.trainsOnCustomerData()
				&& (type == ProviderType.SELF_HOSTED || dataPolicy.dpaSigned());
	}

	/** The highest-tier capability serving the query, if any. */
	public Optional<ProviderCapability> capabilityFor(RouteQuery query) {
		return capabilities.stream().filter(c -> c.matches(query))
				.max(Comparator.comparing(ProviderCapability::qualityTier));
	}
}
