package com.qalsmartkeyboard.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.qalsmartkeyboard.identity.UserDeletionParticipant;
import com.qalsmartkeyboard.identity.attestation.MockAttestationTokens;
import com.qalsmartkeyboard.identity.oauth.FakeGoogle;
import com.qalsmartkeyboard.notifications.OtpMessage;
import com.qalsmartkeyboard.notifications.OtpSender;
import com.qalsmartkeyboard.platform.crypto.CryptoShreddedException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;
import com.qalsmartkeyboard.usage.CreditSource;
import com.qalsmartkeyboard.usage.Credits;
import com.qalsmartkeyboard.usage.Metering;

/**
 * B6.2 done-when on PostgreSQL 18, through the real security chain, every response validated against the contract: a
 * user with data in every encrypted column and every per-user table requests deletion; after the grace period the
 * executor crypto-shreds, purges and anonymises, and then every ciphertext the user had fails with
 * {@link CryptoShreddedException}, the data keys are DESTROYED with NULL wrapped keys, the purged tables hold nothing of
 * the user, the append-only tables hold only rows attached to the anonymised id, sessions and tokens are dead and the
 * same phone number or Google account signs in to a <b>new</b> account. Also: cancel during the grace period, resume
 * after a crash between steps and after a failing step, executors racing, guests, validation and the V11 guards. The
 * fixtures follow {@code GuestMergeIT} (sign-in helpers; tables without writers seeded the way their modules will).
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false",
		"qal.identity.oauth.google.client-ids=" + FakeGoogle.CLIENT_ID,
		"qal.identity.oauth.google.jwks-min-refresh-interval=100ms" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class DeletionIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final FakeGoogle GOOGLE = new FakeGoogle();
	static final RSAKey KEY = FakeGoogle.newKey("deletion-kid-1");
	static final Duration GRACE = Duration.ofDays(14);

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

	/** A participant that fails the purge of one chosen user (a crash inside step 2). */
	@TestConfiguration
	static class FailingParticipantConfiguration {

		static volatile UUID failFor;

		@Bean
		UserDeletionParticipant failingParticipant() {
			return new UserDeletionParticipant() {
				@Override
				public int deletionOrder() {
					return IDENTITY + 1; // after every real participant, so their deletes are rolled back too
				}

				@Override
				public int purgeUser(UUID userId, Instant now) {
					if (userId.equals(failFor)) {
						throw new IllegalStateException("simulated crash during the purge");
					}
					return 0;
				}
			};
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	UserDataEncryption encryption;
	@Autowired
	DeletionExecutor executor;
	@Autowired
	Metering metering;
	@Autowired
	Credits credits;
	@MockitoSpyBean
	OtpSender sender;

	@AfterEach
	void noFailure() {
		FailingParticipantConfiguration.failFor = null;
	}

	// ---------------------------------------------------------------- helpers (as in GuestMergeIT)

	record Session(UUID userId, String accessToken, String refreshToken, UUID deviceId) {
	}

	/** A value sealed for the user and where it is stored. */
	record Sealed(String table, String column, UUID row, byte[] ciphertext) {
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

	String[] otp(String phone) throws Exception {
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

	ResultActions verifyOtp(String phone, UUID installation, String bearer) throws Exception {
		String[] challenge = otp(phone);
		StringBuilder body = new StringBuilder(
				"{\"challengeId\":\"" + challenge[0] + "\",\"code\":\"" + challenge[1] + "\",\"ageConfirmed\":true");
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

	ResultActions google(String subject, String bearer) throws Exception {
		String nonce = "n-" + UUID.randomUUID();
		var request = post("/api/v1/auth/oauth/GOOGLE").with(r -> {
			r.setRemoteAddr(newIp());
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\""
				+ FakeGoogle.sign(KEY, FakeGoogle.claims(subject, nonce).build()) + "\",\"nonce\":\"" + nonce
				+ "\",\"ageConfirmed\":true}");
		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return mvc.perform(request);
	}

	Session registeredByPhone(String phone, UUID installation) throws Exception {
		return session(verifyOtp(phone, installation, null).andExpect(status().isOk()).andReturn());
	}

	Session newGuest() throws Exception {
		String ip = newIp();
		String nonce = JsonPath.read(mvc.perform(post("/api/v1/auth/attestation-nonces").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		})).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.nonce");
		String body = """
				{"installationId":"%s","platform":"ANDROID",
				 "attestation":{"provider":"PLAY_INTEGRITY","token":"%s","nonce":"%s"}}
				""".formatted(UUID.randomUUID(), MockAttestationTokens.playIntegrity(nonce), nonce);
		return session(mvc.perform(post("/api/v1/auth/guest").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn());
	}

	ResultActions requestDeletion(String accessToken) throws Exception {
		return mvc.perform(post("/api/v1/me/deletion-request").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true,\"reason\":\"moving on\"}"));
	}

	ResultActions currentDeletion(String accessToken) throws Exception {
		return mvc.perform(get("/api/v1/me/deletion-request").header("Authorization", "Bearer " + accessToken));
	}

	ResultActions cancelDeletion(String accessToken) throws Exception {
		return mvc.perform(delete("/api/v1/me/deletion-request").header("Authorization", "Bearer " + accessToken));
	}

	ResultActions devices(String accessToken) throws Exception {
		return mvc.perform(get("/api/v1/devices").header("Authorization", "Bearer " + accessToken));
	}

	ResultActions entitlementToken(String accessToken) throws Exception {
		return mvc.perform(get("/api/v1/me/entitlements/token").header("Authorization", "Bearer " + accessToken));
	}

	ResultActions refresh(String refreshToken) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh").with(r -> {
			r.setRemoteAddr(newIp());
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

	UUID requestId(String accessToken) throws Exception {
		return UUID.fromString(JsonPath.read(requestDeletion(accessToken).andExpect(status().isAccepted()).andReturn()
				.getResponse().getContentAsString(), "$.id"));
	}

	Map<String, Object> user(UUID id) {
		return jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);
	}

	Map<String, Object> request(UUID id) {
		return jdbc.queryForMap("SELECT * FROM user_deletion_requests WHERE id = ?", id);
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	UUID language(String code) {
		return jdbc.queryForObject("SELECT id FROM supported_languages WHERE language_code = ?", UUID.class, code);
	}

	int deletedEvents(UUID user) {
		return count("SELECT count(*) FROM outbox_events WHERE event_type = 'privacy.account.deleted' "
				+ "AND aggregate_id = ?", user.toString());
	}

	static String hex(char c) {
		return String.valueOf(c).repeat(64);
	}

	Sealed seal(UUID user, String table, String column, UUID row, String text) {
		EncryptedValue v = encryption.encryptString(user, EncryptionContext.of(table, column, row), text);
		return new Sealed(table, column, row, v.ciphertext());
	}

	String open(UUID user, Sealed s) {
		return encryption.decryptString(user, EncryptionContext.of(s.table(), s.column(), s.row()), s.ciphertext());
	}

	/** Tables purged for a deleted user (all by {@code user_id}). */
	static final List<String> PURGED = List.of("user_auth_identities", "user_sessions", "user_password_credentials",
			"password_reset_tokens", "attestation_nonces", "user_devices", "user_preferences", "user_typing_languages",
			"user_typing_language_revisions", "user_style_profiles", "user_destination_profiles",
			"user_app_destination_rules", "user_app_rule_revisions", "user_app_rule_keys", "user_dictionary_entries",
			"user_dictionary_sync_state", "user_dictionary_applied_changes", "user_history_items", "data_export_requests",
			"glossaries", "usage_counters", "usage_reservations",
			"user_feature_overrides", "ai_feedback", "organization_members", "user_admin_roles", "voice_jobs");

	/**
	 * Data in every per-user table and every encrypted column: through the API where a writer exists (consents, the
	 * app-rule key, metering, credits), directly otherwise.
	 *
	 * @return every value sealed for the user
	 */
	List<Sealed> seedEverything(Session s) throws Exception {
		UUID u = s.userId();
		UUID am = language("am");
		UUID en = language("en");
		List<Sealed> sealed = new ArrayList<>();
		// consents (append-only, through the API)
		for (String code : List.of("TERMS_ACCEPTANCE", "ANALYTICS", "DICTIONARY_SYNC")) {
			mvc.perform(put("/api/v1/me/consents/" + code).header("Authorization", "Bearer " + s.accessToken())
					.contentType(MediaType.APPLICATION_JSON).content("{\"consentVersion\":\"1\",\"isGranted\":true}"))
					.andExpect(status().isOk());
		}
		// the app-rule HMAC key (encrypted under the user's key, ADR 0012), through the API
		mvc.perform(get("/api/v1/me/app-destination-rules/key").header("Authorization", "Bearer " + s.accessToken()))
				.andExpect(status().isOk());
		sealed.add(new Sealed("user_app_rule_keys", "key_ciphertext", u,
				jdbc.queryForObject("SELECT key_ciphertext FROM user_app_rule_keys WHERE user_id = ?", byte[].class, u)));
		// preferences
		jdbc.update("INSERT INTO user_preferences (user_id, calendar_system) VALUES (?, 'ETHIOPIAN')", u);
		jdbc.update("INSERT INTO user_typing_languages (id, user_id, language_id, is_primary) VALUES (?, ?, ?, TRUE)",
				UUID.randomUUID(), u, am);
		jdbc.update("INSERT INTO user_typing_language_revisions (user_id, revision) VALUES (?, 3)", u);
		UUID style = UUID.randomUUID();
		Sealed signoff = seal(u, "user_style_profiles", "signoff_ciphertext", style, "ምስጋና");
		Sealed phrases = seal(u, "user_style_profiles", "preferred_phrases_ciphertext", style, "እንደምን ነህ");
		jdbc.update("""
				INSERT INTO user_style_profiles (id, user_id, language_id, signoff_ciphertext, preferred_phrases_ciphertext,
				                                 key_version)
				VALUES (?, ?, ?, ?, ?, 1)
				""", style, u, am, signoff.ciphertext(), phrases.ciphertext());
		sealed.add(signoff);
		sealed.add(phrases);
		UUID profile = UUID.randomUUID();
		Sealed label = seal(u, "user_destination_profiles", "label_ciphertext", profile, "Mum");
		jdbc.update("""
				INSERT INTO user_destination_profiles (id, user_id, label_ciphertext, key_version, destination_language_id,
				                                       is_default)
				VALUES (?, ?, ?, 1, ?, TRUE)
				""", profile, u, label.ciphertext(), am);
		sealed.add(label);
		jdbc.update("""
				INSERT INTO user_app_destination_rules (id, user_id, app_package_hmac, destination_profile_id)
				VALUES (?, ?, ?, ?)
				""", UUID.randomUUID(), u, hex('a'), profile);
		jdbc.update("INSERT INTO user_app_rule_revisions (user_id, revision) VALUES (?, 1)", u);
		// dictionary and history
		// the synced dictionary through the API: entry, applied-change id and the per-user HMAC key row (B3.6)
		mvc.perform(post("/api/v1/me/dictionary/changes").header("Authorization", "Bearer " + s.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("{\"changes\":[{\"op\":\"ADD\",\"languageCode\":\"am\","
						+ "\"word\":\"ሰላም\",\"clientChangeId\":\"" + UUID.randomUUID() + "\"}]}"))
				.andExpect(status().isOk());
		UUID word = jdbc.queryForObject("SELECT id FROM user_dictionary_entries WHERE user_id = ?", UUID.class, u);
		Sealed selam = new Sealed("user_dictionary_entries", "word_ciphertext", word, jdbc.queryForObject(
				"SELECT word_ciphertext FROM user_dictionary_entries WHERE id = ?", byte[].class, word));
		sealed.add(new Sealed("user_dictionary_sync_state", "key_ciphertext", u, jdbc.queryForObject(
				"SELECT key_ciphertext FROM user_dictionary_sync_state WHERE user_id = ?", byte[].class, u)));
		sealed.add(selam);
		UUID history = UUID.randomUUID();
		Sealed input = seal(u, "user_history_items", "input_ciphertext", history, "እንደምን አደርክ");
		Sealed output = seal(u, "user_history_items", "output_ciphertext", history, "Good morning");
		jdbc.update("""
				INSERT INTO user_history_items (id, user_id, feature_code, input_ciphertext, output_ciphertext, key_version,
				                                expires_at)
				VALUES (?, ?, 'TRANSLATE', ?, ?, 1, now() + interval '30 days')
				""", history, u, input.ciphertext(), output.ciphertext());
		sealed.add(input);
		sealed.add(output);
		jdbc.update("INSERT INTO data_export_requests (user_id) VALUES (?)", u);
		// AI feedback with an encrypted comment, bound to a FEEDBACK_TEXT consent row of the user
		UUID feedbackConsent = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO user_consents (id, user_id, consent_code, consent_version, is_granted, source_platform)
				VALUES (?, ?, 'FEEDBACK_TEXT', '1', TRUE, 'ANDROID')
				""", feedbackConsent, u);
		UUID feedback = UUID.randomUUID();
		Sealed comment = seal(u, "ai_feedback", "comment_ciphertext", feedback, "too formal");
		jdbc.update("""
				INSERT INTO ai_feedback (id, ai_request_id, user_id, feature_code, rating, comment_ciphertext, key_version,
				                         consent_record_id)
				VALUES (?, ?, ?, 'TRANSLATE', -1, ?, 1, ?)
				""", feedback, UUID.randomUUID(), u, comment.ciphertext(), feedbackConsent);
		sealed.add(comment);
		// glossary with an entry, a term suggestion, an attestation nonce
		UUID glossary = UUID.randomUUID();
		jdbc.update("INSERT INTO glossaries (id, user_id, name, source_language_id, target_language_id) "
				+ "VALUES (?, ?, 'names', ?, ?)", glossary, u, am, en);
		jdbc.update("INSERT INTO glossary_entries (glossary_id, source_term, source_term_normalized, target_term) "
				+ "VALUES (?, 'ቡና', 'ቡና', 'coffee')", glossary);
		jdbc.update("INSERT INTO term_suggestions (language_id, surface_form, normalized_form, submitted_by_user_id) "
				+ "VALUES (?, 'ጀበና', 'ጀበና', ?)", am, u);
		jdbc.update("INSERT INTO attestation_nonces (id, nonce_hash, user_id, expires_at) "
				+ "VALUES (?, ?, ?, now() + interval '5 minutes')", UUID.randomUUID(), hex('9'), u);
		// usage: an open reservation (counter, reservation, ledger) and a credit grant (account, lot, ledger)
		metering.reserve(u, "TRANSLATE", 1, "deletion-it-" + UUID.randomUUID());
		credits.grant(u, new BigDecimal("5"), CreditSource.GRANT, "welcome-" + u, Instant.now().plus(Duration.ofDays(30)));
		// module-less tables: feature override, organisation membership, admin role, voice job
		UUID flag = jdbc.queryForObject("INSERT INTO feature_flags (flag_code) VALUES (?) RETURNING id", UUID.class,
				"deletion-it-" + UUID.randomUUID());
		jdbc.update("INSERT INTO user_feature_overrides (user_id, feature_flag_id, is_enabled) VALUES (?, ?, TRUE)", u,
				flag);
		UUID org = jdbc.queryForObject("INSERT INTO organizations (org_slug, display_name) VALUES (?, 'Org') RETURNING id",
				UUID.class, "org-" + Long.toHexString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE));
		jdbc.update("INSERT INTO organization_members (organization_id, user_id) VALUES (?, ?)", org, u);
		jdbc.update("INSERT INTO user_admin_roles (user_id, admin_role_id) "
				+ "SELECT ?, id FROM admin_roles WHERE role_code = 'LANGUAGE_EDITOR'", u);
		jdbc.update("INSERT INTO voice_jobs (user_id, feature_code) VALUES (?, 'SPEECH_TO_TEXT')", u);
		// credentials that only a future e-mail login would write
		jdbc.update("INSERT INTO user_password_credentials (user_id, password_hash) VALUES (?, '$argon2id$x')", u);
		jdbc.update("INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) "
				+ "VALUES (?, ?, now() + interval '1 hour')", u, hex('7'));

		for (Sealed v : sealed) {
			assertThat(open(u, v)).as(v.table() + "." + v.column()).isNotEmpty();
		}
		for (String table : PURGED) {
			assertThat(count("SELECT count(*) FROM " + table + " WHERE user_id = ?", u)).as(table).isPositive();
		}
		return sealed;
	}

	// ---------------------------------------------------------------- the done-when

	@Test
	void afterTheGracePeriodEveryCiphertextIsUnreadableAndEveryPersonalRowIsGone() throws Exception {
		String phone = newPhone();
		Session a = registeredByPhone(phone, UUID.randomUUID());
		Session b = registeredByPhone(phone, UUID.randomUUID()); // a second device of the same account
		UUID u = a.userId();
		assertThat(b.userId()).isEqualTo(u);
		String subject = newSubject();
		jdbc.update("""
				INSERT INTO user_auth_identities (user_id, identity_provider, provider_subject, provider_email)
				VALUES (?, 'GOOGLE', ?, 'person@example.com')
				""", u, subject);
		jdbc.update("UPDATE users SET display_name = 'Abebe', email = 'person@example.com', "
				+ "email_normalized = 'person@example.com', market_country_code = 'ET', market_source = 'SIM_COUNTRY', "
				+ "ui_locale = 'am-ET' WHERE id = ?", u);
		List<Sealed> sealed = seedEverything(a);
		int consents = count("SELECT count(*) FROM user_consents WHERE user_id = ?", u);
		int usageEvents = count("SELECT count(*) FROM usage_events WHERE subject_id = ?", u);
		int creditLedger = count("SELECT count(*) FROM ai_credit_transactions t JOIN ai_credit_accounts c "
				+ "ON c.id = t.credit_account_id WHERE c.user_id = ?", u);
		assertThat(consents).isEqualTo(4);
		assertThat(usageEvents).isOne();
		assertThat(creditLedger).isOne();
		entitlementToken(a.accessToken()).andExpect(status().isOk());

		// ------------------------------------------------------------ request: grace period starts
		Instant before = Instant.now();
		MvcResult requested = requestDeletion(a.accessToken()).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("SCHEDULED"))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andReturn();
		UUID requestId = UUID.fromString(JsonPath.read(requested.getResponse().getContentAsString(), "$.id"));
		Instant purgeAt = Instant.parse(JsonPath.read(requested.getResponse().getContentAsString(), "$.scheduledPurgeAt"));
		assertThat(purgeAt).isBetween(before.plus(GRACE).minusSeconds(5), Instant.now().plus(GRACE).plusSeconds(5));
		assertThat(user(u)).containsEntry("account_status", "DELETION_PENDING").containsEntry("deleted_at", null);
		assertThat(request(requestId)).containsEntry("request_status", "SCHEDULED");
		assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'privacy.account.deletion_requested' "
				+ "AND aggregate_id = ?", u.toString())).isOne();
		// the requesting session stays (to cancel), the other device is signed out, AI stops, the rest works
		refresh(b.refreshToken()).andExpect(status().isUnauthorized());
		Session a2 = session(refresh(a.refreshToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.user.accountStatus").value("DELETION_PENDING")).andReturn());
		entitlementToken(a2.accessToken()).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("DELETION_PENDING"));
		devices(a2.accessToken()).andExpect(status().isOk());
		currentDeletion(a2.accessToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(requestId.toString())).andExpect(jsonPath("$.status").value("SCHEDULED"));
		requestDeletion(a2.accessToken()).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DELETION_PENDING"));
		// signing in again during the grace period works (the account is still there)
		Session c = registeredByPhone(phone, null);
		assertThat(c.userId()).isEqualTo(u);

		// ------------------------------------------------------------ the executor: nothing before the purge time
		assertThat(executor.process(requestId, purgeAt.minusSeconds(60))).isFalse();
		assertThat(request(requestId)).containsEntry("request_status", "SCHEDULED").containsEntry("shredded_at", null);
		assertThat(open(u, sealed.get(0))).isNotEmpty();

		Instant later = purgeAt.plus(Duration.ofHours(1));
		try (CapturedLogs logs = new CapturedLogs()) {
			// (other tests' requests may be due as well: the executor serves them all)
			assertThat(executor.runOnce(later)).isPositive();
			List<String> secrets = List.of(phone.substring(4), "Abebe", "person@example.com", subject, "Mum", "ሰላም",
					"Good morning", a2.accessToken(), a2.refreshToken());
			assertThat(logs.texts()).isNotEmpty()
					.noneMatch(text -> secrets.stream().anyMatch(secret -> text.contains(secret)));
		}

		// every ciphertext is unreadable, and nothing new can be sealed for the user
		for (Sealed v : sealed) {
			assertThatThrownBy(() -> open(u, v)).as(v.table() + "." + v.column())
					.isInstanceOf(CryptoShreddedException.class);
		}
		assertThatThrownBy(() -> encryption.encryptString(u, EncryptionContext.of("t", "c", "r"), "x"))
				.isInstanceOf(CryptoShreddedException.class);
		assertThat(encryption.isShredded(u)).isTrue();
		List<Map<String, Object>> keys = jdbc.queryForList(
				"SELECT key_status, wrapped_dek, destroyed_at FROM user_data_keys WHERE user_id = ?", u);
		assertThat(keys).isNotEmpty().allSatisfy(k -> assertThat(k).containsEntry("key_status", "DESTROYED")
				.containsEntry("wrapped_dek", null).doesNotContainEntry("destroyed_at", null));

		// purged tables hold nothing of the user
		for (String table : PURGED) {
			assertThat(count("SELECT count(*) FROM " + table + " WHERE user_id = ?", u)).as(table).isZero();
		}
		assertThat(count("SELECT count(*) FROM term_suggestions WHERE submitted_by_user_id = ?", u)).isZero();
		assertThat(count("SELECT count(*) FROM term_suggestions WHERE surface_form = 'ጀበና' "
				+ "AND submitted_by_user_id IS NULL")).isOne(); // detached, not deleted
		assertThat(count("SELECT count(*) FROM voice_jobs WHERE user_id IS NULL")).isPositive();

		// append-only and financial records stay, attached only to the anonymised id
		assertThat(count("SELECT count(*) FROM user_consents WHERE user_id = ?", u)).isEqualTo(consents);
		assertThat(count("SELECT count(*) FROM usage_events WHERE subject_id = ?", u)).isEqualTo(usageEvents);
		assertThat(count("SELECT count(*) FROM ai_credit_transactions t JOIN ai_credit_accounts c "
				+ "ON c.id = t.credit_account_id WHERE c.user_id = ?", u)).isEqualTo(creditLedger);
		Map<String, Object> tombstone = user(u);
		assertThat(tombstone).containsEntry("account_status", "DELETED").containsEntry("phone_e164", null)
				.containsEntry("phone_verified_at", null).containsEntry("email", null)
				.containsEntry("email_normalized", null).containsEntry("display_name", null)
				.containsEntry("market_country_code", null).containsEntry("ui_locale", null)
				.containsEntry("age_confirmed_at", null).containsEntry("last_login_at", null)
				.containsEntry("time_zone", "Africa/Addis_Ababa");
		assertThat(tombstone.get("deleted_at")).isNotNull();
		// nothing anywhere in the database still names the person
		assertThat(count("SELECT count(*) FROM users WHERE phone_e164 = ? OR email_normalized = 'person@example.com'",
				phone)).isZero();

		// the request is done; exactly one event
		assertThat(request(requestId)).containsEntry("request_status", "COMPLETED").containsEntry("attempts", 0);
		assertThat(request(requestId).get("shredded_at")).isNotNull();
		assertThat(request(requestId).get("purged_at")).isNotNull();
		assertThat(request(requestId).get("completed_at")).isNotNull();
		assertThat(deletedEvents(u)).isOne();

		// sessions and tokens are dead
		assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = ?", u)).isZero();
		for (Session s : List.of(a, a2, b, c)) {
			devices(s.accessToken()).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
			refresh(s.refreshToken()).andExpect(status().isUnauthorized());
		}
		entitlementToken(a2.accessToken()).andExpect(status().isUnauthorized());
		currentDeletion(a2.accessToken()).andExpect(status().isUnauthorized());

		// the same phone number and Google account now create new, empty accounts
		Session fresh = session(verifyOtp(phone, null, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.isNewUser").value(true)).andReturn());
		assertThat(fresh.userId()).isNotEqualTo(u);
		devices(fresh.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		Session freshGoogle = session(google(subject, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.isNewUser").value(true)).andReturn());
		assertThat(freshGoogle.userId()).isNotIn(u, fresh.userId());
		assertThat(user(u)).containsEntry("account_status", "DELETED").containsEntry("phone_e164", null);

		// idempotent: another run, or another attempt at this request, changes nothing
		executor.runOnce(later.plus(Duration.ofDays(1)));
		assertThat(executor.step(requestId, later.plus(Duration.ofDays(1)))).isEqualTo(DeletionExecutor.Step.SKIPPED);
		assertThat(deletedEvents(u)).isOne();
		assertThat(user(u)).containsEntry("account_status", "DELETED");
	}

	// ---------------------------------------------------------------- cancel

	@Test
	void cancellingDuringTheGracePeriodRestoresTheAccountAndNothingIsDeleted() throws Exception {
		Session a = registeredByPhone(newPhone(), UUID.randomUUID());
		UUID u = a.userId();
		Sealed note = seal(u, "user_history_items", "input_ciphertext", UUID.randomUUID(), "keep me");
		cancelDeletion(a.accessToken()).andExpect(status().isNotFound());
		UUID requestId = requestId(a.accessToken());

		cancelDeletion(a.accessToken()).andExpect(status().isNoContent());
		assertThat(user(u)).containsEntry("account_status", "ACTIVE").containsEntry("deleted_at", null);
		assertThat(request(requestId)).containsEntry("request_status", "CANCELLED")
				.containsEntry("cancellation_reason", "USER_CANCELLED");
		currentDeletion(a.accessToken()).andExpect(status().isNotFound());
		cancelDeletion(a.accessToken()).andExpect(status().isNotFound());
		entitlementToken(a.accessToken()).andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM outbox_events WHERE event_type = 'privacy.account.deletion_cancelled' "
				+ "AND aggregate_id = ?", u.toString())).isOne();

		// past the original purge time the executor leaves the account alone
		Instant afterGrace = Instant.now().plus(GRACE).plus(Duration.ofDays(1));
		assertThat(executor.process(requestId, afterGrace)).isFalse();
		executor.runOnce(afterGrace);
		assertThat(request(requestId)).containsEntry("request_status", "CANCELLED").containsEntry("shredded_at", null);
		assertThat(open(u, note)).isEqualTo("keep me");
		assertThat(encryption.isShredded(u)).isFalse();
		assertThat(deletedEvents(u)).isZero();
		devices(a.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.items").isNotEmpty());

		// a new request is possible after a cancellation (one open request at a time)
		UUID second = requestId(a.accessToken());
		assertThat(second).isNotEqualTo(requestId);
	}

	// ---------------------------------------------------------------- crash recovery

	@Test
	void aCrashBetweenStepsResumesAtTheNextStepAndAFailedStepIsRetriedAfterTheBackoff() throws Exception {
		String phone = newPhone();
		Session a = registeredByPhone(phone, UUID.randomUUID());
		UUID u = a.userId();
		Sealed note = seal(u, "user_history_items", "input_ciphertext", UUID.randomUUID(), "secret");
		jdbc.update("INSERT INTO user_preferences (user_id) VALUES (?)", u);
		UUID requestId = requestId(a.accessToken());
		Instant due = Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(GRACE).plusSeconds(60);

		// step 1 only, then the "crash": the account is closed and shredded, but nothing is purged yet
		assertThat(executor.step(requestId, due)).isEqualTo(DeletionExecutor.Step.SHREDDED);
		assertThat(request(requestId)).containsEntry("request_status", "PROCESSING").containsEntry("purged_at", null);
		assertThatThrownBy(() -> open(u, note)).isInstanceOf(CryptoShreddedException.class);
		assertThat(user(u)).containsEntry("account_status", "DELETION_PENDING");
		assertThat(user(u).get("deleted_at")).isNotNull();
		assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = ? AND session_status = 'ACTIVE'", u))
				.isZero();
		devices(a.accessToken()).andExpect(status().isUnauthorized());
		refresh(a.refreshToken()).andExpect(status().isUnauthorized());
		verifyOtp(phone, null, null).andExpect(status().isUnauthorized()); // closed, not yet anonymised
		cancelDeletion(a.accessToken()).andExpect(status().isUnauthorized());
		assertThat(count("SELECT count(*) FROM user_preferences WHERE user_id = ?", u)).isOne();

		// step 2 fails (a participant throws): everything it did rolls back, the failure is recorded
		FailingParticipantConfiguration.failFor = u;
		assertThat(executor.step(requestId, due)).isEqualTo(DeletionExecutor.Step.FAILED);
		Map<String, Object> failed = request(requestId);
		assertThat(failed).containsEntry("request_status", "PROCESSING").containsEntry("purged_at", null)
				.containsEntry("attempts", 1);
		assertThat((String) failed.get("processing_error")).isEqualTo("purge: java.lang.IllegalStateException");
		Instant retryAt = ((Timestamp) failed.get("next_attempt_at")).toInstant();
		assertThat(retryAt).isEqualTo(due.plus(Duration.ofMinutes(1)));
		assertThat(count("SELECT count(*) FROM user_preferences WHERE user_id = ?", u)).isOne();
		assertThat(count("SELECT count(*) FROM user_auth_identities WHERE user_id = ?", u)).isOne();

		// a second failure doubles the wait
		assertThat(executor.step(requestId, retryAt.minusSeconds(1))).isEqualTo(DeletionExecutor.Step.SKIPPED);
		assertThat(executor.process(requestId, retryAt)).isFalse();
		assertThat(request(requestId)).containsEntry("attempts", 2);
		Instant secondRetry = ((Timestamp) request(requestId).get("next_attempt_at")).toInstant();
		assertThat(secondRetry).isEqualTo(retryAt.plus(Duration.ofMinutes(2)));
		FailingParticipantConfiguration.failFor = null;

		// not before the backoff, then it resumes at step 2 and finishes; step 1 is not repeated
		Object shreddedAt = request(requestId).get("shredded_at");
		assertThat(executor.step(requestId, secondRetry.minusSeconds(1))).isEqualTo(DeletionExecutor.Step.SKIPPED);
		assertThat(executor.process(requestId, secondRetry)).isTrue();
		assertThat(request(requestId)).containsEntry("request_status", "COMPLETED").containsEntry("attempts", 0)
				.containsEntry("processing_error", null).containsEntry("shredded_at", shreddedAt);
		assertThat(count("SELECT count(*) FROM user_preferences WHERE user_id = ?", u)).isZero();
		assertThat(user(u)).containsEntry("account_status", "DELETED").containsEntry("phone_e164", null);
		assertThat(deletedEvents(u)).isOne();
		assertThat(executor.step(requestId, secondRetry)).isEqualTo(DeletionExecutor.Step.SKIPPED);
	}

	@Test
	void aCrashAfterThePurgeOnlyFinishesTheLastStep() throws Exception {
		Session a = registeredByPhone(newPhone(), null);
		UUID u = a.userId();
		UUID requestId = requestId(a.accessToken());
		Instant due = Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(GRACE).plusSeconds(60);
		assertThat(executor.step(requestId, due)).isEqualTo(DeletionExecutor.Step.SHREDDED);
		assertThat(executor.step(requestId, due)).isEqualTo(DeletionExecutor.Step.PURGED);
		assertThat(user(u)).containsEntry("account_status", "DELETION_PENDING");
		assertThat(deletedEvents(u)).isZero();
		// "restart": a later run picks the request up at step 3
		assertThat(executor.runOnce(due.plusSeconds(600))).isPositive();
		assertThat(request(requestId)).containsEntry("request_status", "COMPLETED");
		assertThat(user(u)).containsEntry("account_status", "DELETED");
		assertThat(deletedEvents(u)).isOne();
	}

	// ---------------------------------------------------------------- races

	@Test
	void executorsRacingOnTheSameRequestsCompleteEachExactlyOnce() throws Exception {
		List<UUID> users = new ArrayList<>();
		List<UUID> requests = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Session s = registeredByPhone(newPhone(), UUID.randomUUID());
			seal(s.userId(), "user_history_items", "input_ciphertext", UUID.randomUUID(), "x");
			users.add(s.userId());
			requests.add(requestId(s.accessToken()));
		}
		Instant due = Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(GRACE).plusSeconds(60);
		int threads = 6;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			// half of the threads drive single requests directly, the other half run whole batches
			List<Future<Integer>> results = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				int index = t;
				Callable<Integer> call = () -> {
					start.await();
					if (index % 2 == 0) {
						return executor.process(requests.get(0), due) ? 1 : 0;
					}
					return executor.runOnce(due);
				};
				results.add(pool.submit(call));
			}
			start.countDown();
			int completed = 0;
			for (Future<Integer> f : results) {
				completed += f.get(120, TimeUnit.SECONDS);
			}
			// a request skipped by everybody (all held by another thread at that instant) is picked up by a later run
			completed += executor.runOnce(due);
			assertThat(completed).isGreaterThanOrEqualTo(requests.size()); // other tests' due requests count too
		}
		finally {
			pool.shutdownNow();
		}
		for (int i = 0; i < users.size(); i++) {
			assertThat(request(requests.get(i))).containsEntry("request_status", "COMPLETED").containsEntry("attempts",
					0);
			assertThat(user(users.get(i))).containsEntry("account_status", "DELETED");
			assertThat(deletedEvents(users.get(i))).isOne();
		}
	}

	@Test
	void aCancelRacingTheExecutorEitherCancelsOrLosesCleanly() throws Exception {
		Session a = registeredByPhone(newPhone(), null);
		UUID u = a.userId();
		UUID requestId = requestId(a.accessToken());
		Instant due = Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(GRACE).plusSeconds(60);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Boolean> run = pool.submit(() -> {
				start.await();
				return executor.process(requestId, due);
			});
			Future<Integer> cancel = pool.submit(() -> {
				start.await();
				return cancelDeletion(a.accessToken()).andReturn().getResponse().getStatus();
			});
			start.countDown();
			boolean deleted = run.get(60, TimeUnit.SECONDS);
			int cancelStatus = cancel.get(60, TimeUnit.SECONDS);
			if (cancelStatus == 204) {
				assertThat(deleted).isFalse();
				assertThat(request(requestId)).containsEntry("request_status", "CANCELLED");
				assertThat(user(u)).containsEntry("account_status", "ACTIVE");
				assertThat(encryption.isShredded(u)).isFalse();
			}
			else {
				// the executor won: the cancel was refused (401 once the account is closed, 409 in between)
				assertThat(cancelStatus).isIn(401, 404, 409);
				executor.runOnce(due);
				assertThat(request(requestId)).containsEntry("request_status", "COMPLETED");
				assertThat(user(u)).containsEntry("account_status", "DELETED");
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ---------------------------------------------------------------- guests, refusals, validation, guards

	@Test
	void aGuestCanDeleteItsAccountAndKeepsItsSessionOnlyUntilThePurge() throws Exception {
		Session guest = newGuest();
		UUID requestId = requestId(guest.accessToken());
		// the guest keeps its token during the grace period (to cancel), but can no longer be merged into an account
		currentDeletion(guest.accessToken()).andExpect(status().isOk());
		google(newSubject(), guest.accessToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("GUEST_SESSION_INVALID"));
		assertThat(user(guest.userId())).containsEntry("account_status", "DELETION_PENDING")
				.containsEntry("merged_into_user_id", null);

		assertThat(executor.runOnce(Instant.now().plus(GRACE).plusSeconds(60))).isPositive();
		assertThat(request(requestId)).containsEntry("request_status", "COMPLETED");
		assertThat(user(guest.userId())).containsEntry("account_status", "DELETED").containsEntry("account_type", "GUEST");
		devices(guest.accessToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("GUEST_SESSION_INVALID"));
		assertThat(count("SELECT count(*) FROM user_devices WHERE user_id = ?", guest.userId())).isZero();
	}

	@Test
	void invalidRequestsAndSuspendedAccountsAreRefused() throws Exception {
		Session a = registeredByPhone(newPhone(), null);
		for (String body : List.of("{\"confirm\":false}", "{}", "{\"confirm\":true,\"reason\":\"" + "x".repeat(256) + "\"}")) {
			mvc.perform(post("/api/v1/me/deletion-request").header("Authorization", "Bearer " + a.accessToken())
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		mvc.perform(post("/api/v1/me/deletion-request").contentType(MediaType.APPLICATION_JSON)
				.content("{\"confirm\":true}")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me/deletion-request")).andExpect(status().isUnauthorized());
		assertThat(user(a.userId())).containsEntry("account_status", "ACTIVE");

		jdbc.update("UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?", a.userId());
		requestDeletion(a.accessToken()).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
		assertThat(count("SELECT count(*) FROM user_deletion_requests WHERE user_id = ?", a.userId())).isZero();
	}

	@Test
	void theProgressColumnsRejectStepsOutOfOrder() throws Exception {
		Session a = registeredByPhone(newPhone(), null);
		UUID requestId = requestId(a.accessToken());
		for (String sql : List.of(
				"UPDATE user_deletion_requests SET purged_at = now() WHERE id = ?",
				"UPDATE user_deletion_requests SET shredded_at = now() WHERE id = ?",
				"UPDATE user_deletion_requests SET request_status = 'COMPLETED', completed_at = now() WHERE id = ?",
				"UPDATE user_deletion_requests SET scheduled_purge_at = NULL WHERE id = ?",
				"UPDATE user_deletion_requests SET attempts = -1 WHERE id = ?")) {
			assertThatThrownBy(() -> jdbc.update(sql, requestId)).as(sql)
					.isInstanceOf(DataIntegrityViolationException.class)
					.hasMessageContaining("violates check constraint");
		}
		// the V1 guard: one open request per user
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO user_deletion_requests (user_id, request_status, scheduled_purge_at)
				VALUES (?, 'SCHEDULED', now() + interval '1 day')
				""", a.userId())).isInstanceOf(DataIntegrityViolationException.class);
	}
}
