package com.qalsmartkeyboard.tools;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Exchange rates taken at one moment: {@code rates} maps an ISO 4217 code to the value of one unit of {@code base} in
 * that currency.
 *
 * @param source provider label ({@code seed} for the built-in development table)
 */
record FxRateSnapshot(String base, Instant asOf, Map<String, BigDecimal> rates, String source) {
}
