package com.qalsmartkeyboard.billing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;

/** SQL behind {@link RoutingData}. */
final class JdbcRoutingData implements RoutingData {

	private final JdbcTemplate jdbc;

	JdbcRoutingData(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<Route> routes(String countryCode, String platform, String distribution) {
		return jdbc.query("""
				SELECT p.provider_code, p.id, p.provider_type, p.provider_status, p.is_active, r.priority, r.is_enabled
				FROM billing_provider_routes r
				JOIN payment_providers p ON p.id = r.provider_id
				WHERE r.country_code = ? AND r.client_platform = ? AND r.distribution_channel = ?
				ORDER BY r.priority""",
				(rs, i) -> new Route(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
						rs.getBoolean(5), rs.getInt(6), rs.getBoolean(7)),
				countryCode, platform, distribution);
	}

	@Override
	public Optional<Policy> policy(String countryCode, String platform, String distribution) {
		return jdbc.query("""
				SELECT purchase_flow, approval_status, purchase_ui_allowed, external_payment_allowed, effective_from,
				       effective_until
				FROM billing_market_channel_policies
				WHERE country_code = ? AND client_platform = ? AND distribution_channel = ?""",
				(rs, i) -> new Policy(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4),
						instant(rs, 5), instant(rs, 6)),
				countryCode, platform, distribution).stream().findFirst();
	}

	@Override
	public List<Method> methods(UUID providerId, String countryCode, PaymentEnvironment environment,
			String currencyCode) {
		return jdbc.query("""
				SELECT a.id, a.account_code, a.currency_code, a.merchant_account_reference, m.id, am.id, m.method_code,
				       m.provider_method_code, m.display_name, m.method_type, (m.requires_redirect OR am.requires_redirect),
				       (m.supports_auto_renew AND am.supports_auto_renew),
				       GREATEST(m.min_amount_minor, am.min_amount_minor), LEAST(m.max_amount_minor, am.max_amount_minor)
				FROM payment_provider_accounts a
				JOIN payment_provider_account_methods am ON am.provider_account_id = a.id
				JOIN provider_payment_methods m ON m.id = am.provider_payment_method_id AND m.provider_id = a.provider_id
				WHERE a.provider_id = ? AND a.country_code = ? AND a.environment = ?
				  AND a.is_active AND a.account_status = 'ACTIVE'
				  AND am.is_enabled AND am.availability_status = 'AVAILABLE'
				  AND m.is_enabled AND m.availability_status = 'AVAILABLE'
				  AND (CAST(? AS TEXT) IS NULL OR a.currency_code = ?)
				ORDER BY a.account_code, m.method_code""",
				(rs, i) -> new Method(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8),
						rs.getString(9), rs.getString(10), rs.getBoolean(11), rs.getBoolean(12), nullableLong(rs, 13),
						nullableLong(rs, 14)),
				providerId, countryCode, environment.name(), currencyCode, currencyCode);
	}

	static Instant instant(ResultSet rs, int column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

	static Long nullableLong(ResultSet rs, int column) throws SQLException {
		long v = rs.getLong(column);
		return rs.wasNull() ? null : v;
	}
}
