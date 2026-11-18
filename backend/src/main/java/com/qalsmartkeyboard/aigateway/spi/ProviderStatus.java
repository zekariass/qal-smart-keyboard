package com.qalsmartkeyboard.aigateway.spi;

/**
 * {@code ai_providers.provider_status}. The plan says "seeded inactive": in the schema that is DISABLED (the fail
 * closed default). Only ACTIVE providers are routable; DEGRADED is operator-visible and excluded until B5.2 decides.
 */
public enum ProviderStatus {
	ACTIVE, DEGRADED, DISABLED
}
