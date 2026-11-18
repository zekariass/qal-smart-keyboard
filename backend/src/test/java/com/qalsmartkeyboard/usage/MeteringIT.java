package com.qalsmartkeyboard.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.entitlements.MeteringUnit;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;

/**
 * B4.2 "done when": the metering engine against PostgreSQL 18 with the V1 seed (FREE: TRANSLATE 10/day,
 * SPEECH_TO_TEXT 600 s/month at 0.166667 credits/s): no overspend under parallel requests (allowance and credits),
 * settle/over-settle/release semantics, idempotent replays, the expiry sweep, window edges in the user's time zone and
 * the guest-merge carry-over into the target's window.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s" })
@Testcontainers
@Import(MeteringIT.Fixtures.class)
class MeteringIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	/** Clock shared by the engine and the entitlement resolver; null = real time. */
	static final AtomicReference<Instant> NOW = new AtomicReference<>();

	@TestConfiguration(proxyBeanMethods = false)
	static class Fixtures {

		@Bean
		@Primary
		Clock meteringTestClock() {
			return new Clock() {
				@Override
				public ZoneId getZone() {
					return ZoneOffset.UTC;
				}

				@Override
				public Clock withZone(ZoneId zone) {
					return this;
				}

				@Override
				public Instant instant() {
					Instant fixed = NOW.get();
					return fixed != null ? fixed : Instant.now();
				}
			};
		}

		@Bean
		@Primary
		BalanceOnlyCreditWallet balanceOnlyCreditWallet(JdbcClient jdbc) {
			return new BalanceOnlyCreditWallet(jdbc);
		}
	}

	@Autowired
	Metering metering;
	@Autowired
	JdbcMetering engine;
	@Autowired
	BalanceOnlyCreditWallet wallet;
	@Autowired
	UsageGuestMerge guestMerge;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager txManager;

	@AfterEach
	void realTime() {
		NOW.set(null);
	}

	// ================================================================== concurrency (done when)

	@Test
	void parallelReservesAgainstASmallLimitSucceedExactlyLimitTimes() throws Exception {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		List<Object> outcomes = parallel(40, i -> metering.reserve(user, "TRANSLATE", 1, key()));

		List<Reservation> ok = reservations(outcomes);
		assertThat(ok).hasSize(10);
		assertThat(outcomes.stream().filter(QuotaExceededException.class::isInstance)).hasSize(30);
		assertThat(ok).allSatisfy(r -> {
			assertThat(r.allowanceUnits()).isOne();
			assertThat(r.creditUnits()).isZero();
			assertThat(r.status()).isEqualTo(ReservationStatus.RESERVED);
		});
		UUID counter = counterOf(ok.get(0).id());
		assertThat(ok).extracting(r -> counterOf(r.id())).containsOnly(counter);
		assertCounter(counter, 0, 10);
		assertThat(count("SELECT count(*) FROM usage_events WHERE subject_id = ? AND event_type = 'RESERVE'", user))
				.isEqualTo(10);

		// close them in parallel, half settled and half released, while 20 new requests race for the freed units
		List<Object> mixed = parallel(30, i -> i < 5 ? metering.settle(ok.get(i).id(), 1)
				: i < 10 ? metering.release(ok.get(i).id(), ReleaseReason.FAILED)
						: metering.reserve(user, "TRANSLATE", 1, key()));
		long newOnes = mixed.stream().filter(Reservation.class::isInstance).count();
		assertThat(newOnes).isBetween(0L, 5L);
		assertThat(mixed.stream().filter(o -> o instanceof Settlement || o instanceof Release)).hasSize(10);
		assertCounter(counter, 5, newOnes);
		assertCounterMatchesReservations(counter);
		assertThat(used(counter) + reserved(counter)).isLessThanOrEqualTo(10);
	}

	@Test
	void parallelSecondReservesNeverExceedTheMonthlyAllowance() throws Exception {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		List<Object> outcomes = parallel(25, i -> metering.reserve(user, "SPEECH_TO_TEXT", 50, key()));
		List<Reservation> ok = reservations(outcomes);
		assertThat(ok).hasSize(12); // 12 x 50 s = the FREE 600 s
		assertThat(ok.get(0).unit()).isEqualTo(MeteringUnit.SECOND);
		UUID counter = counterOf(ok.get(0).id());
		assertCounter(counter, 0, 600);
		QuotaExceededException q = (QuotaExceededException) outcomes.stream()
				.filter(QuotaExceededException.class::isInstance).findFirst().orElseThrow();
		assertThat(q.featureCode()).isEqualTo("SPEECH_TO_TEXT");
		assertThat(q.resetAt()).isEqualTo(ok.get(0).window().end());
	}

	@Test
	void creditsPayAfterTheAllowanceAndAreNeverOverspentUnderConcurrency() throws Exception {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		wallet.fund(user, new BigDecimal("5.000000"));
		List<Object> outcomes = parallel(30, i -> metering.reserve(user, "TRANSLATE", 1, key()));
		List<Reservation> ok = reservations(outcomes);
		assertThat(ok).hasSize(15); // 10 from the allowance + 5 paid with 1 credit each
		assertThat(ok.stream().filter(r -> r.creditUnits() == 1)).hasSize(5)
				.allSatisfy(r -> assertThat(r.creditsHeld()).isEqualByComparingTo("1"));
		assertThat(wallet.balance(user)).isEqualByComparingTo("0");
		assertCounter(counterOf(ok.get(0).id()), 0, 10);

		QuotaExceededException q = (QuotaExceededException) outcomes.stream()
				.filter(QuotaExceededException.class::isInstance).findFirst().orElseThrow();
		assertThat(q.creditBalance()).isEqualByComparingTo("0");

		// releasing two credit-paid reservations gives their credits back; settling the rest keeps them spent
		List<Reservation> paid = ok.stream().filter(r -> r.creditUnits() == 1).toList();
		metering.release(paid.get(0).id(), ReleaseReason.FAILED);
		metering.release(paid.get(1).id(), ReleaseReason.CANCELLED);
		assertThat(wallet.balance(user)).isEqualByComparingTo("2");
		Settlement s = metering.settle(paid.get(2).id(), 1);
		assertThat(s.usageSource()).isEqualTo(UsageSource.AI_CREDITS);
		assertThat(s.creditsCharged()).isEqualByComparingTo("1");
		assertThat(s.creditBalance()).isEqualByComparingTo("2");
		assertThat(wallet.balance(user)).isEqualByComparingTo("2");
	}

	@Test
	void fractionalCreditsForSecondsAreExactAndSplitWithTheAllowance() {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		Reservation most = metering.reserve(user, "SPEECH_TO_TEXT", 590, key());
		assertThat(most.allowanceUnits()).isEqualTo(590);
		wallet.fund(user, new BigDecimal("10"));

		// 10 s left in the allowance, 20 s on credits at 0.166667/s
		Reservation split = metering.reserve(user, "SPEECH_TO_TEXT", 30, key());
		assertThat(split.allowanceUnits()).isEqualTo(10);
		assertThat(split.creditUnits()).isEqualTo(20);
		assertThat(split.creditCostPerUnit()).isEqualByComparingTo("0.166667");
		assertThat(split.creditsHeld()).isEqualTo(new BigDecimal("3.333340"));
		assertThat(wallet.balance(user)).isEqualTo(new BigDecimal("6.666660"));

		// 40 s on credits = 6.66668 > 6.66666: refused, with the balance rounded down
		assertThatThrownBy(() -> metering.reserve(user, "SPEECH_TO_TEXT", 40, key()))
				.isInstanceOfSatisfying(QuotaExceededException.class,
						q -> assertThat(q.creditBalance()).isEqualByComparingTo("6"));
		Reservation fits = metering.reserve(user, "SPEECH_TO_TEXT", 39, key()); // 6.500013
		assertThat(fits.creditsHeld()).isEqualTo(new BigDecimal("6.500013"));
		assertThat(wallet.balance(user)).isEqualTo(new BigDecimal("0.166647"));

		// used 15 s of the split one: the allowance part first, so 5 s of credits are charged and 15 s go back
		Settlement s = metering.settle(split.id(), 15);
		assertThat(s.allowanceUnits()).isEqualTo(10);
		assertThat(s.creditUnits()).isEqualTo(5);
		assertThat(s.creditsCharged()).isEqualTo(new BigDecimal("0.833335"));
		assertThat(s.usageSource()).isEqualTo(UsageSource.AI_CREDITS);
		assertThat(wallet.balance(user)).isEqualTo(new BigDecimal("2.666652")); // 0.166647 + 2.500005
		assertThat(s.remainingAllowance()).isZero();
		assertCounterMatchesReservations(counterOf(split.id()));
	}

	// ================================================================== settle / release semantics

	@Test
	void settleLessAndMoreThanReservedNeverOverdrawsTheAllowance() {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		Reservation a = metering.reserve(user, "SPEECH_TO_TEXT", 60, key());
		Settlement less = metering.settle(a.id(), 40);
		assertThat(less.chargedUnits()).isEqualTo(40);
		assertThat(less.remainingAllowance()).isEqualTo(560);
		UUID counter = counterOf(a.id());
		assertCounter(counter, 40, 0);

		// over-settle charged to the free allowance
		Reservation b = metering.reserve(user, "SPEECH_TO_TEXT", 60, key());
		Settlement more = metering.settle(b.id(), 100);
		assertThat(more.chargedUnits()).isEqualTo(100);
		assertThat(more.uncoveredUnits()).isZero();
		assertCounter(counter, 140, 0);

		// over-settle beyond the window's room and without credits: capped, the rest is uncovered (not charged)
		Reservation held = metering.reserve(user, "SPEECH_TO_TEXT", 400, key());
		Reservation c = metering.reserve(user, "SPEECH_TO_TEXT", 50, key());
		Settlement capped = metering.settle(c.id(), 200);
		assertThat(capped.allowanceUnits()).isEqualTo(60); // its 50 + the 10 s still free
		assertThat(capped.creditUnits()).isZero();
		assertThat(capped.uncoveredUnits()).isEqualTo(140);
		assertThat(capped.actualUnits()).isEqualTo(200);
		assertThat(capped.remainingAllowance()).isZero();
		assertCounter(counter, 200, 400);
		assertThat(used(counter) + reserved(counter)).isEqualTo(600);
		metering.settle(held.id(), 400);
		assertCounter(counter, 600, 0);
		assertCounterMatchesReservations(counter);
		assertThat(jdbc.queryForObject("SELECT units FROM usage_events WHERE reservation_id = ? AND event_type = 'SETTLE'",
				Long.class, c.id())).isEqualTo(60);
	}

	@Test
	void overSettleUsesCreditsAfterTheAllowance() {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		metering.reserve(user, "SPEECH_TO_TEXT", 580, key());
		wallet.fund(user, new BigDecimal("1"));
		Reservation r = metering.reserve(user, "SPEECH_TO_TEXT", 10, key());
		// 30 s used: its 10 s, 10 s more from the allowance, 5 s of credits (1 credit / 0.166667 = 5.99 s), 5 s uncovered
		Settlement s = metering.settle(r.id(), 30);
		assertThat(s.allowanceUnits()).isEqualTo(20);
		assertThat(s.creditUnits()).isEqualTo(5);
		assertThat(s.creditsCharged()).isEqualTo(new BigDecimal("0.833335"));
		assertThat(s.uncoveredUnits()).isEqualTo(5);
		assertThat(wallet.balance(user)).isEqualTo(new BigDecimal("0.166665"));
	}

	@Test
	void releaseGivesEverythingBackAndSettlingZeroIsARelease() {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		Reservation r = metering.reserve(user, "TRANSLATE", 1, key());
		Release rel = metering.release(r.id(), ReleaseReason.FAILED);
		assertThat(rel).isEqualTo(new Release(r.id(), ReservationStatus.RELEASED, ReleaseReason.FAILED, 1, false));
		assertCounter(counterOf(r.id()), 0, 0);
		assertThat(metering.release(r.id(), ReleaseReason.CANCELLED))
				.isEqualTo(new Release(r.id(), ReservationStatus.RELEASED, ReleaseReason.FAILED, 0, true));
		assertThat(events(r.id())).containsExactly("RESERVE", "RELEASE");

		Reservation z = metering.reserve(user, "TRANSLATE", 1, key());
		Settlement zero = metering.settle(z.id(), 0);
		assertThat(zero.status()).isEqualTo(ReservationStatus.RELEASED);
		assertThat(zero.chargedUnits()).isZero();
		assertThat(metering.settle(z.id(), 0).replayed()).isTrue();
		assertThat(reason(z.id())).isEqualTo("NOTHING_USED");
		assertThatThrownBy(() -> metering.settle(z.id(), 1)).isInstanceOf(ReservationClosedException.class);
		assertCounter(counterOf(z.id()), 0, 0);
	}

	// ================================================================== idempotency

	@Test
	void sameKeyReplaysTheOriginalAndADifferentRequestIsRefused() {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		String key = key();
		Reservation first = metering.reserve(user, "TRANSLATE", 1, key);
		Reservation again = metering.reserve(user, "TRANSLATE", 1, key);
		assertThat(again.replayed()).isTrue();
		assertThat(again.id()).isEqualTo(first.id());
		assertThat(again.status()).isEqualTo(ReservationStatus.RESERVED); // in flight: B5.4 answers REQUEST_IN_PROGRESS
		assertCounter(counterOf(first.id()), 0, 1);

		assertThatThrownBy(() -> metering.reserve(user, "TRANSLATE", 2, key))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.code()).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED));
		assertThatThrownBy(() -> metering.reserve(user, "GRAMMAR_CORRECTION", 1, key))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.code()).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED));

		Settlement settled = metering.settle(first.id(), 1);
		Settlement replayed = metering.settle(first.id(), 1);
		assertThat(replayed.replayed()).isTrue();
		assertThat(replayed.chargedUnits()).isEqualTo(settled.chargedUnits());
		assertThatThrownBy(() -> metering.settle(first.id(), 2)).isInstanceOf(ReservationClosedException.class);
		assertThat(metering.release(first.id(), ReleaseReason.FAILED))
				.isEqualTo(new Release(first.id(), ReservationStatus.SETTLED, null, 0, true));
		Reservation afterSettle = metering.reserve(user, "TRANSLATE", 1, key);
		assertThat(afterSettle.status()).isEqualTo(ReservationStatus.SETTLED);
		assertThat(afterSettle.replayed()).isTrue();
		assertCounter(counterOf(first.id()), 1, 0); // charged once

		// keys are per user
		UUID other = user("REGISTERED", "Africa/Addis_Ababa");
		assertThat(metering.reserve(other, "TRANSLATE", 1, key).replayed()).isFalse();
	}

	@Test
	void concurrentRequestsWithOneKeyReserveOnce() throws Exception {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		String key = key();
		List<Object> outcomes = parallel(12, i -> metering.reserve(user, "TRANSLATE", 1, key));
		List<Reservation> all = reservations(outcomes);
		assertThat(all).hasSize(12);
		assertThat(all).extracting(Reservation::id).containsOnly(all.get(0).id());
		assertThat(all.stream().filter(r -> !r.replayed())).hasSize(1);
		assertThat(count("SELECT count(*) FROM usage_reservations WHERE user_id = ?", user)).isOne();
		assertCounter(counterOf(all.get(0).id()), 0, 1);
	}

	@Test
	void aRefusedReserveStoresNothingSoItsKeyCanBeRetried() {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		metering.reserve(user, "TRANSLATE", 10, key());
		String key = key();
		assertThatThrownBy(() -> metering.reserve(user, "TRANSLATE", 1, key)).isInstanceOf(QuotaExceededException.class);
		assertThat(count("SELECT count(*) FROM usage_reservations WHERE idempotency_key = ?", key)).isZero();
		wallet.fund(user, BigDecimal.ONE);
		Reservation paid = metering.reserve(user, "TRANSLATE", 1, key);
		assertThat(paid.replayed()).isFalse();
		assertThat(paid.creditUnits()).isOne();
	}

	// ================================================================== expiry sweep

	@Test
	void expiredReservationsAreReleasedOnceAndLockedOnesAreSkipped() throws Exception {
		// the sweep is global: first release what earlier tests left open (they are finished)
		NOW.set(Instant.parse("2100-01-01T00:00:00Z"));
		engine.releaseExpired();
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		Instant t0 = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		NOW.set(t0);
		wallet.fund(user, BigDecimal.ONE);
		metering.reserve(user, "TRANSLATE", 10, key());
		Reservation withCredit = metering.reserve(user, "TRANSLATE", 1, key());
		assertThat(withCredit.creditUnits()).isOne();
		assertThat(withCredit.expiresAt()).isEqualTo(t0.plus(Duration.ofMinutes(15)));
		NOW.set(t0.plus(Duration.ofMinutes(10)));
		Reservation young = metering.reserve(user, "GRAMMAR_CORRECTION", 1, key());

		NOW.set(t0.plus(Duration.ofMinutes(15)).minusMillis(1));
		assertThat(engine.releaseExpired()).isZero();

		// another transaction holds one of the two expired reservations: the sweep skips it
		NOW.set(t0.plus(Duration.ofMinutes(16)));
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<?> holder = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
				jdbc.queryForObject("SELECT id FROM usage_reservations WHERE id = ? FOR UPDATE", UUID.class,
						withCredit.id());
				locked.countDown();
				try {
					done.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
			assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
			assertThat(engine.releaseExpired()).isEqualTo(1); // the other expired one
			done.countDown();
			holder.get(30, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertThat(engine.releaseExpired()).isEqualTo(1);
		assertThat(engine.releaseExpired()).as("idempotent").isZero();
		assertThat(status(withCredit.id())).isEqualTo("RELEASED");
		assertThat(reason(withCredit.id())).isEqualTo("EXPIRED");
		assertThat(status(young.id())).isEqualTo("RESERVED");
		assertThat(wallet.balance(user)).isEqualByComparingTo("1");
		assertCounter(counterOf(withCredit.id()), 0, 0);
		assertThat(events(withCredit.id())).containsExactly("RESERVE", "RELEASE");
		assertThatThrownBy(() -> metering.settle(withCredit.id(), 1)).isInstanceOf(ReservationClosedException.class);
		// a late settle of a reservation that expired but was not swept yet still works
		NOW.set(t0.plus(Duration.ofMinutes(30)));
		assertThat(metering.settle(young.id(), 1).status()).isEqualTo(ReservationStatus.SETTLED);
	}

	// ================================================================== windows in the user's time zone

	@Test
	void dailyWindowResetsAtMidnightInTheUsersZoneAcrossDst() {
		ZoneId ny = ZoneId.of("America/New_York");
		UUID user = user("REGISTERED", ny.getId());
		NOW.set(local("2026-10-31T23:59:59", ny));
		metering.reserve(user, "TRANSLATE", 10, key());
		QuotaExceededException q = quota(() -> metering.reserve(user, "TRANSLATE", 1, key()));
		assertThat(q.resetAt()).isEqualTo(local("2026-11-01T00:00", ny));
		NOW.set(local("2026-10-31T23:59:59.999999", ny));
		quota(() -> metering.reserve(user, "TRANSLATE", 1, key()));

		NOW.set(local("2026-11-01T00:00", ny)); // the 25-hour DST day
		Reservation next = metering.reserve(user, "TRANSLATE", 10, key());
		assertThat(next.window().start()).isEqualTo(local("2026-11-01T00:00", ny));
		assertThat(Duration.between(next.window().start(), next.window().end())).isEqualTo(Duration.ofHours(25));
		NOW.set(local("2026-11-01T23:59:59", ny));
		assertThat(quota(() -> metering.reserve(user, "TRANSLATE", 1, key())).resetAt())
				.isEqualTo(local("2026-11-02T00:00", ny));

		// the default zone of a new account is Africa/Addis_Ababa (V1); an invalid stored id falls back to it
		UUID broken = user("REGISTERED", "Not/AZone");
		NOW.set(Instant.parse("2026-11-18T20:59:59Z"));
		assertThat(metering.reserve(broken, "TRANSLATE", 1, key()).window().end())
				.isEqualTo(Instant.parse("2026-11-18T21:00:00Z"));
	}

	@Test
	void paidMonthlyWindowsFollowThePeriodStartAcrossMonthEnds() {
		ZoneId addis = ZoneId.of("Africa/Addis_Ababa");
		UUID user = user("REGISTERED", addis.getId());
		Instant periodStart = local("2026-01-31T10:00", addis);
		subscription(user, "PREMIUM", periodStart, local("2026-03-31T10:00", addis));

		NOW.set(local("2026-02-28T09:59:59", addis));
		Reservation all = metering.reserve(user, "TRANSLATE", 600, key()); // PREMIUM: 600/month
		assertThat(all.window()).isEqualTo(new UsageWindow(periodStart, local("2026-02-28T10:00", addis)));
		assertThat(quota(() -> metering.reserve(user, "TRANSLATE", 1, key())).resetAt())
				.isEqualTo(local("2026-02-28T10:00", addis));

		NOW.set(local("2026-02-28T10:00", addis));
		Reservation march = metering.reserve(user, "TRANSLATE", 1, key());
		assertThat(march.window())
				.isEqualTo(new UsageWindow(local("2026-02-28T10:00", addis), local("2026-03-31T10:00", addis)));

		// FREE (no paid period) uses calendar months for its monthly features
		UUID free = user("REGISTERED", addis.getId());
		assertThat(metering.reserve(free, "SPEECH_TO_TEXT", 1, key()).window())
				.isEqualTo(new UsageWindow(local("2026-02-01T00:00", addis), local("2026-03-01T00:00", addis)));
	}

	@Test
	void aFeatureOpenedForCreditsOnlyUsesTheLifetimeWindowWithoutAReset() {
		// FREE excludes TONE_ADJUST; an enabling override opens it with allowance 0 and reset NONE (B4.1)
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		UUID flag = jdbc.queryForObject("""
				INSERT INTO feature_flags (flag_code, feature_code, is_enabled) VALUES (?, 'TONE_ADJUST', TRUE)
				RETURNING id""", UUID.class, "tone-credits-" + user);
		jdbc.update("INSERT INTO user_feature_overrides (user_id, feature_flag_id, is_enabled) VALUES (?, ?, TRUE)",
				user, flag);
		QuotaExceededException none = quota(() -> metering.reserve(user, "TONE_ADJUST", 1, key()));
		assertThat(none.resetAt()).isNull();
		assertThat(none.extensions()).doesNotContainKey("resetAt");

		wallet.fund(user, new BigDecimal("2"));
		Reservation r = metering.reserve(user, "TONE_ADJUST", 2, key());
		assertThat(r.window()).isEqualTo(UsageWindow.LIFETIME);
		assertThat(r.allowanceUnits()).isZero();
		assertThat(r.creditUnits()).isEqualTo(2);
		Settlement s = metering.settle(r.id(), 2);
		assertThat(s.resetAt()).isNull();
		assertThat(s.remainingAllowance()).isZero();
		assertThat(s.usageSource()).isEqualTo(UsageSource.AI_CREDITS);
		assertCounter(counterOf(r.id()), 0, 0); // credits do not count against the (empty) allowance
	}

	// ================================================================== problems and refusals

	@Test
	void quotaExceededCarriesResetCreditsAndUpgradeOptions() {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		metering.reserve(user, "SPEECH_TO_TEXT", 600, key());
		wallet.fund(user, new BigDecimal("4.9"));
		jdbc.update("UPDATE billing_products SET product_status = 'ACTIVE' WHERE product_code IN ('PLAN_PREMIUM', 'CREDITS_500')");
		try {
			QuotaExceededException q = quota(() -> metering.reserve(user, "SPEECH_TO_TEXT", 30, key())); // 5.00001
			assertThat(q.code()).isEqualTo(ApiErrorCode.QUOTA_EXCEEDED);
			assertThat(q.status().value()).isEqualTo(429);
			assertThat(q.creditBalance()).isEqualByComparingTo("4");
			assertThat(q.upgradeOptions()).containsExactly(
					new QuotaExceededException.UpgradeOption("PLAN", "PLAN_PREMIUM"),
					new QuotaExceededException.UpgradeOption("CREDIT_PACK", "CREDITS_500"));
			Map<String, Object> members = q.extensions();
			assertThat(members).containsEntry("featureCode", "SPEECH_TO_TEXT")
					.containsEntry("resetAt", q.resetAt().toString()).containsKey("upgradeOptions");
			assertThat(q.detail()).doesNotContain(user.toString());

			// guests cannot buy: no options
			UUID guest = guest();
			metering.reserve(guest, "TRANSLATE", 3, key());
			assertThat(quota(() -> metering.reserve(guest, "TRANSLATE", 1, key())).upgradeOptions()).isEmpty();
		} finally {
			jdbc.update("UPDATE billing_products SET product_status = 'DRAFT' WHERE product_code IN ('PLAN_PREMIUM', 'CREDITS_500')");
		}
	}

	@Test
	void unentitledUnmeteredAndMalformedRequestsAreRefusedWithoutWriting() {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		assertThatThrownBy(() -> metering.reserve(user, "TONE_ADJUST", 1, key()))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.code()).isEqualTo(ApiErrorCode.FEATURE_NOT_ENTITLED));
		assertThatThrownBy(() -> metering.reserve(user, "DETECT_LANGUAGE", 1, key()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metering.reserve(user, "HISTORY_SYNC", 1, key()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metering.reserve(user, "NO_SUCH_FEATURE", 1, key()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metering.reserve(user, "TRANSLATE", 0, key()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metering.reserve(user, "TRANSLATE", 1, "k".repeat(129)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metering.settle(UUID.randomUUID(), 1))
				.isInstanceOf(java.util.NoSuchElementException.class);
		assertThat(count("SELECT count(*) FROM usage_reservations WHERE user_id = ?", user)).isZero();
		assertThat(count("SELECT count(*) FROM usage_events WHERE subject_id = ?", user)).isZero();
	}

	@Test
	void reservationRowsThatContradictTheirStatusAreRejectedByTheDatabase() {
		UUID user = user("REGISTERED", "Africa/Addis_Ababa");
		Reservation r = metering.reserve(user, "TRANSLATE", 1, key());
		assertThatThrownBy(() -> jdbc.update("UPDATE usage_reservations SET reservation_status = 'SETTLED' WHERE id = ?",
				r.id())).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE usage_reservations SET credit_units = 1 WHERE id = ?", r.id()))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				UPDATE usage_reservations SET reservation_status = 'SETTLED', actual_units = 1, settled_allowance_units = 1,
				    settled_credit_units = 0, credits_charged = 0, uncovered_units = 1, closed_at = now()
				WHERE id = ?""", r.id())).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE usage_events SET units = 2 WHERE id = ?", r.id()))
				.as("usage_events is append-only").isInstanceOf(Exception.class);
	}

	// ================================================================== guest merge carry-over (ADR 0010 open item)

	@Test
	void guestUsageCarriesIntoTheTargetsOwnWindowEvenWhenTheWindowsDiffer() {
		NOW.set(Instant.parse("2026-11-18T12:00:00Z"));
		UUID guest = guest();
		jdbc.update("UPDATE users SET time_zone = 'UTC' WHERE id = ?", guest);
		metering.settle(metering.reserve(guest, "TRANSLATE", 2, key()).id(), 2);
		Reservation inFlight = metering.reserve(guest, "TRANSLATE", 1, key());
		UUID target = user("REGISTERED", "America/New_York");
		metering.reserve(target, "TRANSLATE", 4, key());

		new TransactionTemplate(txManager)
				.executeWithoutResult(s -> guestMerge.mergeGuest(guest, target, NOW.get()));

		// the guest's UTC day overlaps the target's New York day but is not equal to it: 3 units land in the
		// target's window, the one the engine uses next
		UsageWindow ny = new UsageWindow(local("2026-11-18T00:00", ZoneId.of("America/New_York")),
				local("2026-11-19T00:00", ZoneId.of("America/New_York")));
		assertThat(count("SELECT count(*) FROM usage_counters WHERE user_id = ? AND feature_code = 'TRANSLATE'", target))
				.isOne();
		Reservation r = metering.reserve(target, "TRANSLATE", 3, key());
		assertThat(r.window()).isEqualTo(ny);
		assertCounter(counterOf(r.id()), 3, 7);
		quota(() -> metering.reserve(target, "TRANSLATE", 1, key()));
		// the guest's in-flight reservation still closes on the guest's own counter
		assertThat(metering.settle(inFlight.id(), 1).status()).isEqualTo(ReservationStatus.SETTLED);
		assertCounter(counterOf(r.id()), 3, 7);
	}

	// ================================================================== helpers

	interface Task {
		Object run(int i) throws Exception;
	}

	/** Runs n tasks at once (released together by a latch); returns each result or the exception it threw. */
	static List<Object> parallel(int n, Task task) throws InterruptedException, ExecutionException {
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Object>> futures = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				int index = i;
				Callable<Object> call = () -> {
					start.await();
					try {
						return task.run(index);
					} catch (Exception e) {
						return e;
					}
				};
				futures.add(pool.submit(call));
			}
			start.countDown();
			List<Object> results = new ArrayList<>();
			for (Future<Object> f : futures) {
				Object o = f.get(60, TimeUnit.SECONDS);
				if (o instanceof Exception e && !(e instanceof ApiException)) {
					throw new AssertionError("unexpected failure", e);
				}
				results.add(o);
			}
			return results;
		} catch (java.util.concurrent.TimeoutException e) {
			throw new AssertionError(e);
		} finally {
			pool.shutdownNow();
		}
	}

	static List<Reservation> reservations(List<Object> outcomes) {
		return outcomes.stream().filter(Reservation.class::isInstance).map(Reservation.class::cast).toList();
	}

	static QuotaExceededException quota(Callable<?> call) {
		try {
			call.call();
		} catch (QuotaExceededException e) {
			return e;
		} catch (Exception e) {
			throw new AssertionError("expected QUOTA_EXCEEDED", e);
		}
		throw new AssertionError("expected QUOTA_EXCEEDED");
	}

	static Instant local(String dateTime, ZoneId zone) {
		return LocalDateTime.parse(dateTime).atZone(zone).toInstant();
	}

	static String key() {
		return UUID.randomUUID().toString();
	}

	UUID user(String type, String timeZone) {
		return jdbc.queryForObject("""
				INSERT INTO users (account_type, account_status, time_zone) VALUES (?, 'ACTIVE', ?) RETURNING id""",
				UUID.class, type, timeZone);
	}

	UUID guest() {
		return user("GUEST", "Africa/Addis_Ababa");
	}

	void subscription(UUID user, String plan, Instant start, Instant end) {
		jdbc.update("""
				INSERT INTO user_subscriptions (user_id, plan_id, source_type, billing_mode, subscription_status,
				    current_period_start, current_period_end)
				VALUES (?, (SELECT id FROM subscription_plans WHERE plan_code = ?), 'PAYMENT', 'MANUAL_RENEW', 'ACTIVE',
				        ?, ?)""", user, plan, Timestamp.from(start), Timestamp.from(end));
	}

	UUID counterOf(UUID reservationId) {
		return jdbc.queryForObject("SELECT usage_counter_id FROM usage_reservations WHERE id = ?", UUID.class,
				reservationId);
	}

	long used(UUID counter) {
		return jdbc.queryForObject("SELECT used_units FROM usage_counters WHERE id = ?", Long.class, counter);
	}

	long reserved(UUID counter) {
		return jdbc.queryForObject("SELECT reserved_units FROM usage_counters WHERE id = ?", Long.class, counter);
	}

	void assertCounter(UUID counter, long used, long reserved) {
		assertThat(used(counter)).as("used_units").isEqualTo(used);
		assertThat(reserved(counter)).as("reserved_units").isEqualTo(reserved);
	}

	/** The counter equals what its reservations say (no carry-over involved). */
	void assertCounterMatchesReservations(UUID counter) {
		assertThat(reserved(counter)).isEqualTo(count("""
				SELECT coalesce(sum(allowance_units), 0) FROM usage_reservations
				WHERE usage_counter_id = ? AND reservation_status = 'RESERVED'""", counter));
		assertThat(used(counter)).isEqualTo(count("""
				SELECT coalesce(sum(settled_allowance_units), 0) FROM usage_reservations
				WHERE usage_counter_id = ? AND reservation_status = 'SETTLED'""", counter));
		assertThat(count("""
				SELECT count(*) FROM usage_reservations r
				WHERE usage_counter_id = ? AND reservation_status <> 'RESERVED'
				  AND (SELECT count(*) FROM usage_events e WHERE e.reservation_id = r.id) <> 1""", counter)).isZero();
	}

	long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	List<String> events(UUID reservationId) {
		return jdbc.queryForList("""
				SELECT event_type FROM usage_events WHERE id = ? OR reservation_id = ?
				ORDER BY CASE event_type WHEN 'RESERVE' THEN 0 ELSE 1 END""",
				String.class, reservationId, reservationId);
	}

	String status(UUID reservationId) {
		return jdbc.queryForObject("SELECT reservation_status FROM usage_reservations WHERE id = ?", String.class,
				reservationId);
	}

	String reason(UUID reservationId) {
		return jdbc.queryForObject("SELECT release_reason FROM usage_reservations WHERE id = ?", String.class,
				reservationId);
	}
}
