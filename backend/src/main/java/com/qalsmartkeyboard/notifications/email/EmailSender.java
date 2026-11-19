package com.qalsmartkeyboard.notifications.email;

/**
 * Port to an e-mail delivery service (ADR 0033). Only development adapters exist: {@code log} and {@code capture}. A
 * real vendor (Amazon SES, Postmark, Resend, Brevo, ... - an open decision in ADR 0033) is added as another
 * implementation selected in {@link EmailConfiguration}. Implementations must not log the body or the full address and
 * must not put either into exception messages.
 */
public interface EmailSender {

	/** Stable id for metrics and logs, e.g. {@code log}. */
	String id();

	/**
	 * @param to       the address
	 * @param subject  plain-text subject
	 * @param textBody plain-text body (no HTML, no tracking links)
	 * @throws RuntimeException if the provider did not accept the message (the message must not echo the address)
	 */
	void send(String to, String subject, String textBody);
}
