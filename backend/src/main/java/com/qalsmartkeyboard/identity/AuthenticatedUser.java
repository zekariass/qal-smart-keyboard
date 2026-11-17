package com.qalsmartkeyboard.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * The caller of an authenticated {@code /api/v1} request, taken from a verified access token. Other modules read it
 * with {@code @AuthenticationPrincipal AuthenticatedUser user} in a controller method.
 *
 * <p>The access token is stateless: a session revoked by logout or reuse detection keeps working for at most the
 * access-token lifetime (15 minutes). Operations that must not run on a revoked session check it explicitly.
 *
 * @param userId      {@code users.id} ({@code sub})
 * @param sessionId   the refresh-token family ({@code sid}); stable across refreshes, revoked as a whole
 * @param deviceId    {@code user_devices.id} ({@code did}) or {@code null} when the session was created without one (it is bound by
 *                    {@code POST /devices} and appears in the next refreshed access token)
 * @param accountType {@code REGISTERED} or {@code GUEST}
 */
public record AuthenticatedUser(UUID userId, UUID sessionId, UUID deviceId, String accountType) {

	public static final String REGISTERED = "REGISTERED";
	public static final String GUEST = "GUEST";

	public AuthenticatedUser {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(accountType, "accountType");
	}

	public boolean isGuest() {
		return GUEST.equals(accountType);
	}
}
