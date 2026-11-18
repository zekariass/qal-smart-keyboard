package com.qalsmartkeyboard.languages.pack;

import java.util.List;
import java.util.Optional;

/**
 * A Semantic Versioning 2.0.0 version as the pack format allows it (spec section 5.2): at most 40 characters, no build
 * metadata, no leading zeros, no {@code v} prefix, no whitespace; pre-release identifiers allowed. Mirrors the Rust
 * {@code semver} crate's parser and precedence, which the core uses for rollback protection.
 *
 * <p>Ordering is SemVer precedence: numeric fields compared numerically (unsigned 64-bit), a pre-release is lower than
 * its release, pre-release identifiers compared one by one (numeric numerically and below alphanumeric, alphanumeric in
 * ASCII order, a shorter list lower when it is a prefix).
 */
public final class SemVer implements Comparable<SemVer> {

	public static final int MAX_LENGTH = 40;

	private final String text;
	private final long major;
	private final long minor;
	private final long patch;
	private final List<String> preRelease;

	private SemVer(String text, long major, long minor, long patch, List<String> preRelease) {
		this.text = text;
		this.major = major;
		this.minor = minor;
		this.patch = patch;
		this.preRelease = preRelease;
	}

	/** The version, or empty when {@code s} is not a pack-format semantic version. */
	public static Optional<SemVer> parse(String s) {
		if (s == null || s.isEmpty() || s.length() > MAX_LENGTH) {
			return Optional.empty();
		}
		if (s.indexOf('+') >= 0) {
			return Optional.empty(); // build metadata is not allowed (and invalid build metadata is not either)
		}
		int dash = s.indexOf('-');
		String core = dash < 0 ? s : s.substring(0, dash);
		String[] parts = core.split("\\.", -1);
		if (parts.length != 3) {
			return Optional.empty();
		}
		long[] numbers = new long[3];
		for (int i = 0; i < 3; i++) {
			Long n = numeric(parts[i]);
			if (n == null) {
				return Optional.empty();
			}
			numbers[i] = n;
		}
		List<String> pre = List.of();
		if (dash >= 0) {
			String[] ids = s.substring(dash + 1).split("\\.", -1);
			for (String id : ids) {
				if (id.isEmpty() || !id.chars().allMatch(SemVer::identifierChar)) {
					return Optional.empty();
				}
				if (allDigits(id) && id.length() > 1 && id.charAt(0) == '0') {
					return Optional.empty(); // leading zero in a numeric pre-release identifier
				}
			}
			pre = List.of(ids);
		}
		return Optional.of(new SemVer(s, numbers[0], numbers[1], numbers[2], pre));
	}

	public boolean isPreRelease() {
		return !preRelease.isEmpty();
	}

	@Override
	public int compareTo(SemVer o) {
		int c = Long.compareUnsigned(major, o.major);
		if (c == 0) {
			c = Long.compareUnsigned(minor, o.minor);
		}
		if (c == 0) {
			c = Long.compareUnsigned(patch, o.patch);
		}
		if (c != 0) {
			return c;
		}
		if (preRelease.isEmpty() || o.preRelease.isEmpty()) {
			return Boolean.compare(preRelease.isEmpty(), o.preRelease.isEmpty());
		}
		for (int i = 0; i < Math.min(preRelease.size(), o.preRelease.size()); i++) {
			c = compareIdentifier(preRelease.get(i), o.preRelease.get(i));
			if (c != 0) {
				return c;
			}
		}
		return Integer.compare(preRelease.size(), o.preRelease.size());
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof SemVer v && compareTo(v) == 0;
	}

	@Override
	public int hashCode() {
		return text.hashCode();
	}

	/** The version as written (a parsed version has exactly one spelling). */
	@Override
	public String toString() {
		return text;
	}

	private static int compareIdentifier(String a, String b) {
		boolean an = allDigits(a);
		boolean bn = allDigits(b);
		if (an && bn) {
			// no leading zeros, so a longer number is larger (arbitrarily long numbers are allowed)
			return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
		}
		if (an != bn) {
			return an ? -1 : 1;
		}
		return a.compareTo(b);
	}

	/** A u64 without leading zeros, or null. */
	private static Long numeric(String s) {
		if (s.isEmpty() || !allDigits(s) || (s.length() > 1 && s.charAt(0) == '0')) {
			return null;
		}
		try {
			return Long.parseUnsignedLong(s);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private static boolean allDigits(String s) {
		return !s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9');
	}

	private static boolean identifierChar(int c) {
		return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-';
	}
}
