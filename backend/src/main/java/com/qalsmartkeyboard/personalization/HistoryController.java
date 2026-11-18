package com.qalsmartkeyboard.personalization;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.personalization.HistoryDtos.HistoryItem;
import com.qalsmartkeyboard.personalization.HistoryDtos.HistoryPage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The history operations of the contract (tag History). Responses carry text: never cacheable. */
@RestController
class HistoryController {

	private final HistoryService service;
	private final JsonMapper mapper;

	HistoryController(HistoryService service, JsonMapper mapper) {
		this.service = service;
		this.mapper = mapper;
	}

	@GetMapping("/api/v1/me/history")
	ResponseEntity<HistoryPage> listHistory(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestParam(name = "favorite", required = false) Boolean favorite,
			@RequestParam(name = "featureCode", required = false) String featureCode,
			@RequestParam(name = "cursor", required = false) String cursor,
			@RequestParam(name = "limit", required = false) Integer limit) {
		if (cursor != null && cursor.length() > 512) {
			throw HistoryService.invalid("cursor", "INVALID", "The cursor is not valid.");
		}
		return ok(service.list(caller.userId(), favorite, featureCode, cursor,
				limit == null ? HistoryService.DEFAULT_PAGE : limit));
	}

	@DeleteMapping("/api/v1/me/history")
	ResponseEntity<Void> clearHistory(@AuthenticationPrincipal AuthenticatedUser caller) {
		service.clear(caller.userId());
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@GetMapping("/api/v1/me/history/{itemId}")
	ResponseEntity<HistoryItem> getHistoryItem(@AuthenticationPrincipal AuthenticatedUser caller,
			@PathVariable("itemId") UUID itemId) {
		return ok(service.get(caller.userId(), itemId));
	}

	@PatchMapping(path = "/api/v1/me/history/{itemId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<HistoryItem> updateHistoryItem(@AuthenticationPrincipal AuthenticatedUser caller,
			@PathVariable("itemId") UUID itemId, @RequestBody byte[] body) {
		return ok(service.setFavorite(caller.userId(), itemId, favorite(body)));
	}

	/**
	 * Read by hand: a framework binding error quotes the rejected value, and the framework logs it (same rule as the AI
	 * request bodies, B5.5). Only the field and the kind of problem are named.
	 */
	private boolean favorite(byte[] body) {
		JsonNode n;
		try {
			n = body == null || body.length == 0 ? null : mapper.readTree(body);
		}
		catch (RuntimeException e) {
			n = null;
		}
		if (n == null || !n.isObject()) {
			throw HistoryService.invalid(null, "MALFORMED_REQUEST_BODY",
					"The request body is missing or is not valid JSON for this endpoint.");
		}
		JsonNode v = n.get("favorite");
		if (v == null || v.isNull()) {
			throw HistoryService.invalid("favorite", "REQUIRED", "favorite is required.");
		}
		if (!v.isBoolean()) {
			throw HistoryService.invalid("favorite", "INVALID_TYPE", "favorite is invalid.");
		}
		return v.booleanValue();
	}

	@DeleteMapping("/api/v1/me/history/{itemId}")
	ResponseEntity<Void> deleteHistoryItem(@AuthenticationPrincipal AuthenticatedUser caller,
			@PathVariable("itemId") UUID itemId) {
		service.delete(caller.userId(), itemId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
