package com.qalsmartkeyboard.entitlements;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.qalsmartkeyboard.entitlements.EntitlementData.Account;
import com.qalsmartkeyboard.entitlements.EntitlementData.CatalogFeature;
import com.qalsmartkeyboard.entitlements.EntitlementData.FeatureOverride;
import com.qalsmartkeyboard.entitlements.EntitlementData.PlanFeature;
import com.qalsmartkeyboard.entitlements.EntitlementData.PlanRef;
import com.qalsmartkeyboard.entitlements.EntitlementData.SourceRow;

/**
 * In-memory {@link EntitlementData} loaded with the V1 seed (see {@link SeedSql}). Like the SQL adapter it only returns
 * ACTIVE/GRACE_PERIOD source rows and overrides that have not expired.
 */
class FakeEntitlementData implements EntitlementData {

	final SeedSql seed = SeedSql.load();
	final Map<String, PlanRef> plans = new HashMap<>();
	final Map<UUID, Account> accounts = new HashMap<>();
	final Map<UUID, List<SourceRow>> sources = new HashMap<>();
	final Map<UUID, List<TimedOverride>> overrides = new HashMap<>();
	int sourceQueries;

	record TimedOverride(FeatureOverride override, Instant expiresAt) {
	}

	FakeEntitlementData() {
		for (SeedSql.Plan p : seed.plans) {
			plans.put(p.code(), new PlanRef(UUID.nameUUIDFromBytes(p.code().getBytes()), p.code(), p.displayName(),
					p.rank()));
		}
	}

	PlanRef plan(String code) {
		return plans.get(code);
	}

	UUID user(String accountType, String status) {
		UUID id = UUID.randomUUID();
		accounts.put(id, new Account(id, accountType, status, null));
		return id;
	}

	void add(UUID userId, SourceRow row) {
		sources.computeIfAbsent(userId, k -> new ArrayList<>()).add(row);
	}

	@Override
	public Optional<Account> account(UUID userId) {
		return Optional.ofNullable(accounts.get(userId));
	}

	@Override
	public List<SourceRow> candidateSources(UUID userId) {
		sourceQueries++;
		return sources.getOrDefault(userId, List.of()).stream()
				.filter(r -> r.status().equals("ACTIVE") || r.status().equals("GRACE_PERIOD")).toList();
	}

	@Override
	public Optional<PlanRef> defaultPlan() {
		return seed.plans.stream().filter(SeedSql.Plan::isDefault).findFirst().map(p -> plans.get(p.code()));
	}

	@Override
	public Optional<PlanRef> planByCode(String planCode) {
		return Optional.ofNullable(plans.get(planCode));
	}

	@Override
	public List<CatalogFeature> catalog() {
		return seed.catalog.stream()
				.map(f -> new CatalogFeature(f.code(), f.unit(), f.gated(), f.creditCost())).toList();
	}

	@Override
	public List<PlanFeature> planFeatures(UUID planId, String quotaPolicyVersion) {
		if (!"PROVISIONAL_V2".equals(quotaPolicyVersion)) {
			return List.of(); // the seed only carries PROVISIONAL_V2 rows
		}
		String planCode = plans.values().stream().filter(p -> p.id().equals(planId)).findFirst().orElseThrow().code();
		List<PlanFeature> rows = new ArrayList<>();
		for (SeedSql.Feature f : seed.catalog) {
			if (!f.gated()) {
				continue; // the FK in V1 forbids plan rows for ungated features
			}
			SeedSql.Allowance a = seed.enabledRow(planCode, f.code());
			rows.add(a != null ? new PlanFeature(f.code(), true, a.includedUnits(), a.period())
					: new PlanFeature(f.code(), false, f.unit() == MeteringUnit.NONE ? null : 0L, ResetPeriod.NONE));
		}
		return rows;
	}

	@Override
	public List<FeatureOverride> overrides(UUID userId, Instant at) {
		return overrides.getOrDefault(userId, List.of()).stream()
				.filter(o -> o.expiresAt() == null || o.expiresAt().isAfter(at)).map(TimedOverride::override).toList();
	}
}
