package com.qalsmartkeyboard.identity.merge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.RSAKey;
import com.qalsmartkeyboard.identity.attestation.MockAttestationTokens;
import com.qalsmartkeyboard.identity.oauth.FakeGoogle;
import com.qalsmartkeyboard.notifications.OtpMessage;
import com.qalsmartkeyboard.notifications.OtpSender;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.CryptoShreddedException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/**
 * B2.6 end to end on PostgreSQL 18, through the real security chain, with every response validated against the
 * contract: a guest signing in with phone OTP or Google is upgraded in place (new identity) or merged into the existing
 * account, with its data in every per-user table moved (ciphertext re-encrypted), its allowance consumption carried
 * over, its keys shredded, its sessions and tokens dead, exactly once under concurrency. The per-user content tables have
 * no writers yet (B3.x/B5.7), so the test seeds them the way those modules will (row ids from the application,
 * ciphertext under {@code (table, column, row id)}).
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false",
		"qal.identity.oauth.google.client-ids=" + FakeGoogle.CLIENT_ID,
		"qal.identity.oauth.google.jwks-min-refresh-interval=100ms" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class GuestMergeIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final FakeGoogle GOOGLE = new FakeGoogle();
	static final RSAKey KEY = FakeGoogle.newKey("merge-kid-1");

	static {
		GOOGLE.publish(KEY);
	}

	@DynamicPropertySource
	static void fakeJwks(DynamicPropertyRegistry registry) {
		registry.add("qal.identity.oauth.google.jwks-uri", () -> GOOGLE.jwksUri().toString());
	}

	@AfterAll
	static void stop() {
		GOOGLE.close();
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	UserDataEncryption encryption;
	@MockitoSpyBean
	OtpSender sender;

	// ---------------------------------------------------------------- helpers

	record Session(UUID userId, String accessToken, String refreshToken, UUID deviceId) {
	}

	static String newIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
	}

	static String newPhone() {
		return "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
	}

	static String newSubject() {
		return "1" + String.format("%020d", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
	}

	static Session session(MvcResult result) throws Exception {
		String body = result.getResponse().getContentAsString();
		String device = body.contains("\"deviceId\"") ? JsonPath.read(body, "$.deviceId") : null;
		return new Session(UUID.fromString(JsonPath.read(body, "$.user.id")), JsonPath.read(body, "$.accessToken"),
				JsonPath.read(body, "$.refreshToken"), device == null ? null : UUID.fromString(device));
	}

	/** A guest created through {@code /auth/guest} with a (mock) valid Play Integrity verdict. */
	Session newGuest(UUID installation) throws Exception {
		String ip = newIp();
		String nonce = JsonPath.read(mvc.perform(post("/api/v1/auth/attestation-nonces").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		})).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.nonce");
		String body = """
				{"installationId":"%s","platform":"ANDROID",
				 "attestation":{"provider":"PLAY_INTEGRITY","token":"%s","nonce":"%s"}}
				""".formatted(installation, MockAttestationTokens.playIntegrity(nonce), nonce);
		return session(mvc.perform(post("/api/v1/auth/guest").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
				.andExpect(jsonPath("$.user.accountType").value("GUEST")).andReturn());
	}

	/** Starts an OTP challenge for the number. @return challenge id and the code that was "sent" */
	String[] otp(String phone) throws Exception {
		// leave the 60 s resend cooldown of earlier challenges behind (they stay valid: 5 min TTL)
		jdbc.update("UPDATE otp_challenges SET created_at = created_at - interval '61 seconds', "
				+ "expires_at = expires_at - interval '61 seconds' WHERE expires_at > now() + interval '2 minutes'");
		MvcResult r = mvc.perform(post("/api/v1/auth/otp/start").with(req -> {
			req.setRemoteAddr(newIp());
			return req;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"" + phone + "\"}"))
				.andExpect(status().isAccepted()).andReturn();
		ArgumentCaptor<OtpMessage> captor = ArgumentCaptor.forClass(OtpMessage.class);
		verify(sender, atLeastOnce()).send(captor.capture());
		List<OtpMessage> all = captor.getAllValues();
		for (int i = all.size() - 1; i >= 0; i--) {
			if (all.get(i).phoneE164().equals(phone)) {
				return new String[] { JsonPath.read(r.getResponse().getContentAsString(), "$.challengeId"),
						all.get(i).code() };
			}
		}
		throw new AssertionError("no code sent");
	}

	ResultActions verifyOtp(String[] challenge, Boolean ageConfirmed, UUID installation, String bearer)
			throws Exception {
		StringBuilder body = new StringBuilder(
				"{\"challengeId\":\"" + challenge[0] + "\",\"code\":\"" + challenge[1] + "\"");
		if (ageConfirmed != null) {
			body.append(",\"ageConfirmed\":").append(ageConfirmed);
		}
		if (installation != null) {
			body.append(",\"device\":{\"installationId\":\"").append(installation).append("\",\"platform\":\"ANDROID\"}");
		}
		var request = post("/api/v1/auth/otp/verify").with(r -> {
			r.setRemoteAddr(newIp());
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content(body.append('}').toString());
		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return mvc.perform(request);
	}

	ResultActions google(String subject, Boolean ageConfirmed, UUID installation, String bearer) throws Exception {
		String nonce = "n-" + UUID.randomUUID();
		StringBuilder body = new StringBuilder("{\"idToken\":\""
				+ FakeGoogle.sign(KEY, FakeGoogle.claims(subject, nonce).build()) + "\",\"nonce\":\"" + nonce + "\"");
		if (ageConfirmed != null) {
			body.append(",\"ageConfirmed\":").append(ageConfirmed);
		}
		if (installation != null) {
			body.append(",\"device\":{\"installationId\":\"").append(installation).append("\",\"platform\":\"ANDROID\"}");
		}
		var request = post("/api/v1/auth/oauth/GOOGLE").with(r -> {
			r.setRemoteAddr(newIp());
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content(body.append('}').toString());
		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return mvc.perform(request);
	}

	Session registeredByPhone(String phone, UUID installation) throws Exception {
		return session(verifyOtp(otp(phone), true, installation, null).andExpect(status().isOk()).andReturn());
	}

	ResultActions devices(String accessToken) throws Exception {
		return mvc.perform(get("/api/v1/devices").header("Authorization", "Bearer " + accessToken));
	}

	ResultActions refresh(String refreshToken) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh").with(r -> {
			r.setRemoteAddr(newIp());
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

	static void assertInvalidBearer(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("invalid_token")));
	}

	Map<String, Object> user(UUID id) {
		return jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	UUID language(String code) {
		return jdbc.queryForObject("SELECT id FROM supported_languages WHERE language_code = ?", UUID.class, code);
	}

	List<Map<String, Object>> mergeEvents(UUID guest) {
		return jdbc.queryForList("SELECT payload::text AS payload, aggregate_id FROM outbox_events "
				+ "WHERE event_type = 'identity.guest.merged' AND payload->>'guestUserId' = ?", guest.toString());
	}

	static String hmac(char c) {
		return String.valueOf(c).repeat(64);
	}

	EncryptedValue seal(UUID user, String table, String column, UUID row, String text) {
		return encryption.encryptString(user, EncryptionContext.of(table, column, row), text);
	}

	String open(UUID user, String table, String column, UUID row, byte[] ciphertext) {
		return encryption.decryptString(user, EncryptionContext.of(table, column, row), ciphertext);
	}

	byte[] bytes(String sql, Object... args) {
		return jdbc.queryForObject(sql, byte[].class, args);
	}

	// ---------------------------------------------------------------- upgrade in place

	@Test
	void guestSigningInWithANewPhoneIsUpgradedInPlaceAndKeepsEverything() throws Exception {
		UUID installation = UUID.randomUUID();
		Session guest = newGuest(installation);
		UUID historyId = UUID.randomUUID();
		EncryptedValue input = seal(guest.userId(), "user_history_items", "input_ciphertext", historyId, "selam");
		EncryptedValue output = seal(guest.userId(), "user_history_items", "output_ciphertext", historyId, "hello");
		jdbc.update("""
				INSERT INTO user_history_items (id, user_id, feature_code, input_ciphertext, output_ciphertext,
				                                key_version, expires_at)
				VALUES (?, ?, 'TRANSLATE', ?, ?, ?, now() + interval '30 days')
				""", historyId, guest.userId(), input.ciphertext(), output.ciphertext(), input.keyVersion());
		jdbc.update("""
				INSERT INTO usage_counters (subject_type, user_id, feature_code, period_start, period_end, used_units)
				VALUES ('USER', ?, 'TRANSLATE', now() - interval '1 hour', now() + interval '23 hours', 3)
				""", guest.userId());
		String phone = newPhone();
		String[] challenge = otp(phone);

		// a new account needs the age confirmation: refused, nothing written, the code stays usable
		verifyOtp(challenge, null, installation, guest.accessToken()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("ageConfirmed"));
		assertThat(user(guest.userId())).containsEntry("account_type", "GUEST").containsEntry("phone_e164", null);

		Session registered = session(verifyOtp(challenge, true, installation, guest.accessToken())
				.andExpect(status().isOk()).andExpect(jsonPath("$.mergedGuestUserId").value(guest.userId().toString()))
				.andExpect(jsonPath("$.isNewUser").value(true))
				.andExpect(jsonPath("$.user.accountType").value("REGISTERED"))
				.andExpect(jsonPath("$.user.phoneVerified").value(true)).andReturn());

		// same row, same id: nothing moved, nothing re-encrypted
		assertThat(registered.userId()).isEqualTo(guest.userId());
		assertThat(registered.deviceId()).isEqualTo(guest.deviceId());
		assertThat(user(guest.userId())).containsEntry("account_type", "REGISTERED").containsEntry("phone_e164", phone)
				.containsEntry("account_status", "ACTIVE").containsEntry("merged_into_user_id", null);
		assertThat(user(guest.userId()).get("age_confirmed_at")).isNotNull();
		assertThat(count("SELECT count(*) FROM user_auth_identities WHERE user_id = ? AND identity_provider = 'PHONE' "
				+ "AND provider_subject = ?", guest.userId(), phone)).isOne();
		assertThat(jdbc.queryForObject("SELECT attestation_status FROM user_devices WHERE id = ?", String.class,
				guest.deviceId())).isEqualTo("VALID");
		assertThat(open(guest.userId(), "user_history_items", "input_ciphertext", historyId,
				bytes("SELECT input_ciphertext FROM user_history_items WHERE id = ?", historyId))).isEqualTo("selam");
		assertThat(count("SELECT used_units FROM usage_counters WHERE user_id = ? AND feature_code = 'TRANSLATE'",
				guest.userId())).isEqualTo(3);
		assertThat(encryption.isShredded(guest.userId())).isFalse();

		// the guest's own session ended; its tokens are dead, the new ones work
		assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = ? AND revoked_reason = 'ACCOUNT_MERGED'",
				guest.userId())).isOne();
		assertInvalidBearer(devices(guest.accessToken()));
		refresh(guest.refreshToken()).andExpect(status().isUnauthorized());
		devices(registered.accessToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(guest.deviceId().toString()));
		refresh(registered.refreshToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.user.accountType").value("REGISTERED"));

		List<Map<String, Object>> events = mergeEvents(guest.userId());
		assertThat(events).hasSize(1);
		assertThat((String) events.get(0).get("payload")).contains("\"mode\": \"UPGRADED\"")
				.contains("\"method\": \"PHONE\"");
	}

	@Test
	void guestSigningInWithANewGoogleIdentityIsUpgradedInPlace() throws Exception {
		UUID installation = UUID.randomUUID();
		Session guest = newGuest(installation);
		String subject = newSubject();
		Session registered = session(google(subject, true, installation, guest.accessToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.mergedGuestUserId").value(guest.userId().toString()))
				.andExpect(jsonPath("$.isNewUser").value(true)).andExpect(jsonPath("$.user.displayName").value("Test User"))
				.andReturn());
		assertThat(registered.userId()).isEqualTo(guest.userId());
		assertThat(user(guest.userId())).containsEntry("account_type", "REGISTERED").containsEntry("display_name",
				"Test User");
		assertThat(jdbc.queryForObject("SELECT user_id FROM user_auth_identities WHERE identity_provider = 'GOOGLE' "
				+ "AND provider_subject = ?", UUID.class, subject)).isEqualTo(guest.userId());
		assertInvalidBearer(devices(guest.accessToken()));
		assertThat((String) mergeEvents(guest.userId()).get(0).get("payload")).contains("\"method\": \"GOOGLE\"");
	}

	// ---------------------------------------------------------------- merge into an existing account

	@Test
	void guestSigningInToAnExistingPhoneAccountHasEveryTableMergedReEncryptedAndShredded() throws Exception {
		UUID installationA = UUID.randomUUID();
		UUID installationB = UUID.randomUUID();
		String phone = newPhone();
		Session target = registeredByPhone(phone, installationB);
		Session guest = newGuest(installationA);
		UUID g = guest.userId();
		UUID t = target.userId();
		UUID am = language("am");
		UUID ti = language("ti");
		UUID en = language("en");

		// devices: the guest also used installation B, which the target already has (target wins, newer verdict kept)
		UUID guestDeviceB = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO user_devices (id, user_id, installation_id, platform, attestation_provider,
				                          attestation_status, attestation_checked_at)
				VALUES (?, ?, ?, 'ANDROID', 'PLAY_INTEGRITY', 'VALID', now())
				""", guestDeviceB, g, installationB);
		// preferences: the target has none, so the guest's move
		jdbc.update("INSERT INTO user_preferences (user_id, calendar_system) VALUES (?, 'ETHIOPIAN')", g);
		// typing languages: the target's list wins
		jdbc.update("INSERT INTO user_typing_languages (id, user_id, language_id, is_primary) VALUES (?, ?, ?, TRUE)",
				UUID.randomUUID(), t, am);
		jdbc.update("INSERT INTO user_typing_languages (id, user_id, language_id, is_primary) VALUES (?, ?, ?, TRUE)",
				UUID.randomUUID(), g, en);
		// style profiles: am conflicts (target wins), ti moves with its encrypted sign-off
		jdbc.update("INSERT INTO user_style_profiles (id, user_id, language_id, formality) VALUES (?, ?, ?, 'FORMAL')",
				UUID.randomUUID(), t, am);
		jdbc.update("INSERT INTO user_style_profiles (id, user_id, language_id, formality) VALUES (?, ?, ?, 'INFORMAL')",
				UUID.randomUUID(), g, am);
		UUID styleTi = UUID.randomUUID();
		EncryptedValue signoff = seal(g, "user_style_profiles", "signoff_ciphertext", styleTi, "ምስጋና");
		jdbc.update("""
				INSERT INTO user_style_profiles (id, user_id, language_id, signoff_ciphertext, key_version)
				VALUES (?, ?, ?, ?, ?)
				""", styleTi, g, ti, signoff.ciphertext(), signoff.keyVersion());
		// destination profiles and app rules: the target has a default profile and a rule for app "b"
		UUID targetProfile = UUID.randomUUID();
		EncryptedValue work = seal(t, "user_destination_profiles", "label_ciphertext", targetProfile, "Work");
		jdbc.update("""
				INSERT INTO user_destination_profiles (id, user_id, label_ciphertext, key_version,
				                                       destination_language_id, is_default)
				VALUES (?, ?, ?, ?, ?, TRUE)
				""", targetProfile, t, work.ciphertext(), work.keyVersion(), en);
		jdbc.update("""
				INSERT INTO user_app_destination_rules (id, user_id, app_package_hmac, destination_language_id)
				VALUES (?, ?, ?, ?)
				""", UUID.randomUUID(), t, hmac('b'), en);
		UUID guestProfile = UUID.randomUUID();
		EncryptedValue mum = seal(g, "user_destination_profiles", "label_ciphertext", guestProfile, "Mum");
		jdbc.update("""
				INSERT INTO user_destination_profiles (id, user_id, label_ciphertext, key_version,
				                                       destination_language_id, formality, is_default)
				VALUES (?, ?, ?, ?, ?, 'HONORIFIC', TRUE)
				""", guestProfile, g, mum.ciphertext(), mum.keyVersion(), am);
		UUID ruleToProfile = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO user_app_destination_rules (id, user_id, app_package_hmac, destination_profile_id)
				VALUES (?, ?, ?, ?)
				""", ruleToProfile, g, hmac('a'), guestProfile);
		jdbc.update("""
				INSERT INTO user_app_destination_rules (id, user_id, app_package_hmac, destination_profile_id)
				VALUES (?, ?, ?, ?)
				""", UUID.randomUUID(), g, hmac('b'), guestProfile);
		UUID ruleToLanguage = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO user_app_destination_rules (id, user_id, app_package_hmac, destination_language_id)
				VALUES (?, ?, ?, ?)
				""", ruleToLanguage, g, hmac('c'), ti);
		// dictionary: "selam" moves; a word the target already has (same HMAC) stays the target's
		jdbc.update("""
				INSERT INTO user_dictionary_entries (id, user_id, language_id, word_ciphertext, word_hmac, key_version,
				                                     sync_version)
				VALUES (?, ?, ?, ?, ?, ?, 7)
				""", UUID.randomUUID(), t, am, seal(t, "x", "y", UUID.randomUUID(), "x").ciphertext(), hmac('2'), 1);
		UUID word = UUID.randomUUID();
		EncryptedValue selam = seal(g, "user_dictionary_entries", "word_ciphertext", word, "ሰላም");
		jdbc.update("""
				INSERT INTO user_dictionary_entries (id, user_id, language_id, word_ciphertext, word_hmac, key_version)
				VALUES (?, ?, ?, ?, ?, ?)
				""", word, g, am, selam.ciphertext(), hmac('1'), selam.keyVersion());
		jdbc.update("""
				INSERT INTO user_dictionary_entries (id, user_id, language_id, word_ciphertext, word_hmac, key_version)
				VALUES (?, ?, ?, ?, ?, ?)
				""", UUID.randomUUID(), g, am, seal(g, "x", "y", UUID.randomUUID(), "x").ciphertext(), hmac('2'), 1);
		// history
		UUID historyId = UUID.randomUUID();
		EncryptedValue input = seal(g, "user_history_items", "input_ciphertext", historyId, "እንደምን ነህ");
		EncryptedValue output = seal(g, "user_history_items", "output_ciphertext", historyId, "How are you");
		jdbc.update("""
				INSERT INTO user_history_items (id, user_id, feature_code, input_ciphertext, output_ciphertext,
				                                key_version, expires_at)
				VALUES (?, ?, 'TRANSLATE', ?, ?, ?, now() + interval '30 days')
				""", historyId, g, input.ciphertext(), output.ciphertext(), input.keyVersion());
		// consents: TERMS only on the guest (copied); ANALYTICS granted by the target, later revoked by the guest
		// (the revocation is appended); MARKETING revoked by the target, granted by the guest (the target's stands)
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		consent(g, "TERMS_ACCEPTANCE", true, now.minusSeconds(300));
		consent(t, "ANALYTICS", true, now.minusSeconds(600));
		consent(g, "ANALYTICS", false, now.minusSeconds(60));
		consent(t, "MARKETING", false, now.minusSeconds(600));
		consent(g, "MARKETING", true, now.minusSeconds(60));
		// usage: TRANSLATE today 3 (guest) + 2 (target); GRAMMAR 1 used + 1 reserved on the guest only. The target's
		// counter is its real FREE DAILY window (its time zone, Africa/Addis_Ababa); the guest's window differs from it
		// (B4.2: the carry-over lands in the window the metering engine computes for the target)
		jdbc.update("""
				INSERT INTO usage_counters (subject_type, user_id, feature_code, period_start, period_end, used_units)
				VALUES ('USER', ?, 'TRANSLATE', now() - interval '1 hour', now() + interval '23 hours', 3),
				       ('USER', ?, 'TRANSLATE',
				        date_trunc('day', now() AT TIME ZONE 'Africa/Addis_Ababa') AT TIME ZONE 'Africa/Addis_Ababa',
				        (date_trunc('day', now() AT TIME ZONE 'Africa/Addis_Ababa') + interval '1 day')
				            AT TIME ZONE 'Africa/Addis_Ababa', 2)
				""", g, t);
		jdbc.update("""
				INSERT INTO usage_counters (subject_type, user_id, feature_code, period_start, period_end, used_units,
				                            reserved_units)
				VALUES ('USER', ?, 'GRAMMAR_CORRECTION', now() - interval '1 hour', now() + interval '23 hours', 1, 1),
				       ('USER', ?, 'TRANSLATE', now() - interval '2 days', now() - interval '1 day', 3, 0)
				""", g, g);
		// glossary, term suggestion, an unspent attestation nonce of the guest
		jdbc.update("""
				INSERT INTO glossaries (id, user_id, name, source_language_id, target_language_id)
				VALUES (?, ?, 'names', ?, ?)
				""", UUID.randomUUID(), g, am, en);
		jdbc.update("""
				INSERT INTO term_suggestions (language_id, surface_form, normalized_form, submitted_by_user_id)
				VALUES (?, 'ቡና', 'ቡና', ?)
				""", am, g);
		jdbc.update("""
				INSERT INTO attestation_nonces (id, nonce_hash, user_id, expires_at)
				VALUES (?, ?, ?, now() + interval '5 minutes')
				""", UUID.randomUUID(), hmac('9'), g);

		// ------------------------------------------------------------ sign in with the guest's token
		String[] challenge = otp(phone);
		Session merged;
		try (CapturedLogs logs = new CapturedLogs()) {
			merged = session(verifyOtp(challenge, null, installationA, guest.accessToken()).andExpect(status().isOk())
					.andExpect(jsonPath("$.mergedGuestUserId").value(g.toString()))
					.andExpect(jsonPath("$.isNewUser").value(false))
					.andExpect(jsonPath("$.user.id").value(t.toString())).andReturn());
			// ids only: no number, token or decrypted content reaches the logs (PhoneOtpIT covers the code)
			List<String> secrets = List.of(phone.substring(4), guest.accessToken(), guest.refreshToken(),
					merged.accessToken(), merged.refreshToken(), "Mum", "ሰላም", "How are you", "ምስጋና");
			assertThat(logs.texts()).isNotEmpty()
					.noneMatch(text -> secrets.stream().anyMatch(secret -> text.contains(secret)));
		}

		// users: the guest points at the target, keys shredded
		assertThat(user(g)).containsEntry("account_type", "GUEST").containsEntry("account_status", "MERGED")
				.containsEntry("merged_into_user_id", t);
		assertThat(user(t)).containsEntry("account_status", "ACTIVE").containsEntry("account_type", "REGISTERED");
		assertThat(encryption.isShredded(g)).isTrue();
		assertThat(count("SELECT count(*) FROM user_data_keys WHERE user_id = ? AND key_status <> 'DESTROYED'", g))
				.isZero();

		// devices: A moved with its verdict and carries the new session; B stays the target's, with the newer verdict
		assertThat(merged.deviceId()).isEqualTo(guest.deviceId());
		assertThat(jdbc.queryForMap("SELECT user_id, attestation_status FROM user_devices WHERE id = ?",
				guest.deviceId())).containsEntry("user_id", t).containsEntry("attestation_status", "VALID");
		assertThat(jdbc.queryForMap("SELECT user_id, device_status FROM user_devices WHERE id = ?", guestDeviceB))
				.containsEntry("user_id", g).containsEntry("device_status", "REVOKED");
		assertThat(jdbc.queryForObject("SELECT attestation_status FROM user_devices WHERE user_id = ? AND "
				+ "installation_id = ?", String.class, t, installationB)).isEqualTo("VALID");

		// sessions: every guest family ended; the guest's tokens are dead
		assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = ? AND session_status = 'ACTIVE'", g))
				.isZero();
		assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = ? AND revoked_reason = 'ACCOUNT_MERGED'",
				g)).isOne();
		assertInvalidBearer(devices(guest.accessToken()));
		refresh(guest.refreshToken()).andExpect(status().isUnauthorized());
		devices(merged.accessToken()).andExpect(status().isOk());
		refresh(target.refreshToken()).andExpect(status().isOk()); // the target's earlier session is untouched

		// preferences and typing languages
		assertThat(jdbc.queryForObject("SELECT calendar_system FROM user_preferences WHERE user_id = ?", String.class,
				t)).isEqualTo("ETHIOPIAN");
		assertThat(jdbc.queryForList("SELECT language_id FROM user_typing_languages WHERE user_id = ?", UUID.class, t))
				.containsExactly(am);
		// style: am keeps the target's, ti moved and re-encrypted
		assertThat(jdbc.queryForObject("SELECT formality FROM user_style_profiles WHERE user_id = ? AND language_id = ?",
				String.class, t, am)).isEqualTo("FORMAL");
		byte[] movedSignoff = bytes("SELECT signoff_ciphertext FROM user_style_profiles WHERE id = ? AND user_id = ?",
				styleTi, t);
		assertThat(open(t, "user_style_profiles", "signoff_ciphertext", styleTi, movedSignoff)).isEqualTo("ምስጋና");
		assertThat(movedSignoff).isNotEqualTo(signoff.ciphertext());
		// destination profile: moved, re-encrypted, no longer the default (the target has one); its rules followed it
		Map<String, Object> profile = jdbc.queryForMap(
				"SELECT user_id, is_default, formality, label_ciphertext FROM user_destination_profiles WHERE id = ?",
				guestProfile);
		assertThat(profile).containsEntry("user_id", t).containsEntry("is_default", false)
				.containsEntry("formality", "HONORIFIC");
		assertThat(open(t, "user_destination_profiles", "label_ciphertext", guestProfile,
				(byte[]) profile.get("label_ciphertext"))).isEqualTo("Mum");
		assertThat(jdbc.queryForObject("SELECT destination_profile_id FROM user_app_destination_rules WHERE id = ? "
				+ "AND user_id = ?", UUID.class, ruleToProfile, t)).isEqualTo(guestProfile);
		assertThat(jdbc.queryForObject("SELECT user_id FROM user_app_destination_rules WHERE id = ?", UUID.class,
				ruleToLanguage)).isEqualTo(t);
		assertThat(jdbc.queryForObject("SELECT destination_language_id FROM user_app_destination_rules WHERE "
				+ "user_id = ? AND app_package_hmac = ?", UUID.class, t, hmac('b'))).isEqualTo(en);
		// dictionary: the word moved after the target's sync versions; the duplicate stayed the target's
		Map<String, Object> entry = jdbc.queryForMap(
				"SELECT user_id, sync_version, word_ciphertext FROM user_dictionary_entries WHERE id = ?", word);
		assertThat(entry).containsEntry("user_id", t).containsEntry("sync_version", 8L);
		assertThat(open(t, "user_dictionary_entries", "word_ciphertext", word, (byte[]) entry.get("word_ciphertext")))
				.isEqualTo("ሰላም");
		assertThat(count("SELECT count(*) FROM user_dictionary_entries WHERE user_id = ? AND word_hmac = ?", t,
				hmac('2'))).isOne();
		// history: moved and re-encrypted
		Map<String, Object> history = jdbc.queryForMap(
				"SELECT user_id, input_ciphertext, output_ciphertext FROM user_history_items WHERE id = ?", historyId);
		assertThat(history).containsEntry("user_id", t);
		assertThat(open(t, "user_history_items", "input_ciphertext", historyId, (byte[]) history.get("input_ciphertext")))
				.isEqualTo("እንደምን ነህ");
		assertThat(open(t, "user_history_items", "output_ciphertext", historyId,
				(byte[]) history.get("output_ciphertext"))).isEqualTo("How are you");
		// nothing personal is left on the guest (consents are an append-only record and stay)
		for (String table : List.of("user_preferences", "user_typing_languages", "user_style_profiles",
				"user_destination_profiles", "user_app_destination_rules", "user_dictionary_entries",
				"user_history_items", "glossaries")) {
			assertThat(count("SELECT count(*) FROM " + table + " WHERE user_id = ?", g)).as(table).isZero();
		}
		// consents: latest decision per code of the target
		assertThat(latestConsent(t, "TERMS_ACCEPTANCE")).isTrue();
		assertThat(latestConsent(t, "ANALYTICS")).isFalse();
		assertThat(latestConsent(t, "MARKETING")).isFalse();
		assertThat(count("SELECT count(*) FROM user_consents WHERE user_id = ? "
				+ "AND metadata ->> 'mergedFromGuestUserId' = ?", t, g.toString())).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM user_consents WHERE user_id = ?", g)).isEqualTo(3);
		// usage: the guest's current consumption counts against the target (no reset), past windows do not
		assertThat(count("SELECT used_units FROM usage_counters WHERE user_id = ? AND feature_code = 'TRANSLATE' "
				+ "AND period_end > now()", t)).isEqualTo(5);
		assertThat(count("SELECT used_units FROM usage_counters WHERE user_id = ? AND feature_code = "
				+ "'GRAMMAR_CORRECTION'", t)).isEqualTo(2);
		// glossary, suggestion, nonce
		assertThat(count("SELECT count(*) FROM glossaries WHERE user_id = ?", t)).isOne();
		assertThat(count("SELECT count(*) FROM term_suggestions WHERE submitted_by_user_id = ?", t)).isOne();
		assertThat(count("SELECT count(*) FROM attestation_nonces WHERE user_id = ? AND consumed_at IS NULL", g))
				.isZero();

		// the guest's data is unreadable now: its keys are gone, and its old ciphertext never opened as the target
		assertThatThrownBy(() -> open(g, "user_history_items", "input_ciphertext", historyId, input.ciphertext()))
				.isInstanceOf(CryptoShreddedException.class);
		assertThatThrownBy(() -> open(g, "user_destination_profiles", "label_ciphertext", guestProfile,
				mum.ciphertext())).isInstanceOf(CryptoShreddedException.class);
		assertThatThrownBy(() -> open(t, "user_history_items", "input_ciphertext", historyId, input.ciphertext()))
				.isInstanceOfAny(CiphertextRejectedException.class);
		assertThatThrownBy(() -> encryption.encryptString(g, EncryptionContext.of("t", "c", "r"), "x"))
				.isInstanceOf(CryptoShreddedException.class);

		List<Map<String, Object>> events = mergeEvents(g);
		assertThat(events).hasSize(1);
		assertThat(events.get(0)).containsEntry("aggregate_id", t.toString());
		assertThat((String) events.get(0).get("payload")).contains("\"mode\": \"MERGED\"")
				.contains("\"targetUserId\": \"" + t + "\"");
	}

	void consent(UUID user, String code, boolean granted, Instant at) {
		jdbc.update("""
				INSERT INTO user_consents (user_id, consent_code, consent_version, is_granted, recorded_at, source_platform)
				VALUES (?, ?, '1', ?, ?, 'ANDROID')
				""", user, code, granted, Timestamp.from(at));
	}

	Boolean latestConsent(UUID user, String code) {
		return jdbc.queryForObject("""
				SELECT is_granted FROM user_consents WHERE user_id = ? AND consent_code = ?
				ORDER BY recorded_at DESC, id DESC LIMIT 1
				""", Boolean.class, user, code);
	}

	@Test
	void guestSigningInToAnExistingGoogleAccountIsMerged() throws Exception {
		String subject = newSubject();
		Session target = session(google(subject, true, null, null).andExpect(status().isOk()).andReturn());
		UUID installation = UUID.randomUUID();
		Session guest = newGuest(installation);
		Session merged = session(google(subject, null, installation, guest.accessToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.mergedGuestUserId").value(guest.userId().toString()))
				.andExpect(jsonPath("$.isNewUser").value(false)).andReturn());
		assertThat(merged.userId()).isEqualTo(target.userId());
		assertThat(merged.deviceId()).isEqualTo(guest.deviceId());
		assertThat(user(guest.userId())).containsEntry("account_status", "MERGED")
				.containsEntry("merged_into_user_id", target.userId());
		assertThat((String) mergeEvents(guest.userId()).get(0).get("payload")).contains("\"method\": \"GOOGLE\"");
	}

	// ---------------------------------------------------------------- races, replays, wrong tokens

	@Test
	void concurrentSignInsWithOneGuestTokenMergeItExactlyOnce() throws Exception {
		String subject = newSubject();
		Session target = session(google(subject, true, null, null).andExpect(status().isOk()).andReturn());
		Session guest = newGuest(UUID.randomUUID());
		int threads = 6;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<MvcResult>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<MvcResult> call = () -> {
					start.await();
					return google(subject, null, null, guest.accessToken()).andReturn();
				};
				results.add(pool.submit(call));
			}
			start.countDown();
			int ok = 0;
			int refused = 0;
			for (Future<MvcResult> f : results) {
				MvcResult r = f.get(60, TimeUnit.SECONDS);
				if (r.getResponse().getStatus() == 200) {
					ok++;
					assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.mergedGuestUserId"))
							.isEqualTo(guest.userId().toString());
				}
				else {
					assertThat(r.getResponse().getStatus()).isEqualTo(401);
					assertThat(r.getResponse().getContentAsString()).contains("UNAUTHENTICATED");
					refused++;
				}
			}
			assertThat(ok).isOne();
			assertThat(refused).isEqualTo(threads - 1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(user(guest.userId())).containsEntry("account_status", "MERGED")
				.containsEntry("merged_into_user_id", target.userId());
		assertThat(mergeEvents(guest.userId())).hasSize(1);
		assertThat(count("SELECT count(*) FROM user_data_keys WHERE user_id = ? AND key_status <> 'DESTROYED'",
				guest.userId())).isZero();
	}

	@Test
	void theOldGuestTokenCannotMergeAgainAndTheCodeSurvivesForARetryWithoutIt() throws Exception {
		String phone = newPhone();
		Session target = registeredByPhone(phone, null);
		Session guest = newGuest(UUID.randomUUID());
		verifyOtp(otp(phone), null, null, guest.accessToken()).andExpect(status().isOk());

		String[] challenge = otp(phone);
		assertInvalidBearer(verifyOtp(challenge, null, null, guest.accessToken()));
		assertInvalidBearer(google(newSubject(), true, null, guest.accessToken()));
		// the refused attempt consumed nothing: the same code signs in without the stale bearer, and links nothing
		verifyOtp(challenge, null, null, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.user.id").value(target.userId().toString()))
				.andExpect(jsonPath("$.mergedGuestUserId").doesNotExist());
		assertThat(mergeEvents(guest.userId())).hasSize(1);
	}

	@Test
	void aRegisteredTokenInTheGuestPositionLinksNothing() throws Exception {
		Session other = registeredByPhone(newPhone(), null);
		String phone = newPhone();
		Session target = registeredByPhone(phone, null);
		verifyOtp(otp(phone), null, null, other.accessToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.user.id").value(target.userId().toString()))
				.andExpect(jsonPath("$.mergedGuestUserId").doesNotExist());
		google(newSubject(), true, null, other.accessToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.mergedGuestUserId").doesNotExist());
		assertThat(user(other.userId())).containsEntry("account_status", "ACTIVE").containsEntry("merged_into_user_id",
				null);
		refresh(other.refreshToken()).andExpect(status().isOk()); // its session is untouched
		assertThat(mergeEvents(other.userId())).isEmpty();
	}

	@Test
	void aGuestWhoseSessionEndedCannotHandItsDataToAnybody() throws Exception {
		String phone = newPhone();
		registeredByPhone(phone, null);
		Session guest = newGuest(UUID.randomUUID());
		mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + guest.accessToken()))
				.andExpect(status().isNoContent());
		// the access token itself is still within its lifetime, but its session is gone
		assertInvalidBearer(verifyOtp(otp(phone), null, null, guest.accessToken()));
		assertThat(user(guest.userId())).containsEntry("account_type", "GUEST").containsEntry("account_status", "ACTIVE");
		assertThat(mergeEvents(guest.userId())).isEmpty();
	}
}
