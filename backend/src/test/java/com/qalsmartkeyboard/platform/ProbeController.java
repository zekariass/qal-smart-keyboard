package com.qalsmartkeyboard.platform;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Test-only controller (not part of the app; lives in src/test) that exercises each error path. */
@RestController
@Validated
class ProbeController {

	record Body(@NotBlank String name, @Min(1) int count, @Size(max = 5) String note) {
	}

	@PostMapping(path = "/probe/validated", consumes = MediaType.APPLICATION_JSON_VALUE)
	Map<String, Object> validated(@Valid @RequestBody Body body) {
		return Map.of("ok", true);
	}

	@GetMapping("/probe/limit")
	Map<String, Object> limit(@RequestParam @Max(100) int limit) {
		return Map.of("limit", limit);
	}

	@GetMapping("/probe/api-exception")
	void apiException(@RequestParam ApiErrorCode code) {
		throw new ApiException(code, code.defaultStatus(), "Something safe to show.");
	}

	@GetMapping("/probe/quota")
	void quota() {
		throw new ApiException(ApiErrorCode.QUOTA_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS, null, List.of(),
				Map.of("featureCode", "TRANSLATE", "resetAt", "2026-11-17T21:00:00Z", "creditBalance", 0));
	}

	/** B2.1: a Problem with response headers (rate limiting). */
	@GetMapping("/probe/rate-limited")
	void rateLimited() {
		throw new ApiException(ApiErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, null, List.of(), Map.of(),
				Map.of("Retry-After", "42", "RateLimit-Limit", "5", "RateLimit-Remaining", "0", "RateLimit-Reset", "42"));
	}

	@GetMapping("/probe/boom")
	void boom() {
		throw new IllegalStateException("secret internal text with user content: hello@example.com");
	}

	@GetMapping("/probe/ping")
	Map<String, Object> ping() {
		return Map.of("pong", true);
	}

	/** Deliberately violates the Problem schema (no code, no correlationId). */
	@GetMapping("/probe/violating")
	ResponseEntity<String> violating() {
		return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.body("{\"type\":\"about:blank\",\"title\":\"x\",\"status\":400}");
	}
}
