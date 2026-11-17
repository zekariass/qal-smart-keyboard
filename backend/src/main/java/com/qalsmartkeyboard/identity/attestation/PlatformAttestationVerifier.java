package com.qalsmartkeyboard.identity.attestation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;

/**
 * The {@link AttestationVerifier} strategy. An adapter that throws is reported as {@code UNAVAILABLE} (fail closed; only
 * the exception class is logged, because a message could carry parts of the token).
 */
final class PlatformAttestationVerifier implements AttestationVerifier {

	private static final Logger log = LoggerFactory.getLogger(PlatformAttestationVerifier.class);

	private final PlayIntegrityVerifier playIntegrity;
	private final AppAttestVerifier appAttest;

	/** Either adapter may be {@code null} (not configured). */
	PlatformAttestationVerifier(PlayIntegrityVerifier playIntegrity, AppAttestVerifier appAttest) {
		this.playIntegrity = playIntegrity;
		this.appAttest = appAttest;
	}

	@Override
	public AttestationVerdict verify(String platform, Evidence evidence, String expectedNonce) {
		try {
			if ("ANDROID".equals(platform) && playIntegrity != null) {
				return playIntegrity.verify(evidence, expectedNonce);
			}
			if ("IOS".equals(platform) && appAttest != null) {
				return appAttest.verify(evidence, expectedNonce);
			}
			return AttestationVerdict.UNAVAILABLE;
		}
		catch (RuntimeException e) {
			log.warn("The {} attestation verifier failed ({}); treating the verdict as unavailable", platform,
					e.getClass().getSimpleName());
			return AttestationVerdict.UNAVAILABLE;
		}
	}
}
