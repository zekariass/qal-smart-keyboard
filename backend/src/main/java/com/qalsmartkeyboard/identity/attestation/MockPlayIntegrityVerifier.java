package com.qalsmartkeyboard.identity.attestation;

import java.time.Clock;

import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;

/**
 * Development/test adapter for {@link PlayIntegrityVerifier}: accepts only the {@link MockAttestationTokens} format and
 * rejects everything else. Enabled by {@code qal.identity.attestation.mock.enabled=true}, refused under the
 * {@code prod}, {@code production} and {@code staging} profiles.
 */
final class MockPlayIntegrityVerifier implements PlayIntegrityVerifier {

	private final Clock clock;

	MockPlayIntegrityVerifier(Clock clock) {
		this.clock = clock;
	}

	@Override
	public AttestationVerdict verify(Evidence evidence, String expectedNonce) {
		if (evidence == null || !"PLAY_INTEGRITY".equals(evidence.provider())) {
			return AttestationVerdict.REJECTED;
		}
		return MockAttestationTokens.verify(MockAttestationTokens.PLAY_INTEGRITY_PREFIX, evidence, expectedNonce, clock);
	}
}
