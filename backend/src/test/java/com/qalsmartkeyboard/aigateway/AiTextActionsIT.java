package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.Behavior;
import com.qalsmartkeyboard.aigateway.pipeline.PromptRenderer;
import com.qalsmartkeyboard.aigateway.pipeline.PromptTemplates;
import com.qalsmartkeyboard.aigateway.pipeline.PromptValues;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreakers;
import com.qalsmartkeyboard.aigateway.spi.Addressee;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.ContextPart;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * ADR 0027 end to end on PostgreSQL 18 with the V20 seeds (capabilities, disabled policies, the active prompt
 * templates), the mock providers, real metering and entitlements, every response validated against
 * {@code contracts/openapi.yaml}: the text actions in another output language, reply suggestions, the three e-mail
 * modes, their validation, charging and replay, what reaches the provider (instructions without user text, the
 * user's texts as separate parts) and the per-feature time budget.
 *
 * <p>The templates are the seeded ones (this class never deletes them). The e-mail budget is lowered to 3 s / 5 s here
 * so the timeout test stays short; every other feature keeps the 1 s / 3 s test budget.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s",
		"qal.ai.registry-cache-ttl=0s", "qal.ai.routing.policy-cache-ttl=0s", "qal.ai.routing.kill-switch-cache-ttl=0s",
		"qal.ai.routing.cost-cache-ttl=0s", "qal.ai.routing.attempt-timeout=1s", "qal.ai.routing.deadline=3s",
		"qal.ai.pipeline.deadline=3s", "qal.ai.pipeline.catalog-cache-ttl=0s",
		"qal.ai.pipeline.feature-timeouts.DRAFT_DOCUMENT.attempt-timeout=3s",
		"qal.ai.pipeline.feature-timeouts.DRAFT_DOCUMENT.deadline=5s",
		// REWRITE's built-in 6 s / 10 s would make the timeout test slow: keep the 1 s / 3 s test budget
		"qal.ai.pipeline.feature-timeouts.REWRITE.attempt-timeout=1s",
		"qal.ai.pipeline.feature-timeouts.REWRITE.deadline=3s" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
@Import(AiPipelineIT.Adapters.class)
class AiTextActionsIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	/** User text that must never reach the instructions. */
	static final String SECRET = "Zebra lighthouse marmalade";
	static final String NOTES_AM = "ሐሙስ ከሰዓት በኋላ ይመቸኛል";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	CircuitBreakers breakers;
	@Autowired
	@Qualifier("mockAiProvider")
	MockAiProvider mock;
	@Autowired
	AiPipelineIT.ScriptedProvider fallback;
	@Autowired
	PromptTemplates templates;
	@Autowired
	PromptRenderer renderer;

	@BeforeEach
	void enable() {
		clean();
		jdbc.update("UPDATE ai_providers SET provider_status = 'ACTIVE', dpa_signed_at = NOW() "
				+ "WHERE provider_code IN ('mock', 'wiremock-http')");
		mock.setBehavior(Behavior.ok());
		fallback.reset();
		breakers.resetAll();
	}

	@AfterEach
	void clean() {
		jdbc.update("UPDATE ai_providers SET provider_status = 'DISABLED', dpa_signed_at = NULL "
				+ "WHERE provider_code IN ('mock', 'wiremock-http')");
	}

	// ======================================================================================= helpers

	UUID user(String plan) {
		UUID user = jdbc.queryForObject("""
				INSERT INTO users (account_type, account_status, time_zone)
				VALUES ('REGISTERED', 'ACTIVE', 'Africa/Addis_Ababa') RETURNING id""", UUID.class);
		if (plan != null) {
			Instant now = Instant.now();
			jdbc.update("""
					INSERT INTO user_subscriptions (user_id, plan_id, source_type, billing_mode, subscription_status,
					    current_period_start, current_period_end)
					VALUES (?, (SELECT id FROM subscription_plans WHERE plan_code = ?), 'PAYMENT', 'MANUAL_RENEW',
					        'ACTIVE', ?, ?)""", user, plan, Timestamp.from(now.minus(Duration.ofDays(1))),
					Timestamp.from(now.plus(Duration.ofDays(29))));
		}
		return user;
	}

	ResultActions send(UUID user, String path, String key, String body) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/ai/" + path)
				.header("Authorization", "Bearer " + tokens.issue(user, UUID.randomUUID(), null, "REGISTERED").value())
				.contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return mvc.perform(request);
	}

	static String key() {
		return UUID.randomUUID().toString();
	}

	long used(UUID user, String feature) {
		return jdbc.query("SELECT used_units FROM usage_counters WHERE user_id = ? AND feature_code = ?",
				rs -> rs.next() ? rs.getLong(1) : 0L, user, feature);
	}

	UUID seededTemplate(String feature) {
		return jdbc.queryForObject("""
				SELECT id FROM ai_prompt_templates WHERE feature_code = ?
				   AND language_scope = '*' AND model_family = '*' AND is_active""", UUID.class, feature);
	}

	/** Only the scripted stand-in (it records what it was sent) serves: the mock row is switched off. */
	void onlyTheRecordingProvider() {
		jdbc.update("UPDATE ai_providers SET provider_status = 'DISABLED' WHERE provider_code = 'mock'");
	}

	// ======================================================================================= V20 seeds

	@Test
	void v28BumpsTheEmailReplyAndToneTemplates() {
		Map<String, Object> draft = jdbc.queryForMap("""
				SELECT template_version, template_body FROM ai_prompt_templates
				 WHERE feature_code = 'DRAFT_DOCUMENT' AND is_active AND language_scope = '*' AND model_family = '*'""");
		assertThat(draft.get("template_version")).isEqualTo(3);
		assertThat((String) draft.get("template_body"))
				.contains("use the sender's name only in the sign-off, never in the greeting",
						"Document type: {{documentType}}", "- JOB_APPLICATION:", "- CV:", "- COVER_LETTER:",
						"- BUSINESS_MESSAGE:")
				.doesNotContain("use them in the greeting and the signature");
		Map<String, Object> reply = jdbc.queryForMap("""
				SELECT template_version, template_body FROM ai_prompt_templates
				 WHERE feature_code = 'SMART_REPLY' AND is_active AND language_scope = '*' AND model_family = '*'""");
		assertThat(reply.get("template_version")).isEqualTo(4); // V29: the intent decides every reply's stance
		assertThat((String) reply.get("template_body")).contains("Do not number the replies", "Intent: {{intent}}",
				"DECLINE_POLITELY", "ASK_CLARIFICATION", "Without notes and without an intent",
				"never add a reply with another stance")
				.doesNotContain("Without notes, give");
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ai_prompt_templates WHERE feature_code = 'SMART_REPLY' AND template_version = 3
				   AND NOT is_active""", Integer.class)).isEqualTo(1);
		Map<String, Object> tone = jdbc.queryForMap("""
				SELECT template_version, template_body FROM ai_prompt_templates
				 WHERE feature_code = 'TONE_ADJUST' AND is_active AND language_scope = '*' AND model_family = '*'""");
		assertThat(tone.get("template_version")).isEqualTo(2);
		assertThat((String) tone.get("template_body")).contains("- ROMANTIC:", "- CONFIDENT:", "- FUNNY:",
				"- PERSUASIVE:");
		// the older versions are kept (feedback and history rows point at them) but no longer served
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ai_prompt_templates WHERE feature_code IN ('DRAFT_DOCUMENT', 'SMART_REPLY')
				   AND template_version IN (1, 2) AND NOT is_active""", Integer.class)).isEqualTo(4);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ai_prompt_templates WHERE feature_code = 'TONE_ADJUST'
				   AND template_version = 1 AND NOT is_active""", Integer.class)).isEqualTo(1);
	}

	@Test
	void v20SeedsActiveTemplatesForEveryTextActionAndTheEmailModes() {
		List<String> features = jdbc.queryForList("""
				SELECT feature_code FROM ai_prompt_templates
				 WHERE is_active AND evaluation_status = 'APPROVED'
				   AND language_scope = '*' AND model_family = '*' ORDER BY feature_code""", String.class);
		assertThat(features).containsExactly("AI_CHAT", "DRAFT_DOCUMENT", "EXPLAIN_TEXT", "FORMAT_TEXT",
				"GRAMMAR_CORRECTION", "REWRITE", "SMART_REPLY", "SUMMARIZE", "TONE_ADJUST");

		Map<String, String> values = PromptValues.of(new PromptValues.Inputs(AiFeature.DRAFT_DOCUMENT, "REPLY",
				"PERSUASIVE", null, "MEDIUM", "en", "English", "am", "Amharic", "en", "English",
				Addressee.Formality.FORMAL, Addressee.Gender.FEMININE, "GREGORIAN", "INTERNATIONAL", "WESTERN", true,
				true, LocalDate.of(2026, 11, 18), "am", "Amharic", 3, 2));
		values.put("format", "MEETING_NOTES");
		values.put("intent", "DECLINE_POLITELY");
		values.put("documentType", "JOB_APPLICATION");
		for (String feature : features) {
			// served to the production LLM (family openai) and to the dev mocks alike, with no unknown placeholder
			PromptTemplates.Template t = templates.select(feature, "am", "openai", "openai-llm").orElseThrow();
			assertThat(templates.select(feature, "om", "mock", "mock-llm-1")).contains(t);
			assertThat(renderer.unknownPlaceholders(t.body())).as(feature).isEmpty();
			String rendered = renderer.render(t.body(), values);
			assertThat(rendered).as(feature).doesNotContain("{{")
					// facts are preserved and the output language is enforced
					.contains("exactly as", "Write the result in Amharic (am).")
					.containsAnyOf("Never add", "Never invent", "never invent", "Use only")
					.contains("Ge'ez (Ethiopic) script");
			assertThat(t.body()).as(feature).doesNotContain("{{text}}", "{{input}}");
			for (String line : t.body().split("\\R")) {
				assertThat(line.length()).as(feature + " line").isLessThan(1000);
			}
		}

		String email = renderer.render(templates.select("DRAFT_DOCUMENT", "en", null, null).orElseThrow().body(),
				values);
		assertThat(email).contains("Document type: JOB_APPLICATION", "Mode: REPLY", "- NEW:", "- REPLY:", "- IMPROVE:",
				"Tone: PERSUASIVE", "PROFESSIONAL =", "FORMAL =", "FRIENDLY =", "CASUAL =", "POLITE =", "PERSUASIVE =",
				"APOLOGETIC =", "CONCISE =", "[date]", "Subject:", "\"Subject: Re: \"", "Variation 2",
				"Never invent facts, promises, deadlines, prices, attachments or commitments",
				"international recipients", "Address the reader formally", "Length: MEDIUM",
				"Today is 2026-11-18 in the Gregorian calendar");
		String replies = renderer.render(templates.select("SMART_REPLY", "en", null, null).orElseThrow().body(),
				values);
		assertThat(replies).contains("suggest 3 different replies", "exactly 3 replies, with a line that "
				+ "contains only ---", "Do not number the replies", "the user's own notes", "Variation 2", "[time]",
				"Intent: DECLINE_POLITELY");
		String rewrite = renderer.render(templates.select("REWRITE", "en", null, null).orElseThrow().body(),
				PromptValues.of(new PromptValues.Inputs(AiFeature.REWRITE, "SIMPLIFY", null, null, null, null, null,
						null, null, "en", "English", null, null, null, null, null, true, true, null)));
		assertThat(rewrite).contains("Mode: SIMPLIFY", "- SIMPLIFY: short sentences and common everyday words",
				"Write the result in English (en).");
		String tone = renderer.render(templates.select("TONE_ADJUST", "en", null, null).orElseThrow().body(), values);
		assertThat(tone).contains("- PROFESSIONAL: clear, courteous and businesslike", "- ROMANTIC:", "- CONFIDENT:",
				"- FUNNY:", "- PERSUASIVE:");
		String format = renderer.render(templates.select("FORMAT_TEXT", "en", null, null).orElseThrow().body(),
				values);
		assertThat(format).contains("Format: MEETING_NOTES", "- MEETING_NOTES:", "Never add facts", "[owner]");
	}

	@Test
	void v20SeedsCapabilitiesForEveryProviderAndDisabledPolicies() {
		String count = """
				SELECT count(*) FROM ai_language_capabilities c JOIN ai_models m ON m.id = c.model_id
				  JOIN ai_providers p ON p.id = m.provider_id
				 WHERE p.provider_code = ? AND c.feature_code = ? AND c.is_enabled AND c.quality_tier = 'BETA'
				   AND c.destination_language_id IS NULL AND c.supports_auto_detection""";
		for (String provider : List.of("openai", "mock", "wiremock-http")) {
			// 4 languages of V20 + ar (V23)
			for (String feature : List.of("SMART_REPLY", "SUMMARIZE", "EXPLAIN_TEXT", "DRAFT_DOCUMENT",
					"FORMAT_TEXT")) {
				assertThat(jdbc.queryForObject(count, Integer.class, provider, feature)).as(provider + " " + feature)
						.isEqualTo(5);
			}
			assertThat(jdbc.queryForObject(count, Integer.class, provider, "SCRIPT_CONVERSION")).isZero();
		}
		assertThat(jdbc.queryForObject(count, Integer.class, "google-translate", "DRAFT_DOCUMENT")).isZero();
		assertThat(jdbc.queryForList("""
				SELECT DISTINCT c.feature_code || ':' || c.max_input_characters FROM ai_language_capabilities c
				 WHERE c.feature_code IN ('SMART_REPLY', 'SUMMARIZE', 'EXPLAIN_TEXT', 'DRAFT_DOCUMENT', 'FORMAT_TEXT')
				 ORDER BY 1""",
				String.class)).containsExactly("DRAFT_DOCUMENT:12000", "EXPLAIN_TEXT:5000", "FORMAT_TEXT:5000",
						"SMART_REPLY:6000", "SUMMARIZE:5000");
		assertThat(jdbc.queryForList("""
				SELECT r.feature_code || ':' || p.provider_code || ':' || r.priority || ':' || r.is_enabled
				  FROM ai_routing_policies r JOIN ai_models m ON m.id = r.model_id JOIN ai_providers p ON p.id = m.provider_id
				 WHERE r.feature_code IN ('SMART_REPLY', 'SUMMARIZE', 'EXPLAIN_TEXT', 'DRAFT_DOCUMENT', 'FORMAT_TEXT')
				 ORDER BY 1""",
				String.class)).containsExactly("DRAFT_DOCUMENT:openai:1:false", "EXPLAIN_TEXT:openai:1:false",
						"FORMAT_TEXT:openai:1:false", "SMART_REPLY:openai:1:false", "SUMMARIZE:openai:1:false");
	}

	// ======================================================================================= transform

	@Test
	void textActionsAnswerInTheAskedOutputLanguage() throws Exception {
		UUID user = user("PREMIUM");
		String summary = send(user, "transform", key(), """
				{"task":"SUMMARIZE","text":"%s. The meeting moved to Thursday.","language":"en","outputLanguage":"am",
				 "options":{"targetLength":"SHORT"}}""".formatted(SECRET)).andExpect(status().isOk())
				.andExpect(jsonPath("$.task").value("SUMMARIZE")).andExpect(jsonPath("$.language").value("am"))
				.andExpect(jsonPath("$.resultText").value(org.hamcrest.Matchers.startsWith("ማጠቃለያ፦")))
				.andExpect(jsonPath("$.usage.featureCode").value("SUMMARIZE"))
				.andExpect(jsonPath("$.qualityTier").value("BETA")).andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(summary, "$.requestId"));
		Map<String, Object> row = jdbc.queryForMap("""
				SELECT r.prompt_template_id, d.language_code AS destination, s.language_code AS source
				  FROM ai_requests r LEFT JOIN supported_languages d ON d.id = r.destination_language_id
				  LEFT JOIN supported_languages s ON s.id = r.source_language_id WHERE r.id = ?""", id);
		assertThat(row).containsEntry("prompt_template_id", seededTemplate("SUMMARIZE")).containsEntry("destination", "am")
				.containsEntry("source", "en");
		assertThat(used(user, "SUMMARIZE")).isOne();

		send(user, "transform", key(), """
				{"task":"EXPLAIN_TEXT","text":"Kill two birds with one stone","options":{"explainIn":"om"}}""")
				.andExpect(status().isOk()).andExpect(jsonPath("$.language").value("om"))
				.andExpect(jsonPath("$.resultText").value("Ibsa: Kill two birds with one stone"));
		send(user, "transform", key(), """
				{"task":"REWRITE","text":"The applicant shall remit the fee forthwith.","language":"en",
				 "outputLanguage":"am","options":{"mode":"SIMPLIFY"}}""").andExpect(status().isOk())
				.andExpect(jsonPath("$.resultText").value("[ሙከራ] The applicant shall remit the fee forthwith."));
		send(user, "transform", key(), """
				{"task":"TONE_ADJUST","text":"ሪፖርቱን ቶሎ ላክልኝ","language":"am","outputLanguage":"en",
				 "options":{"tone":"PROFESSIONAL"}}""").andExpect(status().isOk())
				.andExpect(jsonPath("$.language").value("en"))
				.andExpect(jsonPath("$.resultText").value("[mock] ሪፖርቱን ቶሎ ላክልኝ"));
		// a correction keeps the language of the text
		send(user, "transform", key(), """
				{"task":"GRAMMAR_CORRECTION","text":"abc","language":"en","outputLanguage":"am"}""")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("outputLanguage"))
				.andExpect(jsonPath("$.errors[0].code").value("NOT_ALLOWED"));
		send(user, "transform", key(), """
				{"task":"SUMMARIZE","text":"abc","options":{"mode":"SIMPLIFY"}}""").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("options.mode"));
		send(user, "transform", key(), """
				{"task":"SUMMARIZE","text":"abc","outputLanguage":"xx"}""").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LANGUAGE_NOT_SUPPORTED"));
	}

	/** ADR 0048: FORMAT_TEXT needs options.format, restructures the text and takes the new tones. */
	@Test
	void formatTextRestructuresTheTextAndNeedsAFormat() throws Exception {
		UUID user = user("PREMIUM");
		send(user, "transform", key(), """
				{"task":"FORMAT_TEXT","text":"Buy milk\\nCall mom","language":"en","options":{"format":"BULLETS"}}""")
				.andExpect(status().isOk()).andExpect(jsonPath("$.task").value("FORMAT_TEXT"))
				.andExpect(jsonPath("$.resultText").value("- Buy milk\n- Call mom"))
				.andExpect(jsonPath("$.usage.featureCode").value("FORMAT_TEXT"))
				.andExpect(jsonPath("$.qualityTier").value("BETA"));
		send(user, "transform", key(), """
				{"task":"FORMAT_TEXT","text":"Buy milk\\nCall mom","language":"en",
				 "options":{"format":"NUMBERED_LIST"}}""").andExpect(status().isOk())
				.andExpect(jsonPath("$.resultText").value("1. Buy milk\n2. Call mom"));
		send(user, "transform", key(), """
				{"task":"TONE_ADJUST","text":"miss you","language":"en","options":{"tone":"ROMANTIC"}}""")
				.andExpect(status().isOk());
		assertThat(used(user, "FORMAT_TEXT")).isEqualTo(2);

		send(user, "transform", key(), """
				{"task":"FORMAT_TEXT","text":"abc","language":"en"}""").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("options.format"))
				.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		send(user, "transform", key(), """
				{"task":"FORMAT_TEXT","text":"abc","language":"en","options":{"format":"JSON"}}""")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("options.format"))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"));
		send(user, "transform", key(), """
				{"task":"REWRITE","text":"abc","language":"en","options":{"format":"BULLETS"}}""")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("options.format"))
				.andExpect(jsonPath("$.errors[0].code").value("NOT_ALLOWED"));
		assertThat(used(user, "FORMAT_TEXT")).isEqualTo(2); // the refused calls charged nothing
	}

	@Test
	void theFreePlanExplainsThreeTimesADayAndHasNoReplyOrEmailWriter() throws Exception {
		UUID free = user(null);
		for (int i = 0; i < 3; i++) {
			send(free, "transform", key(), "{\"task\":\"EXPLAIN_TEXT\",\"text\":\"Selam\",\"outputLanguage\":\"en\"}")
					.andExpect(status().isOk());
		}
		send(free, "transform", key(), "{\"task\":\"EXPLAIN_TEXT\",\"text\":\"Selam\",\"outputLanguage\":\"en\"}")
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
				.andExpect(jsonPath("$.featureCode").value("EXPLAIN_TEXT"));
		send(free, "replies", key(), "{\"contextText\":\"Hi\",\"replyLanguage\":\"en\"}")
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FEATURE_NOT_ENTITLED"));
		send(free, "drafts", key(), "{\"language\":\"en\",\"instructions\":\"ask for leave\"}")
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FEATURE_NOT_ENTITLED"));
		assertThat(used(free, "SMART_REPLY") + used(free, "DRAFT_DOCUMENT")).isZero();
		// capabilities follow the plan
		mvc.perform(get("/api/v1/ai/capabilities").header("Authorization",
				"Bearer " + tokens.issue(free, UUID.randomUUID(), null, "REGISTERED").value()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.featureCode=='EXPLAIN_TEXT')]").isNotEmpty())
				.andExpect(jsonPath("$.items[?(@.featureCode=='DRAFT_DOCUMENT')]").isEmpty());
		mvc.perform(get("/api/v1/ai/capabilities")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.featureCode=='DRAFT_DOCUMENT' && @.sourceLanguage=='am')]"
						+ ".maxInputCharacters").value(8000))
				.andExpect(jsonPath("$.items[?(@.featureCode=='SMART_REPLY' && @.sourceLanguage=='am')]"
						+ ".maxInputCharacters").value(5000));
	}

	// ======================================================================================= replies

	@Test
	void repliesAreSuggestedInTheReplyLanguageFromTheUsersNotes() throws Exception {
		UUID user = user("PREMIUM");
		String k = key();
		String body = """
				{"contextText":"%s: can we move the meeting? Order 7781","contextLanguage":"en","replyLanguage":"am",
				 "count":2,"tone":"POLITE","instructions":"%s 4500"}""".formatted(SECRET, NOTES_AM);
		String first = send(user, "replies", k, body).andExpect(status().isOk())
				.andExpect(header().string("RateLimit-Limit", "30")).andExpect(jsonPath("$.language").value("am"))
				.andExpect(jsonPath("$.suggestions.length()").value(2))
				.andExpect(jsonPath("$.suggestions[0].text").value("ስለ መልእክትዎ እናመሰግናለን። 4500"))
				.andExpect(jsonPath("$.suggestions[0].tone").value("POLITE"))
				.andExpect(jsonPath("$.usage.featureCode").value("SMART_REPLY"))
				.andExpect(jsonPath("$.usage.unitsCharged").value(1)).andExpect(jsonPath("$.replayed").value(false))
				.andReturn().getResponse().getContentAsString();
		// the same key replays; another variation under it is another body
		send(user, "replies", k, body).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true))
				.andExpect(jsonPath("$.requestId").value((String) JsonPath.read(first, "$.requestId")));
		send(user, "replies", k, body.replace("\"count\":2", "\"count\":2,\"variation\":1")).andExpect(
				status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
		// regenerate = a new key and a higher variation: other suggestions, charged again
		send(user, "replies", key(), body.replace("\"count\":2", "\"count\":2,\"variation\":1")).andExpect(
				status().isOk()).andExpect(jsonPath("$.suggestions[0].text").value("እሺ፣ በቅርቡ እመልስልዎታለሁ። 4500"));
		assertThat(used(user, "SMART_REPLY")).isEqualTo(2);

		// what reaches the provider: the instructions hold no user text; the notes are a separate part
		onlyTheRecordingProvider();
		send(user, "replies", key(), body).andExpect(status().isOk());
		TransformRequest sent = fallback.lastTransform.get();
		assertThat(sent.task()).isEqualTo(AiFeature.SMART_REPLY);
		assertThat(sent.text().value()).startsWith(SECRET);
		assertThat(sent.context()).extracting(ContextPart::kind).containsExactly(ContextPart.Kind.USER_NOTES);
		assertThat(sent.context().get(0).text().value()).isEqualTo(NOTES_AM + " 4500");
		assertThat(sent.count()).isEqualTo(2);
		assertThat(sent.outputLanguage()).isEqualTo("am");
		assertThat(sent.instructions()).contains("suggest 2 different replies", "Write the result in Amharic (am).",
				"Tone: POLITE").doesNotContain(SECRET).doesNotContain(NOTES_AM).doesNotContain("7781");
	}

	@Test
	void repliesAreValidatedBeforeAnythingIsCharged() throws Exception {
		UUID user = user("PREMIUM");
		String ctx = "\"contextText\":\"Hi\"";
		send(user, "replies", key(), "{" + ctx + "}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("replyLanguage"))
				.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		send(user, "replies", key(), "{" + ctx + ",\"replyLanguage\":\"en\",\"count\":6}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("count"));
		send(user, "replies", key(), "{" + ctx + ",\"replyLanguage\":\"en\",\"variation\":21}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("variation"));
		send(user, "replies", key(), "{" + ctx + ",\"replyLanguage\":\"en\",\"tone\":\"FORMAL\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("tone"));
		send(user, "replies", key(), "{" + ctx + ",\"replyLanguage\":\"xx\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LANGUAGE_NOT_SUPPORTED"));
		send(user, "replies", key(), "{\"contextText\":\"" + "x".repeat(5001) + "\",\"replyLanguage\":\"en\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INPUT_TOO_LONG"));
		send(user, "replies", key(), "{" + ctx + ",\"replyLanguage\":\"en\",\"instructions\":\"" + "x".repeat(1001)
				+ "\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INPUT_TOO_LONG"))
				.andExpect(jsonPath("$.errors[0].field").value("instructions"));
		send(user, "replies", key(), "{\"contextText\":\"  \",\"replyLanguage\":\"en\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("contextText"));
		assertThat(used(user, "SMART_REPLY")).isZero();
	}

	/** ADR 0048: a reply intent is a preset stance; it reaches the instructions, not the user text. */
	@Test
	void aReplyIntentIsValidatedAndSteersEverySuggestion() throws Exception {
		UUID user = user("PREMIUM");
		onlyTheRecordingProvider();
		send(user, "replies", key(), """
				{"contextText":"Can you come to the wedding on Saturday?","replyLanguage":"en",
				 "intent":"DECLINE_POLITELY"}""").andExpect(status().isOk());
		TransformRequest sent = fallback.lastTransform.get();
		assertThat(sent.task()).isEqualTo(AiFeature.SMART_REPLY);
		assertThat(sent.instructions()).contains("Intent: DECLINE_POLITELY", "every reply takes that stance");
		send(user, "replies", key(), """
				{"contextText":"Hi","replyLanguage":"en","intent":"ANGRY"}""").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("intent"))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"));
		assertThat(used(user, "SMART_REPLY")).isOne();
	}

	// ======================================================================================= drafts

	@Test
	void aNewEmailIsWrittenWithASubjectNamesAndPlaceholders() throws Exception {
		UUID user = user("PREMIUM");
		String response = send(user, "drafts", key(), """
				{"documentType":"EMAIL","mode":"NEW","language":"en","tone":"FORMAL",
				 "instructions":"ለአለቃዬ ነገ ታምሜ ስለሆነ ወደ ስራ እንደማልመጣ ንገረው",
				 "recipient":{"name":"Ms. Sarah Johnson","formality":"FORMAL"},"senderName":"Abebe Kebede"}""")
				.andExpect(status().isOk()).andExpect(header().string("RateLimit-Limit", "10"))
				.andExpect(jsonPath("$.mode").value("NEW")).andExpect(jsonPath("$.language").value("en"))
				.andExpect(jsonPath("$.subject").value("Mock email subject"))
				.andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.startsWith("Dear Ms. Sarah Johnson,")))
				.andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.endsWith("Best regards,\nAbebe Kebede")))
				.andExpect(jsonPath("$.placeholders.length()").value(1))
				.andExpect(jsonPath("$.placeholders[0]").value("[date]"))
				.andExpect(jsonPath("$.notes[0].type").value("QUALITY"))
				.andExpect(jsonPath("$.usage.featureCode").value("DRAFT_DOCUMENT"))
				.andExpect(jsonPath("$.usage.unitsCharged").value(1)).andExpect(jsonPath("$.usage.remaining").value(49))
				.andReturn().getResponse().getContentAsString();
		assertThat(used(user, "DRAFT_DOCUMENT")).isOne();
		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_requests WHERE id = ?",
				UUID.fromString(JsonPath.read(response, "$.requestId")));
		assertThat(row).containsEntry("feature_code", "DRAFT_DOCUMENT").containsEntry("request_status", "SUCCEEDED")
				.containsEntry("prompt_template_id", seededTemplate("DRAFT_DOCUMENT"));
		assertThat(row.values()).noneMatch(v -> v != null && String.valueOf(v).contains("ታምሜ"));

		// regenerate: a new key and a higher variation give another version
		send(user, "drafts", key(), """
				{"language":"en","instructions":"ask for leave","variation":2}""").andExpect(status().isOk())
				.andExpect(jsonPath("$.subject").value("Mock email subject (2)"))
				.andExpect(jsonPath("$.placeholders").value(org.hamcrest.Matchers.contains("[recipient name]", "[date]",
						"[your name]")));
		// no subject wanted
		send(user, "drafts", key(), """
				{"language":"am","instructions":"ask for leave","includeSubject":false}""").andExpect(status().isOk())
				.andExpect(jsonPath("$.subject").doesNotExist())
				.andExpect(jsonPath("$.placeholders").value(org.hamcrest.Matchers.contains("[የተቀባይ ስም]", "[ቀን]",
						"[ስምዎ]")));
	}

	@Test
	void aReplyAndAnImprovedDraftFollowTheirMode() throws Exception {
		UUID user = user("PREMIUM");
		String original = "Dear Abebe, " + SECRET + ". Can you confirm order 7781 by Friday? Regards, Sarah";
		send(user, "drafts", key(), """
				{"mode":"REPLY","language":"am","tone":"POLITE","originalEmail":"%s",
				 "instructions":"confirm 7781, delivery next week","recipient":{"name":"Sarah"}}""".formatted(original))
				.andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("REPLY"))
				.andExpect(jsonPath("$.subject").value("Re: የሙከራ ኢሜይል ርዕስ"))
				.andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.startsWith("ውድ Sarah,")))
				.andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("7781")));
		send(user, "drafts", key(), """
				{"mode":"IMPROVE","language":"om","draft":"akkam, guyyaa boruu hojii hin dhufu"}""")
				.andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("IMPROVE"))
				.andExpect(jsonPath("$.subject").doesNotExist())
				.andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("guyyaa boruu")));
		assertThat(used(user, "DRAFT_DOCUMENT")).isEqualTo(2);

		// what reaches the provider in a reply: the e-mail as the main text, the notes and names as parts in order
		onlyTheRecordingProvider();
		send(user, "drafts", key(), """
				{"mode":"REPLY","language":"en","originalEmail":"%s","instructions":"%s",
				 "recipient":{"name":"Sarah","role":"Purchasing"},"senderName":"Abebe"}""".formatted(original, NOTES_AM))
				.andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("Re: Mock email subject"));
		TransformRequest sent = fallback.lastTransform.get();
		assertThat(sent.task()).isEqualTo(AiFeature.DRAFT_DOCUMENT);
		assertThat(sent.mode()).isEqualTo("REPLY");
		assertThat(sent.tone()).isEqualTo("PROFESSIONAL");
		assertThat(sent.targetLength()).isEqualTo("MEDIUM");
		assertThat(sent.outputLanguage()).isEqualTo("en");
		assertThat(sent.text().value()).isEqualTo(original);
		assertThat(sent.context()).extracting(ContextPart::kind).containsExactly(ContextPart.Kind.USER_NOTES,
				ContextPart.Kind.RECIPIENT_NAME, ContextPart.Kind.RECIPIENT_ROLE, ContextPart.Kind.SENDER_NAME);
		assertThat(sent.instructions()).contains("Mode: REPLY", "Tone: PROFESSIONAL", "Write the result in English (en).")
				.doesNotContain(SECRET).doesNotContain(NOTES_AM).doesNotContain("Sarah").doesNotContain("Abebe");
	}

	/** ADR 0048: JOB_APPLICATION, CV, COVER_LETTER and BUSINESS_MESSAGE are served next to EMAIL. */
	@Test
	void theNewDocumentTypesAreServedAndTheOthersStayUnavailable() throws Exception {
		UUID user = user("PREMIUM");
		onlyTheRecordingProvider();
		send(user, "drafts", key(), """
				{"documentType":"CV","language":"en","instructions":"nurse, 5 years at Black Lion hospital"}""")
				.andExpect(status().isOk());
		TransformRequest sent = fallback.lastTransform.get();
		assertThat(sent.instructions()).contains("Document type: CV", "- JOB_APPLICATION:", "- COVER_LETTER:",
				"- BUSINESS_MESSAGE:", "no greeting and no sign-off");
		send(user, "drafts", key(), """
				{"documentType":"BUSINESS_MESSAGE","language":"en","instructions":"ask about invoice 118"}""")
				.andExpect(status().isOk());
		assertThat(used(user, "DRAFT_DOCUMENT")).isEqualTo(2);
		// the still-unserved types answer NOT_AVAILABLE on documentType and charge nothing
		send(user, "drafts", key(), """
				{"documentType":"LETTER","language":"en","instructions":"a"}""").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("documentType"))
				.andExpect(jsonPath("$.errors[0].code").value("NOT_AVAILABLE"));
		send(user, "drafts", key(), """
				{"documentType":"RESIGNATION","language":"en","instructions":"a"}""")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("documentType"))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_VALUE"));
		assertThat(used(user, "DRAFT_DOCUMENT")).isEqualTo(2);
	}

	@Test
	void draftsAreValidatedFieldByFieldBeforeAnythingIsCharged() throws Exception {
		UUID user = user("PREMIUM");
		record Case(String body, String code, String field) {
		}
		String big = "x".repeat(8001);
		for (Case c : List.of(new Case("{\"instructions\":\"a\"}", "VALIDATION_FAILED", "language"),
				new Case("{\"language\":\"en\"}", "VALIDATION_FAILED", "instructions"),
				new Case("{\"language\":\"en\",\"instructions\":\"   \"}", "VALIDATION_FAILED", "instructions"),
				new Case("{\"language\":\"en\",\"mode\":\"REPLY\",\"instructions\":\"a\"}", "VALIDATION_FAILED",
						"originalEmail"),
				new Case("{\"language\":\"en\",\"mode\":\"REPLY\",\"originalEmail\":\"a\"}", "VALIDATION_FAILED",
						"instructions"),
				new Case("{\"language\":\"en\",\"mode\":\"IMPROVE\"}", "VALIDATION_FAILED", "draft"),
				new Case("{\"language\":\"en\",\"instructions\":\"a\",\"originalEmail\":\"b\"}", "VALIDATION_FAILED",
						"originalEmail"),
				new Case("{\"language\":\"en\",\"instructions\":\"a\",\"draft\":\"b\"}", "VALIDATION_FAILED", "draft"),
				new Case("{\"language\":\"en\",\"mode\":\"FORWARD\",\"instructions\":\"a\"}", "VALIDATION_FAILED",
						"mode"),
				new Case("{\"language\":\"en\",\"documentType\":\"LETTER\",\"instructions\":\"a\"}",
						"VALIDATION_FAILED", "documentType"),
				new Case("{\"language\":\"en\",\"tone\":\"ANGRY\",\"instructions\":\"a\"}", "VALIDATION_FAILED",
						"tone"),
				new Case("{\"language\":\"en\",\"length\":\"HUGE\",\"instructions\":\"a\"}", "VALIDATION_FAILED",
						"length"),
				new Case("{\"language\":\"en\",\"variation\":21,\"instructions\":\"a\"}", "VALIDATION_FAILED",
						"variation"),
				new Case("{\"language\":\"xx\",\"instructions\":\"a\"}", "LANGUAGE_NOT_SUPPORTED", "language"),
				new Case("{\"language\":\"en\",\"mode\":\"REPLY\",\"instructions\":\"a\",\"originalEmail\":\"" + big
						+ "\"}", "INPUT_TOO_LONG", "originalEmail"),
				new Case("{\"language\":\"en\",\"mode\":\"IMPROVE\",\"draft\":\"" + big + "\"}", "INPUT_TOO_LONG",
						"draft"),
				new Case("{\"language\":\"en\",\"instructions\":\"" + "x".repeat(2001) + "\"}", "INPUT_TOO_LONG",
						"instructions"),
				new Case("{\"language\":\"en\",\"instructions\":\"a\",\"senderName\":\"" + "x".repeat(121) + "\"}",
						"INPUT_TOO_LONG", "senderName"))) {
			// a fresh caller per case: drafts are rate limited to 10 a minute per user (validation precedes the plan)
			send(user(null), "drafts", key(), c.body()).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value(c.code()))
					.andExpect(jsonPath("$.errors[0].field").value(c.field()));
		}
		// an e-mail of 8000 characters is accepted
		send(user, "drafts", key(), "{\"language\":\"en\",\"mode\":\"IMPROVE\",\"draft\":\"" + "word ".repeat(1600)
				+ "\"}").andExpect(status().isOk());
		assertThat(used(user, "DRAFT_DOCUMENT")).isOne();
	}

	/** The e-mail writer has its own time budget: a provider too slow for the keyboard's budget still writes it. */
	@Test
	void anEmailMayTakeLongerThanTheKeyboardBudget() throws Exception {
		UUID user = user("PREMIUM");
		mock.setBehavior(Behavior.slow(Duration.ofMillis(1500)));
		fallback.delegate.setBehavior(Behavior.slow(Duration.ofMillis(1500)));
		try {
			// 1 s attempts: every provider times out, nothing charged
			send(user, "transform", key(), "{\"task\":\"REWRITE\",\"text\":\"Selam\",\"language\":\"en\"}")
					.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PROVIDER_TIMEOUT"));
			breakers.resetAll();
			// 3 s attempts for DRAFT_DOCUMENT in this test configuration
			send(user, "drafts", key(), "{\"language\":\"en\",\"instructions\":\"ask for leave\"}")
					.andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("Mock email subject"));
		} finally {
			mock.setBehavior(Behavior.ok());
			fallback.delegate.setBehavior(Behavior.ok());
			breakers.resetAll();
		}
		assertThat(used(user, "REWRITE")).isZero();
		assertThat(used(user, "DRAFT_DOCUMENT")).isOne();
	}
}
