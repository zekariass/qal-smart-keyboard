package com.qalsmartkeyboard.configuration;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Response body of {@code GET /api/v1/config} ({@code ClientConfig} in the contract). */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ClientConfigDto(int configVersion, String minSupportedAppVersion, String recommendedAppVersion,
		Map<String, Boolean> flags, Map<String, Object> values, Instant serverTime) {
}
