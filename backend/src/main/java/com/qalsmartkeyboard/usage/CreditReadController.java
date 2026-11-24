package com.qalsmartkeyboard.usage;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.usage.CreditReadService.CreditSummaryDto;
import com.qalsmartkeyboard.usage.CreditReadService.CreditTransactionPageDto;

/**
 * {@code getCredits} and {@code listCreditTransactions} (T8.10). Any valid access token, guests included; always the
 * caller's own account (the user id comes from the token, never from the request). {@code no-store}.
 */
@RestController
class CreditReadController {

	private final CreditReadService reads;

	CreditReadController(CreditReadService reads) {
		this.reads = reads;
	}

	@GetMapping("/api/v1/me/credits")
	ResponseEntity<CreditSummaryDto> getCredits(@AuthenticationPrincipal AuthenticatedUser caller) {
		return noStore(reads.summary(caller.userId()));
	}

	@GetMapping("/api/v1/me/credits/transactions")
	ResponseEntity<CreditTransactionPageDto> listCreditTransactions(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestParam(name = "cursor", required = false) String cursor,
			@RequestParam(name = "limit", required = false) Integer limit) {
		if (cursor != null && cursor.length() > 512) {
			cursor = "!"; // rejected as an invalid cursor below
		}
		return noStore(reads.transactions(caller.userId(), cursor, limit));
	}

	private static <T> ResponseEntity<T> noStore(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
