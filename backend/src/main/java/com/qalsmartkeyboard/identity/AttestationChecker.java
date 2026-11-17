package com.qalsmartkeyboard.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Checks a Play Integrity / App Attest verdict for the {@code devices} module ({@code POST /devices/{id}/attestations}).
 * Implemented by identity, which owns attestation nonces and the verifier adapters ({@code identity.attestation}).
 *
 * <p>The nonce in the evidence is consumed (single use) whatever the outcome, so a replayed verdict fails. Nothing is
 * thrown for a bad verdict: the answer is a {@link Result}.
 */
public interface AttestationChecker {

	/**
	 * @param provider {@code PLAY_INTEGRITY} or {@code APP_ATTEST}
	 * @param token    the verdict token (write-only; never logged)
	 * @param keyId    App Attest key identifier (iOS), else {@code null}
	 * @param nonce    the server nonce the client bound into the verdict
	 */
	record Evidence(String provider, String token, String keyId, String nonce) {

		/** Never prints the token. */
		@Override
		public String toString() {
			return "Evidence[provider=" + provider + "]";
		}
	}

	/**
	 * {@code VALID}: the verdict is genuine and covers a fresh nonce. {@code FAILED}: anything else (unknown, expired or
	 * replayed nonce, wrong provider for the platform, rejected verdict). {@code UNSUPPORTED}: no verifier is available
	 * for the platform (not configured, or it failed to answer); the device stays unattested.
	 */
	enum Status {
		VALID, FAILED, UNSUPPORTED
	}

	/**
	 * @param provider   the provider that belongs to the platform ({@code null} for platforms without attestation)
	 * @param validUntil how long the verdict may be relied on ({@code null} unless VALID)
	 */
	record Result(Status status, String provider, Instant checkedAt, Instant validUntil) {
	}

	/**
	 * @param userId   the caller, or {@code null} for an anonymous caller (guest creation); a nonce issued to a user can
	 *                 only be used by that user
	 * @param platform {@code ANDROID}, {@code IOS} or {@code WEB}
	 */
	Result check(UUID userId, String platform, Evidence evidence);
}
