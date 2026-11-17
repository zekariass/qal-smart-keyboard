package com.qalsmartkeyboard.notifications.sms;

import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.qalsmartkeyboard.notifications.OtpSender;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Selects the SMS provider and fails closed at startup: no provider configured, an unknown provider, or the
 * {@code log} mock under a production-like profile all stop the application.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmsProperties.class)
class SmsConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";

	@Bean
	SmsProvider smsProvider(SmsProperties properties, Environment environment) {
		String provider = properties.provider();
		if (provider == null || provider.isBlank()) {
			throw new IllegalStateException("qal.notifications.sms.provider is not set (env QAL_SMS_PROVIDER); "
					+ "use 'log' for development. No production SMS aggregator is implemented yet (OI-11)");
		}
		return switch (provider.trim().toLowerCase(Locale.ROOT)) {
			case "log" -> {
				if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
					throw new IllegalStateException(
							"The 'log' SMS provider is refused under the prod/production/staging profiles");
				}
				yield new LoggingSmsProvider(properties.log().includeMessage());
			}
			default -> throw new IllegalStateException("Unknown qal.notifications.sms.provider; use 'log'");
		};
	}

	@Bean
	OtpSender otpSender(SmsProvider provider, ObjectProvider<MeterRegistry> meters) {
		return new SmsOtpSender(provider, meters.getIfAvailable());
	}
}
