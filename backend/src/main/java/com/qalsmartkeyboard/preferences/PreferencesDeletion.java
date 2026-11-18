package com.qalsmartkeyboard.preferences;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.UserDeletionParticipant;

/**
 * Account deletion (B6.2, ADR 0015) for the preferences tables. All are personal and deleted; the encrypted columns
 * (destination-profile labels, style sign-off and phrases, the app-rule HMAC key of ADR 0012) were already
 * crypto-shredded. App rules go before the profiles they reference (composite foreign key); the revision rows go with
 * their documents.
 */
@Component
class PreferencesDeletion implements UserDeletionParticipant {

	private static final Logger log = LoggerFactory.getLogger(PreferencesDeletion.class);

	/** In foreign-key order. */
	static final List<String> TABLES = List.of("user_app_destination_rules", "user_app_rule_revisions",
			"user_app_rule_keys", "user_destination_profiles", "user_style_profiles", "user_typing_languages",
			"user_typing_language_revisions", "user_preferences");

	private final JdbcClient jdbc;

	PreferencesDeletion(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int deletionOrder() {
		return PREFERENCES;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int rows = 0;
		for (String table : TABLES) {
			rows += jdbc.sql("DELETE FROM " + table + " WHERE user_id = :user").param("user", userId).update();
		}
		log.info("Account deletion {}: {} preference row(s) deleted", userId, rows);
		return rows;
	}
}
