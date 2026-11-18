package com.qalsmartkeyboard.aigateway;

import java.net.URI;
import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.aigateway.adapter.HttpJsonAiProvider;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;
import com.qalsmartkeyboard.aigateway.spi.AiProvider;

/**
 * Wires the registry and the dev/test adapters. The mock and the WireMock-shaped HTTP adapter fail the startup under a
 * production-like profile, like the other mocks (SMS, attestation). No real vendor adapter exists until gate G0.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiGatewayProperties.class)
class AiGatewayConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";

	@Bean
	ProviderDescriptorSource providerDescriptorSource(JdbcClient jdbc) {
		return new JdbcProviderDescriptorSource(jdbc);
	}

	@Bean
	ProviderRegistry providerRegistry(ProviderDescriptorSource source, ObjectProvider<AiProvider> adapters,
			AiGatewayProperties properties, ObjectProvider<Clock> clock) {
		return new ProviderRegistry(source, adapters.orderedStream().toList(), properties.registryCacheTtl(),
				clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	@ConditionalOnProperty(name = "qal.ai.mock.enabled", havingValue = "true")
	MockAiProvider mockAiProvider(AiGatewayProperties properties, Environment environment) {
		refuseInProduction(environment, "The mock AI provider (qal.ai.mock.enabled)");
		AiGatewayProperties.Mock mock = properties.mock();
		return new MockAiProvider(mock.code(), new MockAiProvider.Behavior(mock.latency(), mock.failure(),
				mock.failure() == MockAiProvider.FailureMode.NONE ? 0 : Integer.MAX_VALUE));
	}

	@Bean
	@ConditionalOnProperty(name = "qal.ai.http.enabled", havingValue = "true")
	HttpJsonAiProvider httpJsonAiProvider(AiGatewayProperties properties, Environment environment) {
		refuseInProduction(environment, "The WireMock-shaped HTTP AI adapter (qal.ai.http.enabled)");
		AiGatewayProperties.Http http = properties.http();
		return new HttpJsonAiProvider(new HttpJsonAiProvider.Config(http.code(), URI.create(http.baseUrl()),
				http.connectTimeout(), http.apiKey(), http.mockScenario()));
	}

	private static void refuseInProduction(Environment environment, String what) {
		if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
			throw new IllegalStateException(what + " is refused under the prod/production/staging profiles");
		}
	}
}
