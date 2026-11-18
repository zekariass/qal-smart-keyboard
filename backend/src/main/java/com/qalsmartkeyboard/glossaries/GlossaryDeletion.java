package com.qalsmartkeyboard.glossaries;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.UserDeletionParticipant;

/**
 * Account deletion (B6.2, ADR 0015) for personal glossaries: deleted with their entries ({@code ON DELETE CASCADE});
 * a destination profile pointing at one gets {@code glossary_id = NULL} (the profiles are deleted anyway).
 * Organisation glossaries have no user and are untouched.
 */
@Component
class GlossaryDeletion implements UserDeletionParticipant {

	private static final Logger log = LoggerFactory.getLogger(GlossaryDeletion.class);

	private final JdbcClient jdbc;

	GlossaryDeletion(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int deletionOrder() {
		return GLOSSARIES;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int rows = jdbc.sql("DELETE FROM glossaries WHERE user_id = :user").param("user", userId).update();
		log.info("Account deletion {}: {} personal glossaries deleted", userId, rows);
		return rows;
	}
}
