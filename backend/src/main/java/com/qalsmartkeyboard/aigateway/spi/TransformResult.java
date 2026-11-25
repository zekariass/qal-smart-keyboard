package com.qalsmartkeyboard.aigateway.spi;

import java.util.List;
import java.util.Objects;

/**
 * Maps to {@code TransformResponse}'s content fields.
 *
 * @param truncated the provider stopped at its output limit; only AI_CHAT accepts such a result (a chat reply that is
 *                  cut off is still useful text, ADR 0048), every other task treats it as a failed call
 */
public record TransformResult(UserText resultText, List<TextEdit> edits, ProviderCallMetadata call,
		ProviderUsage usage, boolean truncated) {

	public TransformResult(UserText resultText, List<TextEdit> edits, ProviderCallMetadata call, ProviderUsage usage) {
		this(resultText, edits, call, usage, false);
	}

	/** Contract {@code TextEdit}: UTF-16 offsets into the input. */
	public record TextEdit(int start, int end, UserText replacement, String category) {
	}

	public TransformResult {
		Objects.requireNonNull(resultText, "resultText");
		Objects.requireNonNull(call, "call");
		Objects.requireNonNull(usage, "usage");
		edits = edits == null ? List.of() : List.copyOf(edits);
	}
}
