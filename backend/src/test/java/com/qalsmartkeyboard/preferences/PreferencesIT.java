package com.qalsmartkeyboard.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.account.UserAccountStore.PhoneSignIn;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.preferences.PreferencesDtos.PreferencesDto;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B3.3 on PostgreSQL 18 through the real security chain, every response validated against the contract: the revision
 * protocol of both documents, the concurrent-write guarantees (same revision: exactly one winner; N retrying writers: the
 * revision ends at base + N, nothing lost), validation, F14 (a retired profile never blocks a reorder), guests and the
 * guest merge of the two documents. The consent check (403 CONSENT_REQUIRED, declared on this operation) is exercised
 * end to end in ConsentIT.
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class PreferencesIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	final String AM_CODE = "AM_" + UUID.randomUUID().toString().replace("-", "_").toUpperCase();
	static final String SHA = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	UserAccountStore accounts;
	@Autowired
	TransactionTemplate transactions;
	@Autowired
	PreferencesService preferences;
	@Autowired
	PreferencesGuestMerge merge;

	// ---------------------------------------------------------------- helpers

	record User(UUID id, String bearer) {
	}

	User user() {
		UUID id = transactions.execute(s -> {
			String phone = "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
			return ((PhoneSignIn.SignedIn) accounts.signInWithPhone(phone, true, Instant.now())).user().id();
		});
		return new User(id, "Bearer " + tokens.issue(id, UUID.randomUUID(), null, "REGISTERED").value());
	}

	User guest() {
		UUID id = transactions.execute(s -> accounts.createGuest(Instant.now()).id());
		return new User(id, "Bearer " + tokens.issue(id, UUID.randomUUID(), null, "GUEST").value());
	}

	ResultActions send(String path, User u, String body) throws Exception {
		return mvc.perform(put("/api/v1/me/" + path).header("Authorization", u.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	ResultActions read(String path, User u) throws Exception {
		return mvc.perform(get("/api/v1/me/" + path).header("Authorization", u.bearer()));
	}

	int revision(String path, User u) throws Exception {
		return JsonPath.read(read(path, u).andReturn().getResponse().getContentAsString(), "$.revision");
	}

	static String lang(String code, boolean enabled, boolean primary, int order, String mode, String profile) {
		return "{\"languageCode\":\"" + code + "\",\"enabled\":" + enabled + ",\"isPrimary\":" + primary
				+ ",\"sortOrder\":" + order + ",\"inputMode\":\"" + mode + "\""
				+ (profile == null ? "" : ",\"transliterationProfileCode\":\"" + profile + "\"") + "}";
	}

	static String doc(int revision, String... languages) {
		return "{\"revision\":" + revision + ",\"languages\":[" + String.join(",", languages) + "]}";
	}

	/** A body for the writer {@code writer} of the document {@code kind}, based on revision {@code revision}. */
	static String body(String kind, int revision, int writer) {
		return kind.equals("preferences") ? "{\"revision\":" + revision + ",\"keyboardTheme\":\"W" + writer + "\"}"
				: doc(revision, lang("en", true, true, 10 + writer, "LATIN_PASSTHROUGH", null));
	}

	boolean keyInserted;

	/** A profile row of the language: ACTIVE needs a published, signed rules resource (V1 activation trigger). */
	UUID profile(String language, String code, String version, String status) {
		if (!keyInserted) {
			jdbc.update("INSERT INTO resource_signing_keys (key_id, public_key, key_status) VALUES ('prefs-it', "
					+ "'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=', 'ACTIVE') ON CONFLICT DO NOTHING");
			keyInserted = true;
		}
		UUID resource = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO language_resources (id, language_id, resource_type, resource_version, platform, release_channel,
				    rollout_percentage, artifact_uri, sha256_checksum, size_bytes, device_requirements, license_code,
				    signature_key_id, manifest_signature, release_notes, publication_status, published_at)
				VALUES (?, (SELECT id FROM supported_languages WHERE language_code = ?), 'TRANSLITERATION_RULES', ?, 'SHARED',
				    'STABLE', 100, ?, ?, 1000, '{"minRamMb":0,"wifiOnly":true}'::jsonb, 'LicenseRef-Qal-Original',
				    (SELECT id FROM resource_signing_keys WHERE key_id = 'prefs-it'), 'c2ln', 'n', 'PUBLISHED', now())""",
				resource, language, version + "-" + UUID.randomUUID().toString().substring(0, 8), "s3://qal/" + UUID.randomUUID() + ".pack", SHA);
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO transliteration_profiles (id, language_id, profile_code, display_name, target_script_code,
				    profile_version, rules_resource_id, profile_status, created_at)
				VALUES (?, (SELECT id FROM supported_languages WHERE language_code = ?), ?, 'p', 'Ethi', ?, ?, 'ACTIVE',
				    now() + (? * interval '1 second'))""", id, language, code, version, resource,
				jdbc.queryForObject("SELECT count(*) FROM transliteration_profiles", Integer.class));
		if (!"ACTIVE".equals(status)) {
			jdbc.update("UPDATE transliteration_profiles SET profile_status = ? WHERE id = ?", status, id);
		}
		return id;
	}

	// ---------------------------------------------------------------- preferences

	@Test
	void preferencesRevisionProtocol() throws Exception {
		User u = user();
		read("preferences", u).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0))
				.andExpect(jsonPath("$.translationMode").value("MANUAL"))
				.andExpect(jsonPath("$.numeralStyle").value("WESTERN"));

		// first write creates the row at revision 1
		send("preferences", u, "{\"revision\":0,\"numeralStyle\":\"ETHIOPIC\",\"defaultDestinationLanguage\":\"am\","
				+ "\"keyboardTheme\":\"DARK\",\"hapticsEnabled\":false,\"historyRetentionDays\":90}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
				.andExpect(jsonPath("$.numeralStyle").value("ETHIOPIC"))
				.andExpect(jsonPath("$.defaultDestinationLanguage").value("am"))
				.andExpect(jsonPath("$.hapticsEnabled").value(false)).andExpect(jsonPath("$.updatedAt").exists());

		// replacing: what the body omits goes back to its default
		send("preferences", u, "{\"revision\":1,\"calendarSystem\":\"ETHIOPIAN\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.calendarSystem").value("ETHIOPIAN"))
				.andExpect(jsonPath("$.numeralStyle").value("WESTERN"))
				.andExpect(jsonPath("$.defaultDestinationLanguage").doesNotExist())
				.andExpect(jsonPath("$.hapticsEnabled").value(true)).andExpect(jsonPath("$.keyboardTheme").value("SYSTEM"))
				.andExpect(jsonPath("$.historyRetentionDays").value(30));

		// stale, "first write" on an existing row and a future revision are all conflicts that change nothing
		for (int stale : new int[] { 1, 0, 9 }) {
			send("preferences", u, "{\"revision\":" + stale + ",\"calendarSystem\":\"GREGORIAN\"}")
					.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
					.andExpect(jsonPath("$.currentRevision").value(2));
		}
		read("preferences", u).andExpect(jsonPath("$.revision").value(2))
				.andExpect(jsonPath("$.calendarSystem").value("ETHIOPIAN"));

		// no row and a non-zero revision
		send("preferences", user(), "{\"revision\":4}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.currentRevision").value(0));
	}

	@Test
	void preferencesValidation() throws Exception {
		User u = user();
		send("preferences", u, "{\"revision\":0,\"defaultSourceLanguage\":\"xx\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LANGUAGE_NOT_SUPPORTED"))
				.andExpect(jsonPath("$.errors[0].field").value("defaultSourceLanguage"));
		jdbc.update("UPDATE supported_languages SET is_active = FALSE WHERE language_code = 'sw'");
		try {
			send("preferences", u, "{\"revision\":0,\"preferredVoiceLanguage\":\"sw\"}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LANGUAGE_NOT_SUPPORTED"));
		}
		finally {
			jdbc.update("UPDATE supported_languages SET is_active = TRUE WHERE language_code = 'sw'");
		}
		send("preferences", u, "{\"revision\":0,\"historyRetentionDays\":0}").andExpect(status().isBadRequest());
		assertThat(revision("preferences", u)).isZero();
	}

	@Test
	void historyNeedsTheConsent() {
		User u = user();
		assertThatThrownBy(() -> preferences.replace(u.id(), withHistory(0))).isInstanceOf(ApiException.class)
				.hasMessageContaining("CONSENT_REQUIRED");
		jdbc.update("INSERT INTO user_consents (user_id, consent_code, consent_version, is_granted) "
				+ "VALUES (?, 'SAVE_AI_HISTORY', '1', TRUE)", u.id());
		assertThat(preferences.replace(u.id(), withHistory(0)).historyEnabled()).isTrue();
		jdbc.update("INSERT INTO user_consents (user_id, consent_code, consent_version, is_granted, recorded_at) "
				+ "VALUES (?, 'SAVE_AI_HISTORY', '1', FALSE, now() + interval '1 second')", u.id());
		assertThatThrownBy(() -> preferences.replace(u.id(), withHistory(1))).isInstanceOf(ApiException.class);
		assertThat(preferences.replace(u.id(), new PreferencesDto(1, null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, false, null, null, null, null, null, null)).historyEnabled())
				.isFalse();
	}

	static PreferencesDto withHistory(int revision) {
		return new PreferencesDto(revision, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, true, 30, null, null, null, null, null);
	}

	// ---------------------------------------------------------------- concurrency (the step's done-when)

	/** Several writers that read the same revision: exactly one 200, the rest 409, the revision grows by one. */
	@Test
	void concurrentWritersWithTheSameRevisionExactlyOneWins() throws Exception {
		User u = user();
		send("preferences", u, "{\"revision\":0}").andExpect(status().isOk());
		send("typing-languages", u, doc(0, lang("en", true, true, 10, "LATIN_PASSTHROUGH", null)))
				.andExpect(status().isOk());
		for (String kind : List.of("preferences", "typing-languages")) {
			for (int writers : new int[] { 2, 8 }) {
				int base = revision(kind, u);
				ExecutorService pool = Executors.newFixedThreadPool(writers);
				CountDownLatch ready = new CountDownLatch(writers);
				CountDownLatch go = new CountDownLatch(1);
				List<Future<Integer>> futures = new ArrayList<>();
				for (int w = 0; w < writers; w++) {
					String request = body(kind, base, w);
					futures.add(pool.submit(() -> {
						ready.countDown();
						go.await();
						return send(kind, u, request).andReturn().getResponse().getStatus();
					}));
				}
				ready.await();
				go.countDown();
				int[] codes = new int[writers];
				for (int w = 0; w < writers; w++) {
					codes[w] = futures.get(w).get(60, TimeUnit.SECONDS);
				}
				pool.shutdown();
				assertThat(Arrays.stream(codes).filter(c -> c == 200).count()).as(kind + " winners of " + writers)
						.isEqualTo(1);
				assertThat(Arrays.stream(codes).filter(c -> c == 409).count()).isEqualTo(writers - 1);
				assertThat(revision(kind, u)).isEqualTo(base + 1);
			}
		}
	}

	/** N writers that retry on 409 until they win: the revision ends at base + N, so no write was lost or doubled. */
	@Test
	void parallelRetryingWritersLoseNoUpdate() throws Exception {
		User u = user();
		int n = 12;
		for (String kind : List.of("preferences", "typing-languages")) {
			int base = revision(kind, u);
			ExecutorService pool = Executors.newFixedThreadPool(n);
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				int writer = i;
				results.add(pool.submit(() -> {
					go.await();
					for (int attempt = 0; attempt < 200; attempt++) {
						int code = send(kind, u, body(kind, revision(kind, u), writer)).andReturn().getResponse()
								.getStatus();
						if (code == 200) {
							return 200;
						}
						assertThat(code).isEqualTo(409);
					}
					return -1;
				}));
			}
			go.countDown();
			int wins = 0;
			for (Future<Integer> f : results) {
				wins += f.get(120, TimeUnit.SECONDS) == 200 ? 1 : 0;
			}
			pool.shutdown();
			assertThat(wins).as(kind).isEqualTo(n);
			assertThat(revision(kind, u)).as(kind + " revision").isEqualTo(base + n);
		}
	}

	// ---------------------------------------------------------------- typing languages

	@Test
	void typingLanguagesRoundTripAndReplace() throws Exception {
		profile("am", AM_CODE, "1.0.0", "ACTIVE");
		User u = user();
		read("typing-languages", u).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0))
				.andExpect(jsonPath("$.languages").isEmpty());
		send("typing-languages", u,
				doc(0, lang("en", true, false, 20, "LATIN_PASSTHROUGH", null),
						lang("am", true, true, 10, "PHONETIC_LATIN_TO_NATIVE", AM_CODE),
						lang("om", false, false, 30, "LATIN_PASSTHROUGH", null)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
				.andExpect(jsonPath("$.languages[0].languageCode").value("am"))
				.andExpect(jsonPath("$.languages[0].transliterationProfileCode").value(AM_CODE))
				.andExpect(jsonPath("$.languages[0].isPrimary").value(true))
				.andExpect(jsonPath("$.languages[0].conversionTrigger").value("LIVE_COMPOSING"))
				.andExpect(jsonPath("$.languages[1].languageCode").value("en"))
				.andExpect(jsonPath("$.languages[2].languageCode").value("om"));

		// replace: om dropped, primary moves to en, am goes to direct layout; the profile is detached
		send("typing-languages", u, doc(1, lang("en", true, true, 10, "LATIN_PASSTHROUGH", null),
				lang("am", true, false, 20, "DIRECT_SCRIPT_LAYOUT", null))).andExpect(status().isOk())
				.andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.languages.length()").value(2))
				.andExpect(jsonPath("$.languages[0].languageCode").value("en"))
				.andExpect(jsonPath("$.languages[0].isPrimary").value(true))
				.andExpect(jsonPath("$.languages[1].transliterationProfileCode").doesNotExist());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_typing_languages WHERE user_id = ?", Integer.class,
				u.id())).isEqualTo(2);

		// stale write: 409 and the list is unchanged
		send("typing-languages", u, doc(1, lang("om", true, true, 10, "LATIN_PASSTHROUGH", null)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
				.andExpect(jsonPath("$.currentRevision").value(2));
		read("typing-languages", u).andExpect(jsonPath("$.languages.length()").value(2));

		// the empty list is a valid document and bumps the revision
		send("typing-languages", u, doc(2)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3))
				.andExpect(jsonPath("$.languages").isEmpty());
	}

	@Test
	void typingLanguagesValidation() throws Exception {
		profile("am", AM_CODE, "1.0.0", "ACTIVE");
		User u = user();
		String en = lang("en", true, true, 10, "LATIN_PASSTHROUGH", null);
		String am = lang("am", true, false, 20, "DIRECT_SCRIPT_LAYOUT", null);
		bad(u, doc(0, en, en), "VALIDATION_FAILED");
		bad(u, doc(0, en, lang("am", true, true, 20, "DIRECT_SCRIPT_LAYOUT", null)), "VALIDATION_FAILED");
		bad(u, doc(0, en, lang("am", true, false, 10, "DIRECT_SCRIPT_LAYOUT", null)), "VALIDATION_FAILED");
		bad(u, doc(0, lang("en", true, false, 10, "DIRECT_SCRIPT_LAYOUT", null)), "VALIDATION_FAILED");
		bad(u, doc(0, lang("om", true, false, 10, "PHONETIC_LATIN_TO_NATIVE", AM_CODE)), "VALIDATION_FAILED");
		bad(u, doc(0, lang("am", true, false, 10, "PHONETIC_LATIN_TO_NATIVE", null)), "VALIDATION_FAILED");
		bad(u, doc(0, lang("am", true, false, 10, "LATIN_PASSTHROUGH", AM_CODE)), "VALIDATION_FAILED");
		bad(u, doc(0, en, lang("xx", true, false, 20, "LATIN_PASSTHROUGH", null)), "LANGUAGE_NOT_SUPPORTED");
		jdbc.update("UPDATE supported_languages SET is_active = FALSE WHERE language_code = 'sw'");
		try {
			bad(u, doc(0, lang("sw", true, false, 20, "LATIN_PASSTHROUGH", null)), "LANGUAGE_NOT_SUPPORTED");
		}
		finally {
			jdbc.update("UPDATE supported_languages SET is_active = TRUE WHERE language_code = 'sw'");
		}
		// a valid list still goes through after all the failures, at revision 0 -> 1
		send("typing-languages", u, doc(0, en, am)).andExpect(status().isOk());
		assertThat(revision("typing-languages", u)).isEqualTo(1);
	}

	void bad(User u, String body, String code) throws Exception {
		int before = revision("typing-languages", u);
		send("typing-languages", u, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code));
		assertThat(revision("typing-languages", u)).isEqualTo(before);
	}

	@Test
	void aProfileThatIsNotActiveIs422() throws Exception {
		profile("am", AM_CODE, "1.0.0", "ACTIVE");
		profile("ti", "TI_RETIRED", "1.0.0", "RETIRED");
		jdbc.update("""
				INSERT INTO transliteration_profiles (language_id, profile_code, display_name, target_script_code,
				    profile_version) SELECT id, 'AM_DRAFT', 'd', 'Ethi', '1' FROM supported_languages WHERE language_code = 'am'""");
		User u = user();
		for (String[] c : new String[][] { { "am", "AM_DRAFT" }, { "am", "NO_SUCH" }, { "ti", AM_CODE },
				{ "ti", "TI_RETIRED" } }) {
			send("typing-languages", u, doc(0, lang(c[0], true, true, 10, "PHONETIC_LATIN_TO_NATIVE", c[1])))
					.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PROFILE_NOT_ACTIVE"))
					.andExpect(jsonPath("$.errors[0].field").value("languages[0].transliterationProfileCode"));
		}
		assertThat(revision("typing-languages", u)).isZero(); // the 422 rolled the revision back too
	}

	/** F14: retiring a profile never blocks reordering or any other edit that keeps the profile. */
	@Test
	void reorderingNeverFailsBecauseAProfileWasRetired() throws Exception {
		UUID v1 = profile("am", AM_CODE, "1.0.0", "ACTIVE");
		User u = user();
		send("typing-languages", u, doc(0, lang("am", true, true, 10, "PHONETIC_LATIN_TO_NATIVE", AM_CODE),
				lang("en", true, false, 20, "LATIN_PASSTHROUGH", null))).andExpect(status().isOk());
		jdbc.update("UPDATE transliteration_profiles SET profile_status = 'RETIRED' WHERE id = ?", v1);

		// reorder, disable, add a language: all accepted, the row keeps its (now RETIRED) profile unchanged
		send("typing-languages", u, doc(1, lang("en", true, true, 10, "LATIN_PASSTHROUGH", null),
				lang("am", false, false, 20, "PHONETIC_LATIN_TO_NATIVE", AM_CODE),
				lang("om", true, false, 30, "LATIN_PASSTHROUGH", null))).andExpect(status().isOk())
				.andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.languages[0].languageCode").value("en"))
				.andExpect(jsonPath("$.languages[1].languageCode").value("am"))
				.andExpect(jsonPath("$.languages[1].enabled").value(false))
				.andExpect(jsonPath("$.languages[1].transliterationProfileCode").value(AM_CODE));
		assertThat(jdbc.queryForObject("""
				SELECT transliteration_profile_id FROM user_typing_languages t
				JOIN supported_languages l ON l.id = t.language_id WHERE t.user_id = ? AND l.language_code = 'am'""",
				UUID.class, u.id())).isEqualTo(v1);

		// a profile code the row never had is not accepted just because the old one was retired
		profile("ti", "TI_ACTIVE", "1.0.0", "ACTIVE");
		send("typing-languages", u, doc(2, lang("am", true, true, 10, "PHONETIC_LATIN_TO_NATIVE", "TI_ACTIVE")))
				.andExpect(status().isUnprocessableContent());

		// once a newer ACTIVE version of the code exists, the code resolves to it
		UUID v2 = profile("am", AM_CODE, "1.1.0", "ACTIVE");
		send("typing-languages", u, doc(2, lang("am", true, true, 10, "PHONETIC_LATIN_TO_NATIVE", AM_CODE)))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT transliteration_profile_id FROM user_typing_languages WHERE user_id = ?",
				UUID.class, u.id())).isEqualTo(v2);
	}

	// ---------------------------------------------------------------- guests and merge

	@Test
	void guestsMayUseBothDocumentsAndUnauthenticatedIs401() throws Exception {
		User g = guest();
		send("preferences", g, "{\"revision\":0,\"numeralStyle\":\"ETHIOPIC\"}").andExpect(status().isOk());
		send("typing-languages", g, doc(0, lang("en", true, true, 10, "LATIN_PASSTHROUGH", null)))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v1/me/preferences")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me/typing-languages")).andExpect(status().isUnauthorized());
	}

	@Test
	void guestMergeMovesTheDocumentsAndBumpsTheTargetsListRevision() throws Exception {
		User guestWithDocs = guest();
		User target = user();
		send("preferences", guestWithDocs, "{\"revision\":0,\"numeralStyle\":\"ETHIOPIC\"}").andExpect(status().isOk());
		send("preferences", guestWithDocs, "{\"revision\":1,\"numeralStyle\":\"ETHIOPIC\"}").andExpect(status().isOk());
		send("typing-languages", guestWithDocs, doc(0, lang("en", true, true, 10, "LATIN_PASSTHROUGH", null)))
				.andExpect(status().isOk());
		transactions.executeWithoutResult(s -> merge.mergeGuest(guestWithDocs.id(), target.id(), Instant.now()));

		read("preferences", target).andExpect(jsonPath("$.revision").value(2))
				.andExpect(jsonPath("$.numeralStyle").value("ETHIOPIC"));
		read("typing-languages", target).andExpect(jsonPath("$.revision").value(1))
				.andExpect(jsonPath("$.languages[0].languageCode").value("en"));
		// a target device that cached "no document" is told to re-read
		send("typing-languages", target, doc(0)).andExpect(status().isConflict());
		send("preferences", target, "{\"revision\":0}").andExpect(status().isConflict());
		send("typing-languages", target, doc(1, lang("en", true, true, 10, "LATIN_PASSTHROUGH", null)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_typing_language_revisions WHERE user_id = ?",
				Integer.class, guestWithDocs.id())).isZero();

		// the target's own documents win: nothing moves, nothing changes
		User guest2 = guest();
		send("preferences", guest2, "{\"revision\":0,\"numeralStyle\":\"WESTERN\"}").andExpect(status().isOk());
		send("typing-languages", guest2, doc(0, lang("om", true, true, 10, "LATIN_PASSTHROUGH", null)))
				.andExpect(status().isOk());
		transactions.executeWithoutResult(s -> merge.mergeGuest(guest2.id(), target.id(), Instant.now()));
		read("preferences", target).andExpect(jsonPath("$.numeralStyle").value("ETHIOPIC"));
		read("typing-languages", target).andExpect(jsonPath("$.revision").value(2))
				.andExpect(jsonPath("$.languages[0].languageCode").value("en"));
	}
}
