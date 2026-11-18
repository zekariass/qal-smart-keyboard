package com.qalsmartkeyboard.aigateway.spi;

import java.util.Objects;

/**
 * Grammar, rewrite, tone, summarise... through an LLM; maps to the contract's {@code TransformRequest}. The pipeline
 * renders the versioned prompt template (B5.3) into {@code instructions}; the adapter sends {@code text} as delimited,
 * untrusted data next to it and gives the model no tools.
 *
 * @param language language of {@code text}; null = unknown
 * @param mode contract {@code options.mode} (GRAMMAR, NATURAL, SHORTEN...) or null
 * @param tone contract {@code options.tone} or null
 * @param targetLength contract {@code options.targetLength} or null
 * @param explainIn contract {@code options.explainIn} or null
 * @param instructions rendered template (never user content); null for adapters with built-in prompts
 */
public record TransformRequest(AiFeature task, UserText text, String language, String mode, String tone,
		String targetLength, String explainIn, Addressee addressee, String instructions) {

	public TransformRequest {
		Objects.requireNonNull(task, "task");
		Objects.requireNonNull(text, "text");
		if (task.operation() != AiFeature.Operation.TRANSFORM) {
			throw new IllegalArgumentException("not a transform feature: " + task);
		}
		addressee = addressee == null ? Addressee.UNSPECIFIED : addressee;
	}

	public static TransformRequest of(AiFeature task, String text, String language) {
		return new TransformRequest(task, UserText.of(text), language, null, null, null, null, null, null);
	}
}
