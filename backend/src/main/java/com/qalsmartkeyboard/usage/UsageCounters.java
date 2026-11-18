package com.qalsmartkeyboard.usage;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code usage_counters} rows of USER subjects: one per user, feature and window. The row lock taken here is what
 * serialises concurrent reservations of one window (ADR 0011); every caller holds it until its transaction ends.
 */
final class UsageCounters {

	/** A locked counter row. */
	record Counter(UUID id, long used, long reserved) {
	}

	private final JdbcClient jdbc;

	UsageCounters(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Creates the window's counter if it is missing, then locks it ({@code SELECT ... FOR UPDATE}) for the rest of the
	 * transaction. Concurrent first reservations of a window converge on one row: {@code ON CONFLICT DO NOTHING} waits
	 * for the other inserter and the following SELECT sees its row.
	 */
	Counter lock(UUID userId, String featureCode, UsageWindow window, Instant now) {
		Timestamp start = Timestamp.from(window.start());
		Timestamp end = Timestamp.from(window.end());
		jdbc.sql("""
				INSERT INTO usage_counters (id, subject_type, user_id, feature_code, period_start, period_end,
				                            used_units, reserved_units, updated_at)
				VALUES (:id, 'USER', :user, :feature, :start, :end, 0, 0, :now)
				ON CONFLICT ON CONSTRAINT ux_usage_subject_feature_period DO NOTHING
				""").param("id", UUID.randomUUID()).param("user", userId).param("feature", featureCode)
				.param("start", start).param("end", end).param("now", Timestamp.from(now)).update();
		return jdbc.sql("""
				SELECT id, used_units, reserved_units FROM usage_counters
				WHERE subject_type = 'USER' AND user_id = :user AND feature_code = :feature
				  AND period_start = :start AND period_end = :end
				FOR UPDATE
				""").param("user", userId).param("feature", featureCode).param("start", start).param("end", end)
				.query((rs, n) -> new Counter(rs.getObject("id", UUID.class), rs.getLong("used_units"),
						rs.getLong("reserved_units")))
				.single();
	}

	/** Locks a counter by id. */
	Counter lock(UUID counterId) {
		return jdbc.sql("SELECT id, used_units, reserved_units FROM usage_counters WHERE id = :id FOR UPDATE")
				.param("id", counterId)
				.query((rs, n) -> new Counter(rs.getObject("id", UUID.class), rs.getLong("used_units"),
						rs.getLong("reserved_units")))
				.single();
	}

	/**
	 * Adds a reservation's allowance units to {@code reserved_units}. The engine has already checked the allowance
	 * under the row lock; the conditional UPDATE re-checks {@code used + reserved + units <= included} in the database
	 * as a second, independent guard (ADR 0011).
	 *
	 * @param included the window's allowance; null = unlimited
	 */
	void reserve(UUID counterId, long units, Long included, Instant now) {
		int updated = jdbc.sql("""
				UPDATE usage_counters SET reserved_units = reserved_units + :units, updated_at = :now
				WHERE id = :id AND (CAST(:included AS BIGINT) IS NULL
				                    OR used_units + reserved_units + :units <= CAST(:included AS BIGINT))
				""").param("units", units).param("now", Timestamp.from(now)).param("id", counterId)
				.param("included", included, Types.BIGINT).update();
		if (updated != 1) {
			throw new IllegalStateException("Usage counter " + counterId + " would exceed its allowance");
		}
	}

	/**
	 * Closes a reservation on its counter: {@code release} units leave {@code reserved_units} and {@code used} units
	 * are added to {@code used_units}. When more is used than was reserved (an over-settle) the allowance is re-checked
	 * in the database as for {@link #reserve}.
	 */
	void close(UUID counterId, long release, long used, Long included, Instant now) {
		int updated = jdbc.sql("""
				UPDATE usage_counters
				SET reserved_units = reserved_units - :release, used_units = used_units + :used, updated_at = :now
				WHERE id = :id AND reserved_units >= :release
				  AND (:used <= :release OR CAST(:included AS BIGINT) IS NULL
				       OR used_units + reserved_units - :release + :used <= CAST(:included AS BIGINT))
				""").param("release", release).param("used", used).param("now", Timestamp.from(now))
				.param("id", counterId).param("included", included, Types.BIGINT).update();
		if (updated != 1) {
			throw new IllegalStateException("Usage counter " + counterId + " cannot close the reservation");
		}
	}

	/** Adds consumption that was not reserved here (guest merge carry-over, ADR 0010). No allowance check. */
	void addUsed(UUID counterId, long units, Instant now) {
		jdbc.sql("UPDATE usage_counters SET used_units = used_units + :units, updated_at = :now WHERE id = :id")
				.param("units", units).param("now", Timestamp.from(now)).param("id", counterId).update();
	}
}
