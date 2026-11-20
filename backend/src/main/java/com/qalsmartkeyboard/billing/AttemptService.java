package com.qalsmartkeyboard.billing;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.billing.BillingDtos.CreateAttemptRequest;
import com.qalsmartkeyboard.billing.BillingDtos.NextActionDto;
import com.qalsmartkeyboard.billing.BillingDtos.PaymentAttemptDto;
import com.qalsmartkeyboard.billing.OrderService.OrderRow;
import com.qalsmartkeyboard.billing.PaymentStates.AttemptStatus;
import com.qalsmartkeyboard.billing.PaymentStates.OrderStatus;
import com.qalsmartkeyboard.billing.ProviderRouting.Decision;
import com.qalsmartkeyboard.billing.ProviderRouting.MethodOption;
import com.qalsmartkeyboard.billing.spi.CheckoutRequest;
import com.qalsmartkeyboard.billing.spi.CheckoutSession;
import com.qalsmartkeyboard.billing.spi.MerchantAccount;
import com.qalsmartkeyboard.billing.spi.Money;
import com.qalsmartkeyboard.billing.spi.PaymentProvider;
import com.qalsmartkeyboard.billing.spi.PaymentProviderException;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.geo.CountryResolution.Confidence;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /billing/orders/{orderId}/attempts} and {@code GET .../attempts/{attemptId}} (SD 16.6, ADR 0045).
 *
 * <p>The attempt binds the order to the provider, merchant account and account method the routing layer offers for the
 * order's market, channel, currency and amount (and the database guard re-checks it). It is committed as INITIATED with
 * our merchant reference (the attempt id, ArifPay's {@code nonce}) before the provider is called, so a callback that
 * races the start call finds it. The hosted checkout then moves it to REQUIRES_ACTION. A provider that cannot be
 * reached leaves it UNKNOWN (the session may exist: the reconciliation job looks it up by merchant reference) and
 * answers 503; a refusal marks it FAILED (422). While a checkout of the same method is open and unexpired, a new
 * request returns that attempt instead of opening a second provider session (ArifPay: one session per order).
 */
final class AttemptService {

	private static final Logger log = LoggerFactory.getLogger(AttemptService.class);

	record AttemptRow(UUID id, UUID orderId, int attemptNumber, AttemptStatus status, String methodCode,
			String providerCode, String checkoutUrl, String failureCode, String failureMessage, Instant expiresAt,
			Instant verifiedAt, Instant createdAt, String fingerprint, UUID accountMethodId) {
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final OrderService orders;
	private final ProviderRouting routing;
	private final ProviderRegistry registry;
	private final BillingProperties properties;
	private final JsonMapper json;
	private final Clock clock;

	AttemptService(JdbcTemplate jdbc, TransactionTemplate transactions, OrderService orders, ProviderRouting routing,
			ProviderRegistry registry, BillingProperties properties, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.orders = orders;
		this.routing = routing;
		this.registry = registry;
		this.properties = properties;
		this.json = json;
		this.clock = clock;
	}

	PaymentAttemptDto create(AuthenticatedUser user, UUID orderId, String idempotencyKeyHeader,
			CreateAttemptRequest body) {
		String key = BillingErrors.idempotencyKey(idempotencyKeyHeader);
		String fingerprint = BillingErrors.sha256Hex(orderId + "|" + body.methodCode());
		Optional<AttemptRow> replay = byKey(user.userId(), key);
		if (replay.isPresent()) {
			return replayed(replay.get(), orderId, fingerprint);
		}
		if (body.returnUrl() != null && !body.returnUrl().startsWith(properties.appReturnUri().toString())) {
			throw BillingErrors.invalid("returnUrl", "NOT_ALLOWED", "The return URL is not allowed.");
		}
		OrderRow order = orders.load(user.userId(), orderId).orElseThrow(BillingErrors::notFound);
		Instant now = clock.instant();
		if (order.status() == OrderStatus.EXPIRED || order.status().isPayable() && order.expiredAt(now)) {
			throw BillingErrors.problem(ApiErrorCode.ORDER_EXPIRED, "This order has expired; create a new one.");
		}
		if (!order.status().isPayable()) {
			throw BillingErrors.problem(ApiErrorCode.ORDER_NOT_PAYABLE, "This order cannot be paid.");
		}
		Decision decision = routing.decide(new ProviderRouting.Query(order.marketCountry(), Confidence.HIGH,
				order.channel(), order.currency(), order.amountMinor()));
		MethodOption option = decision.method(body.methodCode()).filter(m -> m.method().accountId() != null)
				.orElseThrow(() -> BillingErrors.problem(ApiErrorCode.PAYMENT_METHOD_UNAVAILABLE,
						"This payment method is not available for this order."));
		PaymentProvider provider = registry.gateway(option.providerCode(), properties.environment())
				.orElseThrow(() -> BillingErrors.problem(ApiErrorCode.PAYMENT_METHOD_UNAVAILABLE,
						"This payment method is not available for this order."));
		if (properties.publicBaseUrl() == null) {
			log.error("qal.billing.public-base-url (QAL_PUBLIC_BASE_URL) is not set: hosted checkout cannot start");
			throw BillingErrors.problem(ApiErrorCode.PAYMENT_METHOD_UNAVAILABLE,
					"This payment method is not available right now.");
		}

		Optional<AttemptRow> open = openCheckout(orderId, option.method().accountMethodId(), now);
		if (open.isPresent()) {
			return dto(open.get());
		}

		UUID attemptId = UUID.randomUUID();
		String nonce = attemptId.toString();
		Instant expiresAt = now.plus(properties.attemptTtl());
		if (order.expiresAt() != null && order.expiresAt().isBefore(expiresAt)) {
			expiresAt = order.expiresAt();
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("nonce", nonce);
		metadata.put("requestFingerprint", fingerprint);
		metadata.put("methodCode", body.methodCode());
		Instant attemptExpiry = expiresAt;
		try {
			transactions.executeWithoutResult(s -> {
				jdbc.query("SELECT id FROM payment_orders WHERE id = ? FOR UPDATE", (rs, i) -> rs.getObject(1),
						orderId);
				jdbc.update("""
						INSERT INTO payment_attempts (id, payment_order_id, user_id, attempt_number, provider_id,
						       provider_account_id, provider_payment_method_id, provider_account_method_id,
						       market_country_code, currency_code, environment, attempt_status, idempotency_key,
						       expires_at, metadata, created_at, updated_at)
						SELECT ?, ?, ?, COALESCE(MAX(attempt_number), 0) + 1,
						       (SELECT id FROM payment_providers WHERE provider_code = ?), ?, ?, ?, ?, ?, ?, 'INITIATED',
						       ?, ?, CAST(? AS JSONB), ?, ?
						FROM payment_attempts WHERE payment_order_id = ?""", attemptId, orderId, user.userId(),
						option.providerCode(), option.method().accountId(), option.method().baseMethodId(),
						option.method().accountMethodId(), order.marketCountry(), order.currency(),
						properties.environment().name(), key, OrderService.utc(attemptExpiry),
						json.writeValueAsString(metadata), OrderService.utc(now), OrderService.utc(now), orderId);
			});
		}
		catch (DuplicateKeyException e) {
			return byKey(user.userId(), key).map(r -> replayed(r, orderId, fingerprint))
					.orElseThrow(() -> BillingErrors.problem(ApiErrorCode.REQUEST_IN_PROGRESS, "Please retry."));
		}
		catch (DataAccessException e) {
			log.warn("billing attempt refused by the database guard orderId={} method={}", orderId, body.methodCode());
			throw BillingErrors.problem(ApiErrorCode.PAYMENT_METHOD_UNAVAILABLE,
					"This payment method is not available for this order.");
		}

		URI base = properties.publicBaseUrl();
		String origin = base.toString().endsWith("/") ? base.toString().substring(0, base.toString().length() - 1)
				: base.toString();
		URI returnBase = URI.create(origin + "/api/v1/billing/return/" + attemptId);
		MerchantAccount account = new MerchantAccount(option.providerCode(), option.method().accountCode(),
				order.marketCountry(), option.method().currencyCode(), properties.environment(),
				option.method().merchantAccountReference());
		CheckoutRequest request = new CheckoutRequest(account, nonce, order.orderReference(),
				new Money(order.amountMinor(), order.currency(), order.exponent()), itemName(order),
				body.methodCode(), option.method().providerMethodCode(), URI.create(returnBase + "?result=success"),
				URI.create(returnBase + "?result=cancel"), URI.create(returnBase + "?result=error"),
				URI.create(origin + "/api/v1/billing/webhooks/" + option.providerCode() + "/"
						+ option.method().accountCode()),
				attemptExpiry, body.locale());
		CheckoutSession session;
		try {
			session = provider.initiateCheckout(request);
		}
		catch (PaymentProviderException e) {
			boolean unknown = e.kind() == PaymentProviderException.Kind.UNAVAILABLE
					|| e.kind() == PaymentProviderException.Kind.INVALID_RESPONSE;
			close(attemptId, unknown ? AttemptStatus.UNKNOWN : AttemptStatus.FAILED,
					unknown ? "PROVIDER_UNAVAILABLE" : "PROVIDER_REJECTED");
			log.warn("billing checkout start failed attemptId={} provider={} kind={} code={}", attemptId,
					option.providerCode(), e.kind(), e.safeCode());
			if (unknown) {
				throw BillingErrors.problem(ApiErrorCode.PROVIDER_UNAVAILABLE,
						"The payment provider is not reachable. Try again in a moment.");
			}
			throw BillingErrors.problem(ApiErrorCode.PAYMENT_METHOD_UNAVAILABLE,
					"The payment provider refused this payment. Try another method.");
		}
		Instant sessionExpiry = session.expiresAt() != null && session.expiresAt().isBefore(attemptExpiry)
				? session.expiresAt()
				: attemptExpiry;
		try {
			transactions.executeWithoutResult(s -> jdbc.update("""
					UPDATE payment_attempts
					   SET provider_payment_reference = ?, checkout_url = ?, attempt_status = 'REQUIRES_ACTION',
					       expires_at = ?, updated_at = ?
					 WHERE id = ? AND attempt_status = 'INITIATED'""", session.providerReference(),
					session.checkoutUrl().toString(), OrderService.utc(sessionExpiry), OrderService.utc(clock.instant()),
					attemptId));
		}
		catch (DuplicateKeyException e) {
			close(attemptId, AttemptStatus.UNKNOWN, "PROVIDER_REFERENCE_REUSED");
			log.error("billing provider returned a checkout reference already used attemptId={} provider={}", attemptId,
					option.providerCode());
			throw BillingErrors.problem(ApiErrorCode.PROVIDER_UNAVAILABLE, "Try again in a moment.");
		}
		log.info("billing checkout started attemptId={} orderId={} provider={} account={}", attemptId, orderId,
				option.providerCode(), option.method().accountCode());
		return dto(load(user.userId(), orderId, attemptId).orElseThrow());
	}

	PaymentAttemptDto get(UUID userId, UUID orderId, UUID attemptId) {
		return dto(load(userId, orderId, attemptId).orElseThrow(BillingErrors::notFound));
	}

	private void close(UUID attemptId, AttemptStatus status, String failureCode) {
		transactions.executeWithoutResult(s -> jdbc.update("""
				UPDATE payment_attempts SET attempt_status = ?, failure_code = ?, updated_at = ?
				WHERE id = ? AND attempt_status = 'INITIATED'""", status.name(), failureCode,
				OrderService.utc(clock.instant()), attemptId));
	}

	private PaymentAttemptDto replayed(AttemptRow existing, UUID orderId, String fingerprint) {
		if (!existing.orderId().equals(orderId) || !fingerprint.equals(existing.fingerprint())) {
			throw BillingErrors.problem(ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
					"This Idempotency-Key was used for a different payment attempt.");
		}
		return dto(existing);
	}

	private Optional<AttemptRow> openCheckout(UUID orderId, UUID accountMethodId, Instant now) {
		return jdbc.query(SELECT_ATTEMPT + """
				 WHERE a.payment_order_id = ? AND a.provider_account_method_id = ?
				   AND a.attempt_status IN ('REQUIRES_ACTION', 'PENDING') AND a.checkout_url IS NOT NULL
				   AND a.expires_at > ?
				 ORDER BY a.attempt_number DESC LIMIT 1""", AttemptService::row, orderId, accountMethodId,
				OrderService.utc(now)).stream().findFirst();
	}

	private Optional<AttemptRow> byKey(UUID userId, String key) {
		return jdbc.query(SELECT_ATTEMPT + " WHERE a.user_id = ? AND a.idempotency_key = ?", AttemptService::row,
				userId, key).stream().findFirst();
	}

	Optional<AttemptRow> load(UUID userId, UUID orderId, UUID attemptId) {
		return jdbc.query(SELECT_ATTEMPT + " WHERE a.id = ? AND a.user_id = ? AND a.payment_order_id = ?",
				AttemptService::row, attemptId, userId, orderId).stream().findFirst();
	}

	PaymentAttemptDto dto(AttemptRow a) {
		Instant now = clock.instant();
		boolean redirect = (a.status() == AttemptStatus.REQUIRES_ACTION || a.status() == AttemptStatus.PENDING)
				&& a.checkoutUrl() != null && (a.expiresAt() == null || a.expiresAt().isAfter(now));
		NextActionDto next = redirect ? new NextActionDto("REDIRECT", a.checkoutUrl()) : new NextActionDto("NONE", null);
		return new PaymentAttemptDto(a.id(), a.attemptNumber(), a.status().name(), a.methodCode(), a.providerCode(),
				next, a.failureCode(), a.failureMessage() != null ? a.failureMessage() : failureMessage(a.failureCode()),
				a.expiresAt(), a.verifiedAt(), a.createdAt());
	}

	/** Safe, static messages per failure code (never provider prose). */
	static String failureMessage(String code) {
		if (code == null) {
			return null;
		}
		return switch (code) {
			case "PROVIDER_UNAVAILABLE" -> "The payment provider could not be reached. We will check this payment again.";
			case "PROVIDER_REJECTED" -> "The payment provider refused this payment. Try another method.";
			case "CHECKOUT_EXPIRED" -> "The payment page expired before the payment was made.";
			case "VERIFICATION_MISMATCH" -> "This payment is being reviewed.";
			default -> "The payment was not completed.";
		};
	}

	private static String itemName(OrderRow order) {
		String term = switch (order.billingPeriod()) {
			case "MONTHLY" -> "30 days";
			case "ANNUAL" -> "1 year";
			case "QUARTERLY" -> "90 days";
			case "WEEKLY" -> "7 days";
			default -> order.billingPeriod();
		};
		return order.productName() + " (" + term + ")";
	}

	private static final String SELECT_ATTEMPT = """
			SELECT a.id, a.payment_order_id, a.attempt_number, a.attempt_status, m.method_code, p.provider_code,
			       a.checkout_url, a.failure_code, a.failure_message_safe, a.expires_at, a.verified_at, a.created_at,
			       a.metadata->>'requestFingerprint', a.provider_account_method_id
			FROM payment_attempts a
			JOIN provider_payment_methods m ON m.id = a.provider_payment_method_id
			JOIN payment_providers p ON p.id = a.provider_id
			""";

	private static AttemptRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new AttemptRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
				AttemptStatus.valueOf(rs.getString(4)), rs.getString(5), rs.getString(6), rs.getString(7),
				rs.getString(8), rs.getString(9), JdbcRoutingData.instant(rs, 10), JdbcRoutingData.instant(rs, 11),
				JdbcRoutingData.instant(rs, 12), rs.getString(13), rs.getObject(14, UUID.class));
	}
}
