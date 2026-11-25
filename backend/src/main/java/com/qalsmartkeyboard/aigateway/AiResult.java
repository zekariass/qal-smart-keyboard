package com.qalsmartkeyboard.aigateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qalsmartkeyboard.aigateway.spi.Addressee;
import com.qalsmartkeyboard.aigateway.spi.QualityTier;

/**
 * What {@link AiPipeline#execute} returns, field for field the contract response bodies ({@code TranslateResponse},
 * {@code TransformResponse}, {@code RepliesResponse}, {@code DraftResponse}, {@code DetectLanguageResponse}), so B5.5
 * serialises them as they are. Absent optional members are null and omitted from JSON.
 *
 * <p>These records carry the result text for the client. Their {@code toString()} prints lengths instead, so logging a
 * result by accident leaks nothing (SD 19).
 */
public sealed interface AiResult permits AiResult.TranslateResponse, AiResult.TransformResponse,
		AiResult.RepliesResponse, AiResult.DraftResponse, AiResult.DetectLanguageResponse, AiResult.ChatResponse {

	/** Contract {@code UsageSummary}; null on a response for an unmetered feature. */
	UsageSummary usage();

	/** The same response carrying the id of the history item stored for it (B5.7); detection has none. */
	default AiResult withHistoryItemId(UUID historyItemId) {
		return this;
	}

	/** Contract {@code UsageSummary}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record UsageSummary(String featureCode, long unitsCharged, String unit, String usageSource, Long remaining,
			BigDecimal creditBalance, BigDecimal creditsCharged, Long allowanceUnitsCharged, Instant resetsAt) {
	}

	/** Contract {@code AiNote}: a short static message (never user text). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AiNote(String type, String message) {
	}

	/** Contract {@code Alternative}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Alternative(String label, String text, Addressee addressee) {

		@Override
		public String toString() {
			return "Alternative[" + length(text) + " chars]";
		}
	}

	/** Contract {@code TextEdit}: UTF-16 offsets into the input. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record TextEdit(int start, int end, String original, String replacement, String category) {

		@Override
		public String toString() {
			return "TextEdit[" + start + ".." + end + ", " + category + "]";
		}
	}

	/** Contract {@code TranslateResponse}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record TranslateResponse(UUID requestId, String sourceLanguage, String detectedSourceLanguage,
			Double detectionConfidence, String destinationLanguage, String translatedText,
			List<Alternative> alternatives, List<AiNote> notes, QualityTier qualityTier, boolean replayed,
			UUID historyItemId, UsageSummary usage) implements AiResult {

		@Override
		public AiResult withHistoryItemId(UUID id) {
			return new TranslateResponse(requestId, sourceLanguage, detectedSourceLanguage, detectionConfidence,
					destinationLanguage, translatedText, alternatives, notes, qualityTier, replayed, id, usage);
		}

		@Override
		public String toString() {
			return "TranslateResponse[" + requestId + ", " + sourceLanguage + "->" + destinationLanguage + ", "
					+ length(translatedText) + " chars, " + qualityTier + ", " + usage + "]";
		}
	}

	/** Contract {@code TransformResponse}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record TransformResponse(UUID requestId, String task, String language, String resultText, List<TextEdit> edits,
			List<Alternative> alternatives, List<AiNote> notes, QualityTier qualityTier, boolean replayed,
			UUID historyItemId, UsageSummary usage) implements AiResult {

		@Override
		public AiResult withHistoryItemId(UUID id) {
			return new TransformResponse(requestId, task, language, resultText, edits, alternatives, notes,
					qualityTier, replayed, id, usage);
		}

		@Override
		public String toString() {
			return "TransformResponse[" + requestId + ", " + task + ", " + language + ", " + length(resultText)
					+ " chars, " + qualityTier + ", " + usage + "]";
		}
	}

	/** Contract {@code RepliesResponse} (SMART_REPLY, ADR 0027). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record RepliesResponse(UUID requestId, String language, List<Suggestion> suggestions, List<AiNote> notes,
			QualityTier qualityTier, boolean replayed, UUID historyItemId, UsageSummary usage) implements AiResult {

		/** One suggested reply; {@code tone} echoes the requested tone (absent when none was asked for). */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Suggestion(String text, String tone) {

			@Override
			public String toString() {
				return "Suggestion[" + length(text) + " chars]";
			}
		}

		@Override
		public AiResult withHistoryItemId(UUID id) {
			return new RepliesResponse(requestId, language, suggestions, notes, qualityTier, replayed, id, usage);
		}

		@Override
		public String toString() {
			return "RepliesResponse[" + requestId + ", " + language + ", " + suggestions.size() + " suggestions, "
					+ qualityTier + ", " + usage + "]";
		}
	}

	/**
	 * Contract {@code DraftResponse} (DRAFT_DOCUMENT, ADR 0027).
	 *
	 * @param subject      absent when the request said {@code includeSubject: false} or an improved draft had none
	 * @param placeholders bracketed items in the subject or body the user must fill in ({@code [date]}), brackets
	 *                     included, in order of appearance; empty when there are none
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record DraftResponse(UUID requestId, String mode, String language, String subject, String body,
			List<String> placeholders, List<AiNote> notes, QualityTier qualityTier, boolean replayed,
			UUID historyItemId, UsageSummary usage) implements AiResult {

		@Override
		public AiResult withHistoryItemId(UUID id) {
			return new DraftResponse(requestId, mode, language, subject, body, placeholders, notes, qualityTier,
					replayed, id, usage);
		}

		@Override
		public String toString() {
			return "DraftResponse[" + requestId + ", " + mode + ", " + language + ", subject " + length(subject)
					+ " chars, body " + length(body) + " chars, " + (placeholders == null ? 0 : placeholders.size())
					+ " placeholders, " + qualityTier + ", " + usage + "]";
		}
	}

	/**
	 * Contract {@code ChatResponse} (AI_CHAT, ADR 0048): the assistant's next message. No history item: chat is never
	 * saved.
	 *
	 * @param language     the requested reply language; null when the request named none
	 * @param finishReason STOP, or LENGTH when the provider cut the reply off at its output limit
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record ChatResponse(UUID requestId, Message message, String language, String finishReason, List<AiNote> notes,
			QualityTier qualityTier, boolean replayed, UsageSummary usage) implements AiResult {

		/** Contract {@code ChatMessage}. */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Message(String role, String text) {

			@Override
			public String toString() {
				return "Message[" + role + ", " + length(text) + " chars]";
			}
		}

		@Override
		public String toString() {
			return "ChatResponse[" + requestId + ", " + language + ", " + message + ", " + finishReason + ", "
					+ qualityTier + ", " + usage + "]";
		}
	}

	/** Contract {@code DetectLanguageResponse} (no request id or usage in the contract; usage is always null). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record DetectLanguageResponse(List<DetectedLanguage> languages, boolean mixedLanguage, Boolean romanizedNative)
			implements AiResult {

		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record DetectedLanguage(String languageCode, String scriptCode, double confidence) {
		}

		@Override
		public UsageSummary usage() {
			return null;
		}
	}

	private static int length(String s) {
		return s == null ? 0 : s.codePointCount(0, s.length());
	}
}
