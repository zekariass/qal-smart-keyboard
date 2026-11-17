package com.qalsmartkeyboard.notifications.sms;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qalsmartkeyboard.notifications.OtpDeliveryException;
import com.qalsmartkeyboard.notifications.OtpMessage;
import com.qalsmartkeyboard.notifications.OtpSender;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Renders the OTP text and hands it to the {@link SmsProvider}. English only for now: localised templates (am, ti, om)
 * need native-speaker review (gate G0) and come with the notification templates.
 *
 * <p>Counts {@code qal.notifications.sms.sent{provider,outcome=accepted|failed}}. A provider failure is logged with
 * the exception class only (provider errors can echo the number or the body) and rethrown without a cause.
 */
final class SmsOtpSender implements OtpSender {

	private static final Logger log = LoggerFactory.getLogger(SmsOtpSender.class);

	private final SmsProvider provider;
	private final MeterRegistry meters;

	SmsOtpSender(SmsProvider provider, MeterRegistry meters) {
		this.provider = Objects.requireNonNull(provider, "provider");
		this.meters = meters;
	}

	@Override
	public void send(OtpMessage message) {
		String body = render(message);
		try {
			provider.send(message.phoneE164(), body);
		}
		catch (RuntimeException e) {
			count("failed");
			log.warn("SMS provider {} rejected an OTP message ({})", provider.id(), e.getClass().getName());
			throw new OtpDeliveryException("SMS provider " + provider.id() + " did not accept the message");
		}
		count("accepted");
	}

	static String render(OtpMessage message) {
		long minutes = Math.max(1, message.validity().toMinutes());
		return "Qal: your sign-in code is " + message.code() + ". It expires in " + minutes
				+ (minutes == 1 ? " minute" : " minutes") + ". Never share this code.";
	}

	private void count(String outcome) {
		if (meters != null) {
			meters.counter("qal.notifications.sms.sent", "provider", provider.id(), "outcome", outcome).increment();
		}
	}
}
