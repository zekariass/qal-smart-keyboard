package com.qalsmartkeyboard.aigateway.spi;

import java.util.List;
import java.util.Objects;

/** Maps to {@code DetectLanguageRequest}; {@code candidates} empty = any language. */
public record DetectRequest(UserText text, List<String> candidates) {

	public DetectRequest {
		Objects.requireNonNull(text, "text");
		candidates = candidates == null ? List.of() : List.copyOf(candidates);
	}

	public static DetectRequest of(String text) {
		return new DetectRequest(UserText.of(text), List.of());
	}
}
