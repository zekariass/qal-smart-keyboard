package com.qalsmartkeyboard.notifications.sms;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed failure of an {@link SmsProvider} (ADR 0025). Carries a {@link Reason}, an optional {@code Retry-After} and
 * never a cause, the number, the body or any provider text (provider errors can echo the number).
 */
public final class SmsDeliveryException extends RuntimeException {

	/** Why the provider did not accept the message. {@code alert} = an operator must act (no user can fix it). */
	public enum Reason {
		/** The provider (or our local check) refused the number. */
		INVALID_NUMBER(false),
		/** The request we built was refused (missing/empty field, unknown path, unexpected 4xx): our bug. */
		INTEGRATION_ERROR(true),
		/** The provider account/campaign is not usable (inactive campaign, sender ID not approved). */
		PROVIDER_MISCONFIGURED(true),
		/** The prepaid SMS balance cannot cover the message: top up. */
		INSUFFICIENT_BALANCE(true),
		/** Test campaign: the recipient is not whitelisted in the provider dashboard. */
		RECIPIENT_NOT_WHITELISTED(false),
		/** The API key is missing, invalid, expired or deactivated. */
		AUTHENTICATION_FAILED(true),
		/** The provider's rate limit was hit; see {@link SmsDeliveryException#retryAfter()}. */
		RATE_LIMITED(false),
		/** 5xx, timeout or I/O failure. */
		UNAVAILABLE(false),
		/** 2xx with a body we could not read or a status other than accepted. */
		INVALID_RESPONSE(true);

		private final boolean alert;

		Reason(boolean alert) {
			this.alert = alert;
		}

		public boolean alert() {
			return alert;
		}

		/** Lower-case tag value, e.g. {@code insufficient_balance}. */
		public String tag() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private final String providerId;
	private final Reason reason;
	private final transient Duration retryAfter;

	public SmsDeliveryException(String providerId, Reason reason, Duration retryAfter) {
		super("SMS provider " + providerId + " did not accept the message: " + reason.tag());
		this.providerId = Objects.requireNonNull(providerId, "providerId");
		this.reason = Objects.requireNonNull(reason, "reason");
		this.retryAfter = retryAfter;
	}

	public SmsDeliveryException(String providerId, Reason reason) {
		this(providerId, reason, null);
	}

	public String providerId() {
		return providerId;
	}

	public Reason reason() {
		return reason;
	}

	public Optional<Duration> retryAfter() {
		return Optional.ofNullable(retryAfter);
	}
}
