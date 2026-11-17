package com.qalsmartkeyboard.notifications.sms;

/** Masks an E.164 number for logs: country prefix (first 4 characters) and last 2 digits, e.g. {@code +251*******67}. */
final class PhoneMask {

	private PhoneMask() {
	}

	static String mask(String e164) {
		if (e164 == null || e164.length() < 8) {
			return "<invalid>";
		}
		return e164.substring(0, 4) + "*".repeat(e164.length() - 6) + e164.substring(e164.length() - 2);
	}
}
