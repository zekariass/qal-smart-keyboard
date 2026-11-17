package com.qalsmartkeyboard.notifications.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development/test SMS provider ({@code qal.notifications.sms.provider=log}): sends nothing and logs a masked number.
 * The message body (which contains the code) is logged only when {@code qal.notifications.sms.log.include-message=true},
 * a development-only switch so a developer can sign in locally without a phone. Both are refused under the
 * {@code prod}, {@code production} and {@code staging} profiles (see {@link SmsConfiguration}).
 */
final class LoggingSmsProvider implements SmsProvider {

	private static final Logger log = LoggerFactory.getLogger(LoggingSmsProvider.class);

	private final boolean includeMessage;

	LoggingSmsProvider(boolean includeMessage) {
		this.includeMessage = includeMessage;
	}

	@Override
	public String id() {
		return "log";
	}

	@Override
	public void send(String toE164, String body) {
		if (includeMessage) {
			log.warn("DEV ONLY mock SMS to {}: {}", PhoneMask.mask(toE164), body);
		}
		else {
			log.info("Mock SMS to {} accepted ({} characters, body not logged)", PhoneMask.mask(toE164), body.length());
		}
	}
}
