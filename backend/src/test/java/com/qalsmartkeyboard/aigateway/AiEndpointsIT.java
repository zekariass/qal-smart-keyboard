package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.Behavior;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.FailureMode;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreakers;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;
import com.qalsmartkeyboard.usage.Metering;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

/**
 * B5.5 "done when", end to end on PostgreSQL 18 with the B5.1 mock providers, through the real security chain, the
 * controllers, the pipeline, metering and the replay cache, every response validated against
 * {@code contracts/openapi.yaml}:
 * <ul>
 * <li>every operation (translate, transform GRAMMAR_CORRECTION / REWRITE / TONE_ADJUST, detect-language,
 * capabilities) with its metering, replay and {@code ai_requests} metadata;</li>
 * <li>every error mapping reachable through HTTP (validation, entitlement, deletion pending, ON_DEVICE_ONLY, quota,
 * idempotency reuse, provider failures, content refusal, guardrail failure, unsupported pair);</li>
 * <li>the <b>log scrub</b>: distinctive input and provider output text never reaches a log line (framework loggers
 * raised to DEBUG), an exception message, a Problem body, a metric name or tag, or any column of any table
 * (plaintext, or the hex of its bytes inside a {@code bytea}), including the ledger, outbox, audit and replay
 * cache.</li>
 * </ul>
 * Distinct text per direction: the mock answers {@code "[mock:<dst>] <input>"}; the scripted fallback provider answers
 * with a distinct sentence of its own.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s",
		"qal.ai.registry-cache-ttl=0s", "qal.ai.routing.policy-cache-ttl=0s", "qal.ai.routing.kill-switch-cache-ttl=0s",
		"qal.ai.routing.cost-cache-ttl=0s", "qal.ai.routing.attempt-timeout=1s", "qal.ai.routing.deadline=3s",
		"qal.ai.pipeline.deadline=3s", "qal.ai.pipeline.catalog-cache-ttl=0s" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
@Import(AiPipelineIT.Adapters.class)
class AiEndpointsIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	/** User text: no digits (the guardrails protect numbers), distinctive words. */
	static final String IN_EN = "Zebra lighthouse marmalade";
	static final String IN_AM = "ሚስጥራዊ ቃል ጽሑፍ";
	/** What the scripted fallback provider answers (provider output text). */
	static final String OUT_SCRIPTED = "Quokka velvet harbour";
	static final String OUT_MOCK = "[mock:om] " + IN_EN;
	static final List<String> SECRETS = List.of(IN_EN, IN_AM, OUT_SCRIPTED, OUT_MOCK, "Quokka", "lighthouse");

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	CircuitBreakers breakers;
	@Autowired
	MeterRegistry meters;
	@Autowired
	@Qualifier("mockAiProvider")
	MockAiProvider mock;
	@Autowired
	AiPipelineIT.ScriptedProvider fallback;
	@Autowired
	Metering metering;

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
		template("REWRITE", "Rewrite the text ({{mode}}).");
		template("TONE_ADJUST", "Adjust the tone to {{tone}}.");
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

	UUID user(String status) {
		return jdbc.queryForObject("""
				INSERT INTO users (account_type, account_status, time_zone) VALUES ('REGISTERED', ?, 'Africa/Addis_Ababa')
				RETURNING id""", UUID.class, status);
	}

	String bearer(UUID user) {
		return "Bearer " + tokens.issue(user, UUID.randomUUID(), null, "REGISTERED").value();
	}

	static String key() {
		return UUID.randomUUID().toString();
	}

	static String json(String text) {
		return "\"" + text + "\"";
	}

	ResultActions send(UUID user, String path, String key, String body) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/ai/" + path).header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mvc.perform(request);
	}

	String translateBody(String text, String from, String to) {
		return "{\"text\":" + json(text) + (from == null ? "" : ",\"sourceLanguage\":\"" + from + "\"")
				+ ",\"destinationLanguage\":\"" + to + "\"}";
	}

	String transformBody(String task, String text, String language, String options) {
		return "{\"task\":\"" + task + "\",\"text\":" + json(text) + ",\"language\":\"" + language + "\",\"options\":"
				+ options + "}";
	}

	String detectBody(String text) {
		return "{\"text\":" + json(text) + "}";
	}

	long used(UUID user, String feature) {
		return jdbc.query("SELECT used_units FROM usage_counters WHERE user_id = ? AND feature_code = ?",
				rs -> rs.next() ? rs.getLong(1) : 0L, user, feature);
	}

	String reservationStatus(UUID user, String key) {
		return jdbc.queryForObject(
				"SELECT reservation_status FROM usage_reservations WHERE user_id = ? AND idempotency_key = ?",
				String.class, user, key);
	}

	// ======================================================================================= happy paths

	@Test
	void everyOperationWorksEndToEndThroughHttp() throws Exception {
		UUID user = user("ACTIVE");

		// translate: charged once, metadata only, replay answers from the encrypted cache
		String k = key();
		String requestId = send(user, "translate", k, translateBody(IN_EN, "en", "om")).andExpect(status().isOk())
				.andExpect(header().string("RateLimit-Limit", "30")).andExpect(jsonPath("$.translatedText").value(OUT_MOCK))
				.andExpect(jsonPath("$.replayed").value(false)).andExpect(jsonPath("$.qualityTier").value("BETA"))
				.andExpect(jsonPath("$.usage.unitsCharged").value(1)).andExpect(jsonPath("$.usage.remaining").value(9))
				.andExpect(jsonPath("$.notes[0].type").value("QUALITY")).andReturn().getResponse()
				.getContentAsString();
		String id = com.jayway.jsonpath.JsonPath.read(requestId, "$.requestId");
		assertThat(reservationStatus(user, k)).isEqualTo("SETTLED");
		assertThat(used(user, "TRANSLATE")).isEqualTo(1);
		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_requests WHERE id = ?", UUID.fromString(id));
		assertThat(row).containsEntry("feature_code", "TRANSLATE").containsEntry("request_status", "SUCCEEDED")
				.containsEntry("surface", "KEYBOARD").containsEntry("content_retained", false)
				.containsEntry("usage_source", "PLAN_ALLOWANCE").containsEntry("idempotency_key", k);
		assertThat(row.get("duration_ms")).isNotNull();

		send(user, "translate", k, translateBody(IN_EN, "en", "om")).andExpect(status().isOk())
				.andExpect(jsonPath("$.replayed").value(true)).andExpect(jsonPath("$.requestId").value(id))
				.andExpect(jsonPath("$.usage.unitsCharged").value(1)).andExpect(jsonPath("$.usage.remaining").value(9));
		assertThat(used(user, "TRANSLATE")).isEqualTo(1);
		send(user, "translate", k, translateBody("Another text entirely", "en", "om"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		send(user, "translate", null, translateBody(IN_EN, "en", "om")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		// auto-detected source, Amharic destination profile fields are optional
		send(user, "translate", key(), translateBody(IN_EN, null, "om")).andExpect(status().isOk())
				.andExpect(jsonPath("$.detectedSourceLanguage").value("en"))
				.andExpect(jsonPath("$.detectionConfidence").isNumber());

		// transform: grammar, rewrite, tone
		send(user, "transform", key(), transformBody("GRAMMAR_CORRECTION", "  " + IN_AM + "   ነው ", "am",
				"{\"mode\":\"GRAMMAR\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.task").value("GRAMMAR_CORRECTION"))
				.andExpect(jsonPath("$.resultText").value(IN_AM + " ነው"));
		send(user, "transform", key(), transformBody("REWRITE", IN_EN, "en", "{\"mode\":\"NATURAL\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.task").value("REWRITE"))
				.andExpect(jsonPath("$.usage.remaining").value(2)); // FREE: 3 rewrites a day
		// the FREE plan has no TONE_ADJUST: a plan refusal, nothing reserved
		send(user, "transform", key(), transformBody("TONE_ADJUST", IN_EN, "en", "{\"tone\":\"POLITE\"}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FEATURE_NOT_ENTITLED"));
		assertThat(used(user, "GRAMMAR_CORRECTION")).isEqualTo(1);
		send(user, "transform", key(), transformBody("SCRIPT_CONVERSION", IN_EN, "en", "{}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_AVAILABLE"));
		// ADR 0027: SUMMARIZE is served now, but not on the FREE plan
		send(user, "transform", key(), transformBody("SUMMARIZE", IN_EN, "en", "{}")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FEATURE_NOT_ENTITLED"));
		send(user, "transform", key(), transformBody("TONE_ADJUST", IN_EN, "en", "{}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("options.tone"));

		// detect: unmetered, no key
		send(user, "detect-language", null, detectBody(IN_AM)).andExpect(status().isOk())
				.andExpect(jsonPath("$.languages[0].languageCode").value("am")).andExpect(jsonPath("$.usage").doesNotExist());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_requests WHERE user_id = ? AND feature_code = "
				+ "'DETECT_LANGUAGE' AND usage_source = 'UNMETERED'", Long.class, user)).isOne();

		// capabilities: anonymous and signed in, from the enabled providers' BETA capabilities
		mvc.perform(get("/api/v1/ai/capabilities")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.featureCode=='TRANSLATE' && @.sourceLanguage=='en' "
						+ "&& @.destinationLanguage=='am')].qualityTier").value("BETA"))
				.andExpect(jsonPath("$.items[?(@.featureCode=='DETECT_LANGUAGE')]").isNotEmpty())
				.andExpect(jsonPath("$.items[?(@.featureCode=='SUMMARIZE')]").isNotEmpty()) // V20 (ADR 0027)
				.andExpect(jsonPath("$.items[?(@.featureCode=='SCRIPT_CONVERSION')]").isEmpty());
		mvc.perform(get("/api/v1/ai/capabilities").header("Authorization", bearer(user))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.featureCode=='REWRITE')]").isNotEmpty());
		jdbc.update("UPDATE ai_providers SET provider_status = 'DISABLED', dpa_signed_at = NULL");
		mvc.perform(get("/api/v1/ai/capabilities")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
	}

	// ======================================================================================= error mappings

	@Test
	void everyErrorMapsToItsContractProblemAndNeverCharges() throws Exception {
		UUID user = user("ACTIVE");

		// provider failures: every provider down = PROVIDER_UNAVAILABLE, released, nothing charged
		mock.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		fallback.delegate.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		String k = key();
		send(user, "translate", k, translateBody(IN_EN, "en", "om")).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
		assertThat(reservationStatus(user, k)).isEqualTo("RELEASED");
		assertThat(used(user, "TRANSLATE")).isZero();
		// the same key answers the original failure again (a new key is a new attempt)
		send(user, "translate", k, translateBody(IN_EN, "en", "om")).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
		breakers.resetAll();
		mock.setBehavior(Behavior.failing(FailureMode.TIMEOUT));
		fallback.delegate.setBehavior(Behavior.failing(FailureMode.TIMEOUT));
		send(user, "translate", key(), translateBody(IN_EN, "en", "om")).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("PROVIDER_TIMEOUT"));
		breakers.resetAll();
		mock.setBehavior(Behavior.failing(FailureMode.CONTENT_REJECTED));
		send(user, "translate", key(), translateBody(IN_EN, "en", "om")).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("CONTENT_REJECTED"));
		breakers.resetAll();
		mock.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		fallback.delegate.setBehavior(Behavior.ok());
		fallback.translations.add(""); // the fallback answers with nothing: the output guardrail refuses it
		send(user, "translate", key(), translateBody(IN_EN, "en", "om")).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("OUTPUT_GUARDRAIL_FAILED"));
		assertThat(used(user, "TRANSLATE")).isZero();
		breakers.resetAll();
		mock.setBehavior(Behavior.ok());
		fallback.reset();
		// no capability for am -> ti
		send(user, "translate", key(), translateBody(IN_AM, "am", "ti")).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("LANGUAGE_PAIR_UNAVAILABLE"));

		// validation
		send(user, "translate", key(), translateBody(IN_EN, "en", "xx")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LANGUAGE_NOT_SUPPORTED"));
		send(user, "translate", key(), translateBody(IN_EN, "en", "en")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("SAME_LANGUAGE"));
		send(user, "translate", key(), translateBody("x".repeat(5001), "en", "om")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INPUT_TOO_LONG"));
		send(user, "detect-language", null, detectBody("x".repeat(1001))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INPUT_TOO_LONG"));
		send(user, "translate", key(), "{\"text\":" + json(IN_EN)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post("/api/v1/ai/translate").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key())
				.content("{}")).andExpect(status().isUnauthorized());

		// ON_DEVICE_ONLY (D33): refused before any charge, every operation
		UUID deviceOnly = user("ACTIVE");
		jdbc.update("INSERT INTO user_preferences (user_id, ai_processing_preference) VALUES (?, 'ON_DEVICE_ONLY')",
				deviceOnly);
		send(deviceOnly, "translate", key(), translateBody(IN_EN, "en", "om")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ON_DEVICE_ONLY_PREFERRED"));
		send(deviceOnly, "transform", key(), transformBody("REWRITE", IN_EN, "en", "{\"mode\":\"NATURAL\"}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ON_DEVICE_ONLY_PREFERRED"));
		send(deviceOnly, "detect-language", null, detectBody(IN_EN)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ON_DEVICE_ONLY_PREFERRED"));
		mvc.perform(get("/api/v1/ai/capabilities").header("Authorization", bearer(deviceOnly))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_reservations WHERE user_id = ?", Long.class,
				deviceOnly)).isZero();

		// deletion pending (D33): detection stays, everything else is refused
		UUID pending = user("DELETION_PENDING");
		send(pending, "detect-language", null, detectBody(IN_AM)).andExpect(status().isOk())
				.andExpect(jsonPath("$.languages[0].languageCode").value("am"));
		send(pending, "translate", key(), translateBody(IN_EN, "en", "om")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("DELETION_PENDING"));
		mvc.perform(get("/api/v1/ai/capabilities").header("Authorization", bearer(pending))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].featureCode").value("DETECT_LANGUAGE"));

		// quota: the FREE plan allows 10 translations a day
		UUID quota = user("ACTIVE");
		for (int i = 0; i < 10; i++) {
			send(quota, "translate", key(), translateBody(IN_EN, "en", "om")).andExpect(status().isOk());
		}
		send(quota, "translate", key(), translateBody(IN_EN, "en", "om")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED")).andExpect(jsonPath("$.featureCode").value("TRANSLATE"))
				.andExpect(jsonPath("$.resetAt").exists());
		assertThat(used(quota, "TRANSLATE")).isEqualTo(10);

		// in-flight duplicate: the reservation of the same key is still open
		UUID busy = user("ACTIVE");
		String open = key();
		metering.reserve(busy, "TRANSLATE", 1, open);
		send(busy, "translate", open, translateBody(IN_EN, "en", "om")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS")).andExpect(header().exists("Retry-After"));
	}

	// ======================================================================================= the log scrub

	@Test
	void noUserTextOrProviderOutputEverReachesLogsMetricsProblemsOrAnyTable() throws Exception {
		UUID user = user("ACTIVE");
		UUID pending = user("DELETION_PENDING");
		UUID deviceOnly = user("ACTIVE");
		jdbc.update("INSERT INTO user_preferences (user_id, ai_processing_preference) VALUES (?, 'ON_DEVICE_ONLY')",
				deviceOnly);
		List<String> responses = new ArrayList<>();

		// framework loggers at DEBUG: request/response conversion and exception resolution are the likely leaks
		Logger web = (Logger) LoggerFactory.getLogger("org.springframework.web");
		Logger http = (Logger) LoggerFactory.getLogger("org.springframework.http");
		Level webLevel = web.getLevel();
		Level httpLevel = http.getLevel();
		web.setLevel(Level.DEBUG);
		http.setLevel(Level.DEBUG);
		List<String> logLines;
		try (CapturedLogs logs = new CapturedLogs()) {
			// successes with the mock (output contains the input) and with the scripted provider (distinct output)
			responses.add(body(send(user, "translate", key(), translateBody(IN_EN, "en", "om"))));
			String replayKey = key();
			responses.add(body(send(user, "translate", replayKey, translateBody(IN_EN, "en", "om"))));
			responses.add(body(send(user, "translate", replayKey, translateBody(IN_EN, "en", "om")))); // replay
			responses.add(body(send(user, "translate", replayKey, translateBody(IN_AM, "am", "en")))); // 409
			responses.add(body(send(user, "transform", key(), transformBody("GRAMMAR_CORRECTION", IN_AM, "am",
					"{\"mode\":\"SPELLING_ONLY\"}"))));
			responses.add(body(send(user, "transform", key(), transformBody("REWRITE", IN_EN, "en", "{\"mode\":\"NATURAL\"}"))));
			responses.add(body(send(user, "transform", key(), transformBody("TONE_ADJUST", IN_EN, "en", "{\"tone\":\"FRIENDLY\"}"))));
			responses.add(body(send(user, "detect-language", null, detectBody(IN_AM))));
			responses.add(body(send(pending, "detect-language", null, detectBody(IN_EN))));
			mock.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
			responses.add(body(send(user, "translate", key(), translateBody(IN_EN, "en", "om")))); // scripted answers
			fallback.translations.add(OUT_SCRIPTED);
			responses.add(body(send(user, "translate", key(), translateBody(IN_EN, "en", "om"))));
			fallback.translations.clear();
			fallback.translations.add("");
			responses.add(body(send(user, "translate", key(), translateBody(IN_EN, "en", "om")))); // guardrail
			fallback.delegate.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
			responses.add(body(send(user, "translate", key(), translateBody(IN_EN, "en", "om")))); // 503
			responses.add(body(send(user, "transform", key(), transformBody("REWRITE", IN_EN, "en", "{\"mode\":\"NATURAL\"}"))));
			mock.setBehavior(Behavior.failing(FailureMode.CONTENT_REJECTED));
			fallback.delegate.setBehavior(Behavior.ok());
			responses.add(body(send(user, "translate", key(), translateBody(IN_EN, "en", "om")))); // 422
			mock.setBehavior(Behavior.ok());
			fallback.reset();
			breakers.resetAll();
			// refusals and malformed input carrying the text
			responses.add(body(send(user, "translate", key(), translateBody(IN_EN, "en", "xx"))));
			responses.add(body(send(user, "translate", key(), translateBody(IN_EN, "en", "en"))));
			responses.add(body(send(user, "translate", key(), translateBody(IN_AM, "am", "ti"))));
			responses.add(body(send(user, "translate", null, translateBody(IN_EN, "en", "om"))));
			responses.add(body(send(user, "translate", "not-a-uuid-" + IN_EN, translateBody(IN_EN, "en", "om"))));
			responses.add(body(send(user, "translate", key(), "{\"text\":" + json(IN_EN)))); // truncated JSON
			responses.add(body(send(user, "translate", key(), "{\"text\":" + json(IN_EN) + ",\"addressee\":{\"formality\":\""
					+ IN_EN + "\"}}")));
			for (String bad : List.of(",\"preserveNames\":\"" + IN_EN + "\"", ",\"glossaryId\":\"" + IN_EN + "\"",
					",\"destinationProfileId\":\"" + IN_EN + "\"", ",\"addressee\":\"" + IN_EN + "\"",
					",\"culturalOptions\":{\"adaptIdioms\":\"" + IN_EN + "\"}", ",\"sourceLanguage\":{\"" + IN_EN + "\":1}")) {
				responses.add(body(send(user, "translate", key(), "{\"text\":" + json(IN_EN) + bad + "}")));
			}
			responses.add(body(send(user, "translate", key(), "{\"text\":" + json(IN_EN) + ",\"style\":\"" + IN_EN
					+ "\",\"destinationLanguage\":\"om\"}")));
			responses.add(body(send(user, "transform", key(), "{\"task\":\"" + IN_EN + "\",\"text\":" + json(IN_EN) + "}")));
			responses.add(body(send(user, "transform", key(), transformBody("REWRITE", IN_EN, "en", "{\"mode\":\"" + IN_EN
					+ "\"}"))));
			responses.add(body(send(user, "transform", key(), transformBody("SCRIPT_CONVERSION", IN_EN, "en", "{}"))));
			responses.add(body(send(user, "detect-language", null, "{\"text\":" + json(IN_AM) + ",\"candidates\":[\""
					+ IN_EN + "\"]}")));
			responses.add(body(send(deviceOnly, "translate", key(), translateBody(IN_EN, "en", "om"))));
			responses.add(body(send(deviceOnly, "detect-language", null, detectBody(IN_EN))));
			responses.add(body(send(pending, "translate", key(), translateBody(IN_EN, "en", "om"))));
			responses.add(body(mvc.perform(post("/api/v1/ai/translate").contentType(MediaType.APPLICATION_JSON)
					.header("Idempotency-Key", key()).content(translateBody(IN_EN, "en", "om").getBytes(StandardCharsets.UTF_8)))));
			responses.add(body(mvc.perform(get("/api/v1/ai/capabilities").header("Authorization", bearer(user)))));
			// a quota refusal
			UUID quota = user("ACTIVE");
			for (int i = 0; i < 11; i++) {
				responses.add(body(send(quota, "translate", key(), translateBody(IN_EN, "en", "om"))));
			}
			logLines = logs.texts();
			assertThat(logs.size()).isPositive();
		} finally {
			web.setLevel(webLevel);
			http.setLevel(httpLevel);
		}

		// nothing in a response body except where the contract returns it: Problems never echo text
		for (String response : responses) {
			if (response.contains("\"code\"")) { // a Problem
				for (String secret : SECRETS) {
					assertThat(response).as("Problem body").doesNotContain(secret);
				}
			}
		}
		// logs: messages, MDC, throwable class names and messages
		assertThat(logLines).isNotEmpty();
		for (String line : logLines) {
			for (String secret : SECRETS) {
				assertThat(line).as("log line").doesNotContain(secret);
			}
		}
		// metrics: names and tags
		for (Meter meter : meters.getMeters()) {
			assertThat(meter.getId().getName()).doesNotContain("Zebra", "Quokka");
			for (Tag tag : meter.getId().getTags()) {
				for (String secret : SECRETS) {
					assertThat(tag.getKey() + "=" + tag.getValue()).as("metric tag").doesNotContain(secret);
				}
			}
		}
		// every table, every row: plaintext, and the hex of the bytes inside a bytea
		assertNoTableHolds(SECRETS);
	}

	String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	void assertNoTableHolds(List<String> secrets) {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
				String.class);
		assertThat(tables).contains("ai_requests", "usage_events", "usage_reservations", "outbox_events", "audit_events",
				"ai_replay_cache", "ai_request_fingerprints");
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
