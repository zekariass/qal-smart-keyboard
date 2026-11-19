package com.qalsmartkeyboard.notifications;

import java.time.Duration;
import java.util.Objects;

/**
 * One e-mail code to deliver.
 *
 * @param to       destination address (as stored: local part as typed, domain in lower-case ASCII)
 * @param code     the plaintext code; exists only in memory between generation and delivery
 * @param validity how long the code is valid (shown to the user)
 * @param kind     which message to render
 */
public record EmailCodeMessage(String to, String code, Duration validity, Kind kind) {

	/** The three e-mail code messages. */
	public enum Kind {
		/** A sign-up that must prove control of the mailbox before the account exists. */
		SIGN_UP,
		/** An existing account whose address is not verified yet signs in. */
		VERIFY_EMAIL,
		/** "Forgot password". */
		PASSWORD_RESET
	}

	public EmailCodeMessage {
		Objects.requireNonNull(to, "to");
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(validity, "validity");
		Objects.requireNonNull(kind, "kind");
	}

	/** Never prints the code or the address. */
	@Override
	public String toString() {
		return "EmailCodeMessage[to=<redacted>, code=<redacted>, validity=" + validity + ", kind=" + kind + "]";
	}
}
