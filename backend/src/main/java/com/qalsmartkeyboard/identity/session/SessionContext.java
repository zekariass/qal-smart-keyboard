package com.qalsmartkeyboard.identity.session;

import java.util.UUID;

import com.qalsmartkeyboard.identity.DeviceInfo;

/**
 * Request metadata recorded on a session row.
 *
 * @param deviceId  {@code user_devices.id} when already known (guest sign-up registers its device itself), else
 *                  {@code null}
 * @param ipAddress client address as seen by the server (proxy headers are resolved by the servlet container)
 * @param userAgent {@code User-Agent}, truncated; may be {@code null}
 * @param device    the installation to register for the user while the session is issued (the {@code device} member
 *                  of a sign-in request), or {@code null}; ignored when {@code deviceId} is set and for platforms
 *                  without devices ({@code WEB})
 */
public record SessionContext(UUID deviceId, String ipAddress, String userAgent, DeviceInfo device) {

	static final int MAX_USER_AGENT = 512;

	public SessionContext(UUID deviceId, String ipAddress, String userAgent) {
		this(deviceId, ipAddress, userAgent, null);
	}

	public SessionContext {
		if (userAgent != null && userAgent.length() > MAX_USER_AGENT) {
			userAgent = userAgent.substring(0, MAX_USER_AGENT);
		}
	}
}
