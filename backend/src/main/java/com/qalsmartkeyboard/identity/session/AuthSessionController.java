package com.qalsmartkeyboard.identity.session;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.session.SessionService.Refreshed;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/auth/refresh} ({@code refreshSession}, anonymous: the refresh token is the credential) and
 * {@code POST /api/v1/auth/logout} ({@code logout}, bearer access token).
 */
@RestController
class AuthSessionController {

	/** {@code RefreshRequest}. */
	record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {
		@Override
		public String toString() {
			return "RefreshRequest[refreshToken=<redacted>]";
		}
	}

	/** {@code LogoutRequest}. */
	record LogoutRequest(Boolean allDevices) {
	}

	private final SessionService sessions;

	AuthSessionController(SessionService sessions) {
		this.sessions = sessions;
	}

	@PostMapping(path = "/api/v1/auth/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AuthSessionDto> refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
		Refreshed refreshed = sessions.refresh(body.refreshToken(),
				new SessionContext(null, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT)));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(AuthSessionDto.of(refreshed.session(), refreshed.user(), null));
	}

	@PostMapping("/api/v1/auth/logout")
	ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestBody(required = false) LogoutRequest body) {
		sessions.logout(caller, body != null && Boolean.TRUE.equals(body.allDevices()));
		return ResponseEntity.noContent().build();
	}
}
