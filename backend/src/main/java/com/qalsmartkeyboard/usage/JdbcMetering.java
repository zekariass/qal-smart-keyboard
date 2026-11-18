package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.entitlements.EffectivePlan;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.entitlements.FeatureEntitlement;
import com.qalsmartkeyboard.entitlements.MeteringUnit;
import com.qalsmartkeyboard.entitlements.ResolvedEntitlements;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.usage.UsageCounters.Counter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * PostgreSQL implementation of {@link Metering} (ADR 0011).
 *
 * <h2>Why it cannot overspend</h2>
 * Every reserve locks the window's {@code usage_counters} row ({@code SELECT ... FOR UPDATE}, created first with
 * {@code INSERT ... ON CONFLICT DO NOTHING}), so all reservations of one user, feature and window run one after the
 * other through the check {@code used + reserved + allowanceUnits <= included}. The {@code UPDATE} that bumps
 * {@code reserved_units} repeats that check in its {@code WHERE} clause, so even a bug in the Java arithmetic cannot
 * push the counter past the allowance. Credits go through {@link CreditWallet#hold}, which is all-or-nothing in the same
 * transaction. Lock order is always reservation row, then counter row, then wallet account, so the paths cannot
 * deadlock each other.
 *
 * <h2>Idempotency</h2>
 * {@code usage_reservations (user_id, idempotency_key)} is unique; the insert uses {@code ON CONFLICT DO NOTHING}, which
 * waits for a concurrent insert of the same key and then reports the conflict, after which the original row is read and
 * replayed. Settle and release lock the reservation row and look at its status, so repeating them is harmless.
 */
final class JdbcMetering implements Metering {

	private static final Logger log = LoggerFactory.getLogger(JdbcMetering.class);
	static final int MAX_IDEMPOTENCY_KEY = 128;
	private static final int CREDIT_SCALE = 6;
	private static final SecureRandom RANDOM = new SecureRandom();

	/** A {@code usage_reservations} row with its counter's window. */
	record Row(UUID id, UUID userId, String featureCode, UUID counterId, String idempotencyKey, MeteringUnit unit,
			ReservationStatus status, long estimated, long allowance, long credit, Long included, BigDecimal cost,
			BigDecimal held, Instant expiresAt, Long actual, Long settledAllowance, Long settledCredit,
			BigDecimal creditsCharged, Long uncovered, ReleaseReason reason, UsageWindow window) {
	}

	private static final String SELECT_ROW = """
			SELECT r.id, r.user_id, r.feature_code, r.usage_counter_id, r.idempotency_key, r.metering_unit,
			       r.reservation_status, r.estimated_units, r.allowance_units, r.credit_units, r.included_units,
			       r.credit_cost_per_unit, r.credits_held, r.expires_at, r.actual_units, r.settled_allowance_units,
			       r.settled_credit_units, r.credits_charged, r.uncovered_units, r.release_reason,
			       c.period_start, c.period_end
			FROM usage_reservations r
			JOIN usage_counters c ON c.id = r.usage_counter_id
			""";

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;
	private final EntitlementResolver entitlements;
	private final UsageCounters counters;
	private final UserWindows windows;
	private final CreditWallet wallet;
	private final UpgradeOptions upgrades;
	private final UsageProperties properties;
	private final Clock clock;
	private final MeterRegistry meters;

	JdbcMetering(JdbcClient jdbc, TransactionTemplate tx, EntitlementResolver entitlements, CreditWallet wallet,
			UsageProperties properties, Clock clock, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.entitlements = entitlements;
		this.counters = new UsageCounters(jdbc);
		this.windows = new UserWindows(jdbc, properties.zone());
		this.wallet = wallet;
		this.upgrades = new UpgradeOptions(jdbc);
		this.properties = properties;
		this.clock = clock;
		this.meters = meters;
	}

	// ------------------------------------------------------------------ reserve

	@Override
	public Reservation reserve(UUID userId, String featureCode, long estimatedUnits, String idempotencyKey) {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(featureCode, "featureCode");
		if (estimatedUnits < 1) {
			throw new IllegalArgumentException("estimatedUnits must be at least 1");
		}
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
			throw new IllegalArgumentException("An idempotency key of 1 to " + MAX_IDEMPOTENCY_KEY
					+ " characters is required");
		}
		try {
			Reservation r = tx.execute(s -> doReserve(userId, featureCode, estimatedUnits, idempotencyKey));
			count("qal.usage.reservations", "outcome",
					r.replayed() ? "replayed" : r.creditUnits() > 0 ? "reserved_with_credits" : "reserved");
			return r;
		} catch (QuotaExceededException e) {
			count("qal.usage.reservations", "outcome", "quota_exceeded");
			throw e;
		}
	}

	private Reservation doReserve(UUID userId, String featureCode, long estimated, String key) {
		Optional<Row> existing = findByKey(userId, key);
		if (existing.isPresent()) {
			return replay(existing.get(), featureCode, estimated);
		}
		Instant now = clock.instant();
		ResolvedEntitlements resolved = entitlements.resolve(userId);
		FeatureEntitlement feature = resolved.feature(featureCode)
				.orElseThrow(() -> new IllegalArgumentException("Unknown or inactive feature " + featureCode));
		if (!feature.gated() || feature.unit() == MeteringUnit.NONE) {
			throw new IllegalArgumentException("Feature " + featureCode + " is not metered");
		}
		if (!feature.accessEnabled()) {
			throw new ApiException(ApiErrorCode.FEATURE_NOT_ENTITLED, HttpStatus.FORBIDDEN,
					"The current plan does not include " + featureCode + ".");
		}
		UsageWindow window = windows.current(userId, feature, resolved.plan(), now);
		Counter counter = counters.lock(userId, featureCode, window, now);

		Long included = feature.includedUnits();
		long available = included == null ? Long.MAX_VALUE
				: Math.max(0, included - counter.used() - counter.reserved());
		long allowanceUnits = Math.min(estimated, available);
		long creditUnits = estimated - allowanceUnits;
		BigDecimal cost = feature.creditCostPerUnit().setScale(CREDIT_SCALE, RoundingMode.UNNECESSARY);
		BigDecimal held = credits(cost, creditUnits);
		if (creditUnits > 0 && !(feature.creditsUsable() && cost.signum() > 0)) {
			throw quotaExceeded(userId, resolved, feature, window);
		}

		UUID id = uuid7(now);
		int inserted = jdbc.sql("""
				INSERT INTO usage_reservations (id, user_id, feature_code, usage_counter_id, idempotency_key,
				    metering_unit, reservation_status, estimated_units, allowance_units, credit_units, included_units,
				    credit_cost_per_unit, credits_held, expires_at, created_at)
				VALUES (:id, :user, :feature, :counter, :key, :unit, 'RESERVED', :estimated, :allowance, :credit,
				        :included, :cost, :held, :expires, :now)
				ON CONFLICT (user_id, idempotency_key) DO NOTHING
				""").param("id", id).param("user", userId).param("feature", featureCode).param("counter", counter.id())
				.param("key", key).param("unit", feature.unit().name()).param("estimated", estimated)
				.param("allowance", allowanceUnits).param("credit", creditUnits)
				.param("included", included, Types.BIGINT).param("cost", cost).param("held", held)
				.param("expires", Timestamp.from(now.plus(properties.reservationTtl())))
				.param("now", Timestamp.from(now)).update();
		if (inserted == 0) {
			// a concurrent request with the same key committed first (ON CONFLICT waited for it)
			return replay(findByKey(userId, key).orElseThrow(), featureCode, estimated);
		}
		if (creditUnits > 0 && !wallet.hold(userId, id, held)) {
			throw quotaExceeded(userId, resolved, feature, window); // rolls the reservation row back
		}
		if (allowanceUnits > 0) {
			counters.reserve(counter.id(), allowanceUnits, included, now);
		}
		jdbc.sql("""
				INSERT INTO usage_events (id, event_type, reservation_id, usage_counter_id, subject_type, subject_id,
				                          feature_code, units, idempotency_key, expires_at, created_at)
				VALUES (:id, 'RESERVE', NULL, :counter, 'USER', :user, :feature, :units, :key, :expires, :now)
				""").param("id", id).param("counter", counter.id()).param("user", userId).param("feature", featureCode)
				.param("units", estimated).param("key", key)
				.param("expires", Timestamp.from(now.plus(properties.reservationTtl())))
				.param("now", Timestamp.from(now)).update();
		log.debug("Reserved {} {} of {} for user {} ({} allowance, {} credit units): {}", estimated, feature.unit(),
				featureCode, userId, allowanceUnits, creditUnits, id);
		return new Reservation(id, userId, featureCode, feature.unit(), estimated, allowanceUnits, creditUnits, cost,
				held, now.plus(properties.reservationTtl()), window, ReservationStatus.RESERVED, false);
	}

	private Reservation replay(Row row, String featureCode, long estimated) {
		if (!row.featureCode().equals(featureCode) || row.estimated() != estimated) {
			throw new ApiException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.CONFLICT,
					"This Idempotency-Key was already used for a different request.");
		}
		return new Reservation(row.id(), row.userId(), row.featureCode(), row.unit(), row.estimated(), row.allowance(),
				row.credit(), row.cost(), row.held(), row.expiresAt(), row.window(), row.status(), true);
	}

	private QuotaExceededException quotaExceeded(UUID userId, ResolvedEntitlements resolved,
			FeatureEntitlement feature, UsageWindow window) {
		int rank = resolved.plan().map(EffectivePlan::planRank).orElse(Integer.MIN_VALUE);
		return new QuotaExceededException(feature.featureCode(), window.resetAt(), wallet.balance(userId),
				upgrades.forFeature(userId, feature.featureCode(), rank, feature.creditsUsable(),
						resolved.quotaPolicyVersion()));
	}

	// ------------------------------------------------------------------ settle

	@Override
	public Settlement settle(UUID reservationId, long actualUnits) {
		Objects.requireNonNull(reservationId, "reservationId");
		if (actualUnits < 0) {
			throw new IllegalArgumentException("actualUnits must not be negative");
		}
		Settlement s = tx.execute(t -> doSettle(reservationId, actualUnits));
		if (!s.replayed()) {
			count("qal.usage.settlements", "source", s.status() == ReservationStatus.RELEASED ? "nothing_used"
					: s.usageSource().name().toLowerCase(java.util.Locale.ROOT));
			if (s.uncoveredUnits() > 0) {
				count("qal.usage.settlements.uncovered", "feature", s.featureCode());
			}
		}
		return s;
	}

	private Settlement doSettle(UUID reservationId, long actual) {
		Row row = lockRow(reservationId)
				.orElseThrow(() -> new NoSuchElementException("Unknown reservation " + reservationId));
		switch (row.status()) {
			case SETTLED -> {
				if (row.actual() != actual) {
					throw new ReservationClosedException(row.id(), row.status(),
							"Reservation " + row.id() + " was settled with other units");
				}
				return settlement(row, row.actual(), row.settledAllowance(), row.settledCredit(),
						row.creditsCharged(), row.uncovered(), ReservationStatus.SETTLED, true);
			}
			case RELEASED -> {
				if (actual == 0 && row.reason() == ReleaseReason.NOTHING_USED) {
					return settlement(row, 0, 0, 0, zero(), 0, ReservationStatus.RELEASED, true);
				}
				throw new ReservationClosedException(row.id(), row.status(),
						"Reservation " + row.id() + " was already released (" + row.reason() + ")");
			}
			case RESERVED -> {
				// settled below
			}
		}
		Instant now = clock.instant();
		if (actual == 0) {
			close(row, ReleaseReason.NOTHING_USED, now);
			return settlement(row, 0, 0, 0, zero(), 0, ReservationStatus.RELEASED, false);
		}
		Counter counter = counters.lock(row.counterId());
		long allowanceUsed;
		long creditUsed;
		long uncovered = 0;
		BigDecimal heldTotal = row.held();
		if (actual <= row.estimated()) {
			// allowance first: unused credits go back before unused allowance
			allowanceUsed = Math.min(actual, row.allowance());
			creditUsed = actual - allowanceUsed;
		} else {
			allowanceUsed = row.allowance();
			creditUsed = row.credit();
			long extra = actual - row.estimated();
			// room left in the window beyond everything reserved, this reservation's own allowance units included
			long free = row.included() == null ? Long.MAX_VALUE
					: Math.max(0, row.included() - counter.used() - counter.reserved());
			long extraAllowance = Math.min(extra, free);
			allowanceUsed += extraAllowance;
			extra -= extraAllowance;
			if (extra > 0 && row.cost().signum() > 0) {
				long affordable = wallet.balance(row.userId()).divide(row.cost(), 0, RoundingMode.FLOOR)
						.min(BigDecimal.valueOf(extra)).longValueExact();
				if (affordable > 0 && wallet.hold(row.userId(), row.id(), credits(row.cost(), affordable))) {
					heldTotal = heldTotal.add(credits(row.cost(), affordable));
					creditUsed += affordable;
					extra -= affordable;
				}
			}
			uncovered = extra;
		}
		BigDecimal charged = credits(row.cost(), creditUsed);
		counters.close(counter.id(), row.allowance(), allowanceUsed, row.included(), now);
		if (heldTotal.signum() > 0) {
			wallet.settle(row.userId(), row.id(), heldTotal, charged);
		}
		appendClose(row, "SETTLE", allowanceUsed + creditUsed, now);
		jdbc.sql("""
				UPDATE usage_reservations
				SET reservation_status = 'SETTLED', actual_units = :actual, settled_allowance_units = :allowance,
				    settled_credit_units = :credit, credits_charged = :charged, uncovered_units = :uncovered,
				    closed_at = :now
				WHERE id = :id AND reservation_status = 'RESERVED'
				""").param("actual", actual).param("allowance", allowanceUsed).param("credit", creditUsed)
				.param("charged", charged).param("uncovered", uncovered).param("now", Timestamp.from(now))
				.param("id", row.id()).update();
		if (uncovered > 0) {
			log.warn("Reservation {} ({}) settled {} unit(s) above what allowance and credits cover; not charged",
					row.id(), row.featureCode(), uncovered);
		}
		return settlement(row, actual, allowanceUsed, creditUsed, charged, uncovered, ReservationStatus.SETTLED, false);
	}

	private Settlement settlement(Row row, long actual, long allowanceUnits, long creditUnits, BigDecimal charged,
			long uncovered, ReservationStatus status, boolean replayed) {
		Counter now = jdbc.sql("SELECT id, used_units, reserved_units FROM usage_counters WHERE id = :id")
				.param("id", row.counterId())
				.query((rs, n) -> new Counter(rs.getObject("id", UUID.class), rs.getLong("used_units"),
						rs.getLong("reserved_units")))
				.single();
		Long remaining = row.included() == null ? null
				: Math.max(0, row.included() - now.used() - now.reserved());
		return new Settlement(row.id(), row.userId(), row.featureCode(), row.unit(), actual,
				allowanceUnits + creditUnits, allowanceUnits, creditUnits, charged, uncovered, status, remaining,
				row.window().resetAt(), wallet.balance(row.userId()), replayed);
	}

	// ------------------------------------------------------------------ release and expiry

	@Override
	public Release release(UUID reservationId, ReleaseReason reason) {
		Objects.requireNonNull(reservationId, "reservationId");
		Objects.requireNonNull(reason, "reason");
		Release r = tx.execute(t -> {
			Row row = lockRow(reservationId)
					.orElseThrow(() -> new NoSuchElementException("Unknown reservation " + reservationId));
			return switch (row.status()) {
				case RELEASED -> new Release(row.id(), ReservationStatus.RELEASED, row.reason(), 0, true);
				case SETTLED -> new Release(row.id(), ReservationStatus.SETTLED, null, 0, true);
				case RESERVED -> {
					close(row, reason, clock.instant());
					yield new Release(row.id(), ReservationStatus.RELEASED, reason, row.estimated(), false);
				}
			};
		});
		if (!r.replayed()) {
			count("qal.usage.releases", "reason", reason.name().toLowerCase(java.util.Locale.ROOT));
		}
		return r;
	}

	/**
	 * Releases reservations whose {@code expires_at} has passed (SD 15.3 step 6). Each one is released in its own
	 * transaction after locking it with {@code SKIP LOCKED}: a reservation that is being settled or released right now
	 * is left alone, and running the sweep twice (or on two instances) releases nothing twice.
	 *
	 * @return the number of reservations released
	 */
	int releaseExpired() {
		int total = 0;
		int batch;
		do {
			Instant now = clock.instant();
			List<UUID> due = jdbc.sql("""
					SELECT id FROM usage_reservations
					WHERE reservation_status = 'RESERVED' AND expires_at <= :now
					ORDER BY expires_at LIMIT :limit
					""").param("now", Timestamp.from(now)).param("limit", properties.sweepBatchSize())
					.query(UUID.class).list();
			batch = 0;
			for (UUID id : due) {
				Boolean released = tx.execute(t -> {
					Optional<Row> row = jdbc.sql(SELECT_ROW + """
							WHERE r.id = :id AND r.reservation_status = 'RESERVED' AND r.expires_at <= :now
							FOR UPDATE OF r SKIP LOCKED
							""").param("id", id).param("now", Timestamp.from(now)).query(JdbcMetering::row).optional();
					row.ifPresent(r -> close(r, ReleaseReason.EXPIRED, now));
					return row.isPresent();
				});
				if (Boolean.TRUE.equals(released)) {
					batch++;
				}
			}
			total += batch;
			if (due.size() < properties.sweepBatchSize()) {
				break;
			}
		} while (batch > 0);
		if (total > 0) {
			count("qal.usage.releases", "reason", "expired", total);
			log.info("Released {} expired usage reservation(s)", total);
		}
		return total;
	}

	/** Gives back what a RESERVED row holds and records the release. Caller holds the reservation row lock. */
	private void close(Row row, ReleaseReason reason, Instant now) {
		counters.close(row.counterId(), row.allowance(), 0, row.included(), now);
		if (row.held().signum() > 0) {
			wallet.settle(row.userId(), row.id(), row.held(), zero());
		}
		appendClose(row, "RELEASE", row.estimated(), now);
		jdbc.sql("""
				UPDATE usage_reservations SET reservation_status = 'RELEASED', release_reason = :reason, closed_at = :now
				WHERE id = :id AND reservation_status = 'RESERVED'
				""").param("reason", reason.name()).param("now", Timestamp.from(now)).param("id", row.id()).update();
	}

	private void appendClose(Row row, String type, long units, Instant now) {
		jdbc.sql("""
				INSERT INTO usage_events (id, event_type, reservation_id, usage_counter_id, subject_type, subject_id,
				                          feature_code, units, created_at)
				VALUES (:id, :type, :reservation, :counter, 'USER', :user, :feature, :units, :now)
				""").param("id", uuid7(now)).param("type", type).param("reservation", row.id())
				.param("counter", row.counterId()).param("user", row.userId()).param("feature", row.featureCode())
				.param("units", units).param("now", Timestamp.from(now)).update();
	}

	// ------------------------------------------------------------------ rows

	private Optional<Row> findByKey(UUID userId, String key) {
		return jdbc.sql(SELECT_ROW + "WHERE r.user_id = :user AND r.idempotency_key = :key").param("user", userId)
				.param("key", key).query(JdbcMetering::row).optional();
	}

	private Optional<Row> lockRow(UUID id) {
		return jdbc.sql(SELECT_ROW + "WHERE r.id = :id FOR UPDATE OF r").param("id", id).query(JdbcMetering::row)
				.optional();
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		String reason = rs.getString("release_reason");
		return new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getString("feature_code"), rs.getObject("usage_counter_id", UUID.class),
				rs.getString("idempotency_key"), MeteringUnit.valueOf(rs.getString("metering_unit")),
				ReservationStatus.valueOf(rs.getString("reservation_status")), rs.getLong("estimated_units"),
				rs.getLong("allowance_units"), rs.getLong("credit_units"), rs.getObject("included_units", Long.class),
				rs.getBigDecimal("credit_cost_per_unit"), rs.getBigDecimal("credits_held"),
				rs.getTimestamp("expires_at").toInstant(), rs.getObject("actual_units", Long.class),
				rs.getObject("settled_allowance_units", Long.class), rs.getObject("settled_credit_units", Long.class),
				rs.getBigDecimal("credits_charged"), rs.getObject("uncovered_units", Long.class),
				reason == null ? null : ReleaseReason.valueOf(reason),
				new UsageWindow(rs.getTimestamp("period_start").toInstant(), rs.getTimestamp("period_end").toInstant()));
	}

	// ------------------------------------------------------------------ helpers

	/** {@code units x costPerUnit}, exact to 6 decimal places (both factors are exact: no rounding happens). */
	static BigDecimal credits(BigDecimal costPerUnit, long units) {
		return costPerUnit.multiply(BigDecimal.valueOf(units)).setScale(CREDIT_SCALE, RoundingMode.UNNECESSARY);
	}

	private static BigDecimal zero() {
		return BigDecimal.ZERO.setScale(CREDIT_SCALE);
	}

	/** RFC 9562 UUIDv7 (usage_events ids are application-generated, D18). */
	static UUID uuid7(Instant now) {
		long ms = now.toEpochMilli();
		long msb = (ms << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
		long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
		return new UUID(msb, lsb);
	}

	private void count(String name, String tag, String value) {
		count(name, tag, value, 1);
	}

	private void count(String name, String tag, String value, int amount) {
		if (meters != null) {
			meters.counter(name, tag, value).increment(amount);
		}
	}
}
