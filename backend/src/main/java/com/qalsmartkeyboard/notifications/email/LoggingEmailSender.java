package com.qalsmartkeyboard.notifications.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development/test e-mail provider ({@code qal.notifications.email.provider=log}): sends nothing and logs a masked
 * address. The body (which contains the code) is logged only when
 * {@code qal.notifications.email.log.include-message=true}, a development-only switch. Both are refused under the
 * {@code prod}, {@code production} and {@code staging} profiles (see {@link EmailConfiguration}).
 */
final class LoggingEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

	private final boolean includeMessage;

	LoggingEmailSender(boolean includeMessage) {
		this.includeMessage = includeMessage;
	}

	@Override
	public String id() {
		return "log";
	}

	@Override
	public void send(String to, String subject, String textBody) {
		if (includeMessage) {
			log.warn("DEV ONLY mock e-mail to {}: {} / {}", EmailMask.mask(to), subject, textBody);
		}
		else {
			log.info("Mock e-mail to {} accepted ({} characters, body not logged)", EmailMask.mask(to),
					textBody.length());
		}
	}
}
