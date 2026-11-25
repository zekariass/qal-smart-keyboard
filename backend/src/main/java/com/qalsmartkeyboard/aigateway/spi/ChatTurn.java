package com.qalsmartkeyboard.aigateway.spi;

import java.util.Objects;

/**
 * One turn of an AI_CHAT conversation (ADR 0048). The conversation is held by the client and sent whole with every
 * request; the server keeps none. Both roles are untrusted data: there is no system role, so nothing a client sends
 * can become an instruction. The text stays in {@link UserText}, so printing a turn prints a length.
 */
public record ChatTurn(Role role, UserText text) {

	/** Who wrote the turn. */
	public enum Role {
		USER, ASSISTANT
	}

	public ChatTurn {
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(text, "text");
	}

	public static ChatTurn user(String text) {
		return new ChatTurn(Role.USER, UserText.of(text));
	}

	public static ChatTurn assistant(String text) {
		return new ChatTurn(Role.ASSISTANT, UserText.of(text));
	}
}
