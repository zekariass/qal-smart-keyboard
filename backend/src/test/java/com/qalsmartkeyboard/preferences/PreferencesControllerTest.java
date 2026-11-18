package com.qalsmartkeyboard.preferences;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.preferences.PreferencesDtos.TypingLanguageDto;
import com.qalsmartkeyboard.preferences.PreferencesDtos.TypingLanguagesDto;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B3.3: the four operations against the contract (services mocked) behind the real security chain. The contract lists no
 * 401 for the two PUTs, so unauthenticated access is covered by {@link PreferencesIT}.
 */
@WebMvcTest(PreferencesController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class })
class PreferencesControllerTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;
	@MockitoBean
	PreferencesService preferences;
	@MockitoBean
	TypingLanguagesService typingLanguages;

	final UUID userId = UUID.randomUUID();

	String bearer() {
		return "Bearer " + tokens.issue(userId, UUID.randomUUID(), null, "REGISTERED").value();
	}

	@Test
	void getPreferencesReturnsTheDocumentNoStore() throws Exception {
		when(preferences.get(userId)).thenReturn(PreferencesService.defaults());
		mvc.perform(get("/api/v1/me/preferences").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.revision").value(0)).andExpect(jsonPath("$.translationMode").value("MANUAL"))
				.andExpect(jsonPath("$.defaultSourceLanguage").doesNotExist())
				.andExpect(header().string("Cache-Control", "no-store"));
	}

	@Test
	void replacePreferencesPassesTheBodyAndTheCaller() throws Exception {
		when(preferences.replace(eq(userId), any())).thenReturn(PreferencesService.defaults());
		mvc.perform(put("/api/v1/me/preferences").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"revision\":0,\"numeralStyle\":\"ETHIOPIC\",\"keyboardTheme\":\"DARK\",\"hapticsEnabled\":false,"
						+ "\"updatedAt\":\"2020-01-01T00:00:00Z\"}"))
				.andExpect(status().isOk());
		verify(preferences).replace(eq(userId), any());
	}

	@Test
	void replacePreferencesRejectsABadBodyWith400() throws Exception {
		for (String body : List.of("{\"numeralStyle\":\"WESTERN\"}", "{\"revision\":-1}",
				"{\"revision\":1,\"numeralStyle\":\"ROMAN\"}", "{\"revision\":1,\"historyRetentionDays\":0}",
				"{\"revision\":1,\"historyRetentionDays\":366}", "{\"revision\":1,\"defaultSourceLanguage\":\"Amharic!\"}",
				"{\"revision\":\"x\"}")) {
			mvc.perform(put("/api/v1/me/preferences").header("Authorization", bearer())
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		verify(preferences, never()).replace(any(), any());
	}

	@Test
	void aStaleRevisionIs409WithTheCurrentRevision() throws Exception {
		when(preferences.replace(eq(userId), any())).thenThrow(PreferencesService.conflict(7));
		mvc.perform(put("/api/v1/me/preferences").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"revision\":3}")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REVISION_CONFLICT")).andExpect(jsonPath("$.currentRevision").value(7));
	}

	@Test
	void getWithoutATokenIs401() throws Exception {
		mvc.perform(get("/api/v1/me/preferences")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(get("/api/v1/me/typing-languages")).andExpect(status().isUnauthorized());
	}

	@Test
	void getTypingLanguagesReturnsTheDocument() throws Exception {
		when(typingLanguages.get(userId)).thenReturn(new TypingLanguagesDto(3, List.of(new TypingLanguageDto("am", true,
				true, 10, "PHONETIC_LATIN_TO_NATIVE", "AM_PHONETIC_QWERTY_TO_ETHIOPIC", "LIVE_COMPOSING", true,
				"PRESERVE_LATIN"))));
		mvc.perform(get("/api/v1/me/typing-languages").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.revision").value(3)).andExpect(jsonPath("$.languages[0].languageCode").value("am"))
				.andExpect(jsonPath("$.languages[0].isPrimary").value(true));
	}

	@Test
	void replaceTypingLanguagesAcceptsTheContractExample() throws Exception {
		when(typingLanguages.replace(eq(userId), any())).thenReturn(new TypingLanguagesDto(4, List.of()));
		mvc.perform(put("/api/v1/me/typing-languages").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"revision":3,"languages":[
						 {"languageCode":"am","enabled":true,"isPrimary":true,"sortOrder":10,
						  "inputMode":"PHONETIC_LATIN_TO_NATIVE","transliterationProfileCode":"AM_PHONETIC_QWERTY_TO_ETHIOPIC",
						  "conversionTrigger":"LIVE_COMPOSING","showTransliterationCandidates":true,
						  "unknownTokenBehavior":"PRESERVE_LATIN"},
						 {"languageCode":"en","enabled":true,"isPrimary":false,"sortOrder":20,"inputMode":"LATIN_PASSTHROUGH",
						  "conversionTrigger":"LIVE_COMPOSING","showTransliterationCandidates":false,
						  "unknownTokenBehavior":"PRESERVE_LATIN"}]}"""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(4));
	}

	@Test
	void replaceTypingLanguagesRejectsBadShapesWith400() throws Exception {
		String item = "{\"languageCode\":\"am\",\"enabled\":true,\"inputMode\":\"LATIN_PASSTHROUGH\"}";
		for (String body : List.of("{\"languages\":[]}", "{\"revision\":1}", "{\"revision\":1,\"languages\":[{}]}",
				"{\"revision\":1,\"languages\":[{\"languageCode\":\"am\",\"enabled\":true,\"inputMode\":\"QWERTY\"}]}",
				"{\"revision\":1,\"languages\":[{\"languageCode\":\"am\",\"inputMode\":\"LATIN_PASSTHROUGH\"}]}",
				"{\"revision\":1,\"languages\":[" + String.join(",", java.util.Collections.nCopies(21, item)) + "]}")) {
			mvc.perform(put("/api/v1/me/typing-languages").header("Authorization", bearer())
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		verify(typingLanguages, never()).replace(any(), any());
	}
}
