package com.qalsmartkeyboard.notifications;

/** No e-mail provider is configured, or it did not accept a message. Never contains the code or the address. */
public class EmailDeliveryException extends RuntimeException {

	public EmailDeliveryException(String message) {
		super(message);
	}
}
