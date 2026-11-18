package com.qalsmartkeyboard.privacy;

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
 * Data export (B6.1, ADR 0022) of the privacy tables:
 * <ul>
 * <li>{@code consents}: every consent decision ever recorded (append-only, SD 19.3), oldest first; the latest per code
 * is the current state.</li>
 * <li>{@code history}: opt-in translation history with input and output <b>decrypted</b>
 * ({@code user_history_items}); items past their expiry are not shown (the reads hide them too).</li>
 * <li>{@code exportRequests}, {@code deletionRequests}: the person's own earlier requests (state and times; the
 * archive's object key is internal and left out).</li>
 * </ul>
 */
@Component
class PrivacyExport implements UserExportParticipant {

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;

	PrivacyExport(JdbcClient jdbc, UserDataEncryption encryption) {
		this.jdbc = jdbc;
		this.encryption = encryption;
	}

	@Override
	public int exportOrder() {
		return PRIVACY;
	}

	@Override
	public void exportUser(UUID userId, ExportWriter out) {
		Map<String, Object> user = Map.of("u", userId);
		out.section("consents", "Account and preferences", "Every consent decision (granted or withdrawn) with version, "
				+ "time and platform, oldest first.", ExportRows.query(jdbc, """
						SELECT id, consent_code, consent_version, is_granted, recorded_at, source_platform, metadata
						FROM user_consents WHERE user_id = :u ORDER BY recorded_at, id
						""", user));
		out.section("history", "History (opt-in)", "Saved translation history, input and output decrypted; expired items "
				+ "are not included.", ExportRows.query(jdbc, """
						SELECT h.id, h.feature_code, h.ai_request_id, s.language_code AS source_language,
						       d.language_code AS destination_language, h.input_ciphertext, h.output_ciphertext,
						       h.is_favorite, h.created_at, h.updated_at, h.expires_at
						FROM user_history_items h
						LEFT JOIN supported_languages s ON s.id = h.source_language_id
						LEFT JOIN supported_languages d ON d.id = h.destination_language_id
						WHERE h.user_id = :u AND (h.expires_at IS NULL OR h.expires_at > now())
						ORDER BY h.created_at, h.id
						""", user, row -> {
					String id = (String) row.get("id");
					byte[] input = (byte[]) row.remove("inputCiphertext");
					byte[] output = (byte[]) row.remove("outputCiphertext");
					row.put("inputText", encryption.decryptString(userId,
							EncryptionContext.of("user_history_items", "input_ciphertext", id), input));
					row.put("outputText", encryption.decryptString(userId,
							EncryptionContext.of("user_history_items", "output_ciphertext", id), output));
					return row;
				}));
		out.section("exportRequests", "Data export rows", "Your data export requests (this one included): state and "
				+ "times.", ExportRows.query(jdbc, """
						SELECT id, request_status, requested_at, completed_at, download_expires_at
						FROM data_export_requests WHERE user_id = :u ORDER BY requested_at, id
						""", user));
		out.section("deletionRequests", "Account and preferences", "Your account deletion requests: state and times.",
				ExportRows.query(jdbc, """
						SELECT id, request_status, requested_at, scheduled_purge_at, completed_at, cancellation_reason
						FROM user_deletion_requests WHERE user_id = :u ORDER BY requested_at, id
						""", user));
	}
}
