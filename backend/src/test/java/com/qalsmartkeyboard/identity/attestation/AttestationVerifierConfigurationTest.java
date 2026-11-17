package com.qalsmartkeyboard.identity.attestation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.qalsmartkeyboard.identity.AttestationChecker.Evidence;
import com.qalsmartkeyboard.identity.attestation.AttestationVerdict.Outcome;

/**
 * B2.3 fail-closed wiring: no real Play Integrity / App Attest adapter exists, so without the mock switch every verdict
 * is UNAVAILABLE (guests refused), and the mock switch is refused under production-like profiles.
 */
class AttestationVerifierConfigurationTest {

	static final String NONCE = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCde";

	final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(AttestationVerifierConfiguration.class);

	static Evidence android() {
		return new Evidence("PLAY_INTEGRITY", MockAttestationTokens.playIntegrity(NONCE), null, NONCE);
	}

	@Test
	void withoutAnyConfigurationNoVerifierExistsAndEveryVerdictIsUnavailable() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(PlayIntegrityVerifier.class)
					.doesNotHaveBean(AppAttestVerifier.class);
			AttestationVerifier verifier = context.getBean(AttestationVerifier.class);
			assertThat(verifier.verify("ANDROID", android(), NONCE).outcome()).isEqualTo(Outcome.UNAVAILABLE);
			assertThat(verifier.verify("IOS", new Evidence("APP_ATTEST", MockAttestationTokens.appAttest(NONCE), "k",
					NONCE), NONCE).outcome()).isEqualTo(Outcome.UNAVAILABLE);
		});
	}

	@Test
	void productionLikeProfilesStartWithoutVerifiersAndRefuseGuestsThatWay() {
		for (String profile : new String[] { "prod", "production", "staging" }) {
			runner.withInitializer(c -> c.getEnvironment().setActiveProfiles(profile)).run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(AttestationVerifier.class).verify("ANDROID", android(), NONCE).outcome())
						.as(profile).isEqualTo(Outcome.UNAVAILABLE);
			});
		}
	}

	@Test
	void theMockSwitchIsRefusedUnderProductionLikeProfiles() {
		for (String profile : new String[] { "prod", "production", "staging" }) {
			runner.withPropertyValues("qal.identity.attestation.mock.enabled=true")
					.withInitializer(c -> c.getEnvironment().setActiveProfiles(profile)).run(context -> {
						assertThat(context).as(profile).hasFailed();
						assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
								.rootCause().hasMessageContaining("refused under the prod/production/staging");
					});
		}
	}

	@Test
	void theMockSwitchRegistersTheMockAdaptersInDevelopment() {
		runner.withPropertyValues("qal.identity.attestation.mock.enabled=true").run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(PlayIntegrityVerifier.class)
					.hasSingleBean(AppAttestVerifier.class);
			assertThat(context.getBean(AttestationVerifier.class).verify("ANDROID", android(), NONCE).outcome())
					.isEqualTo(Outcome.VALID);
		});
	}

	@Test
	void aRealAdapterBeanIsUsedAndTheOtherPlatformStaysUnavailable() {
		runner.withBean(PlayIntegrityVerifier.class, () -> (evidence, nonce) -> AttestationVerdict.REJECTED).run(context -> {
			AttestationVerifier verifier = context.getBean(AttestationVerifier.class);
			assertThat(verifier.verify("ANDROID", android(), NONCE).outcome()).isEqualTo(Outcome.REJECTED);
			assertThat(verifier.verify("IOS", android(), NONCE).outcome()).isEqualTo(Outcome.UNAVAILABLE);
		});
	}

	@Test
	void theNonceLifetimeCannotExceedFiveMinutes() {
		runner.withPropertyValues("qal.identity.attestation.nonce-ttl=6m").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("qal.identity.attestation.nonce-ttl=0s").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("qal.identity.attestation.nonce-ttl=2m").run(context -> assertThat(context).hasNotFailed());
	}
}
