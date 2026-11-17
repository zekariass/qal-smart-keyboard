package com.qalsmartkeyboard.identity.guest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;
import com.qalsmartkeyboard.identity.account.UserAccount;
import com.qalsmartkeyboard.identity.guest.GuestService.GuestSession;
import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.session.IssuedSession;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B2.3: {@code createGuestSession} against the contract (service mocked), behind the real security chain, where the
 * operation is anonymous and ignores {@code Authorization}.
 */
@WebMvcTest(GuestController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class })
class GuestControllerTest {

	static final String GUEST = "/api/v1/auth/guest";

	@Autowired
	MockMvc mvc;
	@MockitoBean
	GuestService service;

	final UUID installation = UUID.randomUUID();

	String body(String attestation) {
		return "{\"installationId\":\"" + installation + "\",\"platform\":\"ANDROID\""
				+ (attestation == null ? "" : ",\"attestation\":" + attestation) + "}";
	}

	static final String EVIDENCE = "{\"provider\":\"PLAY_INTEGRITY\",\"token\":\"t-secret\",\"nonce\":\"n-1\"}";

	@Test
	void createsAGuestSessionPer200AuthSession() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID deviceId = UUID.randomUUID();
		when(service.create(any(), any(), any(), any(), any(), any())).thenReturn(new GuestSession(
				new UserAccount(userId, "GUEST", "ACTIVE", null, null, null, null, "Africa/Addis_Ababa",
						Instant.parse("2026-11-17T10:00:00Z")),
				new IssuedSession("access.jwt.token", Instant.parse("2026-11-17T10:15:00Z"), "refresh-token",
						Instant.parse("2027-01-16T10:00:00Z"), UUID.randomUUID(), deviceId)));
		mvc.perform(post(GUEST).header("X-App-Version", "2.0.3").header("User-Agent", "qal-android")
				.contentType(MediaType.APPLICATION_JSON).content(body(EVIDENCE))).andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer")).andExpect(jsonPath("$.accessToken").value("access.jwt.token"))
				.andExpect(jsonPath("$.refreshToken").value("refresh-token"))
				.andExpect(jsonPath("$.deviceId").value(deviceId.toString())).andExpect(jsonPath("$.isNewUser").value(true))
				.andExpect(jsonPath("$.user.id").value(userId.toString()))
				.andExpect(jsonPath("$.user.accountType").value("GUEST"))
				.andExpect(header().string("Cache-Control", "no-store"));
		ArgumentCaptor<Evidence> evidence = ArgumentCaptor.forClass(Evidence.class);
		verify(service).create(eq(installation), eq("ANDROID"), evidence.capture(), eq("127.0.0.1"), eq("qal-android"),
				eq("2.0.3"));
		org.assertj.core.api.Assertions.assertThat(evidence.getValue().token()).isEqualTo("t-secret");
	}

	/** The "done when" of B2.3: no valid attestation, no guest. */
	@Test
	void aGuestWithoutValidAttestationGetsGuestNotAllowed() throws Exception {
		when(service.create(any(), any(), any(), any(), any(), any())).thenThrow(GuestService.notAllowed());
		mvc.perform(post(GUEST).contentType(MediaType.APPLICATION_JSON).content(body(EVIDENCE)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"))
				.andExpect(jsonPath("$.status").value(403)).andExpect(jsonPath("$.correlationId").exists())
				.andExpect(content().string(not(containsString("t-secret"))));
	}

	@Test
	void aMissingAttestationIsPassedAsNullSoTheServiceAnswersGuestNotAllowed() throws Exception {
		when(service.create(any(), any(), isNull(), any(), any(), any())).thenThrow(GuestService.notAllowed());
		mvc.perform(post(GUEST).contentType(MediaType.APPLICATION_JSON).content(body(null)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
	}

	@Test
	void malformedRequestsAre400WithoutReachingTheService() throws Exception {
		mvc.perform(post(GUEST).contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"nope\",\"platform\":\"ANDROID\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(content().string(not(containsString("nope"))));
		mvc.perform(post(GUEST).contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"ANDROID\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("installationId"));
		mvc.perform(post(GUEST).contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"" + installation + "\",\"platform\":\"WINDOWS\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post(GUEST).contentType(MediaType.APPLICATION_JSON)
				.content(body("{\"provider\":\"CARRIER_PIGEON\",\"token\":\"x\",\"nonce\":\"y\"}")))
				.andExpect(status().isBadRequest());
		verify(service, never()).create(any(), any(), any(), any(), any(), any());
	}

	@Test
	void rateLimitedIs429ProblemWithRateLimitHeaders() throws Exception {
		when(service.create(any(), any(), any(), any(), any(), any())).thenThrow(new RateLimitDecision(
				new RateLimitPolicy("guest.ip", 10, Duration.ofHours(1)), false, 0, Duration.ofSeconds(90),
				Duration.ofSeconds(90)).toProblem());
		mvc.perform(post(GUEST).contentType(MediaType.APPLICATION_JSON).content(body(EVIDENCE)))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(header().string("Retry-After", "90")).andExpect(header().string("RateLimit-Limit", "10"));
	}

	/** The operation is anonymous: an invalid {@code Authorization} header is ignored, not a 401. */
	@Test
	void theAuthorizationHeaderIsIgnored() throws Exception {
		when(service.create(any(), any(), any(), any(), any(), any())).thenThrow(GuestService.notAllowed());
		mvc.perform(post(GUEST).header("Authorization", "Bearer garbage").contentType(MediaType.APPLICATION_JSON)
				.content(body(EVIDENCE))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
	}
}
