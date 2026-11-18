package com.qalsmartkeyboard.preferences;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.preferences.DestinationDtos.Profile;
import com.qalsmartkeyboard.preferences.DestinationDtos.RulesDocument;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/** B3.4: the six operations against the contract (services mocked) behind the real security chain. */
@WebMvcTest(DestinationController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class })
class DestinationControllerTest {

	static final String HASH = "a".repeat(64);

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;
	@MockitoBean
	DestinationProfilesService profiles;
	@MockitoBean
	AppDestinationRulesService rules;

	final UUID userId = UUID.randomUUID();

	String bearer() {
		return "Bearer " + tokens.issue(userId, UUID.randomUUID(), null, "REGISTERED").value();
	}

	Profile profile(UUID id) {
		return new Profile(id, "Mum", "am", "HONORIFIC", "FEMININE", "POLITE", null, null, 100, Instant.now(),
				Instant.now());
	}

	@Test
	void listCreateUpdateAndDelete() throws Exception {
		UUID id = UUID.randomUUID();
		when(profiles.list(userId)).thenReturn(List.of(profile(id)));
		when(profiles.create(eq(userId), any())).thenReturn(profile(id));
		when(profiles.replace(eq(userId), eq(id), any())).thenReturn(profile(id));
		String body = "{\"label\":\"Mum\",\"destinationLanguage\":\"am\",\"formality\":\"HONORIFIC\"}";
		mvc.perform(get("/api/v1/me/destination-profiles").header("Authorization", bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].label").value("Mum"))
				.andExpect(header().string("Cache-Control", "no-store"));
		mvc.perform(post("/api/v1/me/destination-profiles").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
		mvc.perform(put("/api/v1/me/destination-profiles/" + id).header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
		mvc.perform(delete("/api/v1/me/destination-profiles/" + id).header("Authorization", bearer()))
				.andExpect(status().isNoContent());
		verify(profiles).delete(userId, id);
	}

	@Test
	void badProfileBodiesAre400() throws Exception {
		for (String body : List.of("{\"destinationLanguage\":\"am\"}", "{\"label\":\"\",\"destinationLanguage\":\"am\"}",
				"{\"label\":\"" + "x".repeat(81) + "\",\"destinationLanguage\":\"am\"}", "{\"label\":\"a\"}",
				"{\"label\":\"a\",\"destinationLanguage\":\"am\",\"formality\":\"RUDE\"}",
				"{\"label\":\"a\",\"destinationLanguage\":\"am\",\"addresseeGender\":\"X\"}",
				"{\"label\":\"a\",\"destinationLanguage\":\"am\",\"defaultTone\":\"NATURAL\"}")) {
			mvc.perform(post("/api/v1/me/destination-profiles").header("Authorization", bearer())
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		verify(profiles, never()).create(any(), any());
	}

	@Test
	void rulesRoundTripAndRawPackageNamesAre400() throws Exception {
		when(rules.get(userId)).thenReturn(new RulesDocument(0, List.of()));
		when(rules.replace(eq(userId), any())).thenReturn(new RulesDocument(1, List.of()));
		mvc.perform(get("/api/v1/me/app-destination-rules").header("Authorization", bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(0));
		mvc.perform(put("/api/v1/me/app-destination-rules").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"revision\":0,\"rules\":[{\"appPackageHmac\":\"" + HASH + "\",\"destinationLanguage\":\"am\"}]}"))
				.andExpect(status().isOk());
		for (String hmac : List.of("com.whatsapp", "com.whatsapp.w4b", "A".repeat(64), "a".repeat(63), "a".repeat(65),
				"g".repeat(64), "")) {
			mvc.perform(put("/api/v1/me/app-destination-rules").header("Authorization", bearer())
					.contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0,\"rules\":[{\"appPackageHmac\":\""
							+ hmac + "\",\"destinationLanguage\":\"am\"}]}"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		verify(rules).replace(eq(userId), any());
	}

	@Test
	void unauthenticatedIs401() throws Exception {
		mvc.perform(get("/api/v1/me/destination-profiles")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me/app-destination-rules")).andExpect(status().isUnauthorized());
	}
}
