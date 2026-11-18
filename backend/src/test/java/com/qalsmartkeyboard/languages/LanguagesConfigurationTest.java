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
import com.qalsmartkeyboard.languages.pack.KmsSigningClient;
import com.qalsmartkeyboard.languages.pack.LocalEd25519PackSigner;
import com.qalsmartkeyboard.languages.pack.PackSigner;
import com.qalsmartkeyboard.languages.pack.UnconfiguredPackSigner;

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

	// ------------------------------------------------------------------ B3.2 pack signing key

	@SuppressWarnings("unchecked")
	final ObjectProvider<KmsSigningClient> noKms = org.mockito.Mockito.mock(ObjectProvider.class);

	static PackPublishingProperties signing(String provider, String keyId, String privateKey, boolean ephemeral) {
		return new PackPublishingProperties(33685504, null,
				new PackPublishingProperties.Signing(provider, keyId, privateKey, ephemeral, null),
				new PackPublishingProperties.Storage(null, null, null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "prod", "production", "staging" })
	void productionLikeProfilesRefuseAnEphemeralPackSigningKey(String profile) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(profile);
		assertThatThrownBy(() -> config.packSigner(signing("local", null, null, true), env, noKms))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("allow-ephemeral");
		// without any key: no start refusal, but every signature fails (fail closed at use)
		assertThat(config.packSigner(signing("local", null, null, false), env, noKms))
				.isInstanceOf(UnconfiguredPackSigner.class);
	}

	@Test
	void aConfiguredSeedGivesTheLocalSignerInAnyProfile() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("prod");
		String hex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
		PackSigner fromHex = config.packSigner(signing("local", "packs-2026-01", hex, false), env, noKms);
		assertThat(fromHex).isInstanceOf(LocalEd25519PackSigner.class);
		assertThat(fromHex.keyId()).isEqualTo("packs-2026-01");
		// test-active-1 of the pack vectors (seed 00..1f)
		assertThat(java.util.HexFormat.of().formatHex(fromHex.publicKey()))
				.isEqualTo("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8");
		String b64 = java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(hex));
		assertThat(config.packSigner(signing("local", "packs-2026-01", b64, false), env, noKms).publicKey())
				.isEqualTo(fromHex.publicKey());
		assertThatThrownBy(() -> config.packSigner(signing("local", null, hex, false), env, noKms))
				.hasMessageContaining("key-id");
		assertThatThrownBy(() -> config.packSigner(signing("local", "k", "AAEC", false), env, noKms))
				.hasMessageContaining("32-byte");
		assertThatThrownBy(() -> config.packSigner(signing("kms", "k", null, false), env, noKms))
				.hasMessageContaining("KmsSigningClient");
		assertThatThrownBy(() -> config.packSigner(signing("hsm", "k", null, false), env, noKms))
				.hasMessageContaining("local or kms");
		assertThat(config.packSigner(signing("local", null, null, true), new MockEnvironment(), noKms).keyId())
				.startsWith("ephemeral-");
	}

	@Test
	void objectStoreNeedsTheWriteCredential() {
		PackPublishingProperties none = signing("local", null, null, false);
		assertThat(config.packObjectStore(props("http://localhost:9000", "ak", "sk"), none, clock))
				.isInstanceOf(UnconfiguredPackObjectStore.class);
		PackPublishingProperties write = new PackPublishingProperties(33685504, null, none.signing(),
				new PackPublishingProperties.Storage(null, "wk", "ws"));
		assertThat(config.packObjectStore(props("http://localhost:9000", "ak", "sk"), write, clock))
				.isInstanceOf(S3PackObjectStore.class);
		assertThat(config.licenseAllowList(none).check("MIT")).isNotNull();
	}
}
