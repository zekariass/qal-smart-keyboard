package com.qalsmartkeyboard.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.billing.spi.MerchantAccount;
import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.PaymentProvider;
import com.qalsmartkeyboard.billing.spi.PaymentProviderException;
import com.qalsmartkeyboard.billing.spi.WebhookAuthentication;
import com.qalsmartkeyboard.billing.spi.WebhookEvent;
import com.qalsmartkeyboard.billing.spi.WebhookRequest;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.outbox.OutboxEvent;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /billing/webhooks/{providerCode}/{accountCode}} (SD 16.9, contract {@code receiveGatewayWebhook}) and the
 * outbox processing of stored events.
 *
 * <p>Receive: the route selects the merchant account (unknown provider/account or no adapter: 404); the adapter
 * authenticates the call (invalid: stored as INVALID under a key of its own, so a forged event can never occupy the
 * dedupe key of a real one, then 401); the parsed event is stored once on {@code (provider, external_event_id)} (sha256
 * of the body when the provider sends no id) with a redacted payload and its hash, a processing event is written to the
 * outbox in the same transaction, and the provider gets its acknowledgement at once. A redelivery is a no-op.
 *
 * <p>Process ({@link #process}): finds the attempt by our merchant reference (else by the provider reference) on the same
 * provider and account, and hands it to {@link PaymentVerifier}: the event body is never trusted. An attempt not found
 * yet (the callback raced the start call's commit) is retried by the outbox; a provider that cannot be reached is retried
 * too (out-of-order and duplicate events are safe because verification reads the provider's current state and every
 * write is idempotent).
 */
final class WebhookIntake {

	static final int MAX_BODY_BYTES = 64 * 1024;
	private static final int MAX_ATTEMPT_LOOKUPS = 5;
	private static final Logger log = LoggerFactory.getLogger(WebhookIntake.class);

	record Account(UUID providerId, UUID accountId, MerchantAccount merchant) {
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final ProviderRegistry registry;
	private final OutboxPublisher outbox;
	private final PaymentVerifier verifier;
	private final JsonMapper json;
	private final Clock clock;

	WebhookIntake(JdbcTemplate jdbc, TransactionTemplate transactions, ProviderRegistry registry, OutboxPublisher outbox,
			PaymentVerifier verifier, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.registry = registry;
		this.outbox = outbox;
		this.verifier = verifier;
		this.json = json;
		this.clock = clock;
	}

	void receive(String providerCode, String accountCode, byte[] body, Map<String, List<String>> headers,
			Map<String, String> query) {
		Account account = account(providerCode, accountCode).orElseThrow(BillingErrors::notFound);
		PaymentProvider provider = registry.gateway(providerCode, account.merchant().environment())
				.orElseThrow(BillingErrors::notFound);
		String sha = BillingErrors.sha256Hex(body);
		if (body.length > MAX_BODY_BYTES) {
			reject(account, sha, "OVERSIZED");
		}
		WebhookRequest request = new WebhookRequest(account.merchant(), body, headers, query);
		WebhookAuthentication auth = provider.authenticate(request);
		if (auth == WebhookAuthentication.INVALID) {
			reject(account, sha, "INVALID");
		}
		WebhookEvent event;
		try {
			event = provider.parseWebhook(request);
		}
		catch (PaymentProviderException e) {
			store(account, "unparseable:" + sha, false, "UNPARSEABLE", auth, "IGNORED", sha, Map.of(), false);
			log.warn("billing webhook unparseable provider={} account={} code={}", providerCode, accountCode,
					e.safeCode());
			return;
		}
		boolean synthetic = event.externalEventId() == null;
		String externalId = synthetic ? sha : event.externalEventId();
		Map<String, Object> redacted = new LinkedHashMap<>();
		redacted.put("providerReference", event.providerReference());
		redacted.put("merchantReference", event.merchantReference());
		redacted.put("reportedStatus", event.reportedStatus().name());
		redacted.put("event", event.redactedPayload());
		boolean stored = store(account, externalId, synthetic, event.eventType(), auth, "RECEIVED", sha, redacted, true);
		log.info("billing webhook received provider={} account={} type={} reported={} duplicate={}", providerCode,
				accountCode, event.eventType(), event.reportedStatus(), !stored);
	}

	private void reject(Account account, String sha, String reason) {
		store(account, "invalid:" + sha, false, reason, WebhookAuthentication.INVALID, "IGNORED", sha, Map.of(), false);
		log.warn("billing webhook rejected provider={} account={} reason={}", account.merchant().providerCode(),
				account.merchant().accountCode(), reason);
		throw BillingErrors.problem(ApiErrorCode.WEBHOOK_SIGNATURE_INVALID, null);
	}

	/** Stores the event once; returns false for a duplicate. Publishes the processing event when asked. */
	private boolean store(Account account, String externalId, boolean synthetic, String eventType,
			WebhookAuthentication auth, String status, String sha, Map<String, Object> redacted, boolean publish) {
		Boolean inserted = transactions.execute(s -> {
			UUID id = UUID.randomUUID();
			Instant now = clock.instant();
			int rows = jdbc.update("""
					INSERT INTO payment_webhook_events (id, provider_id, provider_account_id, external_event_id,
					       is_synthetic_event_id, event_type, signature_status, event_status, payload_sha256,
					       redacted_payload, received_at, updated_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?, ?)
					ON CONFLICT (provider_id, external_event_id) DO NOTHING""", id, account.providerId(),
					account.accountId(), truncate(externalId, 255), synthetic, truncate(eventType, 120), auth.name(),
					status, sha, json.writeValueAsString(redacted), OrderService.utc(now), OrderService.utc(now));
			if (rows == 1 && publish) {
				outbox.publish("payment_webhook_event", id.toString(), BillingEvents.WEBHOOK_RECEIVED,
						Map.of("webhookEventId", id.toString()));
			}
			return rows == 1;
		});
		return Boolean.TRUE.equals(inserted);
	}

	/** Outbox handler body for {@link BillingEvents#WEBHOOK_RECEIVED}. Runs in the dispatcher's transaction. */
	void process(OutboxEvent event) {
		UUID eventId = UUID.fromString(json.readTree(event.payloadJson()).path("webhookEventId").asString());
		Map<String, Object> row = jdbc.query("""
				SELECT provider_id, provider_account_id, event_status, processing_attempts, redacted_payload::text
				FROM payment_webhook_events WHERE id = ?""", (rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("providerId", rs.getObject(1, UUID.class));
			m.put("accountId", rs.getObject(2, UUID.class));
			m.put("status", rs.getString(3));
			m.put("attempts", rs.getInt(4));
			m.put("payload", rs.getString(5));
			return m;
		}, eventId).stream().findFirst().orElse(null);
		if (row == null || "PROCESSED".equals(row.get("status")) || "IGNORED".equals(row.get("status"))) {
			return;
		}
		int attempts = (Integer) row.get("attempts") + 1;
		JsonNode payload = json.readTree((String) row.get("payload"));
		String merchantRef = text(payload.path("merchantReference"));
		String providerRef = text(payload.path("providerReference"));
		UUID attemptId = findAttempt((UUID) row.get("providerId"), (UUID) row.get("accountId"), merchantRef,
				providerRef);
		if (attemptId == null) {
			if (attempts < MAX_ATTEMPT_LOOKUPS) {
				markProcessing(eventId, attempts, "ATTEMPT_NOT_FOUND");
				throw new IllegalStateException("webhook attempt not found yet");
			}
			finish(eventId, "IGNORED", attempts, "ATTEMPT_NOT_FOUND");
			log.warn("billing webhook ignored: no matching attempt webhookEventId={}", eventId);
			return;
		}
		PaymentVerifier.Outcome outcome = verifier.verify(attemptId, "WEBHOOK");
		switch (outcome) {
			case PROVIDER_UNAVAILABLE, NO_ADAPTER -> {
				markProcessing(eventId, attempts, outcome.name());
				throw new IllegalStateException("provider verification unavailable");
			}
			default -> finish(eventId, "PROCESSED", attempts, null);
		}
	}

	private UUID findAttempt(UUID providerId, UUID accountId, String merchantRef, String providerRef) {
		if (merchantRef != null) {
			try {
				UUID byNonce = UUID.fromString(merchantRef);
				Optional<UUID> hit = jdbc.query("""
						SELECT id FROM payment_attempts
						WHERE id = ? AND provider_id = ? AND provider_account_id = ? AND metadata->>'nonce' = ?""",
						(rs, i) -> rs.getObject(1, UUID.class), byNonce, providerId, accountId, merchantRef).stream()
						.findFirst();
				if (hit.isPresent()) {
					return hit.get();
				}
			}
			catch (IllegalArgumentException e) {
				// not one of our references
			}
		}
		if (providerRef != null) {
			return jdbc.query("""
					SELECT id FROM payment_attempts
					WHERE provider_id = ? AND provider_account_id = ? AND provider_payment_reference = ?""",
					(rs, i) -> rs.getObject(1, UUID.class), providerId, accountId, providerRef).stream().findFirst()
					.orElse(null);
		}
		return null;
	}

	private void markProcessing(UUID eventId, int attempts, String code) {
		// Written in its own transaction: the dispatcher rolls the handler's transaction back to retry.
		new TransactionTemplate(transactions.getTransactionManager(), requiresNew()).executeWithoutResult(s -> jdbc
				.update("""
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

	Optional<Account> account(String providerCode, String accountCode) {
		return jdbc.query("""
				SELECT p.id, a.id, a.country_code, a.currency_code, a.environment, a.merchant_account_reference
				FROM payment_providers p
				JOIN payment_provider_accounts a ON a.provider_id = p.id
				WHERE p.provider_code = ? AND a.account_code = ?""",
				(rs, i) -> new Account(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
						new MerchantAccount(providerCode, accountCode, rs.getString(3), rs.getString(4),
								PaymentEnvironment.valueOf(rs.getString(5)), rs.getString(6))),
				providerCode, accountCode).stream().findFirst();
	}

	private static org.springframework.transaction.TransactionDefinition requiresNew() {
		org.springframework.transaction.support.DefaultTransactionDefinition d = new org.springframework.transaction.support.DefaultTransactionDefinition();
		d.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return d;
	}

	private static String text(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}
}
