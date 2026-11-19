package com.qalsmartkeyboard.aigateway;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qalsmartkeyboard.aigateway.spi.QualityTier;

/**
 * Response bodies of the AI endpoints that are not {@link AiResult}s: contract {@code AiCapabilities}. (Requests are
 * read by {@link AiRequestParser}, not bound by the framework, see there.)
 */
final class AiDtos {

	private AiDtos() {
	}

	/** Contract {@code AiCapabilities}; {@code translationLanguages} since ADR 0040. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Capabilities(Instant generatedAt, List<Capability> items, List<TranslationLanguage> translationLanguages) {

		/** Contract {@code TranslationLanguage} (ADR 0040). */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		record TranslationLanguage(String code, String englishName, String nativeName, String scriptCode,
				String writingDirection, boolean asSource, boolean asDestination, boolean supportsAutoDetection,
				QualityTier qualityTier, boolean isTypingLanguage) {
		}

		@JsonInclude(JsonInclude.Include.NON_NULL)
		record Capability(String featureCode, String sourceLanguage, String destinationLanguage, QualityTier qualityTier,
				String execution, Boolean supportsAutoDetection, Boolean supportsStreaming, Integer maxInputCharacters) {
		}
	}
}
