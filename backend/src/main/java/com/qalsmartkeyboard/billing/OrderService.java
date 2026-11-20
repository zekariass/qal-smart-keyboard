package com.qalsmartkeyboard.billing;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.billing.BillingCatalog.PriceRow;
import com.qalsmartkeyboard.billing.BillingDtos.CreateOrderRequest;
import com.qalsmartkeyboard.billing.BillingDtos.FulfilmentDto;
import com.qalsmartkeyboard.billing.BillingDtos.MoneyDto;
import com.qalsmartkeyboard.billing.BillingDtos.OrderAmountsDto;
import com.qalsmartkeyboard.billing.BillingDtos.PaymentMethodOptionDto;
import com.qalsmartkeyboard.billing.BillingDtos.PaymentOrderDto;
import com.qalsmartkeyboard.billing.PaymentStates.OrderStatus;
import com.qalsmartkeyboard.billing.ProviderRouting.Decision;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.geo.CountryResolution;
import com.qalsmartkeyboard.platform.geo.CountryResolution.Confidence;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /billing/orders}, {@code GET /billing/orders/{orderId}} and {@code POST .../cancel} (SD 16.6, ADR 0045).
 *
 * <p>Order creation: registered users only; {@code Idempotency-Key} scoped to the user (same key and body returns the
 * same order, another body is 409 {@code IDEMPOTENCY_KEY_REUSED}); the market must equal the server-resolved market
 * (403 {@code BILLING_POLICY_DENIED}); the market/channel policy must allow an in-app gateway flow with at least one
 * eligible method for the amount; the server prices from {@code billing_prices} and snapshots list, discount, tax and
 * total. The resolved country and the signal summary (no IP address) go into {@code payment_orders.metadata}. Promotions
 * (M4.4), upgrades (M4.4), credit packs and AUTO_RENEW (mandates) are not part of this step.
 */
final class OrderService {

	private static final Logger log = LoggerFactory.getLogger(OrderService.class);
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final char[] REF_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
	private static final DateTimeFormatter YYMM = DateTimeFormatter.ofPattern("yyMM");

	/** An order as the API shows it. */
	record OrderRow(UUID id, UUID userId, String orderReference, String orderType, OrderStatus status,
			String productCode, String productName, String billingPeriod, String renewalModel, String marketCountry,
			String salesChannel, String taxBehavior, long listMinor, long discountMinor, long taxMinor,
			long amountMinor, String currency, int exponent, Instant expiresAt, Instant paidAt, Instant createdAt,
			String fingerprint, UUID subscriptionId, UUID receiptId) {

		Channel channel() {
			return Channel.ofSalesChannel(salesChannel).orElseThrow();
		}

		boolean expiredAt(Instant now) {
			return expiresAt != null && !expiresAt.isAfter(now);
		}
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final ProviderRouting routing;
	private final BillingCatalog catalog;
	private final BillingProperties properties;
	private final JsonMapper json;
	private final Clock clock;

	OrderService(JdbcTemplate jdbc, TransactionTemplate transactions, ProviderRouting routing, BillingCatalog catalog,
			BillingProperties properties, JsonMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.routing = routing;
		this.catalog = catalog;
		this.properties = properties;
		this.json = json;
		this.clock = clock;
	}

	PaymentOrderDto create(AuthenticatedUser user, String idempotencyKeyHeader, CreateOrderRequest body,
			String platformHeader, CountryResolution resolution) {
		if (user.isGuest()) {
			throw BillingErrors.problem(ApiErrorCode.GUEST_NOT_ALLOWED, "Sign in to buy a plan.");
		}
		String key = BillingErrors.idempotencyKey(idempotencyKeyHeader);
		Channel channel = Channel.ofSalesChannel(body.salesChannel())
				.orElseThrow(() -> BillingErrors.invalid("salesChannel", "NOT_SUPPORTED",
						"This sales channel cannot create orders."));
		if (platformHeader != null && !platformHeader.equals(channel.platform())) {
			throw BillingErrors.invalid("salesChannel", "PLATFORM_MISMATCH",
					"The sales channel must match the X-Client-Platform header.");
		}
		String renewal = body.renewalPreference() == null ? "MANUAL_RENEW" : body.renewalPreference();
		String fingerprint = BillingErrors.sha256Hex(String.join("|", body.productCode(), body.billingPeriod(),
				body.marketCountry(), body.salesChannel(), renewal, Objects.toString(body.promotionCode(), ""),
				Objects.toString(body.upgradeFromSubscriptionId(), "")));

		Optional<OrderRow> replay = findByKey(user.userId(), key);
		if (replay.isPresent()) {
			return replayed(replay.get(), fingerprint);
		}

		String market = routing.market(resolution.country(), resolution.confidence());
		if (!market.equals(body.marketCountry())) {
			throw BillingErrors.problem(ApiErrorCode.BILLING_POLICY_DENIED,
					"The market does not match the billing country resolved for this request; reload the billing options.");
		}
		if (body.upgradeFromSubscriptionId() != null) {
			throw BillingErrors.problem(ApiErrorCode.PRODUCT_NOT_AVAILABLE, "Upgrades are not available yet.");
		}
		if (body.promotionCode() != null && !body.promotionCode().isBlank()) {
			throw BillingErrors.problem(ApiErrorCode.PROMOTION_INVALID, "Promotion codes are not available yet.");
		}
		if ("AUTO_RENEW".equals(renewal)) {
			throw BillingErrors.problem(ApiErrorCode.MANDATE_NOT_SUPPORTED,
					"Automatic renewal is not available; choose a fixed term.");
		}

		Decision policyOnly = routing.decide(new ProviderRouting.Query(market, Confidence.HIGH, channel, null, null));
		boolean gatewayFlow = "LOCAL_GATEWAY_IN_APP".equals(policyOnly.purchaseFlow())
				|| "EXTERNAL_WEB_CHECKOUT".equals(policyOnly.purchaseFlow());
		if (!policyOnly.purchaseUiAllowed() || !gatewayFlow) {
			log.info("billing order refused market={} channel={} reason={}", market, channel.salesChannel(),
					policyOnly.denial() != null ? policyOnly.denial() : "FLOW_" + policyOnly.purchaseFlow());
			throw BillingErrors.problem(ApiErrorCode.BILLING_POLICY_DENIED,
					"Purchases are not available for this market and app build.");
		}

		PriceRow price = catalog.price(body.productCode(), market, channel.salesChannel(), body.billingPeriod(), renewal)
				.orElse(null);
		if (price == null) {
			boolean productListed = catalog.prices(market, channel.salesChannel()).stream()
					.anyMatch(p -> p.productCode().equals(body.productCode()));
			throw productListed
					? BillingErrors.problem(ApiErrorCode.PRICE_NOT_AVAILABLE,
							"No price for this billing period in this market.")
					: BillingErrors.problem(ApiErrorCode.PRODUCT_NOT_AVAILABLE, "This product is not available.");
		}
		if (!"SUBSCRIPTION".equals(price.productType()) || OrderPricing.term(price.billingPeriod()) == null) {
			throw BillingErrors.problem(ApiErrorCode.PRODUCT_NOT_AVAILABLE, "This product is not available yet.");
		}
		OrderPricing.Amounts amounts = OrderPricing.amounts(price.amountMinor(), 0, price.taxBehavior(),
				price.taxRateBasisPoints());
		Decision decision = routing.decide(new ProviderRouting.Query(market, Confidence.HIGH, channel,
				price.currency(), amounts.totalMinor()));
		if (decision.methods().stream().noneMatch(m -> m.method().accountId() != null)) {
			throw BillingErrors.problem(ApiErrorCode.PAYMENT_METHOD_UNAVAILABLE,
					"No payment method is available for this order right now.");
		}

		Instant now = clock.instant();
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("requestFingerprint", fingerprint);
		metadata.put("countryResolution", resolution.summary());
		metadata.put("clientPlatform", channel.platform());
		metadata.put("distributionChannel", channel.distribution());
		metadata.put("renewalModel", renewal);
		UUID id = UUID.randomUUID();
		String reference = reference(market, now);
		try {
			int inserted = transactions.execute(s -> jdbc.update("""
					INSERT INTO payment_orders (id, order_reference, user_id, billing_product_id, billing_price_id,
					       order_type, sales_channel, market_country_code, currency_code, list_amount_minor,
					       discount_minor, tax_minor, amount_minor, order_status, idempotency_key, expires_at, metadata,
					       created_at, updated_at)
					VALUES (?, ?, ?, ?, ?, 'SUBSCRIPTION_PURCHASE', ?, ?, ?, ?, ?, ?, ?, 'AWAITING_PAYMENT', ?, ?,
					        CAST(? AS JSONB), ?, ?)
					ON CONFLICT (user_id, idempotency_key) DO NOTHING""", id, reference, user.userId(),
					price.productId(), price.priceId(), channel.salesChannel(), market, price.currency(),
					amounts.listMinor(), amounts.discountMinor(), amounts.taxMinor(), amounts.totalMinor(), key,
					utc(now.plus(properties.orderTtl())), json.writeValueAsString(metadata), utc(now), utc(now)));
			if (inserted == 0) {
				return replayed(findByKey(user.userId(), key).orElseThrow(), fingerprint);
			}
		}
		catch (DuplicateKeyException e) {
			// order_reference collision (1 in 31^6 per month and market): the client retries with the same key
			throw BillingErrors.problem(ApiErrorCode.REQUEST_IN_PROGRESS, "Please retry.");
		}
		catch (DataAccessException e) {
			log.warn("billing order insert refused by a database guard market={} product={}", market,
					body.productCode());
			throw BillingErrors.problem(ApiErrorCode.PRODUCT_NOT_AVAILABLE, "This product is not available.");
		}
		log.info("billing order created orderId={} market={} channel={} countrySource={} confidence={} flags={}", id,
				market, channel.salesChannel(), resolution.source(), resolution.confidence(), resolution.flags());
		return dto(load(user.userId(), id).orElseThrow());
	}

	PaymentOrderDto get(UUID userId, UUID orderId) {
		return dto(load(userId, orderId).orElseThrow(BillingErrors::notFound));
	}

	PaymentOrderDto cancel(UUID userId, UUID orderId) {
		return transactions.execute(s -> {
			String status = jdbc.query("SELECT order_status FROM payment_orders WHERE id = ? AND user_id = ? FOR UPDATE",
					(rs, i) -> rs.getString(1), orderId, userId).stream().findFirst()
					.orElseThrow(BillingErrors::notFound);
			OrderStatus current = OrderStatus.valueOf(status);
			if (current == OrderStatus.CANCELLED) {
				return dto(load(userId, orderId).orElseThrow());
			}
			if (!current.isPayable()) {
				throw BillingErrors.problem(ApiErrorCode.ORDER_NOT_PAYABLE, "This order can no longer be cancelled.");
			}
			Instant now = clock.instant();
			jdbc.update("""
					UPDATE payment_orders SET order_status = 'CANCELLED', cancelled_at = ?, updated_at = ?
					WHERE id = ?""", utc(now), utc(now), orderId);
			// Open attempts stay open: a payment the provider still completes is verified and honoured (ADR 0045).
			return dto(load(userId, orderId).orElseThrow());
		});
	}

	Optional<OrderRow> load(UUID userId, UUID orderId) {
		return jdbc.query(SELECT_ORDER + " WHERE o.id = ? AND o.user_id = ?", OrderService::row, orderId, userId)
				.stream().findFirst();
	}

	private Optional<OrderRow> findByKey(UUID userId, String key) {
		return jdbc.query(SELECT_ORDER + " WHERE o.user_id = ? AND o.idempotency_key = ?", OrderService::row, userId,
				key).stream().findFirst();
	}

	private PaymentOrderDto replayed(OrderRow existing, String fingerprint) {
		if (!fingerprint.equals(existing.fingerprint())) {
			throw BillingErrors.problem(ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
					"This Idempotency-Key was used for a different order.");
		}
		return dto(existing);
	}

	PaymentOrderDto dto(OrderRow o) {
		Instant now = clock.instant();
		List<PaymentMethodOptionDto> methods = List.of();
		if (o.status().isPayable() && !o.expiredAt(now)) {
			Decision d = routing.decide(new ProviderRouting.Query(o.marketCountry(), Confidence.HIGH, o.channel(),
					o.currency(), o.amountMinor()));
			methods = catalog.methodDtos(d.methods().stream().filter(m -> m.method().accountId() != null).toList(),
					o.exponent());
		}
		String status = o.status().isPayable() && o.expiredAt(now) ? OrderStatus.EXPIRED.name() : o.status().name();
		FulfilmentDto fulfilment = o.subscriptionId() == null && o.receiptId() == null ? null
				: new FulfilmentDto(o.subscriptionId(), null, o.receiptId());
		return new PaymentOrderDto(o.id(), o.orderReference(), o.orderType(), status, o.productCode(),
				o.billingPeriod(), o.renewalModel(), o.marketCountry(), o.salesChannel(), o.taxBehavior(),
				new OrderAmountsDto(money(o.listMinor(), o), money(o.discountMinor(), o), money(o.taxMinor(), o),
						money(o.amountMinor(), o)),
				null, o.expiresAt(), o.paidAt(), methods, fulfilment, o.createdAt());
	}

	private static MoneyDto money(long minor, OrderRow o) {
		return MoneyDto.of(minor, o.currency(), o.exponent());
	}

	/** {@code QAL-ET-2611-7Q4M2X}: market, year and month, six unambiguous random characters. */
	private static String reference(String market, Instant now) {
		StringBuilder sb = new StringBuilder("QAL-").append(market).append('-')
				.append(YYMM.format(now.atOffset(ZoneOffset.UTC))).append('-');
		for (int i = 0; i < 6; i++) {
			sb.append(REF_ALPHABET[RANDOM.nextInt(REF_ALPHABET.length)]);
		}
		return sb.toString();
	}

	static OffsetDateTime utc(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static final String SELECT_ORDER = """
			SELECT o.id, o.user_id, o.order_reference, o.order_type, o.order_status, bp.product_code, bp.display_name,
			       pr.billing_period, pr.renewal_model, o.market_country_code, o.sales_channel, pr.tax_behavior,
			       o.list_amount_minor, o.discount_minor, o.tax_minor, o.amount_minor, o.currency_code,
			       c.minor_unit_exponent, o.expires_at, o.paid_at, o.created_at, o.metadata->>'requestFingerprint',
			       (SELECT a.subscription_id FROM subscription_payment_allocations a WHERE a.payment_order_id = o.id),
			       (SELECT r.id FROM payment_receipts r WHERE r.payment_order_id = o.id)
			FROM payment_orders o
			JOIN billing_products bp ON bp.id = o.billing_product_id
			JOIN billing_prices pr ON pr.id = o.billing_price_id
			JOIN billing_currencies c ON c.currency_code = o.currency_code""";

	private static OrderRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new OrderRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
				OrderStatus.valueOf(rs.getString(5)), rs.getString(6), rs.getString(7), rs.getString(8),
				rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getLong(13), rs.getLong(14),
				rs.getLong(15), rs.getLong(16), rs.getString(17), rs.getInt(18), JdbcRoutingData.instant(rs, 19),
				JdbcRoutingData.instant(rs, 20), JdbcRoutingData.instant(rs, 21), rs.getString(22),
				rs.getObject(23, UUID.class), rs.getObject(24, UUID.class));
	}
}
