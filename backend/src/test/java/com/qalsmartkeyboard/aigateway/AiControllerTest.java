package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.aigateway.pipeline.AiUserContext;
import com.qalsmartkeyboard.aigateway.spi.Addressee;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.DataPolicy;
import com.qalsmartkeyboard.aigateway.spi.ProviderCapability;
import com.qalsmartkeyboard.aigateway.spi.ProviderDescriptor;
import com.qalsmartkeyboard.aigateway.spi.ProviderStatus;
import com.qalsmartkeyboard.aigateway.spi.ProviderType;
import com.qalsmartkeyboard.aigateway.spi.QualityTier;
import com.qalsmartkeyboard.entitlements.AccessState;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.entitlements.FeatureEntitlement;
import com.qalsmartkeyboard.entitlements.MeteringUnit;
import com.qalsmartkeyboard.entitlements.ResolvedEntitlements;
import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;
import com.qalsmartkeyboard.usage.QuotaExceededException;

/**
 * B5.5: every AI operation and every error mapping against {@code contracts/openapi.yaml} (pipeline mocked) behind the
 * real security chain. The pipeline's own behaviour is proven in {@link AiPipelineIT}, the full stack in
 * {@link AiEndpointsIT}.
 */
@WebMvcTest(AiController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class, AiControllerTest.Properties.class })
class AiControllerTest {

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AiGatewayProperties.class)
	static class Properties {
	}

	static final String SECRET = "Zebra lighthouse 7741 marmalade";
	static final UUID REQUEST_ID = UUID.fromString("0193a5c2-6f1e-7c3a-9b2d-4e5f6a7b8c9d");

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;
	@MockitoBean
	AiPipeline pipeline;
	@MockitoBean
	RateLimiter limiter;
	@MockitoBean
	ProviderRegistry registry;
	@MockitoBean
	EntitlementResolver entitlements;
	@MockitoBean
	AiUserContext users;
	@MockitoBean
	AiFeedback feedback;

	final UUID userId = UUID.randomUUID();

	@BeforeEach
	void allowRequests() {
		when(limiter.tryConsume(any(), any())).thenAnswer(i -> new RateLimitDecision(i.getArgument(0), true, 29,
				Duration.ZERO, Duration.ofSeconds(60)));
		when(users.defaults(any())).thenReturn(AiUserContext.Defaults.NONE);
	}

	String bearer() {
		return "Bearer " + tokens.issue(userId, UUID.randomUUID(), null, "REGISTERED").value();
	}

	static String key() {
		return UUID.randomUUID().toString();
	}

	static AiResult.UsageSummary usage() {
		return new AiResult.UsageSummary("TRANSLATE", 1, "REQUEST", "PLAN_ALLOWANCE", 9L, null, null, null,
				Instant.parse("2026-11-19T21:00:00Z"));
	}

	static AiResult.TranslateResponse translated() {
		return new AiResult.TranslateResponse(REQUEST_ID, "en", null, null, "am", "ሰላም", List.of(),
				List.of(new AiResult.AiNote("QUALITY", "Beta quality: please review the result before sending.")),
				QualityTier.BETA, false, null, usage());
	}

	// ============================================================================================= translate

	@Test
	void translateMapsTheBodyToACommandAndReturnsTheContractResponse() throws Exception {
		when(pipeline.execute(any())).thenReturn(translated());
		UUID profile = UUID.randomUUID();
		String k = key();
		mvc.perform(post("/api/v1/ai/translate").header("Authorization", bearer()).header("Idempotency-Key", k)
				.header("X-Client-Surface", "COMPANION").contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"%s","sourceLanguage":"en","destinationLanguage":"am","style":"POLITE",
						 "preserveNames":false,"addressee":{"formality":"HONORIFIC","gender":"FEMININE"},
						 "culturalOptions":{"calendar":"ETHIOPIAN","numerals":"ETHIOPIC","adaptIdioms":false},
						 "destinationProfileId":"%s","saveToHistory":true}
						""".formatted(SECRET, profile)))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().string("RateLimit-Limit", "30")).andExpect(header().string("RateLimit-Remaining", "29"))
				.andExpect(header().exists("RateLimit-Reset")).andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()))
				.andExpect(jsonPath("$.translatedText").value("ሰላም")).andExpect(jsonPath("$.replayed").value(false))
				.andExpect(jsonPath("$.qualityTier").value("BETA")).andExpect(jsonPath("$.usage.remaining").value(9))
				.andExpect(jsonPath("$.usage.creditBalance").doesNotExist());

		ArgumentCaptor<AiCommand> captor = ArgumentCaptor.forClass(AiCommand.class);
		verify(pipeline).execute(captor.capture());
		AiCommand.Translate c = (AiCommand.Translate) captor.getValue();
		assertThat(c.text().value()).isEqualTo(SECRET);
		assertThat(c.sourceLanguage()).isEqualTo("en");
		assertThat(c.destinationLanguage()).isEqualTo("am");
		assertThat(c.style()).isEqualTo("POLITE");
		assertThat(c.preserveNames()).isFalse();
		assertThat(c.addressee()).isEqualTo(new Addressee(Addressee.Formality.HONORIFIC, Addressee.Gender.FEMININE));
		assertThat(c.culturalOptions()).isEqualTo(new AiCommand.CulturalOptions("ETHIOPIAN", null, "ETHIOPIC", false));
		assertThat(c.destinationProfileId()).isEqualTo(profile);
		assertThat(c.caller().userId()).isEqualTo(userId);
		assertThat(c.caller().idempotencyKey()).isEqualTo(k);
		assertThat(c.caller().surface()).isEqualTo(AiCommand.Surface.COMPANION);
		assertThat(c.toString()).doesNotContain(SECRET);
	}

	@Test
	void translateRequiresAnIdempotencyKeyThatIsAUuid() throws Exception {
		String body = "{\"text\":\"" + SECRET + "\",\"destinationLanguage\":\"am\"}";
		mvc.perform(post("/api/v1/ai/translate").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post("/api/v1/ai/translate").header("Authorization", bearer()).header("Idempotency-Key", "not-a-uuid")
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
		verifyNoInteractions(pipeline);
	}

	@Test
	void malformedBodiesAre400WithoutEchoingTheText() throws Exception {
		String t = "{\"text\":\"" + SECRET + "\"";
		for (String body : List.of(t, t + ",", "", "[]", "\"" + SECRET + "\"", "{\"destinationLanguage\":\"am\"}",
				"{\"text\":12345}", t + ",\"preserveNames\":\"" + SECRET + "\"}",
				t + ",\"glossaryId\":\"" + SECRET + "\"}", t + ",\"destinationProfileId\":[\"" + SECRET + "\"]}",
				t + ",\"style\":{\"" + SECRET + "\":1}}", t + ",\"addressee\":\"" + SECRET + "\"}",
				t + ",\"addressee\":{\"formality\":\"" + SECRET + "\"}}",
				t + ",\"addressee\":{\"gender\":\"" + SECRET + "\"}}",
				t + ",\"culturalOptions\":{\"adaptIdioms\":\"" + SECRET + "\"}}",
				t + ",\"culturalOptions\":{\"calendar\":7}}")) {
			String response = mvc.perform(post("/api/v1/ai/translate").header("Authorization", bearer())
					.header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
					.andReturn().getResponse().getContentAsString();
			assertThat(response).doesNotContain(SECRET);
		}
		verifyNoInteractions(pipeline);
	}

	@Test
	void withoutATokenEveryPostIs401() throws Exception {
		for (String path : List.of("/api/v1/ai/translate", "/api/v1/ai/transform", "/api/v1/ai/detect-language")) {
			mvc.perform(post(path).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
					.content("{\"text\":\"x\"}")).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}
		verifyNoInteractions(pipeline);
	}

	@Test
	void anInvalidSurfaceIs400() throws Exception {
		mvc.perform(post("/api/v1/ai/translate").header("Authorization", bearer()).header("Idempotency-Key", key())
				.header("X-Client-Surface", "WATCH").contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"x\",\"destinationLanguage\":\"am\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("X-Client-Surface"));
	}

	// ============================================================================================= transform

	@Test
	void transformMapsOptionsAndReturnsTheContractResponse() throws Exception {
		when(pipeline.execute(any())).thenReturn(new AiResult.TransformResponse(REQUEST_ID, "TONE_ADJUST", "am", "ሰላም",
				List.of(new AiResult.TextEdit(0, 3, null, "ሰላም", "STYLE")), List.of(), List.of(), QualityTier.BETA,
				false, null, new AiResult.UsageSummary("TONE_ADJUST", 1, "REQUEST", "AI_CREDITS", 0L,
						new BigDecimal("12"), new BigDecimal("0.500000"), null, null)));
		mvc.perform(post("/api/v1/ai/transform").header("Authorization", bearer()).header("Idempotency-Key", key())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"task":"TONE_ADJUST","text":"%s","language":"am",
						 "options":{"tone":"HONORIFIC","targetLength":"SHORT"},
						 "addressee":{"formality":"HONORIFIC","gender":"UNKNOWN"},"applyStyleProfile":false}
						""".formatted(SECRET)))
				.andExpect(status().isOk()).andExpect(header().string("RateLimit-Limit", "30"))
				.andExpect(jsonPath("$.task").value("TONE_ADJUST")).andExpect(jsonPath("$.resultText").value("ሰላም"))
				.andExpect(jsonPath("$.edits[0].category").value("STYLE"))
				.andExpect(jsonPath("$.usage.creditsCharged").value(0.5));

		ArgumentCaptor<AiCommand> captor = ArgumentCaptor.forClass(AiCommand.class);
		verify(pipeline).execute(captor.capture());
		AiCommand.Transform c = (AiCommand.Transform) captor.getValue();
		assertThat(c.task()).isEqualTo(AiFeature.TONE_ADJUST);
		assertThat(c.language()).isEqualTo("am");
		assertThat(c.tone()).isEqualTo("HONORIFIC");
		assertThat(c.targetLength()).isEqualTo("SHORT");
		assertThat(c.mode()).isNull();
		assertThat(c.addressee().gender()).isEqualTo(Addressee.Gender.UNKNOWN);
	}

	@Test
	void grammarAndRewriteTasksReachThePipelineWithTheirMode() throws Exception {
		when(pipeline.execute(any())).thenReturn(new AiResult.TransformResponse(REQUEST_ID, "GRAMMAR_CORRECTION", null,
				"fixed", List.of(), List.of(), List.of(), QualityTier.PRODUCTION, false, null, usage()));
		for (String[] t : new String[][] { { "GRAMMAR_CORRECTION", "SPELLING_ONLY" }, { "REWRITE", "SHORTEN" } }) {
			mvc.perform(post("/api/v1/ai/transform").header("Authorization", bearer()).header("Idempotency-Key", key())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"task\":\"" + t[0] + "\",\"text\":\"abc\",\"options\":{\"mode\":\"" + t[1] + "\"}}"))
					.andExpect(status().isOk());
		}
		ArgumentCaptor<AiCommand> captor = ArgumentCaptor.forClass(AiCommand.class);
		verify(pipeline, org.mockito.Mockito.times(2)).execute(captor.capture());
		assertThat(captor.getAllValues()).extracting(c -> ((AiCommand.Transform) c).mode())
				.containsExactly("SPELLING_ONLY", "SHORTEN");
	}

	@Test
	void anUnknownOrNonTransformTaskIs400() throws Exception {
		for (String task : List.of("TRANSLATE", "DETECT_LANGUAGE", "NOPE")) {
			mvc.perform(post("/api/v1/ai/transform").header("Authorization", bearer()).header("Idempotency-Key", key())
					.contentType(MediaType.APPLICATION_JSON).content("{\"task\":\"" + task + "\",\"text\":\"abc\"}"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("task"));
		}
		verify(pipeline, never()).execute(any());
	}

	@Test
	void aTaskTheGatewayDoesNotServeYetIsThePipelinesValidationProblem() throws Exception {
		when(pipeline.execute(any())).thenThrow(new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
				"This task is not available.",
				List.of(new com.qalsmartkeyboard.platform.ApiFieldError("task", "NOT_AVAILABLE", "This task is not available.")),
				Map.of()));
		mvc.perform(post("/api/v1/ai/transform").header("Authorization", bearer()).header("Idempotency-Key", key())
				.contentType(MediaType.APPLICATION_JSON).content("{\"task\":\"SUMMARIZE\",\"text\":\"abc\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_AVAILABLE"));
	}

	// ============================================================================================= detect

	@Test
	void detectLanguageNeedsNoKeyAndReturnsTheContractResponse() throws Exception {
		when(pipeline.execute(any())).thenReturn(new AiResult.DetectLanguageResponse(
				List.of(new AiResult.DetectLanguageResponse.DetectedLanguage("am", "Ethi", 0.97)), false, null));
		mvc.perform(post("/api/v1/ai/detect-language").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"" + SECRET + "\",\"candidates\":[\"am\",\"ti\"]}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.languages[0].languageCode").value("am"))
				.andExpect(jsonPath("$.mixedLanguage").value(false)).andExpect(jsonPath("$.romanizedNative").doesNotExist());
		ArgumentCaptor<AiCommand> captor = ArgumentCaptor.forClass(AiCommand.class);
		verify(pipeline).execute(captor.capture());
		AiCommand.Detect d = (AiCommand.Detect) captor.getValue();
		assertThat(d.candidates()).containsExactly("am", "ti");
		assertThat(d.caller().idempotencyKey()).isNull();
	}

	// ============================================================================================= errors

	/** Every Problem the pipeline can raise, per operation, is a contract-valid response with its code and status. */
	static Stream<Arguments> problems() {
		return Stream.of(ApiErrorCode.VALIDATION_FAILED, ApiErrorCode.INPUT_TOO_LONG, ApiErrorCode.LANGUAGE_NOT_SUPPORTED,
				ApiErrorCode.UNAUTHENTICATED, ApiErrorCode.GUEST_SESSION_INVALID, ApiErrorCode.ACCOUNT_SUSPENDED,
				ApiErrorCode.DELETION_PENDING, ApiErrorCode.FEATURE_NOT_ENTITLED, ApiErrorCode.CONSENT_REQUIRED,
				ApiErrorCode.ON_DEVICE_ONLY_PREFERRED, ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
				ApiErrorCode.REQUEST_IN_PROGRESS, ApiErrorCode.OUTPUT_GUARDRAIL_FAILED, ApiErrorCode.CONTENT_REJECTED,
				ApiErrorCode.RATE_LIMITED, ApiErrorCode.PROVIDER_UNAVAILABLE, ApiErrorCode.PROVIDER_TIMEOUT,
				ApiErrorCode.LANGUAGE_PAIR_UNAVAILABLE, ApiErrorCode.ZERO_RETENTION_UNAVAILABLE)
				.flatMap(code -> Stream.of("translate", "transform", "detect-language").map(op -> Arguments.of(op, code)));
	}

	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("problems")
	void everyPipelineProblemIsAContractValidProblem(String op, ApiErrorCode code) throws Exception {
		boolean detect = op.equals("detect-language");
		if (detect && (code == ApiErrorCode.IDEMPOTENCY_KEY_REUSED || code == ApiErrorCode.REQUEST_IN_PROGRESS
				|| code == ApiErrorCode.OUTPUT_GUARDRAIL_FAILED)) {
			return; // a keyless, unmetered, output-unchecked operation cannot raise these
		}
		ApiException problem = code == ApiErrorCode.REQUEST_IN_PROGRESS
				? new ApiException(code, code.defaultStatus(), "Busy.", List.of(), Map.of(), Map.of("Retry-After", "2"))
				: new ApiException(code, code.defaultStatus(), "Detail.");
		when(pipeline.execute(any())).thenThrow(problem);
		var request = post("/api/v1/ai/" + op).header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"task\":\"REWRITE\",\"text\":\"abc\",\"destinationLanguage\":\"am\"}");
		if (!detect) {
			request.header("Idempotency-Key", key());
		}
		mvc.perform(request).andExpect(status().is(code.defaultStatus().value()))
				.andExpect(jsonPath("$.code").value(code.name())).andExpect(jsonPath("$.correlationId").exists());
	}

	@Test
	void aQuotaProblemCarriesItsExtensionMembersAndNoText() throws Exception {
		when(pipeline.execute(any())).thenThrow(new QuotaExceededException("TRANSLATE",
				Instant.parse("2026-11-19T21:00:00Z"), BigDecimal.ZERO,
				List.of(new QuotaExceededException.UpgradeOption("PLAN", "PREMIUM"))));
		String body = mvc.perform(post("/api/v1/ai/translate").header("Authorization", bearer())
				.header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"text\":\"" + SECRET + "\",\"destinationLanguage\":\"am\"}"))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
				.andExpect(jsonPath("$.featureCode").value("TRANSLATE")).andExpect(jsonPath("$.resetAt").exists())
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(SECRET);
	}

	@Test
	void theRateLimitAnswers429BeforeTheKeyIsLookedAtOrThePipelineRuns() throws Exception {
		org.mockito.Mockito.doAnswer(i -> new RateLimitDecision(i.getArgument(0), false, 0, Duration.ofSeconds(20),
				Duration.ofSeconds(20))).when(limiter).tryConsume(any(), any());
		for (String path : List.of("translate", "transform", "detect-language")) {
			mvc.perform(post("/api/v1/ai/" + path).header("Authorization", bearer()).header("Idempotency-Key", key())
					.contentType(MediaType.APPLICATION_JSON).content("{\"task\":\"REWRITE\",\"text\":\"abc\"}"))
					.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
					.andExpect(header().string("Retry-After", "20")).andExpect(header().string("RateLimit-Remaining", "0"));
		}
		verifyNoInteractions(pipeline);
		ArgumentCaptor<RateLimitPolicy> policies = ArgumentCaptor.forClass(RateLimitPolicy.class);
		verify(limiter, org.mockito.Mockito.times(3)).tryConsume(policies.capture(), eq(userId.toString()));
		assertThat(policies.getAllValues()).extracting(RateLimitPolicy::name)
				.containsExactly("ai.translate.user", "ai.transform.user", "ai.detect.user");
	}

	// ============================================================================================= capabilities

	static ProviderDescriptor provider(String code, ProviderCapability... capabilities) {
		return new ProviderDescriptor(code, code, ProviderType.SELF_HOSTED, ProviderStatus.ACTIVE,
				new DataPolicy(false, false, java.util.Set.of("local"), true), List.of(capabilities));
	}

	static ResolvedEntitlements plan(AccessState access, String... allowed) {
		java.util.LinkedHashMap<String, FeatureEntitlement> features = new java.util.LinkedHashMap<>();
		for (AiFeature f : AiFeature.values()) {
			boolean on = List.of(allowed).contains(f.name());
			features.put(f.name(), new FeatureEntitlement(f.name(), true, on, MeteringUnit.REQUEST, 10L, null,
					BigDecimal.ONE, false, FeatureEntitlement.Origin.PLAN));
		}
		return new ResolvedEntitlements(UUID.randomUUID(), Instant.now(), access, Optional.empty(), "v", features);
	}

	void providers() {
		when(registry.activeDescriptors()).thenReturn(List.of(
				provider("a", new ProviderCapability(AiFeature.TRANSLATE, "en", "am", QualityTier.BETA, true, 3000, "m1"),
						new ProviderCapability(AiFeature.TRANSLATE, "am", "en", QualityTier.EXPERIMENTAL, true, 5000, "m1"),
						new ProviderCapability(AiFeature.SUMMARIZE, "en", null, QualityTier.PRODUCTION, true, 5000, "m1"),
						new ProviderCapability(AiFeature.DETECT_LANGUAGE, null, null, QualityTier.BETA, true, null, "lid")),
				provider("b", new ProviderCapability(AiFeature.TRANSLATE, "en", "am", QualityTier.PRODUCTION, false, 8000,
						"m2"),
						new ProviderCapability(AiFeature.REWRITE, "am", null, QualityTier.BETA, true, 5000, "m2"))));
	}

	@Test
	void capabilitiesForAnAnonymousCallerListEveryExposedServedPair() throws Exception {
		providers();
		mvc.perform(get("/api/v1/ai/capabilities")).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.generatedAt").exists()).andExpect(jsonPath("$.items.length()").value(3))
				.andExpect(jsonPath("$.items[0].featureCode").value("DETECT_LANGUAGE"))
				.andExpect(jsonPath("$.items[0].maxInputCharacters").value(1000))
				.andExpect(jsonPath("$.items[0].sourceLanguage").doesNotExist())
				.andExpect(jsonPath("$.items[1].featureCode").value("REWRITE"))
				.andExpect(jsonPath("$.items[2].featureCode").value("TRANSLATE"))
				.andExpect(jsonPath("$.items[2].qualityTier").value("PRODUCTION")) // the better of the two providers
				.andExpect(jsonPath("$.items[2].maxInputCharacters").value(5000)) // capped by the contract limit
				.andExpect(jsonPath("$.items[2].execution").value("SERVER"))
				.andExpect(jsonPath("$.items[2].supportsStreaming").value(false));
		verifyNoInteractions(entitlements);
	}

	@Test
	void capabilitiesForASignedInCallerFollowThePlan() throws Exception {
		providers();
		when(entitlements.resolve(userId)).thenReturn(plan(AccessState.GRANTED, "TRANSLATE", "DETECT_LANGUAGE"));
		mvc.perform(get("/api/v1/ai/capabilities").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].featureCode").value("DETECT_LANGUAGE"))
				.andExpect(jsonPath("$.items[1].featureCode").value("TRANSLATE"));
	}

	@Test
	void capabilitiesAreEmptyWhenTheAccountCannotUseServerAi() throws Exception {
		providers();
		when(entitlements.resolve(userId)).thenReturn(plan(AccessState.SUSPENDED, "TRANSLATE"));
		mvc.perform(get("/api/v1/ai/capabilities").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
		// deletion pending: language detection only (D33)
		when(entitlements.resolve(userId)).thenReturn(plan(AccessState.DELETED, "DETECT_LANGUAGE", "TRANSLATE"));
		mvc.perform(get("/api/v1/ai/capabilities").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].featureCode").value("DETECT_LANGUAGE"));
		// ON_DEVICE_ONLY: the server offers nothing (D33)
		when(users.defaults(userId)).thenReturn(new AiUserContext.Defaults("INFORMAL", "UNKNOWN", "NATURAL", true,
				"GREGORIAN", "INTERNATIONAL", "WESTERN", AiUserContext.Defaults.MARKET_ZONE, "ON_DEVICE_ONLY"));
		when(entitlements.resolve(userId)).thenReturn(plan(AccessState.GRANTED, "TRANSLATE"));
		mvc.perform(get("/api/v1/ai/capabilities").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void capabilitiesWithNoActiveProviderAreAnEmptyList() throws Exception {
		when(registry.activeDescriptors()).thenReturn(List.of());
		mvc.perform(get("/api/v1/ai/capabilities")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void capabilitiesAreRateLimitedPerClient() throws Exception {
		org.mockito.Mockito.doAnswer(i -> new RateLimitDecision(i.getArgument(0), false, 0, Duration.ofSeconds(5),
				Duration.ofSeconds(5))).when(limiter).tryConsume(any(), any());
		mvc.perform(get("/api/v1/ai/capabilities")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		verifyNoInteractions(registry);
	}
}
