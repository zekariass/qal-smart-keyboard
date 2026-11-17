package com.qalsmartkeyboard.notifications;

/**
 * Delivers one-time sign-in codes (SD 8.2: notifications owns SMS). The identity module generates and hashes the code;
 * this port only renders the message and hands it to the configured SMS provider.
 *
 * <p>Called <em>outside</em> any database transaction (SD 8.3). Implementations never log the code or the full phone
 * number (SD 18 logging hygiene).
 */
public interface OtpSender {

	/** @throws OtpDeliveryException if the provider did not accept the message */
	void send(OtpMessage message);
}
