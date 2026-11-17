package com.qalsmartkeyboard.identity.oauth;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.oauth.OAuthService.Exchange;
import com.qalsmartkeyboard.identity.oauth.OAuthService.SignedIn;
import com.qalsmartkeyboard.identity.session.AuthSessionDto;
import com.qalsmartkeyboard.identity.session.DeviceRegistrationDto;
import com.qalsmartkeyboard.identity.session.SessionContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/auth/oauth/{provider}} ({@code exchangeOAuthIdToken}). Anonymous in the contract (a presented
 * bearer token must still be valid, see {@code ApiPaths}; the guest merge belongs to B2.6).
 *
 * <p>{@code provider} is the contract's enum, {@code GOOGLE} or {@code APPLE}. Only Google can be verified so far:
 * every other value, Apple included, is a 400 {@code VALIDATION_FAILED} on {@code provider}
 * ({@code PROVIDER_NOT_SUPPORTED}). The client IP is {@link HttpServletRequest#getRemoteAddr()} (proxy headers are
 * the container's job, see the README).
 */
@RestController
class OAuthController {

	private final OAuthService service;

	OAuthController(OAuthService service) {
		this.service = service;
	}

	/** {@code OAuthExchangeRequest}. {@code authorizationCode} is Apple-only and unused. */
	record OAuthExchangeRequest(@NotBlank @Size(max = 8192) String idToken, @NotBlank @Size(max = 256) String nonce,
			@Size(max = 2048) String authorizationCode, @Size(max = 120) String displayName, Boolean ageConfirmed,
			@Valid DeviceRegistrationDto device) {

		/** Never prints the tokens, the nonce or the name. */
		@Override
		public String toString() {
			return "OAuthExchangeRequest[...]";
		}
	}

	@PostMapping(path = "/api/v1/auth/oauth/{provider}", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AuthSessionDto> exchange(@PathVariable("provider") String provider,
			@Valid @RequestBody OAuthExchangeRequest body, HttpServletRequest request) {
		if (!"GOOGLE".equals(provider) && !"APPLE".equals(provider)) {
			throw OAuthService.unsupported();
		}
		String deviceKey = body.device() == null ? null : body.device().installationId().toString();
		SignedIn signedIn = service.exchange(provider,
				new Exchange(body.idToken(), body.nonce(), body.displayName(), Boolean.TRUE.equals(body.ageConfirmed())),
				request.getRemoteAddr(), deviceKey,
				new SessionContext(null, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT),
						body.device() == null ? null : body.device().toDeviceInfo(request.getHeader("X-App-Version"))));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(AuthSessionDto.of(signedIn.session(), signedIn.user(), signedIn.created()));
	}
}
