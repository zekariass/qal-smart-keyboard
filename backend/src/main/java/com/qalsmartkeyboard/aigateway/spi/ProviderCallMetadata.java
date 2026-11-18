package com.qalsmartkeyboard.aigateway.spi;

import java.time.Duration;
import java.util.Objects;

/**
 * Operational facts about one provider call (never content): who served it, which model, how long it took and the
 * vendor's own request id for support tickets. {@code providerRequestId} and {@code modelCode} may be null.
 */
public record ProviderCallMetadata(String providerCode, String modelCode, String providerRequestId, Duration latency) {

	public ProviderCallMetadata {
		Objects.requireNonNull(providerCode, "providerCode");
		Objects.requireNonNull(latency, "latency");
	}
}
