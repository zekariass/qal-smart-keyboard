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
 * B5.7 "done when": opt-in AI history end to end on PostgreSQL 18 with the mock providers, through the security chain,
 * controllers, pipeline, consents and the retention job, every response validated against {@code contracts/openapi.yaml}:
 * off by default, the gates (preference, consent, request flag, entitlement), encrypted at rest (a scan of every table),
 * failures and replays write nothing, list / get / favourite / delete / clear, retention expiry and purge, consent
 * withdrawal, and the log scrub of the history text.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s",
		"qal.ai.registry-cache-ttl=0s", "qal.ai.routing.policy-cache-ttl=0s", "qal.ai.routing.kill-switch-cache-ttl=0s",
		"qal.ai.routing.cost-cache-ttl=0s", "qal.ai.routing.attempt-timeout=1s", "qal.ai.routing.deadline=3s",
		"qal.ai.pipeline.deadline=3s", "qal.ai.pipeline.catalog-cache-ttl=0s" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
@Import(AiPipelineIT.Adapters.class)
class AiHistoryIT {

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

	// ======================================================================================= B5.7 history gates

	@Test
	void historyIsOffByDefault() throws Exception {
		UUID user = user("REGISTERED"); // default preferences: no row at all
		JsonNode r = translated(user, true); // even a request that asks for it
		assertThat(r.has("historyItemId")).isFalse();
		assertThat(historyRows(user)).isZero();

		// a preferences row with the column default (history off) and the consent granted: still nothing
		consent(user, "SAVE_AI_HISTORY", true);
		jdbc.update("INSERT INTO user_preferences (user_id) VALUES (?)", user);
		assertThat(jdbc.queryForObject("SELECT ai_history_enabled FROM user_preferences WHERE user_id = ?", Boolean.class,
				user)).isFalse();
		assertThat(translated(user, true).has("historyItemId")).isFalse();
		assertThat(historyRows(user)).isZero();
		listHistory(user, "").andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void historyNeedsPreferenceConsentRequestFlagAndEntitlement() throws Exception {
		// preference on, no consent
		UUID noConsent = user("REGISTERED");
		history(noConsent, true, 30);
		assertThat(translated(noConsent, true).has("historyItemId")).isFalse();
		// consent granted then withdrawn, preference still on in the table
		UUID withdrawn = user("REGISTERED");
		consent(withdrawn, "SAVE_AI_HISTORY", true);
		consent(withdrawn, "SAVE_AI_HISTORY", false);
		history(withdrawn, true, 30);
		assertThat(translated(withdrawn, true).has("historyItemId")).isFalse();
		// consent and preference, but the request did not ask (contract default false)
		UUID user = historyUser();
		assertThat(translated(user, false).has("historyItemId")).isFalse();
		assertThat(historyRows(user)).isZero();
		// a guest has no HISTORY_SYNC entitlement
		UUID guest = user("GUEST");
		consent(guest, "SAVE_AI_HISTORY", true);
		history(guest, true, 30);
		assertThat(ok(translate(guest, "GUEST", key(), IN_EN, true)).has("historyItemId")).isFalse();
		for (UUID u : List.of(noConsent, withdrawn, guest)) {
			assertThat(historyRows(u)).isZero();
		}

		// all four: kept
		JsonNode r = translated(user, true);
		assertThat(r.get("historyItemId").asString()).isNotBlank();
		assertThat(historyRows(user)).isEqualTo(1L);
	}

	@Test
	void aSavedItemIsEncryptedPerColumnAndExpiresPerRetention() throws Exception {
		UUID user = user("REGISTERED");
		consent(user, "SAVE_AI_HISTORY", true);
		history(user, true, 7);
		JsonNode r = translated(user, true);
		UUID id = UUID.fromString(r.get("historyItemId").asString());

		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM user_history_items WHERE id = ?", id);
		assertThat(row.get("ai_request_id")).isEqualTo(UUID.fromString(r.get("requestId").asString()));
		assertThat(row.get("feature_code")).isEqualTo("TRANSLATE");
		assertThat(row.get("is_favorite")).isEqualTo(false);
		Instant created = ((Timestamp) row.get("created_at")).toInstant();
		Instant expires = ((Timestamp) row.get("expires_at")).toInstant();
		assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofDays(7));
		assertThat(jdbc.queryForObject("""
				SELECT sl.language_code || '>' || dl.language_code FROM user_history_items h
				JOIN supported_languages sl ON sl.id = h.source_language_id
				JOIN supported_languages dl ON dl.id = h.destination_language_id WHERE h.id = ?""", String.class, id))
				.isEqualTo("en>om");

		byte[] input = (byte[]) row.get("input_ciphertext");
		byte[] output = (byte[]) row.get("output_ciphertext");
		assertThat(encryption.decryptString(user, ctx("user_history_items", "input_ciphertext", id), input)).isEqualTo(IN_EN);
		assertThat(encryption.decryptString(user, ctx("user_history_items", "output_ciphertext", id), output))
				.isEqualTo(OUT_MOCK);
		// separate AAD per column: the output does not open as the input, nor as another row or user
		assertThatThrownBy(() -> encryption.decryptString(user, ctx("user_history_items", "input_ciphertext", id), output))
				.isInstanceOf(CiphertextRejectedException.class);
		assertThatThrownBy(() -> encryption.decryptString(user, ctx("user_history_items", "input_ciphertext", UUID.randomUUID()), input))
				.isInstanceOf(CiphertextRejectedException.class);
		// DB scan: no plaintext of input or output anywhere, in any table (ledger, replay cache, outbox, audit...)
		assertNoTableHolds(List.of(IN_EN, OUT_MOCK, "lighthouse"));

		// a transform is kept too, with its language in the source column
		JsonNode t = ok(mvc.perform(post("/api/v1/ai/transform").header("Authorization", bearer(user))
				.header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
				.content(("{\"task\":\"GRAMMAR_CORRECTION\",\"text\":\"" + IN_EN + "\",\"language\":\"en\","
						+ "\"options\":{\"mode\":\"GRAMMAR\"},\"saveToHistory\":true}").getBytes(StandardCharsets.UTF_8))));
		assertThat(jdbc.queryForObject("SELECT feature_code FROM user_history_items WHERE id = ?", String.class,
				UUID.fromString(t.get("historyItemId").asString()))).isEqualTo("GRAMMAR_CORRECTION");
	}

	@Test
	void failuresAndReplaysWriteNothingMore() throws Exception {
		UUID user = historyUser();
		// failure: nothing
		mock.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		fallback.delegate.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		translate(user, key(), true).andExpect(status().isServiceUnavailable());
		assertThat(historyRows(user)).isZero();
		mock.setBehavior(Behavior.ok());
		fallback.delegate.setBehavior(Behavior.ok());
		breakers.resetAll();

		// success then replay with the same key: one row, same item id, second answer replayed
		String k = key();
		JsonNode first = ok(translate(user, k, true));
		JsonNode second = ok(translate(user, k, true));
		assertThat(second.get("replayed").asBoolean()).isTrue();
		assertThat(second.get("historyItemId").asString()).isEqualTo(first.get("historyItemId").asString());
		assertThat(historyRows(user)).isEqualTo(1L);

		// the replay cache expired, the key is recomputed without a charge: still one row
		jdbc.update("DELETE FROM ai_replay_cache");
		JsonNode third = ok(translate(user, k, true));
		assertThat(third.has("historyItemId")).isFalse();
		assertThat(historyRows(user)).isEqualTo(1L);
		// the unique index is the last line of defence against a second row per request
		UUID requestId = UUID.fromString(first.get("requestId").asString());
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO user_history_items (user_id, feature_code, ai_request_id, input_ciphertext, output_ciphertext,
				       key_version, expires_at) VALUES (?, 'TRANSLATE', ?, '\\x00', '\\x00', 1, NOW() + interval '1 day')""",
				user, requestId)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
	}

	@Test
	void withdrawingTheConsentStopsNewWritesAndKeepsWhatExists() throws Exception {
		UUID user = historyUser();
		UUID kept = UUID.fromString(translated(user, true).get("historyItemId").asString());

		consent(user, "SAVE_AI_HISTORY", false);
		// B3.5: the preference is switched off with the withdrawal
		assertThat(jdbc.queryForObject("SELECT ai_history_enabled FROM user_preferences WHERE user_id = ?", Boolean.class,
				user)).isFalse();
		assertThat(translated(user, true).has("historyItemId")).isFalse();
		assertThat(historyRows(user)).isEqualTo(1L);
		// ... and even with the preference forced back on, the consent gate holds
		history(user, true, 30);
		assertThat(translated(user, true).has("historyItemId")).isFalse();

		// the contract "offers deletion": the existing item stays readable and deletable
		listHistory(user, "").andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(kept.toString()));
		mvc.perform(delete("/api/v1/me/history").header("Authorization", bearer(user))).andExpect(status().isNoContent());
		assertThat(historyRows(user)).isZero();
		// granting again resumes
		consent(user, "SAVE_AI_HISTORY", true);
		history(user, true, 30);
		assertThat(translated(user, true).has("historyItemId")).isTrue();
	}

	// ======================================================================================= B5.7 endpoints

	@Test
	void listPagesNewestFirstWithFiltersAndOwnership() throws Exception {
		UUID user = historyUser();
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			ids.add(UUID.fromString(translated(user, true).get("historyItemId").asString()));
		}
		UUID grammar = UUID.fromString(ok(mvc.perform(post("/api/v1/ai/transform").header("Authorization", bearer(user))
				.header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
				.content(("{\"task\":\"GRAMMAR_CORRECTION\",\"text\":\"" + IN_EN + "\",\"language\":\"en\","
						+ "\"options\":{\"mode\":\"GRAMMAR\"},\"saveToHistory\":true}").getBytes(StandardCharsets.UTF_8))))
				.get("historyItemId").asString());
		ids.add(grammar);

		JsonNode page1 = ok(listHistory(user, "?limit=2"));
		assertThat(page1.get("items")).hasSize(2);
		assertThat(page1.get("items").get(0).get("id").asString()).isEqualTo(ids.get(4).toString());
		assertThat(page1.get("items").get(1).get("id").asString()).isEqualTo(ids.get(3).toString());
		assertThat(page1.get("items").get(0).get("inputText").asString()).isEqualTo(IN_EN);
		assertThat(page1.get("items").get(1).get("outputText").asString()).isEqualTo(OUT_MOCK);
		assertThat(page1.get("items").get(1).get("sourceLanguage").asString()).isEqualTo("en");
		assertThat(page1.get("items").get(1).get("destinationLanguage").asString()).isEqualTo("om");
		String cursor = page1.get("nextCursor").asString();
		JsonNode page2 = ok(listHistory(user, "?limit=2&cursor=" + cursor));
		assertThat(page2.get("items").get(0).get("id").asString()).isEqualTo(ids.get(2).toString());
		JsonNode page3 = ok(listHistory(user, "?limit=2&cursor=" + page2.get("nextCursor").asString()));
		assertThat(page3.get("items")).hasSize(1);
		assertThat(page3.has("nextCursor")).isFalse();
		assertThat(ok(listHistory(user, "?featureCode=GRAMMAR_CORRECTION")).get("items")).hasSize(1);
		assertThat(ok(listHistory(user, "?favorite=true")).get("items")).isEmpty();
		listHistory(user, "?cursor=not-a-cursor").andExpect(status().isBadRequest());
		listHistory(user, "?limit=0").andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/me/history")).andExpect(status().isUnauthorized());

		// another user sees nothing and cannot touch an item
		UUID other = user("REGISTERED");
		listHistory(other, "").andExpect(jsonPath("$.items").isEmpty());
		String path = "/api/v1/me/history/" + ids.get(0);
		mvc.perform(get(path).header("Authorization", bearer(other))).andExpect(status().isNotFound());
		mvc.perform(patch(path).header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"favorite\":true}")).andExpect(status().isNotFound());
		mvc.perform(delete(path).header("Authorization", bearer(other))).andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/me/history").header("Authorization", bearer(other))).andExpect(status().isNoContent());
		assertThat(historyRows(user)).isEqualTo(5L);
	}

	@Test
	void getFavouriteAndDeleteOneItem() throws Exception {
		UUID user = historyUser();
		UUID a = UUID.fromString(translated(user, true).get("historyItemId").asString());
		UUID b = UUID.fromString(translated(user, true).get("historyItemId").asString());
		String path = "/api/v1/me/history/" + a;

		mvc.perform(get(path).header("Authorization", bearer(user))).andExpect(status().isOk())
				.andExpect(jsonPath("$.inputText").value(IN_EN)).andExpect(jsonPath("$.favorite").value(false))
				.andExpect(jsonPath("$.expiresAt").exists());
		// favourite: no expiry
		mvc.perform(patch(path).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"favorite\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.favorite").value(true))
				.andExpect(jsonPath("$.expiresAt").doesNotExist());
		assertThat(jdbc.queryForObject("SELECT expires_at IS NULL FROM user_history_items WHERE id = ?", Boolean.class, a)).isTrue();
		assertThat(ok(listHistory(user, "?favorite=true")).get("items")).hasSize(1);
		// unmark: a new expiry from now
		history(user, true, 10);
		mvc.perform(patch(path).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"favorite\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.favorite").value(false))
				.andExpect(jsonPath("$.expiresAt").exists());
		Instant expires = jdbc.queryForObject("SELECT expires_at FROM user_history_items WHERE id = ?", Timestamp.class, a)
				.toInstant();
		assertThat(Duration.between(Instant.now(), expires)).isBetween(Duration.ofDays(10).minusMinutes(1), Duration.ofDays(10));
		// a body without the flag is a 400
		mvc.perform(patch(path).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());

		mvc.perform(delete(path).header("Authorization", bearer(user))).andExpect(status().isNoContent());
		mvc.perform(get(path).header("Authorization", bearer(user))).andExpect(status().isNotFound());
		mvc.perform(delete(path).header("Authorization", bearer(user))).andExpect(status().isNotFound());
		assertThat(ok(listHistory(user, "")).get("items")).hasSize(1);
		assertThat(ok(listHistory(user, "")).get("items").get(0).get("id").asString()).isEqualTo(b.toString());
	}

	@Test
	void expiredItemsAreHiddenAndThePurgeJobKeepsFavourites() throws Exception {
		UUID user = historyUser();
		UUID live = UUID.fromString(translated(user, true).get("historyItemId").asString());
		UUID expired = UUID.fromString(translated(user, true).get("historyItemId").asString());
		UUID favourite = UUID.fromString(translated(user, true).get("historyItemId").asString());
		mvc.perform(patch("/api/v1/me/history/" + favourite).header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}")).andExpect(status().isOk());
		jdbc.update("UPDATE user_history_items SET created_at = NOW() - interval '40 days', "
				+ "expires_at = NOW() - interval '10 days' WHERE id = ?", expired);

		JsonNode items = ok(listHistory(user, "")).get("items");
		assertThat(items).hasSize(2);
		assertThat(items.findValuesAsString("id")).containsExactlyInAnyOrder(live.toString(), favourite.toString());
		mvc.perform(get("/api/v1/me/history/" + expired).header("Authorization", bearer(user))).andExpect(status().isNotFound());
		assertThat(historyRows(user)).isEqualTo(3L); // not purged yet

		RetentionJob.Result result = retention.run();
		assertThat(result.failed()).isEmpty();
		assertThat(result.purged().getOrDefault("user-history-items", 0)).isGreaterThanOrEqualTo(1);
		assertThat(jdbc.queryForList("SELECT id FROM user_history_items WHERE user_id = ?", UUID.class, user))
				.containsExactlyInAnyOrder(live, favourite);
	}

	@Test
	void historyTextNeverReachesLogsMetricsOrPlaintextColumns() throws Exception {
		UUID user = historyUser();
		Logger web = (Logger) LoggerFactory.getLogger("org.springframework.web");
		Logger http = (Logger) LoggerFactory.getLogger("org.springframework.http");
		Level webLevel = web.getLevel();
		Level httpLevel = http.getLevel();
		web.setLevel(Level.DEBUG);
		http.setLevel(Level.DEBUG);
		List<String> lines;
		try (CapturedLogs logs = new CapturedLogs()) {
			String k = key();
			UUID id = UUID.fromString(ok(translate(user, k, true)).get("historyItemId").asString());
			ok(translate(user, k, true)); // replay
			ok(listHistory(user, ""));
			ok(mvc.perform(get("/api/v1/me/history/" + id).header("Authorization", bearer(user))));
			ok(mvc.perform(patch("/api/v1/me/history/" + id).header("Authorization", bearer(user))
					.contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}")));
			mvc.perform(patch("/api/v1/me/history/" + id).header("Authorization", bearer(user))
					.contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":\"" + IN_EN + "\"}"))
					.andExpect(status().isBadRequest());
			mvc.perform(delete("/api/v1/me/history/" + id).header("Authorization", bearer(user))).andExpect(status().isNoContent());
			mvc.perform(get("/api/v1/me/history/" + id).header("Authorization", bearer(user))).andExpect(status().isNotFound());
			translated(user, true);
			mvc.perform(delete("/api/v1/me/history").header("Authorization", bearer(user))).andExpect(status().isNoContent());
			lines = logs.texts();
			assertThat(logs.size()).isPositive();
		}
		finally {
			web.setLevel(webLevel);
			http.setLevel(httpLevel);
		}
		for (String line : lines) {
			for (String secret : List.of(IN_EN, OUT_MOCK, "Zebra", "lighthouse")) {
				assertThat(line).as("log line").doesNotContain(secret);
			}
		}
		for (Meter m : meters.getMeters()) {
			assertThat(m.getId().getName()).doesNotContain("Zebra");
			m.getId().getTags().forEach(t -> assertThat(t.getKey() + "=" + t.getValue()).doesNotContain("Zebra"));
		}
		// with history on and an item present
		translated(user, true);
		assertNoTableHolds(List.of(IN_EN, OUT_MOCK, "lighthouse"));
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
