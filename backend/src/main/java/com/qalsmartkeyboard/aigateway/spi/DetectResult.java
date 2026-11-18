package com.qalsmartkeyboard.aigateway.spi;

import java.util.List;
import java.util.Objects;

/** Maps to {@code DetectLanguageResponse}; {@code languages} ranked best first. */
public record DetectResult(List<DetectedLanguage> languages, boolean mixedLanguage, boolean romanizedNative,
		ProviderCallMetadata call, ProviderUsage usage) {

	public record DetectedLanguage(String languageCode, String scriptCode, double confidence) {
	}

	public DetectResult {
		Objects.requireNonNull(call, "call");
		Objects.requireNonNull(usage, "usage");
		languages = languages == null ? List.of() : List.copyOf(languages);
	}
}
