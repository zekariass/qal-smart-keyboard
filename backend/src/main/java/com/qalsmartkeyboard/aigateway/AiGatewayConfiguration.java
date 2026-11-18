package com.qalsmartkeyboard.aigateway;

import java.net.URI;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.aigateway.adapter.GoogleTranslateProvider;
import com.qalsmartkeyboard.aigateway.adapter.HttpJsonAiProvider;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;
import com.qalsmartkeyboard.aigateway.adapter.OpenAiProvider;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreaker;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreakers;
import com.qalsmartkeyboard.aigateway.routing.CostControl;
import com.qalsmartkeyboard.aigateway.routing.CostStore;
import com.qalsmartkeyboard.aigateway.routing.JdbcCostStore;
import com.qalsmartkeyboard.aigateway.routing.JdbcKillSwitchSource;
import com.qalsmartkeyboard.aigateway.routing.KillSwitch;
import com.qalsmartkeyboard.aigateway.routing.RoutingMetrics;
import com.qalsmartkeyboard.aigateway.routing.RoutingPolicies;
import com.qalsmartkeyboard.aigateway.spi.AiProvider;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Wires the registry, the router and the adapters (the request pipeline is {@link AiPipelineConfiguration}). The mock and
 * the WireMock-shaped HTTP adapter fail the startup under a production-like profile, like the other mocks (SMS,
 * attestation). The real vendor adapters (OpenAI, Google Cloud Translation; ADR 0007 D46, ADR 0023) are allowed under
 * every profile and exist only when their API key is set; their {@code ai_providers} rows still decide whether they are
 * routed.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiGatewayProperties.class)
class AiGatewayConfiguration {

	private static final Logger log = LoggerFactory.getLogger(AiGatewayConfiguration.class);

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
	RoutingMetrics routingMetrics(ObjectProvider<MeterRegistry> meters) {
		return new RoutingMetrics(meters.getIfAvailable(SimpleMeterRegistry::new));
	}

	@Bean
	CircuitBreakers providerCircuitBreakers(AiGatewayProperties properties, ObjectProvider<Clock> clock,
			RoutingMetrics metrics) {
		AiGatewayProperties.Breaker b = properties.routing().breaker();
		return new CircuitBreakers(new CircuitBreaker.Config(b.windowSize(), b.minimumCalls(), b.failureRatePercent(),
				b.slowCallThreshold(), b.slowCallRatePercent(), b.openDuration(), b.halfOpenCalls()),
				clock.getIfAvailable(Clock::systemUTC), t -> {
					metrics.circuitTransition(t);
					log.warn("AI provider {} circuit breaker {} -> {}", t.providerCode(), t.from(), t.to());
				});
	}

	@Bean
	KillSwitch aiKillSwitch(JdbcClient jdbc, AiGatewayProperties properties, ObjectProvider<Clock> clock) {
		AiGatewayProperties.Routing r = properties.routing();
		return new KillSwitch(new JdbcKillSwitchSource(jdbc), r.killedProviders(), r.killedFeatures(),
				r.killSwitchCacheTtl(), clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	RoutingPolicies routingPolicies(JdbcClient jdbc, AiGatewayProperties properties, ObjectProvider<Clock> clock) {
		return new RoutingPolicies(RoutingPolicies.jdbc(jdbc), properties.routing().policyCacheTtl(),
				clock.getIfAvailable(Clock::systemUTC), () -> ThreadLocalRandom.current().nextDouble());
	}

	@Bean
	CostStore aiCostStore(JdbcClient jdbc) {
		return new JdbcCostStore(jdbc);
	}

	@Bean
	CostControl aiCostControl(CostStore store, AiGatewayProperties properties, ObjectProvider<Clock> clock,
			RoutingMetrics metrics) {
		return new CostControl(store, properties.routing().costCacheTtl(), clock.getIfAvailable(Clock::systemUTC),
				a -> {
					metrics.costAlert(a.providerCode(), a.level().name());
					log.warn("AI provider {} reached its {} daily spend limit ({} of {} micro-USD)", a.providerCode(),
							a.level(), a.spendMicros(), a.limitMicros());
				});
	}

	@Bean
	AiRouter aiRouter(ProviderRegistry registry, RoutingPolicies policies, KillSwitch killSwitch,
			CircuitBreakers breakers, CostControl cost, AiGatewayProperties properties, RoutingMetrics metrics) {
		AiGatewayProperties.Routing r = properties.routing();
		return new AiRouter(registry, policies, killSwitch, breakers, cost,
				new AiRouter.Settings(r.maxAttempts(), r.attemptTimeout(), r.deadline()), metrics);
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

	/** OpenAI (D46): only with {@code QAL_OPENAI_API_KEY}; a key without {@code QAL_OPENAI_MODEL} stops the startup. */
	@Bean
	@Conditional(OpenAiKeyPresent.class)
	OpenAiProvider openAiProvider(AiGatewayProperties properties) {
		AiGatewayProperties.OpenAi o = properties.openai();
		if (o.model() == null || o.model().isBlank()) {
			throw new IllegalStateException("QAL_OPENAI_API_KEY is set but QAL_OPENAI_MODEL (qal.ai.openai.model) is "
					+ "blank: set the OpenAI model id to use, or unset the key to run without the OpenAI adapter");
		}
		return new OpenAiProvider(new OpenAiProvider.Config(URI.create(o.baseUrl()), o.connectTimeout(), o.apiKey(),
				o.model(), o.maxOutputTokens(), o.reasoningEffort()));
	}

	/** Google Cloud Translation - Basic (D46): only with {@code QAL_GOOGLE_TRANSLATE_API_KEY}. */
	@Bean
	@Conditional(GoogleTranslateKeyPresent.class)
	GoogleTranslateProvider googleTranslateProvider(AiGatewayProperties properties) {
		AiGatewayProperties.GoogleTranslate g = properties.googleTranslate();
		return new GoogleTranslateProvider(new GoogleTranslateProvider.Config(URI.create(g.baseUrl()),
				g.connectTimeout(), g.apiKey()));
	}

	/**
	 * Matches when a property holds non-blank text. {@code @ConditionalOnProperty} without a value also matches an empty
	 * string (only "false" fails it), and {@code application.yaml} always defines the key as {@code ${ENV:}}.
	 */
	abstract static class NonBlankProperty implements Condition {
		private final String name;

		NonBlankProperty(String name) {
			this.name = name;
		}

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			String value = context.getEnvironment().getProperty(name);
			return value != null && !value.isBlank();
		}
	}

	static final class OpenAiKeyPresent extends NonBlankProperty {
		OpenAiKeyPresent() {
			super("qal.ai.openai.api-key");
		}
	}

	static final class GoogleTranslateKeyPresent extends NonBlankProperty {
		GoogleTranslateKeyPresent() {
			super("qal.ai.google-translate.api-key");
		}
	}

	private static void refuseInProduction(Environment environment, String what) {
		if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
			throw new IllegalStateException(what + " is refused under the prod/production/staging profiles");
		}
	}
}
