package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.Behavior;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.FailureMode;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreakers;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.privacy.RetentionJob;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.instrument.Meter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B5.6 "done when": AI feedback end to end on PostgreSQL 18 with the mock providers, through the security chain, controller,
 * pipeline and consents, every response validated against {@code contracts/openapi.yaml}: one rating per request,
 * ownership (404), the {@code FEEDBACK_TEXT} gate for comment and content, ciphertext at rest bound to column and row,
 * the rate limit, and the log scrub of the comment.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s",
		"qal.ai.registry-cache-ttl=0s", "qal.ai.routing.policy-cache-ttl=0s", "qal.ai.routing.kill-switch-cache-ttl=0s",
		"qal.ai.routing.cost-cache-ttl=0s", "qal.ai.routing.attempt-timeout=1s", "qal.ai.routing.deadline=3s",
		"qal.ai.pipeline.deadline=3s", "qal.ai.pipeline.catalog-cache-ttl=0s" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
@Import(AiPipelineIT.Adapters.class)
class AiFeedbackIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final String IN_EN = "Zebra lighthouse marmalade";
	static final String OUT_MOCK = "[mock:om] " + IN_EN;
	static final String COMMENT = "Pelican turbine saffron";
	static final String CONTENT_IN = "Walrus ornament cinnamon";
	static final String CONTENT_OUT = "Heron compass nutmeg";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	CircuitBreakers breakers;
	@Autowired
	io.micrometer.core.instrument.MeterRegistry meters;
	@Autowired
	@Qualifier("mockAiProvider")
	MockAiProvider mock;
	@Autowired
	AiPipelineIT.ScriptedProvider fallback;
	@Autowired
	UserDataEncryption encryption;
	@Autowired
	RetentionJob retention;
	@Autowired
	JsonMapper json;

	@BeforeEach
	void enable() {
		clean();
		jdbc.update("UPDATE ai_providers SET provider_status = 'ACTIVE', dpa_signed_at = NOW() "
				+ "WHERE provider_code IN ('mock', 'wiremock-http')");
		mock.setBehavior(Behavior.ok());
		fallback.reset();
		breakers.resetAll();
		template("TRANSLATE", "You translate from {{sourceLanguageName}} into {{destinationLanguageName}}. "
				+ "{{addresseeInstruction}} {{calendarContext}}");
		template("GRAMMAR_CORRECTION", "Correct grammar ({{mode}}). {{calendarContext}}");
	}

	@AfterEach
	void clean() {
		jdbc.update("DELETE FROM ai_routing_policies");
		jdbc.update("DELETE FROM ai_prompt_templates");
		jdbc.update("UPDATE ai_providers SET provider_status = 'DISABLED', dpa_signed_at = NULL "
				+ "WHERE provider_code IN ('mock', 'wiremock-http')");
	}

	void template(String feature, String body) {
		jdbc.update("""
				INSERT INTO ai_prompt_templates (feature_code, template_version, language_scope, model_family,
				       template_body, evaluation_status, is_active)
				VALUES (?, 1, '*', '*', ?, 'APPROVED', TRUE)""", feature, body);
	}

	// ======================================================================================= helpers

	UUID user(String type) {
		return jdbc.queryForObject("""
				INSERT INTO users (account_type, account_status, time_zone) VALUES (?, 'ACTIVE', 'Africa/Addis_Ababa')
				RETURNING id""", UUID.class, type);
	}

	String bearer(UUID user) {
		return bearer(user, "REGISTERED");
	}

	String bearer(UUID user, String type) {
		return "Bearer " + tokens.issue(user, UUID.randomUUID(), null, type).value();
	}

	static String key() {
		return UUID.randomUUID().toString();
	}

	void consent(UUID user, String code, boolean granted) throws Exception {
		mvc.perform(put("/api/v1/me/consents/" + code).header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"1\",\"isGranted\":" + granted + "}")).andExpect(status().isOk());
	}

	/** History preference row (B3.3 stores it); {@code days} the retention. */
	void history(UUID user, boolean enabled, int days) {
		jdbc.update("""
				INSERT INTO user_preferences (user_id, ai_history_enabled, history_retention_days) VALUES (?, ?, ?)
				ON CONFLICT (user_id) DO UPDATE SET ai_history_enabled = EXCLUDED.ai_history_enabled,
				       history_retention_days = EXCLUDED.history_retention_days""", user, enabled, days);
	}

	/** A user with the preference, the consent and the default plan: everything a history write needs. */
	UUID historyUser() throws Exception {
		UUID user = user("REGISTERED");
		consent(user, "SAVE_AI_HISTORY", true);
		history(user, true, 30);
		return user;
	}

	ResultActions translate(UUID user, String key, boolean save) throws Exception {
		return translate(user, "REGISTERED", key, IN_EN, save);
	}

	ResultActions translate(UUID user, String type, String key, String text, boolean save) throws Exception {
		return mvc.perform(post("/api/v1/ai/translate").header("Authorization", bearer(user, type))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(("{\"text\":\"" + text + "\",\"sourceLanguage\":\"en\",\"destinationLanguage\":\"om\""
						+ (save ? ",\"saveToHistory\":true" : "") + "}").getBytes(StandardCharsets.UTF_8)));
	}

	JsonNode ok(ResultActions r) throws Exception {
		r.andExpect(status().isOk());
		return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	/** One successful translation; returns its response. */
	JsonNode translated(UUID user, boolean save) throws Exception {
		return ok(translate(user, key(), save));
	}

	long historyRows(UUID user) {
		return jdbc.queryForObject("SELECT count(*) FROM user_history_items WHERE user_id = ?", Long.class, user);
	}

	long feedbackRows(UUID user) {
		return jdbc.queryForObject("SELECT count(*) FROM ai_feedback WHERE user_id = ?", Long.class, user);
	}

	ResultActions feedback(UUID user, String body) throws Exception {
		return mvc.perform(post("/api/v1/ai/feedback").header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)));
	}

	static String feedbackBody(Object requestId, String rating, String extra) {
		return "{\"aiRequestId\":\"" + requestId + "\",\"rating\":\"" + rating + "\"" + extra + "}";
	}

	ResultActions listHistory(UUID user, String query) throws Exception {
		return mvc.perform(get("/api/v1/me/history" + query).header("Authorization", bearer(user)));
	}

	EncryptionContext ctx(String table, String column, Object row) {
		return EncryptionContext.of(table, column, row.toString());
	}

	// ======================================================================================= B5.6 feedback

	@Test
	void feedbackIsOneRatingPerRequestAndMetadataOnly() throws Exception {
		UUID user = user("REGISTERED");
		String requestId = translated(user, false).get("requestId").asString();

		feedback(user, feedbackBody(requestId, "POSITIVE", "")).andExpect(status().isAccepted());
		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_feedback WHERE user_id = ?", user);
		assertThat(((Number) row.get("rating")).intValue()).isEqualTo(1);
		assertThat(row.get("feature_code")).isEqualTo("TRANSLATE");
		assertThat(row.get("comment_ciphertext")).isNull();
		assertThat(row.get("input_ciphertext")).isNull();
		assertThat(row.get("consent_record_id")).isNull();

		// the same request again replaces the rating (idempotent per request)
		feedback(user, feedbackBody(requestId, "NEGATIVE", ",\"reasonCodes\":[\"WRONG_TONE\",\"WRONG_GENDER_OR_FORMALITY\",\"TOO_SLOW\"]"))
				.andExpect(status().isAccepted());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_feedback WHERE user_id = ?", Long.class, user))
				.isEqualTo(1L);
		row = jdbc.queryForMap("SELECT * FROM ai_feedback WHERE user_id = ?", user);
		assertThat(((Number) row.get("rating")).intValue()).isEqualTo(-1);
		assertThat(jdbc.queryForObject("SELECT array_to_string(reason_codes, ',') FROM ai_feedback WHERE user_id = ?",
				String.class, user)).isEqualTo("WRONG_TONE,WRONG_GENDER_OR_FORMALITY,TOO_SLOW");
	}

	@Test
	void feedbackNeedsAnOwnedSucceededRequest() throws Exception {
		UUID owner = user("REGISTERED");
		UUID other = user("REGISTERED");
		String requestId = translated(owner, false).get("requestId").asString();

		feedback(other, feedbackBody(requestId, "POSITIVE", "")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		feedback(owner, feedbackBody(UUID.randomUUID(), "POSITIVE", "")).andExpect(status().isNotFound());
		// a failed request cannot be rated
		mock.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		fallback.delegate.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		translate(owner, key(), false).andExpect(status().isServiceUnavailable());
		UUID failed = jdbc.queryForObject(
				"SELECT id FROM ai_requests WHERE user_id = ? AND request_status <> 'SUCCEEDED' LIMIT 1", UUID.class, owner);
		feedback(owner, feedbackBody(failed, "POSITIVE", "")).andExpect(status().isNotFound());
		assertThat(feedbackRows(owner)).isZero();
	}

	@Test
	void feedbackValidationNamesFieldsOnly() throws Exception {
		UUID user = user("REGISTERED");
		String requestId = translated(user, false).get("requestId").asString();
		for (String body : List.of("{}", "not json " + COMMENT, feedbackBody(requestId, "MAYBE", ""),
				feedbackBody(requestId, "POSITIVE", ",\"reasonCodes\":[\"" + COMMENT + "\"]"),
				feedbackBody("nope", "POSITIVE", ""), feedbackBody(requestId, "POSITIVE", ",\"comment\":5"),
				feedbackBody(requestId, "POSITIVE", ",\"comment\":\"" + "x".repeat(1001) + "\""),
				feedbackBody(requestId, "POSITIVE", ",\"includeContent\":\"" + COMMENT + "\""))) {
			String response = feedback(user, body).andExpect(status().isBadRequest())
					.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
			assertThat(response).doesNotContain(COMMENT);
		}
		assertThat(feedbackRows(user)).isZero();
	}

	@Test
	void commentAndContentNeedTheFeedbackTextConsent() throws Exception {
		UUID user = user("REGISTERED");
		String requestId = translated(user, false).get("requestId").asString();

		// no consent: 403 with the consent code, nothing stored (not even the rating)
		feedback(user, feedbackBody(requestId, "NEGATIVE", ",\"comment\":\"" + COMMENT + "\""))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
				.andExpect(jsonPath("$.consentCode").value("FEEDBACK_TEXT"));
		feedback(user, feedbackBody(requestId, "NEGATIVE", ",\"includeContent\":true"))
				.andExpect(status().isForbidden());
		assertThat(feedbackRows(user)).isZero();
		// the rating alone needs no consent
		feedback(user, feedbackBody(requestId, "NEGATIVE", ",\"inputText\":\"" + CONTENT_IN + "\"")) // ignored: no includeContent
				.andExpect(status().isAccepted());
		assertThat(jdbc.queryForObject("SELECT input_ciphertext IS NULL FROM ai_feedback WHERE user_id = ?", Boolean.class, user)).isTrue();

		consent(user, "FEEDBACK_TEXT", true);
		feedback(user, feedbackBody(requestId, "NEGATIVE", ",\"comment\":\"" + COMMENT + "\",\"includeContent\":true,"
				+ "\"inputText\":\"" + CONTENT_IN + "\",\"outputText\":\"" + CONTENT_OUT + "\"")).andExpect(status().isAccepted());

		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_feedback WHERE user_id = ?", user);
		UUID id = (UUID) row.get("id");
		assertThat(row.get("consent_record_id")).isNotNull();
		assertThat(jdbc.queryForObject("""
				SELECT c.is_granted AND c.consent_code = 'FEEDBACK_TEXT' FROM ai_feedback f
				JOIN user_consents c ON c.id = f.consent_record_id AND c.user_id = f.user_id""", Boolean.class)).isTrue();
		byte[] comment = (byte[]) row.get("comment_ciphertext");
		byte[] input = (byte[]) row.get("input_ciphertext");
		byte[] output = (byte[]) row.get("output_ciphertext");
		// sealed per column and row with the user's key
		assertThat(encryption.decryptString(user, ctx("ai_feedback", "comment_ciphertext", id), comment)).isEqualTo(COMMENT);
		assertThat(encryption.decryptString(user, ctx("ai_feedback", "input_ciphertext", id), input)).isEqualTo(CONTENT_IN);
		assertThat(encryption.decryptString(user, ctx("ai_feedback", "output_ciphertext", id), output)).isEqualTo(CONTENT_OUT);
		assertThatThrownBy(() -> encryption.decryptString(user, ctx("ai_feedback", "input_ciphertext", id), comment))
				.isInstanceOf(CiphertextRejectedException.class);
		assertNoTableHolds(List.of(COMMENT, CONTENT_IN, CONTENT_OUT));

		// resending without words replaces them
		feedback(user, feedbackBody(requestId, "POSITIVE", "")).andExpect(status().isAccepted());
		row = jdbc.queryForMap("SELECT * FROM ai_feedback WHERE user_id = ?", user);
		assertThat(row.get("comment_ciphertext")).isNull();
		assertThat(row.get("output_ciphertext")).isNull();
		assertThat(row.get("key_version")).isNull();

		// withdrawal: new words are refused again
		consent(user, "FEEDBACK_TEXT", false);
		feedback(user, feedbackBody(requestId, "POSITIVE", ",\"comment\":\"" + COMMENT + "\"")).andExpect(status().isForbidden());
	}

	@Test
	void feedbackIsRateLimited() throws Exception {
		UUID user = user("REGISTERED");
		for (int i = 0; i < 60; i++) {
			feedback(user, feedbackBody(UUID.randomUUID(), "POSITIVE", "")).andExpect(status().isNotFound());
		}
		feedback(user, feedbackBody(UUID.randomUUID(), "POSITIVE", "")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
	}

	@Test
	void feedbackCommentNeverReachesLogsMetricsOrPlaintextColumns() throws Exception {
		UUID user = user("REGISTERED");
		consent(user, "FEEDBACK_TEXT", true);
		String requestId = translated(user, false).get("requestId").asString();
		Logger web = (Logger) LoggerFactory.getLogger("org.springframework.web");
		Logger http = (Logger) LoggerFactory.getLogger("org.springframework.http");
		Level webLevel = web.getLevel();
		Level httpLevel = http.getLevel();
		web.setLevel(Level.DEBUG);
		http.setLevel(Level.DEBUG);
		List<String> lines;
		try (CapturedLogs logs = new CapturedLogs()) {
			feedback(user, feedbackBody(requestId, "NEGATIVE", ",\"comment\":\"" + COMMENT + "\",\"includeContent\":true,"
					+ "\"inputText\":\"" + CONTENT_IN + "\",\"outputText\":\"" + CONTENT_OUT + "\"")).andExpect(status().isAccepted());
			feedback(user, feedbackBody(requestId, "BAD", ",\"comment\":\"" + COMMENT + "\"")).andExpect(status().isBadRequest());
			feedback(user, feedbackBody(requestId, "POSITIVE", ",\"comment\":\"" + COMMENT + "\",\"includeContent\":\"x\""))
					.andExpect(status().isBadRequest());
			feedback(user, "{\"comment\":\"" + COMMENT).andExpect(status().isBadRequest());
			feedback(user, feedbackBody(UUID.randomUUID(), "POSITIVE", ",\"comment\":\"" + COMMENT + "\"")).andExpect(status().isNotFound());
			UUID stranger = user("REGISTERED");
			feedback(stranger, feedbackBody(requestId, "POSITIVE", ",\"comment\":\"" + COMMENT + "\"")).andExpect(status().isNotFound());
			lines = logs.texts();
			assertThat(logs.size()).isPositive();
		}
		finally {
			web.setLevel(webLevel);
			http.setLevel(httpLevel);
		}
		for (String line : lines) {
			for (String secret : List.of(COMMENT, CONTENT_IN, CONTENT_OUT)) {
				assertThat(line).as("log line").doesNotContain(secret);
			}
		}
		for (Meter m : meters.getMeters()) {
			assertThat(m.getId().getName()).doesNotContain("Pelican");
			m.getId().getTags().forEach(t -> assertThat(t.getKey() + "=" + t.getValue()).doesNotContain("Pelican"));
		}
		assertNoTableHolds(List.of(COMMENT, CONTENT_IN, CONTENT_OUT));
	}

	// ======================================================================================= scan

	void assertNoTableHolds(List<String> secrets) {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
				String.class);
		assertThat(tables).contains("ai_requests", "ai_feedback", "user_history_items", "ai_replay_cache", "audit_events");
		for (String table : tables) {
			for (String secret : secrets) {
				String hex = HexFormat.of().formatHex(secret.getBytes(StandardCharsets.UTF_8));
				Long hits = jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\" t WHERE t::text LIKE ? "
						+ "OR lower(t::text) LIKE ?", Long.class, "%" + secret + "%", "%" + hex + "%");
				assertThat(hits).as("rows of " + table + " holding the text").isZero();
			}
		}
	}
}
