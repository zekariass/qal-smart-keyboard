package com.qalsmartkeyboard.languages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.languages.pack.KeyEndorsement;
import com.qalsmartkeyboard.languages.pack.KeyRing;
import com.qalsmartkeyboard.languages.pack.KeyStatus;
import com.qalsmartkeyboard.languages.pack.LocalEd25519PackSigner;
import com.qalsmartkeyboard.languages.pack.PackLimits;
import com.qalsmartkeyboard.languages.pack.PackSigner;
import com.qalsmartkeyboard.languages.pack.PackVerifier;
import com.qalsmartkeyboard.languages.pack.PackWriter;
import com.qalsmartkeyboard.languages.pack.TrustedKey;
import com.qalsmartkeyboard.languages.pack.VerifiedPack;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B3.2 "done when": unsigned, tampered, unknown-key, revoked-key, unlicensed, non-commercial and downgraded packs are
 * rejected; a valid pack goes upload, stage, publish and is then discoverable through B3.1's API with an artifact URL that
 * resolves (real MinIO) to a container that verifies with the server key; non-admins get 403; every action is audited.
 * Real PostgreSQL 18 (V1-V15), real MinIO, the real security chain; every response validated against the contract. Uses
 * the real dev seed packs of {@code android/ime/src/main/assets/packs/} (their per-checkout dev key is trusted through
 * {@code seed-packs.json}) and packs built here with test keys.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false",
		"qal.languages.publishing.max-container-bytes=200000" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class PackPublishingIT {

	static final String MINIO_USER = "qal_minio";
	static final String MINIO_PASSWORD = "qal_minio_dev_only";
	static final String BUCKET = "qal-language-packs";
	static final Path SEED_PACKS = Path.of("..", "android", "ime", "src", "main", "assets", "packs");
	static final JsonMapper JSON = JsonMapper.builder().build();
	static final HexFormat HEX = HexFormat.of();

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	/** The MinIO build pinned by infra/compose (upstream minio/minio images are no longer published). */
	@Container
	static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(System.getProperty(
			"qal.test.minio-image",
			"cgr.dev/chainguard/minio@sha256:59953701f8f8d0de81aab456f0b0b6c0a1804119415559bf7ede79d944108879")))
			.withCommand("server", "/data").withEnv("MINIO_ROOT_USER", MINIO_USER)
			.withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD).withExposedPorts(9000)
			.waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).forStatusCode(200));

	static String minioEndpoint() {
		return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
	}

	@DynamicPropertySource
	static void objectStore(DynamicPropertyRegistry registry) {
		registry.add("qal.languages.artifacts.endpoint", PackPublishingIT::minioEndpoint);
		registry.add("qal.languages.artifacts.access-key", () -> MINIO_USER);
		registry.add("qal.languages.artifacts.secret-key", () -> MINIO_PASSWORD);
		registry.add("qal.languages.publishing.storage.access-key", () -> MINIO_USER);
		registry.add("qal.languages.publishing.storage.secret-key", () -> MINIO_PASSWORD);
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	PackSigner serverSigner;

	/** Test keys: fixed seeds, registered per test as needed. */
	final LocalEd25519PackSigner pipeline = new LocalEd25519PackSigner("test-pipeline-1", seed(0x11));
	final LocalEd25519PackSigner stranger = new LocalEd25519PackSigner("test-untrusted-9", seed(0x22));
	final LocalEd25519PackSigner doomed = new LocalEd25519PackSigner("test-doomed-1", seed(0x33));

	String editor;
	String reviewer;
	String secondReviewer;
	String superAdmin;
	String nobody;
	UUID editorId;
	UUID reviewerId;
	UUID secondReviewerId;
	UUID nobodyId;

	@BeforeEach
	void setUp() {
		new S3PackObjectStore(minioEndpoint(), BUCKET, "us-east-1", true, MINIO_USER, MINIO_PASSWORD, Clock.systemUTC())
				.createBucketIfMissing();
		jdbc.update("UPDATE transliteration_profiles SET rules_resource_id = NULL, profile_status = 'DRAFT'");
		jdbc.update("DELETE FROM language_resources");
		jdbc.update("DELETE FROM language_datasets");
		jdbc.update("DELETE FROM resource_signing_keys");
		editorId = admin("LANGUAGE_EDITOR");
		reviewerId = admin("LANGUAGE_REVIEWER");
		secondReviewerId = admin("LANGUAGE_REVIEWER");
		UUID superId = admin("SUPER_ADMIN");
		nobodyId = admin(null);
		editor = bearer(editorId);
		reviewer = bearer(reviewerId);
		secondReviewer = bearer(secondReviewerId);
		superAdmin = bearer(superId);
		nobody = bearer(nobodyId);
	}

	// ------------------------------------------------------------------ the step's done-when: the happy path

	@Test
	void seedPackGoesFromUploadThroughStageAndPublishAndIsDiscoverableAndDownloadable() throws Exception {
		registerServerKey();
		registerSeedDevKey();
		dataset("am-seed-wordlist", "am", "LicenseRef-Qal-Original");
		byte[] seedPack = Files.readAllBytes(SEED_PACKS.resolve("am-dictionary.pack"));
		JsonNode seedIndex = seedEntry("am-dictionary.pack"); // sha256/sizeBytes there describe the .pack file
		VerifiedPack original = PackVerifier.verify(seedPack, seedKeyRing(), PackLimits.DEFAULT);
		assertThat(PackVerifier.sha256Hex(seedPack)).isEqualTo(seedIndex.get("sha256").asString());

		// upload: verified with the dev key, stored, DRAFT
		JsonNode draft = upload(seedPack, "am-seed-wordlist").andExpect(status().isCreated()).andReturnJson();
		String id = draft.get("id").asString();
		assertThat(draft.get("state").asString()).isEqualTo("DRAFT");
		assertThat(draft.get("languageCode").asString()).isEqualTo("am");
		assertThat(draft.get("resourceType").asString()).isEqualTo("DICTIONARY");
		assertThat(draft.get("sha256").asString()).isEqualTo(original.manifest().sha256());
		assertThat(draft.get("sizeBytes").asLong()).isEqualTo(original.artifactLength());
		assertThat(draft.get("containerSizeBytes").asLong()).isEqualTo(seedIndex.get("sizeBytes").asLong());
		assertThat(draft.get("signatureKeyId").asString()).isEqualTo(seedKeyId());
		assertThat(draft.get("licenseCode").asString()).isEqualTo("LicenseRef-Qal-Original");
		assertThat(draft.get("artifactUri").asString()).startsWith("s3://" + BUCKET + "/uploads/" + id + "/");
		assertThat(draft.get("datasetCodes").get(0).asString()).isEqualTo("am-seed-wordlist");
		// invisible to clients while DRAFT
		mvc.perform(get("/api/v1/language-resources/" + id)).andExpect(status().isNotFound());

		// stage: INTERNAL only, re-signed by the server key
		JsonNode staged = perform(post("/admin/v1/language-resources/" + id + "/stage"), editor).andExpect(status().isOk())
				.andReturnJson();
		assertThat(staged.get("state").asString()).isEqualTo("STAGED");
		assertThat(staged.get("channel").asString()).isEqualTo("INTERNAL");
		assertThat(staged.get("signatureKeyId").asString()).isEqualTo(serverSigner.keyId());
		assertThat(discover("channel=BETA")).isEmpty();
		mvc.perform(get("/api/v1/language-resources/" + id)).andExpect(status().isNotFound());

		// publish to BETA
		JsonNode beta = perform(post("/admin/v1/language-resources/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
				.content("{\"channel\":\"BETA\",\"rolloutPercentage\":100,\"releaseNotes\":\"Seed dictionary\"}"), reviewer)
				.andExpect(status().isOk()).andReturnJson();
		assertThat(beta.get("state").asString()).isEqualTo("PUBLISHED");
		assertThat(beta.get("channel").asString()).isEqualTo("BETA");
		List<JsonNode> betaItems = discover("channel=BETA");
		assertThat(betaItems).hasSize(1);
		VerifiedPack served = download(betaItems.get(0));
		assertThat(served.manifest().releaseChannel()).isEqualTo("BETA");
		assertThat(served.manifest().resourceId()).isEqualTo(id);
		assertThat(served.manifest().version().toString()).isEqualTo("0.1.0");
		assertThat(served.manifest().sha256()).isEqualTo(original.manifest().sha256());
		assertThat(served.artifact()).isEqualTo(original.artifact());
		assertThat(discover("")).isEmpty(); // STABLE clients do not see BETA

		// STABLE needs a second admin (Review D25): none yet, and self-approval does not count
		perform(post("/admin/v1/language-resources/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
				.content("{\"channel\":\"STABLE\",\"rolloutPercentage\":100}"), reviewer)
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"));
		perform(post("/admin/v1/language-resources/" + id + "/stable-approval"), reviewer).andExpect(status().isOk());
		perform(post("/admin/v1/language-resources/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
				.content("{\"channel\":\"STABLE\",\"rolloutPercentage\":100}"), reviewer)
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("APPROVAL_REQUIRED"));
		perform(post("/admin/v1/language-resources/" + id + "/stable-approval"), secondReviewer).andExpect(status().isOk());
		JsonNode stable = perform(post("/admin/v1/language-resources/" + id + "/publish")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"channel\":\"STABLE\",\"rolloutPercentage\":100,\"minAppVersion\":\"1.0.0\"}"), reviewer)
				.andExpect(status().isOk()).andReturnJson();
		assertThat(stable.get("channel").asString()).isEqualTo("STABLE");

		// discoverable by an anonymous STABLE client, with a URL that resolves to a container the server key signed
		List<JsonNode> items = discover("");
		assertThat(items).hasSize(1);
		JsonNode m = items.get(0);
		assertThat(m.get("id").asString()).isEqualTo(id);
		assertThat(m.get("signatureKeyId").asString()).isEqualTo(serverSigner.keyId());
		assertThat(m.get("minAppVersion").asString()).isEqualTo("1.0.0");
		assertThat(m.get("releaseNotes").asString()).isEqualTo("Seed dictionary");
		VerifiedPack stablePack = download(m);
		assertThat(stablePack.manifest().releaseChannel()).isEqualTo("STABLE");
		assertThat(stablePack.manifest().minAppVersion().toString()).isEqualTo("1.0.0");
		assertThat((long) stablePack.containerLength()).isEqualTo(m.get("containerSizeBytes").asLong());
		assertThat(m.get("sizeBytes").asLong()).isEqualTo(original.artifactLength());
		assertThat(discover("platform=IOS")).hasSize(1); // SHARED pack
		assertThat(discover("appVersion=0.9.0")).isEmpty(); // below minAppVersion

		// every action audited: actor, action, entity, outcome; four-eyes approver on the STABLE publish
		List<Map<String, Object>> audit = jdbc.queryForList("""
				SELECT action_code, outcome, actor_user_id, approved_by_user_id, metadata::text AS metadata
				FROM audit_events WHERE entity_type = 'language_resource' AND entity_id = ? ORDER BY created_at, id""", id);
		List<String> trail = new ArrayList<>();
		audit.forEach(a -> trail.add(a.get("action_code") + ":" + a.get("outcome")));
		assertThat(trail).containsExactlyInAnyOrder("LANGUAGE_RESOURCE_UPLOAD:SUCCESS", "LANGUAGE_RESOURCE_STAGE:SUCCESS",
				"LANGUAGE_RESOURCE_PUBLISH:SUCCESS", "LANGUAGE_RESOURCE_PUBLISH:FAILURE",
				"LANGUAGE_RESOURCE_APPROVE_STABLE:SUCCESS", "LANGUAGE_RESOURCE_PUBLISH:FAILURE",
				"LANGUAGE_RESOURCE_APPROVE_STABLE:SUCCESS", "LANGUAGE_RESOURCE_PUBLISH:SUCCESS");
		Map<String, Object> stablePublish = audit.stream()
				.filter(a -> "LANGUAGE_RESOURCE_PUBLISH".equals(a.get("action_code")) && a.get("approved_by_user_id") != null)
				.findFirst().orElseThrow();
		assertThat(stablePublish.get("actor_user_id")).isEqualTo(reviewerId);
		assertThat(stablePublish.get("approved_by_user_id")).isEqualTo(secondReviewerId);
		audit.forEach(a -> assertThat((String) a.get("metadata")).doesNotContain("\"artifact"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
				Integer.class, id, PackPublishingService.EVENT)).isEqualTo(3);

		// retire: clients stop seeing it
		perform(post("/admin/v1/language-resources/" + id + "/retire"), reviewer).andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("RETIRED"));
		assertThat(discover("")).isEmpty();
	}

	// ------------------------------------------------------------------ the step's done-when: rejections

	@Test
	void unsignedTamperedUnknownAndRevokedKeyPacksAreRejected() throws Exception {
		registerServerKey();
		registerKey(pipeline, false);
		registerKey(doomed, false);
		perform(post("/admin/v1/resource-signing-keys/" + doomed.keyId() + "/revoke"), superAdmin)
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
		registerSeedDevKey();
		dataset("ti-words", "ti", "CC0-1.0");

		byte[] artifact = "plain artifact bytes".getBytes(StandardCharsets.UTF_8);
		// "unsigned": a bare artifact, and a container whose signature is all zeros
		expectRejected(upload(artifact, "ti-words"), 422, "MALFORMED");
		byte[] good = pack(pipeline, manifest(UUID.randomUUID(), "ti", "DICTIONARY", "1.0.0", "CC0-1.0", artifact), artifact);
		byte[] zeroSignature = good.clone();
		int signatureAt = 11 + pipeline.keyId().length();
		java.util.Arrays.fill(zeroSignature, signatureAt, signatureAt + 64, (byte) 0);
		expectRejected(upload(zeroSignature, "ti-words"), 422, "BAD_SIGNATURE");
		// tampered signature and tampered manifest of a real seed pack
		byte[] flipped = good.clone();
		flipped[signatureAt + 5] ^= 0x01;
		expectRejected(upload(flipped, "ti-words"), 422, "BAD_SIGNATURE");
		byte[] seed = Files.readAllBytes(SEED_PACKS.resolve("ti-dictionary.pack"));
		int manifestAt = 11 + seed[10] + 64 + 4;
		seed[manifestAt + 20] ^= 0x01;
		expectRejected(upload(seed, "ti-words"), 422, "BAD_SIGNATURE");
		// artifact tampered after signing
		byte[] badArtifact = good.clone();
		badArtifact[badArtifact.length - 1] ^= 0x01;
		expectRejected(upload(badArtifact, "ti-words"), 422, "CHECKSUM_MISMATCH");
		// keys: unknown, revoked
		expectRejected(upload(pack(stranger, manifest(UUID.randomUUID(), "ti", "DICTIONARY", "1.0.0", "CC0-1.0", artifact),
				artifact), "ti-words"), 422, "UNKNOWN_KEY");
		expectRejected(upload(pack(doomed, manifest(UUID.randomUUID(), "ti", "DICTIONARY", "1.0.0", "CC0-1.0", artifact),
				artifact), "ti-words"), 422, "REVOKED_KEY");
		// too large for the upload limit (200 000 bytes in this test)
		expectRejected(upload(new byte[200_001], "ti-words"), 413, "TOO_LARGE");

		// the untampered pack is accepted
		upload(good, "ti-words").andExpect(status().isCreated());
		// nothing of the rejected uploads was stored or registered, and each refusal is audited
		assertThat(jdbc.queryForObject("SELECT count(*) FROM language_resources", Integer.class)).isOne();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM audit_events WHERE actor_user_id = ? AND action_code = 'LANGUAGE_RESOURCE_UPLOAD'
				  AND outcome = 'FAILURE' AND metadata->>'code' = 'PACK_REJECTED'""", Integer.class, editorId)).isEqualTo(8);
	}

	@Test
	void unlicensedNonCommercialAndUnprovenPacksAreRejected() throws Exception {
		registerServerKey();
		registerKey(pipeline, false);
		dataset("om-words", "om", "CC-BY-4.0");
		jdbc.update("""
				INSERT INTO language_datasets (dataset_code, name, license_code, permitted_uses, consent_basis)
				VALUES ('nllb-sample', 'NLLB sample', 'CC-BY-NC-4.0', ARRAY['RESEARCH_ONLY'], 'OPEN_LICENSE')""");
		byte[] artifact = "om words".getBytes(StandardCharsets.UTF_8);

		// missing licence: the signed manifest itself is invalid (licenseCode is mandatory, rule N10)
		String noLicence = manifest(UUID.randomUUID(), "om", "DICTIONARY", "1.0.0", "CC-BY-4.0", artifact)
				.replace(",\"licenseCode\":\"CC-BY-4.0\"", "");
		expectRejected(upload(pack(pipeline, noLicence, artifact), "om-words"), 422, "INVALID_MANIFEST");
		// licences that fail the allow-list (language-data/licenses.json rules)
		expectRejected(upload(omPack("CC-BY-NC-4.0", artifact), "om-words"), 422, "LICENSE_NOT_COMMERCIAL");
		expectRejected(upload(omPack("UNKNOWN", artifact), "om-words"), 422, "LICENSE_NOT_COMMERCIAL");
		expectRejected(upload(omPack("LicenseRef-Made-Up", artifact), "om-words"), 422, "LICENSE_UNKNOWN");
		// datasets: none, unknown, non-commercial
		expectRejected(upload(omPack("CC-BY-4.0", artifact)), 422, "DATASET_MISSING");
		expectRejected(upload(omPack("CC-BY-4.0", artifact), "nope"), 422, "DATASET_UNKNOWN");
		expectRejected(upload(omPack("CC-BY-4.0", artifact), "om-words", "nllb-sample"), 422, "DATASET_NOT_COMMERCIAL");
		// a non-commercial dataset cannot be registered either
		perform(post("/admin/v1/language-datasets").contentType(MediaType.APPLICATION_JSON).content("""
				{"datasets":[{"datasetCode":"mms-sample","licenseCode":"CC-BY-NC-SA-4.0"}]}"""), editor)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("LICENSE_NOT_COMMERCIAL"));
		// other policy checks: unknown language, delta packs
		expectRejected(upload(pack(pipeline, manifest(UUID.randomUUID(), "zz", "DICTIONARY", "1.0.0", "CC-BY-4.0",
				artifact), artifact), "om-words"), 422, "UNKNOWN_LANGUAGE");
		String delta = manifest(UUID.randomUUID(), "om", "DICTIONARY", "1.0.0", "CC-BY-4.0", artifact).replace(
				"\"baseResourceId\":null", "\"baseResourceId\":\"" + UUID.randomUUID() + "\"");
		expectRejected(upload(pack(pipeline, delta, artifact), "om-words"), 422, "DELTA_NOT_SUPPORTED");

		upload(omPack("CC-BY-4.0", artifact), "om-words").andExpect(status().isCreated());
	}

	@Test
	void downgradesAreRejectedPerLanguageTypeAndPlatform() throws Exception {
		registerServerKey();
		registerKey(pipeline, false);
		dataset("ti-words", "ti", "CC0-1.0");
		String v110 = uploadOk(tiDictionary("1.1.0", "SHARED"));
		perform(post("/admin/v1/language-resources/" + v110 + "/stage"), editor).andExpect(status().isOk());

		expectRejected(upload(tiDictionary("1.0.0", "SHARED"), "ti-words"), 422, "DOWNGRADE");
		expectRejected(upload(tiDictionary("1.1.0", "SHARED"), "ti-words"), 422, "DOWNGRADE");
		expectRejected(upload(tiDictionary("1.1.0-rc.1", "SHARED"), "ti-words"), 422, "DOWNGRADE");
		uploadOk(tiDictionary("1.0.0", "ANDROID")); // another slot
		String v120 = uploadOk(tiDictionary("1.2.0", "SHARED"));
		String v130 = uploadOk(tiDictionary("1.3.0", "SHARED"));
		perform(post("/admin/v1/language-resources/" + v130 + "/stage"), editor).andExpect(status().isOk());
		// 1.2.0 was uploaded before 1.3.0 was released: staging it now would be a downgrade
		expectRejected(perform(post("/admin/v1/language-resources/" + v120 + "/stage"), editor), 422, "DOWNGRADE");
		// a retired release still counts (devices may hold it)
		perform(post("/admin/v1/language-resources/" + v130 + "/retire"), reviewer).andExpect(status().isOk());
		expectRejected(upload(tiDictionary("1.3.0", "SHARED"), "ti-words"), 422, "DOWNGRADE");
	}

	@Test
	void rulesPacksMustCarryTheCanonicalBackendProfileCode() throws Exception {
		registerServerKey();
		registerKey(pipeline, false);
		registerSeedDevKey();
		dataset("am-rules", "am", "LicenseRef-Qal-Original");

		// the seed rules pack still uses the old core id "am-phonetic" (ADR 0007 D21)
		expectRejected(upload(Files.readAllBytes(SEED_PACKS.resolve("am-phonetic-rules.pack")), "am-rules"), 422,
				"PROFILE_UNKNOWN");
		byte[] rules = ("{\"format\":\"qal-transliteration-profile\",\"formatVersion\":1,\"language\":\"am\","
				+ "\"profileId\":\"AM_PHONETIC_QWERTY_TO_ETHIOPIC\",\"profileVersion\":\"1.0.0\"}")
				.getBytes(StandardCharsets.UTF_8);
		UUID id = UUID.randomUUID();
		String mismatch = manifest(id, "am", "TRANSLITERATION_RULES", "1.0.0", "LicenseRef-Qal-Original", rules)
				.replace("\"baseResourceId\":null", "\"baseResourceId\":null,\"profile\":{\"code\":\"AM_OTHER\"}");
		expectRejected(upload(pack(pipeline, mismatch, rules), "am-rules"), 422, "PROFILE_MISMATCH");

		upload(pack(pipeline, manifest(id, "am", "TRANSLITERATION_RULES", "1.0.0", "LicenseRef-Qal-Original", rules),
				rules), "am-rules").andExpect(status().isCreated());
		// the V1 profile is DRAFT: fine for INTERNAL testers, not for BETA users (N9)
		perform(post("/admin/v1/language-resources/" + id + "/stage"), editor).andExpect(status().isOk());
		expectRejected(perform(post("/admin/v1/language-resources/" + id + "/publish")
				.contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"BETA\",\"rolloutPercentage\":100}"),
				reviewer), 422, "PROFILE_NOT_ACTIVE");
		jdbc.update("UPDATE transliteration_profiles SET profile_status = 'ACTIVE' "
				+ "WHERE profile_code = 'AM_PHONETIC_QWERTY_TO_ETHIOPIC'");
		perform(post("/admin/v1/language-resources/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
				.content("{\"channel\":\"BETA\",\"rolloutPercentage\":100}"), reviewer).andExpect(status().isOk());
		// published rules pack linked to its profile; discovery shows the transliteration block
		List<JsonNode> items = discover("channel=BETA&type=TRANSLITERATION_RULES");
		assertThat(items).hasSize(1);
		assertThat(items.get(0).get("transliteration").get("profileCode").asString())
				.isEqualTo("AM_PHONETIC_QWERTY_TO_ETHIOPIC");
	}

	// ------------------------------------------------------------------ access control and the state machine

	@Test
	void nonAdminsAreRefusedAndEveryRefusalIsAudited() throws Exception {
		registerServerKey();
		registerKey(pipeline, false);
		dataset("ti-words", "ti", "CC0-1.0");
		byte[] pack = tiDictionary("1.0.0", "SHARED");

		uploadAs(pack, nobody, "ti-words").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(post("/admin/v1/language-resources").contentType(MediaType.APPLICATION_OCTET_STREAM).content(pack))
				.andExpect(status().isUnauthorized());
		UUID guestId = jdbc.queryForObject(
				"INSERT INTO users (account_type, account_status) VALUES ('GUEST', 'ACTIVE') RETURNING id", UUID.class);
		String guest = "Bearer " + tokens.issue(guestId, UUID.randomUUID(), null, "GUEST").value();
		uploadAs(pack, guest, "ti-words").andExpect(status().isForbidden());
		// an expired role assignment grants nothing
		UUID expired = admin("LANGUAGE_EDITOR");
		jdbc.update("UPDATE user_admin_roles SET expires_at = NOW() - INTERVAL '1 minute' WHERE user_id = ?", expired);
		uploadAs(pack, bearer(expired), "ti-words").andExpect(status().isForbidden());

		String id = uploadOk(pack);
		// the editor may stage but not publish; the reviewer may not manage keys
		perform(post("/admin/v1/language-resources/" + id + "/stage"), nobody).andExpect(status().isForbidden());
		perform(post("/admin/v1/language-resources/" + id + "/stage"), editor).andExpect(status().isOk());
		perform(post("/admin/v1/language-resources/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
				.content("{\"channel\":\"BETA\",\"rolloutPercentage\":10}"), editor).andExpect(status().isForbidden());
		perform(post("/admin/v1/language-resources/" + id + "/stable-approval"), editor).andExpect(status().isForbidden());
		perform(get("/admin/v1/pack-signer"), reviewer).andExpect(status().isForbidden());
		perform(get("/admin/v1/language-resources/" + id), nobody).andExpect(status().isForbidden());
		perform(get("/admin/v1/language-resources/" + id), editor).andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("STAGED"));

		assertThat(jdbc.queryForList("""
				SELECT action_code FROM audit_events WHERE actor_user_id = ? AND outcome = 'DENIED'""", String.class,
				nobodyId)).containsExactlyInAnyOrder("LANGUAGE_RESOURCE_UPLOAD", "LANGUAGE_RESOURCE_STAGE",
						"LANGUAGE_RESOURCE_READ");
		assertThat(jdbc.queryForList("""
				SELECT action_code FROM audit_events WHERE actor_user_id = ? AND outcome = 'DENIED'""", String.class,
				editorId)).containsExactlyInAnyOrder("LANGUAGE_RESOURCE_PUBLISH", "LANGUAGE_RESOURCE_APPROVE_STABLE");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE actor_user_id = ? AND outcome = 'DENIED'",
				Integer.class, guestId)).isOne();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE actor_user_id = ? AND outcome = 'DENIED'",
				Integer.class, expired)).isOne();
	}

	@Test
	void transitionsAreValidated() throws Exception {
		registerServerKey();
		registerKey(pipeline, false);
		dataset("ti-words", "ti", "CC0-1.0");
		String id = uploadOk(tiDictionary("2.0.0", "SHARED"));
		String publishBeta = "{\"channel\":\"BETA\",\"rolloutPercentage\":50}";

		// publish before stage
		perform(post("/admin/v1/language-resources/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
				.content(publishBeta), reviewer).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PUBLICATION_CONFLICT"));
		perform(post("/admin/v1/language-resources/" + id + "/stable-approval"), secondReviewer)
				.andExpect(status().isConflict());
		perform(post("/admin/v1/language-resources/" + id + "/stage"), editor).andExpect(status().isOk());
		perform(post("/admin/v1/language-resources/" + id + "/stage"), editor).andExpect(status().isConflict());
		// invalid publish requests
		perform(post("/admin/v1/language-resources/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
				.content("{\"channel\":\"INTERNAL\",\"rolloutPercentage\":150,\"minAppVersion\":\"v1\"}"), reviewer)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(3));
		perform(post("/admin/v1/language-resources/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
				.content(publishBeta), reviewer).andExpect(status().isOk())
				.andExpect(jsonPath("$.rolloutPercentage").value(50));
		// a partial rollout is not shown to anonymous clients (B3.1)
		assertThat(discover("channel=BETA")).isEmpty();
		perform(post("/admin/v1/language-resources/" + id + "/stable-approval"), secondReviewer).andExpect(status().isOk());
		perform(post("/admin/v1/language-resources/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
				.content("{\"channel\":\"STABLE\",\"rolloutPercentage\":100}"), reviewer).andExpect(status().isOk());
		// never narrower
		perform(post("/admin/v1/language-resources/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
				.content(publishBeta), reviewer).andExpect(status().isConflict());
		perform(post("/admin/v1/language-resources/" + id + "/retire"), reviewer).andExpect(status().isOk());
		perform(post("/admin/v1/language-resources/" + id + "/retire"), reviewer).andExpect(status().isConflict());
		perform(post("/admin/v1/language-resources/" + id + "/stage"), editor).andExpect(status().isConflict());
		perform(post("/admin/v1/language-resources/" + UUID.randomUUID() + "/stage"), editor)
				.andExpect(status().isNotFound());
		perform(post("/admin/v1/language-resources/not-a-uuid/stage"), editor).andExpect(status().isBadRequest());
	}

	@Test
	void stagingNeedsTheServerKeyToBeRegistered() throws Exception {
		registerKey(pipeline, false);
		dataset("ti-words", "ti", "CC0-1.0");
		String id = uploadOk(tiDictionary("1.0.0", "SHARED"));
		perform(post("/admin/v1/language-resources/" + id + "/stage"), editor).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PUBLICATION_CONFLICT"));
		perform(get("/admin/v1/pack-signer"), superAdmin).andExpect(status().isOk())
				.andExpect(jsonPath("$.registered").value(false));
		registerServerKey();
		perform(get("/admin/v1/pack-signer"), superAdmin).andExpect(status().isOk())
				.andExpect(jsonPath("$.registered").value(true)).andExpect(jsonPath("$.status").value("ACTIVE"));
		perform(post("/admin/v1/language-resources/" + id + "/stage"), editor).andExpect(status().isOk());
	}

	// ------------------------------------------------------------------ signing keys and endorsements

	@Test
	void signingKeysAreEndorsedRotatedAndRevoked() throws Exception {
		// endorsing needs the server key registered first
		perform(post("/admin/v1/resource-signing-keys").contentType(MediaType.APPLICATION_JSON)
				.content(keyBody(pipeline, true)), superAdmin).andExpect(status().isConflict());
		registerServerKey();
		JsonNode next = json(perform(post("/admin/v1/resource-signing-keys").contentType(MediaType.APPLICATION_JSON)
				.content(keyBody(pipeline, true)), superAdmin).andExpect(status().isCreated()));
		assertThat(next.get("endorsedByKeyId").asString()).isEqualTo(serverSigner.keyId());
		assertThat(KeyEndorsement.verify(serverSigner.publicKey(), pipeline.keyId(), pipeline.publicKey(),
				next.get("endorsementSignature").asString())).isTrue();
		assertThat(KeyEndorsement.verify(serverSigner.publicKey(), "other-id", pipeline.publicKey(),
				next.get("endorsementSignature").asString())).isFalse();
		// announced to clients by B3.1 with the endorsement
		JsonNode announced = json(mvc.perform(get("/api/v1/resource-signing-keys")).andExpect(status().isOk()));
		JsonNode listed = null;
		for (JsonNode k : announced.get("items")) {
			if (pipeline.keyId().equals(k.get("keyId").asString())) {
				listed = k;
			}
		}
		assertThat(listed.get("endorsementSignature").asString()).isEqualTo(next.get("endorsementSignature").asString());

		// duplicates and bad keys
		perform(post("/admin/v1/resource-signing-keys").contentType(MediaType.APPLICATION_JSON)
				.content(keyBody(pipeline, true)), superAdmin).andExpect(status().isConflict());
		perform(post("/admin/v1/resource-signing-keys").contentType(MediaType.APPLICATION_JSON).content(
				"{\"keyId\":\"alias-1\",\"publicKey\":\"" + Base64.getEncoder().encodeToString(pipeline.publicKey()) + "\"}"),
				superAdmin).andExpect(status().isConflict());
		byte[] smallOrder = new byte[32];
		smallOrder[0] = 1; // the identity point
		perform(post("/admin/v1/resource-signing-keys").contentType(MediaType.APPLICATION_JSON).content(
				"{\"keyId\":\"weak-1\",\"publicKey\":\"" + Base64.getEncoder().encodeToString(smallOrder) + "\"}"),
				superAdmin).andExpect(status().isBadRequest());
		perform(post("/admin/v1/resource-signing-keys").contentType(MediaType.APPLICATION_JSON)
				.content(keyBody(doomed, false)), reviewer).andExpect(status().isForbidden());

		// RETIRING keys verify but may not sign new uploads; status only moves forward
		dataset("ti-words", "ti", "CC0-1.0");
		perform(post("/admin/v1/resource-signing-keys/" + pipeline.keyId() + "/retire"), superAdmin)
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RETIRING"));
		expectRejected(upload(tiDictionary("1.0.0", "SHARED"), "ti-words"), 422, "KEY_NOT_ACTIVE");
		perform(post("/admin/v1/resource-signing-keys/" + pipeline.keyId() + "/retire"), superAdmin)
				.andExpect(status().isConflict());
		perform(post("/admin/v1/resource-signing-keys/" + pipeline.keyId() + "/revoke"), superAdmin)
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
		expectRejected(upload(tiDictionary("1.0.0", "SHARED"), "ti-words"), 422, "REVOKED_KEY");
		perform(post("/admin/v1/resource-signing-keys/unknown-key/revoke"), superAdmin).andExpect(status().isNotFound());
		assertThat(jdbc.queryForList("""
				SELECT action_code || ':' || outcome FROM audit_events
				WHERE entity_type = 'resource_signing_key' AND entity_id = ?""", String.class, pipeline.keyId()))
				.contains("RESOURCE_SIGNING_KEY_REGISTER:SUCCESS", "RESOURCE_SIGNING_KEY_RETIRE:SUCCESS",
						"RESOURCE_SIGNING_KEY_RETIRE:FAILURE", "RESOURCE_SIGNING_KEY_REVOKE:SUCCESS");
	}

	// ------------------------------------------------------------------ helpers

	private UUID admin(String roleCode) {
		UUID id = jdbc.queryForObject(
				"INSERT INTO users (account_type, account_status) VALUES ('REGISTERED', 'ACTIVE') RETURNING id", UUID.class);
		if (roleCode != null) {
			jdbc.update("INSERT INTO user_admin_roles (user_id, admin_role_id) SELECT ?, id FROM admin_roles WHERE role_code = ?",
					id, roleCode);
		}
		return id;
	}

	private String bearer(UUID userId) {
		return "Bearer " + tokens.issue(userId, UUID.randomUUID(), null, "REGISTERED").value();
	}

	private static byte[] seed(int fill) {
		byte[] s = new byte[32];
		java.util.Arrays.fill(s, (byte) fill);
		return s;
	}

	private static String manifest(UUID id, String language, String type, String version, String license,
			byte[] artifact) {
		return "{\"resourceId\":\"" + id + "\",\"languageCode\":\"" + language + "\",\"resourceType\":\"" + type
				+ "\",\"version\":\"" + version + "\",\"platform\":\"SHARED\",\"releaseChannel\":\"INTERNAL\",\"sha256\":\""
				+ PackVerifier.sha256Hex(artifact) + "\",\"sizeBytes\":" + artifact.length
				+ ",\"baseResourceId\":null,\"licenseCode\":\"" + license + "\",\"signatureKeyId\":\"%KEY%\"}";
	}

	private static byte[] pack(PackSigner signer, String manifest, byte[] artifact) {
		return PackWriter.sign(signer, manifest.replace("%KEY%", signer.keyId()).getBytes(StandardCharsets.UTF_8),
				artifact);
	}

	private byte[] omPack(String license, byte[] artifact) {
		return pack(pipeline, manifest(UUID.randomUUID(), "om", "DICTIONARY", "1.0.0", license, artifact), artifact);
	}

	private byte[] tiDictionary(String version, String platform) {
		byte[] artifact = ("ti dictionary " + version + " " + platform).getBytes(StandardCharsets.UTF_8);
		return pack(pipeline, manifest(UUID.randomUUID(), "ti", "DICTIONARY", version, "CC0-1.0", artifact)
				.replace("\"platform\":\"SHARED\"", "\"platform\":\"" + platform + "\""), artifact);
	}

	private Json upload(byte[] container, String... datasets) throws Exception {
		return uploadAs(container, editor, datasets);
	}

	private Json uploadAs(byte[] container, String auth, String... datasets) throws Exception {
		var req = post("/admin/v1/language-resources").contentType(MediaType.APPLICATION_OCTET_STREAM).content(container);
		for (String d : datasets) {
			req = req.queryParam("datasetCode", d);
		}
		return perform(req, auth);
	}

	private String uploadOk(byte[] container) throws Exception {
		return upload(container, "ti-words").andExpect(status().isCreated()).andReturnJson().get("id").asString();
	}

	private Json perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String auth)
			throws Exception {
		return new Json(mvc.perform(request.header("Authorization", auth)));
	}

	private static void expectRejected(Json result, int status, String reason) throws Exception {
		result.andExpect(status().is(status)).andExpect(jsonPath("$.code").value("PACK_REJECTED"))
				.andExpect(jsonPath("$.reason").value(reason));
	}

	private void dataset(String code, String language, String license) throws Exception {
		perform(post("/admin/v1/language-datasets").contentType(MediaType.APPLICATION_JSON)
				.content("{\"format\":\"qal-datasets-manifest\",\"formatVersion\":1,\"datasets\":[{\"datasetCode\":\""
						+ code + "\",\"name\":\"" + code + "\",\"languageCode\":\"" + language + "\",\"licenseCode\":\""
						+ license + "\",\"permittedUses\":[\"COMMERCIAL\"],\"sha256\":\"00\"}]}"),
				editor).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].created").value(true))
				.andExpect(jsonPath("$.items[0].reviewStatus").value("PENDING_REVIEW"));
	}

	private void registerServerKey() throws Exception {
		JsonNode signer = json(perform(get("/admin/v1/pack-signer"), superAdmin).andExpect(status().isOk()));
		perform(post("/admin/v1/resource-signing-keys").contentType(MediaType.APPLICATION_JSON)
				.content("{\"keyId\":\"" + signer.get("keyId").asString() + "\",\"publicKey\":\""
						+ signer.get("publicKey").asString() + "\"}"), superAdmin)
				.andExpect(status().isCreated()).andExpect(jsonPath("$.endorsedByKeyId").doesNotExist());
	}

	private void registerKey(PackSigner key, boolean endorse) throws Exception {
		perform(post("/admin/v1/resource-signing-keys").contentType(MediaType.APPLICATION_JSON)
				.content(keyBody(key, endorse)), superAdmin).andExpect(status().isCreated());
	}

	private static String keyBody(PackSigner key, boolean endorse) {
		return "{\"keyId\":\"" + key.keyId() + "\",\"publicKey\":\"" + Base64.getEncoder().encodeToString(key.publicKey())
				+ "\",\"endorse\":" + endorse + "}";
	}

	/** The per-checkout dev key the seed packs are signed with (public half from seed-packs.json). */
	private void registerSeedDevKey() throws Exception {
		perform(post("/admin/v1/resource-signing-keys").contentType(MediaType.APPLICATION_JSON)
				.content("{\"keyId\":\"" + seedKeyId() + "\",\"publicKey\":\""
						+ Base64.getEncoder().encodeToString(HEX.parseHex(seedIndex().get("signingPublicKeyHex").asString()))
						+ "\",\"endorse\":false}"), superAdmin).andExpect(status().isCreated());
	}

	private static JsonNode seedIndex() throws Exception {
		return JSON.readTree(Files.readString(SEED_PACKS.resolve("seed-packs.json"), StandardCharsets.UTF_8));
	}

	private static String seedKeyId() throws Exception {
		return seedIndex().get("signingKeyId").asString();
	}

	private static KeyRing seedKeyRing() throws Exception {
		return KeyRing.of(new TrustedKey(seedKeyId(), HEX.parseHex(seedIndex().get("signingPublicKeyHex").asString()),
				KeyStatus.ACTIVE));
	}

	private static JsonNode seedEntry(String file) throws Exception {
		for (JsonNode p : seedIndex().get("packs")) {
			if (file.equals(p.get("file").asString())) {
				return p;
			}
		}
		throw new IllegalStateException("no seed pack " + file);
	}

	private List<JsonNode> discover(String query) throws Exception {
		String q = "platform=ANDROID&appVersion=1.0.0" + (query.isEmpty() ? "" : "&" + query);
		if (query.startsWith("platform=") || query.startsWith("appVersion=")) {
			q = (query.startsWith("platform=") ? "appVersion=1.0.0&" : "platform=ANDROID&") + query;
		}
		JsonNode body = json(mvc.perform(get("/api/v1/language-resources?" + q)).andExpect(status().isOk()));
		List<JsonNode> out = new ArrayList<>();
		body.get("items").forEach(out::add);
		return out;
	}

	/** Downloads the discovered artifact URL (pre-signed for MinIO) and verifies it with the server key only. */
	private VerifiedPack download(JsonNode item) throws Exception {
		HttpResponse<byte[]> response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
				.send(HttpRequest.newBuilder(URI.create(item.get("artifactUrl").asString())).GET().build(),
						HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).isEqualTo(200);
		return PackVerifier.verify(response.body(),
				KeyRing.of(new TrustedKey(serverSigner.keyId(), serverSigner.publicKey(), KeyStatus.ACTIVE)),
				PackLimits.DEFAULT);
	}

	private static JsonNode json(ResultActions result) throws Exception {
		return JSON.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private static JsonNode json(Json result) throws Exception {
		return result.andReturnJson();
	}

	/** {@link ResultActions} plus a JSON body accessor. */
	private record Json(ResultActions actions) {

		Json andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
			actions.andExpect(matcher);
			return this;
		}

		JsonNode andReturnJson() throws Exception {
			return JSON.readTree(actions.andReturn().getResponse().getContentAsString());
		}
	}
}
