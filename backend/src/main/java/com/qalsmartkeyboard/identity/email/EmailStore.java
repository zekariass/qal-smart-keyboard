package com.qalsmartkeyboard.identity.email;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL for e-mail sign-in (ADR 0033): {@code email_challenges} (V22), {@code user_password_credentials} (V1) and the
 * account lookup by {@code users.email_normalized}. Every method runs inside the caller's transaction, which holds the
 * per-address advisory lock ({@link #lockAddress}) for every write.
 */
class EmailStore {

	static final String REGISTRATION = "REGISTRATION";
	static final String EMAIL_VERIFICATION = "EMAIL_VERIFICATION";
	static final String PASSWORD_RESET = "PASSWORD_RESET";

	/**
	 * The live account that owns an address, with its password credential.
	 *
	 * @param status        effective status ({@code DELETED} once {@code deleted_at} is set)
	 * @param passwordHash  {@code null} when the account has no password
	 * @param lockedUntil   {@code null} when not locked
	 */
	record Account(UUID userId, String accountType, String status, String email, Instant emailVerifiedAt,
			String passwordHash, int failedAttempts, Instant lockedUntil) {

		boolean verified() {
			return emailVerifiedAt != null;
		}

		@Override
		public String toString() {
			return "Account[userId=" + userId + ", status=" + status + "]";
		}
	}

	record Challenge(UUID id, UUID userId, String purpose, String emailHash, String codeHash, byte[] payload,
			String pendingPasswordHash, int attempts, int maxAttempts, int sends, Instant createdAt,
			Instant lastSentAt, Instant expiresAt, Instant consumedAt) {

		@Override
		public String toString() {
			return "Challenge[id=" + id + ", purpose=" + purpose + "]";
		}
	}

	private final JdbcClient jdbc;

	EmailStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Serialises every e-mail flow of one address across instances for the rest of the transaction. */
	void lockAddress(String emailHash) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "qal.email:" + emailHash)
				.query().singleRow();
	}

	// ------------------------------------------------------------------ accounts and credentials

	/** The live account ({@code deleted_at IS NULL}) whose normalised address is {@code normalized}. */
	Optional<Account> findAccount(String normalized) {
		return jdbc.sql("""
				SELECT u.id, u.account_type,
				       CASE WHEN u.deleted_at IS NOT NULL THEN 'DELETED' ELSE u.account_status END AS status,
				       u.email, u.email_verified_at, c.password_hash, COALESCE(c.failed_attempts, 0) AS failed_attempts,
				       c.locked_until
				FROM users u LEFT JOIN user_password_credentials c ON c.user_id = u.id
				WHERE u.email_normalized = :normalized AND u.deleted_at IS NULL
				""").param("normalized", normalized).query(EmailStore::account).optional();
	}

	Optional<Account> findAccountById(UUID userId) {
		return jdbc.sql("""
				SELECT u.id, u.account_type,
				       CASE WHEN u.deleted_at IS NOT NULL THEN 'DELETED' ELSE u.account_status END AS status,
				       u.email, u.email_verified_at, c.password_hash, COALESCE(c.failed_attempts, 0) AS failed_attempts,
				       c.locked_until
				FROM users u LEFT JOIN user_password_credentials c ON c.user_id = u.id
				WHERE u.id = :id
				""").param("id", userId).query(EmailStore::account).optional();
	}

	/**
	 * Frees the address held by the {@code EMAIL} identity of a closed account (deletion in progress, ADR 0015): the
	 * deletion purges that row anyway, and until then it would block a new sign-up with the address (V1
	 * {@code ux_auth_provider_subject}).
	 */
	int releaseClosedIdentity(String normalized) {
		return jdbc.sql("""
				DELETE FROM user_auth_identities i USING users u
				WHERE i.user_id = u.id AND i.identity_provider = 'EMAIL' AND i.provider_subject = :normalized
				  AND (u.deleted_at IS NOT NULL OR u.account_status IN ('DELETED', 'MERGED'))
				""").param("normalized", normalized).update();
	}

	/** Sets the password and clears the failure counter and any lock. */
	void setPassword(UUID userId, String passwordHash, Instant now) {
		jdbc.sql("""
				INSERT INTO user_password_credentials (user_id, password_hash, password_updated_at, failed_attempts,
				                                       locked_until, created_at, updated_at)
				VALUES (:userId, :hash, :now, 0, NULL, :now, :now)
				ON CONFLICT (user_id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
				       password_updated_at = EXCLUDED.password_updated_at, failed_attempts = 0, locked_until = NULL,
				       updated_at = EXCLUDED.updated_at
				""").param("userId", userId).param("hash", passwordHash).param("now", Timestamp.from(now)).update();
	}

	/** Re-hash with current parameters; only if the stored hash is still {@code expected} (same password). */
	boolean upgradeHash(UUID userId, String expected, String passwordHash, Instant now) {
		return jdbc.sql("""
				UPDATE user_password_credentials SET password_hash = :hash, updated_at = :now
				WHERE user_id = :userId AND password_hash = :expected
				""").param("hash", passwordHash).param("now", Timestamp.from(now)).param("userId", userId)
				.param("expected", expected).update() == 1;
	}

	/**
	 * Counts a wrong password. Reaching {@code maxFailures} locks the credential until {@code now + lockFor} and starts
	 * the count again.
	 *
	 * @return the lock end if this failure locked the credential, else empty
	 */
	Optional<Instant> recordFailure(UUID userId, int maxFailures, java.time.Duration lockFor, Instant now) {
		Instant lockedUntil = now.plus(lockFor).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		return jdbc.sql("""
				UPDATE user_password_credentials
				SET failed_attempts = CASE WHEN failed_attempts + 1 >= :max THEN 0 ELSE failed_attempts + 1 END,
				    locked_until = CASE WHEN failed_attempts + 1 >= :max THEN :lockedUntil ELSE locked_until END,
				    updated_at = :now
				WHERE user_id = :userId
				RETURNING locked_until
				""").param("max", maxFailures).param("lockedUntil", Timestamp.from(lockedUntil))
				.param("now", Timestamp.from(now)).param("userId", userId)
				.query((rs, n) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant)).optional()
				.flatMap(o -> o).filter(until -> until.equals(lockedUntil));
	}

	void clearFailures(UUID userId, Instant now) {
		jdbc.sql("""
				UPDATE user_password_credentials SET failed_attempts = 0, locked_until = NULL, updated_at = :now
				WHERE user_id = :userId AND (failed_attempts <> 0 OR locked_until IS NOT NULL)
				""").param("now", Timestamp.from(now)).param("userId", userId).update();
	}

	// ------------------------------------------------------------------ challenges

	void insertChallenge(UUID id, UUID userId, String purpose, String emailHash, String codeHash, byte[] payload,
			String pendingPasswordHash, int maxAttempts, String ipAddress, Instant now, Instant expiresAt) {
		jdbc.sql("""
				INSERT INTO email_challenges (id, user_id, purpose, email_hash, code_hash, payload_ciphertext,
				                              pending_password_hash, attempts, max_attempts, sends, ip_address,
				                              created_at, last_sent_at, expires_at)
				VALUES (:id, :userId, :purpose, :emailHash, :codeHash, :payload, :pending, 0, :maxAttempts, 1,
				        CAST(:ip AS inet), :now, :now, :expiresAt)
				""").param("id", id).param("userId", userId).param("purpose", purpose).param("emailHash", emailHash)
				.param("codeHash", codeHash).param("payload", payload).param("pending", pendingPasswordHash)
				.param("maxAttempts", maxAttempts).param("ip", ipAddress).param("now", Timestamp.from(now))
				.param("expiresAt", Timestamp.from(expiresAt)).update();
	}

	/** Unlocked lookup of the address hash, to take the address lock before locking the row. */
	Optional<String> findEmailHash(UUID id) {
		return jdbc.sql("SELECT email_hash FROM email_challenges WHERE id = :id").param("id", id).query(String.class)
				.optional();
	}

	Optional<Challenge> findForUpdate(UUID id) {
		return jdbc.sql("SELECT * FROM email_challenges WHERE id = :id FOR UPDATE").param("id", id)
				.query(EmailStore::challenge).optional();
	}

	/** The newest unconsumed challenge of a purpose for an account. */
	Optional<Challenge> latestOpenForUser(UUID userId, String purpose) {
		return jdbc.sql("""
				SELECT * FROM email_challenges
				WHERE user_id = :userId AND purpose = :purpose AND consumed_at IS NULL
				ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE
				""").param("userId", userId).param("purpose", purpose).query(EmailStore::challenge).optional();
	}

	/** The newest challenge of a purpose for an address (consumed or not). */
	Optional<Challenge> latestForAddress(String emailHash, String purpose) {
		return jdbc.sql("""
				SELECT * FROM email_challenges
				WHERE email_hash = :emailHash AND purpose = :purpose
				ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE
				""").param("emailHash", emailHash).param("purpose", purpose).query(EmailStore::challenge).optional();
	}

	/** A new code for the same challenge: the old code stops working, attempts start again, the expiry moves. */
	void resend(UUID id, String codeHash, Instant now, Instant expiresAt) {
		jdbc.sql("""
				UPDATE email_challenges SET code_hash = :codeHash, attempts = 0, sends = sends + 1, last_sent_at = :now,
				       expires_at = GREATEST(:expiresAt, created_at + INTERVAL '1 second')
				WHERE id = :id AND consumed_at IS NULL
				""").param("codeHash", codeHash).param("now", Timestamp.from(now))
				.param("expiresAt", Timestamp.from(expiresAt)).param("id", id).update();
	}

	/** @return the attempt count after this failure */
	int recordFailedAttempt(UUID id) {
		return jdbc.sql("UPDATE email_challenges SET attempts = attempts + 1 WHERE id = :id RETURNING attempts")
				.param("id", id).query(Integer.class).single();
	}

	void consume(UUID id, Instant now) {
		int updated = jdbc.sql("UPDATE email_challenges SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL")
				.param("now", Timestamp.from(now)).param("id", id).update();
		if (updated != 1) {
			throw new IllegalStateException("E-mail challenge was consumed concurrently");
		}
	}

	/** Ends every open challenge of an account (after a reset or a verification, older codes must not work). */
	int consumeAllOfUser(UUID userId, Instant now) {
		return jdbc.sql("""
				UPDATE email_challenges SET consumed_at = GREATEST(:now, created_at)
				WHERE user_id = :userId AND consumed_at IS NULL
				""").param("now", Timestamp.from(now)).param("userId", userId).update();
	}

	/** Ends the open sign-up challenges of an address once an account owns it. */
	int consumeOpenRegistrations(String emailHash, Instant now) {
		return jdbc.sql("""
				UPDATE email_challenges SET consumed_at = GREATEST(:now, created_at)
				WHERE email_hash = :emailHash AND purpose = 'REGISTRATION' AND consumed_at IS NULL
				""").param("now", Timestamp.from(now)).param("emailHash", emailHash).update();
	}

	private static Account account(ResultSet rs, int row) throws SQLException {
		return new Account(rs.getObject("id", UUID.class), rs.getString("account_type"), rs.getString("status"),
				rs.getString("email"), instant(rs, "email_verified_at"), rs.getString("password_hash"),
				rs.getInt("failed_attempts"), instant(rs, "locked_until"));
	}

	private static Challenge challenge(ResultSet rs, int row) throws SQLException {
		return new Challenge(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getString("purpose"), rs.getString("email_hash"), rs.getString("code_hash"),
				rs.getBytes("payload_ciphertext"), rs.getString("pending_password_hash"), rs.getInt("attempts"),
				rs.getInt("max_attempts"), rs.getInt("sends"), instant(rs, "created_at"), instant(rs, "last_sent_at"),
				instant(rs, "expires_at"), instant(rs, "consumed_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}
}
