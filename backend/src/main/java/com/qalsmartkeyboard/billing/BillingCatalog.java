package com.qalsmartkeyboard.billing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.qalsmartkeyboard.platform.geo.CountryResolution;

/**
 * Sellable products with their active prices for a market and sales channel, and the {@code BillingOptions} document
 * (products plus the routed payment methods). Only ACTIVE products of sellable ACTIVE plans with an active price valid
 * now are listed.
 */
final class BillingCatalog {

	/** One active price of a sellable product. */
	record PriceRow(UUID productId, String productCode, String productType, String productName, String description,
			String planCode, BigDecimal creditUnits, Integer creditValidityDays, UUID priceId, long amountMinor,
			String currency, int exponent, String taxBehavior, Integer taxRateBasisPoints, String billingPeriod,
			String renewalModel) {
	}

	private final JdbcTemplate jdbc;
	private final ProviderRouting routing;
	private final Clock clock;

	BillingCatalog(JdbcTemplate jdbc, ProviderRouting routing, Clock clock) {
		this.jdbc = jdbc;
		this.routing = routing;
		this.clock = clock;
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
				ORDER BY COALESCE(sp.display_order, 1000), bp.product_code, pr.billing_period""",
				(rs, i) -> new PriceRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getString(5), rs.getString(6), rs.getBigDecimal(7), (Integer) rs.getObject(8),
						rs.getObject(9, UUID.class), rs.getLong(10), rs.getString(11), rs.getInt(12), rs.getString(13),
						(Integer) rs.getObject(14), rs.getString(15), rs.getString(16)),
				marketCountry, salesChannel, now, now);
	}

	/** The active price of one product, period and renewal model in a market and channel. */
	Optional<PriceRow> price(String productCode, String marketCountry, String salesChannel, String billingPeriod,
			String renewalModel) {
		return prices(marketCountry, salesChannel).stream()
				.filter(p -> p.productCode().equals(productCode) && p.billingPeriod().equals(billingPeriod)
						&& p.renewalModel().equals(renewalModel))
				.findFirst();
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
		List<PaymentMethodOptionDto> methods = d.purchaseUiAllowed() ? methodDtos(d.methods(), null) : List.of();
		List<ProductDto> products = d.purchaseUiAllowed() && gatewayFlow && !methods.isEmpty()
				? products(d.marketCountry(), channel.salesChannel())
				: List.of();
		boolean allowed = d.purchaseUiAllowed() && !methods.isEmpty();
		return new BillingOptionsDto(d.marketCountry(), market, channel.platform(), channel.distribution(),
				allowed ? d.purchaseFlow() : "NONE", allowed, allowed ? products : List.of(),
				allowed ? methods : List.of(), null, now);
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
