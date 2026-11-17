package com.qalsmartkeyboard.identity.session;

import java.time.Instant;
import java.util.UUID;

/**
 * A freshly issued token pair. The refresh token exists in clear only here, on its way to the client.
 *
 * @param sessionId the token family id (stable across rotations; the access token's {@code sid})
 * @param deviceId  {@code user_devices.id} or {@code null}
 */
public record IssuedSession(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
		Instant refreshTokenExpiresAt, UUID sessionId, UUID deviceId) {

	/** Never prints the tokens. */
	@Override
	public String toString() {
		return "IssuedSession[sessionId=" + sessionId + ", deviceId=" + deviceId + ", accessTokenExpiresAt="
				+ accessTokenExpiresAt + ", refreshTokenExpiresAt=" + refreshTokenExpiresAt + "]";
	}
}
