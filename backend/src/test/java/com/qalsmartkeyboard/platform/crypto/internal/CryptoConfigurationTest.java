package com.qalsmartkeyboard.platform.crypto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.platform.crypto.kms.CloudKmsClient;
import com.qalsmartkeyboard.platform.crypto.kms.KeyManagementService;

/** Startup fails closed on every unsafe or incomplete KMS configuration. */
class CryptoConfigurationTest {

	private static final String KEK = Base64.getEncoder().encodeToString(randomBytes(32));

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(CryptoConfiguration.class)
			.withBean(JdbcClient.class, () -> mock(JdbcClient.class))
			.withBean(PlatformTransactionManager.class, NoOpTransactionManager::new);

	private static byte[] randomBytes(int n) {
		byte[] bytes = new byte[n];
		new SecureRandom().nextBytes(bytes);
		return bytes;
	}

	private static void assertStartupFails(AssertableApplicationContext context, String messagePart) {
		assertThat(context).hasFailed();
		assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(messagePart);
		assertThat(context.getStartupFailure()).rootCause().message().doesNotContain(KEK);
	}

	@Test
	void noProviderFailsStartup() {
		runner.run(context -> assertStartupFails(context, "qal.crypto.kms.provider is not set"));
	}

	@Test
	void unknownProviderFailsStartup() {
		runner.withPropertyValues("qal.crypto.kms.provider=vault")
				.run(context -> assertStartupFails(context, "Unknown qal.crypto.kms.provider"));
	}

	@Test
	void localKmsWithAConfiguredKek() {
		runner.withPropertyValues("qal.crypto.kms.provider=local", "qal.crypto.local-kms.kek=" + KEK)
				.run(context -> {
					assertThat(context).hasNotFailed().hasSingleBean(UserDataEncryption.class);
					assertThat(context.getBean(KeyManagementService.class).activeKeyId()).isEqualTo("local:dev-1");
				});
	}

	@ParameterizedTest
	@ValueSource(strings = { "prod", "production", "staging", "worker,prod" })
	void localKmsIsRefusedInProductionLikeProfiles(String profiles) {
		runner.withPropertyValues("spring.profiles.active=" + profiles, "qal.crypto.kms.provider=local",
				"qal.crypto.local-kms.kek=" + KEK, "qal.crypto.local-kms.allow-ephemeral-kek=true")
				.run(context -> assertStartupFails(context, "refused under the prod/production/staging profiles"));
	}

	@Test
	void localKmsWithoutKekFailsUnlessEphemeralIsExplicitlyAllowed() {
		runner.withPropertyValues("qal.crypto.kms.provider=local")
				.run(context -> assertStartupFails(context, "needs qal.crypto.local-kms.kek"));

		runner.withPropertyValues("qal.crypto.kms.provider=local", "qal.crypto.local-kms.allow-ephemeral-kek=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(KeyManagementService.class).activeKeyId())
							.matches("local:ephemeral-[0-9a-f]{8}");
				});
	}

	@Test
	void localKmsRejectsBadKeks() {
		String shortKek = Base64.getEncoder().encodeToString(randomBytes(16));
		runner.withPropertyValues("qal.crypto.kms.provider=local", "qal.crypto.local-kms.kek=" + shortKek)
				.run(context -> assertStartupFails(context, "exactly 32 bytes"));
		runner.withPropertyValues("qal.crypto.kms.provider=local", "qal.crypto.local-kms.kek=not*base64")
				.run(context -> assertStartupFails(context, "not valid base64"));
	}

	@Test
	void cloudKmsNeedsAClientAndAKeyName() {
		runner.withPropertyValues("qal.crypto.kms.provider=cloud", "qal.crypto.cloud-kms.key-name=k")
				.run(context -> assertStartupFails(context, "needs a CloudKmsClient bean"));

		ApplicationContextRunner withClient = runner.withBean(CloudKmsClient.class, () -> new CloudKmsClient() {
			@Override
			public byte[] encrypt(String keyName, byte[] plaintext, Map<String, String> context) {
				return plaintext;
			}

			@Override
			public byte[] decrypt(String keyName, byte[] ciphertext, Map<String, String> context) {
				return ciphertext;
			}
		});
		withClient.withPropertyValues("qal.crypto.kms.provider=cloud")
				.run(context -> assertStartupFails(context, "needs qal.crypto.cloud-kms.key-name"));
		withClient.withPropertyValues("qal.crypto.kms.provider=cloud",
				"qal.crypto.cloud-kms.key-name=projects/p/locations/l/keyRings/r/cryptoKeys/user-dek")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(KeyManagementService.class).activeKeyId()).endsWith("cryptoKeys/user-dek");
				});
	}

	@Test
	void propertiesNeverPrintTheKek() {
		assertThat(new CryptoProperties.LocalKms(KEK, "dev-1", false).toString()).doesNotContain(KEK)
				.contains("<redacted>");
	}
}
