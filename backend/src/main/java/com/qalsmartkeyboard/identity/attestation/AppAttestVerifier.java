package com.qalsmartkeyboard.identity.attestation;

import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;

/**
 * Verifies an Apple App Attest attestation or assertion (iOS). The real adapter validates the attestation object
 * against Apple's root, the App ID hash and the key id, and that the client data hash includes
 * {@code expectedNonce}. It does not exist yet; until it does the only implementation is
 * {@link MockAppAttestVerifier}, enabled for development and tests only. Same contract as
 * {@link PlayIntegrityVerifier}.
 */
public interface AppAttestVerifier {

	/** @param expectedNonce the server nonce the evidence must cover */
	AttestationVerdict verify(Evidence evidence, String expectedNonce);
}
