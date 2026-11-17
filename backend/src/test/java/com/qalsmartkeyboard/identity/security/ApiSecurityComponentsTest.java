package com.qalsmartkeyboard.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;

import tools.jackson.databind.json.JsonMapper;

class ApiSecurityComponentsTest {

	final ServletProblemResponseWriter writer = new ServletProblemResponseWriter("https://api.example.com/problems/",
			JsonMapper.builder().build());

	@Test
	void accessDeniedIsA403ForbiddenProblem() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
		MockHttpServletResponse response = new MockHttpServletResponse();
		ApiSecurityConfiguration.accessDeniedHandler(writer).handle(request, response, new AccessDeniedException("no"));
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentType()).startsWith("application/problem+json");
		assertThat(response.getContentAsString()).contains("\"code\":\"FORBIDDEN\"").contains("\"correlationId\"")
				.contains("\"instance\":\"/api/v1/x\"").doesNotContain("\"no\"");
		assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
	}

	@Test
	void onlyAnExpiryErrorCountsAsExpired() {
		OAuth2Error expired = new OAuth2Error(AccessTokenService.EXPIRED_ERROR_CODE);
		OAuth2Error other = new OAuth2Error("invalid_token");
		assertThat(ApiAuthenticationEntryPoint.onlyExpired(new InvalidBearerTokenException("x",
				new JwtValidationException("x", List.of(expired))))).isTrue();
		assertThat(ApiAuthenticationEntryPoint.onlyExpired(new InvalidBearerTokenException("x",
				new JwtValidationException("x", List.of(expired, other))))).isFalse();
		assertThat(ApiAuthenticationEntryPoint.onlyExpired(new InvalidBearerTokenException("x",
				new BadJwtException("bad signature")))).isFalse();
	}

	@Test
	void bearerTokensAreIgnoredOnNoBearerAndPartnerPaths() {
		var resolver = ApiSecurityConfiguration.bearerTokenResolver();
		for (String[] call : new String[][] { { "POST", "/api/v1/auth/refresh" },
				{ "POST", "/api/v1/billing/webhooks/CHAPA/main" },
				{ "POST", "/api/v1/billing/store-notifications/GOOGLE_PLAY" }, { "GET", "/partner/v1/languages" },
				{ "GET", "/actuator/health" } }) {
			MockHttpServletRequest request = new MockHttpServletRequest(call[0], call[1]);
			request.addHeader("Authorization", "Bearer abc.def.ghi");
			assertThat(resolver.resolve(request)).as(call[1]).isNull();
		}
		MockHttpServletRequest optional = new MockHttpServletRequest("POST", "/api/v1/auth/otp/verify");
		optional.addHeader("Authorization", "Bearer abc.def.ghi");
		assertThat(resolver.resolve(optional)).isEqualTo("abc.def.ghi");
		MockHttpServletRequest protectedCall = new MockHttpServletRequest("GET", "/api/v1/me");
		protectedCall.addHeader("Authorization", "Bearer abc.def.ghi");
		assertThat(resolver.resolve(protectedCall)).isEqualTo("abc.def.ghi");
	}
}
