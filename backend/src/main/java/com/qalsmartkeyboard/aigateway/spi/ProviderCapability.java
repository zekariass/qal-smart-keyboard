package com.qalsmartkeyboard.aigateway.spi;

import java.util.Objects;

/**
 * One enabled row of the capability matrix ({@code ai_language_capabilities}) on an ACTIVE model of the provider. A null
 * language means "any". {@code maxInputCharacters} null = no declared limit.
 */
public record ProviderCapability(AiFeature feature, String sourceLanguage, String destinationLanguage,
		QualityTier qualityTier, boolean supportsAutoDetection, Integer maxInputCharacters, String modelCode) {

	public ProviderCapability {
		Objects.requireNonNull(feature, "feature");
		Objects.requireNonNull(qualityTier, "qualityTier");
	}

	/**
	 * Language pair match. An auto-detect request (null source) needs a provider that detects, except for language
	 * identification itself. For transform features the destination language is null on both sides.
	 */
	boolean matches(RouteQuery q) {
		if (feature != q.feature() || !qualityTier.atLeast(q.minimumTier())) {
			return false;
		}
		if (q.inputCharacters() > 0 && maxInputCharacters != null && q.inputCharacters() > maxInputCharacters) {
			return false;
		}
		boolean sourceOk = q.sourceLanguage() == null
				? (feature == AiFeature.DETECT_LANGUAGE || supportsAutoDetection)
				: (sourceLanguage == null || sourceLanguage.equals(q.sourceLanguage()));
		boolean destinationOk = destinationLanguage == null || destinationLanguage.equals(q.destinationLanguage());
		return sourceOk && destinationOk;
	}
}
