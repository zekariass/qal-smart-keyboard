package com.qalsmartkeyboard.billing.arifpay;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.qalsmartkeyboard.billing.spi.PaymentEnvironment;

/**
 * Registers the ArifPay adapter when {@code qal.billing.arifpay.api-key} (env {@code ARIFPAY_SECRET_KEY}) is set; without
 * it no ArifPay route is ever offered (SD 16.3 rule 2). Under prod/production/staging: a webhook secret of at least 32
 * characters and an https base URL are required. Elsewhere a missing webhook secret becomes a random per-JVM key (notify
 * tokens of attempts started before a restart then fail and the reconciliation job verifies those attempts instead).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArifPayProperties.class)
class ArifPayConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";
	private static final Logger log = LoggerFactory.getLogger(ArifPayConfiguration.class);

	/** True when the API key property is non-blank. */
	static final class ApiKeyPresent implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String key = context.getEnvironment().getProperty("qal.billing.arifpay.api-key");
			return key != null && !key.isBlank();
		}
	}

	@Bean
	@Conditional(ApiKeyPresent.class)
	ArifPayProvider arifPayProvider(ArifPayProperties properties, Environment environment) {
		boolean productionLike = environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES));
		String scheme = properties.effectiveBaseUrl().getScheme() == null ? ""
				: properties.effectiveBaseUrl().getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http") || properties.effectiveBaseUrl().getHost() == null) {
			throw new IllegalStateException("qal.billing.arifpay.base-url must be an absolute http(s) URL");
		}
		byte[] key;
		if (properties.webhookSecret() != null) {
			if (productionLike && properties.webhookSecret().length() < 32) {
				throw new IllegalStateException("qal.billing.arifpay.webhook-secret (QAL_ARIFPAY_WEBHOOK_SECRET) must be "
						+ "at least 32 characters under the prod/production/staging profiles");
			}
			key = properties.webhookSecret().getBytes(StandardCharsets.UTF_8);
		}
		else {
			if (productionLike) {
				throw new IllegalStateException("qal.billing.arifpay.webhook-secret (QAL_ARIFPAY_WEBHOOK_SECRET) is "
						+ "required under the prod/production/staging profiles");
			}
			key = new byte[32];
			new SecureRandom().nextBytes(key);
			log.warn("QAL_ARIFPAY_WEBHOOK_SECRET is not set: using a random per-JVM notify-token key (development only)");
		}
		if (productionLike && !scheme.equals("https")) {
			throw new IllegalStateException("qal.billing.arifpay.base-url must use https under the "
					+ "prod/production/staging profiles");
		}
		if (productionLike && properties.environment() == PaymentEnvironment.SANDBOX) {
			log.warn("The ArifPay adapter runs with a SANDBOX key under a production profile");
		}
		log.info("ArifPay adapter enabled environment={} baseUrl={}", properties.environment(),
				properties.effectiveBaseUrl());
		return new ArifPayProvider(properties, key);
	}
}
