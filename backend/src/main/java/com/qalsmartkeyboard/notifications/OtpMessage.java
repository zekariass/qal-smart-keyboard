package com.qalsmartkeyboard.notifications;

import java.time.Duration;
import java.util.Objects;

/**
 * One OTP to deliver.
 *
 * @param phoneE164 destination, E.164 ({@code +251911234567})
 * @param code      the plaintext code; exists only in memory between generation and delivery
 * @param validity  how long the code is valid (shown to the user)
 * @param uiLocale  preferred message language from {@code Accept-Language}, may be {@code null}
 */
public record OtpMessage(String phoneE164, String code, Duration validity, String uiLocale) {

	public OtpMessage {
		Objects.requireNonNull(phoneE164, "phoneE164");
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(validity, "validity");
	}

	/** Never prints the code or the number. */
	@Override
	public String toString() {
		return "OtpMessage[phoneE164=<redacted>, code=<redacted>, validity=" + validity + ", uiLocale=" + uiLocale + "]";
	}
}
