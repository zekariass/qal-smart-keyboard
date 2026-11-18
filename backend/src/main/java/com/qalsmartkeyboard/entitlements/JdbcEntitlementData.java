package com.qalsmartkeyboard.entitlements;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read-only SQL over {@code users}, {@code user_subscriptions}, {@code store_purchases}, the organisation seat tables,
 * {@code subscription_plans}, {@code plan_entitlements}, {@code feature_catalog}, {@code feature_flags} and
 * {@code user_feature_overrides}. No other module reads these tables for entitlement decisions; the billing and
 * organisations modules have no Java API yet (M4.x, E7.x), so the reads live here until they do.
 */
class JdbcEntitlementData implements EntitlementData {

	private final JdbcTemplate jdbc;

	JdbcEntitlementData(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Account> account(UUID userId) {
		return jdbc.query("SELECT id, account_type, account_status, deleted_at FROM users WHERE id = ?",
				(rs, i) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
						instant(rs, 4)),
				userId).stream().findFirst();
	}

	@Override
	public List<SourceRow> candidateSources(UUID userId) {
		List<SourceRow> rows = new ArrayList<>();
		rows.addAll(jdbc.query("""
				SELECT s.id, s.source_type, s.subscription_status, s.current_period_start, s.current_period_end,
				       s.grace_period_end, sp.purchase_status, p.id, p.plan_code, p.display_name, p.plan_rank
				FROM user_subscriptions s
				JOIN subscription_plans p ON p.id = s.plan_id
				LEFT JOIN store_purchases sp ON sp.id = s.store_purchase_id
				WHERE s.user_id = ? AND s.subscription_status IN ('ACTIVE', 'GRACE_PERIOD')""",
				(rs, i) -> new SourceRow(subscriptionSource(rs.getString(2)), rs.getObject(1, UUID.class), null,
						rs.getString(3), instant(rs, 4), instant(rs, 5), instant(rs, 6), rs.getString(7), plan(rs, 8)),
				userId));
		rows.addAll(jdbc.query("""
				SELECT a.organization_id, os.id, os.subscription_status, os.current_period_start, os.current_period_end,
				       p.id, p.plan_code, p.display_name, p.plan_rank
				FROM organization_seat_assignments a
				JOIN organization_subscriptions os ON os.id = a.organization_subscription_id
				JOIN organizations o ON o.id = os.organization_id AND o.org_status = 'ACTIVE'
				JOIN organization_members m ON m.organization_id = a.organization_id AND m.user_id = a.user_id
				     AND m.member_status = 'ACTIVE'
				JOIN subscription_plans p ON p.id = os.plan_id
				WHERE a.user_id = ? AND a.assignment_status = 'ACTIVE'
				  AND os.subscription_status IN ('ACTIVE', 'GRACE_PERIOD')""",
				(rs, i) -> new SourceRow(PlanSource.ORGANIZATION_SEAT, rs.getObject(2, UUID.class),
						rs.getObject(1, UUID.class), rs.getString(3), instant(rs, 4), instant(rs, 5), null, null,
						plan(rs, 6)),
				userId));
		return rows;
	}

	@Override
	public Optional<PlanRef> defaultPlan() {
		return jdbc.query("SELECT id, plan_code, display_name, plan_rank FROM subscription_plans WHERE is_default",
				(rs, i) -> plan(rs, 1)).stream().findFirst();
	}

	@Override
	public Optional<PlanRef> planByCode(String planCode) {
		return jdbc.query("SELECT id, plan_code, display_name, plan_rank FROM subscription_plans WHERE plan_code = ?",
				(rs, i) -> plan(rs, 1), planCode).stream().findFirst();
	}

	@Override
	public List<CatalogFeature> catalog() {
		return jdbc.query("""
				SELECT feature_code, metering_unit, is_entitlement_gated, credit_cost_per_unit
				FROM feature_catalog WHERE is_active ORDER BY feature_code""",
				(rs, i) -> new CatalogFeature(rs.getString(1), MeteringUnit.valueOf(rs.getString(2)),
						rs.getBoolean(3), rs.getBigDecimal(4)));
	}

	@Override
	public List<PlanFeature> planFeatures(UUID planId, String quotaPolicyVersion) {
		return jdbc.query("""
				SELECT feature_code, access_enabled, included_units, reset_period
				FROM plan_entitlements
				WHERE plan_id = ? AND configuration ->> 'quotaPolicyVersion' = ?""",
				(rs, i) -> new PlanFeature(rs.getString(1), rs.getBoolean(2), (Long) rs.getObject(3),
						ResetPeriod.valueOf(rs.getString(4))),
				planId, quotaPolicyVersion);
	}

	@Override
	public List<FeatureOverride> overrides(UUID userId, Instant at) {
		return jdbc.query("""
				SELECT f.feature_code, o.is_enabled
				FROM user_feature_overrides o
				JOIN feature_flags f ON f.id = o.feature_flag_id
				WHERE o.user_id = ? AND f.feature_code IS NOT NULL
				  AND (o.expires_at IS NULL OR o.expires_at > ?)""",
				(rs, i) -> new FeatureOverride(rs.getString(1), rs.getBoolean(2)),
				userId, java.sql.Timestamp.from(at));
	}

	private static PlanSource subscriptionSource(String sourceType) {
		return switch (sourceType) {
			case "STORE" -> PlanSource.STORE_PURCHASE;
			case "PARTNER" -> PlanSource.PARTNER;
			default -> PlanSource.SUBSCRIPTION; // PAYMENT, PROMOTION, ADMIN_GRANT
		};
	}

	private static PlanRef plan(ResultSet rs, int firstColumn) throws SQLException {
		return new PlanRef(rs.getObject(firstColumn, UUID.class), rs.getString(firstColumn + 1),
				rs.getString(firstColumn + 2), rs.getInt(firstColumn + 3));
	}

	private static Instant instant(ResultSet rs, int column) throws SQLException {
		java.sql.Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}
}
