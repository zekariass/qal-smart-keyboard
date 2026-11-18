package com.qalsmartkeyboard.languages.pack;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The commercial-use licence allow-list (rule N10), read from {@code language-data/licenses.json}
 * ({@code format: qal-licence-allowlist}, version 1), the same file and the same rules as the pack build's licence gate
 * ({@code language-data/scripts/build_packs.py check_license}, L1.2):
 * <ul>
 * <li>a missing or blank code fails ({@link Verdict#MISSING});</li>
 * <li>a code not listed fails closed ({@link Verdict#UNKNOWN});</li>
 * <li>a code whose entry is not {@code commercialUse: true}, or whose id contains an {@code NC} component (any
 * {@code -NC} licence, even if marked commercial), fails ({@link Verdict#NOT_COMMERCIAL}).</li>
 * </ul>
 */
public final class LicenseAllowList {

	/** {@code (^|[-_.])NC([-_.]|$)}, case-insensitive: the build script's {@code _NC_RE}. */
	private static final Pattern NON_COMMERCIAL = Pattern.compile("(^|[-_.])NC([-_.]|$)", Pattern.CASE_INSENSITIVE);

	public enum Verdict {
		ALLOWED, MISSING, UNKNOWN, NOT_COMMERCIAL
	}

	/** An allow-list entry ({@code consentBasis} feeds {@code language_datasets.consent_basis}). */
	public record Entry(boolean commercialUse, String consentBasis, String url) {
	}

	private final Map<String, Entry> entries;

	private LicenseAllowList(Map<String, Entry> entries) {
		this.entries = Map.copyOf(entries);
	}

	public static LicenseAllowList load(InputStream json) throws IOException {
		JsonNode doc;
		try {
			doc = JsonMapper.builder().build().readTree(json);
		}
		catch (JacksonException e) {
			throw new IOException("licence allow-list is not valid JSON");
		}
		if (!"qal-licence-allowlist".equals(doc.path("format").asString(null)) || doc.path("formatVersion").asInt(0) != 1
				|| !doc.path("licenses").isObject()) {
			throw new IOException("not a qal-licence-allowlist v1 document");
		}
		Map<String, Entry> entries = new LinkedHashMap<>();
		for (var e : doc.get("licenses").properties()) {
			JsonNode v = e.getValue();
			boolean commercial = v.path("commercialUse").isBoolean() && v.path("commercialUse").asBoolean();
			entries.put(e.getKey(), new Entry(commercial, v.path("consentBasis").asString("UNKNOWN"),
					emptyToNull(v.path("url").asString(null))));
		}
		return new LicenseAllowList(entries);
	}

	public Verdict check(String code) {
		if (code == null || code.isBlank()) {
			return Verdict.MISSING;
		}
		Entry entry = entries.get(code);
		if (entry == null) {
			return Verdict.UNKNOWN;
		}
		if (!entry.commercialUse() || NON_COMMERCIAL.matcher(code).find()) {
			return Verdict.NOT_COMMERCIAL;
		}
		return Verdict.ALLOWED;
	}

	public Optional<Entry> entry(String code) {
		return Optional.ofNullable(code == null ? null : entries.get(code));
	}

	public int size() {
		return entries.size();
	}

	private static String emptyToNull(String s) {
		return s == null || s.isEmpty() ? null : s;
	}
}
