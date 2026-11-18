package com.qalsmartkeyboard.entitlements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.qalsmartkeyboard.entitlements.EntitlementData.Account;
import com.qalsmartkeyboard.entitlements.EntitlementData.FeatureOverride;
import com.qalsmartkeyboard.entitlements.EntitlementData.SourceRow;

/** Source precedence, validity edges, grace, account states, overrides and the cache (SD 15.5, 16.5). */
class EntitlementResolverTest {

	static final Instant NOW = Instant.parse("2026-11-18T09:00:00Z");
	static final Duration GRACE = Duration.ofDays(3);
	static final Duration LAG = Duration.ofHours(24); // D22 default renewal lag

	FakeEntitlementData data;
	AtomicReference<Instant> clockNow;
	EntitlementResolver resolver;
	UUID user;

	@BeforeEach
	void setUp() {
		data = new FakeEntitlementData();
		clockNow = new AtomicReference<>(NOW);
		resolver = resolverWith(Duration.ZERO, LAG);
		user = data.user("REGISTERED", "ACTIVE");
	}

	private EntitlementResolver resolverWith(Duration cacheTtl) {
		return resolverWith(cacheTtl, LAG);
	}

	private EntitlementResolver resolverWith(Duration cacheTtl, Duration renewalLag) {
		Clock clock = new Clock() {
			@Override
			public java.time.ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return clockNow.get();
			}
		};
		return new EntitlementResolver(data,
				new EntitlementProperties("PROVISIONAL_V2", "GUEST", GRACE, cacheTtl, 100, renewalLag), clock);
	}

	private SourceRow sub(PlanSource source, String plan, String status, Instant start, Instant end) {
		return new SourceRow(source, UUID.randomUUID(), source == PlanSource.ORGANIZATION_SEAT ? UUID.randomUUID() : null,
				status, start, end, null, null, data.plan(plan));
	}

	private SourceRow active(PlanSource source, String plan) {
		return sub(source, plan, "ACTIVE", NOW.minus(Duration.ofDays(5)), NOW.plus(Duration.ofDays(25)));
	}

	private EffectivePlan plan() {
		return resolver.resolve(user).plan().orElseThrow();
	}

	// ------------------------------------------------------------------ plan rank and sources

	@Test
	void registeredUserWithoutSourcesGetsFree() {
		EffectivePlan p = plan();
		assertThat(p.planCode()).isEqualTo("FREE");
		assertThat(p.source()).isEqualTo(PlanSource.DEFAULT);
		assertThat(p.effectiveUntil()).isNull();
		assertThat(p.subscriptionId()).isNull();
	}

	@Test
	void guestWithoutSourcesGetsGuestPlan() {
		user = data.user("GUEST", "ACTIVE");
		EffectivePlan p = plan();
		assertThat(p.planCode()).isEqualTo("GUEST");
		assertThat(p.source()).isEqualTo(PlanSource.DEFAULT);
		assertThat(p.planRank()).isEqualTo(-10);
	}

	@Test
	void guestPlanCodeIsConfigurable() {
		user = data.user("GUEST", "ACTIVE");
		var custom = new EntitlementResolver(data,
				new EntitlementProperties("PROVISIONAL_V2", "FREE", GRACE, Duration.ZERO, 10, LAG), Clock.fixed(NOW, ZoneOffset.UTC));
		assertThat(custom.resolve(user).plan().orElseThrow().planCode()).isEqualTo("FREE");
	}

	@Test
	void missingDefaultPlanFailsLoudly() {
		data.plans.remove("GUEST");
		user = data.user("GUEST", "ACTIVE");
		assertThatThrownBy(() -> resolver.resolve(user)).isInstanceOf(IllegalStateException.class);
	}

	@ParameterizedTest
	@CsvSource({ "SUBSCRIPTION,SUBSCRIPTION", "STORE_PURCHASE,STORE_PURCHASE", "PARTNER,PARTNER",
			"ORGANIZATION_SEAT,ORGANIZATION_SEAT", "DEFAULT,DEFAULT" })
	void contractSourceMapping(PlanSource source, String contract) {
		assertThat(source.contractValue()).isEqualTo(contract);
	}

	@ParameterizedTest
	@ValueSource(strings = { "SUBSCRIPTION", "STORE_PURCHASE", "PARTNER" })
	void everySubscriptionBackedSourceGrantsItsPlan(PlanSource source) {
		SourceRow row = active(source, "PREMIUM");
		data.add(user, row);
		EffectivePlan p = plan();
		assertThat(p.planCode()).isEqualTo("PREMIUM");
		assertThat(p.source()).isEqualTo(source);
		assertThat(p.subscriptionId()).isEqualTo(row.subscriptionId());
		assertThat(p.effectiveUntil()).isEqualTo(row.end());
		assertThat(p.inGracePeriod()).isFalse();
	}

	@Test
	void organizationSeatGrantsBusinessAndReportsTheOrganization() {
		SourceRow seat = active(PlanSource.ORGANIZATION_SEAT, "BUSINESS");
		data.add(user, seat);
		EffectivePlan p = plan();
		assertThat(p.planCode()).isEqualTo("BUSINESS");
		assertThat(p.source()).isEqualTo(PlanSource.ORGANIZATION_SEAT);
		assertThat(p.organizationId()).isEqualTo(seat.organizationId());
	}

	@Test
	void highestRankWinsAcrossAllSourcesAndAllowancesAreNotSummed() {
		data.add(user, active(PlanSource.SUBSCRIPTION, "PREMIUM"));
		data.add(user, active(PlanSource.STORE_PURCHASE, "PREMIUM_PLUS"));
		data.add(user, active(PlanSource.PARTNER, "PREMIUM"));
		data.add(user, active(PlanSource.ORGANIZATION_SEAT, "BUSINESS"));
		assertThat(plan().planCode()).isEqualTo("BUSINESS");
		// BUSINESS allowance (3000), not 600 + 2000 + 600 + 3000
		assertThat(resolver.resolve(user).feature("TRANSLATE").orElseThrow().includedUnits()).isEqualTo(3000L);
	}

	@Test
	void higherRankInAnyOrderOfInsertionWins() {
		data.add(user, active(PlanSource.PARTNER, "PREMIUM_PLUS"));
		data.add(user, active(PlanSource.SUBSCRIPTION, "PREMIUM"));
		assertThat(plan().planCode()).isEqualTo("PREMIUM_PLUS");
		assertThat(plan().source()).isEqualTo(PlanSource.PARTNER);
	}

	@Test
	void expiredHigherPlanFallsBackToTheLowerValidOne() {
		data.add(user, sub(PlanSource.SUBSCRIPTION, "PREMIUM_PLUS", "ACTIVE", NOW.minus(Duration.ofDays(40)),
				NOW.minus(Duration.ofDays(10))));
		data.add(user, active(PlanSource.STORE_PURCHASE, "PREMIUM"));
		assertThat(plan().planCode()).isEqualTo("PREMIUM");
	}

	@Test
	void sourceBelowTheDefaultNeverLowersAnAccount() {
		// a (hypothetical) rank -10 grant must not beat FREE for a registered user
		data.add(user, active(PlanSource.SUBSCRIPTION, "GUEST"));
		assertThat(plan().planCode()).isEqualTo("FREE");
	}

	@Test
	void retiredOrDraftPlansKeepWorkingWhenSold() {
		// BUSINESS is DRAFT in the seed; plan_status is not part of the resolution
		data.add(user, active(PlanSource.SUBSCRIPTION, "BUSINESS"));
		assertThat(plan().planCode()).isEqualTo("BUSINESS");
	}

	// ------------------------------------------------------------------ ties

	@Test
	void tieActiveBeatsGrace() {
		SourceRow grace = new SourceRow(PlanSource.SUBSCRIPTION, UUID.randomUUID(), null, "GRACE_PERIOD",
				NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(2)), null,
				data.plan("PREMIUM"));
		SourceRow active = active(PlanSource.PARTNER, "PREMIUM");
		data.add(user, grace);
		data.add(user, active);
		assertThat(plan().subscriptionId()).isEqualTo(active.subscriptionId());
	}

	@Test
	void tieLaterEndWinsAndOpenEndedWinsOverBoth() {
		SourceRow shorter = sub(PlanSource.SUBSCRIPTION, "PREMIUM", "ACTIVE", NOW.minus(Duration.ofDays(1)),
				NOW.plus(Duration.ofDays(5)));
		SourceRow longer = sub(PlanSource.STORE_PURCHASE, "PREMIUM", "ACTIVE", NOW.minus(Duration.ofDays(1)),
				NOW.plus(Duration.ofDays(20)));
		data.add(user, shorter);
		data.add(user, longer);
		assertThat(plan().subscriptionId()).isEqualTo(longer.subscriptionId());

		SourceRow openEnded = sub(PlanSource.PARTNER, "PREMIUM", "ACTIVE", null, null);
		data.add(user, openEnded);
		EffectivePlan p = plan();
		assertThat(p.subscriptionId()).isEqualTo(openEnded.subscriptionId());
		assertThat(p.effectiveUntil()).isNull();
	}

	@Test
	void tieOnEverythingFollowsSourceOrderThenIdDeterministically() {
		Instant end = NOW.plus(Duration.ofDays(10));
		SourceRow partner = sub(PlanSource.PARTNER, "PREMIUM", "ACTIVE", null, end);
		SourceRow store = sub(PlanSource.STORE_PURCHASE, "PREMIUM", "ACTIVE", null, end);
		SourceRow org = sub(PlanSource.ORGANIZATION_SEAT, "PREMIUM", "ACTIVE", null, end);
		SourceRow subscription = sub(PlanSource.SUBSCRIPTION, "PREMIUM", "ACTIVE", null, end);
		for (SourceRow r : List.of(partner, org, store, subscription)) {
			data.add(user, r);
		}
		assertThat(plan().source()).isEqualTo(PlanSource.SUBSCRIPTION);

		data.sources.get(user).remove(subscription);
		assertThat(plan().source()).isEqualTo(PlanSource.STORE_PURCHASE);
		data.sources.get(user).remove(store);
		assertThat(plan().source()).isEqualTo(PlanSource.ORGANIZATION_SEAT);
		data.sources.get(user).remove(org);
		assertThat(plan().source()).isEqualTo(PlanSource.PARTNER);

		// same source, same end: the smaller id wins whatever the row order
		SourceRow a = new SourceRow(PlanSource.SUBSCRIPTION, new UUID(0, 1), null, "ACTIVE", null, end, null, null,
				data.plan("PREMIUM"));
		SourceRow b = new SourceRow(PlanSource.SUBSCRIPTION, new UUID(0, 2), null, "ACTIVE", null, end, null, null,
				data.plan("PREMIUM"));
		UUID other = data.user("REGISTERED", "ACTIVE");
		data.add(other, b);
		data.add(other, a);
		assertThat(resolver.resolve(other).plan().orElseThrow().subscriptionId()).isEqualTo(a.subscriptionId());
	}

	// ------------------------------------------------------------------ validity edges

	@Test
	void periodEndIsExclusiveAndStartIsInclusive() {
		Instant start = NOW.plus(Duration.ofHours(1));
		Instant end = NOW.plus(Duration.ofHours(2));
		data.add(user, sub(PlanSource.SUBSCRIPTION, "PREMIUM", "ACTIVE", start, end));
		assertThat(resolver.resolve(user, start.minusNanos(1)).plan().orElseThrow().planCode()).isEqualTo("FREE");
		assertThat(resolver.resolve(user, start).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");
		assertThat(resolver.resolve(user, end.minusNanos(1)).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");
		// D22: the renewal lag keeps an ACTIVE source valid past its end; the lag end is exclusive too
		assertThat(resolver.resolve(user, end.plus(LAG).minusNanos(1)).plan().orElseThrow().planCode())
				.isEqualTo("PREMIUM");
		assertThat(resolver.resolve(user, end.plus(LAG)).plan().orElseThrow().planCode()).isEqualTo("FREE");
		EntitlementResolver noLag = resolverWith(Duration.ZERO, Duration.ZERO);
		assertThat(noLag.resolve(user, end.minusNanos(1)).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");
		assertThat(noLag.resolve(user, end).plan().orElseThrow().planCode()).isEqualTo("FREE");
	}

	// ------------------------------------------------------------------ renewal lag (ADR 0007 D22)

	@Test
	void activeSourceJustPastItsEndKeepsAccessDuringTheRenewalLag() {
		Instant end = NOW.minus(Duration.ofHours(23));
		data.add(user, sub(PlanSource.STORE_PURCHASE, "PREMIUM", "ACTIVE", end.minus(Duration.ofDays(30)), end));
		EffectivePlan p = plan();
		assertThat(p.planCode()).isEqualTo("PREMIUM");
		assertThat(p.inGracePeriod()).isFalse();
		assertThat(p.effectiveUntil()).as("still the period end: a renewal is pending").isEqualTo(end);
		assertThat(resolver.resolve(user).feature("TONE_ADJUST").orElseThrow().accessEnabled()).isTrue();
		// 1 h later the lag is over
		assertThat(resolver.resolve(user, NOW.plus(Duration.ofHours(1))).plan().orElseThrow().planCode())
				.isEqualTo("FREE");
	}

	@Test
	void renewalLagAppliesToOrganisationSubscriptionsAndNotToGraceOrOtherStatuses() {
		Instant end = NOW.minus(Duration.ofHours(1));
		data.add(user, sub(PlanSource.ORGANIZATION_SEAT, "BUSINESS", "ACTIVE", end.minus(Duration.ofDays(30)), end));
		assertThat(plan().planCode()).isEqualTo("BUSINESS");

		UUID graceUser = data.user("REGISTERED", "ACTIVE");
		Instant graceEnd = NOW.minus(Duration.ofHours(1));
		data.add(graceUser, new SourceRow(PlanSource.SUBSCRIPTION, UUID.randomUUID(), null, "GRACE_PERIOD",
				graceEnd.minus(Duration.ofDays(33)), graceEnd.minus(Duration.ofDays(3)), graceEnd, null,
				data.plan("PREMIUM")));
		assertThat(resolver.resolve(graceUser).plan().orElseThrow().planCode()).as("grace end is not lagged")
				.isEqualTo("FREE");

		UUID pastDue = data.user("REGISTERED", "ACTIVE");
		data.add(pastDue, sub(PlanSource.SUBSCRIPTION, "PREMIUM", "PAST_DUE", end.minus(Duration.ofDays(30)), end));
		assertThat(resolver.resolve(pastDue).plan().orElseThrow().planCode()).isEqualTo("FREE");
	}

	@Test
	void aRenewedPeriodWinsOverTheLaggingOneAndLagIsConfigurable() {
		Instant boundary = NOW.minus(Duration.ofHours(2));
		data.add(user, sub(PlanSource.SUBSCRIPTION, "PREMIUM", "ACTIVE", boundary.minus(Duration.ofDays(30)), boundary));
		SourceRow renewed = sub(PlanSource.SUBSCRIPTION, "PREMIUM", "ACTIVE", boundary, boundary.plus(Duration.ofDays(30)));
		data.add(user, renewed);
		assertThat(plan().subscriptionId()).isEqualTo(renewed.subscriptionId());

		UUID other = data.user("REGISTERED", "ACTIVE");
		data.add(other, sub(PlanSource.SUBSCRIPTION, "PREMIUM", "ACTIVE", boundary.minus(Duration.ofDays(30)), boundary));
		assertThat(resolverWith(Duration.ZERO, Duration.ofHours(1)).resolve(other).plan().orElseThrow().planCode())
				.isEqualTo("FREE");
		assertThat(resolverWith(Duration.ZERO, Duration.ofHours(3)).resolve(other).plan().orElseThrow().planCode())
				.isEqualTo("PREMIUM");
	}

	@Test
	void cacheOfALaggingSourceEndsWithTheLag() {
		EntitlementResolver cached = resolverWith(Duration.ofMinutes(10), Duration.ofMinutes(5));
		Instant end = NOW.minus(Duration.ofMinutes(2));
		data.add(user, sub(PlanSource.SUBSCRIPTION, "PREMIUM", "ACTIVE", end.minus(Duration.ofDays(30)), end));
		assertThat(cached.resolve(user).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");
		clockNow.set(NOW.plus(Duration.ofMinutes(2))); // inside the TTL, still inside the lag (ends at NOW + 3 min)
		assertThat(cached.resolve(user).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");
		clockNow.set(NOW.plus(Duration.ofMinutes(3))); // inside the TTL, lag over
		assertThat(cached.resolve(user).plan().orElseThrow().planCode()).isEqualTo("FREE");
	}

	@Test
	void extensionStartsWhereTheCurrentPeriodEnds() {
		Instant boundary = NOW.plus(Duration.ofDays(1));
		data.add(user, sub(PlanSource.SUBSCRIPTION, "PREMIUM", "ACTIVE", NOW.minus(Duration.ofDays(29)), boundary));
		SourceRow next = sub(PlanSource.SUBSCRIPTION, "PREMIUM", "ACTIVE", boundary, boundary.plus(Duration.ofDays(30)));
		data.add(user, next);
		assertThat(resolver.resolve(user, boundary.minusSeconds(1)).plan().orElseThrow().effectiveUntil())
				.isEqualTo(boundary);
		EffectivePlan after = resolver.resolve(user, boundary).plan().orElseThrow();
		assertThat(after.planCode()).isEqualTo("PREMIUM");
		assertThat(after.subscriptionId()).isEqualTo(next.subscriptionId());
	}

	@Test
	void openEndedActiveSourceNeverExpires() {
		data.add(user, sub(PlanSource.PARTNER, "PREMIUM_PLUS", "ACTIVE", null, null));
		assertThat(resolver.resolve(user, NOW.plus(Duration.ofDays(3650))).plan().orElseThrow().planCode())
				.isEqualTo("PREMIUM_PLUS");
	}

	@ParameterizedTest
	@ValueSource(strings = { "PENDING", "PAST_DUE", "PAUSED", "CANCELLED", "EXPIRED", "REVOKED", "UPGRADED" })
	void statusesOtherThanActiveAndGraceGrantNothing(String status) {
		data.add(user, sub(PlanSource.SUBSCRIPTION, "PREMIUM", status, NOW.minus(Duration.ofDays(1)),
				NOW.plus(Duration.ofDays(20))));
		assertThat(plan().planCode()).isEqualTo("FREE");
	}

	// ------------------------------------------------------------------ grace

	@Test
	void graceUsesTheExplicitGraceEnd() {
		Instant periodEnd = NOW.minus(Duration.ofDays(1));
		Instant graceEnd = NOW.plus(Duration.ofHours(6));
		data.add(user, new SourceRow(PlanSource.SUBSCRIPTION, UUID.randomUUID(), null, "GRACE_PERIOD",
				periodEnd.minus(Duration.ofDays(30)), periodEnd, graceEnd, null, data.plan("PREMIUM")));
		EffectivePlan p = plan();
		assertThat(p.planCode()).isEqualTo("PREMIUM");
		assertThat(p.inGracePeriod()).isTrue();
		assertThat(p.effectiveUntil()).isEqualTo(graceEnd);
		assertThat(resolver.resolve(user, graceEnd.minusNanos(1)).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");
		assertThat(resolver.resolve(user, graceEnd).plan().orElseThrow().planCode()).isEqualTo("FREE");
	}

	@Test
	void graceWithoutExplicitEndIsThePeriodEndPlusTheDefaultGrace() {
		Instant periodEnd = NOW.minus(Duration.ofDays(2));
		data.add(user, sub(PlanSource.STORE_PURCHASE, "PREMIUM", "GRACE_PERIOD", periodEnd.minus(Duration.ofDays(30)),
				periodEnd));
		assertThat(plan().effectiveUntil()).isEqualTo(periodEnd.plus(GRACE));
		assertThat(resolver.resolve(user, periodEnd.plus(GRACE).minusNanos(1)).plan().orElseThrow().planCode())
				.isEqualTo("PREMIUM");
		assertThat(resolver.resolve(user, periodEnd.plus(GRACE)).plan().orElseThrow().planCode()).isEqualTo("FREE");
	}

	@Test
	void organizationSubscriptionGraceUsesTheDefaultGrace() {
		Instant periodEnd = NOW.minus(Duration.ofDays(1));
		data.add(user, sub(PlanSource.ORGANIZATION_SEAT, "BUSINESS", "GRACE_PERIOD", periodEnd.minus(Duration.ofDays(30)),
				periodEnd));
		assertThat(plan().planCode()).isEqualTo("BUSINESS");
		assertThat(resolver.resolve(user, periodEnd.plus(GRACE)).plan().orElseThrow().planCode()).isEqualTo("FREE");
	}

	@Test
	void graceOfALowerPlanLosesToAnActiveHigherPlanButBeatsNothingBetter() {
		data.add(user, sub(PlanSource.SUBSCRIPTION, "PREMIUM", "GRACE_PERIOD", NOW.minus(Duration.ofDays(31)),
				NOW.minus(Duration.ofDays(1))));
		assertThat(plan().planCode()).isEqualTo("PREMIUM");
		data.add(user, active(PlanSource.PARTNER, "PREMIUM_PLUS"));
		assertThat(plan().planCode()).isEqualTo("PREMIUM_PLUS");
	}

	@Test
	void refundedOrRevokedStorePurchaseGrantsNothingEvenIfTheSubscriptionRowIsStale() {
		for (String purchase : List.of("REFUNDED", "REVOKED")) {
			UUID u = data.user("REGISTERED", "ACTIVE");
			data.add(u, new SourceRow(PlanSource.STORE_PURCHASE, UUID.randomUUID(), null, "ACTIVE",
					NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(20)), null, purchase, data.plan("PREMIUM")));
			assertThat(resolver.resolve(u).plan().orElseThrow().planCode()).as(purchase).isEqualTo("FREE");
		}
		for (String purchase : List.of("ACTIVE", "IN_GRACE_PERIOD", "CANCELLED")) {
			UUID u = data.user("REGISTERED", "ACTIVE");
			data.add(u, new SourceRow(PlanSource.STORE_PURCHASE, UUID.randomUUID(), null, "ACTIVE",
					NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(20)), null, purchase, data.plan("PREMIUM")));
			assertThat(resolver.resolve(u).plan().orElseThrow().planCode()).as(purchase).isEqualTo("PREMIUM");
		}
	}

	// ------------------------------------------------------------------ account state

	@ParameterizedTest
	@CsvSource({ "SUSPENDED,SUSPENDED", "DELETION_PENDING,DELETED", "DELETED,DELETED", "MERGED,MERGED",
			"PENDING,PENDING" })
	void accountsThatCannotHoldEntitlementsGetNothing(String status, AccessState expected) {
		UUID u = data.user("REGISTERED", status);
		data.add(u, active(PlanSource.SUBSCRIPTION, "PREMIUM_PLUS"));
		data.overrides.put(u, List.of());
		ResolvedEntitlements r = resolver.resolve(u);
		assertThat(r.access()).isEqualTo(expected);
		assertThat(r.plan()).isEmpty();
		assertGatedFeaturesDenied(r);
	}

	@Test
	void deletedAtDeniesEvenWhenStatusLooksActive() {
		UUID u = UUID.randomUUID();
		data.accounts.put(u, new Account(u, "REGISTERED", "ACTIVE", NOW.minusSeconds(5)));
		ResolvedEntitlements r = resolver.resolve(u);
		assertThat(r.access()).isEqualTo(AccessState.DELETED);
		assertGatedFeaturesDenied(r);
	}

	@Test
	void unknownUserGetsNothing() {
		ResolvedEntitlements r = resolver.resolve(UUID.randomUUID());
		assertThat(r.access()).isEqualTo(AccessState.UNKNOWN);
		assertThat(r.plan()).isEmpty();
		assertGatedFeaturesDenied(r);
	}

	@Test
	void suspendedGuestAndOverridesGetNothingToo() {
		UUID u = data.user("GUEST", "SUSPENDED");
		data.overrides.put(u, List.of(new FakeEntitlementData.TimedOverride(new FeatureOverride("TRANSLATE", true), null)));
		ResolvedEntitlements r = resolver.resolve(u);
		assertThat(r.isAllowed("TRANSLATE")).isFalse();
		assertThat(r.isAllowed("OFFLINE_TYPING")).isTrue();
	}

	private void assertGatedFeaturesDenied(ResolvedEntitlements r) {
		assertThat(r.features()).hasSize(25);
		for (FeatureEntitlement f : r.features().values()) {
			if (f.gated()) {
				assertThat(f.accessEnabled()).as(f.featureCode()).isFalse();
				assertThat(f.creditsUsable()).isFalse();
				assertThat(f.origin()).isEqualTo(FeatureEntitlement.Origin.ACCOUNT_DENIED);
				assertThat(f.includedUnits()).isEqualTo(f.unit() == MeteringUnit.NONE ? null : 0L);
			} else {
				assertThat(f.accessEnabled()).as("never gated: " + f.featureCode()).isTrue();
			}
		}
	}

	@Test
	void typingIsAlwaysAllowedAndUnknownCodesAreDenied() {
		for (String typing : List.of("OFFLINE_TYPING", "DIRECT_SCRIPT_LAYOUT", "PHONETIC_TRANSLITERATION",
				"OFFLINE_SUGGESTIONS", "DETECT_LANGUAGE")) {
			assertThat(resolver.resolve(user).isAllowed(typing)).as(typing).isTrue();
		}
		assertThat(resolver.resolve(user).isAllowed("NOT_A_FEATURE")).isFalse();
		assertThat(resolver.resolve(user).feature("NOT_A_FEATURE")).isEmpty();
	}

	// ------------------------------------------------------------------ quota policy version

	@Test
	void anotherQuotaPolicyVersionWithoutRowsFailsClosed() {
		data.add(user, active(PlanSource.SUBSCRIPTION, "PREMIUM"));
		var v3 = new EntitlementResolver(data, new EntitlementProperties("V3", "GUEST", GRACE, Duration.ZERO, 10, LAG),
				Clock.fixed(NOW, ZoneOffset.UTC));
		ResolvedEntitlements r = v3.resolve(user);
		assertThat(r.quotaPolicyVersion()).isEqualTo("V3");
		assertThat(r.plan().orElseThrow().planCode()).isEqualTo("PREMIUM");
		assertThat(r.isAllowed("TRANSLATE")).isFalse();
		assertThat(r.feature("TRANSLATE").orElseThrow().origin()).isEqualTo(FeatureEntitlement.Origin.NO_PLAN_ROW);
		assertThat(r.isAllowed("OFFLINE_TYPING")).isTrue();
	}

	// ------------------------------------------------------------------ overrides

	private void override(UUID u, String feature, boolean enabled, Instant expiresAt) {
		data.overrides.computeIfAbsent(u, k -> new java.util.ArrayList<>())
				.add(new FakeEntitlementData.TimedOverride(new FeatureOverride(feature, enabled), expiresAt));
	}

	@Test
	void overrideDisableBeatsThePlan() {
		data.add(user, active(PlanSource.SUBSCRIPTION, "PREMIUM"));
		override(user, "TRANSLATE", false, null);
		FeatureEntitlement f = resolver.resolve(user).feature("TRANSLATE").orElseThrow();
		assertThat(f.accessEnabled()).isFalse();
		assertThat(f.includedUnits()).isEqualTo(0L);
		assertThat(f.resetPeriod()).isEqualTo(ResetPeriod.NONE);
		assertThat(f.creditsUsable()).isFalse();
		assertThat(f.origin()).isEqualTo(FeatureEntitlement.Origin.OVERRIDE);
	}

	@Test
	void overrideEnableOpensAFeatureThePlanExcludesForCreditsOnly() {
		// FREE has no TONE_ADJUST: enabling gives access with a zero allowance, payable with credits
		override(user, "TONE_ADJUST", true, null);
		FeatureEntitlement f = resolver.resolve(user).feature("TONE_ADJUST").orElseThrow();
		assertThat(f.accessEnabled()).isTrue();
		assertThat(f.includedUnits()).isEqualTo(0L);
		assertThat(f.creditsUsable()).isTrue();
		assertThat(f.origin()).isEqualTo(FeatureEntitlement.Origin.OVERRIDE);
	}

	@Test
	void overrideEnableOnABooleanFeatureTurnsItOn() {
		override(user, "CUSTOM_GLOSSARY", true, null); // FREE: disabled
		FeatureEntitlement f = resolver.resolve(user).feature("CUSTOM_GLOSSARY").orElseThrow();
		assertThat(f.accessEnabled()).isTrue();
		assertThat(f.unit()).isEqualTo(MeteringUnit.NONE);
		assertThat(f.includedUnits()).isNull();
		assertThat(f.creditsUsable()).isFalse();
	}

	@Test
	void overrideThatAgreesWithThePlanChangesNothing() {
		data.add(user, active(PlanSource.SUBSCRIPTION, "PREMIUM"));
		override(user, "TRANSLATE", true, null);
		FeatureEntitlement f = resolver.resolve(user).feature("TRANSLATE").orElseThrow();
		assertThat(f.origin()).isEqualTo(FeatureEntitlement.Origin.PLAN);
		assertThat(f.includedUnits()).isEqualTo(600L);
		assertThat(f.resetPeriod()).isEqualTo(ResetPeriod.MONTHLY);
	}

	@Test
	void overrideExpiryIsExclusive() {
		Instant expires = NOW.plus(Duration.ofHours(1));
		override(user, "TONE_ADJUST", true, expires);
		assertThat(resolver.resolve(user, expires.minusNanos(1)).isAllowed("TONE_ADJUST")).isTrue();
		assertThat(resolver.resolve(user, expires).isAllowed("TONE_ADJUST")).isFalse();
	}

	@Test
	void conflictingOverridesOnOneFeatureDisableWins() {
		override(user, "TONE_ADJUST", true, null);
		override(user, "TONE_ADJUST", false, null);
		assertThat(resolver.resolve(user).isAllowed("TONE_ADJUST")).isFalse();
	}

	@Test
	void overridesNeverGateTypingAndIgnoreUnknownFeatures() {
		override(user, "OFFLINE_TYPING", false, null);
		override(user, "NOT_A_FEATURE", true, null);
		ResolvedEntitlements r = resolver.resolve(user);
		assertThat(r.isAllowed("OFFLINE_TYPING")).isTrue();
		assertThat(r.feature("OFFLINE_TYPING").orElseThrow().origin()).isEqualTo(FeatureEntitlement.Origin.UNGATED);
		assertThat(r.features()).doesNotContainKey("NOT_A_FEATURE");
	}

	@Test
	void overridesApplyToGuestsToo() {
		UUID guest = data.user("GUEST", "ACTIVE");
		override(guest, "SUMMARIZE", true, null);
		assertThat(resolver.resolve(guest).isAllowed("SUMMARIZE")).isTrue();
		assertThat(resolver.resolve(guest).isAllowed("TONE_ADJUST")).isFalse();
	}

	// ------------------------------------------------------------------ cache

	@Test
	void cacheServesRepeatedResolvesAndInvalidateRefreshes() {
		EntitlementResolver cached = resolverWith(Duration.ofSeconds(30));
		assertThat(cached.resolve(user).plan().orElseThrow().planCode()).isEqualTo("FREE");
		int queries = data.sourceQueries;

		data.add(user, active(PlanSource.SUBSCRIPTION, "PREMIUM"));
		assertThat(cached.resolve(user).plan().orElseThrow().planCode()).as("still cached").isEqualTo("FREE");
		assertThat(data.sourceQueries).isEqualTo(queries);

		cached.invalidate(user);
		assertThat(cached.resolve(user).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");
	}

	@Test
	void cacheNeverOutlivesTheWinningSourceAndExplicitInstantBypassesIt() {
		EntitlementResolver cached = resolverWith(Duration.ofMinutes(10), Duration.ZERO);
		data.add(user, sub(PlanSource.SUBSCRIPTION, "PREMIUM", "ACTIVE", NOW.minus(Duration.ofDays(5)),
				NOW.plus(Duration.ofMinutes(1))));
		assertThat(cached.resolve(user).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");

		clockNow.set(NOW.plus(Duration.ofMinutes(2))); // past the period end, inside the cache TTL
		assertThat(cached.resolve(user).plan().orElseThrow().planCode()).isEqualTo("FREE");

		int queries = data.sourceQueries;
		cached.resolve(user, NOW);
		cached.resolve(user, NOW);
		assertThat(data.sourceQueries).as("explicit instants always compute").isEqualTo(queries + 2);
	}

	@Test
	void invalidateAllAndDisabledCache() {
		EntitlementResolver cached = resolverWith(Duration.ofSeconds(30));
		cached.resolve(user);
		data.add(user, active(PlanSource.SUBSCRIPTION, "PREMIUM"));
		cached.invalidateAll();
		assertThat(cached.resolve(user).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");

		int queries = data.sourceQueries; // ttl 0: no cache at all
		resolver.resolve(user);
		resolver.resolve(user);
		assertThat(data.sourceQueries).isEqualTo(queries + 2);
		resolver.invalidate(user); // harmless without a cache
		resolver.invalidateAll();
	}

	@Test
	void propertiesRejectNonsense() {
		assertThatThrownBy(() -> new EntitlementProperties(" ", "GUEST", GRACE, Duration.ZERO, 1, LAG))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntitlementProperties("V", "", GRACE, Duration.ZERO, 1, LAG))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntitlementProperties("V", "GUEST", Duration.ofDays(-1), Duration.ZERO, 1, LAG))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntitlementProperties("V", "GUEST", GRACE, Duration.ZERO, 0, LAG))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new EntitlementProperties("V", "GUEST", GRACE, Duration.ZERO, 1, Duration.ofHours(-1)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
