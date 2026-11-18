package com.qalsmartkeyboard.languages;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.languages.DictionaryDtos.Change;
import com.qalsmartkeyboard.languages.DictionaryDtos.ChangesPage;
import com.qalsmartkeyboard.languages.DictionaryDtos.ChangesResult;
import com.qalsmartkeyboard.languages.DictionaryDtos.Rejected;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/** B3.6: both operations against the contract (service mocked) behind the real security chain. */
@WebMvcTest(DictionaryController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class })
class DictionaryControllerTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;
	@MockitoBean
	DictionarySyncService sync;

	final UUID userId = UUID.randomUUID();

	String bearer() {
		return "Bearer " + tokens.issue(userId, UUID.randomUUID(), null, "REGISTERED").value();
	}

	static String change(String op, String language, String word) {
		return "{\"op\":\"" + op + "\",\"languageCode\":\"" + language + "\",\"word\":\"" + word
				+ "\",\"clientChangeId\":\"" + UUID.randomUUID() + "\"}";
	}

	@Test
	void pullAndPushMatchTheContract() throws Exception {
		UUID entry = UUID.randomUUID();
		when(sync.pull(any(AuthenticatedUser.class), eq("v1.NQ"), eq(2)))
				.thenReturn(new ChangesPage(List.of(new Change("ADD", "am", "ሰላም", entry, Instant.now()),
						new Change("DELETE", "am", "ቤት", UUID.randomUUID(), Instant.now())), "v1.Nw", true));
		mvc.perform(get("/api/v1/me/dictionary/changes?since=v1.NQ&limit=2").header("Authorization", bearer()))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.changes[0].word").value("ሰላም")).andExpect(jsonPath("$.changes[1].op").value("DELETE"))
				.andExpect(jsonPath("$.hasMore").value(true));
		when(sync.push(any(AuthenticatedUser.class), any()))
				.thenReturn(new ChangesResult(1, List.of(new Rejected(UUID.randomUUID(), "INVALID_WORD")), "v1.MQ"));
		mvc.perform(post("/api/v1/me/dictionary/changes").header("Authorization", bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"changes\":[" + change("ADD", "am", "ሰላም") + "," + change("DELETE", "am", "ቤት") + "]}"))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.applied").value(1)).andExpect(jsonPath("$.rejected[0].code").value("INVALID_WORD"))
				.andExpect(jsonPath("$.syncToken").value("v1.MQ"));
	}

	@Test
	void malformedBodiesAndParametersAre400() throws Exception {
		for (String body : List.of("{}", "{\"changes\":[]}", "{\"changes\":[" + change("UPDATE", "am", "x") + "]}",
				"{\"changes\":[" + change("ADD", "AM", "x") + "]}", "{\"changes\":[" + change("ADD", "am", "") + "]}",
				"{\"changes\":[" + change("ADD", "am", "x".repeat(65)) + "]}",
				"{\"changes\":[{\"op\":\"ADD\",\"languageCode\":\"am\",\"word\":\"x\",\"clientChangeId\":\"nope\"}]}")) {
			mvc.perform(post("/api/v1/me/dictionary/changes").header("Authorization", bearer())
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		mvc.perform(get("/api/v1/me/dictionary/changes?limit=0").header("Authorization", bearer()))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/me/dictionary/changes?limit=101").header("Authorization", bearer()))
				.andExpect(status().isBadRequest());
		verify(sync, never()).push(any(), any());
	}

	@Test
	void gateFailuresAre403() throws Exception {
		when(sync.pull(any(AuthenticatedUser.class), any(), eq(20)))
				.thenThrow(new ApiException(ApiErrorCode.GUEST_NOT_ALLOWED, HttpStatus.FORBIDDEN, "guest"));
		mvc.perform(get("/api/v1/me/dictionary/changes").header("Authorization", bearer()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
	}

	@Test
	void tokensAreBase64AndRoundTrip() {
		org.assertj.core.api.Assertions.assertThat(DictionarySyncService.parseToken(DictionarySyncService.token(42)))
				.isEqualTo(42);
		org.assertj.core.api.Assertions.assertThat(DictionarySyncService.parseToken(null)).isZero();
		for (String bad : List.of("42", "v1.", "v1.!!", "v1.LTE", "x1.NDI")) {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> DictionarySyncService.parseToken(bad))
					.isInstanceOf(ApiException.class);
		}
	}

	@Test
	void unauthenticatedIs401() throws Exception {
		mvc.perform(get("/api/v1/me/dictionary/changes")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/me/dictionary/changes").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
	}
}
