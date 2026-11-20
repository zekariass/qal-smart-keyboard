package com.qalsmartkeyboard.billing.chapa;

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
 * Registers the Chapa adapter when {@code qal.billing.chapa.secret-key} (env {@code CHAPA_SECRET_KEY}) is set; without
 * it no Chapa route is ever offered (SD 16.3 rule 2). A Chapa key that names its mode ({@code CHASECK_TEST-} /
 * {@code CHASECK-}) must match {@code qal.billing.chapa.environment}: test and live never mix. Under
 * prod/production/staging the webhook secret and an https base URL are required.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChapaProperties.class)
class ChapaConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";
	private static final Logger log = LoggerFactory.getLogger(ChapaConfiguration.class);

	/** True when the secret key property is non-blank. */
	static final class SecretKeyPresent implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String key = context.getEnvironment().getProperty("qal.billing.chapa.secret-key");
			return key != null && !key.isBlank();
		}
	}

	@Bean
	@Conditional(SecretKeyPresent.class)
	ChapaProvider chapaProvider(ChapaProperties properties, Environment environment) {
		boolean productionLike = environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES));
		String scheme = properties.effectiveBaseUrl().getScheme() == null ? ""
				: properties.effectiveBaseUrl().getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http") || properties.effectiveBaseUrl().getHost() == null) {
			throw new IllegalStateException("qal.billing.chapa.base-url must be an absolute http(s) URL");
		}
		if (productionLike && !scheme.equals("https")) {
			throw new IllegalStateException("qal.billing.chapa.base-url must use https under the "
					+ "prod/production/staging profiles");
		}
		String upper = properties.secretKey().toUpperCase(Locale.ROOT);
		if (upper.startsWith("CHASECK")) {
			boolean test = properties.testKey();
			if (test && properties.environment() == PaymentEnvironment.PRODUCTION
					|| !test && properties.environment() == PaymentEnvironment.SANDBOX) {
				throw new IllegalStateException("CHAPA_SECRET_KEY is a " + (test ? "test" : "live")
						+ " key but QAL_CHAPA_ENVIRONMENT is " + properties.environment());
			}
		}
		if (properties.webhookSecret() == null) {
			if (productionLike) {
				throw new IllegalStateException("qal.billing.chapa.webhook-secret (CHAPA_WEBHOOK_SECRET) is required "
						+ "under the prod/production/staging profiles");
			}
			log.warn("CHAPA_WEBHOOK_SECRET is not set: every Chapa webhook is refused (the return callback and the "
					+ "reconciliation job still verify payments)");
		}
		log.info("Chapa adapter enabled environment={} baseUrl={}", properties.environment(),
				properties.effectiveBaseUrl());
		return new ChapaProvider(properties);
	}
}
