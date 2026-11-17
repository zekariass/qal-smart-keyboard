package com.qalsmartkeyboard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.qalsmartkeyboard.testsupport.contract.OpenApiContract;

/** The real contract loads (OpenAPI 3.1) and the validator really checks real operations (positive and negative). */
class OpenApiContractTest {

	private static final OpenApiInteractionValidator VALIDATOR = OpenApiContract.validator();

	private static SimpleResponse problem(int status, String body) {
		return SimpleResponse.Builder.status(status).withContentType("application/problem+json")
				.withHeader("X-Correlation-Id", "abc").withBody(body).build();
	}

	@Test
	void contractIs31AndLoads() {
		assertThat(OpenApiContract.load().getOpenapi()).startsWith("3.1");
		assertThat(OpenApiContract.load().getPaths()).containsKey("/api/v1/auth/otp/start");
	}

	@Test
	void conformingProblemOnARealOperationIsValid() {
		ValidationReport report = VALIDATOR.validateResponse("/api/v1/auth/otp/start", Request.Method.POST, problem(400,
				"{\"type\":\"https://api.example.com/problems/validation-failed\",\"title\":\"Request validation failed\","
						+ "\"status\":400,\"code\":\"VALIDATION_FAILED\",\"correlationId\":\"abc\","
						+ "\"errors\":[{\"field\":\"phoneNumber\",\"code\":\"REQUIRED\",\"message\":\"phoneNumber is required.\"}]}"));
		assertThat(report.hasErrors()).as(report.toString()).isFalse();
	}

	@Test
	void problemMissingRequiredMembersFailsOnARealOperation() {
		ValidationReport report = VALIDATOR.validateResponse("/api/v1/auth/otp/start", Request.Method.POST,
				problem(400, "{\"type\":\"about:blank\",\"title\":\"x\",\"status\":400}"));
		assertThat(report.hasErrors()).isTrue();
		assertThat(report.toString()).contains("code").contains("correlationId");
	}

	@Test
	void undeclaredStatusFailsOnARealOperation() {
		ValidationReport report = VALIDATOR.validateResponse("/api/v1/auth/otp/start", Request.Method.POST,
				problem(418, "{}"));
		assertThat(report.hasErrors()).isTrue();
	}

	@Test
	void unknownPathFails() {
		ValidationReport report = VALIDATOR.validateResponse("/api/v1/not-in-contract", Request.Method.GET,
				problem(404, "{}"));
		assertThat(report.hasErrors()).isTrue();
	}
}
