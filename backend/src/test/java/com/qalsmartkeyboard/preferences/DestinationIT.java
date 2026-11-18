package com.qalsmartkeyboard.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

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
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B3.4 on PostgreSQL 18 through the real security chain, every response validated against the contract. The done-when
 * test ({@link #noPlaintextLabelAndNoPackageNameIsEverStored}) scans every column of every row the feature writes.
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class DestinationIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final String WHATSAPP = "com.whatsapp";
	static final String TELEGRAM = "org.telegram.messenger";
	static final String GMAIL = "com.google.android.gm";

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
	UserDataEncryption encryption;
	@Autowired
	AppRuleKeys ruleKeys;
	@Autowired
	PreferencesGuestMerge merge;

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

	ResultActions create(User u, String body) throws Exception {
		return mvc.perform(post("/api/v1/me/destination-profiles").header("Authorization", u.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	ResultActions replace(User u, UUID id, String body) throws Exception {
		return mvc.perform(put("/api/v1/me/destination-profiles/" + id).header("Authorization", u.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	ResultActions list(User u) throws Exception {
		return mvc.perform(get("/api/v1/me/destination-profiles").header("Authorization", u.bearer()));
	}

	ResultActions remove(User u, UUID id) throws Exception {
		return mvc.perform(delete("/api/v1/me/destination-profiles/" + id).header("Authorization", u.bearer()));
	}

	ResultActions putRules(User u, int revision, String... rules) throws Exception {
		return mvc.perform(put("/api/v1/me/app-destination-rules").header("Authorization", u.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"revision\":" + revision + ",\"rules\":[" + String.join(",", rules) + "]}"));
	}

	ResultActions getRules(User u) throws Exception {
		return mvc.perform(get("/api/v1/me/app-destination-rules").header("Authorization", u.bearer()));
	}

	static String profileBody(String label) {
		return "{\"label\":" + json(label) + ",\"destinationLanguage\":\"am\",\"formality\":\"HONORIFIC\","
				+ "\"addresseeGender\":\"FEMININE\",\"defaultTone\":\"POLITE\",\"defaultStyle\":\"POLITE\"}";
	}

	static String json(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/** A stand-in for the client's HMAC: 64 hex characters, distinct per seed. */
	static String hmac(String seed) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static String ruleToLanguage(String hmac, String language) {
		return "{\"appPackageHmac\":\"" + hmac + "\",\"destinationLanguage\":\"" + language + "\"}";
	}

	static String ruleToProfile(String hmac, String profileId) {
		return "{\"appPackageHmac\":\"" + hmac + "\",\"destinationProfileId\":\"" + profileId + "\"}";
	}

	UUID createProfile(User u, String label) throws Exception {
		String body = create(u, profileBody(label)).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	// ----------------------------------------------------------------------------------------------- profiles

	@Test
	void profileCrudRoundTripAndTheLabelIsCiphertextBoundToItsRow() throws Exception {
		User u = user();
		list(u).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		String label = "እናቴ (Mum) " + UUID.randomUUID();
		String body = create(u, profileBody(label)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.label").value(label)).andExpect(jsonPath("$.destinationLanguage").value("am"))
				.andExpect(jsonPath("$.formality").value("HONORIFIC"))
				.andExpect(jsonPath("$.addresseeGender").value("FEMININE"))
				.andExpect(jsonPath("$.defaultTone").value("POLITE")).andExpect(jsonPath("$.defaultStyle").value("POLITE"))
				.andExpect(jsonPath("$.sortOrder").value(100)).andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

		byte[] stored = jdbc.queryForObject("SELECT label_ciphertext FROM user_destination_profiles WHERE id = ?",
				byte[].class, id);
		assertThat(indexOf(stored, label.getBytes(StandardCharsets.UTF_8))).isEqualTo(-1);
		assertThat(encryption.decryptString(u.id(),
				EncryptionContext.of("user_destination_profiles", "label_ciphertext", id), stored)).isEqualTo(label);
		// bound to the row: the same bytes do not open under another row id
		assertThatThrownBy(() -> encryption.decryptString(u.id(),
				EncryptionContext.of("user_destination_profiles", "label_ciphertext", UUID.randomUUID()), stored))
				.isNotNull();

		list(u).andExpect(jsonPath("$.items[0].label").value(label))
				.andExpect(jsonPath("$.items[0].id").value(id.toString()));
		// replace: defaults for absent members, a fresh ciphertext
		replace(u, id, "{\"label\":\"Work\",\"destinationLanguage\":\"en\",\"sortOrder\":5}").andExpect(status().isOk())
				.andExpect(jsonPath("$.label").value("Work")).andExpect(jsonPath("$.formality").value("INFORMAL"))
				.andExpect(jsonPath("$.addresseeGender").value("UNKNOWN"))
				.andExpect(jsonPath("$.defaultTone").doesNotExist()).andExpect(jsonPath("$.defaultStyle").doesNotExist())
				.andExpect(jsonPath("$.sortOrder").value(5));
		assertThat(jdbc.queryForObject("SELECT label_ciphertext FROM user_destination_profiles WHERE id = ?",
				byte[].class, id)).isNotEqualTo(stored);
		remove(u, id).andExpect(status().isNoContent());
		list(u).andExpect(jsonPath("$.items").isEmpty());
		remove(u, id).andExpect(status().isNotFound());
	}

	@Test
	void profilesAreIsolatedBetweenUsers() throws Exception {
		User a = user();
		User b = user();
		UUID id = createProfile(a, "Secret label");
		list(b).andExpect(jsonPath("$.items").isEmpty());
		replace(b, id, profileBody("Hijack")).andExpect(status().isNotFound());
		remove(b, id).andExpect(status().isNotFound());
		list(a).andExpect(jsonPath("$.items[0].label").value("Secret label"));
		// and a rule cannot point at another user's profile
		putRules(b, 0, ruleToProfile(hmac("x"), id.toString())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void profileValidation() throws Exception {
		User u = user();
		create(u, "{\"label\":\"   \",\"destinationLanguage\":\"am\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		create(u, "{\"label\":\"x\",\"destinationLanguage\":\"zz\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LANGUAGE_NOT_SUPPORTED"));
		create(u, "{\"label\":\"x\",\"destinationLanguage\":\"am\",\"glossaryId\":\"" + UUID.randomUUID() + "\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		replace(u, UUID.randomUUID(), profileBody("x")).andExpect(status().isNotFound());
		list(u).andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void theProfileCapIs100() throws Exception {
		User u = user();
		for (int i = 0; i < DestinationProfilesService.MAX_PROFILES; i++) {
			jdbc.update("""
					INSERT INTO user_destination_profiles (user_id, label_ciphertext, key_version, destination_language_id)
					VALUES (?, ?, 1, (SELECT id FROM supported_languages WHERE language_code = 'am'))
					""", u.id(), new byte[] { 1 });
		}
		create(u, profileBody("one too many")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	// -------------------------------------------------------------------------------------------------- rules

	/** ADR 0007 D24: the key is delivered over HTTP, created on first read, stable, per account, never cached. */
	@Test
	void theRuleKeyIsDeliveredNoStoreCreatedOnFirstReadAndStablePerAccount() throws Exception {
		User a = user();
		User b = guest();
		assertThat(ruleKeys.exists(a.id())).isFalse();
		String first = mvc.perform(get("/api/v1/me/app-destination-rules/key").header("Authorization", a.bearer()))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.keyId").isNotEmpty()).andReturn().getResponse().getContentAsString();
		assertThat(ruleKeys.exists(a.id())).isTrue();
		String key = com.jayway.jsonpath.JsonPath.read(first, "$.key");
		assertThat(Base64.getDecoder().decode(key)).hasSize(32);
		// stable across reads and across the first rules PUT (which no longer has to create it)
		putRules(a, 0, ruleToLanguage(hmac(TELEGRAM), "en")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/me/app-destination-rules/key").header("Authorization", a.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.key").value(key))
				.andExpect(jsonPath("$.keyId").value((String) com.jayway.jsonpath.JsonPath.read(first, "$.keyId")));
		// a guest may read its own; another account never gets the same key
		mvc.perform(get("/api/v1/me/app-destination-rules/key").header("Authorization", b.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.key").value(org.hamcrest.Matchers.not(key)));
		mvc.perform(get("/api/v1/me/app-destination-rules/key")).andExpect(status().isUnauthorized());
	}

	@Test
	void rulesRevisionProtocolAndValidation() throws Exception {
		User u = user();
		UUID profile = createProfile(u, "Mum");
		getRules(u).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0))
				.andExpect(jsonPath("$.rules").isEmpty());
		putRules(u, 0, ruleToProfile(hmac(WHATSAPP), profile.toString()), ruleToLanguage(hmac(TELEGRAM), "en"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
				.andExpect(jsonPath("$.rules.length()").value(2));
		getRules(u).andExpect(jsonPath("$.rules[?(@.appPackageHmac=='" + hmac(WHATSAPP) + "')].destinationProfileId")
				.value(profile.toString())).andExpect(jsonPath("$.rules[0].enabled").value(true));
		// stale and future revisions
		putRules(u, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
				.andExpect(jsonPath("$.currentRevision").value(1));
		putRules(u, 5).andExpect(status().isConflict());
		// a disabled rule round-trips
		putRules(u, 1, "{\"appPackageHmac\":\"" + hmac("a") + "\",\"destinationLanguage\":\"am\",\"enabled\":false}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2))
				.andExpect(jsonPath("$.rules[0].enabled").value(false));

		// validation: nothing is changed by a rejected write
		putRules(u, 2, ruleToLanguage(hmac("d"), "am"), ruleToLanguage(hmac("d"), "en")).andExpect(status().isBadRequest());
		putRules(u, 2, "{\"appPackageHmac\":\"" + hmac("e") + "\"}").andExpect(status().isBadRequest());
		putRules(u, 2, "{\"appPackageHmac\":\"" + hmac("e") + "\",\"destinationLanguage\":\"am\","
				+ "\"destinationProfileId\":\"" + profile + "\"}").andExpect(status().isBadRequest());
		putRules(u, 2, ruleToLanguage(hmac("e"), "zz")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LANGUAGE_NOT_SUPPORTED"));
		putRules(u, 2, ruleToProfile(hmac("e"), UUID.randomUUID().toString())).andExpect(status().isBadRequest());
		getRules(u).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.rules.length()").value(1));
	}

	@Test
	void rawPackageNamesAreRejectedWith400AndNothingIsStored() throws Exception {
		User u = user();
		for (String raw : List.of(WHATSAPP, TELEGRAM, "com.whatsapp.w4b", WHATSAPP.toUpperCase(),
				hmac("a").toUpperCase(), hmac("a") + "0", hmac("a").substring(1), "g".repeat(64))) {
			putRules(u, 0, ruleToLanguage(raw, "am")).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_app_destination_rules WHERE user_id = ?",
				Integer.class, u.id())).isZero();
		getRules(u).andExpect(jsonPath("$.revision").value(0));
	}

	@Test
	void deletingAProfileRemovesItsRulesAndBumpsTheRevision() throws Exception {
		User u = user();
		UUID profile = createProfile(u, "Mum");
		putRules(u, 0, ruleToProfile(hmac("a"), profile.toString()), ruleToLanguage(hmac("b"), "en"))
				.andExpect(status().isOk());
		remove(u, profile).andExpect(status().isNoContent());
		getRules(u).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.rules.length()").value(1))
				.andExpect(jsonPath("$.rules[0].destinationLanguage").value("en"));
		// a profile no rule points at leaves the revision alone
		UUID plain = createProfile(u, "Plain");
		remove(u, plain).andExpect(status().isNoContent());
		getRules(u).andExpect(jsonPath("$.revision").value(2));
	}

	@Test
	void concurrentRuleWritersOnOneRevisionHaveOneWinner() throws Exception {
		User u = user();
		List<Integer> codes = Collections.synchronizedList(new ArrayList<>());
		List<Thread> threads = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			String h = hmac("w" + i);
			Thread t = new Thread(() -> {
				try {
					codes.add(putRules(u, 0, ruleToLanguage(h, "am")).andReturn().getResponse().getStatus());
				}
				catch (Exception e) {
					codes.add(-1);
				}
			});
			threads.add(t);
			t.start();
		}
		for (Thread t : threads) {
			t.join();
		}
		assertThat(codes.stream().filter(c -> c == 200)).hasSize(1);
		assertThat(codes.stream().filter(c -> c == 409)).hasSize(5);
		getRules(u).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.rules.length()").value(1));
	}

	// ------------------------------------------------------------------------- the done-when: nothing in the clear

	@Test
	void noPlaintextLabelAndNoPackageNameIsEverStored() throws Exception {
		User u = user();
		String label1 = "Mum-" + UUID.randomUUID();
		String label2 = "Boss ብት " + UUID.randomUUID();
		UUID p1 = createProfile(u, label1);
		UUID p2 = createProfile(u, label2);
		replace(u, p2, profileBody(label2 + " v2")).andExpect(status().isOk());
		String[] rules = { ruleToProfile(hmac(WHATSAPP), p1.toString()), ruleToLanguage(hmac(TELEGRAM), "en"),
				ruleToProfile(hmac(GMAIL), p2.toString()) };
		putRules(u, 0, rules).andExpect(status().isOk());
		putRules(u, 1, rules).andExpect(status().isOk());
		String key = ruleKeys.getOrCreate(u.id());

		List<String> secrets = List.of(label1, label2, label2 + " v2", WHATSAPP, TELEGRAM, GMAIL, key);
		Pattern packageLike = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
		Pattern hexHash = Pattern.compile("[0-9a-f]{64}");
		int rows = 0;
		for (String table : List.of("user_destination_profiles", "user_app_destination_rules",
				"user_app_rule_revisions", "user_app_rule_keys")) {
			List<List<Object>> all = jdbc.query("SELECT * FROM " + table + " WHERE user_id = ?", (rs, n) -> {
				ResultSetMetaData md = rs.getMetaData();
				List<Object> cells = new ArrayList<>();
				for (int c = 1; c <= md.getColumnCount(); c++) {
					cells.add(rs.getObject(c));
				}
				return cells;
			}, u.id());
			for (List<Object> row : all) {
				rows++;
				for (Object cell : row) {
					if (cell == null) {
						continue;
					}
					if (cell instanceof byte[] bytes) {
						String text = new String(bytes, StandardCharsets.ISO_8859_1);
						for (String secret : secrets) {
							assertThat(indexOf(bytes, secret.getBytes(StandardCharsets.UTF_8)))
									.as(table + " bytes must not contain " + secret).isEqualTo(-1);
							assertThat(text).doesNotContain(secret);
						}
						continue;
					}
					String text = cell.toString();
					for (String secret : secrets) {
						assertThat(text).as(table + " must not contain " + secret).doesNotContain(secret);
					}
					boolean temporal = cell instanceof java.util.Date || cell instanceof java.time.temporal.TemporalAccessor;
					if (!temporal) {
						assertThat(packageLike.matcher(text).find()).as(table + " value looks like a package name: " + text)
								.isFalse();
					}
					if (table.equals("user_app_destination_rules") && cell instanceof String s && s.trim().length() > 20) {
						assertThat(hexHash.matcher(s.trim()).matches()).as("rule column is a bare hash").isTrue();
					}
				}
			}
		}
		assertThat(rows).isEqualTo(2 + 3 + 1 + 1);
		// and the stored hashes are exactly what the client sent
		assertThat(jdbc.queryForList("SELECT app_package_hmac FROM user_app_destination_rules WHERE user_id = ?",
				String.class, u.id())).containsExactlyInAnyOrder(hmac(WHATSAPP), hmac(TELEGRAM), hmac(GMAIL));
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	// ----------------------------------------------------------------------------------------------- the rule key

	@Test
	void theRuleKeyIsCreatedWithTheFirstRuleStableAndOnlyStoredAsCiphertext() throws Exception {
		User u = user();
		putRules(u, 0).andExpect(status().isOk());
		assertThat(ruleKeys.exists(u.id())).isFalse();
		putRules(u, 1, ruleToLanguage(hmac("a"), "am")).andExpect(status().isOk());
		assertThat(ruleKeys.exists(u.id())).isTrue();
		String key = ruleKeys.getOrCreate(u.id());
		assertThat(key).hasSize(43); // 256 bits, base64url without padding
		putRules(u, 2, ruleToLanguage(hmac("b"), "am")).andExpect(status().isOk());
		assertThat(ruleKeys.getOrCreate(u.id())).isEqualTo(key); // stable per account across writes
		byte[] stored = jdbc.queryForObject("SELECT key_ciphertext FROM user_app_rule_keys WHERE user_id = ?",
				byte[].class, u.id());
		byte[] raw = Base64.getUrlDecoder().decode(key);
		assertThat(indexOf(stored, raw)).isEqualTo(-1);
		assertThat(Arrays.equals(encryption.decrypt(u.id(), AppRuleKeys.context(u.id()), stored), raw)).isTrue();
	}

	// -------------------------------------------------------------------------------------------------- merge

	@Test
	void guestMergeAdoptsTheGuestKeyWhenTheTargetHasNoneAndKeepsTheRulesMatching() throws Exception {
		User g = guest();
		User t = user();
		UUID profile = createProfile(g, "Mum");
		putRules(g, 0, ruleToProfile(hmac(WHATSAPP), profile.toString()), ruleToLanguage(hmac(TELEGRAM), "en"))
				.andExpect(status().isOk());
		String guestKey = ruleKeys.getOrCreate(g.id());
		transactions.executeWithoutResult(s -> merge.mergeGuest(g.id(), t.id(), Instant.now()));

		assertThat(ruleKeys.getOrCreate(t.id())).isEqualTo(guestKey); // the same key: the moved hashes still match
		assertThat(ruleKeys.exists(g.id())).isFalse();
		list(t).andExpect(jsonPath("$.items[0].label").value("Mum"));
		getRules(t).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.rules.length()").value(2));
		putRules(t, 0).andExpect(status().isConflict());
	}

	@Test
	void guestMergeDropsTheGuestRulesWhenBothAccountsHaveADifferentKey() throws Exception {
		User g = guest();
		User t = user();
		UUID guestProfile = createProfile(g, "Guest profile");
		putRules(g, 0, ruleToProfile(hmac("g1"), guestProfile.toString()), ruleToLanguage(hmac("g2"), "en"))
				.andExpect(status().isOk());
		putRules(t, 0, ruleToLanguage(hmac("t1"), "am")).andExpect(status().isOk());
		String targetKey = ruleKeys.getOrCreate(t.id());
		assertThat(ruleKeys.getOrCreate(g.id())).isNotEqualTo(targetKey);
		transactions.executeWithoutResult(s -> merge.mergeGuest(g.id(), t.id(), Instant.now()));

		assertThat(ruleKeys.getOrCreate(t.id())).isEqualTo(targetKey);
		list(t).andExpect(jsonPath("$.items[0].label").value("Guest profile")); // profiles still move
		getRules(t).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.rules.length()").value(1))
				.andExpect(jsonPath("$.rules[0].appPackageHmac").value(hmac("t1")));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_app_destination_rules WHERE user_id = ?",
				Integer.class, g.id())).isZero();
	}
}
