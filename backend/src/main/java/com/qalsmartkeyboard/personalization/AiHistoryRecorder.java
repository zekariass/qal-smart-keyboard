package com.qalsmartkeyboard.personalization;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.aigateway.AiHistorySink;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.privacy.ConsentGate;
import com.qalsmartkeyboard.privacy.ConsentType;

/**
 * Writes the opt-in AI history (B5.7, SD 11.7, ADR 0021). History is <b>off by default</b>: an item is kept only when
 * all of these hold at the time of the call, each read fresh (a withdrawal or a switch takes effect on the next call):
 * the {@code HISTORY_SYNC} entitlement (SD 11.7: guests and plans without it keep nothing), the user's
 * {@code user_preferences.ai_history_enabled}, and a granted {@code SAVE_AI_HISTORY} consent. (The pipeline offers only
 * requests that said {@code saveToHistory}.)
 *
 * <p>Input and output are sealed separately with the user's data key, each bound to this table, its own column and the
 * row id. {@code expires_at} = now + the user's {@code history_retention_days} (1 to 365, default 30; favourites have
 * none). {@code UNIQUE (user_id, ai_request_id)} makes the write idempotent per request.
 */
@Component
class AiHistoryRecorder implements AiHistorySink {

	static final String TABLE = "user_history_items";
	static final int DEFAULT_RETENTION_DAYS = 30;

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;
	private final ConsentGate consents;
	private final EntitlementResolver entitlements;
	private final TransactionTemplate transactions;
	private final Clock clock;

	AiHistoryRecorder(JdbcClient jdbc, UserDataEncryption encryption, ConsentGate consents,
			EntitlementResolver entitlements, TransactionTemplate transactions, ObjectProvider<Clock> clock) {
		this.jdbc = jdbc;
		this.encryption = encryption;
		this.consents = consents;
		this.entitlements = entitlements;
		this.transactions = transactions;
		this.clock = clock.getIfAvailable(Clock::systemUTC);
	}

	@Override
	public Optional<UUID> offer(Entry e) {
		Integer days = jdbc
				.sql("SELECT history_retention_days FROM user_preferences WHERE user_id = :user AND ai_history_enabled")
				.param("user", e.userId()).query(Integer.class).optional().orElse(null);
		if (days == null || !consents.hasConsent(e.userId(), ConsentType.SAVE_AI_HISTORY)
				|| !entitlements.resolve(e.userId()).isAllowed("HISTORY_SYNC")) {
			return Optional.empty();
		}
		return transactions.execute(status -> insert(e, days));
	}

	private Optional<UUID> insert(Entry e, int days) {
		UUID id = UUID.randomUUID();
		EncryptedValue input = null;
		EncryptedValue output = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			input = encryption.encryptString(e.userId(), EncryptionContext.of(TABLE, "input_ciphertext", id),
					e.inputText());
			output = encryption.encryptString(e.userId(), EncryptionContext.of(TABLE, "output_ciphertext", id),
					e.outputText());
			if (input.keyVersion() == output.keyVersion()) {
				break; // one key_version column: seal again if the key rotated in between
			}
			output = null;
		}
		if (output == null) {
			throw new IllegalStateException("The data key kept rotating while sealing history");
		}
		Instant now = clock.instant();
		int inserted = jdbc.sql("""
				INSERT INTO user_history_items (id, user_id, feature_code, ai_request_id, source_language_id,
				       destination_language_id, input_ciphertext, output_ciphertext, key_version, is_favorite,
				       created_at, updated_at, expires_at)
				VALUES (:id, :user, :feature, :request,
				       (SELECT id FROM supported_languages WHERE language_code = :src),
				       (SELECT id FROM supported_languages WHERE language_code = :dst),
				       :input, :output, :keyVersion, FALSE, :now, :now, :expires)
				ON CONFLICT (user_id, ai_request_id) WHERE ai_request_id IS NOT NULL DO NOTHING
				""").param("id", id).param("user", e.userId()).param("feature", e.feature())
				.param("request", e.requestId()).param("src", e.source()).param("dst", e.destination())
				.param("input", input.ciphertext()).param("output", output.ciphertext())
				.param("keyVersion", input.keyVersion()).param("now", Timestamp.from(now))
				.param("expires", Timestamp.from(now.plus(Duration.ofDays(days)))).update();
		return inserted == 1 ? Optional.of(id) : Optional.empty();
	}
}
