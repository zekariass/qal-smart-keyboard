package com.qalsmartkeyboard.billing;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.qalsmartkeyboard.billing.PaymentVerifier.Context;

import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a verified, paid order into a subscription term (SD 15.5, 16.5, 16.9 step 5). Runs inside the verifier's
 * transaction, after the order row is locked and set PAID.
 *
 * <ul>
 * <li><b>New plan:</b> a {@code user_subscriptions} row, source PAYMENT, billing mode MANUAL_RENEW, ACTIVE for one term
 * from now (30 days for MONTHLY, 365 for ANNUAL), bound to the provider, account and methods of the paying attempt;
 * allocation INITIAL; event ACTIVATED.</li>
 * <li><b>Same plan already ACTIVE or in GRACE_PERIOD</b> (stacking, SD 15.5): the term starts at the current period end
 * if that is still ahead, else now; allocation RENEWAL; event EXTENDED (or RENEWED after a lapse).</li>
 * <li>A receipt with the next per-market, per-year number ({@code ET-2026-000001}).</li>
 * </ul>
 * Entitlements derive from these rows; this class never writes entitlement tables and never looks at the payer's
 * current country (a term bought in Ethiopia keeps working abroad).
 */
final class Fulfilment {

	private final JdbcTemplate jdbc;
	private final JsonMapper json;

	Fulfilment(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** @return the subscription the order paid for */
	UUID fulfil(Context ctx, UUID captureTransactionId, Instant now) {
		Map<String, Object> order = jdbc.queryForMap("""
				SELECT o.order_type, o.billing_product_id, o.billing_price_id, o.market_country_code, o.currency_code,
				       o.amount_minor, o.tax_minor, pr.billing_period, pr.tax_behavior, bp.plan_id
				FROM payment_orders o
				JOIN billing_prices pr ON pr.id = o.billing_price_id
				JOIN billing_products bp ON bp.id = o.billing_product_id
				WHERE o.id = ?""", ctx.orderId());
		if (!"SUBSCRIPTION_PURCHASE".equals(order.get("order_type")) && !"SUBSCRIPTION_RENEWAL".equals(order.get("order_type"))) {
			throw new IllegalStateException("only subscription orders are fulfilled in this step");
		}
		Duration term = OrderPricing.term((String) order.get("billing_period"));
		if (term == null) {
			throw new IllegalStateException("billing period without a term");
		}
		UUID planId = (UUID) order.get("plan_id");

		Map<String, Object> existing = jdbc.query("""
				SELECT id, subscription_status, current_period_end
				FROM user_subscriptions
				WHERE user_id = ? AND plan_id = ? AND source_type = 'PAYMENT' AND billing_mode = 'MANUAL_RENEW'
				  AND subscription_status IN ('ACTIVE', 'GRACE_PERIOD')
				ORDER BY current_period_end DESC NULLS LAST
				LIMIT 1
				FOR UPDATE""", (rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("status", rs.getString(2));
			m.put("end", JdbcRoutingData.instant(rs, 3));
			return m;
		}, ctx.userId(), planId).stream().findFirst().orElse(null);

		UUID subscriptionId;
		Instant start;
		Instant end;
		String allocationType;
		String eventType;
		String previousStatus;
		if (existing != null) {
			subscriptionId = (UUID) existing.get("id");
			previousStatus = (String) existing.get("status");
			Instant currentEnd = (Instant) existing.get("end");
			boolean stacking = currentEnd != null && currentEnd.isAfter(now);
			start = stacking ? currentEnd : now;
			end = start.plus(term);
			allocationType = "RENEWAL";
			eventType = stacking ? "EXTENDED" : "RENEWED";
			jdbc.update("""
					UPDATE user_subscriptions
					   SET subscription_status = 'ACTIVE', current_period_start = CASE WHEN ? THEN current_period_start ELSE ? END,
					       current_period_end = ?, grace_period_end = NULL, last_verified_at = ?, updated_at = ?
					 WHERE id = ?""", stacking, OrderService.utc(start), OrderService.utc(end), OrderService.utc(now),
					OrderService.utc(now), subscriptionId);
		}
		else {
			subscriptionId = UUID.randomUUID();
			previousStatus = null;
			start = now;
			end = now.plus(term);
			allocationType = "INITIAL";
			eventType = "ACTIVATED";
			jdbc.update("""
					INSERT INTO user_subscriptions (id, user_id, plan_id, billing_product_id, billing_price_id, source_type,
					       billing_mode, provider_id, provider_account_id, provider_payment_method_id,
					       provider_account_method_id, subscription_status, current_period_start, current_period_end,
					       auto_renew_enabled, started_at, last_verified_at, created_at, updated_at)
					VALUES (?, ?, ?, ?, ?, 'PAYMENT', 'MANUAL_RENEW', ?, ?, ?, ?, 'ACTIVE', ?, ?, FALSE, ?, ?, ?, ?)""",
					subscriptionId, ctx.userId(), planId, order.get("billing_product_id"), order.get("billing_price_id"),
					ctx.providerId(), ctx.accountId(), ctx.baseMethodId(), ctx.accountMethodId(),
					OrderService.utc(start), OrderService.utc(end), OrderService.utc(now), OrderService.utc(now),
					OrderService.utc(now), OrderService.utc(now));
		}
		jdbc.update("""
				INSERT INTO subscription_payment_allocations (user_id, subscription_id, payment_order_id, allocation_type,
				       period_start, period_end)
				VALUES (?, ?, ?, ?, ?, ?)""", ctx.userId(), subscriptionId, ctx.orderId(), allocationType,
				OrderService.utc(start), OrderService.utc(end));
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("attemptId", ctx.attemptId().toString());
		details.put("providerCode", ctx.providerCode());
		details.put("periodStart", start.toString());
		details.put("periodEnd", end.toString());
		jdbc.update("""
				INSERT INTO subscription_events (subscription_id, payment_order_id, event_type, previous_status, new_status,
				       actor_type, event_details)
				VALUES (?, ?, ?, ?, 'ACTIVE', 'SYSTEM', CAST(? AS JSONB))""", subscriptionId, ctx.orderId(), eventType,
				previousStatus, json.writeValueAsString(details));

		String market = (String) order.get("market_country_code");
		int year = now.atOffset(ZoneOffset.UTC).getYear();
		long next = jdbc.queryForObject("""
				INSERT INTO billing_receipt_sequences (market_country_code, receipt_year, last_value)
				VALUES (?, ?, 1)
				ON CONFLICT (market_country_code, receipt_year)
				DO UPDATE SET last_value = billing_receipt_sequences.last_value + 1, updated_at = NOW()
				RETURNING last_value""", Long.class, market, year);
		jdbc.update("""
				INSERT INTO payment_receipts (payment_order_id, payment_transaction_id, market_country_code, receipt_number,
				       currency_code, amount_minor, tax_minor, tax_behavior, issued_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", ctx.orderId(), captureTransactionId, market,
				String.format("%s-%d-%06d", market, year, next), order.get("currency_code"), order.get("amount_minor"),
				order.get("tax_minor"), order.get("tax_behavior"), OrderService.utc(now));
		return subscriptionId;
	}
}
