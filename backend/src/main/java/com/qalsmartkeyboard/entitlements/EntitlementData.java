package com.qalsmartkeyboard.entitlements;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read port over the tables the resolver needs. The JDBC adapter is the only implementation; tests use a fake. */
interface EntitlementData {

	record Account(UUID id, String accountType, String accountStatus, Instant deletedAt) {
	}

	record PlanRef(UUID id, String code, String displayName, int rank) {
	}

	/**
	 * A candidate source row, already narrowed to statuses that can grant (ACTIVE, GRACE_PERIOD). Time rules are
	 * applied by the resolver.
	 *
	 * @param purchaseStatus {@code store_purchases.purchase_status} of a STORE subscription, else null
	 * @param graceEnd       {@code user_subscriptions.grace_period_end}; null for organisation subscriptions
	 */
	record SourceRow(PlanSource source, UUID subscriptionId, UUID organizationId, String status, Instant start,
			Instant end, Instant graceEnd, String purchaseStatus, PlanRef plan) {
	}

	record CatalogFeature(String code, MeteringUnit unit, boolean gated, BigDecimal creditCostPerUnit) {
	}

	record PlanFeature(String code, boolean accessEnabled, Long includedUnits, ResetPeriod resetPeriod) {
	}

	record FeatureOverride(String featureCode, boolean enabled) {
	}

	Optional<Account> account(UUID userId);

	List<SourceRow> candidateSources(UUID userId);

	Optional<PlanRef> defaultPlan();

	Optional<PlanRef> planByCode(String planCode);

	/** Active catalogue features in a stable order. */
	List<CatalogFeature> catalog();

	/** Rows of the plan whose {@code configuration->>'quotaPolicyVersion'} equals the given version. */
	List<PlanFeature> planFeatures(UUID planId, String quotaPolicyVersion);

	/** Overrides of the user that have not expired at the instant and belong to a flag that names a feature. */
	List<FeatureOverride> overrides(UUID userId, Instant at);
}
