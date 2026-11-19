package com.qalsmartkeyboard.notifications;

/**
 * Delivers one-time e-mail codes (ADR 0033): sign-up verification, verification of an existing account, password
 * reset. The identity module generates and hashes the code; this port renders the message and hands it to the
 * configured e-mail provider ({@code qal.notifications.email.provider}).
 *
 * <p>Called <em>outside</em> any database transaction (SD 8.3). Implementations never log the code or the full
 * address.
 */
public interface EmailCodeSender {

	/**
	 * Whether a provider that can deliver e-mail is configured ({@code log} and {@code capture} count in development;
	 * {@code none} does not). Flows that need a code by e-mail answer 503 {@code EMAIL_NOT_CONFIGURED} without one.
	 */
	boolean available();

	/** @throws EmailDeliveryException if no provider is configured or the provider did not accept the message */
	void send(EmailCodeMessage message);
}
