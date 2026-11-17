package com.qalsmartkeyboard.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B1.3: every Problem produced by the platform error handling is validated against the contract (the
 * {@link ContractValidated} wiring validates each perform(...) automatically), the correlation ID rules hold,
 * and the validator is shown to fail on responses that violate the contract.
 */
@WebMvcTest(ProbeController.class)
@ContractValidated
@Import(ProblemDetailsContractTest.TestConfig.class)
class ProblemDetailsContractTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class TestConfig {
		@Bean
		OpenApiInteractionValidator probeValidator() {
			return ProbeContract.validator();
		}

		/** Authentication is B2's concern; the probe endpoints are open. */
		@Bean
		SecurityFilterChain openChain(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable()).build();
		}
	}

	@Autowired
	MockMvc mvc;

	private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

	// ---------------------------------------------------------------- happy path and correlation id

	@Test
	void successResponseIsValidAndCarriesGeneratedCorrelationId() throws Exception {
		MvcResult r = mvc.perform(get("/probe/ping")).andExpect(status().isOk())
				.andExpect(header().exists("X-Correlation-Id")).andReturn();
		assertThat(UUID.fromString(r.getResponse().getHeader("X-Correlation-Id"))).isNotNull();
	}

	@Test
	void wellFormedInboundCorrelationIdIsEchoedOnSuccessAndInProblem() throws Exception {
		mvc.perform(get("/probe/ping").header("X-Correlation-Id", "client-id_1.2:3"))
				.andExpect(header().string("X-Correlation-Id", "client-id_1.2:3"));
		mvc.perform(get("/probe/boom").header("X-Correlation-Id", "client-id_1.2:3"))
				.andExpect(status().isInternalServerError())
				.andExpect(header().string("X-Correlation-Id", "client-id_1.2:3"))
				.andExpect(jsonPath("$.correlationId").value("client-id_1.2:3"));
	}

	@Test
	void malformedOrOverlongInboundCorrelationIdIsReplacedByUuid() throws Exception {
		for (String bad : new String[] { "has space", "x".repeat(101), "semi;colon", "<script>" }) {
			MvcResult r = mvc.perform(get("/probe/ping").header("X-Correlation-Id", bad)).andReturn();
			assertThat(UUID.fromString(r.getResponse().getHeader("X-Correlation-Id"))).isNotNull();
		}
	}

	@Test
	void problemCorrelationIdEqualsResponseHeader() throws Exception {
		MvcResult r = mvc.perform(get("/probe/no-such-route")).andExpect(status().isNotFound()).andReturn();
		String header = r.getResponse().getHeader("X-Correlation-Id");
		assertThat(header).isNotBlank();
		assertThat(r.getResponse().getContentAsString()).contains("\"correlationId\":\"" + header + "\"");
	}

	// ---------------------------------------------------------------- error mapping (each validated against the contract)

	@Test
	void unknownRouteIs404NotFoundProblem() throws Exception {
		mvc.perform(get("/probe/no-such-route")).andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("NOT_FOUND")).andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.correlationId").isNotEmpty());
	}

	@Test
	void beanValidationFailureIs400WithFieldErrorsAndNoEchoedValues() throws Exception {
		mvc.perform(post("/probe/validated").contentType(JSON)
				.content("{\"name\":\"\",\"count\":0,\"note\":\"SENSITIVE-TEXT\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors.length()").value(3))
				.andExpect(jsonPath("$.errors[?(@.field=='name')].code").value("REQUIRED"))
				.andExpect(jsonPath("$.errors[?(@.field=='count')].code").value("MIN"))
				.andExpect(jsonPath("$.errors[?(@.field=='note')].code").value("SIZE"))
				.andExpect(content().string(not(containsString("SENSITIVE-TEXT"))));
	}

	@Test
	void parameterValidationFailureIs400() throws Exception {
		mvc.perform(get("/probe/limit").param("limit", "101")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("limit"))
				.andExpect(jsonPath("$.errors[0].code").value("MAX"));
	}

	@Test
	void missingAndMistypedParametersAre400() throws Exception {
		mvc.perform(get("/probe/limit")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("limit"))
				.andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		mvc.perform(get("/probe/limit").param("limit", "abc")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("TYPE_MISMATCH"))
				.andExpect(content().string(not(containsString("abc"))));
	}

	@Test
	void malformedJsonIs400AndDoesNotEchoTheBody() throws Exception {
		mvc.perform(post("/probe/validated").contentType(JSON).content("{\"name\": \"PRIVATE-TEXT\", oops"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].code").value("MALFORMED_REQUEST_BODY"))
				.andExpect(content().string(not(containsString("PRIVATE-TEXT"))));
	}

	@Test
	void methodNotAllowedIs405Problem() throws Exception {
		mvc.perform(post("/probe/ping").contentType(JSON).content("{}")).andExpect(status().isMethodNotAllowed())
				.andExpect(header().exists("Allow"))
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.status").value(405)).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void unsupportedMediaTypeIs415Problem() throws Exception {
		mvc.perform(post("/probe/validated").contentType(MediaType.TEXT_PLAIN).content("name=x"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.status").value(415))
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@ParameterizedTest
	@EnumSource(ApiErrorCode.class)
	void everyApiErrorCodeProducesAContractValidProblem(ApiErrorCode code) throws Exception {
		mvc.perform(get("/probe/api-exception").param("code", code.name()))
				.andExpect(status().is(code.defaultStatus().value()))
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value(code.name())).andExpect(jsonPath("$.title").value(code.title()))
				.andExpect(jsonPath("$.detail").value("Something safe to show."))
				.andExpect(jsonPath("$.type").value(startsWith("https://")));
	}

	@Test
	void extensionMembersAreCarriedOnQuotaProblem() throws Exception {
		mvc.perform(get("/probe/quota")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
				.andExpect(jsonPath("$.featureCode").value("TRANSLATE")).andExpect(jsonPath("$.creditBalance").value(0));
	}

	@Test
	void unexpectedExceptionIs500WithoutLeakingMessageOrStack() throws Exception {
		mvc.perform(get("/probe/boom")).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(jsonPath("$.detail").doesNotExist())
				.andExpect(content().string(not(containsString("secret"))))
				.andExpect(content().string(not(containsString("hello@"))))
				.andExpect(content().string(not(containsString("IllegalState"))));
	}

	// ---------------------------------------------------------------- the validator cannot be silently green

	@Test
	void validatorFailsOnAProblemThatViolatesTheContractSchema() {
		assertThatThrownBy(() -> mvc.perform(get("/probe/violating"))).hasMessageContaining("code")
				.hasMessageContaining("correlationId");
	}
}
