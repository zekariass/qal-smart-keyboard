package com.qalsmartkeyboard.identity.attestation;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Wires the verifier adapters and fails closed (ADR 0008).
 *
 * <ul>
 * <li>No real Play Integrity or App Attest adapter exists yet. A real adapter is any bean of type
 * {@link PlayIntegrityVerifier} or {@link AppAttestVerifier}; the strategy uses whatever is present.</li>
 * <li>The mock adapters are registered only with {@code qal.identity.attestation.mock.enabled=true}, and that switch is
 * refused at startup under the {@code prod}, {@code production} and {@code staging} profiles.</li>
 * <li>With no adapter for a platform every verdict for it is {@code UNAVAILABLE}: nonces can still be issued, but no
 * guest account is created and no device becomes attested.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttestationProperties.class)
class AttestationVerifierConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";
	private static final Logger log = LoggerFactory.getLogger(AttestationVerifierConfiguration.class);

	@Bean
	@ConditionalOnProperty(name = "qal.identity.attestation.mock.enabled", havingValue = "true")
	PlayIntegrityVerifier mockPlayIntegrityVerifier(ObjectProvider<Clock> clock) {
		return new MockPlayIntegrityVerifier(clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	@ConditionalOnProperty(name = "qal.identity.attestation.mock.enabled", havingValue = "true")
	AppAttestVerifier mockAppAttestVerifier(ObjectProvider<Clock> clock) {
		return new MockAppAttestVerifier(clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	AttestationVerifier attestationVerifier(ObjectProvider<PlayIntegrityVerifier> playIntegrity,
			ObjectProvider<AppAttestVerifier> appAttest, AttestationProperties properties, Environment environment) {
		if (properties.mock().enabled()) {
			if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
				throw new IllegalStateException("qal.identity.attestation.mock.enabled is refused under the "
						+ "prod/production/staging profiles: the mock verifiers accept a published test token");
			}
			log.warn("MOCK attestation verifiers are enabled: guests and attested devices are accepted on a test token "
					+ "format. Never use this outside development and tests.");
		}
		PlayIntegrityVerifier pi = playIntegrity.getIfAvailable();
		AppAttestVerifier aa = appAttest.getIfAvailable();
		if (pi == null || aa == null) {
			log.warn("No {} attestation verifier is configured: guest sessions are refused and devices stay "
					+ "unattested for that platform", pi == null && aa == null ? "Play Integrity or App Attest"
							: pi == null ? "Play Integrity" : "App Attest");
		}
		return new PlatformAttestationVerifier(pi, aa);
	}
}
