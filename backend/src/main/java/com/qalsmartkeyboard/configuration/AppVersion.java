package com.qalsmartkeyboard.configuration;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic app version with SemVer 2.0 precedence.
 *
 * <ul>
 * <li>Accepted: {@code MAJOR[.MINOR[.PATCH]][-prerelease][+build]}; missing minor/patch count as 0
 * ({@code 2.1} equals {@code 2.1.0}). Each number has 1-9 digits; the whole value is at most 40 characters
 * (the contract's {@code maxLength}). Anything else (4 components, {@code v} prefix, blanks) is malformed.</li>
 * <li>Numbers compare numerically ({@code 1.10.0} is greater than {@code 1.9.0}).</li>
 * <li>A pre-release is lower than the same release ({@code 2.0.0-beta.1} is lower than {@code 2.0.0}); pre-release
 * identifiers compare per SemVer: numeric ones numerically and lower than alphanumeric ones, alphanumeric ones in
 * ASCII order, a shorter list is lower when all shared identifiers are equal. So a debug/beta build of the minimum
 * version is itself below the minimum.</li>
 * <li>Build metadata ({@code +...}) is ignored for precedence: {@code 2.0.0+5} equals {@code 2.0.0}.</li>
 * </ul>
 */
public record AppVersion(int major, int minor, int patch, String preRelease) implements Comparable<AppVersion> {

	static final int MAX_LENGTH = 40;
	private static final String IDENTS = "[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*";
	private static final Pattern FORMAT = Pattern
			.compile("^(\\d{1,9})(?:\\.(\\d{1,9}))?(?:\\.(\\d{1,9}))?(?:-(" + IDENTS + "))?(?:\\+" + IDENTS + ")?$");

	public static Optional<AppVersion> parse(String value) {
		if (value == null || value.length() > MAX_LENGTH) {
			return Optional.empty();
		}
		Matcher m = FORMAT.matcher(value);
		if (!m.matches()) {
			return Optional.empty();
		}
		String pre = m.group(4);
		if (pre != null) {
			for (String id : pre.split("\\.")) {
				// SemVer: numeric identifiers must not have leading zeros
				if (isNumeric(id) && id.length() > 1 && id.charAt(0) == '0') {
					return Optional.empty();
				}
				if (isNumeric(id) && id.length() > 18) {
					return Optional.empty();
				}
			}
		}
		return Optional.of(new AppVersion(Integer.parseInt(m.group(1)), number(m.group(2)), number(m.group(3)), pre));
	}

	private static int number(String s) {
		return s == null ? 0 : Integer.parseInt(s);
	}

	private static boolean isNumeric(String s) {
		return s.chars().allMatch(c -> c >= '0' && c <= '9');
	}

	@Override
	public int compareTo(AppVersion o) {
		int c = Integer.compare(major, o.major);
		if (c == 0) {
			c = Integer.compare(minor, o.minor);
		}
		if (c == 0) {
			c = Integer.compare(patch, o.patch);
		}
		if (c != 0) {
			return c;
		}
		if (preRelease == null || o.preRelease == null) {
			return preRelease == null ? (o.preRelease == null ? 0 : 1) : -1;
		}
		String[] a = preRelease.split("\\.");
		String[] b = o.preRelease.split("\\.");
		for (int i = 0; i < Math.min(a.length, b.length); i++) {
			boolean an = isNumeric(a[i]);
			boolean bn = isNumeric(b[i]);
			int r;
			if (an && bn) {
				r = Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]));
			}
			else if (an != bn) {
				r = an ? -1 : 1;
			}
			else {
				r = a[i].compareTo(b[i]);
			}
			if (r != 0) {
				return r;
			}
		}
		return Integer.compare(a.length, b.length);
	}

	/** Canonical text (no build metadata). */
	@Override
	public String toString() {
		return major + "." + minor + "." + patch + (preRelease == null ? "" : "-" + preRelease);
	}
}
