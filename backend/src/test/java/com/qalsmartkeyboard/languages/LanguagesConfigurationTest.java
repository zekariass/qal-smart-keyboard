package com.qalsmartkeyboard.languages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import com.qalsmartkeyboard.languages.LanguagesProperties.Artifacts;

class LanguagesConfigurationTest {

	final LanguagesConfiguration config = new LanguagesConfiguration();
	@SuppressWarnings("unchecked")
	final ObjectProvider<Clock> clock = org.mockito.Mockito.mock(ObjectProvider.class);

	static LanguagesProperties props(String endpoint, String access, String secret) {
		return new LanguagesProperties(false,
				new Artifacts(endpoint, "qal-language-packs", "us-east-1", access, secret, Duration.ofMinutes(15), true));
	}

	@ParameterizedTest
	@ValueSource(strings = { "prod", "production", "staging" })
	void productionLikeProfilesRefuseToStartWithoutCredentials(String profile) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(profile);
		assertThatThrownBy(() -> config.artifactUrlSigner(props("", "", ""), env, clock))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("QAL_PACKS_S3_ENDPOINT");
		assertThatThrownBy(() -> config.artifactUrlSigner(props("http://x:9000", "ak", " "), env, clock))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void developmentWithoutCredentialsGetsTheFailClosedSigner() {
		assertThat(config.artifactUrlSigner(props(null, null, null), new MockEnvironment(), clock))
				.isInstanceOf(UnconfiguredArtifactUrlSigner.class);
	}

	@Test
	void credentialsGiveTheS3Signer() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("prod");
		assertThat(config.artifactUrlSigner(props("https://s3.example.com", "ak", "sk"), env, clock))
				.isInstanceOf(S3PresignedArtifactUrlSigner.class);
	}
}
