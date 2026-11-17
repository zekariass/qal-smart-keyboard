package com.qalsmartkeyboard.identity.session;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.account.UserAccount;
import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.session.SessionService.Refreshed;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;
import com.qalsmartkeyboard.testsupport.contract.OpenApiContract;
import com.qalsmartkeyboard.identity.token.TestAccessTokens;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * B2.4 / OI-P11: the real security chain in a web slice. Every response is validated against the contract; the
 * test-only operation {@value #PROTECTED} and a 401 on the optional-bearer operation {@code listResourceSigningKeys}
 * (not declared there: contract gap) are added in memory.
 */
@WebMvcTest(AuthSessionController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class, ApiSecurityWebTest.TestConfig.class,
		ApiSecurityWebTest.TestEndpoints.class })
class ApiSecurityWebTest {

	static final String PROTECTED = "/api/v1/test-protected";

	@RestController
	static class TestEndpoints {
		@GetMapping(PROTECTED)
		Map<String, Object> whoAmI(@AuthenticationPrincipal AuthenticatedUser user) {
			return Map.of("userId", user.userId().toString(), "sessionId", user.sessionId().toString(), "accountType",
					user.accountType());
		}

		/** Stand-in for an anonymous-or-bearer contract operation not implemented yet. */
		@GetMapping("/api/v1/resource-signing-keys")
		Map<String, Object> signingKeys() {
			return Map.of("items", List.of());
		}

		/** Stand-in for an anonymous, no-bearer contract operation not implemented yet. */
		@PostMapping("/api/v1/auth/password/forgot")
		org.springframework.http.ResponseEntity<Void> forgot() {
			return org.springframework.http.ResponseEntity.accepted().build();
		}

		@GetMapping("/partner/v1/languages")
		Map<String, Object> partner() {
			return Map.of("languages", List.of());
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestConfig {
		@Bean
		OpenApiInteractionValidator securityValidator() {
			return OpenApiContract.validatorWithOverlay(api -> {
				Map<String, ApiResponse> shared = api.getComponents().getResponses();
				ApiResponses responses = new ApiResponses();
				responses.addApiResponse("200", new ApiResponse().description("ok").content(new Content()
						.addMediaType("application/json", new io.swagger.v3.oas.models.media.MediaType()
								.schema(new ObjectSchema()))));
				responses.addApiResponse("401", shared.get("Unauthorized"));
				responses.addApiResponse("403", shared.get("Forbidden"));
				PathItem item = new PathItem();
				item.setGet(new Operation().responses(responses));
				api.getPaths().addPathItem(PROTECTED, item);
				api.getPaths().get("/api/v1/resource-signing-keys").getGet().getResponses()
						.addApiResponse("401", shared.get("Unauthorized"));
			});
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;
	@MockitoBean
	SessionService sessions;

	final UUID userId = UUID.randomUUID();
	final UUID sessionId = UUID.randomUUID();

	String validToken() {
		return tokens.issue(userId, sessionId, null, "REGISTERED").value();
	}

	// ---------------------------------------------------------------- protected endpoints

	@Test
	void protectedEndpointWithoutTokenIs401UnauthenticatedProblem() throws Exception {
		mvc.perform(get(PROTECTED).header("X-Correlation-Id", "sec-1")).andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.correlationId").value("sec-1")).andExpect(jsonPath("$.instance").value(PROTECTED))
				.andExpect(header().string("X-Correlation-Id", "sec-1"))
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	void protectedEndpointWithValidTokenSeesThePrincipal() throws Exception {
		mvc.perform(get(PROTECTED).header("Authorization", "Bearer " + validToken())).andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(userId.toString()))
				.andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
				.andExpect(jsonPath("$.accountType").value("REGISTERED")).andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	void expiredTokenIs401TokenExpired() throws Exception {
		String expired = TestAccessTokens.issuedAt(tokens, Instant.now().minusSeconds(3600), userId, sessionId);
		mvc.perform(get(PROTECTED).header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
				.andExpect(header().string("WWW-Authenticate", containsString("invalid_token")))
				.andExpect(content().string(org.hamcrest.Matchers.not(containsString(expired))));
	}

	@Test
	void forgedOrMalformedTokensAre401Unauthenticated() throws Exception {
		String token = validToken();
		String forged = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");
		for (String bad : new String[] { forged, "not-a-jwt", token + "x" }) {
			mvc.perform(get(PROTECTED).header("Authorization", "Bearer " + bad)).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}
		mvc.perform(get(PROTECTED).header("Authorization", "Basic dXNlcjpwYXNz")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// ---------------------------------------------------------------- anonymous endpoints

	@Test
	void optionalBearerEndpointIsAnonymousButRejectsAnInvalidToken() throws Exception {
		mvc.perform(get("/api/v1/resource-signing-keys")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/resource-signing-keys").header("Authorization", "Bearer " + validToken()))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v1/resource-signing-keys").header("Authorization", "Bearer garbage"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void noBearerEndpointsIgnoreTheAuthorizationHeader() throws Exception {
		mvc.perform(post("/api/v1/auth/password/forgot").header("Authorization", "Bearer garbage")
				.contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@example.com\"}"))
				.andExpect(status().isAccepted());
	}

	@Test
	void partnerApiIsClosedAndNeverAcceptsUserTokens() throws Exception {
		mvc.perform(get("/partner/v1/languages")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("API_KEY_INVALID"));
		mvc.perform(get("/partner/v1/languages").header("Authorization", "Bearer " + validToken()))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("API_KEY_INVALID"));
	}

	// ---------------------------------------------------------------- refresh and logout controllers

	@Test
	void refreshIsAnonymousIgnoresAStaleBearerAndReturnsANewPair() throws Exception {
		UserAccount user = new UserAccount(userId, "REGISTERED", "ACTIVE", "+251911234567", Instant.now(), null, null,
				"Africa/Addis_Ababa", Instant.now());
		when(sessions.refresh(eq("old-refresh"), any())).thenReturn(new Refreshed(new IssuedSession("new.access",
				Instant.parse("2026-11-17T10:15:00Z"), "new-refresh", Instant.parse("2027-01-16T10:00:00Z"), sessionId,
				null), user));
		String expired = TestAccessTokens.issuedAt(tokens, Instant.now().minusSeconds(3600), userId, sessionId);
		mvc.perform(post("/api/v1/auth/refresh").header("Authorization", "Bearer " + expired)
				.contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"old-refresh\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").value("new-refresh"))
				.andExpect(jsonPath("$.accessToken").value("new.access"))
				.andExpect(jsonPath("$.isNewUser").doesNotExist())
				.andExpect(header().string("Cache-Control", containsString("no-store")));
	}

	@Test
	void refreshFailuresAre401Problems() throws Exception {
		when(sessions.refresh(any(), any()))
				.thenThrow(new ApiException(ApiErrorCode.REFRESH_TOKEN_REUSED, HttpStatus.UNAUTHORIZED, "x"))
				.thenThrow(new ApiException(ApiErrorCode.TOKEN_EXPIRED, HttpStatus.UNAUTHORIZED, "x"));
		mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"t\"}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
		mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"t\"}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
	}

	@Test
	void logoutNeedsATokenAndRevokesTheCallersSession() throws Exception {
		mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
		verify(sessions, never()).logout(any(), org.mockito.ArgumentMatchers.anyBoolean());

		mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + validToken()))
				.andExpect(status().isNoContent());
		verify(sessions).logout(new AuthenticatedUser(userId, sessionId, null, "REGISTERED"), false);

		mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + validToken())
				.contentType(MediaType.APPLICATION_JSON).content("{\"allDevices\":true}")).andExpect(status().isNoContent());
		verify(sessions).logout(new AuthenticatedUser(userId, sessionId, null, "REGISTERED"), true);
	}
}
