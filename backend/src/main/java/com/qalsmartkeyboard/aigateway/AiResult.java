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
 * {@code TransformResponse}, {@code DetectLanguageResponse}), so B5.5 serialises them as they are. Absent optional
 * members are null and omitted from JSON.
 *
 * <p>These records carry the result text for the client. Their {@code toString()} prints lengths instead, so logging a
 * result by accident leaks nothing (SD 19).
 */
public sealed interface AiResult permits AiResult.TranslateResponse, AiResult.TransformResponse,
		AiResult.DetectLanguageResponse {

	/** Contract {@code UsageSummary}; null on a response for an unmetered feature. */
	UsageSummary usage();

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
		public String toString() {
			return "TransformResponse[" + requestId + ", " + task + ", " + language + ", " + length(resultText)
					+ " chars, " + qualityTier + ", " + usage + "]";
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
