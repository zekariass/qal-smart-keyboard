package com.qalsmartkeyboard.languages;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Wires the pack-URL signer. Fails closed: under the prod/production/staging profiles the application does not start
 * without object-store credentials (a signer bean from elsewhere, such as a test fake, replaces this one).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LanguagesProperties.class)
class LanguagesConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";

	@Bean
	ArtifactUrlSigner artifactUrlSigner(LanguagesProperties properties, Environment environment,
			ObjectProvider<Clock> clock) {
		LanguagesProperties.Artifacts artifacts = properties.artifacts();
		if (!artifacts.configured()) {
			if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
				throw new IllegalStateException("qal.languages.artifacts.endpoint, access-key and secret-key are required under"
						+ " the prod/production/staging profiles (env QAL_PACKS_S3_ENDPOINT, QAL_PACKS_S3_ACCESS_KEY,"
						+ " QAL_PACKS_S3_SECRET_KEY)");
			}
			return new UnconfiguredArtifactUrlSigner();
		}
		return new S3PresignedArtifactUrlSigner(artifacts, clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	LanguagesService languagesService(LanguagesRepository repository, ArtifactUrlSigner signer,
			LanguagesProperties properties) {
		return new LanguagesService(repository, signer, properties.internalChannelEnabled());
	}
}
