package com.qalsmartkeyboard.identity.security;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ProblemResponseWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 401 as a contract Problem (with correlation id) instead of Spring's empty 401:
 * <ul>
 * <li>a valid but expired access token: {@code TOKEN_EXPIRED} (the client refreshes);</li>
 * <li>no token, or any other invalid token (signature, {@code kid}, algorithm, claims): {@code UNAUTHENTICATED};</li>
 * <li>{@code /partner/v1/**}: {@code API_KEY_INVALID}.</li>
 * </ul>
 * {@code WWW-Authenticate} follows RFC 6750 without echoing anything from the token.
 */
final class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ProblemResponseWriter writer;

	ApiAuthenticationEntryPoint(ProblemResponseWriter writer) {
		this.writer = writer;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
			throws IOException {
		ApiException problem;
		if (ApiPaths.PARTNER.matches(request)) {
			problem = problem(ApiErrorCode.API_KEY_INVALID, "A valid partner API key is required.", "Bearer");
		}
		else if (exception instanceof OAuth2AuthenticationException) {
			problem = onlyExpired(exception)
					? problem(ApiErrorCode.TOKEN_EXPIRED, "The access token has expired. Refresh it and retry.",
							"Bearer error=\"invalid_token\"")
					: problem(ApiErrorCode.UNAUTHENTICATED, "The access token is not valid.",
							"Bearer error=\"invalid_token\"");
		}
		else {
			problem = problem(ApiErrorCode.UNAUTHENTICATED, "A valid access token is required.", "Bearer");
		}
		writer.write(request, response, problem);
	}

	/** True when the token passed every check except expiry. */
	static boolean onlyExpired(Throwable exception) {
		for (Throwable t = exception; t != null; t = t.getCause()) {
			if (t instanceof JwtValidationException invalid) {
				return !invalid.getErrors().isEmpty() && invalid.getErrors().stream()
						.allMatch(e -> AccessTokenService.EXPIRED_ERROR_CODE.equals(e.getErrorCode()));
			}
		}
		return false;
	}

	private static ApiException problem(ApiErrorCode code, String detail, String challenge) {
		return new ApiException(code, HttpStatus.UNAUTHORIZED, detail, List.of(), Map.of(),
				Map.of("WWW-Authenticate", challenge));
	}
}
