package com.qalsmartkeyboard.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * B4.1 against PostgreSQL with the real V1 seed rows: the SQL adapter, the seed (plans x catalogue features, compared
 * with the migration text via {@link SeedSql}) and the source tables with their real constraints.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s" })
@Testcontainers
class EntitlementResolverIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Autowired
	EntitlementResolver resolver;
	@Autowired
	JdbcTemplate jdbc;

	// ------------------------------------------------------------------ the seed

	@Test
	void everySeededPlanResolvesToTheSeedForEveryFeature() {
		SeedSql seed = SeedSql.load();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_entitlements", Integer.class)).isEqualTo(5 * 35);
		int checked = 0;
		for (SeedSql.Plan plan : seed.plans) {
			UUID user = plan.rank() < 0 ? user("GUEST", "ACTIVE") : user("REGISTERED", "ACTIVE");
			if (!plan.isDefault() && plan.rank() >= 0) {
				if (plan.code().equals("BUSINESS")) {
					seat(user, plan.code(), "ACTIVE", Instant.now().minus(Duration.ofDays(1)),
							Instant.now().plus(Duration.ofDays(29)));
				} else {
					subscription(user, plan.code(), "PAYMENT", "ACTIVE", Instant.now().minus(Duration.ofDays(1)),
							Instant.now().plus(Duration.ofDays(29)), null);
				}
			}
			ResolvedEntitlements r = resolver.resolve(user);
			assertThat(r.plan().orElseThrow().planCode()).isEqualTo(plan.code());
			assertThat(r.plan().orElseThrow().planRank()).isEqualTo(plan.rank());
			assertThat(r.plan().orElseThrow().displayName()).isEqualTo(plan.displayName());
			assertThat(r.features()).hasSize(seed.catalog.size());
			for (SeedSql.Feature f : seed.catalog) {
				FeatureEntitlement actual = r.feature(f.code()).orElseThrow();
				String where = plan.code() + "/" + f.code();
				assertThat(actual.unit()).as(where).isEqualTo(f.unit());
				if (!f.gated()) {
					assertThat(actual.accessEnabled()).as(where).isTrue();
				} else {
					SeedSql.Allowance row = seed.enabledRow(plan.code(), f.code());
					assertThat(actual.accessEnabled()).as(where).isEqualTo(row != null);
					assertThat(actual.includedUnits()).as(where)
							.isEqualTo(row != null ? row.includedUnits() : f.unit() == MeteringUnit.NONE ? null : 0L);
					assertThat(actual.resetPeriod()).as(where).isEqualTo(row != null ? row.period() : ResetPeriod.NONE);
					assertThat(actual.origin()).as(where).isEqualTo(FeatureEntitlement.Origin.PLAN);
				}
				checked++;
			}
		}
		assertThat(checked).isEqualTo(5 * 41);
	}

	@Test
	void guestGetsTheGuestPlanWithoutAnySubscriptionRow() {
		UUID guest = user("GUEST", "ACTIVE");
		ResolvedEntitlements r = resolver.resolve(guest);
		assertThat(r.plan().orElseThrow().planCode()).isEqualTo("GUEST");
		assertThat(r.plan().orElseThrow().source()).isEqualTo(PlanSource.DEFAULT);
		assertThat(r.feature("TRANSLATE").orElseThrow().includedUnits()).isEqualTo(3L);
		assertThat(r.feature("TRANSLATE").orElseThrow().resetPeriod()).isEqualTo(ResetPeriod.DAILY);
		assertThat(r.isAllowed("TONE_ADJUST")).isFalse();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_subscriptions WHERE user_id = ?", Integer.class, guest))
				.isZero();
	}

	@Test
	void quotaPolicyVersionComesFromConfigurationNotCode() {
		jdbc.update("""
				UPDATE plan_entitlements SET configuration = jsonb_set(configuration, '{quotaPolicyVersion}', '"V3_TEST"')
				WHERE plan_id = (SELECT id FROM subscription_plans WHERE plan_code = 'BUSINESS')""");
		try {
			UUID user = user("REGISTERED", "ACTIVE");
			seat(user, "BUSINESS", "ACTIVE", Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(9)));
			// the configured version is PROVISIONAL_V2; BUSINESS rows now carry another version: fail closed
			ResolvedEntitlements r = resolver.resolve(user);
			assertThat(r.plan().orElseThrow().planCode()).isEqualTo("BUSINESS");
			assertThat(r.isAllowed("TRANSLATE")).isFalse();
			assertThat(r.feature("TRANSLATE").orElseThrow().origin()).isEqualTo(FeatureEntitlement.Origin.NO_PLAN_ROW);
		} finally {
			jdbc.update("""
					UPDATE plan_entitlements SET configuration = jsonb_set(configuration, '{quotaPolicyVersion}', '"PROVISIONAL_V2"')
					WHERE plan_id = (SELECT id FROM subscription_plans WHERE plan_code = 'BUSINESS')""");
		}
	}

	// ------------------------------------------------------------------ sources

	@Test
	void subscriptionSourceTypesAreToldApart() {
		Instant start = Instant.now().minus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		Instant end = Instant.now().plus(Duration.ofDays(29)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		UUID partner = user("REGISTERED", "ACTIVE");
		UUID partnerSub = subscription(partner, "PREMIUM", "PARTNER", "ACTIVE", start, end, null);
		assertThat(resolver.resolve(partner).plan().orElseThrow().source()).isEqualTo(PlanSource.PARTNER);
		assertThat(resolver.resolve(partner).plan().orElseThrow().subscriptionId()).isEqualTo(partnerSub);

		UUID promo = user("REGISTERED", "ACTIVE");
		subscription(promo, "PREMIUM", "PROMOTION", "ACTIVE", start, end, null);
		assertThat(resolver.resolve(promo).plan().orElseThrow().source()).isEqualTo(PlanSource.SUBSCRIPTION);

		UUID store = user("REGISTERED", "ACTIVE");
		UUID purchase = storePurchase(store, "ACTIVE");
		UUID storeSub = subscription(store, "PREMIUM_PLUS", "STORE", "ACTIVE", start, end, purchase);
		EffectivePlan p = resolver.resolve(store).plan().orElseThrow();
		assertThat(p.source()).isEqualTo(PlanSource.STORE_PURCHASE);
		assertThat(p.subscriptionId()).isEqualTo(storeSub);
		assertThat(p.effectiveUntil()).isEqualTo(end);

		jdbc.update("UPDATE store_purchases SET purchase_status = 'REFUNDED' WHERE id = ?", purchase);
		assertThat(resolver.resolve(store).plan().orElseThrow().planCode()).isEqualTo("FREE");
	}

	@Test
	void precedenceAcrossSubscriptionStoreSeatAndPartnerIsByRankThenTieBreaks() {
		Instant start = Instant.now().minus(Duration.ofDays(1));
		Instant end = Instant.now().plus(Duration.ofDays(29));
		UUID user = user("REGISTERED", "ACTIVE");
		subscription(user, "PREMIUM", "PAYMENT", "ACTIVE", start, end, null);
		subscription(user, "PREMIUM", "PARTNER", "ACTIVE", start, end.plus(Duration.ofDays(5)), null);
		assertThat(resolver.resolve(user).plan().orElseThrow().source()).as("same rank: later end wins")
				.isEqualTo(PlanSource.PARTNER);
		UUID orgId = seat(user, "BUSINESS", "ACTIVE", start, end);
		EffectivePlan p = resolver.resolve(user).plan().orElseThrow();
		assertThat(p.planCode()).isEqualTo("BUSINESS");
		assertThat(p.organizationId()).isEqualTo(orgId);
		assertThat(resolver.resolve(user).feature("TRANSLATE").orElseThrow().includedUnits()).isEqualTo(3000L);
	}

	@Test
	void expiryEdgesAndGraceAgainstRealRows() {
		Instant end = Instant.now().plus(Duration.ofHours(2)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		UUID user = user("REGISTERED", "ACTIVE");
		subscription(user, "PREMIUM", "PAYMENT", "ACTIVE", end.minus(Duration.ofDays(30)), end, null);
		assertThat(resolver.resolve(user, end.minusNanos(1_000)).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");
		// D22: the default 24 h renewal lag keeps the ACTIVE row valid past its end
		assertThat(resolver.resolve(user, end).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");
		assertThat(resolver.resolve(user, end).plan().orElseThrow().effectiveUntil()).isEqualTo(end);
		assertThat(resolver.resolve(user, end.plus(Duration.ofHours(24)).minusNanos(1_000)).plan().orElseThrow()
				.planCode()).isEqualTo("PREMIUM");
		assertThat(resolver.resolve(user, end.plus(Duration.ofHours(24))).plan().orElseThrow().planCode())
				.isEqualTo("FREE");

		UUID graceUser = user("REGISTERED", "ACTIVE");
		Instant periodEnd = Instant.now().minus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		subscription(graceUser, "PREMIUM", "PAYMENT", "GRACE_PERIOD", periodEnd.minus(Duration.ofDays(30)), periodEnd,
				null);
		EffectivePlan p = resolver.resolve(graceUser).plan().orElseThrow();
		assertThat(p.planCode()).isEqualTo("PREMIUM");
		assertThat(p.inGracePeriod()).isTrue();
		assertThat(p.effectiveUntil()).isEqualTo(periodEnd.plus(Duration.ofDays(3)));
		assertThat(resolver.resolve(graceUser, periodEnd.plus(Duration.ofDays(3))).plan().orElseThrow().planCode())
				.isEqualTo("FREE");

		UUID pastDue = user("REGISTERED", "ACTIVE");
		subscription(pastDue, "PREMIUM", "PAYMENT", "PAST_DUE", periodEnd.minus(Duration.ofDays(30)), periodEnd, null);
		assertThat(resolver.resolve(pastDue).plan().orElseThrow().planCode()).isEqualTo("FREE");
	}

	@Test
	void seatNeedsActiveAssignmentMembershipOrganisationAndSubscription() {
		Instant start = Instant.now().minus(Duration.ofDays(1));
		Instant end = Instant.now().plus(Duration.ofDays(29));
		UUID user = user("REGISTERED", "ACTIVE");
		UUID org = UUID.randomUUID();
		UUID orgSub = UUID.randomUUID();
		UUID assignment = UUID.randomUUID();
		seatRows(user, org, orgSub, assignment, "BUSINESS", "ACTIVE", start, end);
		assertThat(resolver.resolve(user).plan().orElseThrow().planCode()).isEqualTo("BUSINESS");

		jdbc.update("UPDATE organization_members SET member_status = 'SUSPENDED' WHERE organization_id = ? AND user_id = ?",
				org, user);
		assertThat(resolver.resolve(user).plan().orElseThrow().planCode()).as("suspended member").isEqualTo("FREE");
		jdbc.update("UPDATE organization_members SET member_status = 'ACTIVE' WHERE organization_id = ? AND user_id = ?",
				org, user);

		jdbc.update("UPDATE organizations SET org_status = 'SUSPENDED' WHERE id = ?", org);
		assertThat(resolver.resolve(user).plan().orElseThrow().planCode()).as("suspended organisation").isEqualTo("FREE");
		jdbc.update("UPDATE organizations SET org_status = 'ACTIVE' WHERE id = ?", org);

		jdbc.update("UPDATE organization_subscriptions SET subscription_status = 'EXPIRED' WHERE id = ?", orgSub);
		assertThat(resolver.resolve(user).plan().orElseThrow().planCode()).as("expired subscription").isEqualTo("FREE");
		jdbc.update("UPDATE organization_subscriptions SET subscription_status = 'ACTIVE' WHERE id = ?", orgSub);

		jdbc.update("UPDATE organization_seat_assignments SET assignment_status = 'REVOKED', revoked_at = now() WHERE id = ?",
				assignment);
		assertThat(resolver.resolve(user).plan().orElseThrow().planCode()).as("revoked seat").isEqualTo("FREE");
	}

	// ------------------------------------------------------------------ account state and overrides

	@Test
	void suspendedAndDeletedAccountsGetNothing() {
		UUID suspended = user("REGISTERED", "SUSPENDED");
		subscription(suspended, "PREMIUM", "PAYMENT", "ACTIVE", Instant.now().minus(Duration.ofDays(1)),
				Instant.now().plus(Duration.ofDays(9)), null);
		ResolvedEntitlements r = resolver.resolve(suspended);
		assertThat(r.access()).isEqualTo(AccessState.SUSPENDED);
		assertThat(r.plan()).isEmpty();
		assertThat(r.isAllowed("TRANSLATE")).isFalse();
		assertThat(r.isAllowed("OFFLINE_TYPING")).isTrue();

		UUID deleted = user("REGISTERED", "DELETED");
		assertThat(resolver.resolve(deleted).access()).isEqualTo(AccessState.DELETED);
		assertThat(resolver.resolve(UUID.randomUUID()).access()).isEqualTo(AccessState.UNKNOWN);
	}

	@Test
	void featureOverridesJoinThroughTheFlagsFeatureCodeAndExpire() {
		UUID user = user("REGISTERED", "ACTIVE");
		UUID flag = flag("ENT_IT_TONE_" + UUID.randomUUID(), "TONE_ADJUST");
		UUID uiFlag = flag("ENT_IT_UI_" + UUID.randomUUID(), null);
		override(user, flag, true, null);
		override(user, uiFlag, true, null);
		FeatureEntitlement tone = resolver.resolve(user).feature("TONE_ADJUST").orElseThrow();
		assertThat(tone.accessEnabled()).isTrue();
		assertThat(tone.includedUnits()).isZero();
		assertThat(tone.creditsUsable()).isTrue();
		assertThat(tone.origin()).isEqualTo(FeatureEntitlement.Origin.OVERRIDE);

		UUID premium = user("REGISTERED", "ACTIVE");
		subscription(premium, "PREMIUM", "PAYMENT", "ACTIVE", Instant.now().minus(Duration.ofDays(1)),
				Instant.now().plus(Duration.ofDays(9)), null);
		UUID translate = flag("ENT_IT_TR_" + UUID.randomUUID(), "TRANSLATE");
		override(premium, translate, false, Instant.now().plus(Duration.ofHours(1)));
		assertThat(resolver.resolve(premium).isAllowed("TRANSLATE")).isFalse();
		assertThat(resolver.resolve(premium, Instant.now().plus(Duration.ofHours(2))).isAllowed("TRANSLATE")).isTrue();
	}

	// ------------------------------------------------------------------ fixtures

	private UUID user(String type, String status) {
		return jdbc.queryForObject(
				"INSERT INTO users (account_type, account_status) VALUES (?, ?) RETURNING id", UUID.class, type, status);
	}

	private UUID subscription(UUID user, String plan, String sourceType, String status, Instant start, Instant end,
			UUID storePurchase) {
		String mode = switch (sourceType) {
			case "STORE" -> "STORE_MANAGED";
			case "PARTNER", "PROMOTION", "ADMIN_GRANT" -> "GRANT";
			default -> "MANUAL_RENEW";
		};
		return jdbc.queryForObject("""
				INSERT INTO user_subscriptions (user_id, plan_id, source_type, billing_mode, store_purchase_id,
				    subscription_status, current_period_start, current_period_end)
				VALUES (?, (SELECT id FROM subscription_plans WHERE plan_code = ?), ?, ?, ?, ?, ?, ?) RETURNING id""",
				UUID.class, user, plan, sourceType, mode, storePurchase, status, Timestamp.from(start),
				Timestamp.from(end));
	}

	/** A store purchase without its billing catalogue: foreign keys are switched off for this one statement. */
	private UUID storePurchase(UUID user, String status) {
		UUID id = UUID.randomUUID();
		jdbc.execute((java.sql.Connection c) -> {
			try (var st = c.createStatement()) {
				st.execute("SET session_replication_role = replica");
				try (var ps = c.prepareStatement("""
						INSERT INTO store_purchases (id, user_id, provider_id, external_product_mapping_id,
						    purchase_token_hash, environment, purchase_status, purchased_at)
						VALUES (?, ?, ?, ?, ?, 'SANDBOX', ?, now())""")) {
					ps.setObject(1, id);
					ps.setObject(2, user);
					ps.setObject(3, UUID.randomUUID());
					ps.setObject(4, UUID.randomUUID());
					ps.setString(5, "a".repeat(64));
					ps.setString(6, status);
					ps.executeUpdate();
				}
				st.execute("SET session_replication_role = DEFAULT");
			}
			return null;
		});
		return id;
	}

	/** An organisation with the user as member, a subscription on the plan and an active seat. Returns the org id. */
	private UUID seat(UUID user, String plan, String status, Instant start, Instant end) {
		UUID org = UUID.randomUUID();
		seatRows(user, org, UUID.randomUUID(), UUID.randomUUID(), plan, status, start, end);
		return org;
	}

	private void seatRows(UUID user, UUID org, UUID orgSub, UUID assignment, String plan, String status, Instant start,
			Instant end) {
		jdbc.update("INSERT INTO organizations (id, org_slug, display_name) VALUES (?, ?, 'Test Org')", org,
				"it-" + org.toString().substring(0, 12));
		jdbc.update("INSERT INTO organization_members (organization_id, user_id) VALUES (?, ?)", org, user);
		jdbc.update("""
				INSERT INTO organization_subscriptions (id, organization_id, plan_id, seat_count, subscription_status,
				    current_period_start, current_period_end)
				VALUES (?, ?, (SELECT id FROM subscription_plans WHERE plan_code = ?), 5, ?, ?, ?)""",
				orgSub, org, plan, status, Timestamp.from(start), Timestamp.from(end));
		jdbc.update("""
				INSERT INTO organization_seat_assignments (id, organization_subscription_id, organization_id, user_id)
				VALUES (?, ?, ?, ?)""", assignment, orgSub, org, user);
	}

	private UUID flag(String code, String featureCode) {
		return jdbc.queryForObject("INSERT INTO feature_flags (flag_code, feature_code) VALUES (?, ?) RETURNING id",
				UUID.class, code, featureCode);
	}

	private void override(UUID user, UUID flag, boolean enabled, Instant expiresAt) {
		jdbc.update("INSERT INTO user_feature_overrides (user_id, feature_flag_id, is_enabled, expires_at) VALUES (?, ?, ?, ?)",
				user, flag, enabled, expiresAt == null ? null : Timestamp.from(expiresAt));
	}
}
