package com.qalsmartkeyboard.billing;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.administration.AdminAccess;
import com.qalsmartkeyboard.administration.AuditTrail;
import com.qalsmartkeyboard.billing.BillingDtos.MarketOfferingDto;
import com.qalsmartkeyboard.billing.BillingDtos.MarketOfferingInput;
import com.qalsmartkeyboard.billing.BillingDtos.MarketOfferingSetDto;
import com.qalsmartkeyboard.billing.BillingDtos.MarketOfferingSetUpdate;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

/**
 * What a market sells (T8.10, owner decision 2026-11-24): {@code billing_market_offerings} (V31) per market and
 * product family ({@code SUBSCRIPTIONS}, {@code AI_CREDITS}); a market without its own row for a family inherits the
 * {@code ZZ} row, and without that the family is off (fail closed, {@code fn_billing_offering_enabled}). The catalogue
 * and therefore order creation enforce it; existing subscriptions, lots and entitlements never depend on it.
 *
 * <p>{@code billing:read} to read, {@code prices:edit} to replace (FINANCE). Replacing is one transaction with N12
 * optimistic concurrency on the market's revision and is audited ({@code BILLING_MARKET_OFFERINGS_REPLACE}).
 */
final class MarketOfferingAdmin {

	static final String PERMISSION_READ = "billing:read";
	static final String PERMISSION_EDIT = "prices:edit";
	static final String ACTION = "BILLING_MARKET_OFFERINGS_REPLACE";
	static final List<String> OFFERINGS = List.of("SUBSCRIPTIONS", "AI_CREDITS");

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final AdminAccess admin;
	private final AuditTrail audit;
	private final Clock clock;

	MarketOfferingAdmin(JdbcTemplate jdbc, TransactionTemplate transactions, AdminAccess admin, AuditTrail audit,
			Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.admin = admin;
		this.audit = audit;
		this.clock = clock;
	}

	private record Row(String market, String offering, boolean enabled, int revision, String notes) {
	}

	MarketOfferingSetDto get(AuthenticatedUser user, String country) {
		country(country);
		admin.require(user, PERMISSION_READ, "BILLING_MARKET_OFFERINGS_READ", "billing_market_offerings", country);
		return read(country);
	}

	MarketOfferingSetDto replace(AuthenticatedUser user, String country, MarketOfferingSetUpdate body) {
		country(country);
		AdminAccess.Actor actor = admin.require(user, PERMISSION_EDIT, ACTION, "billing_market_offerings", country);
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < body.offerings().size(); i++) {
			if (!seen.add(body.offerings().get(i).offering())) {
				throw BillingErrors.invalid("offerings/" + i + "/offering", "DUPLICATE", "Each offering once.");
			}
		}
		return transactions.execute(s -> {
			jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", (rs, i) -> null,
					"billing_market_offerings:" + country);
			List<Row> before = rows(country);
			int current = revision(before);
			if (current != body.expectedRevision()) {
				throw new ApiException(ApiErrorCode.REVISION_CONFLICT, HttpStatus.CONFLICT,
						"The offerings changed since they were read.", List.of(), Map.of("currentRevision", current));
			}
			jdbc.update("DELETE FROM billing_market_offerings WHERE market_country_code = ?", country);
			int next = current + 1;
			for (MarketOfferingInput o : body.offerings()) {
				jdbc.update("""
						INSERT INTO billing_market_offerings (market_country_code, offering, is_enabled, revision, notes,
						       updated_by_user_id, created_at, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", country, o.offering(), o.enabled(), next, o.notes(),
						actor.userId(), OrderService.utc(clock.instant()), OrderService.utc(clock.instant()));
			}
			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put("previousRevision", current);
			meta.put("before", settings(before));
			Map<String, Boolean> after = new LinkedHashMap<>();
			body.offerings().forEach(o -> after.put(o.offering(), o.enabled()));
			meta.put("after", after);
			audit.record(new AuditTrail.Entry(actor.userId(), null, ACTION, "billing_market_offerings", country,
					AuditTrail.Outcome.SUCCESS, meta));
			return read(country); // an emptied market reads as revision 0, like one never configured
		});
	}

	private MarketOfferingSetDto read(String country) {
		List<Row> own = rows(country);
		List<Row> defaults = ProviderRouting.GLOBAL.equals(country) ? List.of() : rows(ProviderRouting.GLOBAL);
		List<MarketOfferingDto> out = new ArrayList<>();
		for (String offering : OFFERINGS) {
			Row mine = find(own, offering);
			Row inherited = find(defaults, offering);
			if (mine != null) {
				out.add(new MarketOfferingDto(offering, mine.enabled(), "MARKET", mine.notes()));
			}
			else if (inherited != null) {
				out.add(new MarketOfferingDto(offering, inherited.enabled(), "DEFAULT", inherited.notes()));
			}
			else {
				out.add(new MarketOfferingDto(offering, false, "NONE", null));
			}
		}
		return new MarketOfferingSetDto(country, revision(own), out);
	}

	private List<Row> rows(String country) {
		return jdbc.query("""
				SELECT market_country_code, offering, is_enabled, revision, notes FROM billing_market_offerings
				WHERE market_country_code = ?""",
				(rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getInt(4), rs.getString(5)),
				country);
	}

	private static Row find(List<Row> rows, String offering) {
		return rows.stream().filter(r -> r.offering().equals(offering)).findFirst().orElse(null);
	}

	private static int revision(List<Row> rows) {
		return rows.stream().mapToInt(Row::revision).max().orElse(0);
	}

	private static Map<String, Boolean> settings(List<Row> rows) {
		Map<String, Boolean> m = new LinkedHashMap<>();
		rows.forEach(r -> m.put(r.offering(), r.enabled()));
		return m;
	}

	private static void country(String country) {
		if (country == null || !country.matches("[A-Z]{2}")) {
			throw BillingErrors.invalid("countryCode", "INVALID", "ISO 3166-1 alpha-2, or ZZ.");
		}
	}
}
