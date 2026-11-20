package com.qalsmartkeyboard.platform.geo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Wires {@link CountryResolver}. {@code DEV_MOCK_COUNTRY} under a prod/production/staging profile stops the
 * application (ADR 0045: refusing is safer than a warning nobody reads, because the override would let every caller
 * buy as that country). Without an edge trust configuration {@code CF-IPCountry} is never trusted (startup WARN).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeoProperties.class)
class GeoConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";
	private static final Logger log = LoggerFactory.getLogger(GeoConfiguration.class);

	@Bean
	CountryResolver countryResolver(GeoProperties properties, Environment environment) {
		boolean productionLike = environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES));
		if (properties.devMockCountry() != null && productionLike) {
			throw new IllegalStateException("qal.geo.dev-mock-country (DEV_MOCK_COUNTRY) is refused under the "
					+ "prod/production/staging profiles");
		}
		if (productionLike && properties.edgeSecret() != null && properties.edgeSecret().length() < 24) {
			throw new IllegalStateException("qal.geo.edge-secret must be at least 24 characters under the "
					+ "prod/production/staging profiles");
		}
		CountryResolver resolver = new CountryResolver(properties);
		if (resolver.devMockActive()) {
			log.warn("DEV_MOCK_COUNTRY={} replaces the IP country of every request (development only)",
					properties.devMockCountry());
		}
		else if (!resolver.edgeTrustConfigured()) {
			log.warn("No trusted edge configured (qal.geo.trusted-proxies / qal.geo.edge-secret): CF-IPCountry is "
					+ "ignored and billing falls back to the global default");
		}
		return resolver;
	}
}
