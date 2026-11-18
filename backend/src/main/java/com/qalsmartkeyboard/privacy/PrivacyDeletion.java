package com.qalsmartkeyboard.privacy;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.identity.UserDeletionParticipant;

/**
 * Account deletion (B6.2, ADR 0015) for the privacy tables:
 * <ul>
 * <li>Deleted: {@code user_history_items} (already crypto-shredded) and {@code data_export_requests} (B6.1 must also
 * delete an export's archive object when it adds one).</li>
 * <li>Kept: {@code user_consents}, append-only (SD 19.3/20.1; the V1 trigger rejects application deletes). The rows
 * are the evidence of what the person agreed to and withdrew; they stay attached to the anonymised account id
 * (pseudonymised) and are removed by the retention job (B6.3) at the end of their period (SD 19.2 "life of account +
 * 30 days"). They carry code, version, decision, time, platform and client-supplied metadata; no contact data.</li>
 * <li>Kept: {@code user_deletion_requests} (the record that and when the deletion was done; no personal data).</li>
 * </ul>
 */
@Component
class PrivacyDeletion implements UserDeletionParticipant {

	private static final Logger log = LoggerFactory.getLogger(PrivacyDeletion.class);

	private final JdbcClient jdbc;

	PrivacyDeletion(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int deletionOrder() {
		return PRIVACY;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int history = jdbc.sql("DELETE FROM user_history_items WHERE user_id = :user").param("user", userId).update();
		int exports = jdbc.sql("DELETE FROM data_export_requests WHERE user_id = :user").param("user", userId).update();
		log.info("Account deletion {}: {} history items and {} export requests deleted", userId, history, exports);
		return history + exports;
	}
}
