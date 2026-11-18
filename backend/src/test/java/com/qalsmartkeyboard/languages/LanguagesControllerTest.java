package com.qalsmartkeyboard.languages;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.languages.LanguageDtos.DeviceRequirementsDto;
import com.qalsmartkeyboard.languages.LanguageDtos.LanguageDto;
import com.qalsmartkeyboard.languages.LanguageDtos.LanguageResourceDto;
import com.qalsmartkeyboard.languages.LanguageDtos.ResourceListDto;
import com.qalsmartkeyboard.languages.LanguageDtos.SigningKeyDto;
import com.qalsmartkeyboard.languages.LanguageDtos.TransliterationDto;
import com.qalsmartkeyboard.languages.LanguageDtos.TransliterationProfileDto;
import com.qalsmartkeyboard.languages.LanguagesService.Caller;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B3.1: the five Languages operations against {@code contracts/openapi.yaml} (service mocked), behind the real security
 * chain: anonymous is allowed, a valid token is allowed, an invalid token is 401 (B2.4 optional-bearer rule).
 */
@WebMvcTest(LanguagesController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class })
class LanguagesControllerTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;
	@MockitoBean
	LanguagesService service;

	final UUID userId = UUID.randomUUID();
	final UUID deviceId = UUID.randomUUID();
	final UUID resourceId = UUID.randomUUID();

	String bearer() {
		return "Bearer " + tokens.issue(userId, UUID.randomUUID(), deviceId, "REGISTERED").value();
	}

	LanguageResourceDto resource() {
		return new LanguageResourceDto(resourceId, "am", "TRANSLITERATION_RULES", "1.3.0", "SHARED", "STABLE",
				"https://packs.example.com/am/translit/1.3.0.pack?X-Amz-Signature=abc", "a".repeat(64), 182044L, 182500L,
				UUID.randomUUID(), "packs-2026-01", "1.4.0", "LicenseRef-Qal-Original",
				new DeviceRequirementsDto(0, null, "NONE", null, true, true),
				new TransliterationDto("AM_PHONETIC_QWERTY_TO_ETHIOPIC", "1.0.0", "NFC"), "Notes",
				Instant.parse("2026-11-17T10:00:00Z"));
	}

	// ------------------------------------------------------------------ listLanguages

	@Test
	void languagesAnonymousAndWithAValidToken() throws Exception {
		when(service.languages()).thenReturn(List.of(
				new LanguageDto("am", "Amharic", "አማርኛ", "Ethi", "LTR", "BETA", true, List.of("DIRECT_SCRIPT_LAYOUT")),
				new LanguageDto("ar", "Arabic", null, "Arab", "RTL", "PLANNED", false, null)));
		mvc.perform(get("/api/v1/languages")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].code").value("am")).andExpect(jsonPath("$.items[0].isLaunchLanguage").value(true))
				.andExpect(jsonPath("$.items[0].inputModes[0]").value("DIRECT_SCRIPT_LAYOUT"))
				.andExpect(jsonPath("$.items[1].writingDirection").value("RTL"))
				.andExpect(jsonPath("$.items[1].nativeName").doesNotExist())
				.andExpect(jsonPath("$.items[1].inputModes").doesNotExist());
		mvc.perform(get("/api/v1/languages").header("Authorization", bearer())).andExpect(status().isOk());
	}

	@Test
	void languagesWithAnInvalidTokenIs401() throws Exception {
		mvc.perform(get("/api/v1/languages").header("Authorization", "Bearer not-a-token"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		verifyNoInteractions(service);
	}

	// ------------------------------------------------------------------ listTransliterationProfiles

	@Test
	void profilesShowStatusAndRulesResource() throws Exception {
		when(service.profiles("am")).thenReturn(List.of(
				new TransliterationProfileDto("AM_PHONETIC_QWERTY_TO_ETHIOPIC", "1.0.0", "Amharic phonetic", "am", "Latn",
						"Ethi", "PHONETIC", true, "ACTIVE", resourceId),
				new TransliterationProfileDto("AM_OLD", "0.9.0", null, "am", "Latn", "Ethi", null, false, "RETIRED", null)));
		mvc.perform(get("/api/v1/languages/am/transliteration-profiles")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$.items[0].rulesResourceId").value(resourceId.toString()))
				.andExpect(jsonPath("$.items[1].status").value("RETIRED"))
				.andExpect(jsonPath("$.items[1].rulesResourceId").doesNotExist());
	}

	@Test
	void profilesOfAnUnknownLanguageIs404() throws Exception {
		when(service.profiles("zz")).thenThrow(LanguagesService.notFound());
		mvc.perform(get("/api/v1/languages/zz/transliteration-profiles")).andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void profilesWithAnInvalidTokenIs401() throws Exception {
		mvc.perform(get("/api/v1/languages/am/transliteration-profiles").header("Authorization", "Bearer x"))
				.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------ listLanguageResources

	@Test
	void resourcesReturnTheManifestAndPassTheFiltersThrough() throws Exception {
		when(service.listResources(any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(new ResourceListDto(List.of(resource()), "next-cursor"));
		mvc.perform(get("/api/v1/language-resources").param("platform", "ANDROID").param("appVersion", "2.1.0")
				.param("channel", "BETA").param("type", "TRANSLITERATION_RULES").param("languageCode", "am")
				.param("limit", "5").param("cursor", "c1").header("Authorization", bearer()))
				.andExpect(status().isOk()).andExpect(header().exists("X-Correlation-Id"))
				.andExpect(jsonPath("$.items[0].id").value(resourceId.toString()))
				.andExpect(jsonPath("$.items[0].artifactUrl").value(
						"https://packs.example.com/am/translit/1.3.0.pack?X-Amz-Signature=abc"))
				.andExpect(jsonPath("$.items[0].sha256").value("a".repeat(64)))
				.andExpect(jsonPath("$.items[0].sizeBytes").value(182044))
				.andExpect(jsonPath("$.items[0].containerSizeBytes").value(182500))
				.andExpect(jsonPath("$.items[0].signatureKeyId").value("packs-2026-01"))
				.andExpect(jsonPath("$.items[0].signature").doesNotExist())
				.andExpect(jsonPath("$.items[0].transliteration.profileCode").value("AM_PHONETIC_QWERTY_TO_ETHIOPIC"))
				.andExpect(jsonPath("$.items[0].deviceRequirements.minRamMb").value(0))
				.andExpect(jsonPath("$.nextCursor").value("next-cursor"));
		ArgumentCaptor<Caller> caller = ArgumentCaptor.forClass(Caller.class);
		verify(service).listResources(eq(PackPlatform.ANDROID), eq("2.1.0"), eq(ReleaseChannel.BETA),
				eq("TRANSLITERATION_RULES"), eq("am"), eq("c1"), eq(5), caller.capture());
		org.assertj.core.api.Assertions.assertThat(caller.getValue().subject()).isEqualTo(deviceId);
	}

	@Test
	void resourcesAnonymousHaveNoSubject() throws Exception {
		when(service.listResources(any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(new ResourceListDto(List.of(), null));
		mvc.perform(get("/api/v1/language-resources").param("platform", "IOS").param("appVersion", "1.0.0"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
		verify(service).listResources(eq(PackPlatform.IOS), eq("1.0.0"), eq(null), eq(null), eq(null), eq(null), eq(null),
				eq(Caller.ANONYMOUS));
	}

	@Test
	void resourcesRequirePlatformAndAppVersion() throws Exception {
		mvc.perform(get("/api/v1/language-resources").param("appVersion", "1.0.0")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(get("/api/v1/language-resources").param("platform", "ANDROID")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		verify(service, never()).listResources(any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void resourcesRejectUnknownEnumValues() throws Exception {
		mvc.perform(get("/api/v1/language-resources").param("platform", "WINDOWS").param("appVersion", "1.0.0"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/language-resources").param("platform", "ANDROID").param("appVersion", "1.0.0")
				.param("channel", "stable")).andExpect(status().isBadRequest());
	}

	@Test
	void serviceValidationFailuresAre400Problems() throws Exception {
		when(service.listResources(any(), eq("banana"), any(), any(), any(), any(), any(), any()))
				.thenThrow(LanguagesService.invalid("appVersion", "INVALID", "Must be a semantic version such as 2.0.3."));
		mvc.perform(get("/api/v1/language-resources").param("platform", "ANDROID").param("appVersion", "banana"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("appVersion"));
	}

	@Test
	void anonymousInternalChannelIs401() throws Exception {
		when(service.listResources(any(), any(), eq(ReleaseChannel.INTERNAL), any(), any(), any(), any(), any()))
				.thenThrow(new ApiException(ApiErrorCode.UNAUTHENTICATED));
		mvc.perform(get("/api/v1/language-resources").param("platform", "ANDROID").param("appVersion", "1.0.0")
				.param("channel", "INTERNAL")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void resourcesWithAnInvalidTokenIs401() throws Exception {
		mvc.perform(get("/api/v1/language-resources").param("platform", "ANDROID").param("appVersion", "1.0.0")
				.header("Authorization", "Bearer broken")).andExpect(status().isUnauthorized());
		verifyNoInteractions(service);
	}

	// ------------------------------------------------------------------ getLanguageResource

	@Test
	void resourceDetail() throws Exception {
		when(service.resource(eq(resourceId), any())).thenReturn(resource());
		mvc.perform(get("/api/v1/language-resources/" + resourceId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(resourceId.toString())).andExpect(jsonPath("$.version").value("1.3.0"))
				.andExpect(jsonPath("$.channel").value("STABLE")).andExpect(jsonPath("$.platform").value("SHARED"))
				.andExpect(jsonPath("$.publishedAt").value("2026-11-17T10:00:00Z"));
	}

	@Test
	void resourceDetailUnknownIs404AndMalformedIdIs400() throws Exception {
		when(service.resource(any(), any())).thenThrow(LanguagesService.notFound());
		mvc.perform(get("/api/v1/language-resources/" + UUID.randomUUID())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void resourceDetailWithAnInvalidTokenIs401() throws Exception {
		mvc.perform(get("/api/v1/language-resources/" + resourceId).header("Authorization", "Bearer x"))
				.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------ listResourceSigningKeys

	@Test
	void signingKeysIncludeStatusAndEndorsementButOnlyPublicMaterial() throws Exception {
		when(service.signingKeys()).thenReturn(List.of(
				new SigningKeyDto("packs-2026-01", "Ed25519", "AAAA", "ACTIVE", Instant.parse("2026-01-01T00:00:00Z"), null,
						null, null),
				new SigningKeyDto("packs-2026-02", "Ed25519", "BBBB", "RETIRING", Instant.parse("2026-06-01T00:00:00Z"),
						Instant.parse("2027-01-01T00:00:00Z"), "packs-2026-01", "ENDORSE"),
				new SigningKeyDto("packs-old", "Ed25519", "CCCC", "REVOKED", Instant.parse("2025-01-01T00:00:00Z"), null, null,
						null)));
		mvc.perform(get("/api/v1/resource-signing-keys")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(3)).andExpect(jsonPath("$.items[0].algorithm").value("Ed25519"))
				.andExpect(jsonPath("$.items[1].endorsedByKeyId").value("packs-2026-01"))
				.andExpect(jsonPath("$.items[1].endorsementSignature").value("ENDORSE"))
				.andExpect(jsonPath("$.items[2].status").value("REVOKED"))
				.andExpect(jsonPath("$.items[0].privateKey").doesNotExist());
	}

	@Test
	void signingKeysWithAnInvalidTokenIs401() throws Exception {
		mvc.perform(get("/api/v1/resource-signing-keys").header("Authorization", "Bearer x"))
				.andExpect(status().isUnauthorized());
	}
}
