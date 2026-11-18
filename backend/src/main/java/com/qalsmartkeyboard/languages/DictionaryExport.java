package com.qalsmartkeyboard.languages;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.ExportRows;
import com.qalsmartkeyboard.identity.ExportWriter;
import com.qalsmartkeyboard.identity.UserExportParticipant;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;

/**
 * Data export (B6.1, ADR 0022) of the languages module's per-user tables:
 * <ul>
 * <li>{@code dictionary}: the personal dictionary ({@code user_dictionary_entries}) with the words decrypted. Deleted
 * words (tombstones) are not exported: the person deleted them, and a tombstone is sync bookkeeping.</li>
 * <li>{@code termSuggestions}: terms the person suggested to the shared catalogue (the contribution is theirs; reviewer
 * identities and notes are left out).</li>
 * <li>Not exported: the dictionary HMAC key and the applied-change ledger ({@code user_dictionary_sync_state},
 * {@code user_dictionary_applied_changes}), sync bookkeeping and a secret.</li>
 * </ul>
 */
@Component
class DictionaryExport implements UserExportParticipant {

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;

	DictionaryExport(JdbcClient jdbc, UserDataEncryption encryption) {
		this.jdbc = jdbc;
		this.encryption = encryption;
	}

	@Override
	public int exportOrder() {
		return LANGUAGES;
	}

	@Override
	public void exportUser(UUID userId, ExportWriter out) {
		Map<String, Object> user = Map.of("u", userId);
		out.section("dictionary", "Dictionary sync", "Personal dictionary words (decrypted); deleted words are not included.",
				ExportRows.query(jdbc, """
						SELECT e.id, l.language_code, e.word_ciphertext, e.created_at, e.updated_at
						FROM user_dictionary_entries e JOIN supported_languages l ON l.id = e.language_id
						WHERE e.user_id = :u AND e.deleted_at IS NULL ORDER BY e.created_at, e.id
						""", user, row -> {
					byte[] ciphertext = (byte[]) row.remove("wordCiphertext");
					row.put("word", encryption.decryptString(userId,
							EncryptionContext.of("user_dictionary_entries", "word_ciphertext", (String) row.get("id")),
							ciphertext));
					return row;
				}));
		out.section("termSuggestions", "Dictionary sync",
				"Terms you suggested to the shared catalogue (reviewer details are not included).", ExportRows.query(jdbc, """
						SELECT s.id, l.language_code, s.surface_form, s.normalized_form, s.term_category,
						       s.suggested_variants, s.region_code, s.contributor_note, s.credit_contributor,
						       s.suggestion_status, s.created_at, s.updated_at
						FROM term_suggestions s JOIN supported_languages l ON l.id = s.language_id
						WHERE s.submitted_by_user_id = :u ORDER BY s.created_at, s.id
						""", user));
		out.excluded("dictionarySyncState", "The dictionary hashing key, deleted-word tombstones and the applied-change "
				+ "ledger are sync bookkeeping and a secret; they are not exported.");
	}
}
