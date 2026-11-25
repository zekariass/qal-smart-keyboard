package com.qalsmartkeyboard.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.billing.BillingDtos.StorePurchaseRequest;
import com.qalsmartkeyboard.billing.BillingDtos.StorePurchaseResultDto;
import com.qalsmartkeyboard.billing.spi.PaymentProviderException;
import com.qalsmartkeyboard.billing.spi.StoreBillingProvider;
import com.qalsmartkeyboard.billing.spi.StoreCustomer;
import com.qalsmartkeyboard.billing.spi.StoreNotification;
import com.qalsmartkeyboard.billing.spi.StoreSubscription;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import tools.jackson.databind.json.JsonMapper;

/**
 * Store purchases through a store-billing provider (ADR 0046; N6: {@code store_purchases}, never gateway orders; N7:
 * nothing is granted from a webhook body or a client claim). {@link #sync} is the only path that writes store purchases
 * and STORE_MANAGED terms: it reads the customer from the provider server to server and applies that state.
 *
 * <ul>
 * <li>Identity: the provider's app user id is our {@code users.id} (lower-case UUID). One {@code store_purchases} row per
 * user, provider and store product ({@code purchase_token_hash} = sha256 of {@code provider|user|product}); a Play base
 * plan change stays on the same row.</li>
 * <li>Products map to plans through active {@code external_product_mappings} of the provider and purchase environment
 * (product id + base plan, or product id alone); unmapped products and purchases of the other environment are skipped
 * (counted, never granted).</li>
 * <li>Each mapped subscription becomes a {@code store_purchases} row ({@link StorePurchaseStates}), an append-only
 * {@code store_purchase_events} row whenever it changes or a notification names it (the redacted raw event), and a
 * {@code user_subscriptions} row (source STORE, billing mode STORE_MANAGED) with a {@code subscription_events} row per
 * change.</li>
 * <li>A subscription the provider no longer lists for the user (transferred to another app user id) is REVOKED.</li>
 * <li>One-time purchases (store-billed credit packs, {@code purchase_type} CONSUMABLE) are applied by
 * {@link StoreConsumables} in the same transaction: one credit lot per store transaction, refunds reverse the unused
 * remainder.</li>
 * <li>Any change writes {@code billing.subscription.changed} to the outbox: the entitlements module drops its cache.
 * Entitlements never depend on the user's current country.</li>
 * </ul>
 * Out-of-order and duplicate notifications are harmless: every sync applies the provider's current state, under a
 * per-user lock, idempotently.
 */
final class StorePurchases {

	private static final Logger log = LoggerFactory.getLogger(StorePurchases.class);

	/** One purchase after a sync. */
	record Synced(UUID storePurchaseId, String productId, String purchaseStatus, String environment,
			boolean autoRenewing, Instant expiresAt, Instant acknowledgedAt, UUID subscriptionId, boolean changed) {
	}

	/**
	 * A sync's outcome; {@code otherEnvironment} lists product ids skipped for their environment, {@code consumables} the
	 * caller's one-time purchases (credit packs, {@link StoreConsumables}), {@code unconfirmedRefunds} refund events the
	 * provider's API did not confirm (the webhook is retried).
	 */
	record SyncResult(List<Synced> purchases, List<StoreConsumables.Consumable> consumables, int unmapped,
			Set<String> otherEnvironment, boolean changed, int unconfirmedRefunds) {
	}

	/** Store distribution channels: the only builds with store billing. */
	private static final Set<String> STORE_CHANNELS = Set.of("GOOGLE_PLAY", "APPLE_APP_STORE");

	private record Mapping(UUID mappingId, UUID priceId, UUID productId, UUID planId) {
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final ProviderRegistry registry;
	private final OutboxPublisher outbox;
	private final SubscriptionQueries subscriptions;
	private final BillingProperties properties;
	private final JsonMapper json;
	private final Clock clock;
	private final StoreConsumables consumables;

	StorePurchases(JdbcTemplate jdbc, TransactionTemplate transactions, ProviderRegistry registry,
			OutboxPublisher outbox, SubscriptionQueries subscriptions, BillingProperties properties, JsonMapper json,
			Clock clock, StoreConsumables consumables) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.registry = registry;
		this.outbox = outbox;
		this.subscriptions = subscriptions;
		this.properties = properties;
		this.json = json;
		this.clock = clock;
		this.consumables = consumables;
	}

	// ------------------------------------------------------------------------------------------------ client report

	/** {@code POST /billing/store-purchases}: the app reports a purchase; the server verifies with the provider. */
	StorePurchaseResultDto report(AuthenticatedUser user, String idempotencyKey, StorePurchaseRequest body) {
		if (user.isGuest()) {
			throw BillingErrors.problem(ApiErrorCode.GUEST_NOT_ALLOWED, "Sign in to buy a plan.");
		}
		BillingErrors.idempotencyKey(idempotencyKey);
		if (body.distributionChannel() != null && !STORE_CHANNELS.contains(body.distributionChannel())) {
			// a sideloaded APK (or the web) has no store billing: it can neither buy nor report a store purchase here
			throw BillingErrors.problem(ApiErrorCode.BILLING_POLICY_DENIED,
					"Purchases are not available in this version of the app. Install the app from the store to buy.");
		}
		// A store without a direct adapter (GOOGLE_PLAY, APPLE_APP_STORE today) is verified through the configured
		// store-verification provider (RevenueCat) by the caller's user id; its token is never trusted, stored or logged.
		StoreBillingProvider provider = registry.store(body.provider())
				.or(() -> registry.store(properties.storeVerificationProvider()))
				.orElseThrow(() -> BillingErrors.problem(ApiErrorCode.STORE_PURCHASE_INVALID,
						"Purchases of this store are not verified by this server."));
		SyncResult result;
		try {
			result = sync(user.userId(), provider, "CLIENT_REPORT", null, null);
		}
		catch (PaymentProviderException e) {
			if (e.kind() == PaymentProviderException.Kind.REJECTED) {
				throw BillingErrors.problem(ApiErrorCode.STORE_PURCHASE_INVALID, "The store did not confirm this purchase.");
			}
			if (e.kind() == PaymentProviderException.Kind.MISCONFIGURED) {
				log.error("store billing credentials refused provider={} code={}", provider.providerCode(),
						e.safeCode());
			}
			throw BillingErrors.problem(ApiErrorCode.PROVIDER_UNAVAILABLE,
					"The store cannot be reached. Try again in a moment.");
		}
		String product = baseProduct(body.productId());
		List<StoreConsumables.Consumable> packs = result.consumables().stream()
				.filter(c -> c.productId().equals(body.productId()) || c.productId().equals(product)).toList();
		if (!packs.isEmpty()) {
			return creditPack(provider, body, packs, result);
		}
		Optional<Synced> hit = result.purchases().stream().filter(p -> p.productId().equals(product)).findFirst();
		if (hit.isEmpty() && body.transactionId() != null) {
			// a one-time purchase named by its transaction that is not (yet) listed for the caller
			return creditPack(provider, body, packs, result);
		}
		if (hit.isEmpty()) {
			String detail = result.otherEnvironment().contains(product)
					? "This purchase was made in a store environment this server does not honour."
					: "No such purchase was found for this account.";
			log.info("store purchase report not confirmed provider={} product={} otherEnvironment={}",
					provider.providerCode(), product, result.otherEnvironment().contains(product));
			throw BillingErrors.problem(ApiErrorCode.STORE_PURCHASE_INVALID, detail);
		}
		Synced p = hit.get();
		return new StorePurchaseResultDto(p.storePurchaseId(), p.productId(), p.purchaseStatus(), p.environment(),
				p.autoRenewing(), p.acknowledgedAt(), p.expiresAt(),
				p.subscriptionId() == null ? null : subscriptions.one(user.userId(), p.subscriptionId()).orElse(null),
				null, "SUBSCRIPTION", null, null, null, null);
	}

	/**
	 * The report of a one-time purchase (a credit pack): the transaction named by {@code transactionId}, else the
	 * caller's most recent one of the product. Granted (or refunded / expired) purchases answer 200; a verified purchase
	 * that may not be granted now is 403 {@code BILLING_POLICY_DENIED} (market/channel policy) or 422
	 * {@code PRODUCT_NOT_AVAILABLE} (pack, mapping or offering off); nothing listed for the caller is 422
	 * {@code STORE_PURCHASE_INVALID}.
	 */
	private StorePurchaseResultDto creditPack(StoreBillingProvider provider, StorePurchaseRequest body,
			List<StoreConsumables.Consumable> packs, SyncResult result) {
		Optional<StoreConsumables.Consumable> hit = body.transactionId() != null
				? packs.stream().filter(c -> body.transactionId().equals(c.storeTransactionId())).findFirst()
				: packs.stream().max(Comparator.comparing(StoreConsumables.Consumable::purchasedAt,
						Comparator.nullsFirst(Comparator.naturalOrder())));
		if (hit.isEmpty()) {
			String product = baseProduct(body.productId());
			String detail = result.otherEnvironment().contains(body.productId())
					|| result.otherEnvironment().contains(product)
							? "This purchase was made in a store environment this server does not honour."
							: "No such purchase was found for this account.";
			log.info("store credit pack report not confirmed provider={} product={}", provider.providerCode(),
					body.productId());
			throw BillingErrors.problem(ApiErrorCode.STORE_PURCHASE_INVALID, detail);
		}
		StoreConsumables.Consumable c = hit.get();
		if ("PENDING".equals(c.purchaseStatus())) {
			log.info("store credit pack report refused storePurchaseId={} reason={}", c.storePurchaseId(),
					c.blockedReason());
			if (StoreConsumables.BLOCKED_POLICY.equals(c.blockedReason())) {
				throw BillingErrors.problem(ApiErrorCode.BILLING_POLICY_DENIED,
						"Credit packs cannot be bought through the store in this market yet.");
			}
			throw BillingErrors.problem(ApiErrorCode.PRODUCT_NOT_AVAILABLE, "This credit pack is not available.");
		}
		return new StorePurchaseResultDto(c.storePurchaseId(), c.productId(), c.purchaseStatus(), c.environment(),
				Boolean.FALSE, c.acknowledgedAt(), null, null, c.creditLotId(), "CONSUMABLE", c.productCode(),
				c.storeTransactionId(), c.creditUnits() == null ? null : c.creditUnits().longValue(),
				c.creditsExpireAt());
	}

	// ------------------------------------------------------------------------------------------------ sync

	/**
	 * Reads the user's customer from the provider and applies it.
	 *
	 * @param trigger        {@code CLIENT_REPORT} or {@code WEBHOOK} (recorded)
	 * @param webhookEventId the stored notification that triggered it, or null
	 * @param event          that notification (its redacted event is kept on the purchase it names), or null
	 * @throws PaymentProviderException when the provider cannot answer (nothing is changed)
	 */
	SyncResult sync(UUID userId, StoreBillingProvider provider, String trigger, UUID webhookEventId,
			StoreNotification event) {
		StoreCustomer customer = provider.customer(userId.toString());
		UUID providerId = jdbc.queryForObject("SELECT id FROM payment_providers WHERE provider_code = ?", UUID.class,
				provider.providerCode());
		SyncResult result = transactions.execute(s -> apply(userId, provider, providerId, customer, trigger,
				webhookEventId, event));
		log.info("store purchases synced provider={} trigger={} purchases={} consumables={} unmapped={} "
				+ "otherEnvironment={} changed={} unconfirmedRefunds={}", provider.providerCode(), trigger,
				result.purchases().size(), result.consumables().size(), result.unmapped(),
				result.otherEnvironment().size(), result.changed(), result.unconfirmedRefunds());
		return result;
	}

	private SyncResult apply(UUID userId, StoreBillingProvider provider, UUID providerId, StoreCustomer customer,
			String trigger, UUID webhookEventId, StoreNotification event) {
		jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", (rs, i) -> null, "store_purchases:" + userId);
		Instant now = clock.instant();
		String eventProduct = event == null ? null : baseProduct(event.productId());
		List<Synced> out = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		Set<String> otherEnvironment = new HashSet<>();
		int unmapped = 0;
		boolean anyChange = false;
		UUID changedSubscription = null;
		for (StoreSubscription sub : customer.subscriptions()) {
			if (!provider.supports(sub.environment())) {
				otherEnvironment.add(sub.productId());
				continue;
			}
			Mapping mapping = mapping(providerId, sub).orElse(null);
			if (mapping == null) {
				unmapped++;
				log.warn("store purchase skipped: product not mapped provider={} product={} environment={}",
						provider.providerCode(), sub.productId(), sub.environment());
				continue;
			}
			String status = StorePurchaseStates.purchaseStatus(sub, now, properties.storeRenewalPending());
			boolean autoRenewing = StorePurchaseStates.autoRenewing(sub, status);
			String hash = BillingErrors.sha256Hex(provider.providerCode() + "|" + userId + "|" + sub.productId());
			boolean named = sub.productId().equals(eventProduct);
			Synced synced = upsertPurchase(userId, providerId, mapping, sub, customer.managementUrl(), status,
					autoRenewing, hash, now, trigger, named ? webhookEventId : null, named ? event : null);
			seen.add(synced.storePurchaseId());
			out.add(synced);
			if (synced.changed()) {
				anyChange = true;
				changedSubscription = synced.subscriptionId();
			}
		}
		// purchases the provider no longer lists for this user (transferred to another app user id)
		List<Object[]> stale = jdbc.query("""
				SELECT id, purchase_status FROM store_purchases
				WHERE user_id = ? AND provider_id = ? AND purchase_status NOT IN ('EXPIRED', 'REVOKED', 'REFUNDED')
				  AND purchase_type = 'SUBSCRIPTION'
				FOR UPDATE""", (rs, i) -> new Object[] { rs.getObject(1, UUID.class), rs.getString(2) }, userId,
				providerId);
		for (Object[] row : stale) {
			UUID id = (UUID) row[0];
			if (seen.contains(id)) {
				continue;
			}
			UUID subscriptionId = revokeMissing(id, (String) row[1], now, trigger, webhookEventId);
			anyChange = true;
			changedSubscription = subscriptionId != null ? subscriptionId : changedSubscription;
		}
		if (anyChange) {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("userId", userId.toString());
			if (changedSubscription != null) {
				payload.put("subscriptionId", changedSubscription.toString());
			}
			outbox.publish("user_subscription", changedSubscription == null ? userId.toString()
					: changedSubscription.toString(), BillingEvents.SUBSCRIPTION_CHANGED, payload);
		}
		// one-time purchases (credit packs): never subscriptions, never the subscription outbox event
		StoreConsumables.Result packs = consumables.apply(userId, provider, providerId, customer, now, trigger,
				webhookEventId, event);
		otherEnvironment.addAll(packs.otherEnvironment());
		return new SyncResult(List.copyOf(out), packs.consumables(), unmapped + packs.unmapped(),
				Set.copyOf(otherEnvironment), anyChange, packs.unconfirmedRefunds());
	}

	private Synced upsertPurchase(UUID userId, UUID providerId, Mapping mapping, StoreSubscription sub,
			String managementUrl, String status, boolean autoRenewing, String hash, Instant now, String trigger,
			UUID webhookEventId, StoreNotification event) {
		Map<String, Object> existing = jdbc.query("""
				SELECT id, purchase_status, expires_at, auto_renewing, acknowledged_at, user_id
				FROM store_purchases WHERE provider_id = ? AND purchase_token_hash = ? FOR UPDATE""", (rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("status", rs.getString(2));
			m.put("expires", JdbcRoutingData.instant(rs, 3));
			m.put("auto", rs.getBoolean(4));
			m.put("ack", JdbcRoutingData.instant(rs, 5));
			return m;
		}, providerId, hash).stream().findFirst().orElse(null);

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("store", sub.store());
		metadata.put("basePlanId", sub.basePlanId());
		metadata.put("periodType", sub.periodType());
		metadata.put("ownershipType", sub.ownershipType());
		metadata.put("managementUrl", managementUrl);
		metadata.put("lastSyncTrigger", trigger);
		metadata.values().removeIf(Objects::isNull);
		Instant purchasedAt = sub.purchasedAt() != null ? sub.purchasedAt()
				: sub.originalPurchasedAt() != null ? sub.originalPurchasedAt() : now;
		boolean grants = "ACTIVE".equals(status) || "IN_GRACE_PERIOD".equals(status);

		UUID purchaseId;
		String previous;
		Instant acknowledgedAt;
		boolean changed;
		if (existing == null) {
			purchaseId = UUID.randomUUID();
			previous = null;
			acknowledgedAt = grants ? now : null;
			if (grants) {
				metadata.put("acknowledgedBy", "store billing provider");
			}
			jdbc.update("""
					INSERT INTO store_purchases (id, user_id, provider_id, provider_type, external_product_mapping_id,
					       purchase_token_hash, latest_transaction_id, environment, purchase_status, auto_renewing,
					       purchased_at, expires_at, acknowledged_at, last_verified_at, metadata, created_at, updated_at)
					VALUES (?, ?, ?, 'APP_STORE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?)""", purchaseId,
					userId, providerId, mapping.mappingId(), hash, sub.storeTransactionId(), sub.environment().name(),
					status, autoRenewing, OrderService.utc(purchasedAt), OrderService.utc(sub.expiresAt()),
					OrderService.utc(acknowledgedAt), OrderService.utc(now), json.writeValueAsString(metadata),
					OrderService.utc(now), OrderService.utc(now));
			changed = true;
		}
		else {
			purchaseId = (UUID) existing.get("id");
			previous = (String) existing.get("status");
			Instant ack = (Instant) existing.get("ack");
			acknowledgedAt = ack == null && grants ? now : ack;
			changed = !status.equals(previous) || !Objects.equals(existing.get("expires"), sub.expiresAt())
					|| (Boolean) existing.get("auto") != autoRenewing;
			jdbc.update("""
					UPDATE store_purchases
					   SET external_product_mapping_id = ?, latest_transaction_id = COALESCE(?, latest_transaction_id),
					       purchase_status = ?, auto_renewing = ?, purchased_at = ?, expires_at = ?,
					       acknowledged_at = ?, last_verified_at = ?, metadata = metadata || CAST(? AS JSONB), updated_at = ?
					 WHERE id = ?""", mapping.mappingId(), sub.storeTransactionId(), status, autoRenewing,
					OrderService.utc(purchasedAt), OrderService.utc(sub.expiresAt()), OrderService.utc(acknowledgedAt),
					OrderService.utc(now), json.writeValueAsString(metadata), OrderService.utc(now), purchaseId);
		}

		UUID purchaseEventId = null;
		if (changed || event != null) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("trigger", trigger);
			details.put("expiresAt", sub.expiresAt() == null ? null : sub.expiresAt().toString());
			details.put("autoRenewing", autoRenewing);
			if (event != null) {
				details.put("event", event.redactedEvent());
			}
			purchaseEventId = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO store_purchase_events (id, store_purchase_id, webhook_event_id, notification_type,
					       previous_status, new_status, event_time, event_details, created_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)""", purchaseEventId, purchaseId, webhookEventId,
					truncate(event != null ? event.eventType() : trigger, 80), previous, status,
					OrderService.utc(event != null && event.eventTime() != null ? event.eventTime() : now),
					json.writeValueAsString(details), OrderService.utc(now));
		}
		UUID subscriptionId = upsertSubscription(userId, providerId, purchaseId, mapping, sub, status, autoRenewing,
				purchasedAt, now, purchaseEventId);
		return new Synced(purchaseId, sub.productId(), status, sub.environment().name(), autoRenewing,
				sub.expiresAt(), acknowledgedAt, subscriptionId, changed);
	}

	private UUID upsertSubscription(UUID userId, UUID providerId, UUID purchaseId, Mapping mapping,
			StoreSubscription sub, String purchaseStatus, boolean autoRenewing, Instant purchasedAt, Instant now,
			UUID purchaseEventId) {
		String status = StorePurchaseStates.subscriptionStatus(purchaseStatus);
		Instant start = sub.expiresAt() != null && !purchasedAt.isBefore(sub.expiresAt()) ? null : purchasedAt;
		Instant grace = "GRACE_PERIOD".equals(status) ? sub.gracePeriodExpiresAt() : null;
		boolean cancelAtEnd = sub.unsubscribeDetectedAt() != null && "ACTIVE".equals(status);
		Instant revokedAt = "REVOKED".equals(status) ? (sub.refundedAt() != null ? sub.refundedAt() : now) : null;
		Map<String, Object> existing = jdbc.query("""
				SELECT id, subscription_status, current_period_end, auto_renew_enabled
				FROM user_subscriptions WHERE store_purchase_id = ? FOR UPDATE""", (rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("status", rs.getString(2));
			m.put("end", JdbcRoutingData.instant(rs, 3));
			m.put("auto", rs.getBoolean(4));
			return m;
		}, purchaseId).stream().findFirst().orElse(null);
		UUID id;
		String previous;
		Boolean previousAuto;
		boolean extended;
		if (existing == null) {
			id = UUID.randomUUID();
			previous = null;
			previousAuto = null;
			extended = false;
			jdbc.update("""
					INSERT INTO user_subscriptions (id, user_id, plan_id, billing_product_id, billing_price_id, source_type,
					       billing_mode, store_purchase_id, provider_id, subscription_status, current_period_start,
					       current_period_end, grace_period_end, auto_renew_enabled, cancel_at_period_end, started_at,
					       cancelled_at, revoked_at, last_verified_at, created_at, updated_at)
					VALUES (?, ?, ?, ?, ?, 'STORE', 'STORE_MANAGED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id,
					userId, mapping.planId(), mapping.productId(), mapping.priceId(), purchaseId, providerId, status,
					OrderService.utc(start), OrderService.utc(sub.expiresAt()), OrderService.utc(grace), autoRenewing,
					cancelAtEnd, OrderService.utc(sub.originalPurchasedAt() != null ? sub.originalPurchasedAt()
							: purchasedAt),
					OrderService.utc(sub.unsubscribeDetectedAt()), OrderService.utc(revokedAt), OrderService.utc(now),
					OrderService.utc(now), OrderService.utc(now));
		}
		else {
			id = (UUID) existing.get("id");
			previous = (String) existing.get("status");
			previousAuto = (Boolean) existing.get("auto");
			Instant previousEnd = (Instant) existing.get("end");
			extended = previousEnd != null && sub.expiresAt() != null && sub.expiresAt().isAfter(previousEnd);
			jdbc.update("""
					UPDATE user_subscriptions
					   SET plan_id = ?, billing_product_id = ?, billing_price_id = ?, subscription_status = ?,
					       current_period_start = ?, current_period_end = ?, grace_period_end = ?, auto_renew_enabled = ?,
					       cancel_at_period_end = ?, cancelled_at = ?, revoked_at = ?, last_verified_at = ?, updated_at = ?
					 WHERE id = ?""", mapping.planId(), mapping.productId(), mapping.priceId(), status,
					OrderService.utc(start), OrderService.utc(sub.expiresAt()), OrderService.utc(grace), autoRenewing,
					cancelAtEnd, OrderService.utc(sub.unsubscribeDetectedAt()), OrderService.utc(revokedAt),
					OrderService.utc(now), OrderService.utc(now), id);
		}
		String eventType = StorePurchaseStates.subscriptionEvent(previous, status, purchaseStatus, extended,
				previousAuto, autoRenewing);
		if (eventType != null) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("storePurchaseId", purchaseId.toString());
			details.put("purchaseStatus", purchaseStatus);
			details.put("periodEnd", sub.expiresAt() == null ? null : sub.expiresAt().toString());
			jdbc.update("""
					INSERT INTO subscription_events (subscription_id, store_purchase_event_id, event_type, previous_status,
					       new_status, actor_type, event_details)
					VALUES (?, ?, ?, ?, ?, 'PROVIDER', CAST(? AS JSONB))""", id, purchaseEventId, eventType, previous,
					status, json.writeValueAsString(details));
		}
		return id;
	}

	/** Revokes a purchase the provider no longer lists for the user; returns its subscription. */
	private UUID revokeMissing(UUID purchaseId, String previous, Instant now, String trigger, UUID webhookEventId) {
		jdbc.update("""
				UPDATE store_purchases SET purchase_status = 'REVOKED', auto_renewing = FALSE, last_verified_at = ?,
				       metadata = metadata || '{"revokedReason": "NOT_LISTED_BY_PROVIDER"}'::JSONB, updated_at = ?
				WHERE id = ?""", OrderService.utc(now), OrderService.utc(now), purchaseId);
		UUID eventId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO store_purchase_events (id, store_purchase_id, webhook_event_id, notification_type,
				       previous_status, new_status, event_time, event_details, created_at)
				VALUES (?, ?, ?, ?, ?, 'REVOKED', ?, CAST(? AS JSONB), ?)""", eventId, purchaseId, webhookEventId,
				truncate(trigger, 80), previous, OrderService.utc(now),
				json.writeValueAsString(Map.of("trigger", trigger, "reason", "NOT_LISTED_BY_PROVIDER")),
				OrderService.utc(now));
		Map<String, Object> sub = jdbc.query("""
				SELECT id, subscription_status FROM user_subscriptions WHERE store_purchase_id = ? FOR UPDATE""",
				(rs, i) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class), "status", rs.getString(2)),
				purchaseId).stream().findFirst().orElse(null);
		if (sub == null) {
			return null;
		}
		UUID id = (UUID) sub.get("id");
		if (!"REVOKED".equals(sub.get("status"))) {
			jdbc.update("""
					UPDATE user_subscriptions SET subscription_status = 'REVOKED', auto_renew_enabled = FALSE,
					       revoked_at = ?, last_verified_at = ?, updated_at = ? WHERE id = ?""", OrderService.utc(now),
					OrderService.utc(now), OrderService.utc(now), id);
			jdbc.update("""
					INSERT INTO subscription_events (subscription_id, store_purchase_event_id, event_type, previous_status,
					       new_status, actor_type, event_details)
					VALUES (?, ?, 'REVOKED', ?, 'REVOKED', 'PROVIDER', CAST(? AS JSONB))""", id, eventId,
					sub.get("status"), json.writeValueAsString(Map.of("reason", "NOT_LISTED_BY_PROVIDER")));
		}
		log.info("store purchase revoked: no longer listed by the provider storePurchaseId={}", purchaseId);
		return id;
	}

	/** The active mapping of a store product (+ base plan) for the purchase environment. */
	private Optional<Mapping> mapping(UUID providerId, StoreSubscription sub) {
		return jdbc.query("""
				SELECT m.id, m.billing_price_id, pr.billing_product_id, bp.plan_id
				FROM external_product_mappings m
				JOIN billing_prices pr ON pr.id = m.billing_price_id
				JOIN billing_products bp ON bp.id = pr.billing_product_id
				WHERE m.provider_id = ? AND m.external_product_id = ? AND m.environment = ? AND m.is_active
				  AND bp.plan_id IS NOT NULL
				  AND (CAST(? AS TEXT) IS NULL OR m.external_offer_id = ? OR m.external_offer_id IS NULL)
				ORDER BY (m.external_offer_id IS NOT DISTINCT FROM ?) DESC, (m.external_offer_id IS NULL) DESC,
				         m.external_offer_id
				LIMIT 1""",
				(rs, i) -> new Mapping(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
						rs.getObject(3, UUID.class), rs.getObject(4, UUID.class)),
				providerId, sub.productId(), sub.environment().name(), sub.basePlanId(), sub.basePlanId(),
				sub.basePlanId()).stream().findFirst();
	}

	/** {@code qal_premium:monthly} to {@code qal_premium} (Play subscription id without the base plan). */
	static String baseProduct(String productId) {
		if (productId == null) {
			return null;
		}
		int colon = productId.indexOf(':');
		return colon > 0 ? productId.substring(0, colon) : productId;
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}
}
