package com.qalsmartkeyboard.privacy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.privacy.ConsentDtos.ConsentDto;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/** B3.5: {@code listConsents} and {@code setConsent} against the contract (service mocked) behind the real security chain. */
@WebMvcTest(ConsentController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class })
class ConsentControllerTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;
	@MockitoBean
	ConsentService consents;

	final UUID userId = UUID.randomUUID();

	String bearer() {
		return "Bearer " + tokens.issue(userId, UUID.randomUUID(), null, "REGISTERED").value();
	}

	@Test
	void listReturnsTheLatestStateNoStore() throws Exception {
		Instant t = Instant.parse("2026-01-01T10:00:00Z");
		when(consents.latest(userId)).thenReturn(List.of(
				new ConsentDto(UUID.randomUUID(), "ANALYTICS", "2", true, t, null, t),
				new ConsentDto(UUID.randomUUID(), "SAVE_AI_HISTORY", "1", false, t, t.plusSeconds(5), t.plusSeconds(5))));
		mvc.perform(get("/api/v1/me/consents").header("Authorization", bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].isGranted").value(true))
				.andExpect(jsonPath("$.items[0].revokedAt").doesNotExist())
				.andExpect(jsonPath("$.items[1].revokedAt").value("2026-01-01T10:00:05Z"))
				.andExpect(header().string("Cache-Control", "no-store"));
	}

	@Test
	void listWithoutATokenIs401() throws Exception {
		mvc.perform(get("/api/v1/me/consents")).andExpect(status().isUnauthorized());
	}

	@Test
	void setRecordsTheDecisionWithThePlatform() throws Exception {
		Instant t = Instant.parse("2026-01-01T10:00:00Z");
		when(consents.record(eq(userId), eq(ConsentType.PERSONAL_STYLE), eq("3"), eq(true), eq("ANDROID"), any()))
				.thenReturn(new ConsentDto(UUID.randomUUID(), "PERSONAL_STYLE", "3", true, t, null, t));
		mvc.perform(put("/api/v1/me/consents/PERSONAL_STYLE").header("Authorization", bearer())
				.header("X-Client-Platform", "ANDROID").contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"3\",\"isGranted\":true,\"details\":{\"surface\":\"onboarding\"}}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.consentCode").value("PERSONAL_STYLE"))
				.andExpect(jsonPath("$.isGranted").value(true));
	}

	@Test
	void badBodiesAndUnknownCodesAre400() throws Exception {
		for (String body : List.of("{}", "{\"consentVersion\":\"1\"}", "{\"isGranted\":true}",
				"{\"consentVersion\":\"\",\"isGranted\":true}",
				"{\"consentVersion\":\"" + "v".repeat(41) + "\",\"isGranted\":true}")) {
			mvc.perform(put("/api/v1/me/consents/ANALYTICS").header("Authorization", bearer())
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		mvc.perform(put("/api/v1/me/consents/NOT_A_CONSENT").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"consentVersion\":\"1\",\"isGranted\":true}"))
				.andExpect(status().isBadRequest());
		verify(consents, never()).record(any(), any(), any(), anyBoolean(), any(), any());
	}

	@Test
	void withdrawingTermsIs422() throws Exception {
		when(consents.record(eq(userId), eq(ConsentType.TERMS_ACCEPTANCE), any(), eq(false), any(), any()))
				.thenThrow(new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_CONTENT,
						"cannot be withdrawn"));
		mvc.perform(put("/api/v1/me/consents/TERMS_ACCEPTANCE").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"consentVersion\":\"1\",\"isGranted\":false}"))
				.andExpect(status().isUnprocessableContent());
	}
}
