package com.qalsmartkeyboard.identity.oauth;

/**
 * The minimal claims of a verified ID token. {@code email} is present only when the provider asserts it is verified
 * ({@code email_verified}); otherwise it is {@code null}. No token is kept.
 */
record VerifiedIdToken(String subject, String email, String name) {

	/** Never prints the e-mail address or the name. */
	@Override
	public String toString() {
		return "VerifiedIdToken[subject=<redacted>]";
	}
}
