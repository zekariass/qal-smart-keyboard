package com.qalsmartkeyboard.billing.spi;

import java.util.Objects;

/**
 * A provider call failed. The message is a short safe code only: never a provider response body, a URL with secrets or
 * payer data.
 */
public class PaymentProviderException extends RuntimeException {

	public enum Kind {
		/** The provider refused the request (4xx, business error): do not retry the same request. */
		REJECTED,
		/** Timeout, connection failure or 5xx: the outcome is unknown, retry or verify later. */
		UNAVAILABLE,
		/** The provider answered something we cannot interpret. */
		INVALID_RESPONSE,
		/** Our configuration is wrong (credentials refused, missing setting). */
		MISCONFIGURED
	}

	private final Kind kind;
	private final String safeCode;

	public PaymentProviderException(Kind kind, String safeCode) {
		super(kind + ":" + safeCode);
		this.kind = Objects.requireNonNull(kind, "kind");
		this.safeCode = safeCode;
	}

	public Kind kind() {
		return kind;
	}

	public String safeCode() {
		return safeCode;
	}
}
