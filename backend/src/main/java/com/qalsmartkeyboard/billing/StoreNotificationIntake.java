package com.qalsmartkeyboard.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.PaymentProviderException;
import com.qalsmartkeyboard.billing.spi.StoreBillingProvider;
import com.qalsmartkeyboard.billing.spi.StoreNotification;
import com.qalsmartkeyboard.billing.spi.StoreNotificationRequest;
import com.qalsmartkeyboard.billing.spi.WebhookAuthentication;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.outbox.OutboxEvent;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /billing/store-notifications/{providerCode}} (contract {@code receiveStoreNotification}, ADR 0046) and the
 * outbox processing of stored notifications.
 *
 * <p>Receive: the provider must have a store adapter (else 404); the adapter authenticates the call (invalid: stored as
 * INVALID under {@code invalid:<sha256>}, then 401); the parsed event is stored once on {@code (provider, event id)} in
 * {@code payment_webhook_events} with a redacted payload (no subscriber attributes), a processing event goes to the outbox
 * in the same transaction, and the provider gets 200 at once. A redelivery (same event id) is a no-op; a TEST event is
 * stored and ignored.
 *
 * <p>Process ({@link #process}): the event's user references that are our user ids are synced through
 * {@link StorePurchases#sync}, which re-reads the customer from the provider: the body is never trusted, so order does
 * not matter. A provider that cannot be reached is retried by the outbox, and so is a refund event for a granted
 * one-time purchase (a credit pack) that the provider's API does not confirm yet (nothing is reversed on the body).
 */
final class StoreNotificationIntake {

	static final int MAX_BODY_BYTES = 64 * 1024;
	private static final Logger log = LoggerFactory.getLogger(StoreNotificationIntake.class);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final ProviderRegistry registry;
	private final OutboxPublisher outbox;
	private final StorePurchases purchases;
	private final JsonMapper json;
	private final Clock clock;

	StoreNotificationIntake(JdbcTemplate jdbc, TransactionTemplate transactions, ProviderRegistry registry,
			OutboxPublisher outbox, StorePurchases purchases, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.registry = registry;
		this.outbox = outbox;
		this.purchases = purchases;
		this.json = json;
		this.clock = clock;
	}

	void receive(String providerCode, byte[] body, Map<String, List<String>> headers) {
		StoreBillingProvider provider = registry.store(providerCode).orElseThrow(BillingErrors::notFound);
		UUID providerId = jdbc.query("SELECT id FROM payment_providers WHERE provider_code = ?",
				(rs, i) -> rs.getObject(1, UUID.class), providerCode).stream().findFirst()
				.orElseThrow(BillingErrors::notFound);
		String sha = BillingErrors.sha256Hex(body);
		if (body.length > MAX_BODY_BYTES) {
			reject(providerId, providerCode, sha, "OVERSIZED");
		}
		StoreNotificationRequest request = new StoreNotificationRequest(body, headers);
		if (provider.authenticateNotification(request) != WebhookAuthentication.VALID) {
			reject(providerId, providerCode, sha, "INVALID");
		}
		StoreNotification event;
		try {
			event = provider.parseNotification(request);
		}
		catch (PaymentProviderException e) {
			store(providerId, "unparseable:" + sha, "UNPARSEABLE", WebhookAuthentication.VALID, "IGNORED", sha,
					Map.of(), false);
			log.warn("store notification unparseable provider={} code={}", providerCode, e.safeCode());
			return;
		}
		Map<String, Object> redacted = new LinkedHashMap<>();
		redacted.put("eventId", event.eventId());
		redacted.put("eventType", event.eventType());
		redacted.put("userReferences", event.userReferences());
		redacted.put("productId", event.productId());
		redacted.put("environment", event.environment() == null ? null : event.environment().name());
		redacted.put("eventTime", event.eventTime() == null ? null : event.eventTime().toString());
		redacted.put("test", event.test());
		redacted.put("transactionIds", event.transactionIds());
		redacted.put("event", event.redactedEvent());
		boolean stored = store(providerId, event.eventId(), event.eventType(), WebhookAuthentication.VALID,
				event.test() ? "IGNORED" : "RECEIVED", sha, redacted, !event.test());
		log.info("store notification received provider={} type={} duplicate={} test={}", providerCode,
				event.eventType(), !stored, event.test());
	}

	private void reject(UUID providerId, String providerCode, String sha, String reason) {
		store(providerId, "invalid:" + sha, reason, WebhookAuthentication.INVALID, "IGNORED", sha, Map.of(), false);
		log.warn("store notification rejected provider={} reason={}", providerCode, reason);
		throw BillingErrors.problem(ApiErrorCode.WEBHOOK_SIGNATURE_INVALID, null);
	}

	private boolean store(UUID providerId, String externalId, String eventType, WebhookAuthentication auth,
			String status, String sha, Map<String, Object> redacted, boolean publish) {
		Boolean inserted = transactions.execute(s -> {
			UUID id = UUID.randomUUID();
			Instant now = clock.instant();
			int rows = jdbc.update("""
					INSERT INTO payment_webhook_events (id, provider_id, provider_account_id, external_event_id,
					       is_synthetic_event_id, event_type, signature_status, event_status, payload_sha256,
					       redacted_payload, received_at, updated_at)
					VALUES (?, ?, NULL, ?, FALSE, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?)
					ON CONFLICT (provider_id, external_event_id) DO NOTHING""", id, providerId,
					truncate(externalId, 255), truncate(eventType, 120), auth.name(), status, sha,
					json.writeValueAsString(redacted), OrderService.utc(now), OrderService.utc(now));
			if (rows == 1 && publish) {
				outbox.publish("payment_webhook_event", id.toString(), BillingEvents.STORE_NOTIFICATION_RECEIVED,
						Map.of("webhookEventId", id.toString()));
			}
			return rows == 1;
		});
		return Boolean.TRUE.equals(inserted);
	}

	/** Outbox handler body for {@link BillingEvents#STORE_NOTIFICATION_RECEIVED}. */
	void process(OutboxEvent outboxEvent) {
		UUID eventId = UUID.fromString(json.readTree(outboxEvent.payloadJson()).path("webhookEventId").asString());
		Object[] row = jdbc.query("""
				SELECT p.provider_code, e.event_status, e.processing_attempts, e.redacted_payload::text
				FROM payment_webhook_events e JOIN payment_providers p ON p.id = e.provider_id WHERE e.id = ?""",
				(rs, i) -> new Object[] { rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4) }, eventId)
				.stream().findFirst().orElse(null);
		if (row == null || "PROCESSED".equals(row[1]) || "IGNORED".equals(row[1])) {
			return;
		}
		int attempts = (Integer) row[2] + 1;
		StoreBillingProvider provider = registry.store((String) row[0]).orElse(null);
		if (provider == null) {
			finish(eventId, "IGNORED", attempts, "NO_ADAPTER");
			return;
		}
		JsonNode payload = json.readTree((String) row[3]);
		StoreNotification event = notification(payload);
		List<UUID> users = users(event.userReferences());
		if (users.isEmpty()) {
			finish(eventId, "IGNORED", attempts, "UNKNOWN_USER");
			log.info("store notification ignored: no user of ours webhookEventId={} type={}", eventId,
					event.eventType());
			return;
		}
		int unconfirmedRefunds = 0;
		for (UUID user : users) {
			try {
				unconfirmedRefunds += purchases.sync(user, provider, "WEBHOOK", eventId, event).unconfirmedRefunds();
			}
			catch (PaymentProviderException e) {
				markProcessing(eventId, attempts, "PROVIDER_" + e.kind().name());
				throw new IllegalStateException("store provider unavailable");
			}
		}
		if (unconfirmedRefunds > 0) {
			// a refund event for a granted one-time purchase that the provider's API does not (yet) confirm: nothing was
			// reversed (the body is never trusted); retried by the outbox until the API agrees or the event goes DEAD
			markProcessing(eventId, attempts, "REFUND_NOT_CONFIRMED");
			throw new IllegalStateException("store refund not confirmed by the provider yet");
		}
		finish(eventId, "PROCESSED", attempts, null);
	}

	/** Our users among the references: lower-case UUIDs of existing accounts (anonymous store ids are skipped). */
	private List<UUID> users(List<String> references) {
		Set<UUID> candidates = new LinkedHashSet<>();
		for (String ref : references) {
			if (ref != null && ref.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
				candidates.add(UUID.fromString(ref));
			}
		}
		List<UUID> out = new ArrayList<>();
		for (UUID id : candidates) {
			Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, id);
			if (n != null && n > 0) {
				out.add(id);
			}
		}
		return out;
	}

	private StoreNotification notification(JsonNode payload) {
		List<String> refs = new ArrayList<>();
		payload.path("userReferences").forEach(n -> refs.add(n.asString()));
		List<String> transactions = new ArrayList<>();
		payload.path("transactionIds").forEach(n -> {
			if (n.isString() && !n.asString().isBlank()) {
				transactions.add(n.asString());
			}
		});
		String env = text(payload.path("environment"));
		String time = text(payload.path("eventTime"));
		@SuppressWarnings("unchecked")
		Map<String, Object> event = payload.path("event").isObject()
				? json.treeToValue(payload.path("event"), Map.class)
				: Map.of();
		return new StoreNotification(text(payload.path("eventId")), text(payload.path("eventType")), refs,
				text(payload.path("productId")), env == null ? null : PaymentEnvironment.valueOf(env),
				time == null ? null : Instant.parse(time), event, payload.path("test").asBoolean(false), transactions);
	}

	private void markProcessing(UUID eventId, int attempts, String code) {
		DefaultTransactionDefinition d = new DefaultTransactionDefinition();
		d.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		new TransactionTemplate(transactions.getTransactionManager(), d).executeWithoutResult(s -> jdbc.update("""
				UPDATE payment_webhook_events
				   SET event_status = 'PROCESSING', processing_attempts = ?, processing_error = ?, updated_at = ?
				 WHERE id = ?""", Math.min(attempts, Short.MAX_VALUE), code, OrderService.utc(clock.instant()),
				eventId));
	}

	private void finish(UUID eventId, String status, int attempts, String code) {
		Instant now = clock.instant();
		jdbc.update("""
				UPDATE payment_webhook_events
				   SET event_status = ?, processing_attempts = ?, processing_error = ?, processed_at = ?, updated_at = ?
				 WHERE id = ?""", status, Math.min(attempts, Short.MAX_VALUE), code, OrderService.utc(now),
				OrderService.utc(now), eventId);
	}

	private static String text(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}
}
