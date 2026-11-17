package com.qalsmartkeyboard.identity.security;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Authentication requirements per operation, taken from the {@code security} of each operation in
 * {@code contracts/openapi.yaml}. The contract's default is {@code bearerAuth}; only these operations differ.
 *
 * <ul>
 * <li>{@link #OPTIONAL_BEARER} ({@code security: [{}, bearerAuth]}): anonymous allowed; a presented bearer token must
 * be valid (an invalid or expired one is 401, so a client never silently loses its identity, e.g. for the guest merge
 * on verify).</li>
 * <li>{@link #NO_BEARER} ({@code security: [{}]} or {@code []}): anonymous; the {@code Authorization} header is ignored
 * (webhook and store callbacks may send their own, and refresh must work with an expired access token around).</li>
 * <li>{@link #PARTNER} ({@code partnerApiKey}): partner API keys are not implemented yet, so it is closed (401
 * {@code API_KEY_INVALID}); user access tokens are never accepted there.</li>
 * </ul>
 * Actuator health is anonymous (probes); everything else needs a valid access token.
 */
final class ApiPaths {

	private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

	static final RequestMatcher OPTIONAL_BEARER = new OrRequestMatcher(List.of(
			post("/api/v1/auth/attestation-nonces"), post("/api/v1/auth/otp/start"), post("/api/v1/auth/otp/verify"),
			post("/api/v1/auth/oauth/{provider}"), post("/api/v1/auth/email/register"), post("/api/v1/auth/email/login"),
			get("/api/v1/config"), get("/api/v1/ai/capabilities"), get("/api/v1/languages"),
			get("/api/v1/languages/{languageCode}/transliteration-profiles"), get("/api/v1/language-resources"),
			get("/api/v1/language-resources/{resourceId}"), get("/api/v1/resource-signing-keys")));

	static final RequestMatcher NO_BEARER = new OrRequestMatcher(List.of(post("/api/v1/auth/password/forgot"),
			post("/api/v1/auth/password/reset"), post("/api/v1/auth/refresh"), post("/api/v1/auth/guest"),
			post("/api/v1/billing/webhooks/{providerCode}/{accountCode}"),
			post("/api/v1/billing/store-notifications/{providerCode}"), PATHS.matcher("/actuator/health"),
			PATHS.matcher("/actuator/health/**")));

	static final RequestMatcher PARTNER = PATHS.matcher("/partner/v1/**");

	private ApiPaths() {
	}

	private static RequestMatcher post(String pattern) {
		return PATHS.matcher(HttpMethod.POST, pattern);
	}

	private static RequestMatcher get(String pattern) {
		return PATHS.matcher(HttpMethod.GET, pattern);
	}
}
