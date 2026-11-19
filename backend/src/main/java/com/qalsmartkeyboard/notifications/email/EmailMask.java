package com.qalsmartkeyboard.notifications.email;

/** Masks an address for logs: first character of the local part and the domain, e.g. {@code a***@example.com}. */
final class EmailMask {

	private EmailMask() {
	}

	static String mask(String address) {
		if (address == null) {
			return "<invalid>";
		}
		int at = address.lastIndexOf('@');
		if (at < 1 || at == address.length() - 1) {
			return "<invalid>";
		}
		return address.charAt(0) + "***" + address.substring(at);
	}
}
