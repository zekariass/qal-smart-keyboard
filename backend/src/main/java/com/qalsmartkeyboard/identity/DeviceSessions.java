package com.qalsmartkeyboard.identity;

import java.util.UUID;

/**
 * Session operations that the {@code devices} module needs, offered by identity (which owns {@code user_sessions}).
 * Both join the caller's transaction.
 */
public interface DeviceSessions {

	/**
	 * Ends every session family of the user that is bound to the device (reason {@code DEVICE_REVOKED}), so their
	 * refresh tokens stop working. Access tokens already issued stay valid for at most their lifetime (15 minutes).
	 *
	 * @return the number of session rows revoked
	 */
	int revokeDeviceSessions(UUID userId, UUID deviceId);

	/**
	 * Binds the caller's session family to the device if it has none yet, so tokens issued by the next refresh carry
	 * the {@code did} claim. A family that already has a device keeps it.
	 *
	 * @return whether the family was bound by this call
	 */
	boolean bindDeviceToSession(UUID userId, UUID sessionId, UUID deviceId);
}
