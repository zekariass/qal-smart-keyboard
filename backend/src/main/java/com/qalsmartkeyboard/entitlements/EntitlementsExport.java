package com.qalsmartkeyboard.entitlements;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.ExportRows;
import com.qalsmartkeyboard.identity.ExportWriter;
import com.qalsmartkeyboard.identity.UserExportParticipant;

/**
 * Data export (B6.1, ADR 0022) of the entitlement tables: the person's subscriptions (plan, source, status, periods;
 * provider and mandate references are internal ids and left out) and the feature overrides set on the account.
 */
@Component
class EntitlementsExport implements UserExportParticipant {

	private final JdbcClient jdbc;

	EntitlementsExport(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int exportOrder() {
		return ENTITLEMENTS;
	}

	@Override
	public void exportUser(UUID userId, ExportWriter out) {
		Map<String, Object> user = Map.of("u", userId);
		out.section("subscriptions", "Financial records", "Subscriptions: plan, source, billing mode, status, periods, "
				+ "renewal and cancellation. Provider and mandate references are not included.", ExportRows.query(jdbc, """
						SELECT s.id, p.plan_code, s.source_type, s.billing_mode, s.subscription_status,
						       s.current_period_start, s.current_period_end, s.grace_period_end, s.auto_renew_enabled,
						       s.cancel_at_period_end, s.next_billing_at, s.started_at, s.cancelled_at, s.revoked_at,
						       s.last_verified_at, s.created_at, s.updated_at
						FROM user_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
						WHERE s.user_id = :u ORDER BY s.created_at, s.id
						""", user));
		out.section("featureOverrides", "Account and preferences", "Feature switches set on the account (support or "
				+ "testing), with reason and expiry.", ExportRows.query(jdbc, """
						SELECT o.id, f.flag_code, o.is_enabled, o.reason, o.expires_at, o.created_at
						FROM user_feature_overrides o JOIN feature_flags f ON f.id = o.feature_flag_id
						WHERE o.user_id = :u ORDER BY o.created_at, o.id
						""", user));
	}
}
