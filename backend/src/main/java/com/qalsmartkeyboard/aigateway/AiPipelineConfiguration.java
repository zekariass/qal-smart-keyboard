package com.qalsmartkeyboard.aigateway;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.aigateway.pipeline.AiRequestLog;
import com.qalsmartkeyboard.aigateway.pipeline.AiUserContext;
import com.qalsmartkeyboard.aigateway.pipeline.Guardrails;
import com.qalsmartkeyboard.aigateway.pipeline.LanguageCatalog;
import com.qalsmartkeyboard.aigateway.pipeline.PromptRenderer;
import com.qalsmartkeyboard.aigateway.pipeline.PromptTemplates;
import com.qalsmartkeyboard.aigateway.pipeline.ReplayWithoutCache;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.privacy.ConsentGate;
import com.qalsmartkeyboard.usage.Metering;

import io.micrometer.core.instrument.MeterRegistry;

/** Wires the request pipeline (B5.3, ADR 0017) on top of the router, metering, entitlements and consents. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiGatewayProperties.class)
class AiPipelineConfiguration {

	@Bean
	PromptRenderer aiPromptRenderer() {
		return new PromptRenderer();
	}

	@Bean
	PromptTemplates aiPromptTemplates(JdbcClient jdbc, PromptRenderer renderer, AiGatewayProperties properties,
			ObjectProvider<Clock> clock) {
		return new PromptTemplates(PromptTemplates.jdbc(jdbc), renderer, properties.pipeline().catalogCacheTtl(),
				clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	LanguageCatalog aiLanguageCatalog(JdbcClient jdbc, AiGatewayProperties properties, ObjectProvider<Clock> clock) {
		return new LanguageCatalog(LanguageCatalog.jdbc(jdbc), properties.pipeline().catalogCacheTtl(),
				clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	AiUserContext aiUserContext(JdbcClient jdbc) {
		return AiUserContext.jdbc(jdbc);
	}

	@Bean
	AiRequestLog aiRequestLog(JdbcClient jdbc) {
		return new AiRequestLog(jdbc);
	}

	/** B5.4 replaces this with the encrypted replay cache (or wraps it). */
	@Bean
	@ConditionalOnMissingBean(AiIdempotency.class)
	AiIdempotency aiIdempotency(AiRequestLog requests) {
		return new ReplayWithoutCache(requests);
	}

	@Bean
	AiPipeline aiPipeline(EntitlementResolver entitlements, ConsentGate consents, Metering metering, AiRouter router,
			ProviderRegistry registry, PromptTemplates templates, PromptRenderer renderer, LanguageCatalog languages,
			AiUserContext users, AiRequestLog requests, AiIdempotency idempotency, AiGatewayProperties properties,
			ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
		AiGatewayProperties.Pipeline p = properties.pipeline();
		return new AiPipeline(entitlements, consents, metering, router, registry, templates, renderer, languages,
				users, new Guardrails(new Guardrails.Config(p.minScriptRatio())), requests, idempotency,
				clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable(),
				new AiPipeline.Settings(p.deadline(), p.requireAiProcessingConsent(), p.maxTextCharacters(),
						p.maxDetectCharacters()));
	}

}
