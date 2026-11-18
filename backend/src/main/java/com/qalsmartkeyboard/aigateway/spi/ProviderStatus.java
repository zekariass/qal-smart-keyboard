package com.qalsmartkeyboard.aigateway.spi;

/**
 * {@code ai_providers.provider_status}. The plan says "seeded inactive": in the schema that is DISABLED (the fail
 * closed default). Only ACTIVE providers are routable; DEGRADED is operator-visible and used by the router only as a
 * last resort (ADR 0007 D29).
 */
public enum ProviderStatus {
	ACTIVE, DEGRADED, DISABLED
}
