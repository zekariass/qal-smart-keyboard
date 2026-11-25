package com.qalsmartkeyboard.privacy;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.ExportRows;
import com.qalsmartkeyboard.identity.ExportWriter;
import com.qalsmartkeyboard.identity.UserExportParticipant;

/**
 * Data export (B6.1, ADR 0022) for per-user tables whose owning module has no code yet (the export twin of
 * {@link UnownedTablesDeletion}): <b>when a module starts writing one of these tables it takes the section over into
 * its own participant and removes it here</b>.
 * <ul>
 * <li>Billing (receipts, SD 19.4 "subscriptions and receipts"): {@code payment_orders} (reference, amounts, status) and
 * {@code store_purchases} (store, product mapping, transaction ids, status, dates). Left out: the purchase token (hash
 * and ciphertext, a credential), idempotency keys and provider metadata.</li>
 * <li>Voice: {@code voice_jobs} and {@code realtime_sessions} (metadata, no audio). Left out: the audio object key, the
 * session ticket hash and provider metadata.</li>
 * <li>Organisations: {@code organization_members} (which organisations the person belongs to, role, status).</li>
 * <li>Not exported: {@code user_admin_roles} (an internal authorisation record, not personal content); payment
 * attempts, transactions, mandates and allocations (billing, M4: the billing module adds them with its participant;
 * until then the manifest says so).</li>
 * </ul>
 */
@Component
class UnownedTablesExport implements UserExportParticipant {

	private final JdbcClient jdbc;

	UnownedTablesExport(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int exportOrder() {
		return UNOWNED;
	}

	@Override
	public void exportUser(UUID userId, ExportWriter out) {
		Map<String, Object> user = Map.of("u", userId);
		out.section("paymentOrders", "Financial records", "Orders and receipts: reference, product, amounts, status and "
				+ "times.", ExportRows.query(jdbc, """
						SELECT o.id, o.order_reference, p.product_code, o.order_type, o.sales_channel,
						       o.market_country_code, o.currency_code, o.list_amount_minor, o.discount_minor, o.tax_minor,
						       o.amount_minor, o.order_status, o.expires_at, o.paid_at, o.cancelled_at, o.created_at,
						       o.updated_at
						FROM payment_orders o JOIN billing_products p ON p.id = o.billing_product_id
						WHERE o.user_id = :u ORDER BY o.created_at, o.id
						""", user));
		out.section("storePurchases", "Financial records", "App-store purchases: store, transaction ids, status and "
				+ "dates (the purchase token is not included).", ExportRows.query(jdbc, """
						SELECT id, provider_type, purchase_type, store_transaction_id, original_transaction_id,
						       latest_transaction_id, environment,
						       storefront_country_code, purchase_status, auto_renewing, purchased_at, expires_at,
						       acknowledged_at, last_verified_at, created_at, updated_at
						FROM store_purchases WHERE user_id = :u ORDER BY purchased_at, id
						""", user));
		out.section("voiceJobs", "Voice / realtime metadata", "Speech job metadata (no audio): feature, languages, "
				+ "status, durations and counts.", ExportRows.query(jdbc, """
						SELECT j.id, j.device_id, j.ai_request_id, j.feature_code, s.language_code AS source_language,
						       d.language_code AS destination_language, j.job_status, j.audio_duration_ms,
						       j.audio_size_bytes, j.billed_seconds, j.transcript_character_count, j.error_code,
						       j.created_at, j.updated_at, j.completed_at
						FROM voice_jobs j
						LEFT JOIN supported_languages s ON s.id = j.source_language_id
						LEFT JOIN supported_languages d ON d.id = j.destination_language_id
						WHERE j.user_id = :u ORDER BY j.created_at, j.id
						""", user));
		out.section("realtimeSessions", "Voice / realtime metadata", "Conversation session metadata (no audio): "
				+ "languages, status, duration and turns.", ExportRows.query(jdbc, """
						SELECT r.id, r.device_id, r.feature_code, a.language_code AS language_a, b.language_code AS language_b,
						       r.session_status, r.started_at, r.ended_at, r.end_reason, r.billed_seconds, r.turn_count,
						       r.created_at, r.updated_at
						FROM realtime_sessions r
						JOIN supported_languages a ON a.id = r.language_a_id
						JOIN supported_languages b ON b.id = r.language_b_id
						WHERE r.user_id = :u ORDER BY r.created_at, r.id
						""", user));
		out.section("organizationMemberships", "Account and preferences", "Organisations you belong to: role, status "
				+ "and join date.", ExportRows.query(jdbc, """
						SELECT m.id, m.organization_id, o.display_name AS organization, m.member_role, m.member_status,
						       m.joined_at
						FROM organization_members m JOIN organizations o ON o.id = m.organization_id
						WHERE m.user_id = :u ORDER BY m.joined_at, m.id
						""", user));
		out.excluded("billingRecords", "Payment attempts, transactions, mandates and payment allocations are not exported yet: "
				+ "no code writes them today, and the billing module that does (M4) adds them to the export with its "
				+ "own participant.");
		out.excluded("adminRoles", "Admin roles are an internal authorisation record, not personal content; they are not "
				+ "exported.");
	}
}
