package com.qalsmartkeyboard.notifications.sms;

/**
 * Port to an SMS aggregator (SD 9.2: pluggable). Production uses {@code SmsEthiopiaProvider} (ADR 0007 D48, ADR 0025:
 * Ethio Telecom {@code +2519} numbers only). Implementations must not log the body or the full number and must not put
 * either into exception messages.
 */
public interface SmsProvider {

	/** Stable id for metrics, e.g. {@code log}. */
	String id();

	/**
	 * @throws SmsDeliveryException if the provider did not accept the message (adapters should use it; any other
	 *                              {@link RuntimeException} is treated as an untyped failure)
	 */
	void send(String toE164, String body);
}
