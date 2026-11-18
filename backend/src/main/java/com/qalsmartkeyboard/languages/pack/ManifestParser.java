package com.qalsmartkeyboard.languages.pack;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses and validates an <b>authenticated</b> manifest (pack spec section 5, verification step 8), mirroring the Rust
 * {@code parse_manifest}: first the JSON shape (any failure, including a missing member, a wrong JSON type or a duplicated
 * defined member, is {@code INVALID_MANIFEST}), then the members in the core's order, where an unknown
 * {@code resourceType} is {@code UNSUPPORTED_RESOURCE_TYPE}.
 *
 * <p>JSON rules matched to {@code serde_json}: UTF-8 without BOM (invalid UTF-8 anywhere is rejected), the first byte is
 * {@code {}, trailing whitespace only, unknown members ignored (whatever their value), defined members not duplicated,
 * {@code sizeBytes} a non-negative integer token below 2^64 (no fraction, exponent or {@code -0}), strings without lone
 * surrogates. Nesting: {@code serde_json} skips unknown members without a depth limit but parses
 * {@code deviceRequirements} and {@code profile} with its recursion limit of 128 (counting the manifest object), so those
 * two may nest at most 126 levels and unknown members are not limited. Parser messages are never surfaced (they may quote
 * content).
 */
public final class ManifestParser {

	private static final int MAX_LANGUAGE_CODE_LENGTH = 35;
	private static final int MAX_LICENSE_CODE_LENGTH = 60;
	private static final int MAX_ARTIFACT_URL_LENGTH = 2048;
	private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

	private static final Set<String> STRING_MEMBERS = Set.of("resourceId", "languageCode", "resourceType", "version",
			"platform", "releaseChannel", "sha256", "signatureKeyId", "licenseCode");
	private static final Set<String> OPTIONAL_STRING_MEMBERS = Set.of("baseResourceId", "minAppVersion", "artifactUrl");
	private static final Set<String> OPTIONAL_OBJECT_MEMBERS = Set.of("deviceRequirements", "profile");
	private static final String SIZE_BYTES = "sizeBytes";

	/** Containers that {@code deviceRequirements} / {@code profile} may nest (serde_json: 128 including the manifest). */
	private static final int MAX_MEMBER_DEPTH = 126;

	/** Skipping and tree building are iterative in Jackson; the manifest size bounds the depth. */
	private static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
			.streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth((int) PackLimits.HARD_MAX_MANIFEST_BYTES)
					.maxNumberLength((int) PackLimits.HARD_MAX_MANIFEST_BYTES)
					.maxNameLength((int) PackLimits.HARD_MAX_MANIFEST_BYTES)
					.maxStringLength((int) PackLimits.HARD_MAX_MANIFEST_BYTES).build())
			.build()).disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	private ManifestParser() {
	}

	public static PackManifest parse(byte[] bytes) {
		if (bytes.length == 0 || bytes[0] != '{') {
			throw invalid("manifest is not a JSON object");
		}
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		}
		catch (CharacterCodingException e) {
			throw invalid("manifest is not UTF-8");
		}
		Map<String, Object> raw = readShape(text);

		String resourceId = (String) raw.get("resourceId");
		String languageCode = (String) raw.get("languageCode");
		String resourceType = (String) raw.get("resourceType");
		String version = (String) raw.get("version");
		String platform = (String) raw.get("platform");
		String channel = (String) raw.get("releaseChannel");
		String sha256 = (String) raw.get("sha256");
		String keyId = (String) raw.get("signatureKeyId");
		String license = (String) raw.get("licenseCode");
		String base = (String) raw.get("baseResourceId");
		String minApp = (String) raw.get("minAppVersion");
		String artifactUrl = (String) raw.get("artifactUrl");

		if (!isUuid(resourceId)) {
			throw invalid("resourceId is not a lowercase UUID");
		}
		if (!isLanguageCode(languageCode)) {
			throw invalid("languageCode is not a valid language tag");
		}
		if (!PackFormat.RESOURCE_TYPES.contains(resourceType)) {
			throw new PackRejectedException(PackRejectReason.UNSUPPORTED_RESOURCE_TYPE, "resourceType is not supported");
		}
		SemVer semver = SemVer.parse(version).orElseThrow(() -> invalid("version is not a semantic version"));
		if (!PackFormat.PLATFORMS.contains(platform)) {
			throw invalid("platform is not supported");
		}
		if (!PackFormat.RELEASE_CHANNELS.contains(channel)) {
			throw invalid("releaseChannel is not supported");
		}
		if (!isSha256Hex(sha256)) {
			throw invalid("sha256 is not 64 lowercase hex digits");
		}
		if (!PackFormat.isValidKeyId(keyId)) {
			throw invalid("signatureKeyId is invalid");
		}
		if (!isLicenseCode(license)) {
			throw invalid("licenseCode is missing or invalid");
		}
		if (base != null && (!isUuid(base) || base.equals(resourceId))) {
			throw invalid("baseResourceId is invalid");
		}
		SemVer minAppVersion = null;
		if (minApp != null) {
			minAppVersion = SemVer.parse(minApp).orElseThrow(() -> invalid("minAppVersion is not a semantic version"));
		}
		if (artifactUrl != null && artifactUrl.getBytes(StandardCharsets.UTF_8).length > MAX_ARTIFACT_URL_LENGTH) {
			throw invalid("artifactUrl is too long");
		}
		return new PackManifest(resourceId, languageCode, resourceType, semver, platform, channel, sha256,
				(BigInteger) raw.get(SIZE_BYTES), base, minAppVersion, license, keyId, artifactUrl,
				(JsonNode) raw.get("deviceRequirements"), (JsonNode) raw.get("profile"));
	}

	/** The JSON shape (serde's {@code RawManifest}): defined members typed, unknown members skipped. */
	private static Map<String, Object> readShape(String text) {
		Map<String, Object> out = new HashMap<>();
		Set<String> seen = new HashSet<>();
		try (JsonParser p = MAPPER.createParser(text)) {
			if (p.nextToken() != JsonToken.START_OBJECT) {
				throw invalid("manifest is not a JSON object");
			}
			JsonToken t;
			while ((t = p.nextToken()) == JsonToken.PROPERTY_NAME) {
				String name = p.currentName();
				JsonToken value = p.nextToken();
				boolean defined = STRING_MEMBERS.contains(name) || OPTIONAL_STRING_MEMBERS.contains(name)
						|| OPTIONAL_OBJECT_MEMBERS.contains(name) || SIZE_BYTES.equals(name);
				if (!defined) {
					p.skipChildren();
					continue;
				}
				if (!seen.add(name)) {
					throw shape();
				}
				if (STRING_MEMBERS.contains(name)) {
					out.put(name, string(p, value));
				}
				else if (OPTIONAL_STRING_MEMBERS.contains(name)) {
					out.put(name, value == JsonToken.VALUE_NULL ? null : string(p, value));
				}
				else if (OPTIONAL_OBJECT_MEMBERS.contains(name)) {
					if (value == JsonToken.VALUE_NULL) {
						out.put(name, null);
					}
					else if (value == JsonToken.START_OBJECT) {
						JsonNode node = MAPPER.readTree(p);
						if (deeperThan(node, MAX_MEMBER_DEPTH) || containsLoneSurrogate(node)) {
							throw shape();
						}
						out.put(name, node);
					}
					else {
						throw shape();
					}
				}
				else {
					out.put(name, unsigned64(p, value));
				}
			}
			if (t != JsonToken.END_OBJECT || p.nextToken() != null) {
				throw shape(); // trailing content after the object
			}
		}
		catch (JacksonException e) {
			throw shape();
		}
		for (String required : STRING_MEMBERS) {
			if (!out.containsKey(required)) {
				throw shape();
			}
		}
		if (!out.containsKey(SIZE_BYTES)) {
			throw shape();
		}
		return out;
	}

	private static String string(JsonParser p, JsonToken token) {
		if (token != JsonToken.VALUE_STRING) {
			throw shape();
		}
		String s = p.getString();
		if (hasLoneSurrogate(s)) {
			throw shape();
		}
		return s;
	}

	private static BigInteger unsigned64(JsonParser p, JsonToken token) {
		if (token != JsonToken.VALUE_NUMBER_INT) {
			throw shape();
		}
		String digits = p.getText();
		if (digits.startsWith("-")) {
			throw shape(); // negative, and serde_json reads -0 as a float
		}
		BigInteger n = new BigInteger(digits);
		if (n.compareTo(U64_MAX) > 0) {
			throw shape();
		}
		return n;
	}

	/** True if {@code node} nests more than {@code limit} containers (recursion bounded by {@code limit}). */
	private static boolean deeperThan(JsonNode node, int limit) {
		if (!node.isContainer()) {
			return false;
		}
		if (limit == 0) {
			return true;
		}
		for (JsonNode child : node) {
			if (deeperThan(child, limit - 1)) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsLoneSurrogate(JsonNode node) {
		if (node.isString()) {
			return hasLoneSurrogate(node.asString());
		}
		if (node.isObject()) {
			for (var entry : node.properties()) {
				if (hasLoneSurrogate(entry.getKey()) || containsLoneSurrogate(entry.getValue())) {
					return true;
				}
			}
		}
		else if (node.isArray()) {
			for (JsonNode child : node) {
				if (containsLoneSurrogate(child)) {
					return true;
				}
			}
		}
		return false;
	}

	static boolean hasLoneSurrogate(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isHighSurrogate(c)) {
				if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
					i++;
					continue;
				}
				return true;
			}
			if (Character.isLowSurrogate(c)) {
				return true;
			}
		}
		return false;
	}

	/** Canonical lowercase UUID {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}. */
	public static boolean isUuid(String s) {
		if (s == null || s.length() != 36) {
			return false;
		}
		for (int i = 0; i < 36; i++) {
			char c = s.charAt(i);
			boolean ok = (i == 8 || i == 13 || i == 18 || i == 23) ? c == '-'
					: (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	/** {@code ^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$}, at most 35 characters. */
	public static boolean isLanguageCode(String s) {
		if (s == null || s.length() > MAX_LANGUAGE_CODE_LENGTH) {
			return false;
		}
		String[] parts = s.split("-", -1);
		if (parts[0].length() < 2 || parts[0].length() > 3 || !parts[0].chars().allMatch(c -> c >= 'a' && c <= 'z')) {
			return false;
		}
		for (int i = 1; i < parts.length; i++) {
			String p = parts[i];
			if (p.length() < 2 || p.length() > 8 || !p.chars().allMatch(c -> (c >= 'a' && c <= 'z')
					|| (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isSha256Hex(String s) {
		return s != null && s.length() == 64 && s.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
	}

	/** 1-60 printable ASCII characters (0x20-0x7E). */
	public static boolean isLicenseCode(String s) {
		return s != null && !s.isEmpty() && s.length() <= MAX_LICENSE_CODE_LENGTH
				&& s.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
	}

	private static PackRejectedException invalid(String detail) {
		return new PackRejectedException(PackRejectReason.INVALID_MANIFEST, detail);
	}

	private static PackRejectedException shape() {
		return invalid("manifest JSON is invalid or a required member is missing");
	}
}
