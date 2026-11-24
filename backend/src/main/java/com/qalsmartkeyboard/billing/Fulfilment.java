package com.qalsmartkeyboard.billing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.qalsmartkeyboard.billing.PaymentVerifier.Context;
import com.qalsmartkeyboard.usage.CreditLot;
import com.qalsmartkeyboard.usage.CreditSource;
import com.qalsmartkeyboard.usage.Credits;

import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a verified, paid order into what it bought (SD 15.4-15.6, 16.5, 16.9 step 5). Runs inside the verifier's
 * transaction, after the order row is locked and set PAID, so the grant commits or rolls back with the PAID transition.
 *
 * <p><b>Subscription orders</b> ({@code SUBSCRIPTION_PURCHASE} / {@code _RENEWAL}):
 * <ul>
 * <li><b>New plan:</b> a {@code user_subscriptions} row, source PAYMENT, billing mode MANUAL_RENEW, ACTIVE for one term
 * from now (30 days for MONTHLY, 365 for ANNUAL), bound to the provider, account and methods of the paying attempt;
 * allocation INITIAL; event ACTIVATED.</li>
 * <li><b>Same plan already ACTIVE or in GRACE_PERIOD</b> (stacking, SD 15.5): the term starts at the current period end
 * if that is still ahead, else now; allocation RENEWAL; event EXTENDED (or RENEWED after a lapse).</li>
 * </ul>
 * <p><b>Credit pack orders</b> ({@code AI_CREDIT_TOPUP}, T8.10): exactly one {@code ai_credit_lots} row through
 * {@link Credits#grant} (source PURCHASE, reference = the order id, so the ledger's deterministic lot id makes a replay
 * a no-op), of the units and validity snapshotted on the order when it was created; the lot expires {@code validity}
 * days after the payment is verified. Its opening ledger row is the PURCHASE transaction.
 *
 * <p>Both get a receipt with the next per-market, per-year number ({@code ET-2026-000001}). Entitlements derive from
 * these rows; this class never writes entitlement tables and never looks at the payer's current country (a term or
 * credits bought in Ethiopia keep working abroad).
 */
final class Fulfilment {

	/** SD 15.4: credit lots default to 365 days when a pack has no validity of its own. */
	static final int DEFAULT_CREDIT_VALIDITY_DAYS = 365;

	private static final Logger log = LoggerFactory.getLogger(Fulfilment.class);

	/** What an order produced: a subscription term or a credit lot (exactly one is set). */
	record Fulfilled(UUID subscriptionId, UUID creditLotId) {
	}

	private final JdbcTemplate jdbc;
	private final Credits credits;
	private final JsonMapper json;

	Fulfilment(JdbcTemplate jdbc, Credits credits, JsonMapper json) {
		this.jdbc = jdbc;
		this.credits = credits;
		this.json = json;
	}

	Fulfilled fulfil(Context ctx, UUID captureTransactionId, Instant now) {
		Map<String, Object> order = jdbc.queryForMap("""
				SELECT o.order_type, o.billing_product_id, o.billing_price_id, o.market_country_code, o.currency_code,
				       o.amount_minor, o.tax_minor, pr.billing_period, pr.tax_behavior, bp.plan_id,
				       o.metadata->>'creditUnits' AS credit_units, (o.metadata->>'creditValidityDays')::INT AS validity,
				       bp.credit_units AS product_credit_units, bp.credit_validity_days AS product_validity
				FROM payment_orders o
				JOIN billing_prices pr ON pr.id = o.billing_price_id
				JOIN billing_products bp ON bp.id = o.billing_product_id
				WHERE o.id = ?""", ctx.orderId());
		String type = (String) order.get("order_type");
		Fulfilled result;
		if ("AI_CREDIT_TOPUP".equals(type)) {
			result = new Fulfilled(null, creditLot(ctx, order, now));
		}
		else if ("SUBSCRIPTION_PURCHASE".equals(type) || "SUBSCRIPTION_RENEWAL".equals(type)) {
			result = new Fulfilled(subscription(ctx, order, now), null);
		}
		else {
			throw new IllegalStateException("order type " + type + " cannot be fulfilled");
		}
		receipt(ctx, captureTransactionId, order, now);
		return result;
	}

	/**
	 * Reverses what is left of a refunded credit-pack order's lot (SD 15.4, 15.6 "Refunds"): one REVERSAL ledger row for
	 * the unconsumed remainder, the lot emptied; consumed credits stay consumed and nothing goes below zero. Idempotent
	 * (a second call reverses 0). To be called by the refund flow in the same transaction that moves the order to
	 * {@code REFUNDED} / {@code PARTIALLY_REFUNDED}; no customer-refund flow for gateway orders exists yet (ADR 0045 A2
	 * only refunds duplicate captures, which never granted anything).
	 *
	 * @return the credits taken back (0 for a replay, an order without a lot, or a fully used lot)
	 */
	BigDecimal reverseCreditTopUp(UUID orderId) {
		Map<String, Object> order = jdbc.query("SELECT user_id, order_type FROM payment_orders WHERE id = ?",
				(rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("user", rs.getObject(1, UUID.class));
					m.put("type", rs.getString(2));
					return m;
				}, orderId).stream().findFirst().orElse(null);
		if (order == null || !"AI_CREDIT_TOPUP".equals(order.get("type"))) {
			throw new IllegalArgumentException("not a credit-pack order: " + orderId);
		}
		BigDecimal reversed = credits.reverse((UUID) order.get("user"), CreditSource.PURCHASE, orderId.toString());
		log.info("billing credit pack reversed orderId={} credits={}", orderId, reversed);
		return reversed;
	}

	private UUID creditLot(Context ctx, Map<String, Object> order, Instant now) {
		// one lot per order, whatever happens: also a guard in front of the ledger's own replay check
		UUID existing = jdbc.query("""
				SELECT l.id FROM ai_credit_lots l
				WHERE l.payment_order_id = ? AND l.lot_source = 'PURCHASE'""", (rs, i) -> rs.getObject(1, UUID.class),
				ctx.orderId()).stream().findFirst().orElse(null);
		if (existing != null) {
			log.warn("billing credit pack already granted orderId={} lotId={} (no second lot)", ctx.orderId(), existing);
			return existing;
		}
		String snapshot = (String) order.get("credit_units");
		BigDecimal units = snapshot != null ? new BigDecimal(snapshot) : (BigDecimal) order.get("product_credit_units");
		Integer validity = order.get("validity") != null ? (Integer) order.get("validity")
				: (Integer) order.get("product_validity");
		if (units == null || units.signum() <= 0) {
			throw new IllegalStateException("credit pack order without credit units: " + ctx.orderId());
		}
		int days = validity == null ? DEFAULT_CREDIT_VALIDITY_DAYS : validity;
		CreditLot lot = credits.grant(ctx.userId(), units, CreditSource.PURCHASE, ctx.orderId().toString(),
				now.plus(Duration.ofDays(days)));
		log.info("billing credit pack granted orderId={} lotId={} units={} validityDays={} created={}", ctx.orderId(),
				lot.id(), units, days, lot.created());
		return lot.id();
	}

	private UUID subscription(Context ctx, Map<String, Object> order, Instant now) {
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
		return subscriptionId;
	}

	private void receipt(Context ctx, UUID captureTransactionId, Map<String, Object> order, Instant now) {
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
	}
}
