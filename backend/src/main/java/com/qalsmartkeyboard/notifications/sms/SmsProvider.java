package com.qalsmartkeyboard.notifications.sms;

/**
 * Port to an SMS aggregator (SD 9.2: pluggable; a local aggregator for +251 and an international provider for
 * diaspora numbers; the choice is open item OI-11). Implementations must not log the body or the full number and must
 * not put either into exception messages.
 */
public interface SmsProvider {

	/** Stable id for metrics, e.g. {@code log}. */
	String id();

	/** @throws RuntimeException if the provider did not accept the message */
	void send(String toE164, String body);
}
