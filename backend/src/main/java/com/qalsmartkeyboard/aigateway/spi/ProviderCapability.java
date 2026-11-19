package com.qalsmartkeyboard.aigateway.spi;

import java.util.Objects;
import java.util.Set;

/**
 * One enabled row of the capability matrix ({@code ai_language_capabilities}) on an ACTIVE model of the provider. A null
 * language means "any". {@code maxInputCharacters} null = no declared limit.
 *
 * <p>ADR 0040: a null side may be limited to a language set ({@code sourceLanguages}, {@code destinationLanguages}),
 * the model's {@code ai_model_translation_languages} for that role. The registry loads a set for every TRANSLATE row
 * with a null side, so one "any pair" row per model stands for N languages instead of N x N pair rows. A null set means
 * "unrestricted" (transforms and detection keep the V1 meaning of a null language); an empty set matches nothing.
 */
public record ProviderCapability(AiFeature feature, String sourceLanguage, String destinationLanguage,
		QualityTier qualityTier, boolean supportsAutoDetection, Integer maxInputCharacters, String modelCode,
		Set<String> sourceLanguages, Set<String> destinationLanguages) {

	public ProviderCapability {
		Objects.requireNonNull(feature, "feature");
		Objects.requireNonNull(qualityTier, "qualityTier");
		sourceLanguages = sourceLanguage != null || sourceLanguages == null ? null : Set.copyOf(sourceLanguages);
		destinationLanguages = destinationLanguage != null || destinationLanguages == null ? null
				: Set.copyOf(destinationLanguages);
	}

	/** A row without language sets: a null language means any language. */
	public ProviderCapability(AiFeature feature, String sourceLanguage, String destinationLanguage,
			QualityTier qualityTier, boolean supportsAutoDetection, Integer maxInputCharacters, String modelCode) {
		this(feature, sourceLanguage, destinationLanguage, qualityTier, supportsAutoDetection, maxInputCharacters,
				modelCode, null, null);
	}

	/** Language sets as their sizes: a full set would put ~190 codes into every log line and assertion message. */
	@Override
	public String toString() {
		return "ProviderCapability[" + feature + " " + side(sourceLanguage, sourceLanguages) + " -> "
				+ side(destinationLanguage, destinationLanguages) + ", " + qualityTier + ", autoDetect="
				+ supportsAutoDetection + ", max=" + maxInputCharacters + ", model=" + modelCode + "]";
	}

	private static String side(String language, Set<String> set) {
		return language != null ? language : set != null ? "{" + set.size() + " languages}" : "*";
	}

	/** True when this row serves {@code language} as a source (its fixed language, a member of its set, or any). */
	public boolean servesSource(String language) {
		return sourceLanguage != null ? sourceLanguage.equals(language)
				: sourceLanguages == null || sourceLanguages.contains(language);
	}

	/** True when this row serves {@code language} as a destination. */
	public boolean servesDestination(String language) {
		return destinationLanguage != null ? destinationLanguage.equals(language)
				: destinationLanguages == null || destinationLanguages.contains(language);
	}

	/**
	 * Language pair match. An auto-detect request (null source) needs a provider that detects, except for language
	 * identification itself. For transform features the destination language is null on both sides. A translation into
	 * the source language itself never matches.
	 */
	boolean matches(RouteQuery q) {
		if (feature != q.feature() || !qualityTier.atLeast(q.minimumTier())) {
			return false;
		}
		if (q.inputCharacters() > 0 && maxInputCharacters != null && q.inputCharacters() > maxInputCharacters) {
			return false;
		}
		if (feature == AiFeature.TRANSLATE && q.sourceLanguage() != null
				&& q.sourceLanguage().equals(q.destinationLanguage())) {
			return false;
		}
		boolean sourceOk = q.sourceLanguage() == null
				? (feature == AiFeature.DETECT_LANGUAGE || supportsAutoDetection)
				: servesSource(q.sourceLanguage());
		boolean destinationOk = destinationLanguage == null && destinationLanguages == null
				|| servesDestination(q.destinationLanguage());
		return sourceOk && destinationOk;
	}
}
