package com.qalsmartkeyboard.identity.guest;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;
import com.qalsmartkeyboard.identity.guest.GuestService.GuestSession;
import com.qalsmartkeyboard.identity.session.AuthSessionDto;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/auth/guest} ({@code createGuestSession}): anonymous, the {@code Authorization} header is ignored.
 * The client IP is {@code getRemoteAddr()} (see ADR 0006 for proxies).
 */
@RestController
class GuestController {

	/**
	 * {@code AttestationEvidence} as the guest request carries it. The members are not required here: a missing or blank
	 * verdict is a failed attestation (403 {@code GUEST_NOT_ALLOWED}), not a malformed request. Shapes are still checked
	 * (provider enum, sizes).
	 */
	record AttestationEvidenceDto(@Pattern(regexp = "^(PLAY_INTEGRITY|APP_ATTEST)$") String provider,
			@Size(max = 16384) String token, @Size(max = 256) String keyId, @Size(max = 128) String nonce) {

		Evidence toEvidence() {
			return new Evidence(provider, token, keyId, nonce);
		}

		@Override
		public String toString() {
			return "AttestationEvidenceDto[provider=" + provider + "]";
		}
	}

	/** {@code GuestSessionRequest}. */
	record GuestSessionRequest(@NotNull UUID installationId, @NotNull @Pattern(regexp = "^(ANDROID|IOS|WEB)$") String platform,
			@Valid AttestationEvidenceDto attestation) {
	}

	private final GuestService service;

	GuestController(GuestService service) {
		this.service = service;
	}

	@PostMapping(path = "/api/v1/auth/guest", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<AuthSessionDto> create(@Valid @RequestBody GuestSessionRequest body,
			@RequestHeader(name = "X-App-Version", required = false) String appVersion, HttpServletRequest request) {
		GuestSession guest = service.create(body.installationId(), body.platform(),
				body.attestation() == null ? null : body.attestation().toEvidence(), request.getRemoteAddr(),
				request.getHeader(HttpHeaders.USER_AGENT), appVersion);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(AuthSessionDto.of(guest.session(), guest.user(), true));
	}
}
