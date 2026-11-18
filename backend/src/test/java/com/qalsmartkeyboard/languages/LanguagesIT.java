package com.qalsmartkeyboard.languages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B3.1 "done when": a client discovers a published pack through the API (languages, profiles, signing keys, resource
 * list, resource detail, download URL via the fake signer) against real PostgreSQL rows (V1 constraints and the V7
 * discovery columns apply), through the real security chain. Every response is validated against the contract.
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
@Import(LanguagesIT.FakeSignerConfig.class)
class LanguagesIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeSignerConfig {
		@Bean
		@Primary
		FakeArtifactUrlSigner fakeSigner() {
			return new FakeArtifactUrlSigner();
		}
	}

	static final String SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
	static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	FakeArtifactUrlSigner signer;

	@BeforeEach
	void clean() {
		jdbc.update("DELETE FROM transliteration_profiles");
		jdbc.update("DELETE FROM language_resources");
		jdbc.update("DELETE FROM resource_signing_keys");
		jdbc.update("UPDATE supported_languages SET is_active = TRUE");
		signer.reset();
	}

	// ------------------------------------------------------------------ seeding helpers

	private void key(String keyId, String status) {
		jdbc.update("INSERT INTO resource_signing_keys (key_id, public_key, key_status) VALUES (?, ?, ?)", keyId,
				"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", status);
	}

	/** Defaults: published, STABLE, SHARED, 100% rollout, key packs-2026-01. */
	private class Pack {
		String language = "am";
		String type = "DICTIONARY";
		String version = "1.0.0";
		String platform = "SHARED";
		String channel = "STABLE";
		String status = "PUBLISHED";
		int rollout = 100;
		String keyId = "packs-2026-01";
		String minAppVersion = null;
		String artifactUri = null;
		Long sizeBytes = 182044L;
		Long containerSize = 182500L;
		UUID base = null;
		String requirements = "{\"minRamMb\":0,\"wifiOnly\":true}";
		String publishedAt = "2026-11-10T10:00:00Z";

		Pack language(String v) { language = v; return this; }
		Pack type(String v) { type = v; return this; }
		Pack version(String v) { version = v; return this; }
		Pack platform(String v) { platform = v; return this; }
		Pack channel(String v) { channel = v; return this; }
		Pack status(String v) { status = v; return this; }
		Pack rollout(int v) { rollout = v; return this; }
		Pack key(String v) { keyId = v; return this; }
		Pack min(String v) { minAppVersion = v; return this; }
		Pack uri(String v) { artifactUri = v; return this; }
		Pack size(Long v) { sizeBytes = v; return this; }
		Pack base(UUID v) { base = v; return this; }
		Pack at(String v) { publishedAt = v; return this; }

		UUID insert() {
			UUID id = UUID.randomUUID();
			String uri = artifactUri != null ? artifactUri
					: "s3://qal-language-packs/" + language + "/" + type.toLowerCase() + "/" + version + "-" + platform + ".pack";
			jdbc.update("""
					INSERT INTO language_resources (id, language_id, resource_type, resource_version, platform, release_channel,
					    rollout_percentage, base_resource_id, artifact_uri, sha256_checksum, size_bytes, container_size_bytes,
					    min_app_version, device_requirements, license_code, signature_key_id, manifest_signature,
					    release_notes, publication_status, published_at)
					VALUES (?, (SELECT id FROM supported_languages WHERE language_code = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
					    ?::jsonb, 'LicenseRef-Qal-Original', (SELECT id FROM resource_signing_keys WHERE key_id = ?),
					    'c2lnbmF0dXJl', 'Notes', ?, ?::timestamptz)""",
					id, language, type, version, platform, channel, rollout, base, uri, SHA, sizeBytes, containerSize,
					minAppVersion, requirements, keyId, status, publishedAt);
			return id;
		}
	}

	private Pack pack() {
		return new Pack();
	}

	private String bearer(UUID device) {
		return "Bearer " + tokens.issue(UUID.randomUUID(), UUID.randomUUID(), device, "REGISTERED").value();
	}

	private JsonNode body(MvcResult r) throws Exception {
		return JSON.readTree(r.getResponse().getContentAsString());
	}

	private JsonNode list(String query, String auth) throws Exception {
		var req = get("/api/v1/language-resources?" + query);
		if (auth != null) {
			req = req.header("Authorization", auth);
		}
		return body(mvc.perform(req).andExpect(status().isOk()).andReturn()).get("items");
	}

	private List<String> versions(JsonNode items) {
		List<String> out = new ArrayList<>();
		items.forEach(i -> out.add(i.get("resourceType").asString() + ":" + i.get("version").asString()));
		return out;
	}

	// ------------------------------------------------------------------ the step's done-when

	@Test
	void clientDiscoversAndResolvesAPublishedPack() throws Exception {
		key("packs-2026-01", "ACTIVE");
		key("packs-2026-02", "ACTIVE");
		jdbc.update("UPDATE resource_signing_keys SET endorsed_by_key_id = 'packs-2026-01', endorsement_signature = 'RU5ET1JTRQ=='"
				+ " WHERE key_id = 'packs-2026-02'");
		UUID rules = pack().type("TRANSLITERATION_RULES").version("1.3.0").min("1.4.0").insert();
		jdbc.update("""
				INSERT INTO transliteration_profiles (language_id, profile_code, display_name, target_script_code, profile_version,
				    rules_resource_id, supports_reverse, profile_status)
				SELECT id, 'AM_PHONETIC_QWERTY_TO_ETHIOPIC', 'Amharic phonetic', 'Ethi', '1.0.0', ?, TRUE, 'ACTIVE'
				FROM supported_languages WHERE language_code = 'am'""", rules);

		// 1. languages
		JsonNode langs = body(mvc.perform(get("/api/v1/languages")).andExpect(status().isOk()).andReturn()).get("items");
		JsonNode am = null;
		for (JsonNode l : langs) {
			if ("am".equals(l.get("code").asString())) {
				am = l;
			}
		}
		assertThat(am).isNotNull();
		assertThat(am.get("scriptCode").asString()).isEqualTo("Ethi");
		assertThat(am.get("isLaunchLanguage").asBoolean()).isTrue();
		assertThat(langs.get(0).get("code").asString()).isEqualTo("am"); // sort_order

		// 2. profiles (rules resource linked)
		JsonNode profiles = body(mvc.perform(get("/api/v1/languages/am/transliteration-profiles"))
				.andExpect(status().isOk()).andReturn()).get("items");
		assertThat(profiles).hasSize(1);
		assertThat(profiles.get(0).get("code").asString()).isEqualTo("AM_PHONETIC_QWERTY_TO_ETHIOPIC");
		assertThat(profiles.get(0).get("rulesResourceId").asString()).isEqualTo(rules.toString());

		// 3. list as an Android 2.0.0 client
		JsonNode items = list("platform=ANDROID&appVersion=2.0.0", null);
		assertThat(items).hasSize(1);
		JsonNode m = items.get(0);
		assertThat(m.get("id").asString()).isEqualTo(rules.toString());
		assertThat(m.get("languageCode").asString()).isEqualTo("am");

		// manifest fields match docs/specs/pack-format-v1.md section 5.2
		assertThat(m.get("resourceType").asString()).isEqualTo("TRANSLITERATION_RULES");
		assertThat(m.get("version").asString()).matches("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)?$");
		assertThat(m.get("platform").asString()).isEqualTo("SHARED");
		assertThat(m.get("channel").asString()).isEqualTo("STABLE");
		assertThat(m.get("sha256").asString()).isEqualTo(SHA).matches("^[0-9a-f]{64}$");
		assertThat(m.get("sizeBytes").asLong()).isEqualTo(182044L);
		assertThat(m.get("containerSizeBytes").asLong()).isEqualTo(182500L).isGreaterThan(m.get("sizeBytes").asLong());
		assertThat(m.get("signatureKeyId").asString()).isEqualTo("packs-2026-01").matches("^[A-Za-z0-9._-]{1,120}$");
		assertThat(m.get("minAppVersion").asString()).isEqualTo("1.4.0");
		assertThat(m.get("licenseCode").asString()).isEqualTo("LicenseRef-Qal-Original");
		assertThat(m.has("baseResourceId")).isFalse();
		assertThat(m.has("signature")).isFalse(); // the pack itself carries the signature
		assertThat(m.get("deviceRequirements").get("minRamMb").asInt()).isZero();
		assertThat(m.get("deviceRequirements").get("wifiOnlyDefault").asBoolean()).isTrue();
		assertThat(m.get("transliteration").get("profileCode").asString()).isEqualTo("AM_PHONETIC_QWERTY_TO_ETHIOPIC");
		assertThat(m.get("transliteration").get("normalizationForm").asString()).isEqualTo("NFC");
		assertThat(m.get("publishedAt").asString()).isEqualTo("2026-11-10T10:00:00Z");

		// 4. artifactUrl resolves through the signer, from the stored location
		assertThat(m.get("artifactUrl").asString())
				.isEqualTo("https://packs.fake.invalid/am/transliteration_rules/1.3.0-SHARED.pack?sig=fake&expires=900");
		assertThat(signer.requested()).containsExactly("s3://qal-language-packs/am/transliteration_rules/1.3.0-SHARED.pack");

		// 5. detail equals the list entry
		JsonNode detail = body(mvc.perform(get("/api/v1/language-resources/" + rules)).andExpect(status().isOk()).andReturn());
		assertThat(detail).isEqualTo(m);

		// 6. the key id is announced by /resource-signing-keys (public material only)
		JsonNode keys = body(mvc.perform(get("/api/v1/resource-signing-keys")).andExpect(status().isOk()).andReturn())
				.get("items");
		assertThat(keys).hasSize(2);
		JsonNode k1 = keys.get(0).get("keyId").asString().equals("packs-2026-01") ? keys.get(0) : keys.get(1);
		JsonNode k2 = k1 == keys.get(0) ? keys.get(1) : keys.get(0);
		assertThat(k1.get("keyId").asString()).isEqualTo(m.get("signatureKeyId").asString());
		assertThat(k1.get("algorithm").asString()).isEqualTo("Ed25519");
		assertThat(k1.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(k1.get("publicKey").asString()).isEqualTo("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
		assertThat(k1.has("endorsedByKeyId")).isFalse();
		assertThat(k2.get("endorsedByKeyId").asString()).isEqualTo("packs-2026-01");
		assertThat(k2.get("endorsementSignature").asString()).isEqualTo("RU5ET1JTRQ==");
		assertThat(k1.has("notBefore")).isTrue();
		assertThat(k1.size()).isEqualTo(5); // keyId, algorithm, publicKey, status, notBefore: nothing else, nothing private
	}

	// ------------------------------------------------------------------ profiles

	@Test
	void profilesListActiveAndRetiredButNeverDraftAndUnknownLanguageIs404() throws Exception {
		key("packs-2026-01", "ACTIVE");
		UUID rules = pack().type("TRANSLITERATION_RULES").insert();
		jdbc.update("""
				INSERT INTO transliteration_profiles (language_id, profile_code, display_name, target_script_code, profile_version,
				    profile_status, rules_resource_id)
				SELECT id, c.code, c.code, 'Ethi', c.version, c.status, c.rules FROM supported_languages,
				    (VALUES ('AM_A', '1.0.0', 'ACTIVE', ?::uuid), ('AM_A', '0.9.0', 'RETIRED', NULL::uuid),
				            ('AM_B', '0.1.0', 'DRAFT', NULL::uuid)) AS c(code, version, status, rules)
				WHERE language_code = 'am'""", rules);
		JsonNode items = body(mvc.perform(get("/api/v1/languages/am/transliteration-profiles")).andExpect(status().isOk())
				.andReturn()).get("items");
		List<String> seen = new ArrayList<>();
		items.forEach(i -> seen.add(i.get("code").asString() + ":" + i.get("version").asString() + ":" + i.get("status").asString()));
		assertThat(seen).containsExactlyInAnyOrder("AM_A:1.0.0:ACTIVE", "AM_A:0.9.0:RETIRED");
		mvc.perform(get("/api/v1/languages/ti/transliteration-profiles")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(get("/api/v1/languages/zz/transliteration-profiles")).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/languages/Not_A_Code/transliteration-profiles")).andExpect(status().isNotFound());
		jdbc.update("UPDATE supported_languages SET is_active = FALSE WHERE language_code = 'am'");
		mvc.perform(get("/api/v1/languages/am/transliteration-profiles")).andExpect(status().isNotFound());
	}

	@Test
	void inactiveLanguagesAreNotListed() throws Exception {
		jdbc.update("UPDATE supported_languages SET is_active = FALSE WHERE language_code = 'ar'");
		JsonNode langs = body(mvc.perform(get("/api/v1/languages")).andExpect(status().isOk()).andReturn()).get("items");
		List<String> codes = new ArrayList<>();
		langs.forEach(l -> codes.add(l.get("code").asString()));
		assertThat(codes).contains("am", "ti", "om", "en").doesNotContain("ar");
		JsonNode ge = null;
		for (JsonNode l : langs) {
			if ("gez".equals(l.get("code").asString())) {
				ge = l;
			}
		}
		assertThat(ge.get("supportLevel").asString()).isEqualTo("PLANNED");
	}

	// ------------------------------------------------------------------ visibility rules

	@Test
	void onlyPublishedResourcesWithAUsableKeyAreServed() throws Exception {
		key("packs-2026-01", "ACTIVE");
		key("packs-retiring", "RETIRING");
		key("packs-revoked", "REVOKED");
		pack().version("1.0.0").insert();
		pack().version("1.1.0").status("DRAFT").insert();
		pack().version("1.2.0").status("RETIRED").insert();
		pack().version("1.3.0").status("REVOKED").insert();
		pack().version("1.4.0").key("packs-retiring").insert(); // RETIRING keys still verify
		pack().version("1.5.0").key("packs-revoked").insert(); // clients would reject it: not served
		pack().version("1.6.0").size(null).insert(); // no artifact size: not a valid discovery entry
		pack().type("GLOSSARY").version("1.0.0").insert(); // not expressible in the contract's ResourceType
		JsonNode items = list("platform=ANDROID&appVersion=2.0.0", null);
		assertThat(versions(items)).containsExactlyInAnyOrder("DICTIONARY:1.0.0", "DICTIONARY:1.4.0");
		mvc.perform(get("/api/v1/language-resources/" + jdbc.queryForObject(
				"SELECT id FROM language_resources WHERE resource_version = '1.1.0'", UUID.class)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/language-resources/" + jdbc.queryForObject(
				"SELECT id FROM language_resources WHERE resource_version = '1.5.0'", UUID.class)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/language-resources/" + UUID.randomUUID())).andExpect(status().isNotFound());
	}

	@Test
	void platformFilter() throws Exception {
		key("packs-2026-01", "ACTIVE");
		pack().type("DICTIONARY").platform("SHARED").insert();
		pack().type("KEYBOARD_LAYOUT").platform("ANDROID").insert();
		pack().type("PREDICTION_MODEL").platform("IOS").insert();
		assertThat(versions(list("platform=ANDROID&appVersion=2.0.0", null)))
				.containsExactlyInAnyOrder("DICTIONARY:1.0.0", "KEYBOARD_LAYOUT:1.0.0");
		assertThat(versions(list("platform=IOS&appVersion=2.0.0", null)))
				.containsExactlyInAnyOrder("DICTIONARY:1.0.0", "PREDICTION_MODEL:1.0.0");
		assertThat(versions(list("platform=WEB&appVersion=2.0.0", null))).containsExactly("DICTIONARY:1.0.0");
	}

	@Test
	void typeAndLanguageFilters() throws Exception {
		key("packs-2026-01", "ACTIVE");
		pack().language("am").type("DICTIONARY").insert();
		pack().language("ti").type("DICTIONARY").insert();
		pack().language("am").type("FREQUENCY_LIST").insert();
		assertThat(list("platform=ANDROID&appVersion=2.0.0&languageCode=ti", null)).hasSize(1);
		assertThat(versions(list("platform=ANDROID&appVersion=2.0.0&languageCode=am&type=FREQUENCY_LIST", null)))
				.containsExactly("FREQUENCY_LIST:1.0.0");
		assertThat(list("platform=ANDROID&appVersion=2.0.0", null)).hasSize(3);
		assertThat(list("platform=ANDROID&appVersion=2.0.0&languageCode=om", null)).isEmpty();
	}

	@Test
	void channelsSeeTheirOwnAndMoreStableOnesAndInternalNeedsAToken() throws Exception {
		key("packs-2026-01", "ACTIVE");
		pack().type("DICTIONARY").channel("STABLE").insert();
		pack().type("FREQUENCY_LIST").channel("BETA").insert();
		pack().type("PREDICTION_MODEL").channel("INTERNAL").insert();
		String q = "platform=ANDROID&appVersion=2.0.0";
		assertThat(versions(list(q, null))).containsExactly("DICTIONARY:1.0.0"); // default STABLE
		assertThat(versions(list(q + "&channel=STABLE", null))).containsExactly("DICTIONARY:1.0.0");
		assertThat(versions(list(q + "&channel=BETA", null))).containsExactlyInAnyOrder("DICTIONARY:1.0.0", "FREQUENCY_LIST:1.0.0");

		mvc.perform(get("/api/v1/language-resources?" + q + "&channel=INTERNAL")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		// authenticated, but the internal channel is off by default (qal.languages.internal-channel-enabled)
		mvc.perform(get("/api/v1/language-resources?" + q + "&channel=INTERNAL").header("Authorization", bearer(null)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("channel"));
		UUID internal = jdbc.queryForObject("SELECT id FROM language_resources WHERE release_channel = 'INTERNAL'", UUID.class);
		mvc.perform(get("/api/v1/language-resources/" + internal)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/language-resources/" + internal).header("Authorization", bearer(null)))
				.andExpect(status().isNotFound());
	}

	@Test
	void minAppVersionIsComparedSemantically() throws Exception {
		key("packs-2026-01", "ACTIVE");
		pack().type("DICTIONARY").min("1.10.0").insert();
		pack().type("FREQUENCY_LIST").min("2.0.0").insert();
		pack().type("KEYBOARD_LAYOUT").insert();
		assertThat(versions(list("platform=ANDROID&appVersion=1.9.0", null))).containsExactly("KEYBOARD_LAYOUT:1.0.0");
		assertThat(versions(list("platform=ANDROID&appVersion=1.10.0", null)))
				.containsExactlyInAnyOrder("KEYBOARD_LAYOUT:1.0.0", "DICTIONARY:1.0.0");
		assertThat(list("platform=ANDROID&appVersion=2.0.0", null)).hasSize(3);
		assertThat(versions(list("platform=ANDROID&appVersion=2.0.0-beta.1", null)))
				.containsExactlyInAnyOrder("KEYBOARD_LAYOUT:1.0.0", "DICTIONARY:1.0.0"); // pre-release is below 2.0.0
	}

	@Test
	void stagedRolloutNeedsAStableIdentityAndIsDeterministic() throws Exception {
		key("packs-2026-01", "ACTIVE");
		UUID id = pack().rollout(50).insert();
		UUID in = null;
		UUID out = null;
		while (in == null || out == null) {
			UUID d = UUID.randomUUID();
			if (LanguagesService.bucket(d, id) < 50) {
				in = d;
			}
			else {
				out = d;
			}
		}
		String q = "platform=ANDROID&appVersion=2.0.0";
		assertThat(list(q, null)).isEmpty(); // anonymous: no stable identity, no partial rollout
		assertThat(list(q, bearer(in))).hasSize(1);
		assertThat(list(q, bearer(in))).hasSize(1); // stable across calls
		assertThat(list(q, bearer(out))).isEmpty();
		mvc.perform(get("/api/v1/language-resources/" + id)).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/language-resources/" + id).header("Authorization", bearer(in))).andExpect(status().isOk());
		jdbc.update("UPDATE language_resources SET rollout_percentage = 100");
		assertThat(list(q, null)).hasSize(1);
		jdbc.update("UPDATE language_resources SET rollout_percentage = 0");
		assertThat(list(q, bearer(in))).isEmpty();
	}

	@Test
	void deltaMetadataNamesTheBase() throws Exception {
		key("packs-2026-01", "ACTIVE");
		UUID base = pack().version("1.0.0").insert();
		UUID delta = pack().version("1.1.0").base(base).size(2048L).at("2026-11-11T10:00:00Z").insert();
		JsonNode items = list("platform=ANDROID&appVersion=2.0.0", null);
		assertThat(items).hasSize(2);
		assertThat(items.get(0).get("id").asString()).isEqualTo(delta.toString()); // newest first
		assertThat(items.get(0).get("baseResourceId").asString()).isEqualTo(base.toString());
		assertThat(items.get(0).get("sizeBytes").asLong()).isEqualTo(2048L);
		assertThat(items.get(1).has("baseResourceId")).isFalse();
	}

	@Test
	void unusableStoredLocationIsSkippedAndNotALeak() throws Exception {
		key("packs-2026-01", "ACTIVE");
		pack().version("1.0.0").uri("ftp://internal.example/pack").insert();
		pack().version("1.1.0").uri("s3://qal-exports/secret.zip").insert();
		pack().version("1.2.0").uri("https://cdn.example.com/am/dictionary/1.2.0.pack").insert();
		pack().version("1.3.0").insert();
		JsonNode items = list("platform=ANDROID&appVersion=2.0.0", null);
		assertThat(versions(items)).containsExactlyInAnyOrder("DICTIONARY:1.2.0", "DICTIONARY:1.3.0");
		assertThat(items.toString()).doesNotContain("ftp://").doesNotContain("qal-exports");
		UUID bad = jdbc.queryForObject("SELECT id FROM language_resources WHERE resource_version = '1.1.0'", UUID.class);
		mvc.perform(get("/api/v1/language-resources/" + bad)).andExpect(status().isNotFound());
	}

	@Test
	void keysetPaginationWalksEveryPackOnceNewestFirstAndSkipsFilteredRows() throws Exception {
		key("packs-2026-01", "ACTIVE");
		for (int i = 0; i < 7; i++) {
			pack().version("1.0." + i).at("2026-11-0" + (i + 1) + "T10:00:00Z").insert();
		}
		pack().version("2.0.0").min("9.0.0").at("2026-11-09T10:00:00Z").insert(); // filtered by app version
		List<String> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			var req = get("/api/v1/language-resources?platform=ANDROID&appVersion=2.0.0&limit=3"
					+ (cursor == null ? "" : "&cursor=" + cursor));
			JsonNode page = body(mvc.perform(req).andExpect(status().isOk()).andReturn());
			page.get("items").forEach(i -> seen.add(i.get("version").asString()));
			cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
			pages++;
		} while (cursor != null && pages < 10);
		assertThat(pages).isEqualTo(3);
		assertThat(seen).containsExactly("1.0.6", "1.0.5", "1.0.4", "1.0.3", "1.0.2", "1.0.1", "1.0.0");
		mvc.perform(get("/api/v1/language-resources?platform=ANDROID&appVersion=2.0.0&cursor=garbage"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/language-resources?platform=ANDROID&appVersion=2.0.0&limit=0")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/language-resources?platform=ANDROID&appVersion=2.0.0&limit=101")).andExpect(status().isBadRequest());
	}

	@Test
	void parametersAreValidated() throws Exception {
		mvc.perform(get("/api/v1/language-resources?platform=ANDROID&appVersion=banana")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("appVersion"));
		mvc.perform(get("/api/v1/language-resources?platform=ANDROID&appVersion=1.0.0&type=SPELLCHECK"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/language-resources?platform=ANDROID&appVersion=1.0.0&languageCode=NOPE"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/language-resources?platform=ANDROID")).andExpect(status().isBadRequest());
	}

	// ------------------------------------------------------------------ security chain and caching

	@Test
	void invalidBearerIs401OnEveryOperationAndAValidOneIsAccepted() throws Exception {
		UUID id = UUID.randomUUID();
		for (String path : new String[] { "/api/v1/languages", "/api/v1/languages/am/transliteration-profiles",
				"/api/v1/language-resources?platform=ANDROID&appVersion=1.0.0", "/api/v1/language-resources/" + id,
				"/api/v1/resource-signing-keys" }) {
			mvc.perform(get(path).header("Authorization", "Bearer not.a.token")).andExpect(status().isUnauthorized());
		}
		mvc.perform(get("/api/v1/resource-signing-keys").header("Authorization", bearer(UUID.randomUUID())))
				.andExpect(status().isOk());
	}

	@Test
	void noValidatorsAreSetBecauseTheContractDefinesNone() throws Exception {
		key("packs-2026-01", "ACTIVE");
		for (String path : new String[] { "/api/v1/languages", "/api/v1/resource-signing-keys",
				"/api/v1/language-resources?platform=ANDROID&appVersion=1.0.0" }) {
			var response = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
			assertThat(response.getHeader("ETag")).isNull();
			assertThat(response.getHeader("Last-Modified")).isNull();
			assertThat(String.valueOf(response.getHeader("Cache-Control"))).doesNotContain("public");
		}
	}

	@Test
	void sha256ConstantIsALowercaseHexDigest() {
		assertThat(HexFormat.of().parseHex(SHA)).hasSize(32);
	}
}
