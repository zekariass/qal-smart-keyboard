package com.qalsmartkeyboard.aigateway.idempotency;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.UserDeletionParticipant;

/**
 * Account deletion (B6.2, ADR 0015) for the B5.4 tables: {@code ai_replay_cache} (sealed responses, at most 10
 * minutes old) and {@code ai_request_fingerprints} (sealed request digests, at most the retention old) are deleted.
 * Both are ciphertext under the user's data key, which is already crypto-shredded when this runs, so a row left behind
 * (a backup, a replica) is unreadable anyway; deleting them also removes the timing metadata.
 */
@Component
class AiIdempotencyDeletion implements UserDeletionParticipant {

	private static final Logger log = LoggerFactory.getLogger(AiIdempotencyDeletion.class);

	private final JdbcClient jdbc;

	AiIdempotencyDeletion(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int deletionOrder() {
		return AI_GATEWAY;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int cache = jdbc.sql("DELETE FROM ai_replay_cache WHERE user_id = :user").param("user", userId).update();
		int fingerprints = jdbc.sql("DELETE FROM ai_request_fingerprints WHERE user_id = :user").param("user", userId)
				.update();
		if (cache + fingerprints > 0) {
			log.info("Account deletion {}: {} AI replay cache row(s) and {} request fingerprint(s) deleted", userId,
					cache, fingerprints);
		}
		return cache + fingerprints;
	}
}
