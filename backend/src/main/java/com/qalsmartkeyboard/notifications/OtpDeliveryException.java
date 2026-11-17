package com.qalsmartkeyboard.notifications;

/** The SMS provider did not accept an OTP message. The message never contains the code or the phone number. */
public class OtpDeliveryException extends RuntimeException {

	public OtpDeliveryException(String message) {
		super(message);
	}

	public OtpDeliveryException(String message, Throwable cause) {
		super(message, cause);
	}
}
