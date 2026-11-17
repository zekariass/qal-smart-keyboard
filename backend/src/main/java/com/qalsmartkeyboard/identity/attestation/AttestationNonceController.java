package com.qalsmartkeyboard.identity.attestation;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.attestation.AttestationService.IssuedNonce;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code POST /api/v1/auth/attestation-nonces} ({@code createAttestationNonce}): anonymous, or with a bearer token
 * (which must then be valid; the chain rejects an invalid one). The nonce is bound to the caller when there is one and to
 * {@code X-Client-Platform} when sent. 201 with {@code Cache-Control: no-store}.
 */
@RestController
class AttestationNonceController {

	/** {@code AttestationNonce}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AttestationNonceDto(String nonce, Instant expiresAt) {
		@Override
		public String toString() {
			return "AttestationNonceDto[expiresAt=" + expiresAt + "]";
		}
	}

	private final AttestationService service;

	AttestationNonceController(AttestationService service) {
		this.service = service;
	}

	@PostMapping("/api/v1/auth/attestation-nonces")
	ResponseEntity<AttestationNonceDto> create(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestHeader(name = "X-Client-Platform", required = false) String platform, HttpServletRequest request) {
		UUID userId = caller == null ? null : caller.userId();
		IssuedNonce issued = service.issueNonce(userId, knownPlatform(platform), request.getRemoteAddr());
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(new AttestationNonceDto(issued.nonce(), issued.expiresAt()));
	}

	/** Only the contract's platforms are stored; anything else counts as "not sent". */
	static String knownPlatform(String header) {
		return "ANDROID".equals(header) || "IOS".equals(header) || "WEB".equals(header) ? header : null;
	}
}
