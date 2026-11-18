package com.qalsmartkeyboard.aigateway.spi;

import java.util.Objects;

/**
 * Maps to the contract's {@code TranslateCoreRequest}. {@code sourceLanguage} null means auto-detect; the provider
 * reports what it detected. Glossary and style resolution are the pipeline's (B5.3).
 *
 * @param style optional contract {@code TranslationStyle} code, passed through
 * @param instructions optional rendered prompt instructions (LLM-backed translation); never user content
 */
public record TranslateRequest(UserText text, String sourceLanguage, String destinationLanguage, String style,
		boolean preserveNames, Addressee addressee, String instructions) {

	public TranslateRequest {
		Objects.requireNonNull(text, "text");
		Objects.requireNonNull(destinationLanguage, "destinationLanguage");
		addressee = addressee == null ? Addressee.UNSPECIFIED : addressee;
	}

	public static TranslateRequest of(String text, String sourceLanguage, String destinationLanguage) {
		return new TranslateRequest(UserText.of(text), sourceLanguage, destinationLanguage, null, true, null, null);
	}
}
