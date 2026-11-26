package com.qalsmartkeyboard.personalization;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.personalization.CreativeBackupDtos.Item;
import com.qalsmartkeyboard.personalization.CreativeBackupDtos.ItemMetadata;
import com.qalsmartkeyboard.personalization.CreativeBackupDtos.ItemPage;
import com.qalsmartkeyboard.personalization.CreativeBackupDtos.Summary;
import com.qalsmartkeyboard.personalization.CreativeBackupService.PutRequest;
import com.qalsmartkeyboard.personalization.CreativeBackupService.Saved;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;
import com.qalsmartkeyboard.privacy.ConsentGate;
import com.qalsmartkeyboard.privacy.ConsentType;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract tag {@code CreativeBackup} (T8.15, ADR 0050): {@code listCreativeBackupItems}, {@code getCreativeBackupItem},
 * {@code putCreativeBackupItem}, {@code deleteCreativeBackupItem}, {@code clearCreativeBackup},
 * {@code getCreativeBackupSummary}. Every response is {@code no-store}.
 *
 * <p>Checks, in this order, on every operation: the product switch {@code qal.creative-backup.enabled} (off: 404
 * {@code NOT_FOUND}), the per-user rate limit (429 {@code RATE_LIMITED}), a registered account (guests 403
 * {@code GUEST_NOT_ALLOWED}), and for the operations that read or store content (list, get, put) a granted
 * {@code CREATIVE_BACKUP} consent (403 {@code CONSENT_REQUIRED}). Deleting and the usage summary work without the consent,
 * so a person can always see and erase what is stored (there is nothing once the consent is withdrawn: withdrawal
 * deletes it).
 *
 * <p>The PUT body is read from the stream with a bound and parsed by hand: a framework binding error would quote the
 * rejected value, and the content must never reach a log.
 */
@RestController
class CreativeBackupController {

	private final CreativeBackupService service;
	private final CreativeBackupProperties properties;
	private final ConsentGate consents;
	private final RateLimiter limiter;
	private final RateLimitPolicy policy;
	private final JsonMapper mapper;

	CreativeBackupController(CreativeBackupService service, CreativeBackupProperties properties, ConsentGate consents,
			RateLimiter limiter, JsonMapper mapper) {
		this.service = service;
		this.properties = properties;
		this.consents = consents;
		this.limiter = limiter;
		this.mapper = mapper;
		this.policy = new RateLimitPolicy("creative-backup.user", properties.rateLimit(), properties.rateLimitPeriod());
	}

	@GetMapping("/api/v1/me/creative-backup/items")
	ResponseEntity<ItemPage> listCreativeBackupItems(@AuthenticationPrincipal AuthenticatedUser caller,
			@RequestParam(name = "kind", required = false) String kind,
			@RequestParam(name = "cursor", required = false) String cursor,
			@RequestParam(name = "limit", required = false) Integer limit) {
		gate(caller, true);
		return ok(service.list(caller.userId(), kind, cursor, limit == null ? CreativeBackupService.DEFAULT_PAGE : limit));
	}

	@GetMapping("/api/v1/me/creative-backup/items/{itemId}")
	ResponseEntity<Item> getCreativeBackupItem(@AuthenticationPrincipal AuthenticatedUser caller,
			@PathVariable("itemId") String itemId) {
		gate(caller, true);
		return ok(service.get(caller.userId(), itemId));
	}

	@PutMapping(path = "/api/v1/me/creative-backup/items/{itemId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<ItemMetadata> putCreativeBackupItem(@AuthenticationPrincipal AuthenticatedUser caller,
			@PathVariable("itemId") String itemId, HttpServletRequest request) throws IOException {
		gate(caller, true);
		CreativeBackupService.requireItemId(itemId);
		Saved saved = service.put(caller.userId(), itemId, parse(request));
		return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
				.cacheControl(CacheControl.noStore()).body(saved.item());
	}

	@DeleteMapping("/api/v1/me/creative-backup/items/{itemId}")
	ResponseEntity<Void> deleteCreativeBackupItem(@AuthenticationPrincipal AuthenticatedUser caller,
			@PathVariable("itemId") String itemId) {
		gate(caller, false);
		service.delete(caller.userId(), itemId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@DeleteMapping("/api/v1/me/creative-backup")
	ResponseEntity<Void> clearCreativeBackup(@AuthenticationPrincipal AuthenticatedUser caller) {
		gate(caller, false);
		service.clear(caller.userId());
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@GetMapping("/api/v1/me/creative-backup/summary")
	ResponseEntity<Summary> getCreativeBackupSummary(@AuthenticationPrincipal AuthenticatedUser caller) {
		gate(caller, false);
		return ok(service.summary(caller.userId()));
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private void gate(AuthenticatedUser caller, boolean consentRequired) {
		if (!properties.enabled()) {
			throw CreativeBackupService.notFound();
		}
		limiter.tryConsume(policy, caller.userId().toString()).orThrow();
		if (caller.isGuest()) {
			throw new ApiException(ApiErrorCode.GUEST_NOT_ALLOWED, HttpStatus.FORBIDDEN,
					"Sign in to back up your creations.");
		}
		if (consentRequired) {
			consents.requireConsent(caller.userId(), ConsentType.CREATIVE_BACKUP);
		}
	}

	/** Reads and validates the {@code CreativeBackupItemPut} body; only field names and problem kinds are reported. */
	private PutRequest parse(HttpServletRequest request) throws IOException {
		int max = properties.maxRequestBytes();
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(max + 1);
		}
		if (body.length > max) {
			throw service.tooLarge();
		}
		JsonNode n;
		try {
			n = body.length == 0 ? null : mapper.readTree(body);
		}
		catch (RuntimeException e) {
			n = null;
		}
		if (n == null || !n.isObject()) {
			throw CreativeBackupService.invalid(null, "MALFORMED_REQUEST_BODY",
					"The request body is missing or is not valid JSON for this endpoint.");
		}
		String kind = string(n, "kind");
		if (!CreativeBackupService.KINDS.contains(kind)) {
			throw CreativeBackupService.invalid("kind", "INVALID", "kind is not a known item kind.");
		}
		Instant updatedAt;
		try {
			updatedAt = OffsetDateTime.parse(string(n, "updatedAt"), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
		}
		catch (RuntimeException e) {
			throw CreativeBackupService.invalid("updatedAt", "INVALID", "updatedAt must be an RFC 3339 date-time.");
		}
		Long expected = null;
		JsonNode r = n.get("expectedRevision");
		if (r != null && !r.isNull()) {
			if (!(r.isInt() || r.isLong()) || r.longValue() < 0) {
				throw CreativeBackupService.invalid("expectedRevision", "INVALID",
						"expectedRevision must be a non-negative integer.");
			}
			expected = r.longValue();
		}
		String encoded = string(n, "content");
		if (encoded.length() > 4L * ((properties.maxItemBytes() + 2L) / 3)) {
			throw service.tooLarge();
		}
		byte[] content;
		try {
			content = Base64.getDecoder().decode(encoded);
		}
		catch (IllegalArgumentException e) {
			throw CreativeBackupService.invalid("content", "INVALID_BASE64", "content must be standard base64.");
		}
		if (content.length == 0) {
			throw CreativeBackupService.invalid("content", "REQUIRED", "content must not be empty.");
		}
		if (content.length > properties.maxItemBytes()) {
			throw service.tooLarge();
		}
		return new PutRequest(kind, updatedAt, expected, content);
	}

	private static String string(JsonNode body, String field) {
		JsonNode v = body.get(field);
		if (v == null || v.isNull()) {
			throw CreativeBackupService.invalid(field, "REQUIRED", field + " is required.");
		}
		if (!v.isString()) {
			throw CreativeBackupService.invalid(field, "INVALID_TYPE", field + " is invalid.");
		}
		return v.stringValue();
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}
}
