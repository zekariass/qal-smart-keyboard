package com.qalsmartkeyboard.aigateway;

/**
 * The caller went away while the provider was working (thread interrupted; ADR 0007 D29). The reservation was released
 * and the request recorded as CANCELLED; there is no client-visible outcome, so B5.5 sends no body (the connection is
 * gone). The thread's interrupt flag is set again before this is thrown.
 */
public class AiRequestCancelledException extends RuntimeException {

	public AiRequestCancelledException() {
		super("AI request cancelled by the caller", null, false, false);
	}
}
