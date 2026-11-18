package com.qalsmartkeyboard.entitlements;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.qalsmartkeyboard.entitlements.EntitlementData.Account;
import com.qalsmartkeyboard.entitlements.EntitlementData.CatalogFeature;
import com.qalsmartkeyboard.entitlements.EntitlementData.FeatureOverride;
import com.qalsmartkeyboard.entitlements.EntitlementData.PlanFeature;
import com.qalsmartkeyboard.entitlements.EntitlementData.PlanRef;
import com.qalsmartkeyboard.entitlements.EntitlementData.SourceRow;

/**
 * Resolves what a user may do at an instant (SD 15.5, plan step B4.1). The other Phase 2D steps build on it: metering
 * (B4.2) reads {@code includedUnits}/{@code resetPeriod}/{@code creditsUsable}, the entitlement token (B4.4) the
 * boolean gates, and {@code /entitlements} (B4.5) maps the result to the contract's {@code Entitlements}.
 *
 * <h2>Rules</h2>
 * <ol>
 * <li><b>Account.</b> Only {@code ACTIVE}, undeleted accounts hold entitlements. SUSPENDED, DELETION_PENDING, DELETED,
 * MERGED, PENDING and unknown users get no plan and every gated feature disabled; ungated features (typing, SD R-2, and
 * DETECT_LANGUAGE) are never gated and stay allowed.</li>
 * <li><b>Sources.</b> A plan can come from {@code user_subscriptions} (payment, promotion, admin grant, store purchase,
 * partner grant: the last two are told apart by {@code source_type}; SD 16.1 and 16.7) and from an active organisation
 * seat. A source is valid at {@code at} when: its start (if any) is not after {@code at}; status ACTIVE and {@code at}
 * is before the period end plus the <b>renewal lag</b> ({@code qal.entitlements.renewal-lag}, default 24 h, ADR 0007
 * D22: a late store or gateway renewal notification must not cut off a paying user; during the lag
 * {@code effectiveUntil} still reports the period end, now in the past, so clients can tell a renewal is pending; no
 * end = open-ended); or status GRACE_PERIOD and {@code at} is before the grace end
 * ({@code grace_period_end}, else period end plus {@code qal.entitlements.default-grace-period}). All other statuses
 * (PENDING, PAST_DUE, PAUSED, CANCELLED, EXPIRED, REVOKED, UPGRADED) grant nothing; a STORE subscription whose purchase
 * is REFUNDED or REVOKED grants nothing either. A seat needs an ACTIVE assignment, an ACTIVE membership, an ACTIVE
 * organisation and an ACTIVE/GRACE_PERIOD organisation subscription (grace = period end plus the default grace).
 * Period ends are exclusive. {@code plans.plan_status} is not consulted: an existing source keeps working when its plan
 * is retired or still DRAFT (BUSINESS).</li>
 * <li><b>Plan rank.</b> The highest {@code plan_rank} among the valid sources and the default plan wins; allowances are
 * never summed. The default plan is FREE ({@code is_default}) for registered users and the guest plan for guests.</li>
 * <li><b>Ties</b> (same rank) are broken deterministically: ACTIVE before GRACE_PERIOD, then the later
 * {@code effectiveUntil} (open-ended last), then the {@link PlanSource} declaration order (SUBSCRIPTION, STORE_PURCHASE,
 * ORGANIZATION_SEAT, PARTNER, DEFAULT), then the smaller source row id.</li>
 * <li><b>Features.</b> Every active {@code feature_catalog} feature appears. Gated ones take the plan's
 * {@code plan_entitlements} row for the configured quota policy version; a missing row means disabled (fail closed).
 * Ungated ones are always allowed.</li>
 * <li><b>Overrides</b> ({@code user_feature_overrides}, joined to {@code feature_flags.feature_code}; flags without a
 * feature code are UI flags and ignored): an unexpired override beats the plan. Disabling turns access off and zeroes a
 * metered allowance. Enabling turns access on and keeps the plan row's allowance, so a feature the plan excludes opens
 * for AI credits only (allowance 0, {@code creditsUsable}). When several overrides name the same feature, disabling
 * wins. Overrides never touch ungated features and never apply to a denied account.</li>
 * </ol>
 *
 * <h2>Cache</h2>
 * {@code resolve(userId)} (clock time) is cached per user for {@code qal.entitlements.cache-ttl} (default 30 s), but
 * never beyond the moment the winning source stops granting ({@code effectiveUntil}, plus the renewal lag for an ACTIVE
 * source). {@code resolve(userId, at)} always computes. Billing and
 * membership code must call {@link #invalidate(UUID)} after changing a user's sources or overrides; without it a change
 * shows up after the TTL. A future-dated source starting, or an override expiring, inside the TTL is likewise seen
 * after at most the TTL.
 */
public class EntitlementResolver {

	private static final Set<String> DEAD_STORE_PURCHASE = Set.of("REFUNDED", "REVOKED");

	private record Cached(ResolvedEntitlements value, Instant validUntil) {
	}

	private record Candidate(SourceRow row, Instant until, boolean grace) {
	}

	private static final Comparator<Candidate> BEST_FIRST = Comparator
			.<Candidate>comparingInt(c -> c.row().plan().rank()).reversed()
			.thenComparing(c -> c.grace() ? 1 : 0)
			.thenComparing(Comparator.comparing((Candidate c) -> c.until() == null ? Instant.MAX : c.until()).reversed())
			.thenComparing(c -> c.row().source())
			.thenComparing(c -> String.valueOf(c.row().subscriptionId() != null ? c.row().subscriptionId()
					: c.row().organizationId()));

	private final EntitlementData data;
	private final EntitlementProperties properties;
	private final Clock clock;
	private final Cache<UUID, Cached> cache;

	EntitlementResolver(EntitlementData data, EntitlementProperties properties, Clock clock) {
		this.data = data;
		this.properties = properties;
		this.clock = clock;
		this.cache = properties.cacheTtl().isZero() ? null
				: Caffeine.newBuilder().maximumSize(properties.cacheMaxUsers()).expireAfterWrite(properties.cacheTtl())
						.build();
	}

	/** Entitlements now (injected clock), served from the per-user cache when fresh. */
	public ResolvedEntitlements resolve(UUID userId) {
		Instant now = clock.instant();
		if (cache == null) {
			return compute(userId, now);
		}
		Cached hit = cache.getIfPresent(userId);
		if (hit != null && now.isBefore(hit.validUntil()) && !now.isBefore(hit.value().at())) {
			return hit.value();
		}
		ResolvedEntitlements fresh = compute(userId, now);
		Instant until = now.plus(properties.cacheTtl());
		Instant planEnd = fresh.plan().map(this::validUntil).orElse(null);
		if (planEnd != null && planEnd.isBefore(until)) {
			until = planEnd;
		}
		cache.put(userId, new Cached(fresh, until));
		return fresh;
	}

	/** When the winning source stops granting: the grace end in grace, else the period end plus the renewal lag. */
	private Instant validUntil(EffectivePlan plan) {
		Instant end = plan.effectiveUntil();
		return end == null || plan.inGracePeriod() ? end : end.plus(properties.renewalLag());
	}

	/** Entitlements at an explicit instant. Never cached. */
	public ResolvedEntitlements resolve(UUID userId, Instant at) {
		return compute(userId, at);
	}

	/** Drops the cached result of one user. Call after a subscription, seat, store purchase or override change. */
	public void invalidate(UUID userId) {
		if (cache != null) {
			cache.invalidate(userId);
		}
	}

	/** Drops every cached result. */
	public void invalidateAll() {
		if (cache != null) {
			cache.invalidateAll();
		}
	}

	private ResolvedEntitlements compute(UUID userId, Instant at) {
		String version = properties.quotaPolicyVersion();
		Optional<Account> account = data.account(userId);
		AccessState access = account.map(EntitlementResolver::accessState).orElse(AccessState.UNKNOWN);
		List<CatalogFeature> catalog = data.catalog();
		if (access != AccessState.GRANTED) {
			return new ResolvedEntitlements(userId, at, access, Optional.empty(), version,
					denied(catalog));
		}

		boolean guest = "GUEST".equals(account.get().accountType());
		PlanRef fallback = (guest ? data.planByCode(properties.guestPlanCode()) : data.defaultPlan())
				.orElseThrow(() -> new IllegalStateException(
						"No " + (guest ? "guest plan '" + properties.guestPlanCode() + "'" : "default plan")
								+ " is configured in subscription_plans"));

		List<Candidate> valid = new ArrayList<>();
		for (SourceRow row : data.candidateSources(userId)) {
			evaluate(row, at).ifPresent(valid::add);
		}
		valid.add(new Candidate(new SourceRow(PlanSource.DEFAULT, null, null, "DEFAULT", null, null, null, null,
				fallback), null, false));
		valid.sort(BEST_FIRST);
		Candidate winner = valid.get(0);

		EffectivePlan plan = new EffectivePlan(winner.row().plan().id(), winner.row().plan().code(),
				winner.row().plan().displayName(), winner.row().plan().rank(), winner.row().source(),
				winner.row().subscriptionId(), winner.row().organizationId(), winner.until(), winner.grace(),
				winner.row().start());

		Map<String, PlanFeature> planRows = new HashMap<>();
		for (PlanFeature f : data.planFeatures(plan.planId(), version)) {
			planRows.put(f.code(), f);
		}
		Map<String, Boolean> overrides = new HashMap<>();
		for (FeatureOverride o : data.overrides(userId, at)) {
			overrides.merge(o.featureCode(), o.enabled(), (a, b) -> a && b); // disabling wins
		}

		Map<String, FeatureEntitlement> features = new LinkedHashMap<>();
		for (CatalogFeature c : catalog) {
			features.put(c.code(), feature(c, planRows.get(c.code()), overrides.get(c.code())));
		}
		return new ResolvedEntitlements(userId, at, AccessState.GRANTED, Optional.of(plan), version, features);
	}

	private static FeatureEntitlement feature(CatalogFeature c, PlanFeature row, Boolean override) {
		if (!c.gated()) {
			return new FeatureEntitlement(c.code(), false, true, c.unit(), null, ResetPeriod.NONE,
					c.creditCostPerUnit(), false, FeatureEntitlement.Origin.UNGATED);
		}
		boolean metered = c.unit() != MeteringUnit.NONE;
		boolean access = row != null && row.accessEnabled();
		Long included = row == null ? (metered ? Long.valueOf(0) : null) : row.includedUnits();
		ResetPeriod period = row == null ? ResetPeriod.NONE : row.resetPeriod();
		FeatureEntitlement.Origin origin = row == null ? FeatureEntitlement.Origin.NO_PLAN_ROW
				: FeatureEntitlement.Origin.PLAN;

		if (override != null && override != access) {
			origin = FeatureEntitlement.Origin.OVERRIDE;
			access = override;
			if (!access) {
				included = metered ? Long.valueOf(0) : null;
				period = ResetPeriod.NONE;
			} else if (metered && row == null) {
				included = 0L; // no plan allowance to keep: credits only
			}
		}
		if (!access && metered) {
			included = 0L;
		}
		boolean credits = access && metered && c.creditCostPerUnit().signum() > 0;
		return new FeatureEntitlement(c.code(), true, access, c.unit(), included, period, c.creditCostPerUnit(),
				credits, origin);
	}

	private static Map<String, FeatureEntitlement> denied(List<CatalogFeature> catalog) {
		Map<String, FeatureEntitlement> features = new LinkedHashMap<>();
		for (CatalogFeature c : catalog) {
			features.put(c.code(), c.gated()
					? new FeatureEntitlement(c.code(), true, false, c.unit(), c.unit() == MeteringUnit.NONE ? null : 0L,
							ResetPeriod.NONE, c.creditCostPerUnit(), false, FeatureEntitlement.Origin.ACCOUNT_DENIED)
					: feature(c, null, null));
		}
		return features;
	}

	private static AccessState accessState(Account a) {
		if (a.deletedAt() != null) {
			return AccessState.DELETED;
		}
		return switch (a.accountStatus()) {
			case "ACTIVE" -> AccessState.GRANTED;
			case "SUSPENDED" -> AccessState.SUSPENDED;
			case "DELETION_PENDING", "DELETED" -> AccessState.DELETED;
			case "MERGED" -> AccessState.MERGED;
			default -> AccessState.PENDING;
		};
	}

	/** Source validity at an instant; empty when the source grants nothing. */
	private Optional<Candidate> evaluate(SourceRow r, Instant at) {
		if (r.purchaseStatus() != null && DEAD_STORE_PURCHASE.contains(r.purchaseStatus())) {
			return Optional.empty();
		}
		if (r.start() != null && at.isBefore(r.start())) {
			return Optional.empty();
		}
		switch (r.status()) {
			case "ACTIVE":
				// D22: still valid for the renewal lag after the period end; effectiveUntil stays the period end
				if (r.end() != null && !at.isBefore(r.end().plus(properties.renewalLag()))) {
					return Optional.empty();
				}
				return Optional.of(new Candidate(r, r.end(), false));
			case "GRACE_PERIOD":
				Instant graceEnd = r.graceEnd();
				if (graceEnd == null && r.end() != null) {
					Duration grace = properties.defaultGracePeriod();
					graceEnd = r.end().plus(grace);
				}
				if (graceEnd != null && !at.isBefore(graceEnd)) {
					return Optional.empty();
				}
				return Optional.of(new Candidate(r, graceEnd, true));
			default:
				return Optional.empty();
		}
	}
}
