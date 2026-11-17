package com.qalsmartkeyboard.configuration;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConfigProperties.class)
class ConfigurationBeans {

	@Bean
	ClientConfigService clientConfigService(ConfigRepository repository, ConfigProperties properties,
			ObjectProvider<Clock> clock) {
		return new ClientConfigService(repository, properties, clock.getIfAvailable(Clock::systemUTC));
	}
}
