package com.qalsmartkeyboard.usage;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.ExportRows;
import com.qalsmartkeyboard.identity.ExportWriter;
import com.qalsmartkeyboard.identity.UserExportParticipant;

/**
 * Data export (B6.1, ADR 0022) of the usage tables: the usage counters (allowance windows), the append-only usage events
 * (metering ledger) and the credit accounts, lots and ledger. Left out: open reservations ({@code usage_reservations}:
 * transient window state that settles or expires within minutes) and the id of the admin who granted a credit lot.
 */
@Component
class UsageExport implements UserExportParticipant {

	private final JdbcClient jdbc;

	UsageExport(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int exportOrder() {
		return USAGE;
	}

	@Override
	public void exportUser(UUID userId, ExportWriter out) {
		Map<String, Object> user = Map.of("u", userId);
		out.section("usageCounters", "Metering state", "Allowance usage per feature and window.", ExportRows.query(jdbc, """
				SELECT id, feature_code, period_start, period_end, used_units, reserved_units, updated_at
				FROM usage_counters WHERE user_id = :u AND subject_type = 'USER' ORDER BY period_start, feature_code, id
				""", user));
		out.section("usageEvents", "Metering state", "The metering ledger: reservations, settlements and releases.",
				ExportRows.query(jdbc, """
						SELECT id, event_type, reservation_id, usage_counter_id, feature_code, units, ai_request_id,
						       expires_at, created_at
						FROM usage_events WHERE subject_type = 'USER' AND subject_id = :u ORDER BY created_at, id
						""", user));
		out.section("creditAccounts", "Financial records", "AI credit accounts and balances.", ExportRows.query(jdbc, """
				SELECT id, currency_unit, balance_units, created_at, updated_at
				FROM ai_credit_accounts WHERE user_id = :u ORDER BY created_at, id
				""", user));
		out.section("creditLots", "Financial records", "Credit lots: source, initial and remaining units, expiry.",
				ExportRows.query(jdbc, """
						SELECT l.id, l.credit_account_id, l.lot_source, l.initial_units, l.remaining_units, l.expires_at,
						       l.payment_order_id, l.store_purchase_id, l.created_at, l.updated_at
						FROM ai_credit_lots l JOIN ai_credit_accounts a ON a.id = l.credit_account_id
						WHERE a.user_id = :u ORDER BY l.created_at, l.id
						""", user));
		out.section("creditTransactions", "Financial records", "The credit ledger: every grant, spend and expiry.",
				ExportRows.query(jdbc, """
						SELECT t.id, t.credit_account_id, t.lot_id, t.ai_request_id, t.payment_order_id,
						       t.transaction_type, t.delta_units, t.balance_after, t.description, t.created_at
						FROM ai_credit_transactions t JOIN ai_credit_accounts a ON a.id = t.credit_account_id
						WHERE a.user_id = :u ORDER BY t.created_at, t.id
						""", user));
		out.excluded("usageReservations", "Open usage reservations are transient window state (they settle or expire "
				+ "within minutes); their outcome is in the usage events.");
	}
}
