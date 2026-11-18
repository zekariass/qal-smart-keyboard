package com.qalsmartkeyboard.languages;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.GuestMergeParticipant;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;

/**
 * Guest merge (B2.6, ADR 0010) for the synced personal dictionary ({@code user_dictionary_entries}) and the attribution
 * of term suggestions.
 *
 * <ul>
 * <li>Live entries (no tombstone) move with the word re-encrypted for the target. <b>The target wins</b> on the unique
 * {@code (language_id, word_hmac)}, tombstones included (a word the account deleted stays deleted). Moved entries get
 * {@code sync_version}s after the target's highest, in the guest's order, so the target's other devices pull them on
 * their next incremental sync. The guest's tombstones and conflict losers are deleted.</li>
 * <li><b>Open (B3.6):</b> {@code word_hmac} is copied unchanged. ADR 0007 D2 makes it a keyed HMAC under a per-user
 * key that does not exist yet; when B3.6 adds that key, this participant must recompute the HMAC of each moved word
 * under the target's key (the plaintext is available here) and compare conflicts on the recomputed value. Guests have
 * no {@code DICTIONARY_SYNC} entitlement, so the table holds no guest rows today.</li>
 * <li>{@code term_suggestions.submitted_by_user_id}: the guest's community suggestions are attributed to the target
 * (public contributions; no content changes).</li>
 * </ul>
 */
@Component
class DictionaryGuestMerge implements GuestMergeParticipant {

	private static final Logger log = LoggerFactory.getLogger(DictionaryGuestMerge.class);
	static final String TABLE = "user_dictionary_entries";

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;

	DictionaryGuestMerge(JdbcClient jdbc, UserDataEncryption encryption) {
		this.jdbc = jdbc;
		this.encryption = encryption;
	}

	@Override
	public int mergeOrder() {
		return LANGUAGES;
	}

	@Override
	public void mergeGuest(UUID guest, UUID target, Instant now) {
		Timestamp ts = Timestamp.from(now);
		record Entry(UUID id, byte[] word) {
		}
		List<Entry> entries = jdbc.sql("""
				SELECT g.id, g.word_ciphertext FROM user_dictionary_entries g
				WHERE g.user_id = :guest AND g.deleted_at IS NULL AND NOT EXISTS (
				    SELECT 1 FROM user_dictionary_entries t
				    WHERE t.user_id = :target AND t.language_id = g.language_id AND t.word_hmac = g.word_hmac)
				ORDER BY g.sync_version, g.id
				""").param("guest", guest).param("target", target)
				.query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getBytes("word_ciphertext"))).list();
		long syncVersion = jdbc.sql(
				"SELECT COALESCE(MAX(sync_version), 0) FROM user_dictionary_entries WHERE user_id = :target")
				.param("target", target).query(Long.class).single();
		int moved = 0;
		for (Entry entry : entries) {
			EncryptionContext context = EncryptionContext.of(TABLE, "word_ciphertext", entry.id());
			EncryptedValue word;
			try {
				word = encryption.reencrypt(guest, context, entry.word(), target, context);
			}
			catch (CiphertextRejectedException e) {
				log.warn("Guest merge: {} row {} does not decrypt and is not moved", TABLE, entry.id());
				continue;
			}
			syncVersion++;
			jdbc.sql("""
					UPDATE user_dictionary_entries SET user_id = :target, word_ciphertext = :word,
					       key_version = :keyVersion, sync_version = :syncVersion, updated_at = :now
					WHERE id = :id AND user_id = :guest
					""").param("target", target).param("word", word.ciphertext())
					.param("keyVersion", word.keyVersion()).param("syncVersion", syncVersion).param("now", ts)
					.param("id", entry.id()).param("guest", guest).update();
			moved++;
		}
		jdbc.sql("DELETE FROM user_dictionary_entries WHERE user_id = :guest").param("guest", guest).update();
		int suggestions = jdbc.sql("""
				UPDATE term_suggestions SET submitted_by_user_id = :target
				WHERE submitted_by_user_id = :guest
				""").param("target", target).param("guest", guest).update();
		log.info("Guest merge {} -> {}: {} dictionary entr(y/ies) moved, {} term suggestion(s) re-attributed", guest,
				target, moved, suggestions);
	}
}
