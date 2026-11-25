package com.qalsmartkeyboard.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credit lots on {@code ai_credit_accounts} / {@code ai_credit_lots} / {@code ai_credit_transactions} (V1; ADR 0011,
 * "Credits (B4.3)"), serving as the {@link Credits} API and the metering engine's {@link CreditWallet}.
 *
 * <p><b>Locking</b>: every mutation locks the account row first ({@code FOR UPDATE}), then the lots it touches in the
 * order FIFO uses ({@code expires_at NULLS LAST, created_at, id}). All writers (hold, settle, grant, reverse, adjust,
 * expiry) follow it, so they serialise per account without deadlocks, and with the engine's order (reservation, counter,
 * wallet) the wallet is always last.
 *
 * <p><b>Ledger</b>: rows are only ever inserted (the V1 trigger rejects UPDATE and DELETE). Lot remainders and the
 * account's {@code balance_units} (sum of all remainders, expired-but-unswept included) are updated with them, in the
 * same transaction; {@code balance_after} is that account balance after the row.
 */
final class JdbcCredits implements Credits, CreditWallet {

	private static final Logger log = LoggerFactory.getLogger(JdbcCredits.class);

	private static final int SCALE = 6;
	private static final String CURRENCY = "AI_CREDIT";
	private static final String FIFO = "ORDER BY expires_at NULLS LAST, created_at, id";
	private static final String LOT_COLUMNS = "SELECT id, initial_units, remaining_units, expires_at, created_at "
			+ "FROM ai_credit_lots ";

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final int batchSize;

	JdbcCredits(JdbcClient jdbc, TransactionTemplate tx, Clock clock, int batchSize) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.clock = clock;
		this.batchSize = batchSize;
	}

	private record Account(UUID id, BigDecimal balance) {
	}

	private record Lot(UUID id, BigDecimal initial, BigDecimal remaining, Instant expiresAt, Instant createdAt) {
	}

	private record Drawn(UUID lotId, BigDecimal units) {
	}

	// ================================================================== Credits

	@Override
	public CreditLot grant(UUID userId, BigDecimal units, CreditSource source, String sourceRef, Instant expiresAt) {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(source, "source");
		positive(units, "units");
		if (sourceRef == null || sourceRef.isBlank() || sourceRef.length() > 100) {
			throw new IllegalArgumentException("sourceRef must be 1-100 characters");
		}
		UUID paymentOrder = source == CreditSource.PURCHASE ? parseUuid(sourceRef) : null;
		return grantLot(userId, units, source, sourceRef, expiresAt, paymentOrder, null);
	}

	@Override
	public CreditLot grantStorePurchase(UUID userId, BigDecimal units, UUID storePurchaseId, Instant expiresAt) {
		Objects.requireNonNull(storePurchaseId, "storePurchaseId");
		return grantLot(userId, units, CreditSource.PURCHASE, storeRef(storePurchaseId), expiresAt, null,
				storePurchaseId);
	}

	/** A PURCHASE lot carries exactly one backing: {@code paymentOrder} or {@code storePurchase} (V32). */
	private CreditLot grantLot(UUID userId, BigDecimal units, CreditSource source, String sourceRef, Instant expiresAt,
			UUID paymentOrder, UUID storePurchase) {
		Objects.requireNonNull(userId, "userId");
		BigDecimal amount = positive(units, "units");
		return tx.execute(status -> {
			Instant now = clock.instant();
			Account account = lockOrCreateAccount(userId);
			UUID lotId = lotId(userId, source, sourceRef);
			Optional<Lot> existing = findLot(lotId, account.id(), false);
			if (existing.isPresent()) {
				Lot lot = existing.get();
				if (lot.initial().compareTo(amount) != 0 || !Objects.equals(lot.expiresAt(), expiresAt)) {
					throw new IllegalArgumentException("Credit grant " + source + "/" + sourceRef
							+ " already exists with other units or expiry");
				}
				return lot(lot, userId, source, false);
			}
			if (expiresAt != null && !expiresAt.isAfter(now)) {
				throw new IllegalArgumentException("A credit lot must expire after the grant time");
			}
			jdbc.sql("""
					INSERT INTO ai_credit_lots (id, credit_account_id, lot_source, initial_units, remaining_units,
					                            expires_at, payment_order_id, store_purchase_id, created_at, updated_at)
					VALUES (:id, :account, :source, :units, :units, :expires, :order, :store, :now, :now)
					""").param("id", lotId).param("account", account.id()).param("source", source.name())
					.param("units", amount).param("expires", expiresAt == null ? null : Timestamp.from(expiresAt))
					.param("order", paymentOrder).param("store", storePurchase).param("now", Timestamp.from(now))
					.update();
			BigDecimal after = account.balance().add(amount);
			insertTx(account.id(), lotId, null, paymentOrder, openingType(source), amount, after,
					"grant:" + source + ":" + sourceRef, source + " " + sourceRef, now);
			setBalance(account.id(), after, now);
			return lot(new Lot(lotId, amount, amount, expiresAt, now), userId, source, true);
		});
	}

	@Override
	public CreditBalance balance(UUID userId, Instant at) {
		BigDecimal exact = jdbc.sql("""
				SELECT COALESCE(sum(l.remaining_units), 0)
				FROM ai_credit_lots l JOIN ai_credit_accounts a ON a.id = l.credit_account_id
				WHERE a.user_id = :user AND a.currency_unit = :currency AND l.remaining_units > 0
				  AND (l.expires_at IS NULL OR l.expires_at > :at)
				""").param("user", userId).param("currency", CURRENCY).param("at", Timestamp.from(at))
				.query(BigDecimal.class).single().setScale(SCALE, RoundingMode.UNNECESSARY);
		return new CreditBalance(exact, exact.setScale(0, RoundingMode.FLOOR).longValueExact());
	}

	@Override
	public BigDecimal reverse(UUID userId, CreditSource source, String sourceRef) {
		// an order reference that is not a UUID can never have been granted: its lot is simply not found
		UUID paymentOrder = null;
		if (source == CreditSource.PURCHASE) {
			try {
				paymentOrder = UUID.fromString(sourceRef);
			}
			catch (IllegalArgumentException e) {
				paymentOrder = null;
			}
		}
		return reverseLot(userId, source, sourceRef, paymentOrder);
	}

	@Override
	public BigDecimal reverseStorePurchase(UUID userId, UUID storePurchaseId) {
		Objects.requireNonNull(storePurchaseId, "storePurchaseId");
		return reverseLot(userId, CreditSource.PURCHASE, storeRef(storePurchaseId), null);
	}

	private BigDecimal reverseLot(UUID userId, CreditSource source, String sourceRef, UUID paymentOrder) {
		return tx.execute(status -> {
			Optional<Account> account = lockAccount(userId);
			if (account.isEmpty()) {
				return zero();
			}
			UUID lotId = lotId(userId, source, sourceRef);
			Optional<Lot> found = findLot(lotId, account.get().id(), true);
			if (found.isEmpty() || found.get().remaining().signum() == 0) {
				return zero();
			}
			Lot lot = found.get();
			Instant now = clock.instant();
			BigDecimal after = account.get().balance().subtract(lot.remaining());
			int rows = insertTx(account.get().id(), lotId, null, paymentOrder, "REVERSAL", lot.remaining().negate(),
					after, "reverse:" + sourceRef, "reversed " + source + " " + sourceRef, now);
			if (rows == 0) {
				return zero();
			}
			setRemaining(lotId, zero(), now);
			setBalance(account.get().id(), after, now);
			return lot.remaining();
		});
	}

	@Override
	public BigDecimal adjust(UUID userId, UUID lotId, BigDecimal delta, String adjustmentRef, String description) {
		Objects.requireNonNull(delta, "delta");
		if (delta.signum() == 0 || delta.stripTrailingZeros().scale() > SCALE) {
			throw new IllegalArgumentException("delta must be non-zero with at most 6 decimals");
		}
		if (adjustmentRef == null || adjustmentRef.isBlank() || adjustmentRef.length() > 100) {
			throw new IllegalArgumentException("adjustmentRef must be 1-100 characters");
		}
		BigDecimal amount = delta.setScale(SCALE, RoundingMode.UNNECESSARY);
		return tx.execute(status -> {
			Account account = lockAccount(userId)
					.orElseThrow(() -> new IllegalArgumentException("User has no credit account"));
			Lot lot = findLot(lotId, account.id(), true)
					.orElseThrow(() -> new IllegalArgumentException("Unknown credit lot " + lotId));
			String key = "adjust:" + adjustmentRef;
			Integer replays = jdbc.sql("SELECT count(*) FROM ai_credit_transactions WHERE credit_account_id = :a "
					+ "AND lot_id = :l AND idempotency_key = :k").param("a", account.id()).param("l", lotId)
					.param("k", key).query(Integer.class).single();
			if (replays > 0) {
				return zero();
			}
			BigDecimal remaining = lot.remaining().add(amount);
			if (remaining.signum() < 0 || remaining.compareTo(lot.initial()) > 0) {
				throw new IllegalArgumentException("Adjustment " + amount + " leaves the lot outside 0.." + lot.initial());
			}
			Instant now = clock.instant();
			BigDecimal after = account.balance().add(amount);
			insertTx(account.id(), lotId, null, null, "ADJUSTMENT", amount, after, key,
					description == null ? null : description.substring(0, Math.min(255, description.length())), now);
			setRemaining(lotId, remaining, now);
			setBalance(account.id(), after, now);
			return amount;
		});
	}

	@Override
	public int expireDue() {
		int expired = 0;
		while (true) {
			Instant now = clock.instant();
			List<UUID> due = jdbc.sql("""
					SELECT id FROM ai_credit_lots
					WHERE remaining_units > 0 AND expires_at IS NOT NULL AND expires_at <= :now
					ORDER BY expires_at, id LIMIT :limit
					""").param("now", Timestamp.from(now)).param("limit", batchSize).query(UUID.class).list();
			int progress = 0;
			for (UUID lotId : due) {
				if (Boolean.TRUE.equals(tx.execute(status -> expireLot(lotId)))) {
					progress++;
				}
			}
			expired += progress;
			if (due.size() < batchSize || progress == 0) {
				break;
			}
		}
		if (expired > 0) {
			log.info("Expired {} credit lot(s)", expired);
		}
		return expired;
	}

	private boolean expireLot(UUID lotId) {
		Optional<UUID> accountId = jdbc.sql("SELECT credit_account_id FROM ai_credit_lots WHERE id = :id")
				.param("id", lotId).query(UUID.class).optional();
		if (accountId.isEmpty()) {
			return false;
		}
		Account account = jdbc.sql("SELECT id, balance_units FROM ai_credit_accounts WHERE id = :id FOR UPDATE")
				.param("id", accountId.get()).query((rs, n) -> new Account(rs.getObject("id", UUID.class),
						rs.getBigDecimal("balance_units")))
				.single();
		Instant now = clock.instant();
		Optional<Lot> lot = findLot(lotId, account.id(), true);
		if (lot.isEmpty() || lot.get().remaining().signum() == 0 || lot.get().expiresAt() == null
				|| lot.get().expiresAt().isAfter(now)) {
			return false; // another run got there first
		}
		BigDecimal after = account.balance().subtract(lot.get().remaining());
		insertTx(account.id(), lotId, null, null, "EXPIRY", lot.get().remaining().negate(), after, null,
				"expired " + lot.get().expiresAt(), now);
		setRemaining(lotId, zero(), now);
		setBalance(account.id(), after, now);
		return true;
	}

	// ================================================================== CreditWallet

	@Override
	public BigDecimal balance(UUID userId) {
		return balance(userId, clock.instant()).exact();
	}

	@Override
	public boolean hold(UUID userId, UUID reservationId, BigDecimal amount) {
		requireTransaction();
		BigDecimal wanted = positive(amount, "amount");
		Optional<Account> found = lockAccount(userId);
		if (found.isEmpty()) {
			return false;
		}
		Account account = found.get();
		Instant now = clock.instant();
		List<Lot> lots = jdbc.sql(LOT_COLUMNS + "WHERE credit_account_id = :a AND remaining_units > 0 "
				+ "AND (expires_at IS NULL OR expires_at > :now) " + FIFO + " FOR UPDATE")
				.param("a", account.id()).param("now", Timestamp.from(now)).query(JdbcCredits::lotRow).list();
		BigDecimal usable = lots.stream().map(Lot::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
		if (usable.compareTo(wanted) < 0) {
			return false;
		}
		int sequence = jdbc.sql("""
				SELECT count(DISTINCT idempotency_key) FROM ai_credit_transactions
				WHERE credit_account_id = :a AND ai_request_id = :res AND transaction_type = 'CONSUMPTION'
				""").param("a", account.id()).param("res", reservationId).query(Integer.class).single();
		BigDecimal toTake = wanted;
		BigDecimal balance = account.balance();
		for (Lot lot : lots) {
			if (toTake.signum() == 0) {
				break;
			}
			BigDecimal take = lot.remaining().min(toTake);
			balance = balance.subtract(take);
			insertTx(account.id(), lot.id(), reservationId, null, "CONSUMPTION", take.negate(), balance,
					"hold:" + reservationId + ":" + sequence, "reserved", now);
			setRemaining(lot.id(), lot.remaining().subtract(take), now);
			toTake = toTake.subtract(take);
		}
		setBalance(account.id(), balance, now);
		return true;
	}

	/**
	 * Returns {@code held - charged} to the lots the reservation drew from: the lots that expire <em>last</em> get it
	 * back first, so the outcome is exactly what consuming {@code charged} FIFO would have left. A repeated call after
	 * the settlement does nothing.
	 */
	@Override
	public void settle(UUID userId, UUID reservationId, BigDecimal held, BigDecimal charged) {
		requireTransaction();
		BigDecimal heldUnits = nonNegative(held, "held");
		BigDecimal chargedUnits = nonNegative(charged, "charged");
		if (chargedUnits.compareTo(heldUnits) > 0) {
			throw new IllegalArgumentException("charged exceeds held");
		}
		BigDecimal giveBack = heldUnits.subtract(chargedUnits);
		Optional<Account> found = lockAccount(userId);
		if (found.isEmpty()) {
			if (heldUnits.signum() != 0) {
				throw new IllegalStateException(
						"Reservation " + reservationId + " holds credits but the user has no account");
			}
			return;
		}
		Account account = found.get();
		// lots in FIFO order with what the reservation still has taken from each (consumptions minus returns)
		List<Drawn> drawn = jdbc.sql("""
				SELECT t.lot_id, -sum(t.delta_units) AS units
				FROM ai_credit_transactions t JOIN ai_credit_lots l ON l.id = t.lot_id
				WHERE t.credit_account_id = :a AND t.ai_request_id = :res
				GROUP BY t.lot_id, l.expires_at, l.created_at, l.id
				HAVING -sum(t.delta_units) > 0
				ORDER BY l.expires_at NULLS LAST, l.created_at, l.id
				""").param("a", account.id()).param("res", reservationId)
				.query((rs, n) -> new Drawn(rs.getObject("lot_id", UUID.class), rs.getBigDecimal("units"))).list();
		BigDecimal netHeld = drawn.stream().map(Drawn::units).reduce(BigDecimal.ZERO, BigDecimal::add);
		if (netHeld.compareTo(chargedUnits) == 0) {
			return; // already settled (or nothing was held and nothing is charged)
		}
		if (netHeld.compareTo(heldUnits) != 0) {
			throw new IllegalStateException(
					"Reservation " + reservationId + " holds " + netHeld + " credits, not " + heldUnits);
		}
		Instant now = clock.instant();
		List<UUID> ids = drawn.stream().map(Drawn::lotId).toList();
		List<Lot> locked = jdbc.sql(LOT_COLUMNS + "WHERE id IN (:ids) " + FIFO + " FOR UPDATE").param("ids", ids)
				.query(JdbcCredits::lotRow).list();
		BigDecimal balance = account.balance();
		BigDecimal left = giveBack;
		List<Drawn> latestFirst = new ArrayList<>(drawn);
		Collections.reverse(latestFirst);
		for (Drawn d : latestFirst) {
			if (left.signum() == 0) {
				break;
			}
			BigDecimal back = d.units().min(left);
			Lot lot = locked.stream().filter(l -> l.id().equals(d.lotId())).findFirst().orElseThrow();
			balance = balance.add(back);
			insertTx(account.id(), lot.id(), reservationId, null, "ADJUSTMENT", back, balance, "settle:" + reservationId,
					"unused credit returned", now);
			setRemaining(lot.id(), lot.remaining().add(back), now);
			left = left.subtract(back);
		}
		setBalance(account.id(), balance, now);
	}

	// ================================================================== helpers

	private Account lockOrCreateAccount(UUID userId) {
		jdbc.sql("""
				INSERT INTO ai_credit_accounts (user_id, currency_unit) VALUES (:user, :currency)
				ON CONFLICT (user_id, currency_unit) WHERE user_id IS NOT NULL DO NOTHING
				""").param("user", userId).param("currency", CURRENCY).update();
		return lockAccount(userId).orElseThrow();
	}

	private Optional<Account> lockAccount(UUID userId) {
		return jdbc.sql("SELECT id, balance_units FROM ai_credit_accounts WHERE user_id = :user AND currency_unit = :c "
				+ "FOR UPDATE").param("user", userId).param("c", CURRENCY)
				.query((rs, n) -> new Account(rs.getObject("id", UUID.class), rs.getBigDecimal("balance_units")))
				.optional();
	}

	private Optional<Lot> findLot(UUID lotId, UUID accountId, boolean forUpdate) {
		return jdbc.sql(LOT_COLUMNS + "WHERE id = :id AND credit_account_id = :a" + (forUpdate ? " FOR UPDATE" : ""))
				.param("id", lotId).param("a", accountId).query(JdbcCredits::lotRow).optional();
	}

	private static Lot lotRow(ResultSet rs, int n) throws SQLException {
		Timestamp expires = rs.getTimestamp("expires_at");
		return new Lot(rs.getObject("id", UUID.class), rs.getBigDecimal("initial_units"),
				rs.getBigDecimal("remaining_units"), expires == null ? null : expires.toInstant(),
				rs.getTimestamp("created_at").toInstant());
	}

	private static CreditLot lot(Lot lot, UUID userId, CreditSource source, boolean created) {
		return new CreditLot(lot.id(), userId, source, lot.initial(), lot.remaining(), lot.expiresAt(), lot.createdAt(),
				created);
	}

	/** @param paymentOrder the purchase order on a PURCHASE lot's own rows (grant, reversal), else null */
	private int insertTx(UUID accountId, UUID lotId, UUID requestId, UUID paymentOrder, String type, BigDecimal delta,
			BigDecimal after, String key, String description, Instant now) {
		return jdbc.sql("""
				INSERT INTO ai_credit_transactions (credit_account_id, lot_id, ai_request_id, payment_order_id,
				                                    transaction_type, delta_units, balance_after, idempotency_key,
				                                    description, created_at)
				VALUES (:a, :lot, :req, :order, :type, :delta, :after, :key, :description, :now)
				ON CONFLICT DO NOTHING
				""").param("a", accountId).param("lot", lotId).param("req", requestId).param("order", paymentOrder)
				.param("type", type)
				.param("delta", delta).param("after", after).param("key", key).param("description", description)
				.param("now", Timestamp.from(now)).update();
	}

	private void setRemaining(UUID lotId, BigDecimal remaining, Instant now) {
		jdbc.sql("UPDATE ai_credit_lots SET remaining_units = :r, updated_at = :now WHERE id = :id")
				.param("r", remaining).param("now", Timestamp.from(now)).param("id", lotId).update();
	}

	private void setBalance(UUID accountId, BigDecimal balance, Instant now) {
		jdbc.sql("UPDATE ai_credit_accounts SET balance_units = :b, updated_at = :now WHERE id = :id")
				.param("b", balance).param("now", Timestamp.from(now)).param("id", accountId).update();
	}

	private static String openingType(CreditSource source) {
		return source == CreditSource.PROMOTION ? "GRANT" : source.name();
	}

	/** The source reference of a store purchase's lot (never a UUID string, so it cannot collide with an order id). */
	static String storeRef(UUID storePurchaseId) {
		return "store:" + storePurchaseId;
	}

	/** Deterministic lot id, so a replayed grant hits the primary key instead of needing a reference column. */
	static UUID lotId(UUID userId, CreditSource source, String sourceRef) {
		return UUID.nameUUIDFromBytes(
				("qal-credit-lot|" + userId + "|" + source + "|" + sourceRef).getBytes(StandardCharsets.UTF_8));
	}

	private static UUID parseUuid(String ref) {
		try {
			return UUID.fromString(ref);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("A PURCHASE lot's sourceRef must be the payment order id", e);
		}
	}

	private static void requireTransaction() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("The credit wallet must run inside the metering transaction");
		}
	}

	private static BigDecimal positive(BigDecimal value, String name) {
		BigDecimal v = nonNegative(value, name);
		if (v.signum() == 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return v;
	}

	private static BigDecimal nonNegative(BigDecimal value, String name) {
		Objects.requireNonNull(value, name);
		if (value.signum() < 0 || value.stripTrailingZeros().scale() > SCALE) {
			throw new IllegalArgumentException(name + " must be >= 0 with at most 6 decimals");
		}
		return value.setScale(SCALE, RoundingMode.UNNECESSARY);
	}

	private static BigDecimal zero() {
		return BigDecimal.ZERO.setScale(SCALE);
	}
}
