package com.qalsmartkeyboard.aigateway.routing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.qalsmartkeyboard.aigateway.routing.CostStore.Budget;
import com.qalsmartkeyboard.aigateway.routing.CostStore.Price;
import com.qalsmartkeyboard.aigateway.routing.CostStore.PriceUnit;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.ProviderUsage;

/**
 * Per-provider daily spend cap (B5.2, SD 19 cost anomaly alerts). The cost of a successful call is estimated as
 * usage x price per unit from {@code ai_provider_prices}, added to the provider's UTC-day total
 * ({@code ai_provider_daily_spend}) and compared with {@code ai_provider_budgets}: crossing the soft limit raises an
 * alert once per day (log + metric via the listener), reaching the hard limit makes {@link #status} return
 * {@code HARD_EXCEEDED} so the router skips the provider until the next UTC day or until the limit is raised.
 *
 * <p>Prices and budgets are cached for the TTL. The spend total is the database value as of the last refresh plus this
 * node's own additions since, so several nodes converge within one TTL; the cap can be overshot by the calls in flight
 * and by the other nodes' spend inside one TTL (documented, acceptable for a budget guard, not a billing ledger).
 * A failed read keeps the last snapshot, and before the first successful read spend is zero and no budget applies
 * (fail open: provider cost is not worth refusing users over a database blip, and the next refresh recovers).
 * A failed ledger write is swallowed and the in-memory total still counts.
 */
public class CostControl {

	public enum Status {
		OK, SOFT_EXCEEDED, HARD_EXCEEDED
	}

	public enum AlertLevel {
		SOFT, HARD
	}

	/** Raised once per provider, level and UTC day. */
	public record Alert(String providerCode, AlertLevel level, BigDecimal spendMicros, BigDecimal limitMicros) {
	}

	private final CostStore store;
	private final Duration ttl;
	private final Clock clock;
	private final Consumer<Alert> alerts;

	private Map<String, List<Price>> prices = Map.of();
	private Map<String, Budget> budgets = Map.of();
	private Instant configLoadedAt = Instant.MIN;

	private LocalDate day;
	private Map<String, BigDecimal> spend = new HashMap<>();
	private Instant spendLoadedAt = Instant.MIN;
	private final Set<String> alerted = new HashSet<>();

	public CostControl(CostStore store, Duration ttl, Clock clock, Consumer<Alert> alerts) {
		this.store = store;
		this.ttl = ttl;
		this.clock = clock;
		this.alerts = alerts == null ? a -> {
		} : alerts;
	}

	/** Where the provider stands against its budget right now. */
	public synchronized Status status(String providerCode) {
		refresh();
		Budget b = budgets.get(providerCode);
		if (b == null) {
			return Status.OK;
		}
		BigDecimal spent = spend.getOrDefault(providerCode, BigDecimal.ZERO);
		if (b.hardMicros() != null && spent.compareTo(b.hardMicros()) >= 0) {
			return Status.HARD_EXCEEDED;
		}
		if (b.softMicros() != null && spent.compareTo(b.softMicros()) >= 0) {
			return Status.SOFT_EXCEEDED;
		}
		return Status.OK;
	}

	/** Spend so far today in micro-USD (for gauges). */
	public synchronized BigDecimal spentToday(String providerCode) {
		refresh();
		return spend.getOrDefault(providerCode, BigDecimal.ZERO);
	}

	/** Estimated cost of one call: sum over price units of units x price; no price row = zero. */
	public synchronized BigDecimal estimate(String providerCode, AiFeature feature, ProviderUsage usage) {
		refresh();
		List<Price> rows = prices.getOrDefault(providerCode, List.of());
		Map<PriceUnit, Price> chosen = new EnumMap<>(PriceUnit.class);
		for (Price p : rows) {
			Price current = chosen.get(p.unit());
			boolean specific = feature.name().equals(p.feature());
			if ((p.feature() == null || specific) && (current == null || (specific && current.feature() == null))) {
				chosen.put(p.unit(), p);
			}
		}
		BigDecimal total = BigDecimal.ZERO;
		for (Price p : chosen.values()) {
			total = total.add(p.microsPerUnit().multiply(BigDecimal.valueOf(units(p.unit(), usage))));
		}
		return total.setScale(6, RoundingMode.HALF_UP);
	}

	/** Books the estimated cost of a successful call and raises the soft or hard alert when a limit is crossed. */
	public synchronized BigDecimal record(String providerCode, AiFeature feature, ProviderUsage usage) {
		BigDecimal cost = estimate(providerCode, feature, usage);
		if (cost.signum() > 0) {
			try {
				store.addSpend(providerCode, day, cost);
			} catch (RuntimeException e) {
				// the in-memory total below still counts; the ledger misses one increment
			}
			spend.merge(providerCode, cost, BigDecimal::add);
			alertIfCrossed(providerCode);
		}
		return cost;
	}

	private void alertIfCrossed(String providerCode) {
		Budget b = budgets.get(providerCode);
		if (b == null) {
			return;
		}
		BigDecimal spent = spend.get(providerCode);
		if (b.hardMicros() != null && spent.compareTo(b.hardMicros()) >= 0 && alerted.add(providerCode + "|HARD")) {
			alerts.accept(new Alert(providerCode, AlertLevel.HARD, spent, b.hardMicros()));
		} else if (b.softMicros() != null && spent.compareTo(b.softMicros()) >= 0
				&& alerted.add(providerCode + "|SOFT")) {
			alerts.accept(new Alert(providerCode, AlertLevel.SOFT, spent, b.softMicros()));
		}
	}

	private void refresh() {
		Instant now = clock.instant();
		LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
		boolean newDay = !today.equals(day);
		if (newDay) {
			day = today;
			spend = new HashMap<>();
			alerted.clear();
			spendLoadedAt = Instant.MIN;
		}
		if (Duration.between(configLoadedAt, now).compareTo(ttl) >= 0) {
			configLoadedAt = now;
			try {
				prices = Map.copyOf(store.prices());
				budgets = Map.copyOf(store.budgets());
			} catch (RuntimeException e) {
				// keep the last snapshot
			}
		}
		if (Duration.between(spendLoadedAt, now).compareTo(ttl) >= 0) {
			spendLoadedAt = now;
			try {
				spend = new HashMap<>(store.spend(today));
			} catch (RuntimeException e) {
				// keep this node's own totals
			}
		}
	}

	private static long units(PriceUnit unit, ProviderUsage u) {
		return switch (unit) {
			case REQUEST -> 1L;
			case INPUT_CHARACTER -> u.inputCharacters();
			case OUTPUT_CHARACTER -> u.outputCharacters();
			case INPUT_TOKEN -> u.inputTokens() == null ? 0L : u.inputTokens();
			case OUTPUT_TOKEN -> u.outputTokens() == null ? 0L : u.outputTokens();
			case AUDIO_SECOND -> u.audioMillis() == null ? 0L : Math.ceilDiv(u.audioMillis(), 1000L);
		};
	}
}
