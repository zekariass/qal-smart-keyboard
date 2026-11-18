package com.qalsmartkeyboard.aigateway.routing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

/** V12 tables: ai_provider_prices, ai_provider_budgets, ai_provider_daily_spend. */
public class JdbcCostStore implements CostStore {

	private final JdbcClient jdbc;

	public JdbcCostStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<String, List<Price>> prices() {
		Map<String, List<Price>> out = new HashMap<>();
		jdbc.sql("""
				SELECT p.provider_code, x.feature_code, x.price_unit, x.unit_price_micros
				  FROM ai_provider_prices x JOIN ai_providers p ON p.id = x.provider_id
				 WHERE x.valid_from <= NOW() AND (x.valid_until IS NULL OR x.valid_until > NOW())
				""").query(rs -> {
			out.computeIfAbsent(rs.getString("provider_code"), k -> new ArrayList<>())
					.add(new Price(rs.getString("feature_code"), PriceUnit.valueOf(rs.getString("price_unit")),
							rs.getBigDecimal("unit_price_micros")));
		});
		return out;
	}

	@Override
	public Map<String, Budget> budgets() {
		Map<String, Budget> out = new HashMap<>();
		jdbc.sql("""
				SELECT p.provider_code, b.daily_soft_limit_micros, b.daily_hard_limit_micros
				  FROM ai_provider_budgets b JOIN ai_providers p ON p.id = b.provider_id
				 WHERE b.is_enabled
				""").query(rs -> {
			out.put(rs.getString("provider_code"), new Budget(rs.getBigDecimal("daily_soft_limit_micros"),
					rs.getBigDecimal("daily_hard_limit_micros")));
		});
		return out;
	}

	@Override
	public Map<String, BigDecimal> spend(LocalDate day) {
		Map<String, BigDecimal> out = new HashMap<>();
		jdbc.sql("""
				SELECT p.provider_code, s.cost_micros
				  FROM ai_provider_daily_spend s JOIN ai_providers p ON p.id = s.provider_id
				 WHERE s.spend_date = :day
				""").param("day", day).query(rs -> {
			out.put(rs.getString("provider_code"), rs.getBigDecimal("cost_micros"));
		});
		return out;
	}

	@Override
	public void addSpend(String providerCode, LocalDate day, BigDecimal micros) {
		jdbc.sql("""
				INSERT INTO ai_provider_daily_spend (provider_id, spend_date, cost_micros, call_count)
				SELECT id, :day, :micros, 1 FROM ai_providers WHERE provider_code = :code
				ON CONFLICT (provider_id, spend_date) DO UPDATE
				   SET cost_micros = ai_provider_daily_spend.cost_micros + EXCLUDED.cost_micros,
				       call_count = ai_provider_daily_spend.call_count + 1, updated_at = NOW()
				""").param("day", day).param("micros", micros).param("code", providerCode).update();
	}
}
