package com.qalsmartkeyboard.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.testsupport.contract.OpenApiContract;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;

/**
 * ADR 0007 D9: the contract declares what the identity code answers, so clients generated from it and the response
 * validator agree with the server.
 */
class IdentityContractDeclarationsTest {

	private static final OpenAPI API = OpenApiContract.load();

	private static Operation op(String path, PathItem.HttpMethod method) {
		return API.getPaths().get(path).readOperationsMap().get(method);
	}

	/** (3) an invalid bearer on an optional-bearer operation stays a 401, so every such operation declares it. */
	@Test
	void everyOptionalBearerOperationDeclares401() {
		List<String> optional = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		API.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
			List<SecurityRequirement> security = op.getSecurity();
			if (security != null && security.stream().anyMatch(Map::isEmpty)
					&& security.stream().anyMatch(r -> r.containsKey("bearerAuth"))) {
				optional.add(method + " " + path);
				if (op.getResponses().get("401") == null) {
					missing.add(method + " " + path);
				}
			}
		}));
		assertThat(optional).hasSizeGreaterThanOrEqualTo(13);
		assertThat(missing).isEmpty();
	}

	/** (2), (4): suspended accounts and malformed refresh requests. */
	@Test
	void verifyAndRefreshDeclareTheirErrorStatuses() {
		assertThat(op("/api/v1/auth/otp/verify", PathItem.HttpMethod.POST).getResponses()).containsKeys("400", "401",
				"403", "429");
		assertThat(op("/api/v1/auth/refresh", PathItem.HttpMethod.POST).getResponses()).containsKeys("200", "400", "401",
				"403");
		// the shared Forbidden response (refs are resolved by the loader), which lists ACCOUNT_SUSPENDED
		assertThat(op("/api/v1/auth/refresh", PathItem.HttpMethod.POST).getResponses().get("403").getDescription())
				.isEqualTo(API.getComponents().getResponses().get("Forbidden").getDescription())
				.contains("ACCOUNT_SUSPENDED");
	}

	/** (1) optional installation id on start, same format as the device's on verify. */
	@Test
	void otpStartRequestHasAnOptionalInstallationId() {
		Schema<?> start = API.getComponents().getSchemas().get("OtpStartRequest");
		Schema<?> device = API.getComponents().getSchemas().get("DeviceRegistration");
		assertThat(start.getProperties()).containsKey("installationId");
		assertThat(start.getRequired()).doesNotContain("installationId");
		assertThat(start.getProperties().get("installationId").getFormat())
				.isEqualTo(device.getProperties().get("installationId").getFormat()).isEqualTo("uuid");
	}

	/** (5) SQL is canonical for stored values: the contract enum equals the {@code users.market_source} CHECK list. */
	@Test
	void marketSourceEnumEqualsTheSqlCheckList() throws IOException {
		String sql;
		try (InputStream in = getClass().getResourceAsStream("/db/migration/V1__baseline.sql")) {
			sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		Matcher check = Pattern.compile("CHECK \\(market_source IN \\(([^)]*)\\)\\)").matcher(sql);
		assertThat(check.find()).isTrue();
		List<String> sqlValues = Arrays.stream(check.group(1).split(",")).map(v -> v.trim().replace("'", "")).toList();

		Schema<?> profile = API.getComponents().getSchemas().get("UserProfile");
		List<?> contractValues = profile.getProperties().get("marketSource").getEnum();
		assertThat(contractValues).map(Object::toString).containsExactlyElementsOf(sqlValues);
	}

	/** B2.2: the OAuth exchange declares what the server answers (suspended, rate limit, signing keys unavailable). */
	@Test
	void oauthExchangeDeclaresItsErrorStatusesAndTheProviderEnum() {
		Operation exchange = op("/api/v1/auth/oauth/{provider}", PathItem.HttpMethod.POST);
		assertThat(exchange.getResponses()).containsKeys("200", "400", "401", "403", "429", "503");
		assertThat(exchange.getParameters().get(0).getSchema().getEnum()).containsExactly("GOOGLE", "APPLE");
		assertThat(API.getComponents().getSchemas().get("OAuthExchangeRequest").getRequired())
				.containsExactlyInAnyOrder("idToken", "nonce");
	}

	/** ADR 0007 D15: {@code GET /devices} follows the list convention and is bearer-protected. */
	@Test
	void listDevicesIsDeclaredWithCursorPaginationAndBearerAuth() {
		Operation list = op("/api/v1/devices", PathItem.HttpMethod.GET);
		assertThat(list.getOperationId()).isEqualTo("listDevices");
		assertThat(list.getSecurity()).isNull();
		assertThat(list.getParameters()).extracting(p -> p.getName()).containsExactlyInAnyOrder("cursor", "limit");
		assertThat(list.getResponses()).containsKeys("200", "400", "401");
	}
}
