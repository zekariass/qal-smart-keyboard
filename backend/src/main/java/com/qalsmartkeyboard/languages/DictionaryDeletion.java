package com.qalsmartkeyboard.languages;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.UserDeletionParticipant;

/**
 * Account deletion (B6.2, ADR 0015) for the languages tables:
 * <ul>
 * <li>{@code user_dictionary_entries} (personal dictionary; the words are already crypto-shredded, but the word HMACs
 * would still allow a dictionary guess): deleted, tombstones included.</li>
 * <li>{@code term_suggestions.submitted_by_user_id}: detached ({@code NULL}, what the foreign key's
 * {@code ON DELETE SET NULL} prescribes). The suggested term was a contribution to the shared catalogue under
 * {@code COMMUNITY_CONTRIBUTION} consent; it stays as catalogue data, no longer linked to the person.</li>
 * </ul>
 */
@Component
class DictionaryDeletion implements UserDeletionParticipant {

	private static final Logger log = LoggerFactory.getLogger(DictionaryDeletion.class);

	private final JdbcClient jdbc;

	DictionaryDeletion(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int deletionOrder() {
		return LANGUAGES;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int words = jdbc.sql("DELETE FROM user_dictionary_entries WHERE user_id = :user").param("user", userId)
				.update();
		int suggestions = jdbc
				.sql("UPDATE term_suggestions SET submitted_by_user_id = NULL WHERE submitted_by_user_id = :user")
				.param("user", userId).update();
		log.info("Account deletion {}: {} dictionary entries deleted, {} term suggestions detached", userId, words,
				suggestions);
		return words + suggestions;
	}
}
