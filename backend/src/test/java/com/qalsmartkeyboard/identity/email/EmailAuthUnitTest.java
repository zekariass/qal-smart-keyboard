package com.qalsmartkeyboard.identity.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.qalsmartkeyboard.identity.email.EmailAddresses.Address;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/** ADR 0033: address rules, password policy, Argon2id hasher, keyed hashes and the startup checks. */
class EmailAuthUnitTest {

	// ------------------------------------------------------------------ addresses

	@Test
	void addressesAreTrimmedAndNormalisedCaseInsensitively() {
		Address a = EmailAddresses.parse("  Abebe.Kebede+qal@Example.COM ");
		assertThat(a.address()).isEqualTo("Abebe.Kebede+qal@example.com");
		assertThat(a.normalized()).isEqualTo("abebe.kebede+qal@example.com");
		assertThat(a.toString()).doesNotContain("abebe");
	}

	@Test
	void internationalisedDomainsBecomePunycode() {
		Address a = EmailAddresses.parse("user@bücher.example");
		assertThat(a.address()).isEqualTo("user@xn--bcher-kva.example");
		assertThat(a.normalized()).isEqualTo("user@xn--bcher-kva.example");
	}

	@Test
	void malformedAddressesAreRefusedWithoutEchoingThem() {
		for (String bad : new String[] { "", "plain", "@example.com", "user@", "a@@example.com", "a@b@example.com",
				".a@example.com", "a.@example.com", "a..b@example.com", "a b@example.com", "user@localhost",
				"user@example.c0m", "user@-example.com", "ü@example.com", "\"quoted\"@example.com",
				"x".repeat(65) + "@example.com", "user@" + "a".repeat(64) + ".com" }) {
			assertThatThrownBy(() -> EmailAddresses.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
					.message().doesNotContain("example");
		}
		assertThatThrownBy(() -> EmailAddresses.parse(null)).isInstanceOf(IllegalArgumentException.class);
	}

	// ------------------------------------------------------------------ password policy

	private final PasswordPolicy policy = PasswordPolicy.bundled();
	private final Address email = EmailAddresses.parse("Selam.Tesfaye@example.com");

	@Test
	void lengthIsCountedInCodePointsAfterNfkc() {
		assertThat(policy.check("short1!", email)).get().extracting(PasswordPolicy.Violation::code)
				.isEqualTo(PasswordPolicy.TOO_SHORT);
		// ten Ge'ez characters (each one code point, some outside the BMP would also count once)
		assertThat(policy.check("ሰላምለዓለምሁሉምሰው", email)).isEmpty();
		assertThat(policy.check("a".repeat(60) + "b".repeat(69), email)).get()
				.extracting(PasswordPolicy.Violation::code).isEqualTo(PasswordPolicy.TOO_LONG);
		// 128 supplementary characters are 256 UTF-16 units but 128 code points: allowed
		assertThat(policy.check("😀😁😂😃".repeat(32), email)).isEmpty();
	}

	@Test
	void commonPasswordsAndVariantsAreRefused() {
		for (String common : new String[] { "password123", "Password2024!", "1234567890", "qwertyuiop", "QWERTYUIOP12",
				"ethiopia1234", "!!Ethiopia2026", "aaaaaaaaaaaa", "abababababab", "abcdefghijkl", "9876543210",
				"1q2w3e4r5t6y", "iloveyou12345" }) {
			assertThat(policy.check(common, email)).as(common).get().extracting(PasswordPolicy.Violation::code)
					.isEqualTo(PasswordPolicy.TOO_COMMON);
		}
	}

	@Test
	void theAddressItselfIsRefused() {
		assertThat(policy.check("selam.tesfaye@example.com", email)).get().extracting(PasswordPolicy.Violation::code)
				.isEqualTo(PasswordPolicy.MATCHES_EMAIL);
		assertThat(policy.check("Selam.Tesfaye", email)).get().extracting(PasswordPolicy.Violation::code)
				.isEqualTo(PasswordPolicy.MATCHES_EMAIL);
		assertThat(policy.check("selam.tesfaye2024", email)).get().extracting(PasswordPolicy.Violation::code)
				.isEqualTo(PasswordPolicy.MATCHES_EMAIL);
	}

	@Test
	void ordinaryPassphrasesPassWithoutCompositionRules() {
		for (String ok : new String[] { "correct horse battery staple", "bunnaandinjera", "ቡና በጣም ጣፋጭ ነው",
				"Tr0ub4dor&3xyz" }) {
			assertThat(policy.check(ok, email)).as(ok).isEmpty();
		}
	}

	// ------------------------------------------------------------------ hasher

	private final PasswordHasher hasher = new PasswordHasher(19456, 2, 1, 2, Duration.ofSeconds(5));

	@Test
	void argon2idHashesVerifyAndCarryTheirParameters() {
		String hash = hasher.hash("correct horse battery staple");
		assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain("correct");
		assertThat(hasher.matches("correct horse battery staple", hash)).isTrue();
		assertThat(hasher.matches("correct horse battery stapl", hash)).isFalse();
		assertThat(hasher.hash("correct horse battery staple")).isNotEqualTo(hash);
		assertThat(hasher.needsRehash(hash)).isFalse();
	}

	@Test
	void weakerStoredParametersAreRehashedAndForeignFormatsNeverMatch() {
		PasswordHasher weak = new PasswordHasher(4096, 1, 1, 2, Duration.ofSeconds(5));
		String old = weak.hash("correct horse battery staple");
		assertThat(hasher.matches("correct horse battery staple", old)).isTrue();
		assertThat(hasher.needsRehash(old)).isTrue();
		// a missing hash (unknown address) still runs a verification and never matches
		assertThat(hasher.matches("anything at all", null)).isFalse();
		assertThat(hasher.matches("anything at all", "$2a$10$abcdefghijklmnopqrstuv")).isFalse();
	}

	@Test
	void busyHasherAnswersWithARetryAfterInsteadOfQueueingForever() throws Exception {
		PasswordHasher one = new PasswordHasher(4096, 1, 1, 1, Duration.ofMillis(50));
		java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		java.lang.reflect.Field slots = PasswordHasher.class.getDeclaredField("slots");
		slots.setAccessible(true);
		java.util.concurrent.Semaphore semaphore = (java.util.concurrent.Semaphore) slots.get(one);
		Thread t = Thread.ofVirtual().start(() -> {
			semaphore.acquireUninterruptibly();
			holding.countDown();
			try {
				release.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			semaphore.release();
		});
		holding.await();
		assertThatThrownBy(() -> one.hash("correct horse battery staple")).isInstanceOf(ApiException.class)
				.hasMessageContaining("RATE_LIMITED");
		release.countDown();
		t.join();
		assertThat(one.hash("correct horse battery staple")).startsWith("$argon2id$");
	}

	// ------------------------------------------------------------------ secrets

	@Test
	void codeHashesAreKeyedAndBoundToChallengeAndPurpose() {
		EmailSecrets secrets = new EmailSecrets(new byte[32], new SecureRandom());
		UUID id = UUID.randomUUID();
		String hash = secrets.codeHash(id, "REGISTRATION", "123456");
		assertThat(hash).startsWith("v1:").doesNotContain("123456");
		assertThat(secrets.codeMatches(id, "REGISTRATION", "123456", hash)).isTrue();
		assertThat(secrets.codeMatches(id, "PASSWORD_RESET", "123456", hash)).isFalse();
		assertThat(secrets.codeMatches(UUID.randomUUID(), "REGISTRATION", "123456", hash)).isFalse();
		assertThat(secrets.codeMatches(id, "REGISTRATION", "123457", hash)).isFalse();
		assertThat(secrets.codeMatches(id, "REGISTRATION", "123456", "v2:00")).isFalse();
		EmailSecrets other = new EmailSecrets(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
				19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32 }, new SecureRandom());
		assertThat(other.addressHash("a@example.com")).isNotEqualTo(secrets.addressHash("a@example.com"))
				.matches("^[0-9a-f]{64}$");
	}

	@Test
	void sealedPayloadOpensOnlyForItsChallenge() {
		EmailSecrets secrets = new EmailSecrets(new byte[32], new SecureRandom());
		UUID id = UUID.randomUUID();
		byte[] sealed = secrets.seal(id, new EmailSecrets.Payload("Abebe@example.com", "Abebe K"));
		assertThat(new String(sealed, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Abebe");
		assertThat(secrets.open(id, sealed)).isEqualTo(new EmailSecrets.Payload("Abebe@example.com", "Abebe K"));
		assertThat(secrets.open(id, secrets.seal(id, new EmailSecrets.Payload("a@example.com", null))).displayName())
				.isNull();
		assertThatThrownBy(() -> secrets.open(UUID.randomUUID(), sealed)).isInstanceOf(IllegalStateException.class);
	}

	// ------------------------------------------------------------------ startup checks

	private static EmailAuthProperties props(boolean enabled, boolean verificationRequired) {
		return new EmailAuthProperties(enabled, verificationRequired, Duration.ofMinutes(15), Duration.ofSeconds(60), 5, 5,
				Duration.ofHours(24), new EmailAuthProperties.Lockout(10, Duration.ofMinutes(15)),
				new EmailAuthProperties.Argon2(19456, 2, 1, 0, Duration.ofSeconds(5)),
				new EmailAuthProperties.Limits(null, null, null, null, null, null, null, null, null));
	}

	@Test
	void requiredVerificationWithoutAnEmailProviderStopsTheApplication() {
		assertThatThrownBy(() -> EmailAuthConfiguration.checkStartup(props(true, true), false, new MockEnvironment()))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("QAL_EMAIL_VERIFICATION_REQUIRED=false");
		EmailAuthConfiguration.checkStartup(props(true, true), true, new MockEnvironment());
		EmailAuthConfiguration.checkStartup(props(false, true), false, new MockEnvironment());
	}

	@Test
	void verificationOffUnderAProductionProfileIsAllowedButLoud() {
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");
		try (CapturedLogs logs = new CapturedLogs()) {
			EmailAuthConfiguration.checkStartup(props(true, false), false, prod);
			assertThat(logs.texts()).anyMatch(t -> t.contains("E-MAIL VERIFICATION IS OFF"))
					.anyMatch(t -> t.contains("EMAIL_NOT_CONFIGURED"));
		}
	}

	@Test
	void ephemeralSecretIsRefusedUnderProductionProfiles() {
		MockEnvironment prod = new MockEnvironment().withProperty("qal.identity.otp.allow-ephemeral-secret-key", "true");
		prod.setActiveProfiles("staging");
		assertThatThrownBy(() -> EmailAuthConfiguration.secrets(prod, new SecureRandom()))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> EmailAuthConfiguration.secrets(new MockEnvironment(), new SecureRandom()))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("QAL_OTP_SECRET_KEY");
		assertThat(EmailAuthConfiguration.secrets(new MockEnvironment().withProperty("qal.identity.otp.secret-key",
				java.util.Base64.getEncoder().encodeToString(new byte[32])), new SecureRandom())).isNotNull();
	}

	@Test
	void displayNamesAreCleanedAndCapped() {
		assertThat(EmailAuthService.displayName("  Abebe\u0000 K \n")).isEqualTo("Abebe K");
		assertThat(EmailAuthService.displayName("   ")).isNull();
		assertThat(EmailAuthService.displayName("x".repeat(200))).hasSize(120);
	}
}
