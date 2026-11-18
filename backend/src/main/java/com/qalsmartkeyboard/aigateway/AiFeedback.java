package com.qalsmartkeyboard.aigateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.privacy.ConsentChanged;
import com.qalsmartkeyboard.privacy.ConsentGate;
import com.qalsmartkeyboard.privacy.ConsentType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI feedback (B5.6, contract {@code submitAiFeedback}, SD 11.8, ADR 0021). One rating per (AI request, user): sending
 * it again replaces the earlier one.
 *
 * <ul>
 * <li>The request must be a {@code SUCCEEDED} {@code ai_requests} row of the caller, otherwise 404 (a foreign id and an
 * unknown id look the same).</li>
 * <li>Rating and reason codes carry no content. A {@code comment} and, with {@code includeContent}, the
 * client-supplied {@code inputText} / {@code outputText} need a granted {@code FEEDBACK_TEXT} consent: without it the
 * whole request is 403 {@code CONSENT_REQUIRED} and nothing is stored. They are sealed per column and row with the
 * user's data key ({@link EncryptionContext} {@code ai_feedback}, {@code comment_ciphertext | input_ciphertext |
 * output_ciphertext}, the row id) and the row points at the consent record it was stored under (V1 composite FK).
 * Without {@code includeContent} the texts are ignored, not stored.</li>
 * <li>Withdrawing {@code FEEDBACK_TEXT} erases the stored comment and content ({@link #onConsentChanged}).</li>
 * <li>The body is read by hand so that no value, least of all the comment, can reach a log through a framework binding
 * error (same reason as {@link AiRequestParser}).</li>
 * </ul>
 */
@Component
class AiFeedback {

	static final String TABLE = "ai_feedback";
	static final int MAX_COMMENT = 1000;
	static final int MAX_INPUT = 5000;
	static final int MAX_OUTPUT = 10000;
	static final Set<String> REASONS = Set.of("WRONG_MEANING", "WRONG_TONE", "WRONG_GENDER_OR_FORMALITY", "UNNATURAL",
			"SPELLING", "NAME_CHANGED", "WRONG_SCRIPT", "TOO_SLOW", "OTHER");

	/** A parsed request; it holds text, so {@code toString} prints ids only. */
	record Request(UUID aiRequestId, int rating, List<String> reasons, String comment, boolean includeContent,
			String inputText, String outputText) {

		@Override
		public String toString() {
			return "Request[" + aiRequestId + ", " + rating + "]";
		}
	}

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;
	private final ConsentGate consents;
	private final TransactionTemplate transactions;
	private final JsonMapper mapper;

	AiFeedback(JdbcClient jdbc, UserDataEncryption encryption, ConsentGate consents, TransactionTemplate transactions,
			JsonMapper mapper) {
		this.jdbc = jdbc;
		this.encryption = encryption;
		this.consents = consents;
		this.transactions = transactions;
		this.mapper = mapper;
	}

	/**
	 * Withdrawing {@code FEEDBACK_TEXT} deletes the stored feedback text (ADR 0007 D41): the comment and the attached
	 * input/output ciphertext of every feedback row of the user are erased, in the withdrawal's own transaction, together
	 * with the key version and the consent-record link that only the text needed. The rating and the reason codes stay
	 * (they carry no content). Idempotent: a repeated withdrawal finds nothing left.
	 */
	@EventListener
	void onConsentChanged(ConsentChanged event) {
		if (event.type() == ConsentType.FEEDBACK_TEXT && !event.granted()) {
			jdbc.sql("""
					UPDATE ai_feedback SET comment_ciphertext = NULL, input_ciphertext = NULL, output_ciphertext = NULL,
					       key_version = NULL, consent_record_id = NULL, updated_at = NOW()
					WHERE user_id = :user
					  AND (comment_ciphertext IS NOT NULL OR input_ciphertext IS NOT NULL OR output_ciphertext IS NOT NULL)
					""").param("user", event.userId()).update();
		}
	}

	void submit(UUID userId, byte[] body) {
		Request in = parse(body);
		transactions.executeWithoutResult(status -> store(userId, in));
	}

	private void store(UUID userId, Request in) {
		String feature = jdbc.sql("""
				SELECT feature_code FROM ai_requests WHERE id = :id AND user_id = :user AND request_status = 'SUCCEEDED'
				""").param("id", in.aiRequestId()).param("user", userId).query(String.class).optional()
				.orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, null));

		UUID consent = null;
		if (in.comment() != null || in.includeContent()) {
			consent = consents.grantedRecord(userId, ConsentType.FEEDBACK_TEXT)
					.orElseThrow(() -> new ApiException(ApiErrorCode.CONSENT_REQUIRED, HttpStatus.FORBIDDEN,
							"This requires the FEEDBACK_TEXT consent.", List.of(),
							Map.of("consentCode", ConsentType.FEEDBACK_TEXT.name())));
		}
		String[] reasons = in.reasons().toArray(String[]::new);
		for (int attempt = 0; attempt < 2; attempt++) {
			UUID existing = jdbc
					.sql("SELECT id FROM ai_feedback WHERE ai_request_id = :id AND user_id = :user FOR UPDATE")
					.param("id", in.aiRequestId()).param("user", userId).query(UUID.class).optional().orElse(null);
			UUID id = existing != null ? existing : UUID.randomUUID();
			EncryptedValue comment = seal(userId, id, "comment_ciphertext", in.comment());
			EncryptedValue input = seal(userId, id, "input_ciphertext", in.inputText());
			EncryptedValue output = seal(userId, id, "output_ciphertext", in.outputText());
			// all columns of a row share one key_version: if the key rotated in between, seal again
			java.util.Set<Integer> versions = new java.util.HashSet<>();
			for (EncryptedValue v : new EncryptedValue[] { comment, input, output }) {
				if (v != null) {
					versions.add(v.keyVersion());
				}
			}
			if (versions.size() > 1) {
				continue;
			}
			Integer keyVersion = versions.isEmpty() ? null : versions.iterator().next();
			if (existing != null) {
				jdbc.sql("""
						UPDATE ai_feedback SET rating = :rating, reason_codes = :reasons, comment_ciphertext = :comment,
						       input_ciphertext = :input, output_ciphertext = :output, key_version = :keyVersion,
						       consent_record_id = :consent, updated_at = NOW()
						 WHERE id = :id AND user_id = :user
						""").param("rating", in.rating()).param("reasons", reasons)
						.param("comment", bytes(comment)).param("input", bytes(input)).param("output", bytes(output))
						.param("keyVersion", keyVersion).param("consent", consent).param("id", id)
						.param("user", userId).update();
				return;
			}
			int inserted = jdbc.sql("""
					INSERT INTO ai_feedback (id, ai_request_id, user_id, feature_code, rating, reason_codes,
					       comment_ciphertext, input_ciphertext, output_ciphertext, key_version, consent_record_id)
					VALUES (:id, :request, :user, :feature, :rating, :reasons, :comment, :input, :output, :keyVersion,
					        :consent)
					ON CONFLICT (ai_request_id, user_id) DO NOTHING
					""").param("id", id).param("request", in.aiRequestId()).param("user", userId)
					.param("feature", feature).param("rating", in.rating()).param("reasons", reasons)
					.param("comment", bytes(comment)).param("input", bytes(input)).param("output", bytes(output))
					.param("keyVersion", keyVersion).param("consent", consent).update();
			if (inserted == 1) {
				return;
			}
			// a concurrent first submission won: the next round updates its row (ciphertext is bound to the row id)
		}
		throw new IllegalStateException("feedback row kept changing");
	}

	private EncryptedValue seal(UUID userId, UUID rowId, String column, String text) {
		return text == null ? null : encryption.encryptString(userId, EncryptionContext.of(TABLE, column, rowId), text);
	}

	private static byte[] bytes(EncryptedValue v) {
		return v == null ? null : v.ciphertext();
	}

	// ------------------------------------------------------------------------------------------------ parsing

	Request parse(byte[] body) {
		JsonNode n;
		try {
			n = body == null || body.length == 0 ? null : mapper.readTree(body);
		}
		catch (RuntimeException e) {
			n = null; // the message may quote the body: drop it
		}
		if (n == null || !n.isObject()) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
					List.of(new ApiFieldError(null, "MALFORMED_REQUEST_BODY",
							"The request body is missing or is not valid JSON for this endpoint.")),
					Map.of());
		}
		String id = string(n, "aiRequestId");
		if (id == null) {
			throw invalid("aiRequestId", "REQUIRED");
		}
		UUID requestId;
		try {
			requestId = UUID.fromString(id);
		}
		catch (IllegalArgumentException e) {
			throw invalid("aiRequestId", "INVALID_FORMAT");
		}
		String rating = string(n, "rating");
		if (rating == null) {
			throw invalid("rating", "REQUIRED");
		}
		int value = switch (rating) {
			case "POSITIVE" -> 1;
			case "NEGATIVE" -> -1;
			default -> throw invalid("rating", "INVALID_VALUE");
		};
		List<String> reasons = new ArrayList<>();
		JsonNode list = n.get("reasonCodes");
		if (list != null && !list.isNull()) {
			if (!list.isArray()) {
				throw invalid("reasonCodes", "INVALID_TYPE");
			}
			if (list.size() > 8) {
				throw invalid("reasonCodes", "TOO_MANY");
			}
			for (JsonNode r : list) {
				if (!r.isString() || !REASONS.contains(r.stringValue())) {
					throw invalid("reasonCodes", "INVALID_VALUE");
				}
				if (!reasons.contains(r.stringValue())) {
					reasons.add(r.stringValue());
				}
			}
		}
		String comment = bounded(n, "comment", MAX_COMMENT);
		JsonNode include = n.get("includeContent");
		if (include != null && !include.isNull() && !include.isBoolean()) {
			throw invalid("includeContent", "INVALID_TYPE");
		}
		boolean includeContent = include != null && include.booleanValue();
		String input = bounded(n, "inputText", MAX_INPUT);
		String output = bounded(n, "outputText", MAX_OUTPUT);
		return new Request(requestId, value, List.copyOf(reasons), comment == null || comment.isBlank() ? null : comment,
				includeContent, includeContent ? input : null, includeContent ? output : null);
	}

	private static String string(JsonNode n, String field) {
		JsonNode v = n.get(field);
		if (v == null || v.isNull()) {
			return null;
		}
		if (!v.isString()) {
			throw invalid(field, "INVALID_TYPE");
		}
		return v.stringValue();
	}

	private static String bounded(JsonNode n, String field, int max) {
		String s = string(n, field);
		if (s != null && s.codePointCount(0, s.length()) > max) {
			throw invalid(field, "TOO_LONG");
		}
		return s;
	}

	/** Names the field and the kind of problem, never the value. */
	private static ApiException invalid(String field, String code) {
		String message = "REQUIRED".equals(code) ? field + " is required." : field + " is invalid.";
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, message,
				List.of(new ApiFieldError(field, code, message)), Map.of());
	}
}
