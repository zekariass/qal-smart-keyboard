package com.qalsmartkeyboard.entitlements;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EntitlementProperties.class)
class EntitlementsConfiguration {

	@Bean
	EntitlementResolver entitlementResolver(JdbcTemplate jdbc, EntitlementProperties properties,
			ObjectProvider<Clock> clock) {
		return new EntitlementResolver(new JdbcEntitlementData(jdbc), properties,
				clock.getIfAvailable(Clock::systemUTC));
	}
}
