package com.qalsmartkeyboard.identity.attestation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;

import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;

/**
 * The token format of the development/test verifiers (never of a real provider):
 *
 * <pre>
 * mock-play-integrity.v1.&lt;nonce&gt;.valid     (provider PLAY_INTEGRITY)
 * mock-app-attest.v1.&lt;nonce&gt;.valid          (provider APP_ATTEST; evidence.keyId must be present)
 * </pre>
 *
 * Exactly four dot-separated parts (base64url nonces contain no dot). Anything else is rejected, as is a verdict word
 * other than {@code valid} (for example {@code invalid}), a nonce that differs from the server's, or an evidence nonce
 * that differs from the token's.
 */
public final class MockAttestationTokens {

	static final String PLAY_INTEGRITY_PREFIX = "mock-play-integrity.v1";
	static final String APP_ATTEST_PREFIX = "mock-app-attest.v1";
	static final Duration VERDICT_VALIDITY = Duration.ofHours(1);

	private MockAttestationTokens() {
	}

	public static String playIntegrity(String nonce) {
		return PLAY_INTEGRITY_PREFIX + "." + nonce + ".valid";
	}

	public static String appAttest(String nonce) {
		return APP_ATTEST_PREFIX + "." + nonce + ".valid";
	}

	/** A well-formed token whose verdict word is {@code invalid}. */
	public static String rejectedPlayIntegrity(String nonce) {
		return PLAY_INTEGRITY_PREFIX + "." + nonce + ".invalid";
	}

	static AttestationVerdict verify(String prefix, Evidence evidence, String expectedNonce, Clock clock) {
		if (evidence == null || evidence.token() == null || expectedNonce == null) {
			return AttestationVerdict.REJECTED;
		}
		String[] parts = evidence.token().split("\\.", -1);
		if (parts.length != 4 || !(parts[0] + "." + parts[1]).equals(prefix) || !"valid".equals(parts[3])) {
			return AttestationVerdict.REJECTED;
		}
		// the verdict must cover this very nonce, both inside the token and as claimed next to it
		if (!sameBytes(parts[2], expectedNonce) || !sameBytes(evidence.nonce(), expectedNonce)) {
			return AttestationVerdict.REJECTED;
		}
		return AttestationVerdict.valid(clock.instant().plus(VERDICT_VALIDITY));
	}

	private static boolean sameBytes(String a, String b) {
		return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
				b.getBytes(StandardCharsets.UTF_8));
	}
}
