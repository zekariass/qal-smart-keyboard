package com.qalsmartkeyboard.identity.email;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.merge.GuestMergeService;
import com.qalsmartkeyboard.identity.session.SessionService;
import com.qalsmartkeyboard.notifications.EmailCodeSender;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Wires e-mail sign-in (ADR 0033) and checks the configuration at startup:
 *
 * <ul>
 * <li><b>Fails</b> when e-mail sign-in is enabled with verification required but no e-mail provider can deliver
 * ({@code qal.notifications.email.provider=none}): nobody could ever finish a sign-up.</li>
 * <li><b>WARNs</b> when verification is off under a production-like profile (allowed: the owner wants it configurable,
 * but accounts then hold addresses nobody proved), and when no provider is configured (password reset and resend
 * answer 503 {@code EMAIL_NOT_CONFIGURED}).</li>
 * <li>WARNs when the Argon2id parameters are below the OWASP baseline.</li>
 * </ul>
 * The keys come from the identity server secret {@code qal.identity.otp.secret-key} (labels distinct from phone OTP's),
 * or a random per-JVM secret when {@code qal.identity.otp.allow-ephemeral-secret-key} is on (refused under
 * prod/production/staging, as for OTP).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailAuthProperties.class)
class EmailAuthConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";
	private static final Logger log = LoggerFactory.getLogger(EmailAuthConfiguration.class);

	private final SecureRandom random = new SecureRandom();

	@Bean
	EmailAuthService emailAuthService(JdbcClient jdbc, EmailAuthProperties properties, Environment environment,
			UserAccountStore accounts, SessionService sessions, GuestMergeService merges, EmailCodeSender sender,
			RateLimiter limiter, PlatformTransactionManager transactionManager, ObjectProvider<Clock> clock,
			ObjectProvider<MeterRegistry> meters, PasswordHasher hasher) {
		checkStartup(properties, sender.available(), environment);
		// password-reset e-mails leave the request thread (EmailAuthService#forgot); one virtual thread per e-mail
		Executor asyncSender = command -> Thread.ofVirtual().name("email-code").start(command);
		return new EmailAuthService(new EmailStore(jdbc), secrets(environment, random), hasher, PasswordPolicy.bundled(),
				accounts, sessions, sessions, merges, sender, limiter, properties,
				new TransactionTemplate(transactionManager), clock.getIfAvailable(Clock::systemUTC), random,
				meters.getIfAvailable(), asyncSender);
	}

	@Bean
	PasswordHasher emailPasswordHasher(EmailAuthProperties properties) {
		EmailAuthProperties.Argon2 argon2 = properties.argon2();
		return new PasswordHasher(argon2.memoryKib(), argon2.iterations(), argon2.parallelism(),
				argon2.effectiveMaxConcurrent(), argon2.maxWait());
	}

	static void checkStartup(EmailAuthProperties properties, boolean deliveryAvailable, Environment environment) {
		if (!properties.enabled()) {
			log.info("E-mail sign-in is disabled (qal.identity.email.enabled=false): its endpoints answer 503");
			return;
		}
		boolean productionLike = environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES));
		if (properties.verificationRequired() && !deliveryAvailable) {
			throw new IllegalStateException("qal.identity.email.verification-required=true needs an e-mail provider, "
					+ "but qal.notifications.email.provider is 'none' (env QAL_EMAIL_PROVIDER). Configure a provider, or "
					+ "set QAL_EMAIL_VERIFICATION_REQUIRED=false to allow unverified e-mail sign-up, or "
					+ "QAL_EMAIL_ENABLED=false to turn e-mail sign-in off (ADR 0033)");
		}
		if (!properties.verificationRequired()) {
			if (productionLike) {
				log.warn("!!! E-MAIL VERIFICATION IS OFF under a production profile (qal.identity.email."
						+ "verification-required=false): anyone can create an account with any e-mail address without "
						+ "proving they own it. Turn it on once an e-mail provider is configured (ADR 0033). !!!");
			}
			else {
				log.info("E-mail verification is off (qal.identity.email.verification-required=false): sign-up signs "
						+ "in at once with an unverified address");
			}
		}
		if (!deliveryAvailable) {
			log.warn("No e-mail provider is configured (qal.notifications.email.provider=none): password reset and code "
					+ "resend answer 503 EMAIL_NOT_CONFIGURED");
		}
		if (properties.argon2().belowOwaspBaseline()) {
			log.warn("qal.identity.email.argon2 parameters are below the OWASP baseline (m=19456 KiB, t=2, p=1)");
		}
	}

	static EmailSecrets secrets(Environment environment, SecureRandom random) {
		String encoded = environment.getProperty("qal.identity.otp.secret-key");
		if (encoded != null && !encoded.isBlank()) {
			byte[] secret;
			try {
				secret = Base64.getDecoder().decode(encoded.trim());
			}
			catch (IllegalArgumentException e) {
				throw new IllegalStateException("qal.identity.otp.secret-key is not valid base64");
			}
			try {
				if (secret.length < EmailSecrets.MIN_SECRET_BYTES) {
					throw new IllegalStateException("qal.identity.otp.secret-key must decode to at least 32 bytes");
				}
				return new EmailSecrets(secret, random);
			}
			finally {
				Arrays.fill(secret, (byte) 0);
			}
		}
		if (environment.getProperty("qal.identity.otp.allow-ephemeral-secret-key", Boolean.class, false)) {
			if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
				throw new IllegalStateException("qal.identity.otp.allow-ephemeral-secret-key is refused under the "
						+ "prod/production/staging profiles; set qal.identity.otp.secret-key");
			}
			byte[] secret = new byte[EmailSecrets.MIN_SECRET_BYTES];
			random.nextBytes(secret);
			log.warn("E-mail codes use an EPHEMERAL secret key: codes issued before a restart or by another instance "
					+ "will not verify. Never use this outside development and tests.");
			try {
				return new EmailSecrets(secret, random);
			}
			finally {
				Arrays.fill(secret, (byte) 0);
			}
		}
		throw new IllegalStateException("qal.identity.otp.secret-key is not set (env QAL_OTP_SECRET_KEY); e-mail codes "
				+ "derive their keys from it");
	}
}
