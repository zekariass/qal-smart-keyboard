package com.qalsmartkeyboard.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.qalsmartkeyboard.entitlements.EntitlementData.SourceRow;

/**
 * B4.1 done-when: every seeded plan x every catalogue feature resolves to what {@code V1__baseline.sql} says. The seed
 * is parsed out of the migration ({@link SeedSql}); the SQL path against real rows is {@code EntitlementResolverIT}.
 */
class EntitlementSeedMatrixTest {

	static final Instant NOW = Instant.parse("2026-11-18T09:00:00Z");

	static Stream<Arguments> planByFeature() {
		SeedSql seed = SeedSql.load();
		return seed.plans.stream().flatMap(p -> seed.catalog.stream().map(f -> Arguments.of(p.code(), f.code())));
	}

	@Test
	void seedHasTheExpectedShape() {
		SeedSql seed = SeedSql.load();
		assertThat(seed.plans).extracting(SeedSql.Plan::code)
				.containsExactly("GUEST", "FREE", "PREMIUM", "PREMIUM_PLUS", "BUSINESS");
		assertThat(seed.catalog).hasSize(25);
		assertThat(seed.catalog.stream().filter(SeedSql.Feature::gated)).hasSize(20);
		assertThat(seed.enabled).hasSize(4 + 9 + 18 + 20 + 20); // GUEST, FREE, PREMIUM, PREMIUM_PLUS, BUSINESS rows
		assertThat(planByFeature().count()).isEqualTo(5 * 25);
	}

	@ParameterizedTest(name = "{0} / {1}")
	@MethodSource("planByFeature")
	void resolvedFeatureEqualsTheSeed(String planCode, String featureCode) {
		SeedSql seed = SeedSql.load();
		FakeEntitlementData data = new FakeEntitlementData();
		UUID userId = userOnPlan(data, seed, planCode);
		EntitlementResolver resolver = new EntitlementResolver(data,
				new EntitlementProperties("PROVISIONAL_V2", "GUEST", Duration.ofDays(3), Duration.ZERO, 100, Duration.ofHours(24)),
				Clock.fixed(NOW, ZoneOffset.UTC));

		ResolvedEntitlements resolved = resolver.resolve(userId);

		assertThat(resolved.access()).isEqualTo(AccessState.GRANTED);
		assertThat(resolved.plan().orElseThrow().planCode()).isEqualTo(planCode);
		assertThat(resolved.features()).hasSize(25);
		FeatureEntitlement actual = resolved.feature(featureCode).orElseThrow();
		SeedSql.Feature catalog = seed.feature(featureCode);
		assertThat(actual.unit()).isEqualTo(catalog.unit());
		assertThat(actual.gated()).isEqualTo(catalog.gated());
		assertThat(actual.creditCostPerUnit()).isEqualByComparingTo(catalog.creditCost());

		if (!catalog.gated()) {
			// SD R-2: typing (and unmetered DETECT_LANGUAGE) is never gated, on any plan.
			assertThat(actual.accessEnabled()).isTrue();
			assertThat(actual.origin()).isEqualTo(FeatureEntitlement.Origin.UNGATED);
			assertThat(actual.creditsUsable()).isFalse();
			return;
		}
		SeedSql.Allowance row = seed.enabledRow(planCode, featureCode);
		assertThat(actual.origin()).isEqualTo(FeatureEntitlement.Origin.PLAN);
		if (row == null) {
			assertThat(actual.accessEnabled()).as("seed lists no enabled row: disabled").isFalse();
			assertThat(actual.includedUnits()).isEqualTo(catalog.unit() == MeteringUnit.NONE ? null : 0L);
			assertThat(actual.resetPeriod()).isEqualTo(ResetPeriod.NONE);
			assertThat(actual.creditsUsable()).isFalse();
		} else {
			assertThat(actual.accessEnabled()).isTrue();
			assertThat(actual.includedUnits()).isEqualTo(row.includedUnits());
			assertThat(actual.resetPeriod()).isEqualTo(row.period());
			assertThat(actual.creditsUsable())
					.isEqualTo(catalog.unit() != MeteringUnit.NONE && catalog.creditCost().signum() > 0);
		}
	}

	/** GUEST and FREE need no source; BUSINESS comes through an organisation seat; the rest through a subscription. */
	private static UUID userOnPlan(FakeEntitlementData data, SeedSql seed, String planCode) {
		SeedSql.Plan plan = seed.plans.stream().filter(p -> p.code().equals(planCode)).findFirst().orElseThrow();
		if (plan.rank() < 0) {
			return data.user("GUEST", "ACTIVE");
		}
		UUID user = data.user("REGISTERED", "ACTIVE");
		if (plan.isDefault()) {
			return user;
		}
		boolean seat = plan.code().equals("BUSINESS");
		data.add(user, new SourceRow(seat ? PlanSource.ORGANIZATION_SEAT : PlanSource.SUBSCRIPTION, UUID.randomUUID(),
				seat ? UUID.randomUUID() : null, "ACTIVE", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(29)),
				null, null, data.plan(planCode)));
		return user;
	}
}
