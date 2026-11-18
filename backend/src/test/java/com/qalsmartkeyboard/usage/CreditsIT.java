package com.qalsmartkeyboard.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * B4.3 "done when": credit lots on PostgreSQL 18 (V1 tables and triggers): FIFO by expiry across lots, a hold spanning
 * lots, settle returning credit to the lots it came from, the expiry boundary and job, parallel holds that never
 * overspend, the append-only ledger, refunds/adjustments, and the metering engine paying with real lots.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s" })
@Testcontainers
@Import(CreditsIT.Fixtures.class)
class CreditsIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
	static final AtomicReference<Instant> NOW = new AtomicReference<>(T0);

	@TestConfiguration(proxyBeanMethods = false)
	static class Fixtures {
		@Bean
		@Primary
		Clock creditsTestClock() {
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
					return NOW.get();
				}
			};
		}
	}

	@Autowired
	JdbcCredits credits;
	@Autowired
	Metering metering;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager txManager;

	@AfterEach
	void resetClock() {
		NOW.set(T0);
	}

	// ================================================================== grant

	@Test
	void grantCreatesALotAndALedgerRowAndIsIdempotentBySourceReference() {
		UUID user = user();
		Instant expires = T0.plusSeconds(86_400);
		CreditLot lot = credits.grant(user, new BigDecimal("10"), CreditSource.GRANT, "welcome-1", expires);
		assertThat(lot.created()).isTrue();
		assertThat(lot.remainingUnits()).isEqualByComparingTo("10");

		CreditLot again = credits.grant(user, new BigDecimal("10.000000"), CreditSource.GRANT, "welcome-1", expires);
		assertThat(again.created()).isFalse();
		assertThat(again.id()).isEqualTo(lot.id());
		assertThat(count("SELECT count(*) FROM ai_credit_lots l JOIN ai_credit_accounts a ON a.id = l.credit_account_id "
				+ "WHERE a.user_id = ?", user)).isOne();
		assertThat(rows(user)).containsExactly("GRANT 10.000000 10.000000");
		assertThat(credits.balance(user, T0).exact()).isEqualByComparingTo("10");

		assertThatThrownBy(() -> credits.grant(user, new BigDecimal("11"), CreditSource.GRANT, "welcome-1", expires))
				.isInstanceOf(IllegalArgumentException.class);
		// another source with the same reference is another lot
		assertThat(credits.grant(user, new BigDecimal("1"), CreditSource.PROMOTION, "welcome-1", null).created())
				.isTrue();
		assertThat(jdbc.queryForList("SELECT transaction_type FROM ai_credit_transactions t JOIN ai_credit_accounts a "
				+ "ON a.id = t.credit_account_id WHERE a.user_id = ? ORDER BY t.created_at, t.id", String.class, user))
				.containsExactly("GRANT", "GRANT");
	}

	@Test
	void parallelGrantsWithOneReferenceCreateOneLot() throws Exception {
		UUID user = user();
		List<Object> out = parallel(8, i -> credits.grant(user, new BigDecimal("5"), CreditSource.GRANT, "same", null));
		assertThat(out).allSatisfy(o -> assertThat(o).isInstanceOf(CreditLot.class));
		assertThat(out.stream().filter(o -> ((CreditLot) o).created())).hasSize(1);
		assertThat(credits.balance(user, T0).exact()).isEqualByComparingTo("5");
	}

	@Test
	void grantValidatesItsInput() {
		UUID user = user();
		assertThatThrownBy(() -> credits.grant(user, BigDecimal.ZERO, CreditSource.GRANT, "a", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> credits.grant(user, new BigDecimal("1.0000001"), CreditSource.GRANT, "a", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> credits.grant(user, BigDecimal.ONE, CreditSource.GRANT, " ", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> credits.grant(user, BigDecimal.ONE, CreditSource.GRANT, "a", T0))
				.isInstanceOf(IllegalArgumentException.class);
		// V1: a PURCHASE lot references its payment order
		assertThatThrownBy(() -> credits.grant(user, BigDecimal.ONE, CreditSource.PURCHASE, "not-a-uuid", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> credits.grant(user, BigDecimal.ONE, CreditSource.PURCHASE, UUID.randomUUID().toString(), null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(credits.balance(user, T0).exact()).isEqualByComparingTo("0");
	}

	// ================================================================== FIFO

	@Test
	void holdConsumesTheEarliestExpiryFirstThenGrantTimeThenNeverExpiring() {
		UUID user = user();
		NOW.set(T0);
		CreditLot late = credits.grant(user, new BigDecimal("5"), CreditSource.GRANT, "late", T0.plusSeconds(3000));
		CreditLot never = credits.grant(user, new BigDecimal("5"), CreditSource.GRANT, "never", null);
		CreditLot early = credits.grant(user, new BigDecimal("5"), CreditSource.GRANT, "early", T0.plusSeconds(1000));
		NOW.set(T0.plusSeconds(10));
		CreditLot tieB = credits.grant(user, new BigDecimal("5"), CreditSource.GRANT, "tie-b", T0.plusSeconds(2000));
		NOW.set(T0.plusSeconds(5));
		CreditLot tieA = credits.grant(user, new BigDecimal("5"), CreditSource.GRANT, "tie-a", T0.plusSeconds(2000));
		NOW.set(T0.plusSeconds(20));

		// 5 credits drain the early lot only; then the 2000 lots by grant time (tie-a before tie-b), 'late', 'never'
		assertThat(hold(user, "2")).isTrue();
		assertThat(remaining(early)).isEqualByComparingTo("3");
		assertThat(hold(user, "3")).isTrue();
		assertThat(remaining(early)).isEqualByComparingTo("0");
		assertThat(hold(user, "6")).isTrue();
		assertThat(remaining(tieA)).isEqualByComparingTo("0");
		assertThat(remaining(tieB)).isEqualByComparingTo("4");
		assertThat(hold(user, "4")).isTrue();
		assertThat(hold(user, "1")).isTrue();
		assertThat(remaining(late)).isEqualByComparingTo("4");
		assertThat(remaining(never)).isEqualByComparingTo("5");
		assertThat(hold(user, "9")).isTrue();
		assertThat(remaining(late)).isEqualByComparingTo("0");
		assertThat(remaining(never)).isEqualByComparingTo("0");
		assertThat(credits.balance(user).signum()).isZero();
		assertThat(hold(user, "0.000001")).isFalse();
		assertLedgerConsistent(user);
	}

	@Test
	void aHoldSpanningSeveralLotsIsAllOrNothing() {
		UUID user = user();
		CreditLot a = credits.grant(user, new BigDecimal("1.5"), CreditSource.GRANT, "a", T0.plusSeconds(100));
		CreditLot b = credits.grant(user, new BigDecimal("2.25"), CreditSource.GRANT, "b", T0.plusSeconds(200));
		CreditLot c = credits.grant(user, new BigDecimal("3"), CreditSource.GRANT, "c", T0.plusSeconds(300));

		UUID res = UUID.randomUUID();
		assertThat(inTx(() -> credits.hold(user, res, new BigDecimal("5.000000")))).isTrue();
		assertThat(remaining(a)).isEqualByComparingTo("0");
		assertThat(remaining(b)).isEqualByComparingTo("0");
		assertThat(remaining(c)).isEqualByComparingTo("1.75");
		assertThat(credits.balance(user)).isEqualByComparingTo("1.75");

		UUID tooMuch = UUID.randomUUID();
		assertThat(inTx(() -> credits.hold(user, tooMuch, new BigDecimal("1.750001")))).isFalse();
		assertThat(remaining(c)).isEqualByComparingTo("1.75"); // nothing changed
		assertThat(count("SELECT count(*) FROM ai_credit_transactions WHERE ai_request_id = ?", tooMuch)).isZero();
		assertThat(consumption(res)).containsExactly("-1.500000", "-2.250000", "-1.250000");
		assertLedgerConsistent(user);
	}

	@Test
	void aUserWithoutAnAccountHasNothingToHold() {
		UUID user = user();
		assertThat(inTx(() -> credits.hold(user, UUID.randomUUID(), BigDecimal.ONE))).isFalse();
		assertThat(credits.balance(user)).isEqualByComparingTo("0");
		assertThat(credits.balance(user, T0).display()).isZero();
	}

	@Test
	void theWalletOnlyWorksInsideATransaction() {
		UUID user = user();
		credits.grant(user, BigDecimal.TEN, CreditSource.GRANT, "x", null);
		assertThatThrownBy(() -> credits.hold(user, UUID.randomUUID(), BigDecimal.ONE))
				.isInstanceOf(IllegalStateException.class);
	}

	// ================================================================== settle returns to the same lots

	@Test
	void settleReturnsTheUnusedPartToTheLotsItCameFromLatestExpiryFirst() {
		UUID user = user();
		CreditLot a = credits.grant(user, new BigDecimal("2"), CreditSource.GRANT, "a", T0.plusSeconds(100));
		CreditLot b = credits.grant(user, new BigDecimal("3"), CreditSource.GRANT, "b", T0.plusSeconds(200));
		CreditLot c = credits.grant(user, new BigDecimal("4"), CreditSource.GRANT, "c", T0.plusSeconds(300));

		UUID res = UUID.randomUUID();
		assertThat(inTx(() -> credits.hold(user, res, new BigDecimal("6")))).isTrue(); // a:2, b:3, c:1
		assertThat(remaining(c)).isEqualByComparingTo("3");

		// charged 2.5 of 6: the earliest lots keep paying (a 2, b 0.5); the unused 3.5 goes back b 2.5, c 1
		inTx(() -> {
			credits.settle(user, res, new BigDecimal("6"), new BigDecimal("2.5"));
			return null;
		});
		assertThat(remaining(a)).isEqualByComparingTo("0");
		assertThat(remaining(b)).isEqualByComparingTo("2.5");
		assertThat(remaining(c)).isEqualByComparingTo("4");
		assertThat(credits.balance(user)).isEqualByComparingTo("6.5");
		assertLedgerConsistent(user);

		// the same call again changes nothing
		inTx(() -> {
			credits.settle(user, res, new BigDecimal("6"), new BigDecimal("2.5"));
			return null;
		});
		assertThat(credits.balance(user)).isEqualByComparingTo("6.5");
		assertThat(count("SELECT count(*) FROM ai_credit_transactions WHERE ai_request_id = ?", res)).isEqualTo(5);

		// FIFO order is preserved: the next hold of 3 takes b's 2.5 and then c
		assertThat(hold(user, "3")).isTrue();
		assertThat(remaining(b)).isEqualByComparingTo("0");
		assertThat(remaining(c)).isEqualByComparingTo("3.5");
	}

	@Test
	void releasingEverythingRestoresEveryLotAndOverSettleHoldsAgain() {
		UUID user = user();
		CreditLot a = credits.grant(user, new BigDecimal("2"), CreditSource.GRANT, "a", T0.plusSeconds(100));
		CreditLot b = credits.grant(user, new BigDecimal("3"), CreditSource.GRANT, "b", T0.plusSeconds(200));
		UUID res = UUID.randomUUID();
		assertThat(inTx(() -> credits.hold(user, res, new BigDecimal("4")))).isTrue();
		inTx(() -> {
			credits.settle(user, res, new BigDecimal("4"), BigDecimal.ZERO);
			return null;
		});
		assertThat(remaining(a)).isEqualByComparingTo("2");
		assertThat(remaining(b)).isEqualByComparingTo("3");

		// a second reservation: hold, hold again (over-settle), settle everything charged
		UUID res2 = UUID.randomUUID();
		assertThat(inTx(() -> credits.hold(user, res2, new BigDecimal("2")))).isTrue();
		assertThat(inTx(() -> credits.hold(user, res2, new BigDecimal("1")))).isTrue();
		inTx(() -> {
			credits.settle(user, res2, new BigDecimal("3"), new BigDecimal("3"));
			return null;
		});
		assertThat(remaining(a)).isEqualByComparingTo("0");
		assertThat(remaining(b)).isEqualByComparingTo("2");
		assertThat(credits.balance(user)).isEqualByComparingTo("2");
		assertLedgerConsistent(user);

		assertThatThrownBy(() -> inTx(() -> {
			credits.settle(user, res2, new BigDecimal("1"), BigDecimal.ZERO);
			return null;
		})).isInstanceOf(IllegalStateException.class); // not what the reservation holds
	}

	// ================================================================== expiry

	@Test
	void aLotStopsCountingAtItsExpiryInstant() {
		UUID user = user();
		Instant end = T0.plusSeconds(1000);
		CreditLot lot = credits.grant(user, new BigDecimal("7.9"), CreditSource.GRANT, "e", end);
		assertThat(credits.balance(user, end.minusNanos(1_000)).exact()).isEqualByComparingTo("7.9");
		assertThat(credits.balance(user, end.minusNanos(1_000)).display()).isEqualTo(7); // rounded down
		assertThat(credits.balance(user, end).exact()).isEqualByComparingTo("0");
		assertThat(credits.balance(user, end.plusSeconds(1)).exact()).isEqualByComparingTo("0");

		NOW.set(end.minusSeconds(1));
		assertThat(hold(user, "1")).isTrue();
		NOW.set(end);
		assertThat(hold(user, "1")).isFalse(); // expired at the instant, even before the job ran
		assertThat(credits.balance(user)).isEqualByComparingTo("0");
		assertThat(remaining(lot)).isEqualByComparingTo("6.9"); // not swept yet
	}

	@Test
	void theExpiryJobWritesOneExpiryRowPerLotAndIsIdempotent() throws Exception {
		UUID user = user();
		Instant end = T0.plusSeconds(1000);
		CreditLot spent = credits.grant(user, new BigDecimal("1"), CreditSource.GRANT, "spent", end);
		CreditLot part = credits.grant(user, new BigDecimal("4"), CreditSource.GRANT, "part", end.plusSeconds(10));
		CreditLot kept = credits.grant(user, new BigDecimal("2"), CreditSource.GRANT, "kept", null);
		assertThat(hold(user, "2.5")).isTrue(); // spent 1, part 1.5

		// (the database is shared with other tests, so the job's return values are only checked after a full run)
		NOW.set(end.minusSeconds(1));
		credits.expireDue();
		assertThat(remaining(part)).isEqualByComparingTo("2.5");
		NOW.set(end.plusSeconds(60));
		credits.expireDue(); // `part` has 2.5 left and ended; `spent` is empty and gets no row
		assertThat(credits.expireDue()).isZero(); // nothing left to expire anywhere
		assertThat(remaining(part)).isEqualByComparingTo("0");
		assertThat(remaining(spent)).isEqualByComparingTo("0");
		assertThat(remaining(kept)).isEqualByComparingTo("2");
		assertThat(jdbc.queryForList("SELECT delta_units::text FROM ai_credit_transactions WHERE transaction_type = 'EXPIRY' "
				+ "AND lot_id = ?", String.class, part.id())).containsExactly("-2.500000");
		assertThat(count("SELECT count(*) FROM ai_credit_transactions WHERE transaction_type = 'EXPIRY' AND lot_id = ?",
				spent.id())).isZero();
		assertThat(accountBalance(user)).isEqualByComparingTo("2");
		assertLedgerConsistent(user);

		// two instances running the job at once expire a lot once
		UUID other = user();
		credits.grant(other, new BigDecimal("3"), CreditSource.GRANT, "x", end.plusSeconds(100));
		NOW.set(end.plusSeconds(200));
		List<Object> out = parallel(6, i -> credits.expireDue());
		assertThat(out).noneMatch(o -> o instanceof Throwable);
		assertThat(count("SELECT count(*) FROM ai_credit_transactions t JOIN ai_credit_accounts a ON a.id = t.credit_account_id "
				+ "WHERE a.user_id = ? AND t.transaction_type = 'EXPIRY'", other)).isOne();
	}

	@Test
	void creditReturnedToALotThatExpiredMeanwhileIsExpiredByTheNextRun() {
		UUID user = user();
		Instant end = T0.plusSeconds(1000);
		CreditLot lot = credits.grant(user, new BigDecimal("5"), CreditSource.GRANT, "l", end);
		UUID res = UUID.randomUUID();
		NOW.set(end.minusSeconds(5));
		assertThat(inTx(() -> credits.hold(user, res, new BigDecimal("5")))).isTrue();
		NOW.set(end.plusSeconds(5));
		inTx(() -> {
			credits.settle(user, res, new BigDecimal("5"), new BigDecimal("2"));
			return null;
		});
		assertThat(credits.balance(user)).isEqualByComparingTo("0"); // expired, not spendable
		assertThat(credits.expireDue()).isPositive();
		assertThat(remaining(lot)).isEqualByComparingTo("0");
		assertThat(count("SELECT count(*) FROM ai_credit_transactions WHERE lot_id = ? AND transaction_type = 'EXPIRY'",
				lot.id())).isOne();
		assertLedgerConsistent(user);
	}

	// ================================================================== concurrency

	@Test
	void parallelHoldsNeverOverspendAcrossLots() throws Exception {
		UUID user = user();
		for (int i = 0; i < 4; i++) {
			credits.grant(user, new BigDecimal("2.5"), CreditSource.GRANT, "lot" + i, T0.plusSeconds(1000L * (i + 1)));
		}
		// 10 credits, 40 racing holds of 0.75: exactly 13 succeed (9.75), 0.25 stays
		List<Object> out = parallel(40, i -> inTx(() -> credits.hold(user, UUID.randomUUID(), new BigDecimal("0.75"))));
		assertThat(out.stream().filter(Boolean.TRUE::equals)).hasSize(13);
		assertThat(credits.balance(user)).isEqualByComparingTo("0.25");
		assertThat(accountBalance(user)).isEqualByComparingTo("0.25");
		assertThat(jdbc.queryForObject("SELECT min(l.remaining_units) FROM ai_credit_lots l JOIN ai_credit_accounts a "
				+ "ON a.id = l.credit_account_id WHERE a.user_id = ?", BigDecimal.class, user).signum())
				.isGreaterThanOrEqualTo(0);
		assertLedgerConsistent(user);
	}

	@Test
	void holdsSettlesGrantsAndTheExpiryJobRacingLeaveConsistentBooks() throws Exception {
		UUID user = user();
		credits.grant(user, new BigDecimal("20"), CreditSource.GRANT, "base", T0.plusSeconds(500));
		credits.grant(user, new BigDecimal("20"), CreditSource.GRANT, "base2", T0.plusSeconds(900));
		List<Object> out = parallel(30, i -> {
			if (i % 10 == 9) {
				return credits.grant(user, new BigDecimal("1"), CreditSource.GRANT, "extra" + i, null);
			}
			if (i % 10 == 8) {
				return credits.expireDue();
			}
			UUID res = UUID.randomUUID();
			boolean ok = inTx(() -> credits.hold(user, res, new BigDecimal("3")));
			if (ok) {
				inTx(() -> {
					credits.settle(user, res, new BigDecimal("3"), new BigDecimal(i % 2 == 0 ? "3" : "1.5"));
					return null;
				});
			}
			return ok;
		});
		assertThat(out).noneMatch(o -> o instanceof Throwable);
		assertThat(credits.balance(user).signum()).isGreaterThanOrEqualTo(0);
		assertLedgerConsistent(user);
	}

	// ================================================================== ledger

	@Test
	void ledgerRowsCannotBeUpdatedOrDeleted() {
		UUID user = user();
		credits.grant(user, BigDecimal.TEN, CreditSource.GRANT, "l", null);
		assertThatThrownBy(() -> jdbc.update("UPDATE ai_credit_transactions SET description = 'x' WHERE lot_id IN "
				+ "(SELECT l.id FROM ai_credit_lots l JOIN ai_credit_accounts a ON a.id = l.credit_account_id WHERE a.user_id = ?)",
				user)).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbc.update("DELETE FROM ai_credit_transactions WHERE lot_id IN "
				+ "(SELECT l.id FROM ai_credit_lots l JOIN ai_credit_accounts a ON a.id = l.credit_account_id WHERE a.user_id = ?)",
				user)).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
		assertThat(rows(user)).hasSize(1);
	}

	// ================================================================== refunds and adjustments

	@Test
	void reverseTakesBackTheUnconsumedRemainderOnceAndNeverGoesNegative() {
		UUID user = user();
		CreditLot pack = credits.grant(user, new BigDecimal("10"), CreditSource.PROMOTION, "pack-1", T0.plusSeconds(5000));
		assertThat(hold(user, "4")).isTrue();
		assertThat(credits.reverse(user, CreditSource.PROMOTION, "pack-1")).isEqualByComparingTo("6");
		assertThat(remaining(pack)).isEqualByComparingTo("0");
		assertThat(credits.reverse(user, CreditSource.PROMOTION, "pack-1")).isEqualByComparingTo("0");
		assertThat(credits.reverse(user, CreditSource.PROMOTION, "unknown")).isEqualByComparingTo("0");
		assertThat(credits.reverse(user(), CreditSource.PROMOTION, "pack-1")).isEqualByComparingTo("0");
		assertThat(accountBalance(user)).isEqualByComparingTo("0");
		assertThat(jdbc.queryForList("SELECT transaction_type || ' ' || delta_units FROM ai_credit_transactions "
				+ "WHERE lot_id = ? ORDER BY created_at, delta_units DESC", String.class, pack.id()))
				.containsExactly("GRANT 10.000000", "CONSUMPTION -4.000000", "REVERSAL -6.000000");
		assertLedgerConsistent(user);
	}

	@Test
	void refundAndAdjustAddAndRemoveCreditWithTheMatchingLedgerTypes() {
		UUID user = user();
		CreditLot refund = credits.grant(user, new BigDecimal("3"), CreditSource.REFUND, "svc-fail-1", T0.plusSeconds(9000));
		NOW.set(T0.plusSeconds(1));
		CreditLot adj = credits.grant(user, new BigDecimal("4"), CreditSource.ADJUSTMENT, "fix-1", null);
		NOW.set(T0.plusSeconds(2)); // grant time orders the never-expiring lots
		assertThat(jdbc.queryForList("SELECT transaction_type FROM ai_credit_transactions WHERE lot_id IN (?, ?) "
				+ "ORDER BY transaction_type", String.class, refund.id(), adj.id())).containsExactly("ADJUSTMENT", "REFUND");

		CreditLot base = credits.grant(user, new BigDecimal("10"), CreditSource.GRANT, "base", null);
		assertThat(hold(user, "6")).isTrue(); // refund 3, adj 3
		assertThat(credits.adjust(user, base.id(), new BigDecimal("-4"), "support-1", "goodwill removal"))
				.isEqualByComparingTo("-4");
		assertThat(credits.adjust(user, base.id(), new BigDecimal("-4"), "support-1", "goodwill removal"))
				.isEqualByComparingTo("0"); // replay
		assertThat(remaining(base)).isEqualByComparingTo("6");
		assertThat(credits.adjust(user, base.id(), new BigDecimal("4"), "support-2", null)).isEqualByComparingTo("4");
		assertThatThrownBy(() -> credits.adjust(user, base.id(), new BigDecimal("0.000001"), "over", null))
				.isInstanceOf(IllegalArgumentException.class); // above initial
		assertThatThrownBy(() -> credits.adjust(user, base.id(), new BigDecimal("-10.5"), "under", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> credits.adjust(user(), base.id(), BigDecimal.ONE, "other-user", null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(accountBalance(user)).isEqualByComparingTo("11");
		assertLedgerConsistent(user);
	}

	// ================================================================== metering with real lots

	@Test
	void meteringPaysFromTheEarliestExpiringLotsAndReturnsTheUnusedPart() {
		UUID user = user();
		CreditLot early = credits.grant(user, new BigDecimal("2"), CreditSource.GRANT, "early", T0.plusSeconds(1000));
		CreditLot late = credits.grant(user, new BigDecimal("5"), CreditSource.GRANT, "late", T0.plusSeconds(2000));

		// FREE: 10 TRANSLATE/day; 14 requested = 10 allowance + 4 credits (early 2, late 2)
		Reservation r = metering.reserve(user, "TRANSLATE", 14, UUID.randomUUID().toString());
		assertThat(r.creditUnits()).isEqualTo(4);
		assertThat(remaining(early)).isEqualByComparingTo("0");
		assertThat(remaining(late)).isEqualByComparingTo("3");

		// only 12 used: 2 allowance + ... allowance first, so credits used = 2 (the early lot's), 2 return to `late`
		Settlement s = metering.settle(r.id(), 12);
		assertThat(s.creditsCharged()).isEqualByComparingTo("2");
		assertThat(remaining(early)).isEqualByComparingTo("0");
		assertThat(remaining(late)).isEqualByComparingTo("5");
		assertThat(s.creditBalance()).isEqualByComparingTo("5");

		// the same user is out of allowance now; a release returns everything
		Reservation r2 = metering.reserve(user, "TRANSLATE", 3, UUID.randomUUID().toString());
		assertThat(r2.creditUnits()).isEqualTo(3);
		assertThat(remaining(late)).isEqualByComparingTo("2");
		metering.release(r2.id(), ReleaseReason.FAILED);
		assertThat(remaining(late)).isEqualByComparingTo("5");

		// a lot that ends keeps the engine from spending it
		NOW.set(T0.plusSeconds(2000));
		assertThatThrownBy(() -> metering.reserve(user, "TRANSLATE", 1, UUID.randomUUID().toString()))
				.isInstanceOf(QuotaExceededException.class);
		assertLedgerConsistent(user);
	}

	// ================================================================== helpers

	private UUID user() {
		return jdbc.queryForObject("INSERT INTO users (account_type, account_status) VALUES ('REGISTERED', 'ACTIVE') "
				+ "RETURNING id", UUID.class);
	}

	private boolean hold(UUID user, String amount) {
		return inTx(() -> credits.hold(user, UUID.randomUUID(), new BigDecimal(amount)));
	}

	private <T> T inTx(java.util.function.Supplier<T> work) {
		return new TransactionTemplate(txManager).execute(status -> work.get());
	}

	private BigDecimal remaining(CreditLot lot) {
		return jdbc.queryForObject("SELECT remaining_units FROM ai_credit_lots WHERE id = ?", BigDecimal.class, lot.id());
	}

	private BigDecimal accountBalance(UUID user) {
		return jdbc.queryForObject("SELECT balance_units FROM ai_credit_accounts WHERE user_id = ?", BigDecimal.class,
				user);
	}

	private long count(String sql, Object arg) {
		return jdbc.queryForObject(sql, Long.class, arg);
	}

	private List<String> rows(UUID user) {
		return jdbc.queryForList("SELECT t.transaction_type || ' ' || t.delta_units || ' ' || t.balance_after "
				+ "FROM ai_credit_transactions t JOIN ai_credit_accounts a ON a.id = t.credit_account_id "
				+ "WHERE a.user_id = ? ORDER BY t.created_at, t.id", String.class, user);
	}

	private List<String> consumption(UUID reservation) {
		return jdbc.queryForList("SELECT t.delta_units::text FROM ai_credit_transactions t JOIN ai_credit_lots l "
				+ "ON l.id = t.lot_id WHERE t.ai_request_id = ? AND t.transaction_type = 'CONSUMPTION' "
				+ "ORDER BY l.expires_at", String.class, reservation);
	}

	/** Lot remainders = initial + sum of ledger deltas per lot, account balance = sum of remainders, nothing negative. */
	private void assertLedgerConsistent(UUID user) {
		assertThat(jdbc.queryForList("""
				SELECT l.id::text FROM ai_credit_lots l JOIN ai_credit_accounts a ON a.id = l.credit_account_id
				WHERE a.user_id = ?
				  AND l.remaining_units <> (SELECT COALESCE(sum(t.delta_units), 0) FROM ai_credit_transactions t WHERE t.lot_id = l.id)
				""", String.class, user)).isEmpty();
		assertThat(accountBalance(user)).isEqualByComparingTo(jdbc.queryForObject("""
				SELECT COALESCE(sum(l.remaining_units), 0) FROM ai_credit_lots l
				JOIN ai_credit_accounts a ON a.id = l.credit_account_id WHERE a.user_id = ?""", BigDecimal.class, user));
	}

	private List<Object> parallel(int n, Task task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Object>> futures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int index = i;
			futures.add(pool.submit(() -> {
				start.await();
				try {
					return task.run(index);
				} catch (Exception e) {
					return e;
				}
			}));
		}
		start.countDown();
		List<Object> out = new ArrayList<>();
		for (Future<Object> f : futures) {
			out.add(f.get(60, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return out;
	}

	@FunctionalInterface
	private interface Task {
		Object run(int index) throws Exception;
	}
}
