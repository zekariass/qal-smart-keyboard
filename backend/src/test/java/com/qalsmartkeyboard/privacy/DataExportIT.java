package com.qalsmartkeyboard.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.CryptoShreddedException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;
import com.qalsmartkeyboard.usage.CreditSource;
import com.qalsmartkeyboard.usage.Credits;
import com.qalsmartkeyboard.usage.Metering;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B6.1 "done when": the data export end to end on PostgreSQL 18 and MinIO, through the security chain and the real job,
 * every response validated against {@code contracts/openapi.yaml}. A user with data in <b>every</b> per-user class of
 * SD 19.2 asks for an export; the job builds it; the archive is downloaded and checked section by section against its
 * manifest (right row counts, plaintext values for everything that was stored encrypted, hashes only for app rules,
 * nothing of another user, no secret). Also: the object at rest is ciphertext only, the link expires, ownership (404),
 * the rate limit, one in flight, deletion and crypto-shredding make exports unreadable and a deleted account cannot
 * export, a schema guard that every per-user table is either exported or deliberately excluded, and the log scrub.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.privacy.export.job-enabled=false",
		"qal.entitlements.cache-ttl=0s" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class DataExportIT {

	static final String MINIO_IMAGE = "cgr.dev/chainguard/minio@sha256:59953701f8f8d0de81aab456f0b0b6c0a1804119415559bf7ede79d944108879";
	static final String MINIO_USER = "qal_minio_test";
	static final String MINIO_PASSWORD = "qal_minio_test_only";
	static final String BUCKET = "qal-exports";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Container
	static final GenericContainer<?> MINIO = new GenericContainer<>(
			DockerImageName.parse(System.getProperty("qal.test.minio-image", MINIO_IMAGE)))
			.withEnv("MINIO_ROOT_USER", MINIO_USER).withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
			.withCommand("server", "/data").withExposedPorts(9000)
			.waitingFor(Wait.forHttp("/minio/health/live").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));

	static String endpoint() {
		return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
	}

	@DynamicPropertySource
	static void objectStore(DynamicPropertyRegistry registry) {
		registry.add("qal.privacy.export.storage.endpoint", DataExportIT::endpoint);
		registry.add("qal.privacy.export.storage.bucket", () -> BUCKET);
		registry.add("qal.privacy.export.storage.access-key", () -> MINIO_USER);
		registry.add("qal.privacy.export.storage.secret-key", () -> MINIO_PASSWORD);
	}

	@BeforeAll
	static void bucket() {
		new S3ExportObjectStore(endpoint(), BUCKET, "us-east-1", true, MINIO_USER, MINIO_PASSWORD, java.time.Clock.systemUTC())
				.createBucketIfMissing();
	}

	// markers that must appear in the archive in the clear, and nowhere else
	static final String NAME = "Abebe Kebede Zeleke";
	static String emailOf(UUID user) {
		return "abebe." + user + "@example.com";
	}

	static final String LABEL = "Mum Zenebech";
	static final String SIGNOFF = "Yours Truly Wubit";
	static final String PHRASES = "see you soon Tigist";
	static final String WORD_A = "ሰላምታ";
	static final String WORD_B = "ቡናማ";
	static final String WORD_GONE = "ተሰርዟል";
	static final String HISTORY_IN = "Zebra lighthouse marmalade";
	static final String HISTORY_OUT = "Pelican turbine saffron";
	static final String HISTORY_EXPIRED = "Walrus ornament cinnamon";
	static final String COMMENT = "Heron compass nutmeg";
	static final String CONTENT_IN = "Otter lantern paprika";
	static final String CONTENT_OUT = "Badger anchor vanilla";
	static final String GLOSSARY_TERM = "Meskel Dembel";
	static final String OTHER = "Bystander Secret Walnut";
	static final String HASH = "a".repeat(64);

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	UserDataEncryption encryption;
	@Autowired
	DataExportJob job;
	@Autowired
	DataExportService service;
	@Autowired
	ExportObjectStore store;
	@Autowired
	DeletionExecutor deletionExecutor;
	@Autowired
	Metering metering;
	@Autowired
	Credits credits;
	@Autowired
	JsonMapper json;

	// ---------------------------------------------------------------------------------------------- helpers

	UUID user(String type) {
		return jdbc.queryForObject("""
				INSERT INTO users (account_type, account_status, time_zone) VALUES (?, 'ACTIVE', 'Africa/Addis_Ababa')
				RETURNING id""", UUID.class, type);
	}

	String bearer(UUID user) {
		return "Bearer " + tokens.issue(user, UUID.randomUUID(), null, "REGISTERED").value();
	}

	UUID language(String code) {
		return jdbc.queryForObject("SELECT id FROM supported_languages WHERE language_code = ?", UUID.class, code);
	}

	ResultActions requestExport(String bearer) throws Exception {
		return mvc.perform(post("/api/v1/me/data-exports").header("Authorization", bearer));
	}

	ResultActions exportStatus(String bearer, Object id) throws Exception {
		return mvc.perform(get("/api/v1/me/data-exports/" + id).header("Authorization", bearer));
	}

	ResultActions archive(String bearer, Object id) throws Exception {
		return mvc.perform(get("/api/v1/me/data-exports/" + id + "/archive").header("Authorization", bearer));
	}

	UUID id(ResultActions r) throws Exception {
		return UUID.fromString(json.readTree(r.andReturn().getResponse().getContentAsString()).get("id").asString());
	}

	/** Requests, builds and returns the export id. */
	UUID exported(String bearer) throws Exception {
		UUID id = id(requestExport(bearer).andExpect(status().isAccepted()));
		job.runOnce();
		exportStatus(bearer, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
		return id;
	}

	JsonNode download(String bearer, UUID id) throws Exception {
		MvcResult r = archive(bearer, id).andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))).andReturn();
		return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	void consent(UUID user, String code, boolean granted) throws Exception {
		mvc.perform(put("/api/v1/me/consents/" + code).header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"1\",\"isGranted\":" + granted + "}")).andExpect(status().isOk());
	}

	EncryptionContext ctx(String table, String column, Object row) {
		return EncryptionContext.of(table, column, row.toString());
	}

	byte[] seal(UUID user, String table, String column, UUID row, String text) {
		return encryption.encryptString(user, ctx(table, column, row), text).ciphertext();
	}

	static String hex(String seed) {
		return (UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString()
				+ UUID.nameUUIDFromBytes((seed + "2").getBytes(StandardCharsets.UTF_8))).replace("-", "").substring(0, 64);
	}

	/**
	 * Data in every per-user class of SD 19.2 (and every encrypted column): through the API where a writer exists
	 * (consents, metering, credits), directly otherwise (the way the owning modules will write it).
	 */
	void seedEverything(UUID u) throws Exception {
		UUID am = language("am");
		UUID en = language("en");
		// account
		jdbc.update("""
				UPDATE users SET display_name = ?, email = ?, email_normalized = ?, phone_e164 = ?, market_country_code = 'ET',
				       market_source = 'SIM_COUNTRY', ui_locale = 'am-ET', age_confirmed_at = now() WHERE id = ?""", NAME,
				emailOf(u), emailOf(u), "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)), u);
		jdbc.update("INSERT INTO user_auth_identities (user_id, identity_provider, provider_subject, provider_email) "
				+ "VALUES (?, 'GOOGLE', ?, ?)", u, "g-" + UUID.randomUUID(), emailOf(u));
		// sessions metadata (one active, one revoked)
		for (String status : List.of("ACTIVE", "REVOKED")) {
			jdbc.update("INSERT INTO user_sessions (user_id, token_family_id, refresh_token_hash, session_status, ip_address,"
					+ " user_agent, expires_at, revoked_at, revoked_reason) VALUES (?, ?, ?, ?, ?::inet, 'QalTest/1.0',"
					+ " now() + interval '30 days', ?, ?)", u, UUID.randomUUID(), hex("session-" + UUID.randomUUID()), status,
					"10.1.2.3", "REVOKED".equals(status) ? Timestamp.from(Instant.now()) : null,
					"REVOKED".equals(status) ? "LOGOUT" : null);
		}
		// devices and installed language resources
		UUID device = null;
		for (int i = 0; i < 2; i++) {
			UUID d = UUID.randomUUID();
			jdbc.update("INSERT INTO user_devices (id, user_id, installation_id, platform, device_name, app_version,"
					+ " push_provider, push_token) VALUES (?, ?, ?, 'ANDROID', ?, '1.0.0', 'FCM', ?)", d, u,
					UUID.randomUUID(), "Pixel " + i, "push-token-secret-" + UUID.randomUUID());
			device = d;
		}
		UUID resource = jdbc.queryForObject("""
				INSERT INTO language_resources (language_id, resource_type, resource_version, artifact_uri, sha256_checksum)
				VALUES (?, 'DICTIONARY', '1.0.0-' || substr(gen_random_uuid()::text, 1, 8), 's3://x/y', ?) RETURNING id""",
				UUID.class, am, hex("resource"));
		jdbc.update("INSERT INTO device_language_resource_installations (device_id, resource_id, installed_version,"
				+ " installation_status) VALUES (?, ?, '1.0.0', 'PENDING')", device, resource);
		// preferences, typing languages, destination profile, app rule (hash), style profile
		jdbc.update("INSERT INTO user_preferences (user_id, calendar_system, default_source_language_id, "
				+ "default_destination_language_id, ai_history_enabled, settings_json) VALUES (?, 'ETHIOPIAN', ?, ?, TRUE, "
				+ "'{\"keyClick\":\"soft\"}'::jsonb)", u, am, en);
		jdbc.update("INSERT INTO user_typing_languages (user_id, language_id, is_primary, sort_order) VALUES (?, ?, TRUE, 1)",
				u, am);
		jdbc.update("INSERT INTO user_typing_languages (user_id, language_id, is_primary, sort_order) VALUES (?, ?, FALSE, 2)",
				u, en);
		UUID profile = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO user_destination_profiles (id, user_id, label_ciphertext, key_version, destination_language_id,
				                                       is_default) VALUES (?, ?, ?, 1, ?, TRUE)""", profile, u,
				seal(u, "user_destination_profiles", "label_ciphertext", profile, LABEL), am);
		jdbc.update("INSERT INTO user_app_destination_rules (user_id, app_package_hmac, destination_profile_id)"
				+ " VALUES (?, ?, ?)", u, HASH, profile);
		UUID style = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO user_style_profiles (id, user_id, language_id, signoff_ciphertext, preferred_phrases_ciphertext,
				                                 key_version) VALUES (?, ?, ?, ?, ?, 1)""", style, u, am,
				seal(u, "user_style_profiles", "signoff_ciphertext", style, SIGNOFF),
				seal(u, "user_style_profiles", "preferred_phrases_ciphertext", style, PHRASES));
		// secrets that must never be exported: the app-rule key and the dictionary key
		jdbc.update("INSERT INTO user_app_rule_keys (user_id, key_ciphertext, key_version) VALUES (?, ?, 1)", u,
				seal(u, "user_app_rule_keys", "key_ciphertext", u, "rule-key-secret"));
		// consents (through the API; FEEDBACK_TEXT is needed for the feedback below) and withdrawn once
		for (String code : List.of("TERMS_ACCEPTANCE", "ANALYTICS", "FEEDBACK_TEXT", "SAVE_AI_HISTORY")) {
			consent(u, code, true);
		}
		consent(u, "ANALYTICS", false);
		// history: two live items (one favourite without expiry) and an expired one that must not be exported
		for (Object[] h : new Object[][] { { HISTORY_IN, HISTORY_OUT, "now() + interval '30 days'", false },
				{ HISTORY_IN + " 2", HISTORY_OUT + " 2", null, true },
				{ HISTORY_EXPIRED, HISTORY_EXPIRED, "now() + interval '1 day'", false } }) {
			UUID id = UUID.randomUUID();
			jdbc.update("INSERT INTO user_history_items (id, user_id, feature_code, source_language_id, "
					+ "destination_language_id, input_ciphertext, output_ciphertext, key_version, is_favorite, expires_at) "
					+ "VALUES (?, ?, 'TRANSLATE', ?, ?, ?, ?, 1, ?, " + (h[2] == null ? "NULL" : h[2]) + ")", id, u, en, am,
					seal(u, "user_history_items", "input_ciphertext", id, (String) h[0]),
					seal(u, "user_history_items", "output_ciphertext", id, (String) h[1]), h[3]);
			if (HISTORY_EXPIRED.equals(h[0])) {
				jdbc.update("UPDATE user_history_items SET created_at = now() - interval '10 days', "
						+ "expires_at = now() - interval '1 day' WHERE id = ?", id);
			}
		}
		// dictionary: two words and a deleted one (a tombstone), and a term suggestion
		for (Object[] w : new Object[][] { { WORD_A, null }, { WORD_B, null }, { WORD_GONE, Timestamp.from(Instant.now()) } }) {
			UUID id = UUID.randomUUID();
			jdbc.update("INSERT INTO user_dictionary_entries (id, user_id, language_id, word_ciphertext, word_hmac, key_version,"
					+ " deleted_at) VALUES (?, ?, ?, ?, ?, 1, ?)", id, u, am,
					seal(u, "user_dictionary_entries", "word_ciphertext", id, (String) w[0]), hex((String) w[0] + u), w[1]);
		}
		jdbc.update("INSERT INTO user_dictionary_sync_state (user_id, key_ciphertext, key_version) VALUES (?, ?, 1)", u,
				seal(u, "user_dictionary_sync_state", "key_ciphertext", u, "dictionary-key-secret"));
		jdbc.update("INSERT INTO term_suggestions (language_id, surface_form, normalized_form, submitted_by_user_id) "
				+ "VALUES (?, 'ጀበና', 'ጀበና', ?)", am, u);
		// glossary with two entries
		UUID glossary = UUID.randomUUID();
		jdbc.update("INSERT INTO glossaries (id, user_id, name, source_language_id, target_language_id) "
				+ "VALUES (?, ?, 'names', ?, ?)", glossary, u, am, en);
		jdbc.update("INSERT INTO glossary_entries (glossary_id, source_term, source_term_normalized, target_term) "
				+ "VALUES (?, ?, ?, 'Meskel Square')", glossary, GLOSSARY_TERM, GLOSSARY_TERM);
		jdbc.update("INSERT INTO glossary_entries (glossary_id, source_term, source_term_normalized, do_not_translate) "
				+ "VALUES (?, 'ቡና', 'ቡና', TRUE)", glossary);
		// usage counters and events (metering), credits (account, lot, ledger)
		metering.reserve(u, "TRANSLATE", 1, "export-it-" + UUID.randomUUID());
		credits.grant(u, new BigDecimal("5"), CreditSource.GRANT, "welcome-" + u, Instant.now().plus(Duration.ofDays(30)));
		// AI request metadata and feedback (rating + sealed comment and content under the FEEDBACK_TEXT consent)
		UUID r1 = UUID.randomUUID();
		UUID r2 = UUID.randomUUID();
		for (UUID r : List.of(r1, r2)) {
			jdbc.update("INSERT INTO ai_requests (id, subject_type, user_id, feature_code, surface, source_language_id, "
					+ "destination_language_id, request_status, metered_units, input_character_count, output_character_count, "
					+ "duration_ms, estimated_cost_micros, idempotency_key, completed_at) VALUES (?, 'USER', ?, 'TRANSLATE', "
					+ "'KEYBOARD', ?, ?, 'SUCCEEDED', 1, 25, 30, 800, 1200, ?, now())", r, u, en, am, "idem-secret-" + r);
		}
		UUID consentRecord = jdbc.queryForObject("SELECT id FROM user_consents WHERE user_id = ? AND consent_code = "
				+ "'FEEDBACK_TEXT' AND is_granted", UUID.class, u);
		UUID feedback = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO ai_feedback (id, ai_request_id, user_id, feature_code, rating, reason_codes, comment_ciphertext,
				       input_ciphertext, output_ciphertext, key_version, consent_record_id)
				VALUES (?, ?, ?, 'TRANSLATE', -1, ARRAY['WRONG_TONE'], ?, ?, ?, 1, ?)""", feedback, r1, u,
				seal(u, "ai_feedback", "comment_ciphertext", feedback, COMMENT),
				seal(u, "ai_feedback", "input_ciphertext", feedback, CONTENT_IN),
				seal(u, "ai_feedback", "output_ciphertext", feedback, CONTENT_OUT), consentRecord);
		// subscription, feature override
		jdbc.update("INSERT INTO user_subscriptions (user_id, plan_id, source_type, billing_mode, subscription_status,"
				+ " current_period_start, current_period_end, started_at) SELECT ?, id, 'ADMIN_GRANT', 'GRANT', 'ACTIVE', "
				+ "now(), now() + interval '30 days', now() FROM subscription_plans WHERE plan_code = 'PREMIUM' "
				+ "LIMIT 1", u);
		UUID flag = jdbc.queryForObject("INSERT INTO feature_flags (flag_code) VALUES (?) RETURNING id", UUID.class,
				"export-it-" + UUID.randomUUID());
		jdbc.update("INSERT INTO user_feature_overrides (user_id, feature_flag_id, is_enabled, reason) VALUES (?, ?, TRUE, "
				+ "'beta tester')", u, flag);
		// receipts: an order and an app-store purchase
		UUID product = jdbc.queryForObject("""
				INSERT INTO billing_products (product_code, product_type, plan_id, display_name, product_status)
				SELECT 'p-' || gen_random_uuid(), 'SUBSCRIPTION', id, 'Plus', 'ACTIVE' FROM subscription_plans
				WHERE plan_code = 'PREMIUM' RETURNING id""", UUID.class);
		UUID price = jdbc.queryForObject("""
				INSERT INTO billing_prices (billing_product_id, sales_channel, currency_code, amount_minor, billing_period,
				       renewal_model) VALUES (?, 'WEB', 'ETB', 29900, 'MONTHLY', 'MANUAL_RENEW') RETURNING id""", UUID.class,
				product);
		jdbc.update("""
				INSERT INTO payment_orders (order_reference, user_id, billing_product_id, billing_price_id, order_type,
				       sales_channel, market_country_code, currency_code, list_amount_minor, discount_minor, tax_minor,
				       amount_minor, order_status, idempotency_key, paid_at)
				VALUES (?, ?, ?, ?, 'SUBSCRIPTION_PURCHASE', 'WEB', 'ZZ', 'ETB', 29900, 0, 0, 29900, 'PAID', ?, now())""",
				"ORD-" + UUID.randomUUID().toString().substring(0, 8), u, product, price, "order-key-" + UUID.randomUUID());
		UUID provider = jdbc.queryForObject("""
				INSERT INTO payment_providers (provider_code, display_name, provider_type, provider_status, is_active)
				VALUES (?, 'Store', 'APP_STORE', 'ACTIVE', TRUE) RETURNING id""", UUID.class, "st-" + UUID.randomUUID().toString().substring(0, 12));
		UUID mapping = jdbc.queryForObject("""
				INSERT INTO external_product_mappings (billing_price_id, provider_id, external_product_id)
				VALUES (?, ?, 'plus.monthly') RETURNING id""", UUID.class, price, provider);
		jdbc.update("""
				INSERT INTO store_purchases (user_id, provider_id, provider_type, external_product_mapping_id,
				       purchase_token_hash, purchase_token_ciphertext, original_transaction_id, environment, purchase_status,
				       purchased_at) VALUES (?, ?, 'APP_STORE', ?, ?, ?, 'orig-1', 'PRODUCTION', 'ACTIVE', now())""", u, provider,
				mapping, hex("token-" + u), new byte[] { 1, 2, 3 });
		// voice and realtime metadata, an organisation membership
		jdbc.update("INSERT INTO voice_jobs (user_id, feature_code, source_language_id, audio_object_key, audio_retention_until) "
				+ "VALUES (?, 'SPEECH_TO_TEXT', ?, 'audio/secret-object', now() + interval '1 day')", u, am);
		jdbc.update("INSERT INTO realtime_sessions (user_id, feature_code, language_a_id, language_b_id, ticket_hash, "
				+ "ticket_expires_at, max_duration_seconds) VALUES (?, 'LIVE_CONVERSATION', ?, ?, ?, now() + interval '5 minutes', 600)", u, am, en,
				hex("ticket-" + u));
		UUID org = jdbc.queryForObject("INSERT INTO organizations (org_slug, display_name) VALUES (?, 'Selam Org') "
				+ "RETURNING id", UUID.class, "org-" + Long.toHexString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE));
		jdbc.update("INSERT INTO organization_members (organization_id, user_id) VALUES (?, ?)", org, u);
		jdbc.update("INSERT INTO user_admin_roles (user_id, admin_role_id) SELECT ?, id FROM admin_roles "
				+ "WHERE role_code = 'LANGUAGE_EDITOR'", u);
		// internal, never exported
		jdbc.update("INSERT INTO user_password_credentials (user_id, password_hash) VALUES (?, '$argon2id$secret-hash')", u);
	}

	/** Every section of the archive and the rows {@link #seedEverything} puts into it. */
	static final Map<String, Integer> EXPECTED = Map.ofEntries(Map.entry("account", 1), Map.entry("authIdentities", 1),
			Map.entry("sessions", 2), Map.entry("devices", 2), Map.entry("deviceLanguageInstallations", 1),
			Map.entry("preferences", 1), Map.entry("typingLanguages", 2), Map.entry("destinationProfiles", 1),
			Map.entry("appDestinationRules", 1), Map.entry("styleProfiles", 1), Map.entry("consents", 5),
			Map.entry("history", 2), Map.entry("exportRequests", 1), Map.entry("deletionRequests", 0),
			Map.entry("dictionary", 2), Map.entry("termSuggestions", 1), Map.entry("glossaries", 1),
			Map.entry("glossaryEntries", 2), Map.entry("usageCounters", 1), Map.entry("usageEvents", 1),
			Map.entry("creditAccounts", 1), Map.entry("creditLots", 1), Map.entry("creditTransactions", 1),
			Map.entry("subscriptions", 1), Map.entry("featureOverrides", 1), Map.entry("aiRequests", 2),
			Map.entry("aiFeedback", 1), Map.entry("paymentOrders", 1), Map.entry("storePurchases", 1),
			Map.entry("voiceJobs", 1), Map.entry("realtimeSessions", 1), Map.entry("organizationMemberships", 1));

	static JsonNode row(JsonNode archive, String section, int index) {
		return archive.get("data").get(section).get(index);
	}

	// ---------------------------------------------------------------------------------------------- the done-when

	@Test
	void theArchiveHoldsEveryUserDataClassWithItsRowsAndPlaintext() throws Exception {
		UUID u = user("REGISTERED");
		UUID bystander = user("REGISTERED");
		seedEverything(u);
		jdbc.update("UPDATE users SET display_name = ? WHERE id = ?", OTHER, bystander);
		UUID bystanderHistory = UUID.randomUUID();
		jdbc.update("INSERT INTO user_history_items (id, user_id, feature_code, input_ciphertext, output_ciphertext, "
				+ "key_version, expires_at) VALUES (?, ?, 'TRANSLATE', ?, ?, 1, now() + interval '1 day')", bystanderHistory,
				bystander, seal(bystander, "user_history_items", "input_ciphertext", bystanderHistory, OTHER),
				seal(bystander, "user_history_items", "output_ciphertext", bystanderHistory, OTHER));
		String bearer = bearer(u);

		// request: 202, queued, contract-valid; nothing to download yet
		MvcResult requested = requestExport(bearer).andExpect(status().isAccepted())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.status").value("REQUESTED")).andExpect(jsonPath("$.downloadUrl").doesNotExist())
				.andReturn();
		UUID id = UUID.fromString(json.readTree(requested.getResponse().getContentAsString()).get("id").asString());
		archive(bearer, id).andExpect(status().isNotFound());
		assertThat(job.runOnce()).isGreaterThanOrEqualTo(1);

		Instant before = Instant.now();
		MvcResult ready = exportStatus(bearer, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.downloadUrl").value("http://localhost/api/v1/me/data-exports/" + id + "/archive"))
				.andReturn();
		JsonNode state = json.readTree(ready.getResponse().getContentAsString());
		Instant readyAt = Instant.parse(state.get("readyAt").asString());
		Instant expiresAt = Instant.parse(state.get("downloadExpiresAt").asString());
		assertThat(Duration.between(readyAt, expiresAt)).isEqualTo(Duration.ofDays(7)); // SD 19.4
		assertThat(expiresAt).isAfter(before.plus(Duration.ofDays(6)));

		JsonNode archive = download(bearer, id);

		// ---- manifest
		JsonNode manifest = archive.get("manifest");
		assertThat(manifest.get("format").asString()).isEqualTo("qal-data-export");
		assertThat(manifest.get("formatVersion").intValue()).isEqualTo(1);
		assertThat(manifest.get("exportId").asString()).isEqualTo(id.toString());
		assertThat(manifest.get("userId").asString()).isEqualTo(u.toString());
		assertThat(Instant.parse(manifest.get("linkExpiresAt").asString())).isEqualTo(expiresAt);
		Map<String, Integer> listed = new java.util.LinkedHashMap<>();
		for (JsonNode s : manifest.get("sections")) {
			assertThat(s.get("dataClass").asString()).isNotBlank();
			assertThat(s.get("description").asString()).isNotBlank();
			listed.put(s.get("code").asString(), s.get("rows").intValue());
		}
		assertThat(listed).containsExactlyInAnyOrderEntriesOf(EXPECTED); // every class, the right counts
		for (var e : EXPECTED.entrySet()) {
			assertThat(archive.get("data").get(e.getKey()).size()).as(e.getKey()).isEqualTo(e.getValue());
		}
		assertThat(archive.get("data").propertyNames()).containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
		Set<String> excluded = new TreeSet<>();
		manifest.get("excluded").forEach(x -> excluded.add(x.get("code").asString()));
		assertThat(excluded).contains("keystrokesAndDrafts", "audio", "auditAndSecurityRecords", "credentials", "appRuleKey",
				"aiRequestContent", "aiReplayCache", "usageReservations", "dictionarySyncState", "adminRoles",
				"billingRecords");
		assertThat(manifest.get("notes").toString()).contains("package names");

		// ---- values, with encrypted columns opened
		JsonNode account = row(archive, "account", 0);
		assertThat(account.get("id").asString()).isEqualTo(u.toString());
		assertThat(account.get("displayName").asString()).isEqualTo(NAME);
		assertThat(account.get("email").asString()).isEqualTo(emailOf(u));
		assertThat(account.get("accountType").asString()).isEqualTo("REGISTERED");
		assertThat(account.get("uiLocale").asString()).isEqualTo("am-ET");
		assertThat(row(archive, "authIdentities", 0).get("identityProvider").asString()).isEqualTo("GOOGLE");
		assertThat(row(archive, "sessions", 0).get("ipAddress").asString()).isEqualTo("10.1.2.3");
		assertThat(row(archive, "sessions", 0).get("userAgent").asString()).isEqualTo("QalTest/1.0");
		assertThat(row(archive, "devices", 0).get("hasPushToken").booleanValue()).isTrue();
		assertThat(row(archive, "deviceLanguageInstallations", 0).get("installationStatus").asString()).isEqualTo("PENDING");
		JsonNode prefs = row(archive, "preferences", 0);
		assertThat(prefs.get("calendarSystem").asString()).isEqualTo("ETHIOPIAN");
		assertThat(prefs.get("defaultSourceLanguage").asString()).isEqualTo("am");
		assertThat(prefs.get("aiHistoryEnabled").booleanValue()).isTrue();
		assertThat(prefs.get("settingsJson").get("keyClick").asString()).isEqualTo("soft");
		assertThat(row(archive, "typingLanguages", 0).get("languageCode").asString()).isEqualTo("am");
		assertThat(row(archive, "destinationProfiles", 0).get("label").asString()).isEqualTo(LABEL);
		assertThat(row(archive, "appDestinationRules", 0).get("appPackageHmac").asString()).isEqualTo(HASH);
		assertThat(row(archive, "appDestinationRules", 0).has("appPackage")).isFalse();
		assertThat(row(archive, "styleProfiles", 0).get("signoff").asString()).isEqualTo(SIGNOFF);
		assertThat(row(archive, "styleProfiles", 0).get("preferredPhrases").asString()).isEqualTo(PHRASES);
		List<String> consentCodes = new ArrayList<>();
		archive.get("data").get("consents").forEach(c -> consentCodes.add(c.get("consentCode").asString() + ":"
				+ c.get("isGranted").booleanValue()));
		assertThat(consentCodes).containsExactly("TERMS_ACCEPTANCE:true", "ANALYTICS:true", "FEEDBACK_TEXT:true",
				"SAVE_AI_HISTORY:true", "ANALYTICS:false"); // the whole history, oldest first
		List<String> history = new ArrayList<>();
		archive.get("data").get("history").forEach(h -> history.add(h.get("inputText").asString() + "|"
				+ h.get("outputText").asString()));
		assertThat(history).containsExactlyInAnyOrder(HISTORY_IN + "|" + HISTORY_OUT, HISTORY_IN + " 2|" + HISTORY_OUT + " 2");
		List<String> words = new ArrayList<>();
		archive.get("data").get("dictionary").forEach(w -> words.add(w.get("word").asString()));
		assertThat(words).containsExactlyInAnyOrder(WORD_A, WORD_B); // the deleted word is not exported
		assertThat(row(archive, "termSuggestions", 0).get("surfaceForm").asString()).isEqualTo("ጀበና");
		assertThat(row(archive, "glossaries", 0).get("name").asString()).isEqualTo("names");
		assertThat(archive.get("data").get("glossaryEntries").toString()).contains(GLOSSARY_TERM);
		assertThat(row(archive, "usageEvents", 0).get("featureCode").asString()).isEqualTo("TRANSLATE");
		assertThat(row(archive, "creditAccounts", 0).get("balanceUnits").asString()).isEqualTo("5");
		assertThat(row(archive, "creditTransactions", 0).get("deltaUnits").asString()).isEqualTo("5");
		assertThat(row(archive, "subscriptions", 0).get("subscriptionStatus").asString()).isEqualTo("ACTIVE");
		assertThat(row(archive, "subscriptions", 0).get("planCode").asString()).isNotBlank();
		assertThat(row(archive, "featureOverrides", 0).get("reason").asString()).isEqualTo("beta tester");
		assertThat(row(archive, "aiRequests", 0).get("inputCharacterCount").intValue()).isEqualTo(25);
		assertThat(row(archive, "aiRequests", 0).get("sourceLanguage").asString()).isEqualTo("en");
		JsonNode feedback = row(archive, "aiFeedback", 0);
		assertThat(feedback.get("rating").asString()).isEqualTo("NEGATIVE");
		assertThat(feedback.get("reasonCodes").get(0).asString()).isEqualTo("WRONG_TONE");
		assertThat(feedback.get("comment").asString()).isEqualTo(COMMENT);
		assertThat(feedback.get("inputText").asString()).isEqualTo(CONTENT_IN);
		assertThat(feedback.get("outputText").asString()).isEqualTo(CONTENT_OUT);
		assertThat(row(archive, "paymentOrders", 0).get("amountMinor").longValue()).isEqualTo(29900L);
		assertThat(row(archive, "storePurchases", 0).get("originalTransactionId").asString()).isEqualTo("orig-1");
		assertThat(row(archive, "voiceJobs", 0).get("jobStatus").asString()).isEqualTo("PENDING");
		assertThat(row(archive, "realtimeSessions", 0).get("languageA").asString()).isEqualTo("am");
		assertThat(row(archive, "organizationMemberships", 0).get("organization").asString()).isEqualTo("Selam Org");
		assertThat(row(archive, "exportRequests", 0).get("id").asString()).isEqualTo(id.toString());

		// ---- exclusions: nobody else's data, no secrets, nothing binary
		String text = archive.toString();
		assertThat(text).doesNotContain(OTHER, bystander.toString(), HISTORY_EXPIRED, WORD_GONE);
		assertThat(text).doesNotContain("push-token-secret", "rule-key-secret", "dictionary-key-secret", "secret-hash",
				"audio/secret-object", "idem-secret", hex("token-" + u), hex("ticket-" + u));
		assertThat(text).doesNotContain("Ciphertext", "refreshToken", "passwordHash", "keyCiphertext", "purchaseToken",
				"tokenFamilyId", "exportObjectKey", "exports/");
	}

	@Test
	void everyPerUserTableIsExportedOrDeliberatelyExcluded() {
		Set<String> exported = Set.of("user_auth_identities", "user_sessions", "user_devices", "user_preferences",
				"user_typing_languages", "user_destination_profiles", "user_app_destination_rules", "user_style_profiles",
				"user_consents", "user_history_items", "data_export_requests", "user_deletion_requests",
				"user_dictionary_entries", "term_suggestions", "glossaries", "usage_counters", "usage_events",
				"ai_credit_accounts", "user_feature_overrides", "user_subscriptions", "ai_requests", "ai_feedback",
				"payment_orders", "store_purchases", "voice_jobs", "realtime_sessions", "organization_members");
		Set<String> excluded = Set.of(
				// credentials and secrets
				"user_password_credentials", "password_reset_tokens", "otp_challenges", "email_challenges",
				"attestation_nonces",
				"user_data_keys", "user_app_rule_keys", "user_dictionary_sync_state",
				// bookkeeping and transient state
				"user_typing_language_revisions", "user_app_rule_revisions", "user_dictionary_applied_changes",
				"usage_reservations", "ai_request_fingerprints", "ai_replay_cache",
				// internal records about the service
				"user_admin_roles", "audit_events",
				// billing records no code writes yet: the billing module adds them with its participant (ADR 0022)
				"payment_attempts", "payment_mandates", "subscription_payment_allocations", "organization_seat_assignments",
				"payment_transactions", "payment_refunds");
		Set<String> perUser = new TreeSet<>(jdbc.queryForList("""
				SELECT DISTINCT table_name FROM information_schema.columns
				WHERE table_schema = 'public' AND column_name IN ('user_id', 'submitted_by_user_id')""", String.class));
		perUser.removeAll(exported);
		perUser.removeAll(excluded);
		assertThat(perUser).as("per-user tables that are neither exported nor deliberately excluded (ADR 0022)").isEmpty();
	}

	// ---------------------------------------------------------------------------------------------- at rest

	@Test
	void theObjectAtRestIsCiphertextUnderTheUsersKeyAndUnguessable() throws Exception {
		UUID u = user("REGISTERED");
		seedEverything(u);
		String bearer = bearer(u);
		UUID id = exported(bearer);
		JsonNode archive = download(bearer, id);
		String key = jdbc.queryForObject("SELECT export_object_key FROM data_export_requests WHERE id = ?", String.class, id);

		// non-guessable: 256 random bits, nothing of the user or the export in the key
		assertThat(key).matches("exports/[0-9a-f]{64}").doesNotContain(u.toString(), id.toString());
		byte[] sealed = store.get(key, 64L << 20).orElseThrow();

		// not plaintext: not JSON, none of the stored words, none of the structure
		String raw = new String(sealed, StandardCharsets.ISO_8859_1);
		assertThatThrownBy(() -> json.readTree(sealed)).isInstanceOf(RuntimeException.class);
		for (String marker : List.of("qal-data-export", "manifest", NAME, emailOf(u), LABEL, HISTORY_IN, COMMENT, "ETHIOPIAN")) {
			assertThat(raw).as(marker).doesNotContain(marker);
		}
		for (String marker : List.of(NAME, WORD_A, HISTORY_OUT, CONTENT_OUT)) {
			assertThat(indexOf(sealed, marker.getBytes(StandardCharsets.UTF_8))).as(marker).isEqualTo(-1);
		}
		// it is exactly the archive, sealed with the user's data key and bound to this export's row
		byte[] opened = encryption.decrypt(u, DataExportJob.context(id), sealed);
		assertThat(json.readTree(opened)).isEqualTo(archive);
		assertThatThrownBy(() -> encryption.decrypt(u, DataExportJob.context(UUID.randomUUID()), sealed))
				.isInstanceOf(CiphertextRejectedException.class);
		assertThatThrownBy(() -> encryption.decrypt(user("REGISTERED"), DataExportJob.context(id), sealed))
				.isInstanceOf(RuntimeException.class);
		// the bucket is private: no anonymous read
		HttpResponse<Void> anonymous = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(endpoint() + "/" + BUCKET + "/" + key)).GET().build(),
				HttpResponse.BodyHandlers.discarding());
		assertThat(anonymous.statusCode()).isIn(401, 403);
		// the client only ever gets the API path, never a bucket URL
		exportStatus(bearer, id).andExpect(jsonPath("$.downloadUrl").value(org.hamcrest.Matchers.not(
				org.hamcrest.Matchers.containsString(BUCKET))));
	}

	static int indexOf(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	// ---------------------------------------------------------------------------------------------- expiry

	@Test
	void theLinkExpiresAndTheObjectIsDeleted() throws Exception {
		UUID u = user("REGISTERED");
		consent(u, "TERMS_ACCEPTANCE", true);
		String bearer = bearer(u);
		UUID id = exported(bearer);
		String key = jdbc.queryForObject("SELECT export_object_key FROM data_export_requests WHERE id = ?", String.class, id);
		assertThat(store.get(key, 1 << 20)).isPresent();
		download(bearer, id);
		assertThat(jdbc.queryForObject("SELECT request_status FROM data_export_requests WHERE id = ?", String.class, id))
				.isEqualTo("DOWNLOADED");
		exportStatus(bearer, id).andExpect(jsonPath("$.status").value("READY")); // the contract has no DOWNLOADED

		// one second before the end of the link: still there
		jdbc.update("UPDATE data_export_requests SET download_expires_at = now() + interval '1 second' WHERE id = ?", id);
		archive(bearer, id).andExpect(status().isOk());
		assertThat(job.expireFinished(Instant.now())).isZero();

		// after it: expired at once (before the sweeper runs), no URL, no download
		jdbc.update("UPDATE data_export_requests SET download_expires_at = now() - interval '1 second' WHERE id = ?", id);
		exportStatus(bearer, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"))
				.andExpect(jsonPath("$.downloadUrl").doesNotExist());
		archive(bearer, id).andExpect(status().isNotFound());
		assertThat(store.get(key, 1 << 20)).isPresent(); // the sweeper has not run yet

		assertThat(job.runOnce()).isGreaterThanOrEqualTo(1);
		assertThat(store.get(key, 1 << 20)).isEmpty();
		assertThat(jdbc.queryForMap("SELECT request_status, export_object_key FROM data_export_requests WHERE id = ?", id))
				.containsEntry("request_status", "EXPIRED").containsEntry("export_object_key", null);
		exportStatus(bearer, id).andExpect(jsonPath("$.status").value("EXPIRED"));
		// and a new export is possible
		exported(bearer);
	}

	// ---------------------------------------------------------------------------------------------- ownership

	@Test
	void anotherUserGetsNotFound() throws Exception {
		UUID owner = user("REGISTERED");
		UUID other = user("REGISTERED");
		String ownerBearer = bearer(owner);
		UUID id = exported(ownerBearer);
		String otherBearer = bearer(other);

		for (ResultActions r : List.of(exportStatus(otherBearer, id), archive(otherBearer, id), exportStatus(otherBearer, UUID.randomUUID()),
				archive(otherBearer, UUID.randomUUID()))) {
			r.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}
		// a not-yet-ready export of the owner is not downloadable either
		UUID pending = id(requestExport(ownerBearer).andExpect(status().isAccepted()));
		archive(ownerBearer, pending).andExpect(status().isNotFound());
		// no token: 401
		mvc.perform(get("/api/v1/me/data-exports/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me/data-exports/" + id + "/archive")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/me/data-exports")).andExpect(status().isUnauthorized());
		// the other user's own export contains only their data
		UUID otherId = exported(otherBearer);
		assertThat(download(otherBearer, otherId).get("manifest").get("userId").asString()).isEqualTo(other.toString());
	}

	// ---------------------------------------------------------------------------------------------- rate limit

	@Test
	void oneExportInFlightAndAtMostThreePerDay() throws Exception {
		UUID u = user("REGISTERED");
		String bearer = bearer(u);
		// one in flight: asking again returns the open request and creates no second row
		UUID first = id(requestExport(bearer).andExpect(status().isAccepted()));
		requestExport(bearer).andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(first.toString()));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM data_export_requests WHERE user_id = ?", Integer.class, u))
				.isEqualTo(1);
		job.runOnce();
		// a finished export no longer blocks: two more are allowed, the fourth in 24 hours is not
		for (int i = 0; i < 2; i++) {
			UUID next = id(requestExport(bearer).andExpect(status().isAccepted()));
			assertThat(next).isNotEqualTo(first);
			job.runOnce();
		}
		MvcResult limited = requestExport(bearer).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().exists("Retry-After")).andReturn();
		long retry = Long.parseLong(limited.getResponse().getHeader("Retry-After"));
		assertThat(retry).isBetween(60L * 60 * 23, 24L * 60 * 60 + 5);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM data_export_requests WHERE user_id = ?", Integer.class, u))
				.isEqualTo(3);
		// other users are not affected; the window slides
		requestExport(bearer(user("REGISTERED"))).andExpect(status().isAccepted());
		jdbc.update("UPDATE data_export_requests SET requested_at = requested_at - interval '25 hours' WHERE user_id = ?", u);
		requestExport(bearer).andExpect(status().isAccepted());
	}

	@Test
	void formatAndGuestsAndStaleJobs() throws Exception {
		UUID guest = user("GUEST");
		String bearer = "Bearer " + tokens.issue(guest, UUID.randomUUID(), null, "GUEST").value();
		mvc.perform(post("/api/v1/me/data-exports").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
				.content("{\"format\":\"CSV\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		UUID id = id(mvc.perform(post("/api/v1/me/data-exports").header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON).content("{\"format\":\"JSON\"}")).andExpect(status().isAccepted()));
		// an instance died while building: the export is queued again once it has been untouched for stale-after
		jdbc.update("UPDATE data_export_requests SET request_status = 'PROCESSING' WHERE id = ?", id);
		job.runOnce(Instant.now()); // just started: left alone
		assertThat(jdbc.queryForObject("SELECT request_status FROM data_export_requests WHERE id = ?", String.class, id))
				.isEqualTo("PROCESSING");
		job.runOnce(Instant.now().plus(Duration.ofHours(1)));
		exportStatus(bearer, id).andExpect(jsonPath("$.status").value("READY"));
		JsonNode archive = download(bearer, id);
		assertThat(row(archive, "account", 0).get("accountType").asString()).isEqualTo("GUEST");
		assertThat(archive.get("manifest").get("sections").size()).isEqualTo(EXPECTED.size());
	}

	// ---------------------------------------------------------------------------------------------- deletion

	@Test
	void deletionAndCryptoShreddingMakeExportsUnreadableAndAClosedAccountCannotExport() throws Exception {
		UUID u = user("REGISTERED");
		seedEverything(u);
		String bearer = bearer(u);
		UUID finished = exported(bearer);
		String key = jdbc.queryForObject("SELECT export_object_key FROM data_export_requests WHERE id = ?", String.class,
				finished);
		byte[] sealed = store.get(key, 64L << 20).orElseThrow();
		UUID pending = id(requestExport(bearer).andExpect(status().isAccepted())); // queued, not built yet

		// during the grace period the person can still take their data with them
		MvcResult deletion = mvc.perform(post("/api/v1/me/deletion-request").header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}")).andExpect(status().isAccepted())
				.andReturn();
		UUID deletionId = UUID.fromString(json.readTree(deletion.getResponse().getContentAsString()).get("id").asString());
		Instant purgeAt = Instant.parse(json.readTree(deletion.getResponse().getContentAsString()).get("scheduledPurgeAt")
				.asString());
		job.runOnce();
		exportStatus(bearer, pending).andExpect(jsonPath("$.status").value("READY"));
		download(bearer, pending);
		assertThat(download(bearer, finished).get("manifest").get("userId").asString()).isEqualTo(u.toString());
		UUID stillPending = id(requestExport(bearer).andExpect(status().isAccepted())); // rate limit allows a third

		// step 1 of the deletion: account closed, keys shredded. The queued export fails, nothing is stored for it
		Instant later = purgeAt.plus(Duration.ofHours(1));
		assertThat(deletionExecutor.step(deletionId, later)).isEqualTo(DeletionExecutor.Step.SHREDDED);
		assertThatThrownBy(() -> encryption.decrypt(u, DataExportJob.context(finished), sealed))
				.isInstanceOf(CryptoShreddedException.class); // the stored object is unreadable even where a copy survives
		job.runOnce();
		assertThat(jdbc.queryForMap("SELECT request_status, export_object_key, processing_error FROM data_export_requests "
				+ "WHERE id = ?", stillPending)).containsEntry("request_status", "FAILED")
				.containsEntry("export_object_key", null).containsEntry("processing_error", "ACCOUNT_CLOSED");
		// the closed account's token is refused and the service refuses on its own account
		exportStatus(bearer, finished).andExpect(status().isUnauthorized());
		requestExport(bearer).andExpect(status().isUnauthorized());
		assertThatThrownBy(() -> service.request(u)).isInstanceOf(ApiException.class);
		assertThat(store.get(key, 64L << 20)).isPresent(); // the (unreadable) object is still there until the purge

		// steps 2 and 3: the purge deletes the rows and the archive objects
		assertThat(deletionExecutor.process(deletionId, later)).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM data_export_requests WHERE user_id = ?", Integer.class, u))
				.isZero();
		assertThat(store.get(key, 64L << 20)).isEmpty();
	}

	// ---------------------------------------------------------------------------------------------- logs

	@Test
	void logsNeverContainExportedValues() throws Exception {
		UUID u = user("REGISTERED");
		seedEverything(u);
		String bearer = bearer(u);
		List<String> secrets = List.of(NAME, emailOf(u), LABEL, SIGNOFF, PHRASES, WORD_A, WORD_B, HISTORY_IN, HISTORY_OUT,
				COMMENT, CONTENT_IN, CONTENT_OUT, GLOSSARY_TERM, HASH, "push-token-secret", "Selam Org");
		try (CapturedLogs logs = new CapturedLogs()) {
			UUID id = exported(bearer);
			download(bearer, id);
			// a failing export logs a class name, not values
			jdbc.update("UPDATE ai_feedback SET comment_ciphertext = ? WHERE user_id = ?", new byte[] { 9, 9, 9, 9 }, u);
			jdbc.update("DELETE FROM data_export_requests WHERE user_id = ? AND request_status <> 'READY'", u);
			jdbc.update("UPDATE data_export_requests SET requested_at = requested_at - interval '25 hours' WHERE user_id = ?", u);
			UUID failing = id(requestExport(bearer).andExpect(status().isAccepted()));
			job.runOnce();
			assertThat(jdbc.queryForObject("SELECT request_status FROM data_export_requests WHERE id = ?", String.class,
					failing)).isEqualTo("FAILED");
			exportStatus(bearer, failing).andExpect(jsonPath("$.status").value("FAILED"));
			assertThat(logs.texts()).isNotEmpty().noneMatch(text -> secrets.stream().anyMatch(text::contains));
			assertThat(logs.texts()).anyMatch(text -> text.contains(id.toString()));
		}
	}

	@Test
	void sealedValueIsNotAcceptedByAnotherExportRow() {
		// the archive ciphertext is bound to (table, column, export id): a copy under another export id fails to open
		UUID u = user("REGISTERED");
		UUID exportA = UUID.randomUUID();
		EncryptedValue sealed = encryption.encryptString(u, DataExportJob.context(exportA), "{}");
		assertThat(encryption.decryptString(u, DataExportJob.context(exportA), sealed.ciphertext())).isEqualTo("{}");
		assertThatThrownBy(() -> encryption.decryptString(u, DataExportJob.context(UUID.randomUUID()), sealed.ciphertext()))
				.isInstanceOf(CiphertextRejectedException.class);
	}
}
