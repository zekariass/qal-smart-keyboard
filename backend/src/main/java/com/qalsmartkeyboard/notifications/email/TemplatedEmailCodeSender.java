package com.qalsmartkeyboard.notifications.email;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qalsmartkeyboard.notifications.EmailCodeMessage;
import com.qalsmartkeyboard.notifications.EmailCodeSender;
import com.qalsmartkeyboard.notifications.EmailDeliveryException;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Renders the code e-mails and hands them to the {@link EmailSender}. Short plain text, English only for now (like the
 * SMS text), no links and no tracking. Counts {@code qal.notifications.email.sent{provider,kind,outcome}}. A provider
 * failure is logged with the exception class only and rethrown without a cause.
 */
final class TemplatedEmailCodeSender implements EmailCodeSender {

	private static final Logger log = LoggerFactory.getLogger(TemplatedEmailCodeSender.class);

	/** {@code null} when the provider is {@code none}. */
	private final EmailSender provider;
	private final MeterRegistry meters;

	TemplatedEmailCodeSender(EmailSender provider, MeterRegistry meters) {
		this.provider = provider;
		this.meters = meters;
	}

	@Override
	public boolean available() {
		return provider != null;
	}

	/** The selected provider, {@code null} for {@code none}. */
	EmailSender provider() {
		return provider;
	}

	@Override
	public void send(EmailCodeMessage message) {
		Objects.requireNonNull(message, "message");
		if (provider == null) {
			count("none", message.kind(), "unavailable");
			throw new EmailDeliveryException("No e-mail provider is configured");
		}
		try {
			provider.send(message.to(), subject(message), body(message));
		}
		catch (RuntimeException e) {
			count(provider.id(), message.kind(), "failed");
			log.warn("E-mail provider {} rejected a {} message ({})", provider.id(), message.kind(),
					e.getClass().getName());
			throw new EmailDeliveryException("E-mail provider " + provider.id() + " did not accept the message");
		}
		count(provider.id(), message.kind(), "accepted");
	}

	static String subject(EmailCodeMessage message) {
		return switch (message.kind()) {
			case SIGN_UP -> "Your Qal sign-up code: " + message.code();
			case VERIFY_EMAIL -> "Your Qal verification code: " + message.code();
			case PASSWORD_RESET -> "Your Qal password reset code: " + message.code();
		};
	}

	static String body(EmailCodeMessage message) {
		long minutes = Math.max(1, message.validity().toMinutes());
		String validity = "It expires in " + minutes + (minutes == 1 ? " minute" : " minutes") + ".";
		return switch (message.kind()) {
			case SIGN_UP -> "Your code to finish creating your Qal account is " + message.code() + ".\n" + validity
					+ "\n\nIf you did not try to create a Qal account, ignore this e-mail. Never share this code.\n";
			case VERIFY_EMAIL -> "Your code to verify your e-mail address for Qal is " + message.code() + ".\n"
					+ validity + "\n\nIf you did not try to sign in to Qal, ignore this e-mail and consider changing "
					+ "your password. Never share this code.\n";
			case PASSWORD_RESET -> "Your code to reset your Qal password is " + message.code() + ".\n" + validity
					+ "\n\nIf you did not ask to reset your password, ignore this e-mail; your password stays the same. "
					+ "Never share this code.\n";
		};
	}

	private void count(String providerId, EmailCodeMessage.Kind kind, String outcome) {
		if (meters != null) {
			meters.counter("qal.notifications.email.sent", "provider", providerId, "kind", kind.name(), "outcome",
					outcome).increment();
		}
	}
}
