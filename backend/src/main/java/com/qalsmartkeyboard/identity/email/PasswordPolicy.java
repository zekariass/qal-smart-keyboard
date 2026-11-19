package com.qalsmartkeyboard.identity.email;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Password rules for e-mail accounts (ADR 0033, NIST SP 800-63B style): length 10 to 128 characters (the contract's
 * {@code minLength}/{@code maxLength}, counted in Unicode code points after NFKC), no composition rules, and a deny
 * list: a small bundled list of common passwords ({@code identity/common-passwords.txt}), a common base word with only
 * digits or symbols around it, one repeated character, a run of a keyboard row or of the alphabet/digits, and the
 * account's own e-mail address or its local part.
 *
 * <p>{@link #normalize} is applied before checking <em>and</em> before hashing (sign-up, reset and sign-in), so the same
 * password typed with a different Unicode composition still matches.
 */
final class PasswordPolicy {

	static final int MIN_LENGTH = 10;
	static final int MAX_LENGTH = 128;

	/** Field error codes of {@code VALIDATION_FAILED} (contract: {@code errors[].code}). */
	static final String TOO_SHORT = "PASSWORD_TOO_SHORT";
	static final String TOO_LONG = "PASSWORD_TOO_LONG";
	static final String TOO_COMMON = "PASSWORD_TOO_COMMON";
	static final String MATCHES_EMAIL = "PASSWORD_MATCHES_EMAIL";

	record Violation(String code, String message) {
	}

	private static final List<String> SEQUENCES = List.of("01234567890123456789012345678901",
			"98765432109876543210987654321098", "abcdefghijklmnopqrstuvwxyzabcdefghij", "zyxwvutsrqponmlkjihgfedcba",
			"qwertyuiopasdfghjklzxcvbnmqwertyuiop", "poiuytrewqlkjhgfdsamnbvcxz", "1qaz2wsx3edc4rfv5tgb6yhn7ujm8ik9ol0p",
			"1q2w3e4r5t6y7u8i9o0p", "q1w2e3r4t5y6u7i8o9p0", "zxcvbnmasdfghjklqwertyuiop");

	private final Set<String> full;
	private final Set<String> base;

	private PasswordPolicy(Set<String> full, Set<String> base) {
		this.full = Set.copyOf(full);
		this.base = Set.copyOf(base);
	}

	/** Loads the bundled list; fails the startup if it is missing. */
	static PasswordPolicy bundled() {
		try (InputStream in = PasswordPolicy.class.getResourceAsStream("/identity/common-passwords.txt")) {
			if (in == null) {
				throw new IllegalStateException("identity/common-passwords.txt is missing from the classpath");
			}
			Set<String> full = new HashSet<>();
			Set<String> base = new HashSet<>();
			Set<String> target = full;
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				String entry = line.strip();
				if (entry.isEmpty() || entry.startsWith("#")) {
					continue;
				}
				if (entry.equals("[full]")) {
					target = full;
				}
				else if (entry.equals("[base]")) {
					target = base;
				}
				else {
					target.add(normalize(entry).toLowerCase(Locale.ROOT));
				}
			}
			return new PasswordPolicy(full, base);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Unicode NFKC; never trims (spaces are allowed and significant). */
	static String normalize(String password) {
		return Normalizer.normalize(password, Normalizer.Form.NFKC);
	}

	/**
	 * @param password the password as received (normalised here)
	 * @param email    the account's address, or {@code null} when unknown
	 * @return the first rule it breaks, empty if acceptable
	 */
	Optional<Violation> check(String password, EmailAddresses.Address email) {
		String normalized = normalize(password);
		int length = normalized.codePointCount(0, normalized.length());
		if (length < MIN_LENGTH) {
			return Optional.of(new Violation(TOO_SHORT, "Use at least " + MIN_LENGTH + " characters."));
		}
		if (length > MAX_LENGTH) {
			return Optional.of(new Violation(TOO_LONG, "Use at most " + MAX_LENGTH + " characters."));
		}
		String lower = normalized.toLowerCase(Locale.ROOT);
		if (email != null) {
			String local = email.normalized().substring(0, email.normalized().lastIndexOf('@'));
			if (lower.equals(email.normalized()) || lower.equals(local) || core(lower).equals(local)) {
				return Optional.of(new Violation(MATCHES_EMAIL, "Do not use your e-mail address as the password."));
			}
		}
		if (full.contains(lower) || base.contains(core(lower)) || oneCharacter(lower) || isSequence(lower)) {
			return Optional.of(new Violation(TOO_COMMON,
					"This password is too common. Use a longer phrase that is not easy to guess."));
		}
		return Optional.empty();
	}

	/** The password without digits and symbols at its start and end ({@code "Password2024!"} -> {@code "password"}). */
	private static String core(String lower) {
		int start = 0;
		int end = lower.length();
		while (start < end && !Character.isLetter(lower.charAt(start))) {
			start++;
		}
		while (end > start && !Character.isLetter(lower.charAt(end - 1))) {
			end--;
		}
		return lower.substring(start, end);
	}

	private static boolean oneCharacter(String lower) {
		return lower.codePoints().distinct().count() <= 2;
	}

	private static boolean isSequence(String lower) {
		for (String sequence : SEQUENCES) {
			if (sequence.contains(lower)) {
				return true;
			}
		}
		return false;
	}
}
