package com.qalsmartkeyboard.usage;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.entitlements.token.EntitlementTokenService;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.usage.UsageReadDtos.EntitlementsDto;
import com.qalsmartkeyboard.usage.UsageReadDtos.UsageReportDto;

/**
 * {@code getEntitlements} and {@code getUsage} (B4.5). Any valid access token may call them, guests included (a guest
 * sees the GUEST plan). Responses are per user and live, so {@code no-store}.
 */
@RestController
class UsageReadController {

	private final UsageReadService reads;
	private final EntitlementTokenService tokens;

	UsageReadController(UsageReadService reads, EntitlementTokenService tokens) {
		this.reads = reads;
		this.tokens = tokens;
	}

	@GetMapping("/api/v1/me/entitlements")
	ResponseEntity<EntitlementsDto> getEntitlements(@AuthenticationPrincipal AuthenticatedUser caller) {
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
