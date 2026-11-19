package com.qalsmartkeyboard.identity.email;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * E-mail address rules (ADR 0033).
 *
 * <ul>
 * <li>Trimmed. One {@code @}; the local part is an RFC 5322 dot-atom of ASCII characters (no quoted strings, no
 * comments, no leading, trailing or double dots), at most 64 characters. Non-ASCII local parts (RFC 6531) are refused:
 * PostgreSQL's {@code LOWER()} in V1 {@code ck_users_email_normalized} and Java's lower-casing could disagree on them,
 * and few mail systems deliver them.</li>
 * <li>The domain may be internationalised: it is converted to its ASCII (punycode) form with {@link IDN#toASCII} and
 * lower-cased. At least two labels, letters/digits/hyphens, a TLD that is alphabetic or {@code xn--}.</li>
 * <li>{@code address} (stored in {@code users.email}, used to send mail) keeps the local part as typed;
 * {@code normalized} ({@code users.email_normalized}, the unique key and the {@code EMAIL} identity's subject) is
 * fully lower-case, so addresses that differ only in case are one account. Provider-specific folding (Gmail dots,
 * {@code +tags}) is not applied: it is not universal.</li>
 * </ul>
 */
final class EmailAddresses {

	static final int MAX_LENGTH = 254;
	private static final Pattern LOCAL = Pattern
			.compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$");
	private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
	private static final Pattern TLD = Pattern.compile("^([a-z]{2,63}|xn--[a-z0-9-]{1,59})$");

	/**
	 * @param address    as stored and used for delivery: local part as typed, ASCII lower-case domain
	 * @param normalized the lower-case key
	 */
	record Address(String address, String normalized) {

		/** Never prints the address. */
		@Override
		public String toString() {
			return "Address[<redacted>]";
		}
	}

	private EmailAddresses() {
	}

	/** @throws IllegalArgumentException with a message safe to return to the client (it never echoes the input) */
	static Address parse(String raw) {
		if (raw == null) {
			throw new IllegalArgumentException("An e-mail address is required.");
		}
		String trimmed = raw.strip();
		int at = trimmed.lastIndexOf('@');
		if (at <= 0 || at == trimmed.length() - 1 || trimmed.indexOf('@') != at) {
			throw new IllegalArgumentException("Enter a valid e-mail address.");
		}
		String local = trimmed.substring(0, at);
		String domainInput = trimmed.substring(at + 1);
		if (local.length() > 64 || !LOCAL.matcher(local).matches()) {
			throw new IllegalArgumentException("Enter a valid e-mail address.");
		}
		String domain;
		try {
			domain = IDN.toASCII(domainInput, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Enter a valid e-mail address.");
		}
		if (domain.endsWith(".")) {
			domain = domain.substring(0, domain.length() - 1);
		}
		String[] labels = domain.split("\\.", -1);
		if (labels.length < 2 || domain.length() > 253) {
			throw new IllegalArgumentException("Enter a valid e-mail address.");
		}
		for (String label : labels) {
			if (!LABEL.matcher(label).matches()) {
				throw new IllegalArgumentException("Enter a valid e-mail address.");
			}
		}
		if (!TLD.matcher(labels[labels.length - 1]).matches()) {
			throw new IllegalArgumentException("Enter a valid e-mail address.");
		}
		String address = local + "@" + domain;
		if (address.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("Enter a valid e-mail address.");
		}
		return new Address(address, address.toLowerCase(Locale.ROOT));
	}
}
