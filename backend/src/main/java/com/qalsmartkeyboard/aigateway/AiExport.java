package com.qalsmartkeyboard.aigateway;

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
 * Data export (B6.1, ADR 0022) of the AI gateway's per-user tables:
 * <ul>
 * <li>{@code aiRequests}: request <b>metadata</b> (SD 19.2: feature, languages, status, character and token counts,
 * metered units, credits, cost, duration, guardrail flags). The text of a request is never stored, so there is none to
 * export. Left out: provider, model, prompt and routing ids and the client's idempotency key (internal).</li>
 * <li>{@code aiFeedback}: ratings and reason codes, and the comment and attached input/output text (decrypted) of the
 * rows stored under a granted {@code FEEDBACK_TEXT} consent.</li>
 * <li>Not exported: the replay cache and request fingerprints (sealed, at most 10 minutes / 24 hours old, no content
 * the person has not already seen).</li>
 * </ul>
 */
@Component
class AiExport implements UserExportParticipant {

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;

	AiExport(JdbcClient jdbc, UserDataEncryption encryption) {
		this.jdbc = jdbc;
		this.encryption = encryption;
	}

	@Override
	public int exportOrder() {
		return AI_GATEWAY;
	}

	@Override
	public void exportUser(UUID userId, ExportWriter out) {
		Map<String, Object> user = Map.of("u", userId);
		out.section("aiRequests", "AI request metadata", "Metadata of every AI request (no text is stored): feature, "
				+ "languages, status, counts, units, cost, duration, guardrail flags.", ExportRows.query(jdbc, """
						SELECT r.id, r.feature_code, r.surface, r.execution, s.language_code AS source_language,
						       d.language_code AS destination_language, r.request_status, r.usage_source, r.metered_units,
						       r.credits_charged, r.input_character_count, r.output_character_count, r.input_token_count,
						       r.output_token_count, r.audio_duration_ms, r.duration_ms, r.estimated_cost_micros,
						       r.cost_currency, r.guardrail_flags, r.content_retained, r.error_code, r.created_at,
						       r.completed_at
						FROM ai_requests r
						LEFT JOIN supported_languages s ON s.id = r.source_language_id
						LEFT JOIN supported_languages d ON d.id = r.destination_language_id
						WHERE r.user_id = :u AND r.subject_type = 'USER' ORDER BY r.created_at, r.id
						""", user));
		out.section("aiFeedback", "AI request metadata", "Feedback on AI results: rating, reason codes and, when you "
				+ "agreed to FEEDBACK_TEXT, the comment and attached texts (decrypted).", ExportRows.query(jdbc, """
						SELECT id, ai_request_id, feature_code, rating, reason_codes, comment_ciphertext, input_ciphertext,
						       output_ciphertext, created_at, updated_at
						FROM ai_feedback WHERE user_id = :u ORDER BY created_at, id
						""", user, row -> {
					String id = (String) row.get("id");
					row.put("rating", ((Number) row.get("rating")).intValue() > 0 ? "POSITIVE" : "NEGATIVE");
					for (String column : new String[] { "comment", "input", "output" }) {
						byte[] ciphertext = (byte[]) row.remove(column + "Ciphertext");
						row.put("comment".equals(column) ? "comment" : column + "Text", ciphertext == null ? null
								: encryption.decryptString(userId,
										EncryptionContext.of("ai_feedback", column + "_ciphertext", id), ciphertext));
					}
					return row;
				}));
		out.excluded("aiRequestContent", "The text of AI requests and results is not stored (SD 19.1): there is nothing "
				+ "to export. Opt-in history is in the history section.");
		out.excluded("aiReplayCache", "Encrypted replay cache and request fingerprints are transient (10 minutes and "
				+ "24 hours) and hold nothing you have not already received.");
	}
}
