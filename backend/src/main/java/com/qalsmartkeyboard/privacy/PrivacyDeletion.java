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
 * <li>Deleted: {@code user_history_items} (already crypto-shredded) and {@code data_export_requests}, with the archive
 * object of every export that still has one (B6.1, ADR 0022). The archive is ciphertext under the shredded data key, so a
 * copy left behind is unreadable anyway; a store failure is logged (class only) and does not hold up the deletion, the
 * bucket's 7-day lifecycle removes the object.</li>
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
	private final ExportObjectStore exportStore;

	PrivacyDeletion(JdbcClient jdbc, ExportObjectStore exportStore) {
		this.jdbc = jdbc;
		this.exportStore = exportStore;
	}

	@Override
	public int deletionOrder() {
		return PRIVACY;
	}

	@Override
	public int purgeUser(UUID userId, Instant now) {
		int history = jdbc.sql("DELETE FROM user_history_items WHERE user_id = :user").param("user", userId).update();
		if (exportStore.configured()) {
			for (String key : jdbc.sql("SELECT export_object_key FROM data_export_requests "
					+ "WHERE user_id = :user AND export_object_key IS NOT NULL").param("user", userId).query(String.class)
					.list()) {
				try {
					exportStore.delete(key);
				}
				catch (RuntimeException e) {
					log.warn("Account deletion {}: an export archive object could not be deleted ({}); it is "
							+ "unreadable and the bucket lifecycle removes it", userId, e.getClass().getName());
				}
			}
		}
		int exports = jdbc.sql("DELETE FROM data_export_requests WHERE user_id = :user").param("user", userId).update();
		log.info("Account deletion {}: {} history items and {} export requests deleted", userId, history, exports);
		return history + exports;
	}
}
