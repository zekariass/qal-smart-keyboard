package com.qalsmartkeyboard.billing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.administration.AdminAccess;
import com.qalsmartkeyboard.administration.AuditTrail;
import com.qalsmartkeyboard.billing.BillingDtos.AdminPriceDto;
import com.qalsmartkeyboard.billing.BillingDtos.AdminPriceUpdate;
import com.qalsmartkeyboard.billing.BillingDtos.AdminProductDto;
import com.qalsmartkeyboard.billing.BillingDtos.AdminProductListDto;
import com.qalsmartkeyboard.billing.BillingDtos.AdminProductUpdate;
import com.qalsmartkeyboard.billing.BillingDtos.MoneyDto;
import com.qalsmartkeyboard.identity.AuthenticatedUser;

/**
 * Admin view and editing of the sellable catalogue (T8.10: "amounts configurable per country without code"):
 * {@code billing_products} (name, description, status; credit units and validity of credit packs) and
 * {@code billing_prices} per (product, market, sales channel, currency, period, renewal model).
 *
 * <p>Permissions (V1 FINANCE role): {@code billing:read} to list, {@code prices:edit} to change, plus
 * {@code prices:approve} to change a product's status (activation is what makes it sellable). Every change is one
 * transaction and audited ({@code BILLING_PRODUCT_UPDATE}, {@code BILLING_PRICE_SET} with old and new values).
 *
 * <p><b>Price history is never rewritten under an order.</b> {@code payment_orders} binds the exact price row and amount
 * (V1 {@code fk_payment_order_price_amount_scope}). So a scope's current row is changed in place only while no order
 * references it; otherwise it is closed ({@code valid_until}) and a new {@code price_version} starts at the same
 * instant (the V1 exclusion constraint keeps active rows of a scope from overlapping). The instant is the earlier of
 * the database and application clocks, so both the order trigger and the catalogue see the new row as valid at once.
 */
final class CatalogAdmin {

	static final String PERMISSION_READ = "billing:read";
	static final String PERMISSION_EDIT = "prices:edit";
	static final String PERMISSION_APPROVE = "prices:approve";
	static final String ACTION_PRODUCT = "BILLING_PRODUCT_UPDATE";
	static final String ACTION_PRICE = "BILLING_PRICE_SET";

	private static final Set<String> TERM_PERIODS = Set.of("WEEKLY", "MONTHLY", "QUARTERLY", "ANNUAL");

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final AdminAccess admin;
	private final AuditTrail audit;
	private final Clock clock;

	CatalogAdmin(JdbcTemplate jdbc, TransactionTemplate transactions, AdminAccess admin, AuditTrail audit, Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.admin = admin;
		this.audit = audit;
		this.clock = clock;
	}

	private record Product(UUID id, String code, String type, String displayName, String description, String status,
			String planCode, BigDecimal creditUnits, Integer creditValidityDays) {
	}

	private record PriceRow(UUID id, long amountMinor, String taxBehavior, Integer taxRate, int version,
			Instant validFrom, boolean referenced) {
	}

	AdminProductListDto list(AuthenticatedUser user, String marketCountry) {
		if (marketCountry != null && !marketCountry.matches("[A-Z]{2}")) {
			throw BillingErrors.invalid("marketCountry", "INVALID", "ISO 3166-1 alpha-2, or ZZ.");
		}
		admin.require(user, PERMISSION_READ, "BILLING_PRODUCTS_READ", "billing_products", null);
		List<AdminProductDto> items = new ArrayList<>();
		for (Product p : allProducts()) {
			items.add(dto(p, marketCountry));
		}
		return new AdminProductListDto(items);
	}

	AdminProductDto update(AuthenticatedUser user, String productCode, AdminProductUpdate body) {
		code(productCode);
		if (body.displayName() == null && body.description() == null && body.productStatus() == null
				&& body.creditUnits() == null && body.creditValidityDays() == null) {
			throw BillingErrors.invalid("", "EMPTY", "Send at least one field to change.");
		}
		if (body.displayName() != null && body.displayName().isBlank()) {
			throw BillingErrors.invalid("displayName", "BLANK", "The display name cannot be blank.");
		}
		AdminAccess.Actor actor = admin.require(user, PERMISSION_EDIT, ACTION_PRODUCT, "billing_products", productCode);
		if (body.productStatus() != null) {
			actor = admin.require(user, PERMISSION_APPROVE, ACTION_PRODUCT, "billing_products", productCode);
		}
		UUID actorId = actor.userId();
		return transactions.execute(s -> {
			Product p = product(productCode, true).orElseThrow(BillingErrors::notFound);
			boolean creditFields = body.creditUnits() != null || body.creditValidityDays() != null;
			if (creditFields && !"AI_CREDIT_PACK".equals(p.type())) {
				throw BillingErrors.invalid(body.creditUnits() != null ? "creditUnits" : "creditValidityDays",
						"NOT_APPLICABLE", "Credit units and validity apply to credit packs only.");
			}
			Map<String, Object> before = new LinkedHashMap<>();
			Map<String, Object> after = new LinkedHashMap<>();
			track(before, after, "displayName", p.displayName(), body.displayName());
			track(before, after, "description", p.description(), body.description());
			track(before, after, "productStatus", p.status(), body.productStatus());
			track(before, after, "creditUnits", p.creditUnits() == null ? null : p.creditUnits().longValue(),
					body.creditUnits());
			track(before, after, "creditValidityDays", p.creditValidityDays(), body.creditValidityDays());
			if (!after.isEmpty()) {
				jdbc.update("""
						UPDATE billing_products
						   SET display_name = COALESCE(?, display_name), description = COALESCE(?, description),
						       product_status = COALESCE(?, product_status), credit_units = COALESCE(?, credit_units),
						       credit_validity_days = COALESCE(?, credit_validity_days), updated_at = ?
						 WHERE id = ?""", body.displayName(), body.description(), body.productStatus(),
						body.creditUnits() == null ? null : BigDecimal.valueOf(body.creditUnits()),
						body.creditValidityDays(), OrderService.utc(clock.instant()), p.id());
				Map<String, Object> meta = new LinkedHashMap<>();
				meta.put("before", before);
				meta.put("after", after);
				audit.record(new AuditTrail.Entry(actorId, null, ACTION_PRODUCT, "billing_products", productCode,
						AuditTrail.Outcome.SUCCESS, meta));
			}
			return dto(product(productCode, false).orElseThrow(), null);
		});
	}

	AdminProductDto setPrice(AuthenticatedUser user, String productCode, String market, String salesChannel,
			AdminPriceUpdate body) {
		code(productCode);
		if (market == null || !market.matches("[A-Z]{2}")) {
			throw BillingErrors.invalid("marketCountry", "INVALID", "ISO 3166-1 alpha-2, or ZZ.");
		}
		if (Channel.ofSalesChannel(salesChannel).isEmpty()) {
			throw BillingErrors.invalid("salesChannel", "INVALID", "Unknown sales channel.");
		}
		String tax = body.taxBehavior() == null ? "INCLUSIVE" : body.taxBehavior();
		if ("EXCLUSIVE".equals(tax) && body.taxRateBasisPoints() == null) {
			throw BillingErrors.invalid("taxRateBasisPoints", "REQUIRED", "An EXCLUSIVE price needs a tax rate.");
		}
		Integer rate = "EXCLUSIVE".equals(tax) ? body.taxRateBasisPoints() : null;
		boolean active = body.active() == null || body.active();
		String scopeKey = productCode + "/" + market + "/" + salesChannel + "/" + body.currency() + "/"
				+ body.billingPeriod() + "/" + body.renewalModel();
		String auditEntity = scopeKey.length() > 100 ? scopeKey.substring(0, 100) : scopeKey;
		AdminAccess.Actor actor = admin.require(user, PERMISSION_EDIT, ACTION_PRICE, "billing_prices", auditEntity);
		return transactions.execute(s -> {
			Product p = product(productCode, true).orElseThrow(BillingErrors::notFound);
			checkShape(p, body);
			Integer currency = jdbc.query("SELECT 1 FROM billing_currencies WHERE currency_code = ?",
					(rs, i) -> 1, body.currency()).stream().findFirst().orElse(null);
			if (currency == null) {
				throw BillingErrors.invalid("currency", "UNKNOWN_CURRENCY", "This currency is not configured.");
			}
			jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", (rs, i) -> null, "billing_prices:" + scopeKey);
			Instant at = jdbc.queryForObject("SELECT LEAST(NOW(), CAST(? AS TIMESTAMPTZ))",
					(rs, i) -> JdbcRoutingData.instant(rs, 1),
					OrderService.utc(clock.instant()));
			List<PriceRow> current = jdbc.query("""
					SELECT pr.id, pr.amount_minor, pr.tax_behavior, pr.tax_rate_basis_points, pr.price_version,
					       pr.valid_from, EXISTS (SELECT 1 FROM payment_orders o WHERE o.billing_price_id = pr.id)
					FROM billing_prices pr
					WHERE pr.billing_product_id = ? AND pr.market_country_code = ? AND pr.sales_channel = ?
					  AND pr.currency_code = ? AND pr.billing_period = ? AND pr.renewal_model = ? AND pr.is_active
					  AND (pr.valid_until IS NULL OR pr.valid_until > ?)
					ORDER BY pr.valid_from DESC
					FOR UPDATE OF pr""",
					(rs, i) -> new PriceRow(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3),
							(Integer) rs.getObject(4), rs.getInt(5), JdbcRoutingData.instant(rs, 6), rs.getBoolean(7)),
					p.id(), market, salesChannel, body.currency(), body.billingPeriod(), body.renewalModel(),
					OrderService.utc(at));
			PriceRow live = current.isEmpty() ? null : current.getFirst();
			String outcome;
			if (!active) {
				if (live == null) {
					outcome = "UNCHANGED";
				}
				else if (live.referenced()) {
					close(live, at);
					outcome = "ENDED";
				}
				else {
					jdbc.update("UPDATE billing_prices SET is_active = FALSE, updated_at = ? WHERE id = ?",
							OrderService.utc(at), live.id());
					outcome = "ENDED";
				}
			}
			else if (live != null && live.amountMinor() == body.amountMinor() && live.taxBehavior().equals(tax)
					&& Objects.equals(live.taxRate(), rate)) {
				outcome = "UNCHANGED";
			}
			else if (live != null && !live.referenced()) {
				jdbc.update("""
						UPDATE billing_prices
						   SET amount_minor = ?, tax_behavior = ?, tax_rate_basis_points = ?,
						       metadata = metadata || '{"setBy": "admin"}'::JSONB, updated_at = ?
						 WHERE id = ?""", body.amountMinor(), tax, rate, OrderService.utc(at), live.id());
				outcome = "UPDATED_IN_PLACE";
			}
			else {
				Instant from = at;
				if (live != null) {
					if (!from.isAfter(live.validFrom())) {
						from = live.validFrom().plusMillis(1);
					}
					close(live, from);
				}
				Integer version = jdbc.queryForObject("""
						SELECT COALESCE(MAX(price_version), 0) + 1 FROM billing_prices
						WHERE billing_product_id = ? AND market_country_code = ? AND sales_channel = ?
						  AND currency_code = ? AND billing_period = ? AND renewal_model = ?""", Integer.class,
						p.id(), market, salesChannel, body.currency(), body.billingPeriod(), body.renewalModel());
				jdbc.update("""
						INSERT INTO billing_prices (billing_product_id, market_country_code, sales_channel, currency_code,
						       amount_minor, tax_behavior, tax_rate_basis_points, billing_period, renewal_model,
						       price_version, is_active, valid_from, metadata, created_at, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, '{"setBy": "admin"}'::JSONB, ?, ?)""", p.id(), market,
						salesChannel, body.currency(), body.amountMinor(), tax, rate, body.billingPeriod(),
						body.renewalModel(), version, OrderService.utc(from), OrderService.utc(at),
						OrderService.utc(at));
				outcome = "NEW_VERSION";
			}
			if (!"UNCHANGED".equals(outcome)) {
				Map<String, Object> meta = new LinkedHashMap<>();
				meta.put("outcome", outcome);
				meta.put("currency", body.currency());
				if (live != null) {
					meta.put("previousAmountMinor", live.amountMinor());
				}
				if (active) {
					meta.put("amountMinor", body.amountMinor());
				}
				meta.put("taxBehavior", tax);
				audit.record(new AuditTrail.Entry(actor.userId(), null, ACTION_PRICE, "billing_prices", auditEntity,
						AuditTrail.Outcome.SUCCESS, meta));
			}
			return dto(product(productCode, false).orElseThrow(), market);
		});
	}

	/** The period and renewal model a product type can be sold with. */
	private static void checkShape(Product p, AdminPriceUpdate body) {
		boolean ok = switch (p.type()) {
			case "AI_CREDIT_PACK" -> "ONE_TIME".equals(body.billingPeriod()) && "NONE".equals(body.renewalModel());
			case "SUBSCRIPTION" -> TERM_PERIODS.contains(body.billingPeriod())
					&& ("MANUAL_RENEW".equals(body.renewalModel()) || "STORE_MANAGED".equals(body.renewalModel()));
			default -> false;
		};
		if (!ok) {
			throw BillingErrors.invalid("billingPeriod", "NOT_FOR_PRODUCT", switch (p.type()) {
				case "AI_CREDIT_PACK" -> "Credit packs are priced ONE_TIME with renewal model NONE.";
				case "SUBSCRIPTION" -> "Plans are priced per term (WEEKLY, MONTHLY, QUARTERLY, ANNUAL) with MANUAL_RENEW "
						+ "or STORE_MANAGED.";
				default -> "This product type cannot be priced yet.";
			});
		}
	}

	private void close(PriceRow row, Instant at) {
		jdbc.update("UPDATE billing_prices SET valid_until = ?, updated_at = ? WHERE id = ?", OrderService.utc(at),
				OrderService.utc(at), row.id());
	}

	private static void code(String productCode) {
		if (productCode == null || !productCode.matches("[A-Z][A-Z0-9_]{0,59}")) {
			throw BillingErrors.notFound();
		}
	}

	private static void track(Map<String, Object> before, Map<String, Object> after, String field, Object old,
			Object requested) {
		if (requested != null && !Objects.equals(old, requested)) {
			before.put(field, old);
			after.put(field, requested);
		}
	}

	private static final String SELECT_PRODUCT = """
			SELECT bp.id, bp.product_code, bp.product_type, bp.display_name, bp.description, bp.product_status,
			       sp.plan_code, bp.credit_units, bp.credit_validity_days
			FROM billing_products bp LEFT JOIN subscription_plans sp ON sp.id = bp.plan_id""";

	private List<Product> allProducts() {
		return jdbc.query(SELECT_PRODUCT + " ORDER BY bp.product_type, bp.product_code", CatalogAdmin::productRow);
	}

	private Optional<Product> product(String code, boolean lock) {
		return jdbc.query(SELECT_PRODUCT + " WHERE bp.product_code = ?" + (lock ? " FOR UPDATE OF bp" : ""),
				CatalogAdmin::productRow, code).stream().findFirst();
	}

	private static Product productRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new Product(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
				rs.getString(5), rs.getString(6), rs.getString(7), rs.getBigDecimal(8), (Integer) rs.getObject(9));
	}

	private AdminProductDto dto(Product p, String market) {
		Instant now = clock.instant();
		List<Object> args = new ArrayList<>(List.of(p.id()));
		String filter = "";
		if (market != null) {
			filter = " AND pr.market_country_code = ?";
			args.add(market);
		}
		List<AdminPriceDto> prices = jdbc.query("""
				SELECT pr.id, pr.market_country_code, pr.sales_channel, pr.amount_minor, pr.currency_code,
				       c.minor_unit_exponent, pr.tax_behavior, pr.tax_rate_basis_points, pr.billing_period,
				       pr.renewal_model, pr.price_version, pr.is_active, pr.valid_from, pr.valid_until
				FROM billing_prices pr JOIN billing_currencies c ON c.currency_code = pr.currency_code
				WHERE pr.billing_product_id = ?""" + filter + """

				ORDER BY pr.market_country_code, pr.sales_channel, pr.currency_code, pr.billing_period,
				         pr.renewal_model, pr.price_version DESC""", (rs, i) -> {
			Instant from = JdbcRoutingData.instant(rs, 13);
			Instant until = JdbcRoutingData.instant(rs, 14);
			boolean live = rs.getBoolean(12) && !from.isAfter(now) && (until == null || until.isAfter(now));
			return new AdminPriceDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
					MoneyDto.of(rs.getLong(4), rs.getString(5), rs.getInt(6)), rs.getString(7),
					(Integer) rs.getObject(8), rs.getString(9), rs.getString(10), rs.getInt(11), live, from, until);
		}, args.toArray());
		return new AdminProductDto(p.code(), p.type(), p.displayName(), p.description(), p.status(), p.planCode(),
				p.creditUnits() == null ? null : p.creditUnits().longValue(), p.creditValidityDays(), prices);
	}
}
