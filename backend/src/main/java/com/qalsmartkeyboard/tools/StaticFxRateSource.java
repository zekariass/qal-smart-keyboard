package com.qalsmartkeyboard.tools;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads the newest snapshot (the rows of the greatest {@code as_of} for base USD) from {@code fx_rate_snapshots}. The
 * table is seeded by migration V30 and may later be fed by a job or an admin path.
 */
class StaticFxRateSource implements FxRateSource {

	static final String BASE = "USD";

	private final JdbcTemplate jdbc;

	StaticFxRateSource(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<FxRateSnapshot> latest() {
		Map<String, BigDecimal> rates = new LinkedHashMap<>();
		Instant[] asOf = new Instant[1];
		String[] source = new String[1];
		jdbc.query("""
				SELECT quote_currency, rate, as_of, source
				FROM fx_rate_snapshots
				WHERE base_currency = ?
				  AND as_of = (SELECT max(as_of) FROM fx_rate_snapshots WHERE base_currency = ?)
				ORDER BY quote_currency""", rs -> {
			rates.put(rs.getString(1), rs.getBigDecimal(2));
			asOf[0] = rs.getTimestamp(3).toInstant();
			source[0] = rs.getString(4);
		}, BASE, BASE);
		if (rates.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new FxRateSnapshot(BASE, asOf[0], rates, source[0]));
	}
}
