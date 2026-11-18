package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.entitlements.EffectivePlan;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.entitlements.EntitlementTokenIssuer;
import com.qalsmartkeyboard.entitlements.FeatureEntitlement;
import com.qalsmartkeyboard.entitlements.MeteringUnit;
import com.qalsmartkeyboard.entitlements.ResolvedEntitlements;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.usage.UsageReadDtos.EntitlementsDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.FeatureDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.PlanDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.TokenDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.UsageItemDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.UsageReportDto;

/**
 * The read side of metering (plan step B4.5, SD 15): {@code GET /me/entitlements} and {@code GET /me/usage}.
 *
 * <p><b>Read only.</b> Counters are read with plain {@code SELECT}s (no {@code FOR UPDATE}, no {@code INSERT}): under
 * MVCC they neither wait for nor block a concurrent reserve or settle, and a window with no counter row yet is reported
 * as an empty window (used 0) without creating the row. The numbers come from the same sources the engine spends from:
 * {@link EntitlementResolver} for plan and allowance, {@link UserWindows} for the window, {@code usage_counters} for
 * consumption and {@link Credits#balance} for credits.
 */
final class UsageReadService {

	/** Rows returned by one history query; newest first, so the cut-off drops the oldest. */
	static final int MAX_HISTORY_ITEMS = 500;

	private final JdbcClient jdbc;
	private final EntitlementResolver entitlements;
	private final Credits credits;
	private final UserWindows windows;
	private final Clock clock;

	UsageReadService(JdbcClient jdbc, EntitlementResolver entitlements, Credits credits, UsageProperties properties,
			Clock clock) {
		this.jdbc = jdbc;
		this.entitlements = entitlements;
		this.credits = credits;
		this.windows = new UserWindows(jdbc, properties.zone());
		this.clock = clock;
	}

	// ------------------------------------------------------------------ /me/entitlements

	EntitlementsDto entitlements(UUID userId, UUID deviceId, EntitlementTokenIssuer issuer) {
		Instant now = clock.instant();
		ResolvedEntitlements resolved = entitlements.resolve(userId);
		EffectivePlan plan = requirePlan(resolved);
		List<FeatureDto> features = new ArrayList<>();
		for (FeatureEntitlement f : resolved.features().values()) {
			features.add(feature(userId, f, resolved, now));
		}
		EntitlementTokenIssuer.Issued token = issuer.issue(resolved, deviceId);
		return new EntitlementsDto(
				new PlanDto(plan.planCode(), plan.displayName(), plan.planRank(), plan.source().contractValue(),
						plan.subscriptionId(), plan.organizationId(), plan.effectiveUntil()),
				features, credits.balance(userId, now).display(),
				new TokenDto(token.jws(), token.keyId(), token.expiresAt(), token.offlineGraceUntil()));
	}

	private FeatureDto feature(UUID userId, FeatureEntitlement f, ResolvedEntitlements resolved, Instant now) {
		if (!f.gated() || f.unit() == MeteringUnit.NONE || !f.accessEnabled()) {
			// ungated typing features, boolean gates and features the plan excludes: no consumption to report
			return new FeatureDto(f.featureCode(), f.accessEnabled(), f.includedUnits(), f.unit().name(),
					f.resetPeriod().name(), null, null, null);
		}
		UsageWindow window = windows.current(userId, f, resolved.plan(), now);
		Counts counts = counts(userId, f.featureCode(), window);
		Long included = f.includedUnits();
		Long remaining = included == null ? null : Math.max(0, included - counts.used() - counts.reserved());
		return new FeatureDto(f.featureCode(), true, included, f.unit().name(), f.resetPeriod().name(), counts.used(),
				remaining, window.resetAt());
	}

	// ------------------------------------------------------------------ /me/usage

	/**
	 * Without {@code from} and {@code to}: the current window of every metered feature the plan gives access to.
	 * With either: the user's counter rows whose window overlaps {@code [from, to)} (newest first), plus the current
	 * window of a feature that has no row yet when it overlaps the range.
	 */
	UsageReportDto usage(UUID userId, Instant from, Instant to) {
		Instant now = clock.instant();
		ResolvedEntitlements resolved = entitlements.resolve(userId);
		requirePlan(resolved);
		boolean history = from != null || to != null;
		if (from != null && to != null && !to.isAfter(from)) {
			return new UsageReportDto(List.of());
		}
		List<UsageItemDto> items = new ArrayList<>();
		Set<String> covered = new HashSet<>();
		if (history) {
			for (UsageItemDto row : history(userId, from, to, resolved, now)) {
				items.add(row);
				if (!row.periodStart().isAfter(now) && row.periodEnd().isAfter(now)) {
					covered.add(row.featureCode());
				}
			}
		}
		for (FeatureEntitlement f : resolved.features().values()) {
			if (!f.gated() || f.unit() == MeteringUnit.NONE || !f.accessEnabled() || covered.contains(f.featureCode())) {
				continue;
			}
			UsageWindow window = windows.current(userId, f, resolved.plan(), now);
			boolean overlaps = (from == null || window.end().isAfter(from)) && (to == null || window.start().isBefore(to));
			if (!history || overlaps) {
				Counts counts = counts(userId, f.featureCode(), window);
				items.add(new UsageItemDto(f.featureCode(), window.start(), window.end(), counts.used(),
						f.includedUnits(), counts.credits(), f.unit().name()));
			}
		}
		items.sort((a, b) -> {
			int byStart = b.periodStart().compareTo(a.periodStart());
			return byStart != 0 ? byStart : a.featureCode().compareTo(b.featureCode());
		});
		return new UsageReportDto(items.size() > MAX_HISTORY_ITEMS ? items.subList(0, MAX_HISTORY_ITEMS) : items);
	}

	private List<UsageItemDto> history(UUID userId, Instant from, Instant to, ResolvedEntitlements resolved,
			Instant now) {
		return jdbc.sql("""
				SELECT c.feature_code, c.period_start, c.period_end, c.used_units, f.metering_unit,
				       (SELECT max(r.included_units) FROM usage_reservations r WHERE r.usage_counter_id = c.id) AS included,
				       COALESCE((SELECT sum(r.credits_charged) FROM usage_reservations r
				                 WHERE r.usage_counter_id = c.id AND r.reservation_status = 'SETTLED'), 0) AS credits
				FROM usage_counters c JOIN feature_catalog f ON f.feature_code = c.feature_code
				WHERE c.subject_type = 'USER' AND c.user_id = :user
				  AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR c.period_end > CAST(:from AS TIMESTAMPTZ))
				  AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR c.period_start < CAST(:to AS TIMESTAMPTZ))
				ORDER BY c.period_start DESC, c.feature_code
				LIMIT :limit
				""").param("user", userId).param("from", from == null ? null : Timestamp.from(from), Types.TIMESTAMP)
				.param("to", to == null ? null : Timestamp.from(to), Types.TIMESTAMP).param("limit", MAX_HISTORY_ITEMS)
				.query((rs, n) -> {
					Instant start = rs.getTimestamp("period_start").toInstant();
					Instant end = rs.getTimestamp("period_end").toInstant();
					String code = rs.getString("feature_code");
					Long included = rs.getObject("included", Long.class);
					if (!start.isAfter(now) && end.isAfter(now)) {
						// the current window: the allowance in force now, which may differ from older reservations
						included = resolved.feature(code).map(FeatureEntitlement::includedUnits).orElse(included);
					}
					return new UsageItemDto(code, start, end, rs.getLong("used_units"), included,
							rs.getBigDecimal("credits"), rs.getString("metering_unit"));
				}).list();
	}

	// ------------------------------------------------------------------ helpers

	private record Counts(long used, long reserved, BigDecimal credits) {
	}

	/** Consumption of one window; zeros when the engine has not created the counter row yet. */
	private Counts counts(UUID userId, String featureCode, UsageWindow window) {
		Optional<Counts> row = jdbc.sql("""
				SELECT c.used_units, c.reserved_units,
				       COALESCE((SELECT sum(r.credits_charged) FROM usage_reservations r
				                 WHERE r.usage_counter_id = c.id AND r.reservation_status = 'SETTLED'), 0) AS credits
				FROM usage_counters c
				WHERE c.subject_type = 'USER' AND c.user_id = :user AND c.feature_code = :feature
				  AND c.period_start = :start AND c.period_end = :end
				""").param("user", userId).param("feature", featureCode).param("start", Timestamp.from(window.start()))
				.param("end", Timestamp.from(window.end()))
				.query((rs, n) -> new Counts(rs.getLong("used_units"), rs.getLong("reserved_units"),
						rs.getBigDecimal("credits")))
				.optional();
		return row.orElse(new Counts(0, 0, BigDecimal.ZERO.setScale(6)));
	}

	/** The plan, or the problem for an account that holds no entitlements (B2.4 / B2.6 rules). */
	private static EffectivePlan requirePlan(ResolvedEntitlements resolved) {
		return switch (resolved.access()) {
			case GRANTED -> resolved.plan().orElseThrow();
			case SUSPENDED -> throw new ApiException(ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN,
					"This account is suspended.");
			case DELETED -> throw new ApiException(ApiErrorCode.DELETION_PENDING, HttpStatus.FORBIDDEN,
					"This account is being deleted.");
			case PENDING -> throw new ApiException(ApiErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN,
					"Registration is not complete.");
			case MERGED, UNKNOWN -> throw new ApiException(ApiErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED,
					"Authentication required.");
		};
	}
}
