package com.qalsmartkeyboard.identity.security;

import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * A guest access token whose account is no longer an active guest (merged, upgraded, suspended, deleted). It is an invalid
 * bearer token like any other, but the entry point answers it with the distinct code {@code GUEST_SESSION_INVALID}
 * (ADR 0007 D20) so a client knows to discard the guest session instead of treating it as a transient auth failure.
 */
final class GuestSessionInvalidException extends InvalidBearerTokenException {

	private static final long serialVersionUID = 1L;

	GuestSessionInvalidException() {
		super("The guest account is no longer active");
	}
}
