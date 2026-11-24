package com.qalsmartkeyboard.tools;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FxProperties.class)
class ToolsConfiguration {

	/** The seeded-table adapter; define another {@link FxRateSource} bean to plug in a real provider. */
	@Bean
	@ConditionalOnMissingBean(FxRateSource.class)
	FxRateSource fxRateSource(JdbcTemplate jdbc) {
		return new StaticFxRateSource(jdbc);
	}

	@Bean
	FxRatesService fxRatesService(FxRateSource source, FxProperties properties, ObjectProvider<Clock> clock) {
		return new FxRatesService(source, properties, clock.getIfAvailable(Clock::systemUTC));
	}
}
