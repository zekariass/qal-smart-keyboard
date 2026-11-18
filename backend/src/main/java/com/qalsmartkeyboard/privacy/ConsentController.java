package com.qalsmartkeyboard.privacy;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.privacy.ConsentDtos.ConsentDto;
import com.qalsmartkeyboard.privacy.ConsentDtos.ConsentUpdateDto;
import com.qalsmartkeyboard.privacy.ConsentDtos.ConsentsDto;

import jakarta.validation.Valid;

/**
 * {@code listConsents} and {@code setConsent}. Any valid access token may use them, guests included: the contract does not
 * restrict them, a guest may decide (for example {@code ANALYTICS}) and the decisions are appended for the account on
 * sign-in (ADR 0010, {@link PrivacyGuestMerge}). Responses are {@code no-store}.
 */
@RestController
class ConsentController {

	private final ConsentService consents;

	ConsentController(ConsentService consents) {
		this.consents = consents;
	}

	@GetMapping("/api/v1/me/consents")
	ResponseEntity<ConsentsDto> listConsents(@AuthenticationPrincipal AuthenticatedUser caller) {
		return noStore(new ConsentsDto(consents.latest(caller.userId())));
	}

	@PutMapping(path = "/api/v1/me/consents/{consentCode}", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<ConsentDto> setConsent(@AuthenticationPrincipal AuthenticatedUser caller,
			@PathVariable String consentCode,
			@RequestHeader(name = "X-Client-Platform", required = false) String platform,
			@Valid @RequestBody ConsentUpdateDto body) {
		ConsentType type = ConsentType.parse(consentCode)
				.orElseThrow(() -> new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null,
						List.of(new ApiFieldError("consentCode", "UNKNOWN_CONSENT", "Unknown consent code.")), Map.of()));
		return noStore(consents.record(caller.userId(), type, body.consentVersion(), body.isGranted(),
				platform(platform), body.details()));
	}

	/** The header is validated by the app-version interceptor; a value other than the three is ignored. */
	private static String platform(String header) {
		if (header == null) {
			return null;
		}
		String p = header.toUpperCase(Locale.ROOT);
		return p.equals("ANDROID") || p.equals("IOS") || p.equals("WEB") ? p : null;
	}

	private static <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
