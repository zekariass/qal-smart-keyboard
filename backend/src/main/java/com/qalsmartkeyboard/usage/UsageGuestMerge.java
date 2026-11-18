package com.qalsmartkeyboard.usage;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.entitlements.FeatureEntitlement;
import com.qalsmartkeyboard.entitlements.MeteringUnit;
import com.qalsmartkeyboard.entitlements.ResolvedEntitlements;
import com.qalsmartkeyboard.identity.GuestMergeParticipant;

/**
 * Guest merge (B2.6, ADR 0010) for metering: <b>the guest's consumption in its current windows carries over</b> to the
 * registered account, so signing in can never reset an allowance (SD 9.1 "remaining trial usage moves", SD 15.3).
 * Without this, a person could use the guest trial, sign in and use the full registered allowance of the same day on
 * top.
 *
 * <p>For every {@code usage_counters} row of the guest whose window has not ended ({@code period_end > now}) and that
 * has consumption, {@code used_units + reserved_units} (an in-flight reservation counts as used: conservative; it is
 * settled or released on the guest's counter later) is added to the {@code used_units} of the target's counter for
 * <b>the window the metering engine computes for the target now</b> (B4.2, ADR 0011: the target's plan, reset period,
 * paid-period anchor and time zone). The guest's window may overlap the target's without being equal (another time
 * zone, a DAILY guest window against an anchored MONTHLY one, ...): the consumption always lands in exactly the counter
 * the engine locks on the target's next reserve, so it is neither lost nor counted twice. Allowances are never summed:
 * the target's plan limit applies to the combined consumption. A feature the target cannot meter (not in the active
 * catalogue, or unit NONE) carries nothing.
 *
 * <p>Not moved, on purpose: the guest's counters stay as they are (history; {@code usage_events} and
 * {@code usage_reservations} reference them), and {@code usage_events} is an append-only ledger keyed by subject id.
 * Credits ({@code ai_credit_accounts}) and subscriptions are not touched: guests cannot buy (SD 9.1), so they have none.
 */
@Component
class UsageGuestMerge implements GuestMergeParticipant {

	private static final Logger log = LoggerFactory.getLogger(UsageGuestMerge.class);

	private final JdbcClient jdbc;
	private final EntitlementResolver entitlements;
	private final UsageCounters counters;
	private final UserWindows windows;

	UsageGuestMerge(JdbcClient jdbc, EntitlementResolver entitlements, UsageProperties properties) {
		this.jdbc = jdbc;
		this.entitlements = entitlements;
		this.counters = new UsageCounters(jdbc);
		this.windows = new UserWindows(jdbc, properties.zone());
	}

	@Override
	public int mergeOrder() {
		return USAGE;
	}

	@Override
	public void mergeGuest(UUID guest, UUID target, Instant now) {
		record Consumption(String feature, long units) {
		}
		List<Consumption> current = jdbc.sql("""
				SELECT feature_code, used_units + reserved_units AS units
				FROM usage_counters
				WHERE subject_type = 'USER' AND user_id = :guest AND period_end > :now
				  AND used_units + reserved_units > 0
				ORDER BY feature_code, period_start
				FOR UPDATE
				""").param("guest", guest).param("now", Timestamp.from(now))
				.query((rs, n) -> new Consumption(rs.getString("feature_code"), rs.getLong("units")))
				.list();
		if (current.isEmpty()) {
			return;
		}
		ResolvedEntitlements resolved = entitlements.resolve(target, now);
		long carried = 0;
		int counted = 0;
		for (Consumption c : current) {
			Optional<FeatureEntitlement> feature = resolved.feature(c.feature())
					.filter(f -> f.unit() != MeteringUnit.NONE);
			if (feature.isEmpty()) {
				continue;
			}
			UsageWindow window = windows.current(target, feature.get(), resolved.plan(), now);
			UsageCounters.Counter counter = counters.lock(target, c.feature(), window, now);
			counters.addUsed(counter.id(), c.units(), now);
			carried += c.units();
			counted++;
		}
		log.info("Guest merge {} -> {}: {} unit(s) of current usage carried over in {} counter update(s)", guest,
				target, carried, counted);
	}
}
