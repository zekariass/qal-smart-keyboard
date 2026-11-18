package com.qalsmartkeyboard.usage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.platform.retention.RetentionAction;
import com.qalsmartkeyboard.platform.retention.RetentionParticipant;
import com.qalsmartkeyboard.platform.retention.RetentionRule;
import com.qalsmartkeyboard.platform.retention.RetentionSupport;

/**
 * Retention (B6.3, ADR 0020) of the usage tables.
 *
 * <ul>
 * <li>{@code usage_reservations}: closed (SETTLED or RELEASED) rows 13 months after {@code closed_at}. They hold the
 * Idempotency-Key and the split of a charge; the key only has to be honoured for 24 hours (SD 8.3) and
 * {@code /me/usage} sums them per window (at most a month), so the SD's 13-month usage history (ADR 0011) is the
 * safe upper bound. An open (RESERVED) row is never touched: the expiry sweeper closes it first.</li>
 * <li>{@code usage_events} and the credit ledger ({@code ai_credit_accounts}, {@code ai_credit_lots},
 * {@code ai_credit_transactions}): <b>kept</b>. They are append-only ledgers of what was metered and what credits were
 * bought, granted and spent (SD 15, 20.1); credits are part of the payment trail (SD 19.2 "as required by tax law, assume
 * 10 years"), and the SD gives usage events no shorter period. They stay attached to the pseudonymised account id
 * (ADR 0015 D3).</li>
 * <li>{@code usage_counters}: <b>kept</b>: one small aggregate row per user, feature and window, no period in the SD.</li>
 * </ul>
 */
@Component
class UsageRetention implements RetentionParticipant {

	static final String RESERVATIONS = "usage-reservations";
	static final String USAGE_EVENTS = "usage-events";
	static final String CREDIT_LEDGER = "ai-credit-ledger";
	static final String COUNTERS = "usage-counters";

	private final JdbcClient jdbc;

	UsageRetention(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int order() {
		return 400;
	}

	@Override
	public List<RetentionRule> rules() {
		return List.of(
				new RetentionRule(RESERVATIONS, "usage", RetentionAction.DELETE, Duration.ofDays(396),
						"UNDEFINED; ADR 0011 (13-month usage history)",
						"usage_reservations with status SETTLED or RELEASED, counted from closed_at"),
				new RetentionRule(USAGE_EVENTS, "usage", RetentionAction.KEEP, null, "UNDEFINED",
						"usage_events: append-only metering ledger; no period in the SD"),
				new RetentionRule(CREDIT_LEDGER, "usage", RetentionAction.KEEP, Duration.ofDays(3650),
						"SD 19.2 (financial records, assumed 10 years)",
						"ai_credit_accounts, ai_credit_lots, ai_credit_transactions"),
				new RetentionRule(COUNTERS, "usage", RetentionAction.KEEP, null, "UNDEFINED",
						"usage_counters: one aggregate row per user, feature and window"));
	}

	@Override
	public int purge(RetentionRule rule, Instant now, Instant cutoff, int limit) {
		if (!RESERVATIONS.equals(rule.code())) {
			throw new IllegalArgumentException("Not an executable usage rule: " + rule.code());
		}
		return RetentionSupport.deleteBatch(jdbc, "usage_reservations", "id",
				"reservation_status <> 'RESERVED' AND closed_at < :cutoff", now, cutoff, limit);
	}
}
