package com.qalsmartkeyboard.entitlements.token;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.qalsmartkeyboard.entitlements.token.EntitlementTokenService.EntitlementToken;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitPolicy;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

/**
 * {@code getEntitlementToken} (bearer, any ACTIVE account including guests; {@code no-store}) and
 * {@code getEntitlementTokenKeys} ({@code /.well-known/jwks.json}, anonymous; the public keys only).
 */
@RestController
class EntitlementTokenController {

	/** The key set changes only on a rotation, which is staged over days (ADR 0013). */
	static final CacheControl KEYS_CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

	/** ADR 0007 D27: every call signs a new token, so one user may ask 60 times an hour. */
	static final RateLimitPolicy PER_USER = new RateLimitPolicy("entitlements.token.user", 60,
			Duration.ofHours(1));

	private final EntitlementTokenService tokens;
	private final RateLimiter limiter;

	EntitlementTokenController(EntitlementTokenService tokens, RateLimiter limiter) {
		this.tokens = tokens;
		this.limiter = limiter;
	}

	@GetMapping(path = "/api/v1/me/entitlements/token", produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<EntitlementToken> getEntitlementToken(@AuthenticationPrincipal AuthenticatedUser caller) {
		limiter.tryConsume(PER_USER, caller.userId().toString()).orThrow();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(tokens.issue(caller.userId(), caller.deviceId()));
	}

	@GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<Map<String, Object>> getEntitlementTokenKeys() {
		return ResponseEntity.ok().cacheControl(KEYS_CACHE).body(tokens.publicKeys().toJSONObject(true));
	}
}
