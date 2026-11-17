package com.qalsmartkeyboard.identity.session;

import java.util.UUID;

/**
 * Request metadata recorded on a session row.
 *
 * @param deviceId  {@code user_devices.id} when known (device registration arrives with B2.5), else {@code null}
 * @param ipAddress client address as seen by the server (proxy headers are resolved by the servlet container)
 * @param userAgent {@code User-Agent}, truncated; may be {@code null}
 */
public record SessionContext(UUID deviceId, String ipAddress, String userAgent) {

	static final int MAX_USER_AGENT = 512;

	public SessionContext {
		if (userAgent != null && userAgent.length() > MAX_USER_AGENT) {
			userAgent = userAgent.substring(0, MAX_USER_AGENT);
		}
	}
}
