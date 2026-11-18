package com.qalsmartkeyboard.glossaries;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.GuestMergeParticipant;

/**
 * Guest merge (B2.6, ADR 0010) for personal glossaries: every glossary the guest owns moves to the target with its
 * entries (they hang off the glossary). Glossaries have no per-user uniqueness, so nothing conflicts. Guests have no
 * {@code CUSTOM_GLOSSARY} entitlement, so this is normally empty. Organisation glossaries are not personal and untouched.
 */
@Component
class GlossaryGuestMerge implements GuestMergeParticipant {

	private static final Logger log = LoggerFactory.getLogger(GlossaryGuestMerge.class);

	private final JdbcClient jdbc;

	GlossaryGuestMerge(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int mergeOrder() {
		return GLOSSARIES;
	}

	@Override
	public void mergeGuest(UUID guest, UUID target, Instant now) {
		int moved = jdbc.sql("UPDATE glossaries SET user_id = :target WHERE user_id = :guest").param("target", target)
				.param("guest", guest).update();
		if (moved > 0) {
			log.info("Guest merge {} -> {}: {} personal glossar(y/ies) moved", guest, target, moved);
		}
	}
}
