package com.qalsmartkeyboard.languages.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The manifest rules of pack spec section 5 / verification step 8, matched to the core's serde-based parser. */
class ManifestParserTest {

	static final String SHA = "2652545f05d714be2e96432e4d00d993c9dbfe7233ffa2747aede6e26d1c33eb";
	static final String VALID = "{\"resourceId\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"languageCode\":\"am\","
			+ "\"resourceType\":\"DICTIONARY\",\"version\":\"1.3.0\",\"platform\":\"SHARED\",\"releaseChannel\":\"STABLE\","
			+ "\"sha256\":\"" + SHA + "\",\"sizeBytes\":26,\"baseResourceId\":null,\"licenseCode\":\"MIT\","
			+ "\"signatureKeyId\":\"k-1\"}";

	static PackManifest parse(String json) {
		return ManifestParser.parse(json.getBytes(StandardCharsets.UTF_8));
	}

	static void rejected(String json, PackRejectReason reason) {
		assertThatThrownBy(() -> parse(json)).isInstanceOfSatisfying(PackRejectedException.class,
				e -> assertThat(e.reason()).isEqualTo(reason));
	}

	static String with(String member, String value) {
		return VALID.substring(0, VALID.length() - 1) + ",\"" + member + "\":" + value + "}";
	}

	@Test
	void validManifestParses() {
		PackManifest m = parse(VALID);
		assertThat(m.resourceType()).isEqualTo("DICTIONARY");
		assertThat(m.version().toString()).isEqualTo("1.3.0");
		assertThat(m.sizeBytes().longValueExact()).isEqualTo(26);
		assertThat(m.isDelta()).isFalse();
		assertThat(m.minAppVersion()).isNull();
	}

	@Test
	void unknownMembersAreIgnoredWhateverTheyHoldEvenDuplicated() {
		String unknown = VALID.replace("{\"resourceId\"",
				"{\"future\":[1,{\"a\":null},\"x\"],\"future\":true,\"signature\":\"ignored\",\"resourceId\"");
		assertThat(parse(unknown).licenseCode()).isEqualTo("MIT");
		assertThat(parse(VALID + "  \n\t\r").languageCode()).isEqualTo("am"); // trailing whitespace
	}

	@Test
	void optionalMembersMayBeNullOrTyped() {
		assertThat(parse(with("minAppVersion", "\"1.4.0\"")).minAppVersion().toString()).isEqualTo("1.4.0");
		assertThat(parse(with("deviceRequirements", "{\"minRamMb\":0}")).deviceRequirements().get("minRamMb").asInt())
				.isZero();
		assertThat(parse(with("profile", "null")).profile()).isNull();
		assertThat(parse(with("profile", "{\"code\":\"AM_X\"}")).profile().get("code").asString()).isEqualTo("AM_X");
		rejected(with("deviceRequirements", "[]"), PackRejectReason.INVALID_MANIFEST);
		rejected(with("artifactUrl", "1"), PackRejectReason.INVALID_MANIFEST);
		rejected(with("artifactUrl", "\"" + "a".repeat(2049) + "\""), PackRejectReason.INVALID_MANIFEST);
	}

	@ParameterizedTest
	@ValueSource(strings = { "26.0", "2.6e1", "-1", "-0", "18446744073709551616", "\"26\"", "null" })
	void sizeBytesMustBeANonNegativeU64Integer(String value) {
		rejected(VALID.replace("\"sizeBytes\":26", "\"sizeBytes\":" + value), PackRejectReason.INVALID_MANIFEST);
	}

	@Test
	void sizeBytesUpToU64MaxIsAccepted() {
		assertThat(parse(VALID.replace("\"sizeBytes\":26", "\"sizeBytes\":18446744073709551615")).sizeBytes())
				.hasToString("18446744073709551615");
	}

	@Test
	void encodingRules() {
		rejected(" " + VALID, PackRejectReason.INVALID_MANIFEST); // leading whitespace
		rejected("\uFEFF" + VALID, PackRejectReason.INVALID_MANIFEST); // BOM
		rejected("[" + VALID + "]", PackRejectReason.INVALID_MANIFEST);
		rejected(VALID + "x", PackRejectReason.INVALID_MANIFEST); // trailing content
		rejected(VALID + "{}", PackRejectReason.INVALID_MANIFEST);
		rejected(VALID.replace("\"licenseCode\":\"MIT\"", "\"licenseCode\":'MIT'"), PackRejectReason.INVALID_MANIFEST);
		rejected(VALID.replace("\"sizeBytes\":26", "\"sizeBytes\":026"), PackRejectReason.INVALID_MANIFEST);
	}

	@Test
	void invalidUtf8IsRejectedEvenInIgnoredMembers() {
		byte[] bytes = with("note", "\"xx\"").getBytes(StandardCharsets.UTF_8);
		int at = new String(bytes, StandardCharsets.UTF_8).indexOf("xx");
		bytes[at] = (byte) 0xC3; // lead byte followed by 'x': malformed
		assertThatThrownBy(() -> ManifestParser.parse(bytes)).isInstanceOfSatisfying(PackRejectedException.class,
				e -> assertThat(e.reason()).isEqualTo(PackRejectReason.INVALID_MANIFEST));
	}

	@Test
	void loneSurrogatesInDefinedMembersAreRejected() {
		rejected(with("artifactUrl", "\"https://x/\\ud800\""), PackRejectReason.INVALID_MANIFEST);
		rejected(with("profile", "{\"name\":\"\\udc00\"}"), PackRejectReason.INVALID_MANIFEST);
		assertThat(parse(with("artifactUrl", "\"https://x/\\ud83d\\ude00\"")).artifactUrl()).endsWith("\uD83D\uDE00");
	}

	@Test
	void definedMembersMayNotBeDuplicatedOrMissingOrNull() {
		rejected(with("licenseCode", "\"MIT\""), PackRejectReason.INVALID_MANIFEST);
		rejected(with("baseResourceId", "null"), PackRejectReason.INVALID_MANIFEST);
		rejected(VALID.replace(",\"licenseCode\":\"MIT\"", ""), PackRejectReason.INVALID_MANIFEST);
		rejected(VALID.replace("\"licenseCode\":\"MIT\"", "\"licenseCode\":null"), PackRejectReason.INVALID_MANIFEST);
		rejected(VALID.replace(",\"sizeBytes\":26", ""), PackRejectReason.INVALID_MANIFEST);
		// a missing resourceType is INVALID_MANIFEST, an unknown one UNSUPPORTED_RESOURCE_TYPE
		rejected(VALID.replace("\"resourceType\":\"DICTIONARY\",", ""), PackRejectReason.INVALID_MANIFEST);
		rejected(VALID.replace("DICTIONARY", "FOO"), PackRejectReason.UNSUPPORTED_RESOURCE_TYPE);
	}

	@Test
	void memberChecksRunInTheCoresOrder() {
		// resourceId and languageCode are checked before resourceType; version, platform... after it
		rejected(VALID.replace("DICTIONARY", "FOO").replace("0f8fad5b", "0F8FAD5B"), PackRejectReason.INVALID_MANIFEST);
		rejected(VALID.replace("DICTIONARY", "FOO").replace("\"am\"", "\"AM\""), PackRejectReason.INVALID_MANIFEST);
		rejected(VALID.replace("DICTIONARY", "FOO").replace("1.3.0", "1.3"), PackRejectReason.UNSUPPORTED_RESOURCE_TYPE);
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"version\":\"v1.3.0\"", "\"version\":\"1.3.0+build\"", "\"platform\":\"WEB\"",
			"\"releaseChannel\":\"ALPHA\"", "\"sha256\":\"ABC\"", "\"signatureKeyId\":\"k 1\"", "\"licenseCode\":\"\"",
			"\"licenseCode\":\"M\u00e9T\"", "\"languageCode\":\"am_ET\"",
			"\"baseResourceId\":\"0f8fad5b-d9cb-469f-a165-70867728950e\"" })
	void memberSyntax(String member) {
		String name = member.substring(0, member.indexOf(':'));
		String json = VALID.replaceAll(name + ":(\"[^\"]*\"|null)", java.util.regex.Matcher.quoteReplacement(member));
		rejected(json, PackRejectReason.INVALID_MANIFEST);
	}

	@Test
	void nestingLimitsFollowSerdeJson() {
		// unknown members are skipped without a depth limit
		assertThat(parse(with("deep", "[".repeat(300) + "]".repeat(300))).licenseCode()).isEqualTo("MIT");
		// deviceRequirements / profile: at most 126 nested containers (128 with the manifest object, serde's limit)
		String ok = "{\"x\":" + "[".repeat(125) + "]".repeat(125) + "}";
		assertThat(parse(with("profile", ok)).profile()).isNotNull();
		String tooDeep = "{\"x\":" + "[".repeat(126) + "]".repeat(126) + "}";
		rejected(with("profile", tooDeep), PackRejectReason.INVALID_MANIFEST);
	}
}
