package com.qalsmartkeyboard.billing;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.qalsmartkeyboard.administration.AuditTrail;
import com.qalsmartkeyboard.billing.ProviderRouting.Decision;
import com.qalsmartkeyboard.billing.spi.StoreBillingProvider;
import com.qalsmartkeyboard.billing.spi.StoreCustomer;
import com.qalsmartkeyboard.billing.spi.StoreNonSubscription;
import com.qalsmartkeyboard.billing.spi.StoreNotification;
import com.qalsmartkeyboard.platform.geo.CountryResolution.Confidence;
import com.qalsmartkeyboard.usage.CreditLot;
import com.qalsmartkeyboard.usage.Credits;

import tools.jackson.databind.json.JsonMapper;

/**
 * Store-billed AI credit packs (T8.10 follow-up, owner rule 2026-11-25; ADR 0046, ADR 0048 section 4): one-time store
 * purchases (Play consumables through RevenueCat) become {@code store_purchases} rows of type CONSUMABLE and exactly one
 * {@code ai_credit_lots} row each (V32). Called by {@link StorePurchases#sync} inside its transaction and per-user lock,
 * with the customer the provider returned server to server: a client report or a webhook body never grants anything.
 *
 * <p><b>Idempotency.</b> The key is the store transaction id (Play order id), unique per provider in
 * {@code store_purchases} (and in {@code purchase_token_hash}); the lot id is derived from the store purchase id and the
 * lot's {@code store_purchase_id} is unique. A client report, a {@code NON_RENEWING_PURCHASE} webhook, replays and
 * concurrent calls for the same user serialise on the sync's per-user advisory lock and all find the same row; two users
 * racing for the same transaction (a RevenueCat transfer) hit the unique key ({@code ON CONFLICT DO NOTHING}) and only
 * the first owner is ever granted.
 *
 * <p><b>Grant gate</b> (a purchase is granted only when, at grant time): the transaction is of the environment this
 * server honours, has a store transaction id, its store product maps (through an ACTIVE mapping) to an ACTIVE
 * {@code AI_CREDIT_PACK} whose market (the mapping's price row, {@code ZZ} for the global store catalogue) offers AI
 * credits ({@code fn_billing_store_pack_sellable}), and that market's policy for the mapping's sales channel is an
 * approved {@code STORE_BILLING} flow offering this provider ({@link ProviderRouting}). Otherwise the verified purchase
 * is kept PENDING with its {@code blockedReason} (re-checked on every later sync, so it is granted once the gate opens)
 * and the report is refused. The lot has the pack's units and validity as snapshotted when the purchase was first
 * verified and expires {@code validity} days after the store's purchase time.
 *
 * <p><b>Refunds.</b> A granted purchase is REFUNDED, and the unused remainder of its lot reversed through
 * {@link Credits#reverseStorePurchase} (consumed credits stay consumed, never negative, idempotent), when the provider
 * reports the transaction refunded ({@code refunded_at}) or, for a {@code CANCELLATION} event naming the transaction,
 * no longer lists it. A cancellation the provider still contradicts is counted as unconfirmed: the webhook's processing
 * fails with {@code REFUND_NOT_CONFIRMED} and is retried by the outbox (its transaction, this sync included, rolls
 * back), never applied from the body. Every change writes an append-only {@code store_purchase_events} row; grants and refunds
 * are also in {@code audit_events} (actor PROVIDER).
 */
final class StoreConsumables {

	static final String PURCHASE_TYPE = "CONSUMABLE";
	static final String BLOCKED_POLICY = "POLICY_DENIED";
	static final String BLOCKED_NOT_SELLABLE = "NOT_SELLABLE";
	static final String BLOCKED_MAPPING_INACTIVE = "MAPPING_INACTIVE";
	static final String AUDIT_GRANT = "BILLING_STORE_CREDIT_PACK_GRANT";
	static final String AUDIT_REFUND = "BILLING_STORE_CREDIT_PACK_REFUND";
	private static final Set<String> REFUND_EVENTS = Set.of("CANCELLATION");
	private static final Logger log = LoggerFactory.getLogger(StoreConsumables.class);

	/** One consumable of the user after a sync. */
	record Consumable(UUID storePurchaseId, String productId, String productCode, String storeTransactionId,
			String purchaseStatus, String environment, Instant purchasedAt, Instant acknowledgedAt, UUID creditLotId,
			BigDecimal creditUnits, Instant creditsExpireAt, String blockedReason) {
	}

	/** A sync's consumable outcome. */
	record Result(List<Consumable> consumables, int unmapped, Set<String> otherEnvironment, int unconfirmedRefunds) {
	}

	private record PackMapping(UUID mappingId, UUID productId, String productCode, boolean active, String market,
			String salesChannel, BigDecimal units, Integer validityDays) {
	}

	private record Row(UUID id, UUID userId, String status, UUID productId, String productCode, Instant purchasedAt,
			Instant acknowledgedAt, String environment, String units, Integer validityDays, String blockedReason) {
	}

	private final JdbcTemplate jdbc;
	private final Credits credits;
	private final ProviderRouting routing;
	private final AuditTrail audit;
	private final JsonMapper json;

	StoreConsumables(JdbcTemplate jdbc, Credits credits, ProviderRouting routing, AuditTrail audit, JsonMapper json) {
		this.jdbc = jdbc;
		this.credits = credits;
		this.routing = routing;
		this.audit = audit;
		this.json = json;
	}

	/** Applies the customer's one-time purchases; runs in the sync's transaction under its per-user lock. */
	Result apply(UUID userId, StoreBillingProvider provider, UUID providerId, StoreCustomer customer, Instant now,
			String trigger, UUID webhookEventId, StoreNotification event) {
		List<Consumable> out = new ArrayList<>();
		Set<String> otherEnvironment = new HashSet<>();
		Set<String> listed = new HashSet<>();
		Set<String> eventTransactions = event == null ? Set.of() : Set.copyOf(event.transactionIds());
		int unmapped = 0;
		for (StoreNonSubscription t : customer.nonSubscriptions()) {
			if (!provider.supports(t.environment())) {
				otherEnvironment.add(t.productId());
				continue;
			}
			PackMapping mapping = mapping(providerId, t).orElse(null);
			if (mapping == null) {
				unmapped++;
				log.warn("store one-time purchase skipped: product not mapped to a credit pack provider={} product={}",
						provider.providerCode(), t.productId());
				continue;
			}
			if (t.storeTransactionId() == null) {
				unmapped++;
				log.warn("store one-time purchase skipped: no store transaction id provider={} product={}",
						provider.providerCode(), t.productId());
				continue;
			}
			listed.add(t.storeTransactionId());
			boolean named = eventTransactions.contains(t.storeTransactionId());
			Consumable c = upsert(userId, provider, providerId, mapping, t, now, trigger, named ? webhookEventId : null,
					named ? event : null);
			if (c != null) {
				out.add(c);
			}
		}
		int unconfirmed = 0;
		if (event != null && REFUND_EVENTS.contains(event.eventType())) {
			for (String transaction : eventTransactions) {
				Row row = row(providerId, transaction).orElse(null);
				if (row == null || !row.userId().equals(userId)) {
					continue;
				}
				if (!listed.contains(transaction)) {
					// the provider confirms: the refunded one-time purchase is gone from the customer
					if ("ACTIVE".equals(row.status()) || "PENDING".equals(row.status())) {
						refund(row, now, trigger, webhookEventId, event, "NOT_LISTED_AFTER_CANCELLATION");
					}
				}
				else if ("ACTIVE".equals(row.status())) {
					// the webhook is retried (the outbox transaction, and with it this sync, rolls back)
					unconfirmed++;
					log.warn("store refund event not confirmed by the provider storePurchaseId={} (nothing reversed)",
							row.id());
				}
			}
		}
		return new Result(List.copyOf(out), unmapped, Set.copyOf(otherEnvironment), unconfirmed);
	}

	private Consumable upsert(UUID userId, StoreBillingProvider provider, UUID providerId, PackMapping mapping,
			StoreNonSubscription t, Instant now, String trigger, UUID webhookEventId, StoreNotification event) {
		Row row = row(providerId, t.storeTransactionId()).orElse(null);
		if (row == null) {
			insert(userId, provider, providerId, mapping, t, now, trigger);
			row = row(providerId, t.storeTransactionId()).orElseThrow();
			if (row.userId().equals(userId)) {
				purchaseEvent(row.id(), webhookEventId, event != null ? event.eventType() : trigger, null, "PENDING",
						now, Map.of("trigger", trigger), event, false);
			}
		}
		if (!row.userId().equals(userId)) {
			// a transaction is granted to its first verified owner only (e.g. transferred to another app user id)
			log.warn("store one-time purchase belongs to another account storePurchaseId={} (not granted again)",
					row.id());
			return null;
		}
		if (t.refundedAt() != null && ("ACTIVE".equals(row.status()) || "PENDING".equals(row.status()))) {
			refund(row, now, trigger, webhookEventId, event, "REFUNDED_AT");
		}
		else if ("PENDING".equals(row.status())) {
			grantOrBlock(row, mapping, provider, now, trigger, webhookEventId, event);
		}
		else if (event != null) {
			purchaseEvent(row.id(), webhookEventId, event.eventType(), row.status(), row.status(), now,
					Map.of("trigger", trigger), event, true);
		}
		jdbc.update("UPDATE store_purchases SET last_verified_at = ?, updated_at = ? WHERE id = ?",
				OrderService.utc(now), OrderService.utc(now), row.id());
		return consumable(row(providerId, t.storeTransactionId()).orElseThrow(), t.productId(), t.storeTransactionId());
	}

	private void insert(UUID userId, StoreBillingProvider provider, UUID providerId, PackMapping mapping,
			StoreNonSubscription t, Instant now, String trigger) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("store", t.store());
		metadata.put("storeProductId", t.productId());
		metadata.put("providerTransactionId", t.providerId());
		metadata.put("productCode", mapping.productCode());
		// what the purchase grants is fixed when it is first verified (as an order snapshots it)
		metadata.put("creditUnits", mapping.units().stripTrailingZeros().toPlainString());
		metadata.put("creditValidityDays",
				mapping.validityDays() == null ? Fulfilment.DEFAULT_CREDIT_VALIDITY_DAYS : mapping.validityDays());
		metadata.put("firstSyncTrigger", trigger);
		metadata.values().removeIf(Objects::isNull);
		String hash = BillingErrors.sha256Hex(provider.providerCode() + "|" + PURCHASE_TYPE + "|"
				+ Objects.toString(t.store(), "") + "|" + t.storeTransactionId());
		jdbc.update("""
				INSERT INTO store_purchases (id, user_id, provider_id, provider_type, external_product_mapping_id,
				       purchase_token_hash, purchase_type, billing_product_id, store_transaction_id, environment,
				       purchase_status, auto_renewing, purchased_at, last_verified_at, metadata, created_at, updated_at)
				VALUES (?, ?, ?, 'APP_STORE', ?, ?, 'CONSUMABLE', ?, ?, ?, 'PENDING', FALSE, ?, ?, CAST(? AS JSONB), ?, ?)
				ON CONFLICT DO NOTHING""", UUID.randomUUID(), userId, providerId, mapping.mappingId(), hash,
				mapping.productId(), t.storeTransactionId(), t.environment().name(),
				OrderService.utc(t.purchasedAt() != null ? t.purchasedAt() : now), OrderService.utc(now),
				json.writeValueAsString(metadata), OrderService.utc(now), OrderService.utc(now));
	}

	private void grantOrBlock(Row row, PackMapping mapping, StoreBillingProvider provider, Instant now,
			String trigger, UUID webhookEventId, StoreNotification event) {
		String blocked = blockedReason(row, mapping, provider);
		if (blocked != null) {
			if (!blocked.equals(row.blockedReason())) {
				jdbc.update("""
						UPDATE store_purchases
						   SET metadata = metadata || jsonb_build_object('blockedReason', CAST(? AS TEXT)), updated_at = ?
						 WHERE id = ?""", blocked, OrderService.utc(now), row.id());
				purchaseEvent(row.id(), webhookEventId, "GRANT_BLOCKED", "PENDING", "PENDING", now,
						Map.of("trigger", trigger, "reason", blocked), event, false);
			}
			log.info("store credit pack not granted storePurchaseId={} reason={}", row.id(), blocked);
			return;
		}
		BigDecimal units = new BigDecimal(row.units());
		int days = row.validityDays() == null ? Fulfilment.DEFAULT_CREDIT_VALIDITY_DAYS : row.validityDays();
		Instant expires = row.purchasedAt().plus(Duration.ofDays(days));
		if (!expires.isAfter(now)) {
			// verified so late that the credits would already have expired: nothing to grant
			jdbc.update("""
					UPDATE store_purchases
					   SET purchase_status = 'EXPIRED', metadata = metadata || '{"blockedReason": "EXPIRED_BEFORE_GRANT"}'::JSONB,
					       updated_at = ?
					 WHERE id = ?""", OrderService.utc(now), row.id());
			purchaseEvent(row.id(), webhookEventId, "EXPIRED_BEFORE_GRANT", "PENDING", "EXPIRED", now,
					Map.of("trigger", trigger), event, false);
			log.warn("store credit pack verified after its validity storePurchaseId={} (no lot)", row.id());
			return;
		}
		CreditLot lot = credits.grantStorePurchase(row.userId(), units, row.id(), expires);
		jdbc.update("""
				UPDATE store_purchases
				   SET purchase_status = 'ACTIVE', acknowledged_at = COALESCE(acknowledged_at, ?),
				       metadata = (metadata - 'blockedReason') || jsonb_build_object('grantedBy', CAST(? AS TEXT)),
				       updated_at = ?
				 WHERE id = ?""", OrderService.utc(now), trigger, OrderService.utc(now), row.id());
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("trigger", trigger);
		details.put("creditLotId", lot.id().toString());
		details.put("creditUnits", units.stripTrailingZeros().toPlainString());
		details.put("creditsExpireAt", expires.toString());
		purchaseEvent(row.id(), webhookEventId, "GRANTED", "PENDING", "ACTIVE", now, details, event, false);
		Map<String, Object> meta = new LinkedHashMap<>(details);
		meta.put("productCode", row.productCode());
		meta.put("userId", row.userId().toString());
		audit.recordAs("PROVIDER", new AuditTrail.Entry(null, null, AUDIT_GRANT, "store_purchases",
				row.id().toString(), AuditTrail.Outcome.SUCCESS, meta));
		log.info("store credit pack granted storePurchaseId={} lotId={} units={} validityDays={} created={} trigger={}",
				row.id(), lot.id(), units, days, lot.created(), trigger);
	}

	/** Why a verified purchase may not be granted now, or null. */
	private String blockedReason(Row row, PackMapping mapping, StoreBillingProvider provider) {
		if (!mapping.active()) {
			return BLOCKED_MAPPING_INACTIVE;
		}
		Boolean sellable = jdbc.queryForObject("SELECT fn_billing_store_pack_sellable(?, ?, ?, ?)", Boolean.class,
				row.productId(), mapping.market(), mapping.salesChannel(), row.environment());
		if (!Boolean.TRUE.equals(sellable)) {
			return BLOCKED_NOT_SELLABLE;
		}
		Optional<Channel> channel = Channel.ofSalesChannel(mapping.salesChannel());
		if (channel.isEmpty()) {
			return BLOCKED_POLICY;
		}
		Decision d = routing.decide(new ProviderRouting.Query(mapping.market(), Confidence.HIGH, channel.get(), null,
				null));
		boolean offered = d.purchaseUiAllowed() && "STORE_BILLING".equals(d.purchaseFlow())
				&& d.options().stream().anyMatch(o -> o.providerCode().equals(provider.providerCode()));
		return offered ? null : BLOCKED_POLICY;
	}

	private void refund(Row row, Instant now, String trigger, UUID webhookEventId, StoreNotification event,
			String basis) {
		BigDecimal reversed = credits.reverseStorePurchase(row.userId(), row.id());
		jdbc.update("""
				UPDATE store_purchases SET purchase_status = 'REFUNDED', last_verified_at = ?,
				       metadata = metadata || jsonb_build_object('refundBasis', CAST(? AS TEXT)), updated_at = ?
				 WHERE id = ?""", OrderService.utc(now), basis, OrderService.utc(now), row.id());
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("trigger", trigger);
		details.put("basis", basis);
		details.put("reversedCredits", reversed.stripTrailingZeros().toPlainString());
		Object reason = event == null ? null : event.redactedEvent().get("cancel_reason");
		if (reason != null) {
			details.put("cancelReason", reason.toString());
		}
		purchaseEvent(row.id(), webhookEventId, event != null ? event.eventType() : "REFUNDED", row.status(),
				"REFUNDED", now, details, event, false);
		Map<String, Object> meta = new LinkedHashMap<>(details);
		meta.put("productCode", row.productCode());
		meta.put("userId", row.userId().toString());
		meta.put("previousStatus", row.status());
		audit.recordAs("PROVIDER", new AuditTrail.Entry(null, null, AUDIT_REFUND, "store_purchases",
				row.id().toString(), AuditTrail.Outcome.SUCCESS, meta));
		log.info("store credit pack refunded storePurchaseId={} reversedCredits={} basis={}", row.id(), reversed,
				basis);
	}

	/**
	 * Appends a {@code store_purchase_events} row. With {@code oncePerWebhook} a retried webhook (same stored event and
	 * type) does not append it again.
	 */
	private void purchaseEvent(UUID purchaseId, UUID webhookEventId, String type, String previous, String next,
			Instant now, Map<String, Object> details, StoreNotification event, boolean oncePerWebhook) {
		String notification = type.length() <= 80 ? type : type.substring(0, 80);
		if (oncePerWebhook && webhookEventId != null) {
			Integer n = jdbc.queryForObject("""
					SELECT COUNT(*) FROM store_purchase_events
					WHERE store_purchase_id = ? AND webhook_event_id = ? AND notification_type = ?""", Integer.class,
					purchaseId, webhookEventId, notification);
			if (n != null && n > 0) {
				return;
			}
		}
		Map<String, Object> d = new LinkedHashMap<>(details);
		if (event != null) {
			d.put("event", event.redactedEvent());
		}
		jdbc.update("""
				INSERT INTO store_purchase_events (id, store_purchase_id, webhook_event_id, notification_type,
				       previous_status, new_status, event_time, event_details, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)""", UUID.randomUUID(), purchaseId, webhookEventId,
				notification, previous, next,
				OrderService.utc(event != null && event.eventTime() != null ? event.eventTime() : now),
				json.writeValueAsString(d), OrderService.utc(now));
	}

	/** The pack a store product maps to for the purchase environment (an inactive mapping is returned and blocks). */
	private Optional<PackMapping> mapping(UUID providerId, StoreNonSubscription t) {
		return jdbc.query("""
				SELECT m.id, bp.id, bp.product_code, m.is_active, pr.market_country_code, pr.sales_channel,
				       bp.credit_units, bp.credit_validity_days
				FROM external_product_mappings m
				JOIN billing_prices pr ON pr.id = m.billing_price_id
				JOIN billing_products bp ON bp.id = pr.billing_product_id
				WHERE m.provider_id = ? AND m.external_product_id = ? AND m.environment = ?
				  AND bp.product_type = 'AI_CREDIT_PACK' AND bp.credit_units IS NOT NULL
				  AND pr.renewal_model = 'STORE_MANAGED' AND pr.billing_period = 'ONE_TIME'
				ORDER BY m.is_active DESC, (pr.market_country_code = 'ZZ') DESC, m.created_at, m.id
				LIMIT 1""",
				(rs, i) -> new PackMapping(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
						rs.getBoolean(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7),
						(Integer) rs.getObject(8)),
				providerId, t.productId(), t.environment().name()).stream().findFirst();
	}

	/** A consumable row by its idempotency key, locked. */
	private Optional<Row> row(UUID providerId, String storeTransactionId) {
		return jdbc.query("""
				SELECT sp.id, sp.user_id, sp.purchase_status, sp.billing_product_id, bp.product_code, sp.purchased_at,
				       sp.acknowledged_at, sp.environment, sp.metadata->>'creditUnits',
				       (sp.metadata->>'creditValidityDays')::INT, sp.metadata->>'blockedReason'
				FROM store_purchases sp JOIN billing_products bp ON bp.id = sp.billing_product_id
				WHERE sp.provider_id = ? AND sp.store_transaction_id = ? AND sp.purchase_type = 'CONSUMABLE'
				FOR UPDATE OF sp""",
				(rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
						rs.getObject(4, UUID.class), rs.getString(5), JdbcRoutingData.instant(rs, 6),
						JdbcRoutingData.instant(rs, 7), rs.getString(8), rs.getString(9), (Integer) rs.getObject(10),
						rs.getString(11)),
				providerId, storeTransactionId).stream().findFirst();
	}

	private Consumable consumable(Row row, String storeProductId, String storeTransactionId) {
		Map<String, Object> lot = jdbc.query("""
				SELECT id, initial_units, expires_at FROM ai_credit_lots WHERE store_purchase_id = ?""", (rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("units", rs.getBigDecimal(2));
			m.put("expires", JdbcRoutingData.instant(rs, 3));
			return m;
		}, row.id()).stream().findFirst().orElse(null);
		BigDecimal units = lot != null ? (BigDecimal) lot.get("units")
				: row.units() == null ? null : new BigDecimal(row.units());
		return new Consumable(row.id(), storeProductId, row.productCode(), storeTransactionId, row.status(),
				row.environment(), row.purchasedAt(), row.acknowledgedAt(), lot == null ? null : (UUID) lot.get("id"),
				units, lot == null ? null : (Instant) lot.get("expires"), row.blockedReason());
	}
}
