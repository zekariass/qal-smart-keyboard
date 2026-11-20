package com.qalsmartkeyboard.billing;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.administration.AdminAccess;
import com.qalsmartkeyboard.administration.AuditTrail;
import com.qalsmartkeyboard.billing.BillingDtos.ProviderRouteDto;
import com.qalsmartkeyboard.billing.BillingDtos.ProviderRouteSetDto;
import com.qalsmartkeyboard.billing.BillingDtos.ProviderRouteSetUpdate;
import com.qalsmartkeyboard.billing.BillingDtos.RouteInput;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

/**
 * Admin view and replacement of {@code billing_provider_routes} for one scope (ADR 0045 "Switching providers"):
 * {@code billing:read} to read, {@code providers:configure} to replace (FINANCE, SUPER_ADMIN). Replacing is one
 * transaction with N12 optimistic concurrency on the scope's revision; the change is audited
 * ({@code BILLING_PROVIDER_ROUTES_REPLACE}, provider codes and revisions only). It never activates a provider, account or
 * policy: those gates keep applying.
 */
final class ProviderRouteAdmin {

	static final String PERMISSION_READ = "billing:read";
	static final String PERMISSION_CONFIGURE = "providers:configure";
	static final String ACTION = "BILLING_PROVIDER_ROUTES_REPLACE";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final AdminAccess admin;
	private final AuditTrail audit;
	private final ProviderRegistry registry;
	private final BillingProperties properties;
	private final Clock clock;

	ProviderRouteAdmin(JdbcTemplate jdbc, TransactionTemplate transactions, AdminAccess admin, AuditTrail audit,
			ProviderRegistry registry, BillingProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.admin = admin;
		this.audit = audit;
		this.registry = registry;
		this.properties = properties;
		this.clock = clock;
	}

	ProviderRouteSetDto get(AuthenticatedUser user, String country, String platform, String channel) {
		Channel scope = scope(country, platform, channel);
		admin.require(user, PERMISSION_READ, "BILLING_PROVIDER_ROUTES_READ", "billing_provider_routes",
				key(country, scope));
		return read(country, scope);
	}

	ProviderRouteSetDto replace(AuthenticatedUser user, String country, String platform, String channel,
			ProviderRouteSetUpdate body) {
		Channel scope = scope(country, platform, channel);
		String entity = key(country, scope);
		AdminAccess.Actor actor = admin.require(user, PERMISSION_CONFIGURE, ACTION, "billing_provider_routes", entity);
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < body.routes().size(); i++) {
			if (!seen.add(body.routes().get(i).providerCode())) {
				throw BillingErrors.invalid("routes/" + i + "/providerCode", "DUPLICATE", "Each provider once.");
			}
		}
		return transactions.execute(s -> {
			jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", (rs, i) -> null, "billing_provider_routes:" + entity);
			int current = revision(country, scope);
			if (current != body.expectedRevision()) {
				throw new ApiException(ApiErrorCode.REVISION_CONFLICT, HttpStatus.CONFLICT,
						"The routes changed since they were read.", List.of(), Map.of("currentRevision", current));
			}
			List<UUID> providerIds = new ArrayList<>();
			for (int i = 0; i < body.routes().size(); i++) {
				String code = body.routes().get(i).providerCode();
				UUID id = jdbc.query("SELECT id FROM payment_providers WHERE provider_code = ?",
						(rs, n) -> rs.getObject(1, UUID.class), code).stream().findFirst().orElse(null);
				if (id == null) {
					throw BillingErrors.invalid("routes/" + i + "/providerCode", "UNKNOWN_PROVIDER",
							"No payment provider with this code.");
				}
				providerIds.add(id);
			}
			jdbc.update("""
					DELETE FROM billing_provider_routes
					WHERE country_code = ? AND client_platform = ? AND distribution_channel = ?""", country,
					scope.platform(), scope.distribution());
			int next = current + 1;
			for (int i = 0; i < body.routes().size(); i++) {
				RouteInput r = body.routes().get(i);
				jdbc.update("""
						INSERT INTO billing_provider_routes (country_code, client_platform, distribution_channel,
						       provider_id, priority, is_enabled, notes, revision, created_at, updated_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", country, scope.platform(), scope.distribution(),
						providerIds.get(i), i + 1, r.enabled() == null || r.enabled(), r.notes(), next,
						OrderService.utc(clock.instant()), OrderService.utc(clock.instant()));
			}
			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put("previousRevision", current);
			meta.put("providers", body.routes().stream().map(RouteInput::providerCode).toList());
			audit.record(new AuditTrail.Entry(actor.userId(), null, ACTION, "billing_provider_routes", entity,
					AuditTrail.Outcome.SUCCESS, meta));
			return read(country, scope); // an emptied scope reads as revision 0, like one never configured
		});
	}

	private ProviderRouteSetDto read(String country, Channel scope) {
		List<ProviderRouteDto> routes = jdbc.query("""
				SELECT p.provider_code, r.priority, r.is_enabled, r.notes, (p.is_active AND p.provider_status = 'ACTIVE')
				FROM billing_provider_routes r JOIN payment_providers p ON p.id = r.provider_id
				WHERE r.country_code = ? AND r.client_platform = ? AND r.distribution_channel = ?
				ORDER BY r.priority""",
				(rs, i) -> new ProviderRouteDto(rs.getString(1), rs.getInt(2), rs.getBoolean(3), rs.getString(4),
						rs.getBoolean(5), registry.registered(rs.getString(1), properties.environment())),
				country, scope.platform(), scope.distribution());
		return new ProviderRouteSetDto(country, scope.platform(), scope.distribution(), revision(country, scope),
				routes);
	}

	private int revision(String country, Channel scope) {
		Integer r = jdbc.queryForObject("""
				SELECT COALESCE(MAX(revision), 0) FROM billing_provider_routes
				WHERE country_code = ? AND client_platform = ? AND distribution_channel = ?""", Integer.class, country,
				scope.platform(), scope.distribution());
		return r == null ? 0 : r;
	}

	private static Channel scope(String country, String platform, String channel) {
		if (country == null || !country.matches("[A-Z]{2}")) {
			throw BillingErrors.invalid("countryCode", "INVALID", "ISO 3166-1 alpha-2, or ZZ.");
		}
		if (!Set.of("ANDROID", "IOS", "WEB").contains(platform)) {
			throw BillingErrors.invalid("clientPlatform", "INVALID", "ANDROID, IOS or WEB.");
		}
		if (!Set.of("GOOGLE_PLAY", "APPLE_APP_STORE", "DIRECT_DOWNLOAD", "WEB").contains(channel)) {
			throw BillingErrors.invalid("distributionChannel", "INVALID",
					"GOOGLE_PLAY, APPLE_APP_STORE, DIRECT_DOWNLOAD or WEB.");
		}
		return new Channel(platform, channel);
	}

	private static String key(String country, Channel scope) {
		return country + "/" + scope.platform() + "/" + scope.distribution();
	}
}
