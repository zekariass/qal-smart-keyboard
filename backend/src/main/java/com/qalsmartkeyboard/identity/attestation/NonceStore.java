package com.qalsmartkeyboard.identity.attestation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL for {@code attestation_nonces} (V5). Only the SHA-256 of a nonce is stored. */
class NonceStore {

	private final JdbcClient jdbc;

	NonceStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(String nonceHash, UUID userId, String platform, Instant now, Instant expiresAt) {
		jdbc.sql("""
				INSERT INTO attestation_nonces (id, nonce_hash, user_id, platform, created_at, expires_at)
				VALUES (:id, :hash, :userId, :platform, :now, :expiresAt)
				""").param("id", UUID.randomUUID()).param("hash", nonceHash).param("userId", userId)
				.param("platform", platform).param("now", Timestamp.from(now)).param("expiresAt", Timestamp.from(expiresAt))
				.update();
	}

	/**
	 * Atomically marks the nonce consumed: it must exist, be unconsumed and unexpired, belong to nobody or to
	 * {@code userId}, and (if it was issued for a platform) to {@code platform}. One statement, so two concurrent
	 * presentations cannot both succeed.
	 */
	boolean consume(String nonceHash, UUID userId, String platform, Instant now) {
		return jdbc.sql("""
				UPDATE attestation_nonces SET consumed_at = :now
				WHERE nonce_hash = :hash AND consumed_at IS NULL AND expires_at > :now
				  AND (user_id IS NULL OR user_id = CAST(:userId AS uuid))
				  AND (platform IS NULL OR platform = CAST(:platform AS varchar))
				""").param("now", Timestamp.from(now)).param("hash", nonceHash).param("userId", userId)
				.param("platform", platform).update() == 1;
	}
}
