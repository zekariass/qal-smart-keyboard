package com.qalsmartkeyboard.aigateway.routing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Where prices, budgets and the spend ledger live (PostgreSQL in production, memory in unit tests). */
public interface CostStore {

	/** One price row: null feature = every feature. */
	record Price(String feature, PriceUnit unit, BigDecimal microsPerUnit) {
	}

	/** Daily limits in micro-USD; null = none. */
	record Budget(BigDecimal softMicros, BigDecimal hardMicros) {
	}

	enum PriceUnit {
		REQUEST, INPUT_CHARACTER, OUTPUT_CHARACTER, INPUT_TOKEN, OUTPUT_TOKEN, AUDIO_SECOND
	}

	/** Prices valid now, by provider code. */
	Map<String, List<Price>> prices();

	/** Enabled budgets by provider code. */
	Map<String, Budget> budgets();

	/** Estimated spend of every provider for the UTC day. */
	Map<String, BigDecimal> spend(LocalDate day);

	/** Adds one call's estimated cost to the provider's day. */
	void addSpend(String providerCode, LocalDate day, BigDecimal micros);
}
