package com.qalsmartkeyboard.aigateway.spi;

/** The text AI features a provider adapter can serve; {@code name()} equals {@code feature_catalog.feature_code}. */
public enum AiFeature {
	TRANSLATE(Operation.TRANSLATE),
	DETECT_LANGUAGE(Operation.DETECT_LANGUAGE),
	GRAMMAR_CORRECTION(Operation.TRANSFORM),
	REWRITE(Operation.TRANSFORM),
	TONE_ADJUST(Operation.TRANSFORM),
	SUMMARIZE(Operation.TRANSFORM),
	EXPLAIN_TEXT(Operation.TRANSFORM),
	SCRIPT_CONVERSION(Operation.TRANSFORM),
	SMART_REPLY(Operation.TRANSFORM),
	DRAFT_DOCUMENT(Operation.TRANSFORM);

	/** The {@link AiProvider} method that serves a feature. */
	public enum Operation {
		TRANSLATE, TRANSFORM, DETECT_LANGUAGE
	}

	private final Operation operation;

	AiFeature(Operation operation) {
		this.operation = operation;
	}

	public Operation operation() {
		return operation;
	}

	/** The feature for a catalogue code, or {@code null} for codes the text SPI does not serve (voice, platform...). */
	public static AiFeature fromCode(String featureCode) {
		for (AiFeature f : values()) {
			if (f.name().equals(featureCode)) {
				return f;
			}
		}
		return null;
	}
}
