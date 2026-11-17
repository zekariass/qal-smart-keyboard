package com.qalsmartkeyboard.identity.oauth;

/**
 * An ID token that was not accepted, or a verifier that could not decide. The message is only the {@link Failure} name:
 * it never carries token content, claim values or the underlying library message (those can contain an e-mail address
 * or the token itself).
 */
final class IdTokenVerificationException extends Exception {

	private static final long serialVersionUID = 1L;

	enum Failure {
		/** Not a three-part JWT, unparsable, or too large. */
		MALFORMED,
		/** {@code alg} is not the pinned one ({@code none}, HS256, ES256 ...) or no key of that algorithm matches. */
		UNSUPPORTED_ALGORITHM_OR_KEY,
		BAD_SIGNATURE,
		/** No key with the token's {@code kid} even after a (rate-limited) JWKS refresh. */
		UNKNOWN_KEY,
		EXPIRED,
		NOT_YET_VALID,
		BAD_ISSUER,
		BAD_AUDIENCE,
		BAD_NONCE,
		MISSING_CLAIM,
		/** The JWKS could not be fetched and no cached copy covers the token: the provider is unavailable. */
		JWKS_UNAVAILABLE;

		boolean unavailable() {
			return this == JWKS_UNAVAILABLE;
		}
	}

	private final Failure failure;

	IdTokenVerificationException(Failure failure) {
		super(failure.name(), null, false, false);
		this.failure = failure;
	}

	Failure failure() {
		return failure;
	}
}
