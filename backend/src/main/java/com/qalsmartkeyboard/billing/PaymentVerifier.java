package com.qalsmartkeyboard.billing;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.billing.PaymentStates.AttemptStatus;
import com.qalsmartkeyboard.billing.PaymentStates.OrderStatus;
import com.qalsmartkeyboard.billing.spi.MerchantAccount;
import com.qalsmartkeyboard.billing.spi.Money;
import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.billing.spi.PaymentProvider;
import com.qalsmartkeyboard.billing.spi.PaymentProviderException;
import com.qalsmartkeyboard.billing.spi.ProviderPaymentStatus;
import com.qalsmartkeyboard.billing.spi.VerificationRequest;
import com.qalsmartkeyboard.billing.spi.VerificationResult;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import tools.jackson.databind.json.JsonMapper;

/**
 * Verify, then grant (SD 16.1 rule 3, 16.9 steps 3-5; N7). The only path that marks an attempt SUCCEEDED, an order
 * PAID or creates a subscription term. Webhooks, the checkout return URL and the reconciliation job all call
 * {@link #verify}; none of them changes payment state on its own.
 *
 * <ol>
 * <li>The provider is asked server to server (outside any lock).</li>
 * <li>A SUCCEEDED answer is accepted only when the provider reference, our merchant reference, the amount and the
 * currency all equal the attempt's; anything else leaves the attempt UNKNOWN with {@code VERIFICATION_MISMATCH} for
 * review and grants nothing.</li>
 * <li>In one transaction, under row locks (attempt, then order): the attempt becomes SUCCEEDED, a CAPTURE row is
 * appended to {@code payment_transactions} (idempotent per attempt), the order becomes PAID and is fulfilled (term,
 * allocation, {@code subscription_events}, receipt) and {@code billing.subscription.changed} is written to the outbox
 * for the entitlements module. A second verified capture on an already paid order is recorded and flagged for refund
 * (step A2), never granted twice.</li>
 * <li>FAILED / CANCELLED / EXPIRED close an open attempt without any grant; a checkout never started is EXPIRED after
 * its expiry plus a grace period. PENDING keeps it open.</li>
 * </ol>
 * Every outcome is idempotent: calling again after success is a no-op.
 */
final class PaymentVerifier {

	private static final Logger log = LoggerFactory.getLogger(PaymentVerifier.class);

	enum Outcome {
		NOT_FOUND, ALREADY_SUCCEEDED, NO_ADAPTER, PROVIDER_UNAVAILABLE, PAID, DUPLICATE_CAPTURE, MISMATCH, CLOSED,
		STILL_OPEN
	}

	record Context(UUID attemptId, UUID userId, UUID orderId, AttemptStatus status, String providerReference,
			String nonce, Instant expiresAt, UUID providerId, UUID accountId, UUID baseMethodId, UUID accountMethodId,
			String currency, PaymentEnvironment environment, String providerCode, String accountCode,
			String accountCountry, String merchantAccountReference, long amountMinor, int exponent) {
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final ProviderRegistry registry;
	private final Fulfilment fulfilment;
	private final OutboxPublisher outbox;
	private final BillingProperties properties;
	private final JsonMapper json;
	private final Clock clock;

	PaymentVerifier(JdbcTemplate jdbc, TransactionTemplate transactions, ProviderRegistry registry,
			Fulfilment fulfilment, OutboxPublisher outbox, BillingProperties properties, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.registry = registry;
		this.fulfilment = fulfilment;
		this.outbox = outbox;
		this.properties = properties;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * Verifies one attempt with its provider and applies the result.
	 *
	 * @param trigger {@code WEBHOOK}, {@code RETURN}, {@code RECONCILIATION} (recorded, never trusted)
	 */
	Outcome verify(UUID attemptId, String trigger) {
		Context ctx = context(attemptId).orElse(null);
		if (ctx == null) {
			return Outcome.NOT_FOUND;
		}
		if (ctx.status() == AttemptStatus.SUCCEEDED) {
			return Outcome.ALREADY_SUCCEEDED;
		}
		Optional<PaymentProvider> provider = registry.gateway(ctx.providerCode(), ctx.environment());
		if (provider.isEmpty()) {
			log.warn("billing verification skipped: no adapter attemptId={} provider={} environment={}", attemptId,
					ctx.providerCode(), ctx.environment());
			return Outcome.NO_ADAPTER;
		}
		Money expected = new Money(ctx.amountMinor(), ctx.currency(), ctx.exponent());
		VerificationResult result;
		try {
			result = provider.get().verify(new VerificationRequest(new MerchantAccount(ctx.providerCode(),
					ctx.accountCode(), ctx.accountCountry(), ctx.currency(), ctx.environment(),
					ctx.merchantAccountReference()), ctx.providerReference(), ctx.nonce(), expected));
		}
		catch (PaymentProviderException e) {
			log.warn("billing verification unavailable attemptId={} provider={} kind={} code={} trigger={}", attemptId,
					ctx.providerCode(), e.kind(), e.safeCode(), trigger);
			touch(attemptId, "PROVIDER_" + e.kind().name());
			return Outcome.PROVIDER_UNAVAILABLE;
		}
		Outcome outcome = transactions.execute(s -> apply(ctx, result, expected, trigger));
		log.info("billing verification attemptId={} provider={} providerStatus={} outcome={} trigger={}", attemptId,
				ctx.providerCode(), result.status(), outcome, trigger);
		return outcome;
	}

	private Outcome apply(Context ctx, VerificationResult result, Money expected, String trigger) {
		Instant now = clock.instant();
		AttemptStatus current = jdbc.query("SELECT attempt_status FROM payment_attempts WHERE id = ? FOR UPDATE",
				(rs, i) -> AttemptStatus.valueOf(rs.getString(1)), ctx.attemptId()).stream().findFirst().orElse(null);
		if (current == null) {
			return Outcome.NOT_FOUND;
		}
		if (current == AttemptStatus.SUCCEEDED) {
			return Outcome.ALREADY_SUCCEEDED;
		}
		Map<String, Object> check = new LinkedHashMap<>();
		check.put("lastCheckedAt", now.toString());
		check.put("lastCheckTrigger", trigger);
		check.put("lastProviderStatus", result.status().name());

		switch (result.status()) {
			case SUCCEEDED -> {
				String mismatch = mismatch(ctx, result, expected);
				if (mismatch != null) {
					check.put("mismatch", mismatch);
					updateAttempt(ctx.attemptId(), current.canMoveTo(AttemptStatus.UNKNOWN) ? AttemptStatus.UNKNOWN
							: current, "VERIFICATION_MISMATCH", null, check);
					log.error("billing verification MISMATCH attemptId={} provider={} field={} (nothing granted)",
							ctx.attemptId(), ctx.providerCode(), mismatch);
					return Outcome.MISMATCH;
				}
				return capture(ctx, result, expected, current, check, now, trigger);
			}
			case FAILED, CANCELLED, EXPIRED -> {
				AttemptStatus next = AttemptStatus.fromProvider(result.status());
				if (current.canMoveTo(next) && current.isOpen()) {
					String code = result.failureCode() != null ? result.failureCode() : "PROVIDER_" + result.status();
					updateAttempt(ctx.attemptId(), next, code, now, check);
					if (result.status() == ProviderPaymentStatus.FAILED && result.providerTransactionReference() != null
							&& ctx.amountMinor() > 0) {
						ledger(ctx, result, "FAILED", "failed:" + ctx.attemptId(), now, trigger);
					}
					return Outcome.CLOSED;
				}
				mergeMetadata(ctx.attemptId(), check);
				return current.isOpen() ? Outcome.STILL_OPEN : Outcome.CLOSED;
			}
			case PENDING -> {
				if (current == AttemptStatus.INITIATED || current == AttemptStatus.REQUIRES_ACTION
						|| current == AttemptStatus.UNKNOWN && ctx.providerReference() != null) {
					updateAttempt(ctx.attemptId(), AttemptStatus.PENDING, null, null, check);
				}
				else {
					mergeMetadata(ctx.attemptId(), check);
				}
				adoptReference(ctx, result);
				return current.isOpen() ? Outcome.STILL_OPEN : Outcome.CLOSED;
			}
			case NOT_STARTED -> {
				adoptReference(ctx, result);
				Instant deadline = ctx.expiresAt() == null ? null
						: ctx.expiresAt().plus(properties.reconciliation().checkoutGrace());
				if (current.isOpen() && deadline != null && now.isAfter(deadline)) {
					updateAttempt(ctx.attemptId(), AttemptStatus.EXPIRED, "CHECKOUT_EXPIRED", now, check);
					return Outcome.CLOSED;
				}
				mergeMetadata(ctx.attemptId(), check);
				return current.isOpen() ? Outcome.STILL_OPEN : Outcome.CLOSED;
			}
			default -> {
				if (result.failureCode() != null) {
					check.put("lastCheckCode", result.failureCode());
				}
				mergeMetadata(ctx.attemptId(), check);
				return Outcome.STILL_OPEN;
			}
		}
	}

	/** Null when the provider's answer matches the attempt, else the first field that differs. */
	static String mismatch(Context ctx, VerificationResult result, Money expected) {
		if (ctx.providerReference() != null && result.providerReference() != null
				&& !ctx.providerReference().equals(result.providerReference())) {
			return "providerReference";
		}
		if (ctx.providerReference() == null && result.providerReference() == null) {
			return "providerReference";
		}
		if (!Objects.equals(ctx.nonce(), result.merchantReference())) {
			return "merchantReference";
		}
		if (result.amount() == null || !expected.sameAmount(result.amount())) {
			return "amount";
		}
		return null;
	}

	private Outcome capture(Context ctx, VerificationResult result, Money expected, AttemptStatus current,
			Map<String, Object> check, Instant now, String trigger) {
		if (result.providerTransactionReference() != null) {
			check.put("providerTransactionReference", result.providerTransactionReference());
		}
		if (result.providerMethodCode() != null) {
			check.put("providerMethodCode", result.providerMethodCode());
		}
		String reference = ctx.providerReference() != null ? ctx.providerReference() : result.providerReference();
		jdbc.update("""
				UPDATE payment_attempts
				   SET attempt_status = 'SUCCEEDED', verified_at = ?, failure_code = NULL,
				       provider_payment_reference = COALESCE(provider_payment_reference, ?),
				       metadata = metadata || CAST(? AS JSONB), updated_at = ?
				 WHERE id = ?""", OrderService.utc(now), reference, json.writeValueAsString(check),
				OrderService.utc(now), ctx.attemptId());
		UUID transactionId = ledger(ctx, result, "SUCCEEDED", "capture:" + ctx.attemptId(), now, trigger);

		OrderStatus order = jdbc.query("SELECT order_status FROM payment_orders WHERE id = ? FOR UPDATE",
				(rs, i) -> OrderStatus.valueOf(rs.getString(1)), ctx.orderId()).stream().findFirst().orElseThrow();
		if (!order.canMoveTo(OrderStatus.PAID)) {
			jdbc.update("""
					UPDATE payment_attempts SET metadata = metadata || '{"duplicateCapture": true, "refundRequired": true}'::JSONB
					WHERE id = ?""", ctx.attemptId());
			log.error("billing duplicate capture on a {} order orderId={} attemptId={}: recorded, refund required "
					+ "(no second grant)", order, ctx.orderId(), ctx.attemptId());
			return Outcome.DUPLICATE_CAPTURE;
		}
		jdbc.update("UPDATE payment_orders SET order_status = 'PAID', paid_at = ?, updated_at = ? WHERE id = ?",
				OrderService.utc(now), OrderService.utc(now), ctx.orderId());
		UUID subscriptionId = fulfilment.fulfil(ctx, transactionId, now);
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("userId", ctx.userId().toString());
		event.put("subscriptionId", subscriptionId.toString());
		event.put("orderId", ctx.orderId().toString());
		outbox.publish("user_subscription", subscriptionId.toString(), BillingEvents.SUBSCRIPTION_CHANGED, event);
		if (current != AttemptStatus.REQUIRES_ACTION && current != AttemptStatus.PENDING
				&& current != AttemptStatus.INITIATED) {
			log.warn("billing late capture honoured attemptId={} previousStatus={} orderStatus={}", ctx.attemptId(),
					current, order);
		}
		return Outcome.PAID;
	}

	/** Appends one ledger row (idempotent per key) and returns its id. */
	private UUID ledger(Context ctx, VerificationResult result, String status, String key, Instant now,
			String trigger) {
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("trigger", trigger);
		if (result.providerMethodCode() != null) {
			meta.put("providerMethodCode", result.providerMethodCode());
		}
		// A FAILED row keeps the provider reference in metadata: the unique (provider, reference, kind) index must stay
		// free for a later verified capture with the same provider transaction id.
		String reference = "SUCCEEDED".equals(status) ? result.providerTransactionReference() : null;
		if (reference == null && result.providerTransactionReference() != null) {
			meta.put("providerTransactionReference", result.providerTransactionReference());
		}
		UUID id = UUID.randomUUID();
		int inserted = jdbc.update("""
				INSERT INTO payment_transactions (id, payment_attempt_id, provider_id, transaction_kind, transaction_status,
				       provider_transaction_reference, amount_minor, currency_code, idempotency_key, occurred_at,
				       verified_at, metadata, created_at)
				VALUES (?, ?, ?, 'CAPTURE', ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
				ON CONFLICT (provider_id, idempotency_key) DO NOTHING""", id, ctx.attemptId(), ctx.providerId(), status,
				reference, ctx.amountMinor(), ctx.currency(), key,
				OrderService.utc(result.occurredAt()), OrderService.utc(now), json.writeValueAsString(meta),
				OrderService.utc(now));
		if (inserted == 1) {
			return id;
		}
		return jdbc.queryForObject("SELECT id FROM payment_transactions WHERE provider_id = ? AND idempotency_key = ?",
				UUID.class, ctx.providerId(), key);
	}

	private void adoptReference(Context ctx, VerificationResult result) {
		if (ctx.providerReference() == null && result.providerReference() != null) {
			jdbc.update("""
					UPDATE payment_attempts SET provider_payment_reference = ?, updated_at = ?
					WHERE id = ? AND provider_payment_reference IS NULL""", result.providerReference(),
					OrderService.utc(clock.instant()), ctx.attemptId());
		}
	}

	private void updateAttempt(UUID attemptId, AttemptStatus status, String failureCode, Instant verifiedAt,
			Map<String, Object> metadata) {
		jdbc.update("""
				UPDATE payment_attempts
				   SET attempt_status = ?, failure_code = COALESCE(?, failure_code), verified_at = COALESCE(?, verified_at),
				       metadata = metadata || CAST(? AS JSONB), updated_at = ?
				 WHERE id = ?""", status.name(), failureCode, OrderService.utc(verifiedAt),
				json.writeValueAsString(metadata), OrderService.utc(clock.instant()), attemptId);
	}

	private void mergeMetadata(UUID attemptId, Map<String, Object> metadata) {
		jdbc.update("UPDATE payment_attempts SET metadata = metadata || CAST(? AS JSONB), updated_at = ? WHERE id = ?",
				json.writeValueAsString(metadata), OrderService.utc(clock.instant()), attemptId);
	}

	/** Records that a check happened (so the reconciliation job paces itself) when the provider could not answer. */
	private void touch(UUID attemptId, String code) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("lastCheckedAt", clock.instant().toString());
		m.put("lastCheckCode", code);
		transactions.executeWithoutResult(s -> mergeMetadata(attemptId, m));
	}

	Optional<Context> context(UUID attemptId) {
		return jdbc.query("""
				SELECT a.id, a.user_id, a.payment_order_id, a.attempt_status, a.provider_payment_reference,
				       a.metadata->>'nonce', a.expires_at, a.provider_id, a.provider_account_id,
				       a.provider_payment_method_id, a.provider_account_method_id, a.currency_code, a.environment,
				       p.provider_code, acc.account_code, acc.country_code, acc.merchant_account_reference, o.amount_minor,
				       c.minor_unit_exponent
				FROM payment_attempts a
				JOIN payment_providers p ON p.id = a.provider_id
				JOIN payment_provider_accounts acc ON acc.id = a.provider_account_id
				JOIN payment_orders o ON o.id = a.payment_order_id
				JOIN billing_currencies c ON c.currency_code = a.currency_code
				WHERE a.id = ?""",
				(rs, i) -> new Context(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
						rs.getObject(3, UUID.class), AttemptStatus.valueOf(rs.getString(4)), rs.getString(5),
						rs.getString(6), JdbcRoutingData.instant(rs, 7), rs.getObject(8, UUID.class),
						rs.getObject(9, UUID.class), rs.getObject(10, UUID.class), rs.getObject(11, UUID.class),
						rs.getString(12), PaymentEnvironment.valueOf(rs.getString(13)), rs.getString(14),
						rs.getString(15), rs.getString(16), rs.getString(17), rs.getLong(18), rs.getInt(19)),
				attemptId).stream().findFirst();
	}
}
