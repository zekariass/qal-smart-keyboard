package com.qalsmartkeyboard.aigateway.pipeline;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes the {@code ai_requests} operational ledger (V1, SD 11.1 step "write ai_requests metadata (no content)",
 * SD 19.2). The record has no text field of any kind: feature, surface, language and provider references, status,
 * counts, units, duration, guardrail flag names, the idempotency key and an error code. Languages, provider, model and
 * template are resolved to ids inside the INSERT; an unknown code simply stores NULL.
 */
public final class AiRequestLog {

	/** {@code ai_requests.request_status}. */
	public enum Status {
		SUCCEEDED, FAILED, CANCELLED, REJECTED
	}

	/**
	 * One finished request.
	 *
	 * @param id             UUIDv7; the usage reservation id for metered requests (ADR 0011 B4.3 note: credit ledger
	 *                       rows reference it as {@code ai_request_id})
	 * @param idempotencyKey stored only for the request that reserved with it (V1 unique per user and key); null for
	 *                       refusals before a reservation and for uncharged recomputes
	 * @param usageSource    PLAN_ALLOWANCE, AI_CREDITS or UNMETERED
	 * @param durationMillis provider time: the serving call on success, all attempts otherwise
	 */
	public record Entry(UUID id, UUID userId, UUID deviceId, String featureCode, String surface,
			String sourceLanguage, String destinationLanguage, String providerCode, String modelCode,
			UUID promptTemplateId, Status status, String usageSource, long meteredUnits, BigDecimal creditsCharged,
			int inputCharacters, int outputCharacters, Long inputTokens, Long outputTokens, Long durationMillis,
			Collection<String> guardrailFlags, String idempotencyKey, String errorCode, Instant createdAt,
			Instant completedAt) {
	}

	private final JdbcClient jdbc;

	public AiRequestLog(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void write(Entry e) {
		jdbc.sql("""
				INSERT INTO ai_requests (id, subject_type, user_id, device_id, feature_code, surface, execution,
				    source_language_id, destination_language_id, provider_id, model_id, prompt_template_id,
				    request_status, usage_source, metered_units, credits_charged, input_character_count,
				    output_character_count, input_token_count, output_token_count, duration_ms, guardrail_flags,
				    idempotency_key, content_retained, error_code, created_at, completed_at)
				VALUES (:id, 'USER', :user, :device, :feature, :surface, 'SERVER',
				    (SELECT id FROM supported_languages WHERE language_code = :src),
				    (SELECT id FROM supported_languages WHERE language_code = :dst),
				    (SELECT id FROM ai_providers WHERE provider_code = :provider),
				    (SELECT m.id FROM ai_models m JOIN ai_providers p ON p.id = m.provider_id
				      WHERE p.provider_code = :provider AND m.model_code = :model),
				    :template, :status, :source, :units, :credits, :inChars, :outChars, :inTokens, :outTokens,
				    :duration, string_to_array(:flags, ','), :key, FALSE, :error, :created, :completed)
				""").param("id", e.id()).param("user", e.userId()).param("device", e.deviceId())
				.param("feature", e.featureCode()).param("surface", e.surface()).param("src", e.sourceLanguage())
				.param("dst", e.destinationLanguage()).param("provider", e.providerCode()).param("model", e.modelCode())
				.param("template", e.promptTemplateId()).param("status", e.status().name())
				.param("source", e.usageSource()).param("units", e.meteredUnits())
				.param("credits", e.creditsCharged() == null ? BigDecimal.ZERO : e.creditsCharged())
				.param("inChars", e.inputCharacters()).param("outChars", e.outputCharacters())
				.param("inTokens", e.inputTokens() == null ? null : (int) Math.min(Integer.MAX_VALUE, e.inputTokens()))
				.param("outTokens",
						e.outputTokens() == null ? null : (int) Math.min(Integer.MAX_VALUE, e.outputTokens()))
				.param("duration", e.durationMillis() == null ? null
						: (int) Math.min(Integer.MAX_VALUE, e.durationMillis()))
				.param("flags", e.guardrailFlags() == null ? "" : e.guardrailFlags().stream().sorted()
						.collect(Collectors.joining(",")))
				.param("key", e.idempotencyKey()).param("error", e.errorCode())
				.param("created", Timestamp.from(e.createdAt()))
				.param("completed", Timestamp.from(e.completedAt())).update();
	}

	/**
	 * The outcome recorded for a request id: status and error code (for the D25 replay of a failed request). Empty when
	 * no row was written.
	 */
	public Optional<Outcome> outcome(UUID id) {
		return jdbc.sql("SELECT request_status, error_code FROM ai_requests WHERE id = :id").param("id", id)
				.query((rs, n) -> new Outcome(Status.valueOf(rs.getString("request_status")),
						rs.getString("error_code")))
				.optional();
	}

	public record Outcome(Status status, String errorCode) {
	}
}
