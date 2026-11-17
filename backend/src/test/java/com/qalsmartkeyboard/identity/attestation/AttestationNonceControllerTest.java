package com.qalsmartkeyboard.identity.attestation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.identity.attestation.AttestationService.IssuedNonce;
import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/** B2.3: {@code createAttestationNonce} against the contract (service mocked), behind the real security chain. */
@WebMvcTest(AttestationNonceController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class })
class AttestationNonceControllerTest {

	static final String NONCES = "/api/v1/auth/attestation-nonces";
	static final String NONCE = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCde";

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;
	@MockitoBean
	AttestationService service;

	@Test
	void anonymousCallerGets201NonceThatIsNeverCached() throws Exception {
		when(service.issueNonce(isNull(), eq("ANDROID"), any()))
				.thenReturn(new IssuedNonce(NONCE, Instant.parse("2026-11-17T10:05:00Z")));
		mvc.perform(post(NONCES).header("X-Client-Platform", "ANDROID").header("X-App-Version", "2.0.3"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.nonce").value(NONCE))
				.andExpect(jsonPath("$.expiresAt").value("2026-11-17T10:05:00Z"))
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(header().exists("X-Correlation-Id"));
		verify(service).issueNonce(isNull(), eq("ANDROID"), eq("127.0.0.1"));
	}

	@Test
	void aBearerTokenBindsTheNonceToTheUserAndAnUnknownPlatformCountsAsNone() throws Exception {
		UUID userId = UUID.randomUUID();
		when(service.issueNonce(any(), any(), any()))
				.thenReturn(new IssuedNonce(NONCE, Instant.parse("2026-11-17T10:05:00Z")));
		String bearer = "Bearer " + tokens.issue(userId, UUID.randomUUID(), null, "REGISTERED").value();
		mvc.perform(post(NONCES).header("Authorization", bearer)).andExpect(status().isCreated());
		verify(service).issueNonce(eq(userId), isNull(), any());
	}

	@Test
	void anInvalidBearerTokenIs401NotAnAnonymousNonce() throws Exception {
		mvc.perform(post(NONCES).header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		org.mockito.Mockito.verifyNoInteractions(service);
	}

	@Test
	void rateLimitedIs429ProblemWithRetryAfter() throws Exception {
		when(service.issueNonce(any(), any(), any())).thenThrow(new RateLimitDecision(
				new RateLimitPolicy("attestation.nonce.ip", 120, Duration.ofHours(1)), false, 0, Duration.ofSeconds(30),
				Duration.ofSeconds(30)).toProblem());
		mvc.perform(post(NONCES)).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().string("Retry-After", "30"));
	}
}
