package com.qalsmartkeyboard.identity.token;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Test helper: access tokens signed with the application's key but issued at another instant (e.g. already expired). */
public final class TestAccessTokens {

	private TestAccessTokens() {
	}

	public static String issuedAt(AccessTokenService service, Instant issuedAt, UUID userId, UUID sessionId) {
		return service.withClock(Clock.fixed(issuedAt, ZoneOffset.UTC)).issue(userId, sessionId, null, "REGISTERED")
				.value();
	}
}
