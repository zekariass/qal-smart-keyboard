package com.qalsmartkeyboard.identity.attestation;

import java.time.Clock;

import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;

/**
 * Development/test adapter for {@link AppAttestVerifier}: accepts only the {@link MockAttestationTokens} format (and
 * needs the App Attest {@code keyId}) and rejects everything else. Same enabling rules as
 * {@link MockPlayIntegrityVerifier}.
 */
final class MockAppAttestVerifier implements AppAttestVerifier {

	private final Clock clock;

	MockAppAttestVerifier(Clock clock) {
		this.clock = clock;
	}

	@Override
	public AttestationVerdict verify(Evidence evidence, String expectedNonce) {
		if (evidence == null || !"APP_ATTEST".equals(evidence.provider()) || evidence.keyId() == null
				|| evidence.keyId().isBlank()) {
			return AttestationVerdict.REJECTED;
		}
		return MockAttestationTokens.verify(MockAttestationTokens.APP_ATTEST_PREFIX, evidence, expectedNonce, clock);
	}
}
