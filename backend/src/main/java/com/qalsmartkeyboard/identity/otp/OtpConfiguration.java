package com.qalsmartkeyboard.identity.otp;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;

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
import com.qalsmartkeyboard.identity.session.SessionIssuer;
import com.qalsmartkeyboard.notifications.OtpSender;
import com.qalsmartkeyboard.platform.ratelimit.RateLimiter;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Wires phone OTP. Fails closed at startup like the local KMS (B1.5): without {@code qal.identity.otp.secret-key} the
 * application does not start, unless the dev/test-only ephemeral switch is on, which is itself refused under the
 * {@code prod}, {@code production} and {@code staging} profiles.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OtpProperties.class)
class OtpConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";
	private static final Logger log = LoggerFactory.getLogger(OtpConfiguration.class);

	private final SecureRandom random = new SecureRandom();

	@Bean
	OtpService otpService(JdbcClient jdbc, OtpProperties properties, Environment environment, UserAccountStore accounts,
			SessionIssuer sessions, OtpSender sender, RateLimiter limiter, PlatformTransactionManager transactionManager,
			ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters, GuestMergeService merges) {
		return new OtpService(new OtpChallengeStore(jdbc), secrets(properties, environment, random), accounts, sessions,
				sender, limiter, properties, new TransactionTemplate(transactionManager),
				clock.getIfAvailable(Clock::systemUTC), random, new OtpMetrics(meters.getIfAvailable()), merges);
	}

	static OtpSecrets secrets(OtpProperties properties, Environment environment, SecureRandom random) {
		String encoded = properties.secretKey();
		if (encoded != null && !encoded.isBlank()) {
			byte[] secret;
			try {
				secret = Base64.getDecoder().decode(encoded.trim());
			}
			catch (IllegalArgumentException e) {
				throw new IllegalStateException("qal.identity.otp.secret-key is not valid base64");
			}
			try {
				if (secret.length < OtpSecrets.MIN_SECRET_BYTES) {
					throw new IllegalStateException("qal.identity.otp.secret-key must decode to at least 32 bytes");
				}
				return new OtpSecrets(secret, random);
			}
			finally {
				Arrays.fill(secret, (byte) 0);
			}
		}
		if (properties.allowEphemeralSecretKey()) {
			if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
				throw new IllegalStateException("qal.identity.otp.allow-ephemeral-secret-key is refused under the "
						+ "prod/production/staging profiles; set qal.identity.otp.secret-key");
			}
			byte[] secret = new byte[OtpSecrets.MIN_SECRET_BYTES];
			random.nextBytes(secret);
			log.warn("Phone OTP uses an EPHEMERAL secret key: codes issued before a restart or by another instance "
					+ "will not verify. Never use this outside development and tests.");
			try {
				return new OtpSecrets(secret, random);
			}
			finally {
				Arrays.fill(secret, (byte) 0);
			}
		}
		throw new IllegalStateException("qal.identity.otp.secret-key is not set (env QAL_OTP_SECRET_KEY, base64 of at "
				+ "least 32 random bytes); qal.identity.otp.allow-ephemeral-secret-key=true is for development and tests only");
	}
}
