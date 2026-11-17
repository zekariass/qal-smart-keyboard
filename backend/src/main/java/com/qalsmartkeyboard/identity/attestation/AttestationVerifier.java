package com.qalsmartkeyboard.identity.attestation;

import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;

/**
 * Chooses the verifier by platform: {@code ANDROID} uses {@link PlayIntegrityVerifier}, {@code IOS} uses
 * {@link AppAttestVerifier}. A platform without an adapter (none configured, or {@code WEB}) is
 * {@link AttestationVerdict#UNAVAILABLE}: the application fails closed, it never treats missing verification as success.
 */
public interface AttestationVerifier {

	AttestationVerdict verify(String platform, Evidence evidence, String expectedNonce);
}
