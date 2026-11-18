package com.qalsmartkeyboard.identity.oauth;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.identity.DeviceInfo;
import com.qalsmartkeyboard.identity.account.UserAccount;
import com.qalsmartkeyboard.identity.oauth.OAuthService.Exchange;
import com.qalsmartkeyboard.identity.oauth.OAuthService.SignedIn;
import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.session.IssuedSession;
import com.qalsmartkeyboard.identity.session.SessionContext;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B2.2: {@code exchangeOAuthIdToken} responses against the contract (service mocked), behind the real security chain:
 * the operation is reachable anonymously, and an invalid bearer is still a 401.
 */
@WebMvcTest(OAuthController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class })
class OAuthControllerTest {

	static final String GOOGLE = "/api/v1/auth/oauth/GOOGLE";
	static final String BODY = "{\"idToken\":\"id.token.secret\",\"nonce\":\"nonce-1\"}";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	OAuthService service;

	@Test
	void returnsAnAuthSessionWithNoStoreAndNeverEchoesTheToken() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID installation = UUID.randomUUID();
		UserAccount user = new UserAccount(userId, "REGISTERED", "ACTIVE", null, null, "Test User", null,
				"Africa/Addis_Ababa", Instant.parse("2026-11-17T10:00:00Z"));
		IssuedSession session = new IssuedSession("access.jwt.value", Instant.parse("2026-11-17T10:15:00Z"),
				"refresh-token-value", Instant.parse("2027-01-16T10:00:00Z"), UUID.randomUUID(), null);
		when(service.exchange(eq("GOOGLE"), any(), eq("127.0.0.1"), eq(installation.toString()), any(), isNull()))
				.thenReturn(new SignedIn(user, true, session, null));
		mvc.perform(post(GOOGLE).header("X-App-Version", "2.0.1").contentType(MediaType.APPLICATION_JSON).content("""
				{"idToken":"id.token.secret","nonce":"nonce-1","displayName":"Test User","ageConfirmed":true,
				 "device":{"installationId":"%s","platform":"ANDROID"}}
				""".formatted(installation))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.tokenType").value("Bearer")).andExpect(jsonPath("$.accessToken").value("access.jwt.value"))
				.andExpect(jsonPath("$.isNewUser").value(true)).andExpect(jsonPath("$.user.id").value(userId.toString()))
				.andExpect(jsonPath("$.user.accountType").value("REGISTERED"))
				.andExpect(jsonPath("$.user.displayName").value("Test User"))
				.andExpect(jsonPath("$.user.phoneNumberMasked").doesNotExist())
				.andExpect(content().string(not(containsString("id.token.secret"))));

		ArgumentCaptor<Exchange> exchange = ArgumentCaptor.forClass(Exchange.class);
		ArgumentCaptor<SessionContext> context = ArgumentCaptor.forClass(SessionContext.class);
		verify(service).exchange(eq("GOOGLE"), exchange.capture(), any(), any(), context.capture(), isNull());
		org.assertj.core.api.Assertions.assertThat(exchange.getValue().idToken()).isEqualTo("id.token.secret");
		org.assertj.core.api.Assertions.assertThat(exchange.getValue().ageConfirmed()).isTrue();
		DeviceInfo device = context.getValue().device();
		org.assertj.core.api.Assertions.assertThat(device.installationId()).isEqualTo(installation);
		org.assertj.core.api.Assertions.assertThat(device.appVersion()).isEqualTo("2.0.1");
	}

	@Test
	void anApplicationProblemIsReturnedAsAContractProblem() throws Exception {
		when(service.exchange(any(), any(), any(), isNull(), any(), any()))
				.thenThrow(new ApiException(ApiErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, "The ID token was not accepted."))
				.thenThrow(new ApiException(ApiErrorCode.ACCOUNT_SUSPENDED, HttpStatus.FORBIDDEN, null))
				.thenThrow(new ApiException(ApiErrorCode.PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, null))
				.thenThrow(new RateLimitDecision(new RateLimitPolicy("oauth.exchange.ip", 60, Duration.ofHours(1)), false,
						0, Duration.ofSeconds(90), Duration.ofSeconds(90)).toProblem());
		mvc.perform(post(GOOGLE).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.correlationId").isNotEmpty())
				.andExpect(content().string(not(containsString("id.token.secret"))));
		mvc.perform(post(GOOGLE).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
		mvc.perform(post(GOOGLE).contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
		mvc.perform(post(GOOGLE).contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(header().string("Retry-After", "90"));
	}

	@Test
	void appleAndUnknownProvidersAreRefusedAsValidationErrorsWithoutCallingTheVerifier() throws Exception {
		when(service.exchange(eq("APPLE"), any(), any(), any(), any(), any())).thenThrow(OAuthService.unsupported());
		mvc.perform(post("/api/v1/auth/oauth/APPLE").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("provider"))
				.andExpect(jsonPath("$.errors[0].code").value("PROVIDER_NOT_SUPPORTED"));
		mvc.perform(post("/api/v1/auth/oauth/FACEBOOK").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PROVIDER_NOT_SUPPORTED"));
		mvc.perform(post("/api/v1/auth/oauth/google").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest());
		verify(service, never()).exchange(eq("FACEBOOK"), any(), any(), any(), any(), any());
	}

	@Test
	void malformedBodiesAre400WithoutEchoingTheValues() throws Exception {
		mvc.perform(post(GOOGLE).contentType(MediaType.APPLICATION_JSON).content("{\"idToken\":\"x\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("nonce"));
		mvc.perform(post(GOOGLE).contentType(MediaType.APPLICATION_JSON).content("{\"nonce\":\"n\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("idToken"));
		mvc.perform(post(GOOGLE).contentType(MediaType.APPLICATION_JSON)
				.content("{\"idToken\":\"" + "a".repeat(8193) + "\",\"nonce\":\"n\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("idToken"))
				.andExpect(content().string(not(containsString("aaaaaaaa"))));
		mvc.perform(post(GOOGLE).contentType(MediaType.APPLICATION_JSON)
				.content("{\"idToken\":\"t\",\"nonce\":\"n\",\"device\":{\"installationId\":\"not-a-uuid\",\"platform\":\"ANDROID\"}}"))
				.andExpect(status().isBadRequest());
	}

	/** ADR 0007 D9: an invalid bearer on an optional-bearer operation stays a 401 (declared in the contract). */
	@Test
	void anInvalidBearerTokenIs401() throws Exception {
		mvc.perform(post(GOOGLE).header("Authorization", "Bearer garbage").contentType(MediaType.APPLICATION_JSON)
				.content(BODY)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}
}
