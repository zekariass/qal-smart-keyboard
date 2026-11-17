package com.qalsmartkeyboard.identity.otp;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL for {@code otp_challenges} (V1 + V3 {@code phone_ciphertext}). Every method must run inside the caller's
 * transaction. Sign-in challenges use {@code purpose = 'SIGN_IN'}.
 */
class OtpChallengeStore {

	static final String PURPOSE_SIGN_IN = "SIGN_IN";

	record Challenge(UUID id, String phoneHash, String codeHash, byte[] phoneCiphertext, int attempts, int maxAttempts,
			Instant createdAt, Instant expiresAt, Instant consumedAt) {
	}

	private final JdbcClient jdbc;

	OtpChallengeStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Serialises start and verify per number across all instances for the rest of the transaction (the same advisory
	 * lock pattern as {@code platform.crypto}). Keyed by the phone hash, never the number.
	 */
	void lockPhone(String phoneHash) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "qal.otp:" + phoneHash)
				.query().singleRow();
	}

	Optional<Instant> latestCreatedAt(String phoneHash) {
		return jdbc.sql("""
				SELECT max(created_at) FROM otp_challenges WHERE phone_hash = :phoneHash AND purpose = :purpose
				""").param("phoneHash", phoneHash).param("purpose", PURPOSE_SIGN_IN)
				.query((rs, n) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant)).single();
	}

	void insert(UUID id, String phoneHash, String codeHash, byte[] phoneCiphertext, int maxAttempts, String ipAddress,
			Instant createdAt, Instant expiresAt) {
		jdbc.sql("""
				INSERT INTO otp_challenges (id, phone_hash, code_hash, phone_ciphertext, purpose, delivery_channel,
				                            attempts, max_attempts, ip_address, created_at, expires_at)
				VALUES (:id, :phoneHash, :codeHash, :phoneCiphertext, :purpose, 'SMS', 0, :maxAttempts,
				        CAST(:ip AS inet), :createdAt, :expiresAt)
				""").param("id", id).param("phoneHash", phoneHash).param("codeHash", codeHash)
				.param("phoneCiphertext", phoneCiphertext).param("purpose", PURPOSE_SIGN_IN)
				.param("maxAttempts", maxAttempts).param("ip", ipAddress)
				.param("createdAt", Timestamp.from(createdAt)).param("expiresAt", Timestamp.from(expiresAt)).update();
	}

	Optional<String> findPhoneHash(UUID id) {
		return jdbc.sql("SELECT phone_hash FROM otp_challenges WHERE id = :id AND purpose = :purpose").param("id", id)
				.param("purpose", PURPOSE_SIGN_IN).query(String.class).optional();
	}

	Optional<Challenge> findForUpdate(UUID id) {
		return jdbc.sql("""
				SELECT id, phone_hash, code_hash, phone_ciphertext, attempts, max_attempts, created_at, expires_at,
				       consumed_at
				FROM otp_challenges WHERE id = :id AND purpose = :purpose FOR UPDATE
				""").param("id", id).param("purpose", PURPOSE_SIGN_IN)
				.query((rs, n) -> new Challenge(rs.getObject("id", UUID.class), rs.getString("phone_hash"),
						rs.getString("code_hash"), rs.getBytes("phone_ciphertext"), rs.getInt("attempts"),
						rs.getInt("max_attempts"), rs.getTimestamp("created_at").toInstant(),
						rs.getTimestamp("expires_at").toInstant(),
						Optional.ofNullable(rs.getTimestamp("consumed_at")).map(Timestamp::toInstant).orElse(null)))
				.optional();
	}

	/** A later challenge for the same number supersedes this one. */
	boolean isSuperseded(Challenge challenge) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM otp_challenges
				               WHERE phone_hash = :phoneHash AND purpose = :purpose AND id <> :id
				                 AND (created_at > :createdAt OR (created_at = :createdAt AND id > :id)))
				""").param("phoneHash", challenge.phoneHash()).param("purpose", PURPOSE_SIGN_IN)
				.param("id", challenge.id()).param("createdAt", Timestamp.from(challenge.createdAt()))
				.query(Boolean.class).single();
	}

	/** @return the attempt count after this failure */
	int recordFailedAttempt(UUID id) {
		return jdbc.sql("UPDATE otp_challenges SET attempts = attempts + 1 WHERE id = :id RETURNING attempts")
				.param("id", id).query(Integer.class).single();
	}

	void consume(UUID id, Instant now) {
		int updated = jdbc.sql("UPDATE otp_challenges SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL")
				.param("now", Timestamp.from(now)).param("id", id).update();
		if (updated != 1) {
			throw new IllegalStateException("OTP challenge was consumed concurrently");
		}
	}
}
