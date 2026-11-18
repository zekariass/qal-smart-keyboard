package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.qalsmartkeyboard.aigateway.AiCommand.Caller;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.Behavior;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.FailureMode;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreakers;
import com.qalsmartkeyboard.aigateway.spi.Addressee;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.AiProvider;
import com.qalsmartkeyboard.aigateway.spi.ProviderCallMetadata;
import com.qalsmartkeyboard.aigateway.spi.ProviderUsage;
import com.qalsmartkeyboard.aigateway.spi.TransformRequest;
import com.qalsmartkeyboard.aigateway.spi.TransformResult;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateResult;
import com.qalsmartkeyboard.aigateway.spi.UserText;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.testsupport.contract.OpenApiContract;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;
import com.qalsmartkeyboard.usage.Metering;
import com.qalsmartkeyboard.usage.QuotaExceededException;

import tools.jackson.databind.json.JsonMapper;

/**
 * B5.3 "done when": the pipeline end to end against PostgreSQL 18 with the B5.1 mock providers. The seeded {@code mock}
 * row (the test configuration's {@link MockAiProvider}) is the primary; the seeded {@code wiremock-http} row is served
 * by {@link ScriptedProvider}, an in-process stand-in whose translation output a test can script (the deterministic
 * mock always answers {@code "[mock:<dst>] <text>"}, Latin script). Both rows are enabled per test with the V10
 * capabilities; templates are seeded per test. Every cache TTL is zero. Real metering, entitlements (V1 FREE plan),
 * router, breaker and ledger.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s",
		"qal.ai.registry-cache-ttl=0s", "qal.ai.routing.policy-cache-ttl=0s", "qal.ai.routing.kill-switch-cache-ttl=0s",
		"qal.ai.routing.cost-cache-ttl=0s", "qal.ai.routing.attempt-timeout=1s", "qal.ai.routing.deadline=3s",
		"qal.ai.pipeline.deadline=3s", "qal.ai.pipeline.catalog-cache-ttl=0s" })
@Testcontainers
@Import(AiPipelineIT.Adapters.class)
class AiPipelineIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	/** Distinctive input text: no log line, exception or ledger column may ever contain it. */
	static final String SECRET = "Zebra lighthouse 7741 marmalade";
	static final String SECRET_AM = "ሚስጥራዊ ቃል ጽሑፍ";

	/** Stand-in for the seeded {@code wiremock-http} provider: scripted translations, instructions captured. */
	static final class ScriptedProvider implements AiProvider {

		final MockAiProvider delegate = new MockAiProvider("wiremock-http", Behavior.ok());
		final Deque<String> translations = new ArrayDeque<>();
		final AtomicInteger calls = new AtomicInteger();
		final AtomicReference<TranslateRequest> lastTranslate = new AtomicReference<>();
		final AtomicReference<TransformRequest> lastTransform = new AtomicReference<>();

		@Override
		public String code() {
			return "wiremock-http";
		}

		void reset() {
			delegate.setBehavior(Behavior.ok());
			synchronized (translations) {
				translations.clear();
			}
			lastTranslate.set(null);
			lastTransform.set(null);
		}

		@Override
		public TranslateResult translate(TranslateRequest request, Duration timeout) {
			calls.incrementAndGet();
			lastTranslate.set(request);
			String scripted;
			synchronized (translations) {
				scripted = translations.size() > 1 ? translations.poll() : translations.peek();
			}
			if (scripted == null) {
				return delegate.translate(request, timeout);
			}
			UserText out = UserText.of(scripted);
			return new TranslateResult(out, null, null, List.of(),
					new ProviderCallMetadata("wiremock-http", "mock-mt-1", null, Duration.ofMillis(5)),
					ProviderUsage.ofCharacters(request.text().characters(), out.characters()));
		}

		@Override
		public TransformResult llmTransform(TransformRequest request, Duration timeout) {
			calls.incrementAndGet();
			lastTransform.set(request);
			return delegate.llmTransform(request, timeout);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Adapters {
		@Bean
		ScriptedProvider scriptedProvider() {
			return new ScriptedProvider();
		}
	}

	@Autowired
	AiPipeline pipeline;
	@Autowired
	Metering metering;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	CircuitBreakers breakers;
	@Autowired
	JsonMapper json;
	@Autowired
	@Qualifier("mockAiProvider")
	MockAiProvider mock;
	@Autowired
	ScriptedProvider fallback;

	UUID translateAnyTemplate;
	UUID translateAmTemplate;
	UUID grammarTemplate;

	@BeforeEach
	void enable() {
		clean();
		jdbc.update("UPDATE ai_providers SET provider_status = 'ACTIVE', dpa_signed_at = NOW() "
				+ "WHERE provider_code IN ('mock', 'wiremock-http')");
		mock.setBehavior(Behavior.ok());
		fallback.reset();
		breakers.resetAll();
		String body = """
				You translate from {{sourceLanguageName}} into {{destinationLanguageName}}, style {{style}}.
				{{addresseeInstruction}}
				{{culturalInstruction}}
				{{calendarContext}}""";
		translateAnyTemplate = template("TRANSLATE", 1, "*", body);
		translateAmTemplate = template("TRANSLATE", 2, "am", "Amharic v2. " + body);
		grammarTemplate = template("GRAMMAR_CORRECTION", 1, "*", "Correct grammar ({{mode}}). {{calendarContext}}");
	}

	@AfterEach
	void clean() {
		jdbc.update("DELETE FROM ai_routing_policies");
		jdbc.update("DELETE FROM ai_prompt_templates");
		jdbc.update("UPDATE ai_providers SET provider_status = 'DISABLED', dpa_signed_at = NULL "
				+ "WHERE provider_code IN ('mock', 'wiremock-http')");
	}

	UUID template(String feature, int version, String scope, String body) {
		return jdbc.queryForObject("""
				INSERT INTO ai_prompt_templates (feature_code, template_version, language_scope, model_family,
				       template_body, evaluation_status, is_active)
				VALUES (?, ?, ?, '*', ?, 'APPROVED', TRUE) RETURNING id""", UUID.class, feature, version, scope, body);
	}

	UUID user(String status) {
		return jdbc.queryForObject("""
				INSERT INTO users (account_type, account_status, time_zone) VALUES ('REGISTERED', ?, 'Africa/Addis_Ababa')
				RETURNING id""", UUID.class, status);
	}

	static String key() {
		return UUID.randomUUID().toString();
	}

	long[] counter(UUID user, String feature) {
		return jdbc.query("SELECT used_units, reserved_units FROM usage_counters WHERE user_id = ? AND feature_code = ?",
				rs -> rs.next() ? new long[] { rs.getLong(1), rs.getLong(2) } : new long[] { 0, 0 }, user, feature);
	}

	Map<String, Object> reservation(UUID user, String key) {
		return jdbc.queryForMap("SELECT id, reservation_status, release_reason, actual_units FROM usage_reservations "
				+ "WHERE user_id = ? AND idempotency_key = ?", user, key);
	}

	Map<String, Object> request(UUID id) {
		return jdbc.queryForMap("""
				SELECT r.*, p.provider_code, m.model_code, s.language_code AS src, d.language_code AS dst,
				       array_to_string(r.guardrail_flags, ',') AS flags, r::text AS whole_row
				  FROM ai_requests r LEFT JOIN ai_providers p ON p.id = r.provider_id
				  LEFT JOIN ai_models m ON m.id = r.model_id
				  LEFT JOIN supported_languages s ON s.id = r.source_language_id
				  LEFT JOIN supported_languages d ON d.id = r.destination_language_id
				 WHERE r.id = ?""", id);
	}

	List<Map<String, Object>> requests(UUID user) {
		return jdbc.queryForList("SELECT id, request_status, error_code, idempotency_key, usage_source, "
				+ "r::text AS whole_row FROM ai_requests r WHERE user_id = ? ORDER BY created_at", user);
	}

	/** Nothing the user typed, and nothing a provider returned, is in any column of the user's ai_requests rows. */
	void assertNoContentStored(UUID user, String... texts) {
		for (Map<String, Object> row : requests(user)) {
			for (String t : texts) {
				assertThat((String) row.get("whole_row")).doesNotContain(t);
			}
		}
		for (String t : texts) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_requests r WHERE r::text LIKE ?", Long.class,
					"%" + t + "%")).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_reservations r WHERE r::text LIKE ?",
					Long.class, "%" + t + "%")).isZero();
		}
	}

	void assertNoContentLogged(CapturedLogs logs, String... texts) {
		for (String line : logs.texts()) {
			for (String t : texts) {
				assertThat(line).doesNotContain(t);
			}
		}
	}

	void assertMatchesContract(String path, Object body) {
		SimpleResponse response = SimpleResponse.Builder.status(200).withContentType("application/json")
				.withHeader("X-Correlation-Id", "abc").withBody(json.writeValueAsString(body)).build();
		ValidationReport report = OpenApiContract.validator().validateResponse(path, Request.Method.POST, response);
		assertThat(report.hasErrors()).as(report.toString()).isFalse();
	}

	// ======================================================================================= happy paths

	@Test
	void translateHappyPathReservesThenSettlesAndRecordsMetadataOnly() {
		UUID user = user("ACTIVE");
		String k = key();
		AiResult.TranslateResponse r;
		try (CapturedLogs logs = new CapturedLogs()) {
			r = (AiResult.TranslateResponse) pipeline
					.execute(AiCommand.Translate.of(new Caller(user, null, AiCommand.Surface.KEYBOARD, k, null),
							SECRET, "en", "om"));
			assertNoContentLogged(logs, SECRET);
		}
		assertThat(r.translatedText()).isEqualTo("[mock:om] " + SECRET);
		assertThat(r.toString()).doesNotContain(SECRET);
		assertThat(r.qualityTier()).hasToString("BETA");
		assertThat(r.notes()).extracting(AiResult.AiNote::type).contains("QUALITY");
		assertThat(r.usage().unitsCharged()).isEqualTo(1);
		assertThat(r.usage().usageSource()).isEqualTo("PLAN_ALLOWANCE");
		assertThat(r.usage().remaining()).isEqualTo(9L); // FREE: 10 translations a day
		assertThat(r.usage().resetsAt()).isNotNull();

		Map<String, Object> res = reservation(user, k);
		assertThat(res.get("reservation_status")).isEqualTo("SETTLED");
		assertThat(res.get("actual_units")).isEqualTo(1L);
		assertThat(r.requestId()).isEqualTo(res.get("id"));
		assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
		assertThat(jdbc.queryForList("SELECT event_type FROM usage_events WHERE subject_id = ? ORDER BY created_at",
				String.class, user)).containsExactly("RESERVE", "SETTLE");

		Map<String, Object> row = request(r.requestId());
		assertThat(row.get("request_status")).isEqualTo("SUCCEEDED");
		assertThat(row.get("feature_code")).isEqualTo("TRANSLATE");
		assertThat(row.get("surface")).isEqualTo("KEYBOARD");
		assertThat(row.get("src")).isEqualTo("en");
		assertThat(row.get("dst")).isEqualTo("om");
		assertThat(row.get("provider_code")).isEqualTo("mock");
		assertThat(row.get("model_code")).isEqualTo("mock-mt-1");
		assertThat(row.get("prompt_template_id")).isEqualTo(translateAnyTemplate); // om: the '*' template
		assertThat(row.get("usage_source")).isEqualTo("PLAN_ALLOWANCE");
		assertThat(row.get("metered_units")).isEqualTo(1L);
		assertThat(row.get("input_character_count")).isEqualTo(SECRET.length());
		assertThat(row.get("output_character_count")).isEqualTo(("[mock:om] " + SECRET).length());
		assertThat(row.get("idempotency_key")).isEqualTo(k);
		assertThat(row.get("duration_ms")).isNotNull();
		assertThat(row.get("error_code")).isNull();
		assertThat(row.get("content_retained")).isEqualTo(false);
		assertNoContentStored(user, SECRET, "Zebra");

		assertMatchesContract("/api/v1/ai/translate", r);
	}

	@Test
	void grammarHappyPathOnAmharicKeepsTheScriptAndSettles() {
		UUID user = user("ACTIVE");
		String k = key();
		AiResult.TransformResponse r = (AiResult.TransformResponse) pipeline.execute(
				AiCommand.Transform.of(Caller.of(user, k), AiFeature.GRAMMAR_CORRECTION, "  " + SECRET_AM + "   ነው  ",
						"am"));
		assertThat(r.resultText()).isEqualTo(SECRET_AM + " ነው");
		assertThat(r.task()).isEqualTo("GRAMMAR_CORRECTION");
		assertThat(r.usage().unitsCharged()).isEqualTo(1);
		assertThat(reservation(user, k).get("reservation_status")).isEqualTo("SETTLED");
		assertThat(counter(user, "GRAMMAR_CORRECTION")).containsExactly(1, 0);
		Map<String, Object> row = request(r.requestId());
		assertThat(row.get("request_status")).isEqualTo("SUCCEEDED");
		assertThat(row.get("model_code")).isEqualTo("mock-llm-1");
		assertThat(row.get("prompt_template_id")).isEqualTo(grammarTemplate);
		assertThat(row.get("src")).isEqualTo("am");
		assertNoContentStored(user, SECRET_AM);
		assertMatchesContract("/api/v1/ai/transform", r);
	}

	@Test
	void destinationProfileAndPreferencesFillTheAddresseeAndThePromptCarriesEthiopianCalendarContext() {
		UUID user = user("ACTIVE");
		UUID profile = jdbc.queryForObject("""
				INSERT INTO user_destination_profiles (user_id, label_ciphertext, key_version, destination_language_id,
				       formality, addressee_gender)
				SELECT ?, '\\x00'::bytea, 1, id, 'HONORIFIC', 'FEMININE' FROM supported_languages WHERE language_code = 'am'
				RETURNING id""", UUID.class, user);
		jdbc.update("INSERT INTO ai_routing_policies (feature_code, model_id, priority, traffic_weight, is_enabled, "
				+ "valid_from) SELECT 'TRANSLATE', m.id, 10, 100, TRUE, NOW() - INTERVAL '1 hour' FROM ai_models m "
				+ "JOIN ai_providers p ON p.id = m.provider_id WHERE p.provider_code = 'wiremock-http' "
				+ "AND m.model_code = 'mock-mt-1'");
		fallback.translations.add("ነገ ይመጣሉ?");

		AiResult.TranslateResponse r = (AiResult.TranslateResponse) pipeline.execute(new AiCommand.Translate(
				Caller.of(user, key()), UserText.of("Are you coming tomorrow?"), "en", "am", null, null, null,
				new Addressee(null, null), new AiCommand.CulturalOptions(null, "ETHIOPIAN", null, null), profile));
		assertThat(r.translatedText()).isEqualTo("ነገ ይመጣሉ?");
		TranslateRequest sent = fallback.lastTranslate.get();
		assertThat(sent.addressee()).isEqualTo(
				new Addressee(Addressee.Formality.HONORIFIC, Addressee.Gender.FEMININE));
		assertThat(sent.style()).isEqualTo("NATURAL"); // preferences default
		assertThat(sent.instructions()).startsWith("Amharic v2.") // the 'am' template beats '*'
				.contains("from English into Amharic").contains("honorific").contains("Ethiopian clock")
				.contains("in the Ethiopian calendar (").doesNotContain("Are you coming");
		assertThat(request(r.requestId()).get("prompt_template_id")).isEqualTo(translateAmTemplate);
		assertThat(r.notes()).extracting(AiResult.AiNote::type).doesNotContain("AMBIGUITY");
	}

	// ======================================================================================= refusals

	@Test
	void quotaExceededCallsNoProviderAndChargesNothing() {
		UUID user = user("ACTIVE");
		for (int i = 0; i < 3; i++) { // FREE: 3 rewrites a day
			pipeline.execute(AiCommand.Transform.of(Caller.of(user, key()), AiFeature.REWRITE, "Please rewrite this",
					"en"));
		}
		int calls = mock.callCount();
		String k = key();
		assertThatThrownBy(() -> pipeline.execute(AiCommand.Transform.of(Caller.of(user, k), AiFeature.REWRITE,
				SECRET, "en"))).isInstanceOfSatisfying(QuotaExceededException.class, e -> {
					assertThat(e.code()).isEqualTo(ApiErrorCode.QUOTA_EXCEEDED);
					assertThat(e.getMessage()).doesNotContain(SECRET);
				});
		assertThat(mock.callCount()).isEqualTo(calls);
		assertThat(counter(user, "REWRITE")).containsExactly(3, 0);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_reservations WHERE idempotency_key = ?", Long.class,
				k)).isZero();
		Map<String, Object> rejected = requests(user).getLast();
		assertThat(rejected.get("request_status")).isEqualTo("REJECTED");
		assertThat(rejected.get("error_code")).isEqualTo("QUOTA_EXCEEDED");
		assertThat(rejected.get("idempotency_key")).isNull(); // the key stays usable (ADR 0011)
	}

	@Test
	void aFeatureOutsideThePlanIsNotEntitled() {
		UUID user = user("ACTIVE");
		int calls = mock.callCount();
		AiCommand.Transform tone = new AiCommand.Transform(Caller.of(user, key()), AiFeature.TONE_ADJUST,
				UserText.of(SECRET), "en", null, "POLITE", null, null, null);
		assertThatThrownBy(() -> pipeline.execute(tone)).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo(ApiErrorCode.FEATURE_NOT_ENTITLED)); // FREE has no TONE_ADJUST
		assertThat(mock.callCount()).isEqualTo(calls);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_reservations WHERE user_id = ?", Long.class, user))
				.isZero();
		assertThat(requests(user)).singleElement()
				.satisfies(row -> assertThat(row.get("error_code")).isEqualTo("FEATURE_NOT_ENTITLED"));
	}

	@Test
	void deletionPendingAndSuspendedAccountsAreRefusedBeforeAnything() {
		int calls = mock.callCount();
		UUID pending = user("DELETION_PENDING");
		assertThatThrownBy(() -> pipeline.execute(AiCommand.Translate.of(Caller.of(pending, key()), SECRET, "en",
				"om"))).isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.code()).isEqualTo(ApiErrorCode.DELETION_PENDING));
		UUID suspended = user("SUSPENDED");
		assertThatThrownBy(() -> pipeline.execute(AiCommand.Translate.of(Caller.of(suspended, key()), SECRET, "en",
				"om"))).isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.code()).isEqualTo(ApiErrorCode.ACCOUNT_SUSPENDED));
		assertThat(mock.callCount()).isEqualTo(calls);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_reservations WHERE user_id IN (?, ?)", Long.class,
				pending, suspended)).isZero();
	}

	@Test
	void validationFailsBeforeReserving() {
		UUID user = user("ACTIVE");
		assertThatThrownBy(() -> pipeline.execute(AiCommand.Translate.of(Caller.of(user, key()), "x".repeat(5001),
				"en", "am"))).isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.code()).isEqualTo(ApiErrorCode.INPUT_TOO_LONG));
		assertThatThrownBy(() -> pipeline.execute(AiCommand.Translate.of(Caller.of(user, key()), SECRET, "en",
				"xx"))).isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.code()).isEqualTo(ApiErrorCode.LANGUAGE_NOT_SUPPORTED));
		assertThatThrownBy(() -> pipeline.execute(AiCommand.Translate.of(Caller.of(user, null), SECRET, "en",
				"am"))).isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_reservations WHERE user_id = ?", Long.class, user))
				.isZero();
	}

	// ======================================================================================= guardrails

	@Test
	void latinOutputForAmharicIsRetriedThenFallsBackThenFailsWithNoCharge() {
		UUID user = user("ACTIVE");
		String k = key();
		int primary = mock.callCount();
		int secondary = fallback.calls.get();
		try (CapturedLogs logs = new CapturedLogs()) {
			assertThatThrownBy(() -> pipeline.execute(AiCommand.Translate.of(Caller.of(user, k), SECRET, "en", "am")))
					.isInstanceOfSatisfying(ApiException.class, e -> {
						assertThat(e.code()).isEqualTo(ApiErrorCode.OUTPUT_GUARDRAIL_FAILED);
						assertThat(e.status().value()).isEqualTo(422);
						assertThat(e.getMessage()).doesNotContain(SECRET);
					});
			assertNoContentLogged(logs, SECRET, "[mock:am]");
		}
		assertThat(mock.callCount() - primary).isEqualTo(2); // first call + one retry on the same route
		assertThat(fallback.calls.get() - secondary).isEqualTo(1); // then one fallback
		Map<String, Object> res = reservation(user, k);
		assertThat(res.get("reservation_status")).isEqualTo("RELEASED");
		assertThat(res.get("release_reason")).isEqualTo("FAILED");
		assertThat(counter(user, "TRANSLATE")).containsExactly(0, 0);
		Map<String, Object> row = request((UUID) res.get("id"));
		assertThat(row.get("request_status")).isEqualTo("FAILED");
		assertThat(row.get("error_code")).isEqualTo("OUTPUT_GUARDRAIL_FAILED");
		assertThat((String) row.get("flags")).contains("SCRIPT_MISMATCH");
		assertThat(row.get("metered_units")).isEqualTo(0L);
		assertThat(row.get("idempotency_key")).isEqualTo(k);
		assertNoContentStored(user, SECRET);

		// D25: the same key replays the original failure, without a provider call or a charge
		int calls = mock.callCount() + fallback.calls.get();
		assertThatThrownBy(() -> pipeline.execute(AiCommand.Translate.of(Caller.of(user, k), SECRET, "en", "am")))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.code()).isEqualTo(ApiErrorCode.OUTPUT_GUARDRAIL_FAILED));
		assertThat(mock.callCount() + fallback.calls.get()).isEqualTo(calls);
		assertThat(counter(user, "TRANSLATE")).containsExactly(0, 0);
	}

	@Test
	void aGuardrailFailureIsRecoveredByTheFallbackWhichIsCharged() {
		UUID user = user("ACTIVE");
		fallback.translations.add("ነገ ትመጣለህ?");
		AiResult.TranslateResponse r = (AiResult.TranslateResponse) pipeline
				.execute(AiCommand.Translate.of(Caller.of(user, key()), "Are you coming tomorrow?", "en", "am"));
		assertThat(r.translatedText()).isEqualTo("ነገ ትመጣለህ?");
		assertThat(r.notes()).extracting(AiResult.AiNote::type).contains("AMBIGUITY"); // gender unknown, informal
		Map<String, Object> row = request(r.requestId());
		assertThat(row.get("provider_code")).isEqualTo("wiremock-http");
		assertThat(row.get("request_status")).isEqualTo("SUCCEEDED");
		assertThat((String) row.get("flags")).contains("SCRIPT_MISMATCH"); // the mock's rejected attempts
		assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
	}

	@Test
	void aGuardrailFailureIsRecoveredByTheRetryOnTheSameProvider() {
		UUID user = user("ACTIVE");
		jdbc.update("INSERT INTO ai_routing_policies (feature_code, model_id, priority, traffic_weight, is_enabled, "
				+ "valid_from) SELECT 'TRANSLATE', m.id, 10, 100, TRUE, NOW() - INTERVAL '1 hour' FROM ai_models m "
				+ "JOIN ai_providers p ON p.id = m.provider_id WHERE p.provider_code = 'wiremock-http' "
				+ "AND m.model_code = 'mock-mt-1'");
		fallback.translations.add("Not Amharic at all, sorry");
		fallback.translations.add("ነገ ትመጣለህ?");
		int secondary = fallback.calls.get();
		AiResult.TranslateResponse r = (AiResult.TranslateResponse) pipeline
				.execute(AiCommand.Translate.of(Caller.of(user, key()), "Are you coming tomorrow?", "en", "am"));
		assertThat(r.translatedText()).isEqualTo("ነገ ትመጣለህ?");
		assertThat(fallback.calls.get() - secondary).isEqualTo(2);
		assertThat(request(r.requestId()).get("provider_code")).isEqualTo("wiremock-http");
	}

	// ======================================================================================= failover, cancel

	@Test
	void providerFailoverSettlesOnTheFallbacksUsage() {
		UUID user = user("ACTIVE");
		mock.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		String k = key();
		AiResult.TranslateResponse r;
		try (CapturedLogs logs = new CapturedLogs()) {
			r = (AiResult.TranslateResponse) pipeline.execute(AiCommand.Translate.of(Caller.of(user, k), SECRET,
					"en", "om"));
			assertNoContentLogged(logs, SECRET);
		}
		assertThat(r.translatedText()).isEqualTo("[mock:om] " + SECRET); // the fallback's (mock dialect) output
		assertThat(reservation(user, k).get("reservation_status")).isEqualTo("SETTLED");
		assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
		Map<String, Object> row = request(r.requestId());
		assertThat(row.get("provider_code")).isEqualTo("wiremock-http");
		assertThat(row.get("input_character_count")).isEqualTo(SECRET.length());
		assertThat(row.get("metered_units")).isEqualTo(1L);
	}

	@Test
	void everyProviderFailingReleasesWithTheRouteCode() {
		UUID user = user("ACTIVE");
		mock.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		fallback.delegate.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		String k = key();
		assertThatThrownBy(() -> pipeline.execute(AiCommand.Translate.of(Caller.of(user, k), SECRET, "en", "om")))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.code()).isEqualTo(ApiErrorCode.PROVIDER_UNAVAILABLE));
		assertThat(reservation(user, k).get("reservation_status")).isEqualTo("RELEASED");
		assertThat(counter(user, "TRANSLATE")).containsExactly(0, 0);
	}

	@Test
	void cancellationReleasesTheReservationAndRecordsACancelledRequest() throws Exception {
		UUID user = user("ACTIVE");
		mock.setBehavior(Behavior.slow(Duration.ofMillis(900)));
		int before = mock.callCount();
		String k = key();
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				pipeline.execute(AiCommand.Translate.of(Caller.of(user, k), SECRET, "en", "om"));
			} catch (Throwable t) {
				thrown.set(t);
			}
			interruptedAfter.set(Thread.currentThread().isInterrupted());
		});
		worker.start();
		for (int i = 0; i < 500 && mock.callCount() == before; i++) {
			Thread.sleep(10); // wait until the provider call is in progress
		}
		Thread.sleep(100);
		worker.interrupt();
		worker.join(5000);

		assertThat(thrown.get()).isInstanceOf(AiRequestCancelledException.class);
		assertThat(interruptedAfter.get()).isTrue(); // the flag is restored for the caller
		Map<String, Object> res = reservation(user, k);
		assertThat(res.get("reservation_status")).isEqualTo("RELEASED");
		assertThat(res.get("release_reason")).isEqualTo("CANCELLED");
		assertThat(counter(user, "TRANSLATE")).containsExactly(0, 0);
		assertThat(request((UUID) res.get("id")).get("request_status")).isEqualTo("CANCELLED");
	}

	// ======================================================================================= idempotency seam

	@Test
	void replaysNeverChargeTwice() {
		UUID user = user("ACTIVE");
		String k = key();
		AiResult first = pipeline.execute(AiCommand.Translate.of(Caller.of(user, k), SECRET, "en", "om"));
		int calls = mock.callCount();
		// B5.4: answered from the encrypted replay cache (AiReplayCacheIT covers expiry, concurrency, reuse)
		AiResult.TranslateResponse again = (AiResult.TranslateResponse) pipeline
				.execute(AiCommand.Translate.of(Caller.of(user, k), SECRET, "en", "om"));
		assertThat(again.translatedText()).isEqualTo(((AiResult.TranslateResponse) first).translatedText());
		assertThat(again.requestId()).isEqualTo(((AiResult.TranslateResponse) first).requestId());
		assertThat(again.replayed()).isTrue();
		assertThat(mock.callCount()).isEqualTo(calls);
		assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
		assertThat(requests(user)).extracting(row -> row.get("usage_source")).containsExactly("PLAN_ALLOWANCE");
		assertMatchesContract("/api/v1/ai/translate", again);

		// in flight: a reservation with the key exists and is still open
		String open = key();
		metering.reserve(user, "TRANSLATE", 1, open);
		assertThatThrownBy(() -> pipeline.execute(AiCommand.Translate.of(Caller.of(user, open), SECRET, "en", "om")))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.code()).isEqualTo(ApiErrorCode.REQUEST_IN_PROGRESS);
					assertThat(e.headers()).containsKey("Retry-After");
				});
	}

	@Test
	void detectLanguageIsUnmeteredAndRecorded() {
		UUID user = user("ACTIVE");
		AiResult.DetectLanguageResponse r = (AiResult.DetectLanguageResponse) pipeline
				.execute(new AiCommand.Detect(Caller.of(user, null), UserText.of(SECRET_AM), List.of()));
		assertThat(r.languages().get(0).languageCode()).isEqualTo("am");
		assertThat(r.usage()).isNull();
		assertThat(requests(user)).singleElement().satisfies(row -> {
			assertThat(row.get("request_status")).isEqualTo("SUCCEEDED");
			assertThat(row.get("usage_source")).isEqualTo("UNMETERED");
		});
		assertMatchesContract("/api/v1/ai/detect-language", r);
	}

	@Test
	void theTransactionRuleIsEnforced() {
		org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
				new org.springframework.jdbc.support.JdbcTransactionManager(jdbc.getDataSource()));
		UUID user = user("ACTIVE");
		assertThatThrownBy(() -> tx.executeWithoutResult(s -> pipeline
				.execute(AiCommand.Translate.of(Caller.of(user, key()), SECRET, "en", "om"))))
				.isInstanceOf(IllegalStateException.class);
	}
}
