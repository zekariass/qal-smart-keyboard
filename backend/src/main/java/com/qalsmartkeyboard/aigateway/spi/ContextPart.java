package com.qalsmartkeyboard.aigateway.spi;

import java.util.Objects;

/**
 * A second (third...) piece of user-supplied content next to the main {@link TransformRequest#text()}, for tasks that
 * take more than one text (ADR 0027): the user's own notes on what a reply should say, a recipient or sender name. It
 * is untrusted data exactly like the main text: an adapter sends each part as its own delimited data unit (OpenAI: its
 * own user message) and describes it to the model only by its {@link Kind}, never by anything inside it. The text stays
 * in {@link UserText}, so printing a part prints a length.
 */
public record ContextPart(Kind kind, UserText text) {

	/** What a part is. {@link #description()} is the fixed, server-written label an adapter may give the model. */
	public enum Kind {
		/** The user's own description of what the result should say (any language). */
		USER_NOTES("the user's own notes on what the result should say or do (any language)"),
		/** The name of the person the result is addressed to. */
		RECIPIENT_NAME("the name of the recipient"),
		/** The recipient's role or title (for example "HR manager"). */
		RECIPIENT_ROLE("the recipient's role or title"),
		/** The name the result is signed with. */
		SENDER_NAME("the name of the sender (the user), to sign with");

		private final String description;

		Kind(String description) {
			this.description = description;
		}

		public String description() {
			return description;
		}
	}

	public ContextPart {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(text, "text");
	}

	public static ContextPart of(Kind kind, String text) {
		return new ContextPart(kind, UserText.of(text));
	}
}
