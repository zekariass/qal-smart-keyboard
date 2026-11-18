package com.qalsmartkeyboard.usage;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.GuestMergeParticipant;

/**
 * Guest merge (B2.6, ADR 0010) for metering: <b>the guest's consumption in its current windows carries over</b> to the
 * registered account, so signing in can never reset an allowance (SD 9.1 "remaining trial usage moves", SD 15.3). Without
 * this, a person could use the guest trial, sign in and use the full registered allowance of the same day on top.
 *
 * <p>For every {@code usage_counters} row of the guest whose window has not ended ({@code period_end > now}) and that
 * has consumption, {@code used_units + reserved_units} (an in-flight reservation counts as used: conservative, it is
 * settled or released on the guest's counter later) is added to the target's counter of the same feature whose window
 * contains {@code now}. The target's window wins (its plan and time zone define it, SD 15.3). If the target has no
 * current counter for the feature, a counter with the guest's window is created for the target (or added to, if one
 * exists for exactly that window); the metering engine (B4.2) must count counters whose window overlaps the one it
 * computes, or create the target's counter from this one. Allowances are never summed: the target's plan limit applies
 * to the combined consumption.
 *
 * <p>Not moved, on purpose: the guest's counters stay as they are (history; {@code usage_events} reference them), and
 * {@code usage_events} is an append-only ledger keyed by subject id. Credits ({@code ai_credit_accounts}) and
 * subscriptions are not touched: guests cannot buy (SD 9.1), so they have none.
 */
@Component
class UsageGuestMerge implements GuestMergeParticipant {

	private static final Logger log = LoggerFactory.getLogger(UsageGuestMerge.class);

	private final JdbcClient jdbc;

	UsageGuestMerge(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int mergeOrder() {
		return USAGE;
	}

	@Override
	public void mergeGuest(UUID guest, UUID target, Instant now) {
		Timestamp ts = Timestamp.from(now);
		record Consumption(String feature, Timestamp start, Timestamp end, long units) {
		}
		List<Consumption> current = jdbc.sql("""
				SELECT feature_code, period_start, period_end, used_units + reserved_units AS units
				FROM usage_counters
				WHERE subject_type = 'USER' AND user_id = :guest AND period_end > :now
				  AND used_units + reserved_units > 0
				ORDER BY feature_code, period_start
				FOR UPDATE
				""").param("guest", guest).param("now", ts)
				.query((rs, n) -> new Consumption(rs.getString("feature_code"), rs.getTimestamp("period_start"),
						rs.getTimestamp("period_end"), rs.getLong("units")))
				.list();
		long carried = 0;
		for (Consumption c : current) {
			int updated = jdbc.sql("""
					UPDATE usage_counters SET used_units = used_units + :units, updated_at = :now
					WHERE id = (SELECT id FROM usage_counters
					            WHERE subject_type = 'USER' AND user_id = :target AND feature_code = :feature
					              AND period_start <= :now AND period_end > :now
					            ORDER BY period_start DESC, id LIMIT 1 FOR UPDATE)
					""").param("units", c.units()).param("now", ts).param("target", target)
					.param("feature", c.feature()).update();
			if (updated == 0) {
				jdbc.sql("""
						INSERT INTO usage_counters (id, subject_type, user_id, feature_code, period_start, period_end,
						                            used_units, reserved_units, updated_at)
						VALUES (:id, 'USER', :target, :feature, :start, :end, :units, 0, :now)
						ON CONFLICT ON CONSTRAINT ux_usage_subject_feature_period
						DO UPDATE SET used_units = usage_counters.used_units + EXCLUDED.used_units, updated_at = :now
						""").param("id", UUID.randomUUID()).param("target", target).param("feature", c.feature())
						.param("start", c.start()).param("end", c.end()).param("units", c.units()).param("now", ts)
						.update();
			}
			carried += c.units();
		}
		log.info("Guest merge {} -> {}: {} unit(s) of current usage carried over in {} counter(s)", guest, target,
				carried, current.size());
	}
}
