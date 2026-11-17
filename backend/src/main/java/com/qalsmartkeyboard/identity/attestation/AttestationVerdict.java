package com.qalsmartkeyboard.identity.attestation;

import java.time.Instant;

/**
 * What a verifier says about one piece of evidence.
 *
 * <ul>
 * <li>{@code VALID}: genuine, and the verdict covers the expected nonce ({@code validUntil} says how long it holds).</li>
 * <li>{@code REJECTED}: not genuine, malformed, or it covers another nonce.</li>
 * <li>{@code UNAVAILABLE}: no adapter for the platform, or the verification service could not be reached. Callers treat
 * it like a rejection for access decisions (fail closed) but keep it apart for reporting.</li>
 * </ul>
 */
public record AttestationVerdict(Outcome outcome, Instant validUntil) {

	public enum Outcome {
		VALID, REJECTED, UNAVAILABLE
	}

	public static final AttestationVerdict REJECTED = new AttestationVerdict(Outcome.REJECTED, null);
	public static final AttestationVerdict UNAVAILABLE = new AttestationVerdict(Outcome.UNAVAILABLE, null);

	public static AttestationVerdict valid(Instant validUntil) {
		return new AttestationVerdict(Outcome.VALID, validUntil);
	}
}
