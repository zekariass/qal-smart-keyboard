package com.qalsmartkeyboard.identity.attestation;

import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;

/**
 * Verifies a Google Play Integrity token (Android). The real adapter calls Google's {@code decodeIntegrityToken}, checks
 * the app and device verdicts and that {@code requestDetails.nonce} equals {@code expectedNonce}. It does not exist
 * yet; until it does the only implementation is {@link MockPlayIntegrityVerifier}, enabled for development and tests
 * only. Implementations must never throw for a bad token (return {@code REJECTED}) and must report an unreachable
 * Google as {@code UNAVAILABLE}.
 */
public interface PlayIntegrityVerifier {

	/** @param expectedNonce the server nonce the verdict must cover */
	AttestationVerdict verify(Evidence evidence, String expectedNonce);
}
