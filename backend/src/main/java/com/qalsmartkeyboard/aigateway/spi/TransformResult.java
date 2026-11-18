package com.qalsmartkeyboard.aigateway.spi;

import java.util.List;
import java.util.Objects;

/** Maps to {@code TransformResponse}'s content fields. */
public record TransformResult(UserText resultText, List<TextEdit> edits, ProviderCallMetadata call,
		ProviderUsage usage) {

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
