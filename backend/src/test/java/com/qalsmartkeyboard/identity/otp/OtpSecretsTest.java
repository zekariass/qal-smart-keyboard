package com.qalsmartkeyboard.identity.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class OtpSecretsTest {

	static final SecureRandom RANDOM = new SecureRandom();
	static final byte[] SECRET = new byte[32];
	static {
		RANDOM.nextBytes(SECRET);
	}

	final OtpSecrets secrets = new OtpSecrets(SECRET, RANDOM);
	final UUID challenge = UUID.randomUUID();

	@Test
	void codeHashIsKeyedBoundToTheChallengeAndNeverContainsTheCode() {
		String hash = secrets.codeHash(challenge, "123456");
		assertThat(hash).startsWith("v1:").hasSize(3 + 64).doesNotContain("123456");
		assertThat(secrets.codeMatches(challenge, "123456", hash)).isTrue();
		assertThat(secrets.codeMatches(challenge, "123457", hash)).isFalse();
		assertThat(secrets.codeMatches(UUID.randomUUID(), "123456", hash)).as("moved to another challenge").isFalse();

		byte[] otherSecret = new byte[32];
		RANDOM.nextBytes(otherSecret);
		assertThat(new OtpSecrets(otherSecret, RANDOM).codeMatches(challenge, "123456", hash)).as("other key").isFalse();
	}

	@Test
	void unknownOrCorruptStoredHashNeverMatches() {
		assertThat(secrets.codeMatches(challenge, "123456", null)).isFalse();
		assertThat(secrets.codeMatches(challenge, "123456", "v2:00")).isFalse();
		assertThat(secrets.codeMatches(challenge, "123456", "v1:zz")).isFalse();
		assertThat(secrets.codeMatches(challenge, "123456", "123456")).isFalse();
	}

	@Test
	void phoneHashIsDeterministicHexAndKeyed() {
		String h = secrets.phoneHash("+251911234567");
		assertThat(h).matches("^[0-9a-f]{64}$").isEqualTo(secrets.phoneHash("+251911234567"));
		assertThat(h).isNotEqualTo(secrets.phoneHash("+251911234568"));
		byte[] otherSecret = new byte[32];
		RANDOM.nextBytes(otherSecret);
		assertThat(new OtpSecrets(otherSecret, RANDOM).phoneHash("+251911234567")).isNotEqualTo(h);
	}

	@Test
	void sealedPhoneOpensOnlyForItsChallenge() {
		byte[] sealed = secrets.sealPhone(challenge, "+251911234567");
		assertThat(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("911234567");
		assertThat(secrets.openPhone(challenge, sealed)).isEqualTo("+251911234567");
		assertThatThrownBy(() -> secrets.openPhone(UUID.randomUUID(), sealed)).isInstanceOf(IllegalStateException.class)
				.hasNoCause();
		sealed[sealed.length - 1] ^= 1;
		assertThatThrownBy(() -> secrets.openPhone(challenge, sealed)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void secretMustBeLongEnough() {
		assertThatThrownBy(() -> new OtpSecrets(new byte[16], RANDOM)).isInstanceOf(IllegalArgumentException.class);
	}

	// ---------------------------------------------------------------- fail-closed configuration

	static OtpProperties props(String key, boolean ephemeral) {
		return new OtpProperties(key, ephemeral, Duration.ofMinutes(5), Duration.ofSeconds(60), 5,
				new OtpProperties.Limits(null, null, null, null, null));
	}

	@Test
	void missingSecretFailsStartup() {
		assertThatThrownBy(() -> OtpConfiguration.secrets(props(null, false), new MockEnvironment(), RANDOM))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("secret-key is not set");
	}

	@Test
	void ephemeralSecretIsRefusedInProductionLikeProfiles() {
		for (String profile : new String[] { "prod", "production", "staging" }) {
			MockEnvironment env = new MockEnvironment();
			env.setActiveProfiles(profile);
			assertThatThrownBy(() -> OtpConfiguration.secrets(props(null, true), env, RANDOM))
					.isInstanceOf(IllegalStateException.class).hasMessageContaining("refused");
		}
		assertThat(OtpConfiguration.secrets(props(null, true), new MockEnvironment(), RANDOM)).isNotNull();
	}

	@Test
	void configuredSecretMustBeBase64OfAtLeast32Bytes() {
		assertThatThrownBy(() -> OtpConfiguration.secrets(props("not base64!", false), new MockEnvironment(), RANDOM))
				.isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not base64!");
		String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
		assertThatThrownBy(() -> OtpConfiguration.secrets(props(shortKey, false), new MockEnvironment(), RANDOM))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");
		assertThat(OtpConfiguration.secrets(props(Base64.getEncoder().encodeToString(SECRET), false), prod, RANDOM))
				.isNotNull();
	}

	@Test
	void maxAttemptsIsCappedAtFive() {
		assertThatThrownBy(() -> new OtpProperties(null, true, Duration.ofMinutes(5), Duration.ofSeconds(60), 6,
				new OtpProperties.Limits(null, null, null, null, null))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void toStringNeverShowsTheSecret() {
		String key = Base64.getEncoder().encodeToString(SECRET);
		assertThat(props(key, false).toString()).doesNotContain(key).contains("<redacted>");
		assertThat(secrets.toString()).doesNotContain(key);
	}
}
