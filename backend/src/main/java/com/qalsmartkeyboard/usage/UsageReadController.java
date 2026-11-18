package com.qalsmartkeyboard.usage;

import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.entitlements.EntitlementTokenIssuer;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.usage.UsageReadDtos.EntitlementsDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.UsageReportDto;

/**
 * {@code getEntitlements} and {@code getUsage} (B4.5). Any valid access token may call them, guests included (a guest
 * sees the GUEST plan). Responses are per user and live, so {@code no-store}.
 */
@RestController
class UsageReadController {

	private final UsageReadService reads;
	private final ObjectProvider<EntitlementTokenIssuer> issuer;

	UsageReadController(UsageReadService reads, ObjectProvider<EntitlementTokenIssuer> issuer) {
		this.reads = reads;
		this.issuer = issuer;
	}

	@GetMapping("/api/v1/me/entitlements")
	ResponseEntity<EntitlementsDto> getEntitlements(@AuthenticationPrincipal AuthenticatedUser caller) {
		EntitlementTokenIssuer tokens = issuer.getIfAvailable();
		if (tokens == null) {
			throw new ApiException(ApiErrorCode.PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
					"Entitlement tokens cannot be issued right now.");
		}
		return noStore(reads.entitlements(caller.userId(), caller.deviceId(), tokens));
	}

	@GetMapping("/api/v1/me/usage")
	ResponseEntity<UsageReportDto> getUsage(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		return noStore(reads.usage(caller.userId(), from, to));
	}

	private static <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
