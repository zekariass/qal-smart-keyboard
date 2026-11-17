package com.qalsmartkeyboard.configuration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;
import com.qalsmartkeyboard.testsupport.contract.OpenApiContract;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * B1.6: {@code GET /api/v1/config} against the contract (200 shape) and the version gate (426 Problem). Every
 * response is validated against {@code contracts/openapi.yaml}. The contract declares no 426 response, so the
 * validator is the real contract plus a test-only operation that reuses the contract's shared Problem response for
 * 400 and 426 (nothing is written back to the contract).
 */
@WebMvcTest(ClientConfigController.class)
@ContractValidated
@Import({ ConfigWebTest.TestConfig.class, ConfigurationWebConfiguration.class, ConfigWebTest.GatedController.class })
class ConfigWebTest {

	static final String GATED = "/api/v1/test-gated";

	/** A stand-in for any client API endpoint behind the version gate. */
	@RestController
	static class GatedController {
		@GetMapping(GATED)
		Map<String, Object> ok() {
			return Map.of("ok", true);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestConfig {
		@Bean
		OpenApiInteractionValidator configValidator() {
			return OpenApiContract.validatorWithOverlay(api -> {
				Map<String, ApiResponse> shared = api.getComponents().getResponses();
				ApiResponses responses = new ApiResponses();
				responses.addApiResponse("200", new ApiResponse().description("ok").content(
						new Content().addMediaType("application/json", new MediaType().schema(new ObjectSchema()))));
				responses.addApiResponse("400", shared.get("BadRequest"));
				responses.addApiResponse("426", shared.get("NotFound")); // generic Problem response object
				PathItem item = new PathItem();
				item.setGet(new Operation().responses(responses));
				api.getPaths().addPathItem(GATED, item);
			});
		}

		/** Authentication is B2's concern; no security chain exists yet. */
		@Bean
		SecurityFilterChain openChain(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable()).build();
		}
	}

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ClientConfigService service;

	@BeforeEach
	void defaults() {
		when(service.minSupported(any())).thenReturn(AppVersion.parse("2.0.0").orElseThrow());
	}

	// ------------------------------------------------------------------ /config

	@Test
	void configReturnsContractShapedBody() throws Exception {
		when(service.resolve(eq(ClientPlatform.ANDROID), eq(AppVersion.parse("2.1.0").orElseThrow()), eq(null)))
				.thenReturn(new ClientConfigDto(7, "2.0.0", "2.2.0", Map.of("smart_reply", true, "beta", false),
						Map.of("typing.maxSuggestions", 5, "ui", Map.of("a", List.of(1, 2))),
						Instant.parse("2026-11-17T10:00:00Z")));
		mvc.perform(get("/api/v1/config").header("X-Client-Platform", "ANDROID").header("X-App-Version", "2.1.0")
				.header("X-Client-Surface", "KEYBOARD")).andExpect(status().isOk())
				.andExpect(header().exists("X-Correlation-Id")).andExpect(jsonPath("$.configVersion").value(7))
				.andExpect(jsonPath("$.minSupportedAppVersion").value("2.0.0"))
				.andExpect(jsonPath("$.recommendedAppVersion").value("2.2.0"))
				.andExpect(jsonPath("$.flags.smart_reply").value(true))
				.andExpect(jsonPath("$.values['typing.maxSuggestions']").value(5))
				.andExpect(jsonPath("$.serverTime").value("2026-11-17T10:00:00Z"));
	}

	@Test
	void configOmitsAbsentRecommendedVersionAndWorksWithoutHeaders() throws Exception {
		when(service.resolve(eq(null), eq(null), eq(null))).thenReturn(
				new ClientConfigDto(0, "0.0.0", null, Map.of(), Map.of(), Instant.parse("2026-11-17T10:00:00Z")));
		mvc.perform(get("/api/v1/config")).andExpect(status().isOk())
				.andExpect(jsonPath("$.recommendedAppVersion").doesNotExist())
				.andExpect(jsonPath("$.flags").isMap()).andExpect(jsonPath("$.values").isMap());
	}

	@Test
	void configIsExemptFromTheVersionGateSoAnOldClientCanLearnTheMinimum() throws Exception {
		when(service.resolve(eq(ClientPlatform.ANDROID), eq(AppVersion.parse("1.0.0").orElseThrow()), eq(null)))
				.thenReturn(new ClientConfigDto(1, "2.0.0", null, Map.of(), Map.of(),
						Instant.parse("2026-11-17T10:00:00Z")));
		mvc.perform(get("/api/v1/config").header("X-Client-Platform", "ANDROID").header("X-App-Version", "1.0.0"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.minSupportedAppVersion").value("2.0.0"));
		verify(service, never()).minSupported(any());
	}

	@Test
	void configTreatsMalformedHeadersAsUnknownInsteadOfFailing() throws Exception {
		when(service.resolve(eq(null), eq(null), eq(null))).thenReturn(
				new ClientConfigDto(0, "0.0.0", null, Map.of(), Map.of(), Instant.parse("2026-11-17T10:00:00Z")));
		mvc.perform(get("/api/v1/config").header("X-Client-Platform", "DESKTOP").header("X-App-Version", "banana"))
				.andExpect(status().isOk());
	}

	// ------------------------------------------------------------------ 426 enforcement

	/** The step's "done when": an old version gets a 426 Problem. */
	@Test
	void oldVersionGets426ProblemWithCorrelationIdAndMinimum() throws Exception {
		mvc.perform(get(GATED).header("X-Client-Platform", "ANDROID").header("X-App-Version", "1.9.9")
				.header("X-Correlation-Id", "req-426")).andExpect(status().isUpgradeRequired())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(header().string("X-Correlation-Id", "req-426"))
				.andExpect(jsonPath("$.status").value(426)).andExpect(jsonPath("$.code").value("APP_VERSION_UNSUPPORTED"))
				.andExpect(jsonPath("$.correlationId").value("req-426"))
				.andExpect(jsonPath("$.minSupportedAppVersion").value("2.0.0"))
				.andExpect(jsonPath("$.title").isNotEmpty());
	}

	@Test
	void semanticComparisonNotStringComparison() throws Exception {
		when(service.minSupported(any())).thenReturn(AppVersion.parse("1.9.0").orElseThrow());
		mvc.perform(get(GATED).header("X-App-Version", "1.10.0")).andExpect(status().isOk());
		mvc.perform(get(GATED).header("X-App-Version", "1.8.99")).andExpect(status().isUpgradeRequired());
	}

	@Test
	void minimumVersionItselfAndNewerAreAccepted() throws Exception {
		mvc.perform(get(GATED).header("X-Client-Platform", "IOS").header("X-App-Version", "2.0.0"))
				.andExpect(status().isOk());
		mvc.perform(get(GATED).header("X-Client-Platform", "IOS").header("X-App-Version", "2.0.1+7"))
				.andExpect(status().isOk());
		mvc.perform(get(GATED).header("X-Client-Platform", "IOS").header("X-App-Version", "2.0.0-beta.1"))
				.andExpect(status().isUpgradeRequired());
	}

	@Test
	void minimumIsLookedUpForTheCallersPlatform() throws Exception {
		mvc.perform(get(GATED).header("X-Client-Platform", "WEB").header("X-App-Version", "1.0.0"));
		verify(service).minSupported(ClientPlatform.WEB);
		mvc.perform(get(GATED).header("X-App-Version", "1.0.0"));
		verify(service).minSupported(null);
	}

	@Test
	void missingVersionHeaderIsNotEnforced() throws Exception {
		mvc.perform(get(GATED)).andExpect(status().isOk());
		mvc.perform(get(GATED).header("X-Client-Platform", "ANDROID")).andExpect(status().isOk());
		verify(service, never()).minSupported(any());
	}

	@Test
	void malformedVersionIs400ValidationFailedNamingTheHeaderWithoutEchoingTheValue() throws Exception {
		mvc.perform(get(GATED).header("X-App-Version", "not-a-version<script>")).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("X-App-Version"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("script"))));
	}

	@Test
	void unknownPlatformIs400ValidationFailed() throws Exception {
		mvc.perform(get(GATED).header("X-Client-Platform", "android").header("X-App-Version", "2.0.0"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("X-Client-Platform"));
	}

	@Test
	void unreadableConfigurationFailsOpen() throws Exception {
		when(service.minSupported(any())).thenThrow(new IllegalStateException("db down"));
		mvc.perform(get(GATED).header("X-App-Version", "0.0.1")).andExpect(status().isOk());
	}
}
