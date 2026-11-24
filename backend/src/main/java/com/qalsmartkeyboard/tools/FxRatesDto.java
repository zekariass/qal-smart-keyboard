package com.qalsmartkeyboard.tools;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Response body of {@code GET /api/v1/tools/fx-rates} ({@code FxRates} in the contract). */
record FxRatesDto(String base, Instant asOf, Map<String, BigDecimal> rates, String source, boolean stale) {
}
