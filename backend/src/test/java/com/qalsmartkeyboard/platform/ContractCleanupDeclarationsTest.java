package com.qalsmartkeyboard.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.entitlements.PlanSource;
import com.qalsmartkeyboard.testsupport.contract.OpenApiContract;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;

/** ADR 0007 D20, D23, D24: the declarations added by the contract cleanup batch stay in the contract. */
class ContractCleanupDeclarationsTest {

	private static final OpenAPI API = OpenApiContract.load();

	private static Schema<?> codeSchema() {
		return (Schema<?>) API.getComponents().getSchemas().get("Problem").getProperties().get("code");
	}

	private static Operation op(String path, PathItem.HttpMethod method) {
		return API.getPaths().get(path).readOperationsMap().get(method);
	}

	/** Every bearer-required write answers 401 for a missing or invalid token. */
	@Test
	void bearerRequiredWritesDeclare401() {
		List<String> missing = new ArrayList<>();
		API.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
			boolean listed = path.startsWith("/api/v1/me/preferences") || path.startsWith("/api/v1/me/typing-languages")
					|| path.startsWith("/api/v1/me/consents") || path.startsWith("/api/v1/me/destination-profiles")
					|| path.startsWith("/api/v1/me/app-destination-rules") || path.startsWith("/api/v1/devices");
			if (listed && op.getResponses().get("401") == null) {
				missing.add(method + " " + path);
			}
		}));
		assertThat(missing).isEmpty();
	}

	@Test
	void preferencesWriteDeclaresConsentRequired() {
		Operation put = op("/api/v1/me/preferences", PathItem.HttpMethod.PUT);
		assertThat(put.getResponses()).containsKeys("401", "403", "409");
		assertThat(put.getDescription()).contains("CONSENT_REQUIRED");
	}

	@Test
	void conflictDocumentsCurrentRevisionAndProblemHasGuestSessionInvalid() {
		assertThat(API.getComponents().getResponses().get("Conflict").getDescription()).contains("currentRevision");
		assertThat(codeSchema().getDescription())
				.contains("GUEST_SESSION_INVALID");
		assertThat(Arrays.stream(ApiErrorCode.values()).map(Enum::name)).contains("GUEST_SESSION_INVALID");
	}

	@Test
	void everyApiErrorCodeIsListedInTheProblemCodeDescription() {
		String description = codeSchema().getDescription();
		assertThat(Arrays.stream(ApiErrorCode.values()).map(Enum::name).filter(n -> !description.contains(n)))
				.isEmpty();
	}

	/** The contract {@code ResourceType} equals the V1 CHECK list, so every stored type can be expressed. */
	@Test
	void resourceTypeEqualsTheSqlCheckList() throws IOException {
		String sql;
		try (InputStream in = getClass().getResourceAsStream("/db/migration/V1__baseline.sql")) {
			sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		Matcher check = Pattern.compile("resource_type IN \\(([^)]*)\\)").matcher(sql);
		assertThat(check.find()).isTrue();
		List<String> sqlValues = Arrays.stream(check.group(1).split(",")).map(v -> v.trim().replace("'", "")).toList();
		List<?> contract = API.getComponents().getSchemas().get("ResourceType").getEnum();
		assertThat(contract).map(Object::toString).containsExactlyInAnyOrderElementsOf(sqlValues);
		assertThat(contract.contains("GLOSSARY")).isTrue();
	}

	@Test
	void planSourceEnumEqualsTheServerEnum() {
		Schema<?> plan = (Schema<?>) API.getComponents().getSchemas().get("Entitlements").getProperties().get("plan");
		Schema<?> source = (Schema<?>) plan.getProperties().get("source");
		assertThat(source.getEnum()).map(Object::toString)
				.containsExactlyInAnyOrderElementsOf(Arrays.stream(PlanSource.values()).map(PlanSource::contractValue).toList());
	}

	@Test
	void profilesListingAndLanguageResourceDeclarations() {
		assertThat(op("/api/v1/languages/{languageCode}/transliteration-profiles", PathItem.HttpMethod.GET).getSummary())
				.contains("ACTIVE and RETIRED");
		assertThat(op("/api/v1/language-resources/{resourceId}", PathItem.HttpMethod.GET).getResponses())
				.containsKeys("400", "404");
		assertThat(op("/api/v1/language-resources", PathItem.HttpMethod.GET).getDescription())
				.contains("Rollout bucketing is server-side");
	}

	@Test
	void consentHistoryAndRuleKeyOperationsAreDeclared() {
		Operation history = op("/api/v1/me/consents/history", PathItem.HttpMethod.GET);
		assertThat(history.getOperationId()).isEqualTo("listConsentHistory");
		assertThat(history.getParameters()).extracting(p -> p.getName())
				.containsExactlyInAnyOrder("consentCode", "cursor", "limit");
		assertThat(history.getResponses()).containsKeys("200", "400", "401");

		Operation key = op("/api/v1/me/app-destination-rules/key", PathItem.HttpMethod.GET);
		assertThat(key.getOperationId()).isEqualTo("getAppDestinationRuleKey");
		assertThat(key.getSecurity()).isNull(); // bearer required (the document default)
		assertThat(key.getResponses().get("200").getHeaders()).containsKey("Cache-Control");
		assertThat(API.getComponents().getSchemas().get("AppDestinationRuleKey").getRequired())
				.containsExactlyInAnyOrder("keyId", "key");
	}

	/** The flat schema validates every member of a response; an {@code allOf} of two closed members never could. */
	@Test
	void destinationProfileIsFlat() {
		Schema<?> profile = API.getComponents().getSchemas().get("DestinationProfile");
		assertThat(profile.getAllOf()).isNull();
		assertThat(profile.getProperties()).containsKeys("id", "label", "destinationLanguage", "createdAt", "updatedAt");
	}
}
