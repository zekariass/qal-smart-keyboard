package com.qalsmartkeyboard.billing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.qalsmartkeyboard.billing.BillingDtos.BillingOptionsDto;
import com.qalsmartkeyboard.billing.BillingDtos.MarketResolutionDto;
import com.qalsmartkeyboard.billing.BillingDtos.MoneyDto;
import com.qalsmartkeyboard.billing.BillingDtos.PaymentMethodOptionDto;
import com.qalsmartkeyboard.billing.BillingDtos.PriceDto;
import com.qalsmartkeyboard.billing.BillingDtos.ProductDto;
import com.qalsmartkeyboard.billing.ProviderRouting.Decision;
import com.qalsmartkeyboard.billing.ProviderRouting.MethodOption;
import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;
import com.qalsmartkeyboard.platform.geo.CountryResolution;
import com.qalsmartkeyboard.platform.geo.CountryResolution.Confidence;

/**
 * Sellable products with their active prices for a market and sales channel, and the {@code BillingOptions} document
 * (products plus the routed payment methods). Only ACTIVE products of sellable ACTIVE plans with an active price valid
 * now are listed, and only of the product families the market offers ({@code billing_market_offerings}, T8.10: the
 * market's own row, else the {@code ZZ} default, else off). Order pricing reads the same list, so a family a market
 * does not offer can neither be shown nor ordered.
 *
 * <p><b>Store flows</b> (T8.10 store packs): where the market/channel policy is {@code STORE_BILLING} the catalogue
 * lists the credit packs sellable through the store ({@code fn_billing_store_pack_sellable}: ACTIVE pack, the market
 * offers AI credits, an ACTIVE store mapping of this server's environment on a STORE_MANAGED price row of the market or
 * of {@code ZZ}); each price carries {@code storeProductId} (the Play / RevenueCat product identifier) and the suggested
 * RevenueCat offering and package, and its {@code listPrice} is a placeholder (renewal {@code STORE_MANAGED}): the store
 * sets and charges the price, the app shows the store's formatted price. Store-billed plans keep coming from the
 * RevenueCat offering in the app (unchanged). Such packs are never ordered: store purchases are reported to
 * {@code POST /billing/store-purchases}. A build without store billing ({@code DIRECT_DOWNLOAD}, {@code WEB}) in a
 * store-billing market gets {@code purchaseUiAllowed = false} and the {@link #NO_STORE_IN_BUILD} notice.
 */
final class BillingCatalog {

	/** One active price of a sellable product. */
	record PriceRow(UUID productId, String productCode, String productType, String productName, String description,
			String planCode, BigDecimal creditUnits, Integer creditValidityDays, UUID priceId, long amountMinor,
			String currency, int exponent, String taxBehavior, Integer taxRateBasisPoints, String billingPeriod,
			String renewalModel) {
	}

	/** {@code BillingOptions.notice} of a build without store billing in a store-billing market. */
	static final String NO_STORE_IN_BUILD = "Purchases are not available in this version of the app. Install the app "
			+ "from Google Play to buy plans or AI credits.";

	private final JdbcTemplate jdbc;
	private final ProviderRouting routing;
	private final PaymentEnvironment environment;
	private final Clock clock;

	BillingCatalog(JdbcTemplate jdbc, ProviderRouting routing, PaymentEnvironment environment, Clock clock) {
		this.jdbc = jdbc;
		this.routing = routing;
		this.environment = environment;
		this.clock = clock;
	}

	/**
	 * {@code GET /billing/products}: the gateway price book of the market and channel, or, where the scope's policy is
	 * meant for store billing, the store-sellable credit packs (whatever the policy's approval, as for gateway prices).
	 */
	List<ProductDto> listed(String marketCountry, String salesChannel) {
		Channel channel = Channel.ofSalesChannel(salesChannel).orElseThrow();
		Decision d = routing.decide(new ProviderRouting.Query(marketCountry, Confidence.HIGH, channel, null, null));
		if ("STORE_BILLING".equals(d.policyFlow())) {
			return channel.storeDistribution() ? storePacks(marketCountry, salesChannel, null) : List.of();
		}
		return products(marketCountry, salesChannel);
	}

	/**
	 * Credit packs sellable through the store in {@code market} for {@code salesChannel}, one price each (the market's
	 * own store row, else the {@code ZZ} one), with the store identifiers of the mapping.
	 *
	 * @param providerCode only mappings of this store provider, or null for any store provider
	 */
	List<ProductDto> storePacks(String market, String salesChannel, String providerCode) {
		List<ProductDto> out = new ArrayList<>(jdbc.query("""
				SELECT DISTINCT ON (bp.id) bp.product_code, bp.display_name, bp.description, bp.credit_units,
				       bp.credit_validity_days, pr.amount_minor, pr.currency_code, c.minor_unit_exponent, pr.tax_behavior,
				       pr.billing_period, pr.renewal_model, m.external_product_id, m.external_offer_id,
				       m.metadata->>'revenueCatOffering', m.metadata->>'revenueCatPackage'
				FROM billing_products bp
				JOIN billing_prices pr ON pr.billing_product_id = bp.id
				JOIN billing_currencies c ON c.currency_code = pr.currency_code
				JOIN external_product_mappings m ON m.billing_price_id = pr.id
				JOIN payment_providers p ON p.id = m.provider_id AND p.provider_type = 'APP_STORE'
				WHERE bp.product_type = 'AI_CREDIT_PACK' AND pr.renewal_model = 'STORE_MANAGED'
				  AND pr.billing_period = 'ONE_TIME' AND pr.sales_channel = ? AND pr.market_country_code IN (?, 'ZZ')
				  AND m.is_active AND m.environment = ? AND (CAST(? AS TEXT) IS NULL OR p.provider_code = ?)
				  AND fn_billing_store_pack_sellable(bp.id, ?, ?, ?)
				ORDER BY bp.id, (pr.market_country_code = ?) DESC, m.created_at, m.id""",
				(rs, i) -> new ProductDto(rs.getString(1), "AI_CREDIT_PACK", rs.getString(2), rs.getString(3), null,
						rs.getBigDecimal(4).longValue(), (Integer) rs.getObject(5),
						List.of(new PriceDto(rs.getString(10), rs.getString(11),
								MoneyDto.of(rs.getLong(6), rs.getString(7), rs.getInt(8)), rs.getString(9), null,
								rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15)))),
				salesChannel, market, environment.name(), providerCode, providerCode, market, salesChannel,
				environment.name(), market));
		out.sort(Comparator.comparing(ProductDto::creditUnits).thenComparing(ProductDto::productCode));
		return out;
	}

	List<PriceRow> prices(String marketCountry, String salesChannel) {
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		return jdbc.query("""
				SELECT bp.id, bp.product_code, bp.product_type, bp.display_name, bp.description, sp.plan_code,
				       bp.credit_units, bp.credit_validity_days, pr.id, pr.amount_minor, pr.currency_code,
				       c.minor_unit_exponent, pr.tax_behavior, pr.tax_rate_basis_points, pr.billing_period,
				       pr.renewal_model
				FROM billing_prices pr
				JOIN billing_products bp ON bp.id = pr.billing_product_id
				JOIN billing_currencies c ON c.currency_code = pr.currency_code
				LEFT JOIN subscription_plans sp ON sp.id = bp.plan_id
				WHERE pr.market_country_code = ? AND pr.sales_channel = ? AND pr.is_active
				  AND pr.valid_from <= ? AND (pr.valid_until IS NULL OR pr.valid_until > ?)
				  AND bp.product_status = 'ACTIVE'
				  AND (bp.product_type <> 'SUBSCRIPTION' OR (sp.is_sellable AND sp.plan_status = 'ACTIVE'))
				  AND fn_billing_offering_enabled(pr.market_country_code, bp.product_type)
				ORDER BY COALESCE(sp.display_order, 1000), bp.product_code, pr.billing_period""",
				(rs, i) -> new PriceRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getString(5), rs.getString(6), rs.getBigDecimal(7), (Integer) rs.getObject(8),
						rs.getObject(9, UUID.class), rs.getLong(10), rs.getString(11), rs.getInt(12), rs.getString(13),
						(Integer) rs.getObject(14), rs.getString(15), rs.getString(16)),
				marketCountry, salesChannel, now, now);
	}


	List<ProductDto> products(String marketCountry, String salesChannel) {
		Map<String, List<PriceRow>> byProduct = new LinkedHashMap<>();
		for (PriceRow p : prices(marketCountry, salesChannel)) {
			byProduct.computeIfAbsent(p.productCode(), k -> new ArrayList<>()).add(p);
		}
		List<ProductDto> out = new ArrayList<>();
		byProduct.forEach((code, rows) -> {
			PriceRow first = rows.get(0);
			List<PriceDto> prices = rows.stream()
					.map(p -> new PriceDto(p.billingPeriod(), p.renewalModel(),
							MoneyDto.of(p.amountMinor(), p.currency(), p.exponent()), p.taxBehavior(), null))
					.toList();
			out.add(new ProductDto(code, first.productType(), first.productName(), first.description(),
					first.planCode(), first.creditUnits() == null ? null : first.creditUnits().longValue(),
					first.creditValidityDays(), prices));
		});
		return out;
	}

	/** {@code BillingOptions} for a resolved country and a client channel. */
	BillingOptionsDto options(CountryResolution resolution, Channel channel) {
		Decision d = routing.decide(new ProviderRouting.Query(resolution.country(), resolution.confidence(), channel,
				null, null));
		Instant now = clock.instant();
		MarketResolutionDto market = new MarketResolutionDto(resolution.source().name(),
				resolution.confidence().name(), d.globalDefault());
		boolean gatewayFlow = "LOCAL_GATEWAY_IN_APP".equals(d.purchaseFlow())
				|| "EXTERNAL_WEB_CHECKOUT".equals(d.purchaseFlow());
		boolean storeFlow = "STORE_BILLING".equals(d.purchaseFlow());
		List<PaymentMethodOptionDto> methods = d.purchaseUiAllowed() ? methodDtos(d.methods(), null) : List.of();
		List<ProductDto> products = List.of();
		if (d.purchaseUiAllowed() && gatewayFlow && !methods.isEmpty()) {
			products = products(d.marketCountry(), channel.salesChannel());
		}
		else if (d.purchaseUiAllowed() && storeFlow && !methods.isEmpty()) {
			// store-billed credit packs of the routed store provider (plans come from the store offering in the app)
			products = storePacks(d.marketCountry(), channel.salesChannel(), methods.get(0).providerCode());
		}
		boolean allowed = d.purchaseUiAllowed() && !methods.isEmpty();
		String notice = !allowed && !channel.storeDistribution() && "STORE_BILLING".equals(d.policyFlow())
				? NO_STORE_IN_BUILD
				: null;
		return new BillingOptionsDto(d.marketCountry(), market, channel.platform(), channel.distribution(),
				allowed ? d.purchaseFlow() : "NONE", allowed, allowed ? products : List.of(),
				allowed ? methods : List.of(), notice, now);
	}

	/** Public method options in priority order; the first is the default. Exponent from the method's currency. */
	List<PaymentMethodOptionDto> methodDtos(List<MethodOption> methods, Integer exponent) {
		List<PaymentMethodOptionDto> out = new ArrayList<>();
		for (MethodOption o : methods) {
			RoutingData.Method m = o.method();
			int exp = exponent != null ? exponent : exponent(m.currencyCode());
			MoneyDto min = m.minAmountMinor() == null || m.currencyCode() == null ? null
					: MoneyDto.of(m.minAmountMinor(), m.currencyCode(), exp);
			MoneyDto max = m.maxAmountMinor() == null || m.currencyCode() == null ? null
					: MoneyDto.of(m.maxAmountMinor(), m.currencyCode(), exp);
			out.add(new PaymentMethodOptionDto(m.methodCode(), o.providerCode(), m.displayName(), m.methodType(),
					m.supportsAutoRenew(), m.requiresRedirect(), min, max, o.priority(), out.isEmpty()));
		}
		return out;
	}

	private int exponent(String currency) {
		if (currency == null) {
			return 2;
		}
		Integer e = jdbc.query("SELECT minor_unit_exponent FROM billing_currencies WHERE currency_code = ?",
				(rs, i) -> rs.getInt(1), currency).stream().findFirst().orElse(2);
		return e;
	}
}
