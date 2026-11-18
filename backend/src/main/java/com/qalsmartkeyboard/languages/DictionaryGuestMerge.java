package com.qalsmartkeyboard.languages;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
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
 * Guest merge (B2.6, ADR 0010, B3.6/ADR 0018) for the synced personal dictionary ({@code user_dictionary_entries}) and
 * the attribution of term suggestions.
 *
 * <ul>
 * <li>Live entries (no tombstone) move: each word is decrypted as the guest, NFC-normalised, its {@code word_hmac}
 * recomputed under <b>the target's dictionary key</b> ({@link DictionaryKeys}, created if the target has none) and the
 * word sealed again for the target. <b>The target wins</b> on the unique {@code (language_id, word_hmac)} compared on
 * the recomputed value, tombstones included (a word the account deleted stays deleted). Moved entries get
 * {@code sync_version}s after the target's counter, in the guest's order, so the target's other devices pull them on
 * their next incremental sync. The guest's tombstones and conflict losers are deleted. The guest's own HMAC key (guests
 * normally have none) is never used; its row is deleted here (and the guest's keys are shredded).</li>
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
	private final DictionaryKeys keys;

	DictionaryGuestMerge(JdbcClient jdbc, UserDataEncryption encryption, DictionaryKeys keys) {
		this.jdbc = jdbc;
		this.encryption = encryption;
		this.keys = keys;
	}

	@Override
	public int mergeOrder() {
		return LANGUAGES;
	}

	@Override
	public void mergeGuest(UUID guest, UUID target, Instant now) {
		record Entry(UUID id, UUID languageId, String languageCode, byte[] word) {
		}
		List<Entry> entries = jdbc.sql("""
				SELECT g.id, g.language_id, l.language_code, g.word_ciphertext FROM user_dictionary_entries g
				JOIN supported_languages l ON l.id = g.language_id
				WHERE g.user_id = :guest AND g.deleted_at IS NULL ORDER BY g.sync_version, g.id
				""").param("guest", guest)
				.query((rs, n) -> new Entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
						rs.getBytes(4)))
				.list();
		int moved = 0;
		if (!entries.isEmpty()) {
			DictionaryKeys.State state = keys.lock(target);
			try {
				long syncVersion = Math.max(state.counter(), jdbc.sql(
						"SELECT COALESCE(MAX(sync_version), 0) FROM user_dictionary_entries WHERE user_id = :target")
						.param("target", target).query(Long.class).single());
				for (Entry entry : entries) {
					EncryptionContext context = EncryptionContext.of(TABLE, "word_ciphertext", entry.id());
					String word;
					try {
						word = DictionarySyncService.normalize(encryption.decryptString(guest, context, entry.word()));
					}
					catch (CiphertextRejectedException e) {
						log.warn("Guest merge: {} row {} does not decrypt and is not moved", TABLE, entry.id());
						continue;
					}
					String hmac = DictionaryKeys.hmac(state.key(), entry.languageCode(), word);
					boolean taken = jdbc.sql("""
							SELECT EXISTS (SELECT 1 FROM user_dictionary_entries
							WHERE user_id = :target AND language_id = :language AND word_hmac = :hmac)
							""").param("target", target).param("language", entry.languageId()).param("hmac", hmac)
							.query(Boolean.class).single();
					if (taken) {
						continue; // the target's entry (live or tombstone) wins
					}
					EncryptedValue sealed = encryption.encryptString(target, context, word);
					syncVersion++;
					jdbc.sql("""
							UPDATE user_dictionary_entries SET user_id = :target, word_ciphertext = :word, word_hmac = :hmac,
							       key_version = :keyVersion, sync_version = :syncVersion, updated_at = :now
							WHERE id = :id AND user_id = :guest
							""").param("target", target).param("word", sealed.ciphertext(), Types.BINARY)
							.param("hmac", hmac).param("keyVersion", sealed.keyVersion())
							.param("syncVersion", syncVersion).param("now", Timestamp.from(now)).param("id", entry.id())
							.param("guest", guest).update();
					moved++;
				}
				keys.saveCounter(target, syncVersion);
			}
			finally {
				Arrays.fill(state.key(), (byte) 0);
			}
		}
		jdbc.sql("DELETE FROM user_dictionary_entries WHERE user_id = :guest").param("guest", guest).update();
		jdbc.sql("DELETE FROM user_dictionary_applied_changes WHERE user_id = :guest").param("guest", guest).update();
		jdbc.sql("DELETE FROM user_dictionary_sync_state WHERE user_id = :guest").param("guest", guest).update();
		int suggestions = jdbc.sql("""
				UPDATE term_suggestions SET submitted_by_user_id = :target
				WHERE submitted_by_user_id = :guest
				""").param("target", target).param("guest", guest).update();
		log.info("Guest merge {} -> {}: {} dictionary entr(y/ies) moved, {} term suggestion(s) re-attributed", guest,
				target, moved, suggestions);
	}
}
