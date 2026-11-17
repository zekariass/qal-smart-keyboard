package com.qalsmartkeyboard.platform;

import java.util.Optional;

import org.slf4j.MDC;

/** Access to the correlation ID of the request being handled (also available as MDC key {@value #MDC_KEY}). */
public final class CorrelationId {

	public static final String HEADER = "X-Correlation-Id";
	public static final String MDC_KEY = "correlationId";

	private CorrelationId() {
	}

	public static Optional<String> current() {
		return Optional.ofNullable(MDC.get(MDC_KEY));
	}
}
