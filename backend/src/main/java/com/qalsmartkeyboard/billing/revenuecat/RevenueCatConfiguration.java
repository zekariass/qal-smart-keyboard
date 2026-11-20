package com.qalsmartkeyboard.billing.revenuecat;

import java.time.Clock;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Registers the RevenueCat store-billing adapter when {@code qal.billing.revenuecat.secret-api-key} (env
 * {@code REVENUECAT_SECRET_API_KEY}) is set; without it no RevenueCat route is offered (SD 16.3 rule 2) and its
 * notification endpoint answers 404. Under prod/production/staging the webhook authorization value (at least 32
 * characters) and an https base URL are required.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RevenueCatProperties.class)
class RevenueCatConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";
	private static final Logger log = LoggerFactory.getLogger(RevenueCatConfiguration.class);

	/** True when the secret API key property is non-blank. */
	static final class SecretKeyPresent implements Condition {
		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String key = context.getEnvironment().getProperty("qal.billing.revenuecat.secret-api-key");
			return key != null && !key.isBlank();
		}
	}

	@Bean
	@Conditional(SecretKeyPresent.class)
	RevenueCatProvider revenueCatProvider(RevenueCatProperties properties, Environment environment,
			ObjectProvider<Clock> clock) {
		boolean productionLike = environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES));
		String scheme = properties.effectiveBaseUrl().getScheme() == null ? ""
				: properties.effectiveBaseUrl().getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http") || properties.effectiveBaseUrl().getHost() == null) {
			throw new IllegalStateException("qal.billing.revenuecat.base-url must be an absolute http(s) URL");
		}
		if (productionLike) {
			if (!scheme.equals("https")) {
				throw new IllegalStateException("qal.billing.revenuecat.base-url must use https under the "
						+ "prod/production/staging profiles");
			}
			if (properties.webhookAuth() == null || properties.webhookAuth().length() < 32) {
				throw new IllegalStateException("qal.billing.revenuecat.webhook-auth (REVENUECAT_WEBHOOK_AUTH) of at "
						+ "least 32 characters is required under the prod/production/staging profiles");
			}
		}
		else if (properties.webhookAuth() == null) {
			log.warn("REVENUECAT_WEBHOOK_AUTH is not set: every RevenueCat notification is refused (client reports "
					+ "still verify purchases)");
		}
		log.info("RevenueCat adapter enabled environment={} baseUrl={} signedWebhooks={}", properties.environment(),
				properties.effectiveBaseUrl(), properties.webhookSigningSecret() != null);
		return new RevenueCatProvider(properties, clock.getIfAvailable(Clock::systemUTC));
	}
}
