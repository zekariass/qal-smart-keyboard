package com.qalsmartkeyboard.aigateway.spi;

import java.util.List;
import java.util.Objects;

/** Maps to {@code TranslateResponse}'s content fields; the pipeline adds requestId, tier and usage summary. */
public record TranslateResult(UserText translatedText, String detectedSourceLanguage, Double detectionConfidence,
		List<UserText> alternatives, ProviderCallMetadata call, ProviderUsage usage) {

	public TranslateResult {
		Objects.requireNonNull(translatedText, "translatedText");
		Objects.requireNonNull(call, "call");
		Objects.requireNonNull(usage, "usage");
		alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
	}
}
