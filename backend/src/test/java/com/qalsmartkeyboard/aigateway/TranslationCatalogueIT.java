package com.qalsmartkeyboard.aigateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.aigateway.RouteResult.Outcome;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreakers;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateResult;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * ADR 0040 on PostgreSQL 18 with the V24 seeds and the real OpenAI and Google adapters on local stubs, enabled the way
 * an operator would (as in {@link AiVendorRoutingIT}): the catalogue endpoint lists the Google v2 languages as
 * translation-only rows with autonyms, scripts and directions and revalidates with its ETag; a pair without English
 * (fr to am, es to ar) is routed by the any-pair rows in the D46 order (OpenAI, then Google); a pair outside the model
 * language sets is refused; a translation-only language is never a typing language. Every HTTP answer is validated
 * against the contract.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.ai.registry-cache-ttl=0s",
		"qal.ai.routing.policy-cache-ttl=0s", "qal.ai.routing.kill-switch-cache-ttl=0s",
		"qal.ai.routing.cost-cache-ttl=0s", "qal.ai.routing.attempt-timeout=2s", "qal.ai.routing.deadline=4s",
		"qal.ai.pipeline.catalog-cache-ttl=0s" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class TranslationCatalogueIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final WireMockServer VENDORS = new WireMockServer(options().dynamicPort());

	static {
		VENDORS.start();
	}

	@DynamicPropertySource
	static void vendors(DynamicPropertyRegistry registry) {
		registry.add("qal.ai.openai.api-key", () -> "sk-test-only");
		registry.add("qal.ai.openai.model", () -> "test-model");
		registry.add("qal.ai.openai.base-url", () -> VENDORS.baseUrl() + "/v1");
		registry.add("qal.ai.google-translate.api-key", () -> "AIza-test-only");
		registry.add("qal.ai.google-translate.base-url", VENDORS::baseUrl);
	}

	@AfterAll
	static void stopVendors() {
		VENDORS.stop();
	}

	static final String OPENAI = "/v1/chat/completions";
	static final String GOOGLE = "/language/translate/v2";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AiRouter router;
	@Autowired
	CircuitBreakers breakers;
	@Autowired
	AccessTokenService tokens;

	@BeforeEach
	void enableLikeAnOperator() {
		VENDORS.resetAll();
		breakers.resetAll();
		jdbc.update("UPDATE ai_providers SET provider_status = 'ACTIVE', dpa_signed_at = NOW() "
				+ "WHERE provider_code IN ('openai', 'google-translate')");
		jdbc.update("""
				UPDATE ai_routing_policies SET is_enabled = TRUE, valid_from = NOW() - INTERVAL '1 hour'
				 WHERE model_id IN (SELECT m.id FROM ai_models m JOIN ai_providers p ON p.id = m.provider_id
				                     WHERE p.provider_code IN ('openai', 'google-translate'))""");
	}

	@AfterEach
	void disable() {
		jdbc.update("UPDATE ai_routing_policies SET is_enabled = FALSE");
		jdbc.update("UPDATE ai_providers SET provider_status = 'DISABLED', dpa_signed_at = NULL "
				+ "WHERE provider_code IN ('openai', 'google-translate')");
		jdbc.update("UPDATE ai_model_translation_languages SET is_enabled = TRUE");
	}

	void openAiAnswers(String content) {
		VENDORS.stubFor(post(urlPathEqualTo(OPENAI)).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("""
						{"id":"chatcmpl-1","model":"test-model","choices":[{"index":0,"message":{"role":"assistant",
						 "content":"%s","refusal":null},"finish_reason":"stop"}],
						 "usage":{"prompt_tokens":30,"completion_tokens":5,"total_tokens":35}}""".formatted(content))));
	}

	void openAiDown() {
		VENDORS.stubFor(post(urlPathEqualTo(OPENAI)).willReturn(aResponse().withStatus(503)));
	}

	void googleAnswers(String text) {
		VENDORS.stubFor(post(urlPathEqualTo(GOOGLE)).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"data\":{\"translations\":[{\"translatedText\":\"" + text + "\"}]}}")));
	}

	RouteResult<TranslateResult> translate(String src, String dst) {
		return router.route(RouteRequest.translate(TranslateRequest.of("Bonjour", src, dst), null, null));
	}

	// ------------------------------------------------------------------------------------------------- seeds

	@Test
	void v24SeedsTranslationOnlyLanguagesAndPerModelSets() {
		Integer translationOnly = jdbc.queryForObject(
				"SELECT count(*) FROM supported_languages WHERE NOT is_typing_language", Integer.class);
		assertThat(translationOnly).isEqualTo(183); // Google v2: 194 rows - 7 already present - 4 regional variants
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM supported_languages WHERE is_typing_language", Integer.class)).isEqualTo(11);
		assertThat(jdbc.queryForMap("""
				SELECT english_name, native_name, script_code, writing_direction, support_level FROM supported_languages
				 WHERE language_code = 'he'""")).containsEntry("native_name", "עברית").containsEntry("script_code", "Hebr")
				.containsEntry("writing_direction", "RTL");
		for (String rtl : List.of("ar", "he", "fa", "ur", "ps", "sd", "ug", "yi", "dv", "ckb", "ms-Arab", "pa-Arab")) {
			assertThat(jdbc.queryForObject("SELECT writing_direction FROM supported_languages WHERE language_code = ?",
					String.class, rtl)).as(rtl).isEqualTo("RTL");
		}
		String perModel = """
				SELECT count(*) FROM ai_model_translation_languages t JOIN ai_models m ON m.id = t.model_id
				  JOIN ai_providers p ON p.id = m.provider_id WHERE p.provider_code = ? AND m.model_code = ?""";
		assertThat(jdbc.queryForObject(perModel, Integer.class, "openai", "openai-llm")).isEqualTo(190);
		assertThat(jdbc.queryForObject(perModel, Integer.class, "google-translate", "google-nmt")).isEqualTo(190);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ai_model_translation_languages t JOIN supported_languages l ON l.id = t.language_id
				 WHERE l.language_code IN ('aa', 'sid', 'wal', 'gez')""", Integer.class)).isZero();
	}

	// ------------------------------------------------------------------------------------------------- endpoint

	@Test
	void theCatalogueEndpointListsEveryProviderLanguageAndRevalidates() throws Exception {
		String body = mvc.perform(get("/api/v1/ai/capabilities")).andExpect(status().isOk())
				.andExpect(header().exists("ETag"))
				.andExpect(jsonPath("$.translationLanguages.length()").value(190))
				// the keyboard languages first (catalogue order), then the rest by English name
				.andExpect(jsonPath("$.translationLanguages[0].code").value("am"))
				.andExpect(jsonPath("$.translationLanguages[0].isTypingLanguage").value(true))
				.andExpect(jsonPath("$.translationLanguages[?(@.code=='fr')].nativeName").value("Français"))
				.andExpect(jsonPath("$.translationLanguages[?(@.code=='fr')].isTypingLanguage").value(false))
				.andExpect(jsonPath("$.translationLanguages[?(@.code=='fr')].asSource").value(true))
				.andExpect(jsonPath("$.translationLanguages[?(@.code=='fr')].asDestination").value(true))
				.andExpect(jsonPath("$.translationLanguages[?(@.code=='fr')].supportsAutoDetection").value(true))
				.andExpect(jsonPath("$.translationLanguages[?(@.code=='fr')].qualityTier").value("BETA"))
				.andExpect(jsonPath("$.translationLanguages[?(@.code=='zh-CN')].scriptCode").value("Hans"))
				.andExpect(jsonPath("$.translationLanguages[?(@.code=='ur')].writingDirection").value("RTL"))
				.andExpect(jsonPath("$.translationLanguages[?(@.code=='aa')]").isEmpty())
				.andExpect(jsonPath("$.items[?(@.featureCode=='TRANSLATE' && @.sourceLanguage=='en' "
						+ "&& @.destinationLanguage=='am')]").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		List<String> codes = JsonPath.read(body, "$.translationLanguages[*].code");
		assertThat(codes.subList(0, 5)).containsExactly("am", "ti", "om", "en", "ar");
		assertThat(codes).contains("es", "pt", "ru", "hi", "bn", "zh-TW", "ja", "ko", "sw", "so", "ha", "yo", "ig",
				"rw", "ln", "zu", "xh", "fil", "id", "ms", "ta", "te", "mr", "gu", "pa", "tr", "fa", "he", "uk", "pl");

		String etag = mvc.perform(get("/api/v1/ai/capabilities")).andReturn().getResponse().getHeader("ETag");
		mvc.perform(get("/api/v1/ai/capabilities").header("If-None-Match", etag)).andExpect(status().isNotModified());
		// a language a model drops is a new catalogue (and a new tag)
		jdbc.update("""
				UPDATE ai_model_translation_languages SET is_enabled = FALSE
				 WHERE language_id = (SELECT id FROM supported_languages WHERE language_code = 'la')""");
		mvc.perform(get("/api/v1/ai/capabilities").header("If-None-Match", etag)).andExpect(status().isOk())
				.andExpect(jsonPath("$.translationLanguages.length()").value(189));
	}

	@Test
	void nothingIsListedWhileTheProvidersAreDisabled() throws Exception {
		disable();
		mvc.perform(get("/api/v1/ai/capabilities")).andExpect(status().isOk())
				.andExpect(jsonPath("$.translationLanguages").isEmpty());
	}

	// ------------------------------------------------------------------------------------------------- routing

	@Test
	void aPairWithoutEnglishIsRoutedOpenAiFirstThenGoogle() {
		openAiAnswers("እንደምን አደርክ");
		googleAnswers("ከ Google");
		RouteResult<TranslateResult> frAm = translate("fr", "am");
		assertThat(frAm.outcome()).isEqualTo(Outcome.SUCCEEDED);
		assertThat(frAm.providerCode()).isEqualTo("openai");
		assertThat(frAm.capability().sourceLanguage()).isNull(); // served by the any-pair row

		openAiDown();
		for (String[] pair : new String[][] { { "fr", "am" }, { "es", "ar" }, { "zh-CN", "ti" }, { null, "he" } }) {
			breakers.resetAll();
			RouteResult<TranslateResult> r = translate(pair[0], pair[1]);
			assertThat(r.outcome()).as(pair[0] + ">" + pair[1]).isEqualTo(Outcome.SUCCEEDED);
			assertThat(r.attempts()).extracting(a -> a.providerCode()).containsExactly("openai", "google-translate");
		}
		VENDORS.verify(postRequestedFor(urlPathEqualTo(GOOGLE)).withRequestBody(matchingJsonPath("$.source",
				com.github.tomakehurst.wiremock.client.WireMock.equalTo("es")))
				.withRequestBody(matchingJsonPath("$.target", com.github.tomakehurst.wiremock.client.WireMock.equalTo("ar"))));
	}

	@Test
	void aLanguageOneModelDoesNotListGoesToTheOtherOnly() {
		openAiAnswers("ሰላም");
		googleAnswers("ከ Google");
		jdbc.update("""
				UPDATE ai_model_translation_languages SET is_enabled = FALSE
				 WHERE model_id = (SELECT m.id FROM ai_models m JOIN ai_providers p ON p.id = m.provider_id
				                    WHERE p.provider_code = 'openai' AND m.model_code = 'openai-llm')
				   AND language_id = (SELECT id FROM supported_languages WHERE language_code = 'fr')""");
		RouteResult<TranslateResult> r = translate("fr", "am");
		assertThat(r.outcome()).isEqualTo(Outcome.SUCCEEDED);
		assertThat(r.attempts()).extracting(a -> a.providerCode()).containsExactly("google-translate");
		// the en <-> am pair rows still serve OpenAI (the existing rows keep working)
		assertThat(translate("en", "am").providerCode()).isEqualTo("openai");
	}

	@Test
	void unsupportedPairsAreRefused() {
		openAiAnswers("x");
		googleAnswers("x");
		// Afar and Ge'ez are (planned) typing languages no provider lists
		assertThat(translate("aa", "en").outcome()).isEqualTo(Outcome.LANGUAGE_PAIR_UNAVAILABLE);
		assertThat(translate("fr", "gez").outcome()).isEqualTo(Outcome.LANGUAGE_PAIR_UNAVAILABLE);
		assertThat(translate("aa", "en").contractCode()).isEqualTo("LANGUAGE_PAIR_UNAVAILABLE");
		// translating into the source language matches no row either
		assertThat(translate("fr", "fr").outcome()).isEqualTo(Outcome.LANGUAGE_PAIR_UNAVAILABLE);
		assertThat(VENDORS.getAllServeEvents()).isEmpty();
	}

	// ------------------------------------------------------------------------------------------------- typing

	@Test
	void translationOnlyLanguagesAreNotTypingLanguages() throws Exception {
		UUID user = jdbc.queryForObject("""
				INSERT INTO users (account_type, account_status, time_zone) VALUES ('REGISTERED', 'ACTIVE', 'Africa/Addis_Ababa')
				RETURNING id""", UUID.class);
		String bearer = "Bearer " + tokens.issue(user, UUID.randomUUID(), null, "REGISTERED").value();
		String body = """
				{"revision":0,"languages":[
				 {"languageCode":"en","enabled":true,"isPrimary":true,"sortOrder":10,"inputMode":"LATIN_PASSTHROUGH"},
				 {"languageCode":"fr","enabled":true,"isPrimary":false,"sortOrder":20,"inputMode":"LATIN_PASSTHROUGH"}]}""";
		mvc.perform(put("/api/v1/me/typing-languages").header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("LANGUAGE_NOT_SUPPORTED"))
				.andExpect(jsonPath("$.errors[0].field").value("languages[1].languageCode"));
		// the keyboard catalogue does not list them
		mvc.perform(get("/api/v1/languages")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(11))
				.andExpect(jsonPath("$.items[?(@.code=='fr')]").isEmpty())
				.andExpect(jsonPath("$.items[?(@.code=='am')]").isNotEmpty());
		// a translation destination is fine where a translation language is meant
		mvc.perform(put("/api/v1/me/preferences").header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0,\"defaultDestinationLanguage\":\"fr\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.defaultDestinationLanguage").value("fr"));
	}
}
