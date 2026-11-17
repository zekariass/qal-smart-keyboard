package com.qalsmartkeyboard.identity.attestation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;
import com.qalsmartkeyboard.identity.attestation.AttestationVerdict.Outcome;

/** B2.3: the mock adapters accept exactly the published test token format and reject everything else. */
class MockVerifiersTest {

	static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
	static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	static final String NONCE = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCde";

	final PlayIntegrityVerifier playIntegrity = new MockPlayIntegrityVerifier(CLOCK);
	final AppAttestVerifier appAttest = new MockAppAttestVerifier(CLOCK);

	static Evidence pi(String token, String nonce) {
		return new Evidence("PLAY_INTEGRITY", token, null, nonce);
	}

	static Evidence aa(String token, String keyId, String nonce) {
		return new Evidence("APP_ATTEST", token, keyId, nonce);
	}

	@Test
	void playIntegrityAcceptsTheTestTokenForTheServerNonce() {
		AttestationVerdict verdict = playIntegrity.verify(pi(MockAttestationTokens.playIntegrity(NONCE), NONCE), NONCE);
		assertThat(verdict.outcome()).isEqualTo(Outcome.VALID);
		assertThat(verdict.validUntil()).isEqualTo(NOW.plus(MockAttestationTokens.VERDICT_VALIDITY));
	}

	@Test
	void appAttestAcceptsTheTestTokenOnlyWithAKeyId() {
		assertThat(appAttest.verify(aa(MockAttestationTokens.appAttest(NONCE), "key-1", NONCE), NONCE).outcome())
				.isEqualTo(Outcome.VALID);
		assertThat(appAttest.verify(aa(MockAttestationTokens.appAttest(NONCE), null, NONCE), NONCE).outcome())
				.isEqualTo(Outcome.REJECTED);
		assertThat(appAttest.verify(aa(MockAttestationTokens.appAttest(NONCE), " ", NONCE), NONCE).outcome())
				.isEqualTo(Outcome.REJECTED);
	}

	@Test
	void aVerdictThatCoversAnotherNonceIsRejected() {
		String other = "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ";
		// token for another nonce, evidence naming the right one
		assertThat(playIntegrity.verify(pi(MockAttestationTokens.playIntegrity(other), NONCE), NONCE).outcome())
				.isEqualTo(Outcome.REJECTED);
		// token for the right nonce, evidence naming another one
		assertThat(playIntegrity.verify(pi(MockAttestationTokens.playIntegrity(NONCE), other), NONCE).outcome())
				.isEqualTo(Outcome.REJECTED);
		assertThat(playIntegrity.verify(pi(MockAttestationTokens.playIntegrity(NONCE), null), NONCE).outcome())
				.isEqualTo(Outcome.REJECTED);
	}

	@Test
	void everythingOutsideTheFormatIsRejected() {
		String[] tokens = { "", " ", "garbage", "valid", MockAttestationTokens.rejectedPlayIntegrity(NONCE),
				"mock-play-integrity.v1." + NONCE + ".VALID", "mock-play-integrity.v1." + NONCE,
				"mock-play-integrity.v1." + NONCE + ".valid.extra", "mock-play-integrity.v2." + NONCE + ".valid",
				"mock-app-attest.v1." + NONCE + ".valid", // the other provider's token
				"eyJhbGciOiJub25lIn0.e30.", "mock-play-integrity.v1..valid" };
		for (String token : tokens) {
			assertThat(playIntegrity.verify(pi(token, NONCE), NONCE).outcome()).as(token).isEqualTo(Outcome.REJECTED);
		}
		assertThat(playIntegrity.verify(pi(null, NONCE), NONCE).outcome()).isEqualTo(Outcome.REJECTED);
		assertThat(playIntegrity.verify(null, NONCE).outcome()).isEqualTo(Outcome.REJECTED);
		assertThat(playIntegrity.verify(pi(MockAttestationTokens.playIntegrity(NONCE), NONCE), null).outcome())
				.isEqualTo(Outcome.REJECTED);
	}

	@Test
	void anAdapterOnlyAcceptsItsOwnProviderAndTokenKind() {
		assertThat(playIntegrity.verify(aa(MockAttestationTokens.playIntegrity(NONCE), "k", NONCE), NONCE).outcome())
				.isEqualTo(Outcome.REJECTED);
		assertThat(appAttest.verify(pi(MockAttestationTokens.appAttest(NONCE), NONCE), NONCE).outcome())
				.isEqualTo(Outcome.REJECTED);
		assertThat(appAttest.verify(aa(MockAttestationTokens.playIntegrity(NONCE), "k", NONCE), NONCE).outcome())
				.isEqualTo(Outcome.REJECTED);
	}

	@Test
	void theStrategyPicksTheAdapterByPlatformAndFailsClosedWithoutOne() {
		AttestationVerifier both = new PlatformAttestationVerifier(playIntegrity, appAttest);
		assertThat(both.verify("ANDROID", pi(MockAttestationTokens.playIntegrity(NONCE), NONCE), NONCE).outcome())
				.isEqualTo(Outcome.VALID);
		assertThat(both.verify("IOS", aa(MockAttestationTokens.appAttest(NONCE), "k", NONCE), NONCE).outcome())
				.isEqualTo(Outcome.VALID);
		// the Android token on the iOS path is rejected by the iOS adapter
		assertThat(both.verify("IOS", pi(MockAttestationTokens.playIntegrity(NONCE), NONCE), NONCE).outcome())
				.isEqualTo(Outcome.REJECTED);
		assertThat(both.verify("WEB", pi(MockAttestationTokens.playIntegrity(NONCE), NONCE), NONCE).outcome())
				.isEqualTo(Outcome.UNAVAILABLE);
		assertThat(both.verify(null, pi(MockAttestationTokens.playIntegrity(NONCE), NONCE), NONCE).outcome())
				.isEqualTo(Outcome.UNAVAILABLE);

		AttestationVerifier none = new PlatformAttestationVerifier(null, null);
		assertThat(none.verify("ANDROID", pi(MockAttestationTokens.playIntegrity(NONCE), NONCE), NONCE).outcome())
				.isEqualTo(Outcome.UNAVAILABLE);
		assertThat(none.verify("IOS", aa(MockAttestationTokens.appAttest(NONCE), "k", NONCE), NONCE).outcome())
				.isEqualTo(Outcome.UNAVAILABLE);
		AttestationVerifier androidOnly = new PlatformAttestationVerifier(playIntegrity, null);
		assertThat(androidOnly.verify("IOS", aa(MockAttestationTokens.appAttest(NONCE), "k", NONCE), NONCE).outcome())
				.isEqualTo(Outcome.UNAVAILABLE);
	}

	@Test
	void anAdapterThatThrowsIsUnavailableNotValid() {
		PlayIntegrityVerifier broken = (evidence, nonce) -> {
			throw new IllegalStateException("google is down: secret-token-fragment");
		};
		AttestationVerdict verdict = new PlatformAttestationVerifier(broken, null).verify("ANDROID",
				pi("t", NONCE), NONCE);
		assertThat(verdict.outcome()).isEqualTo(Outcome.UNAVAILABLE);
	}
}
