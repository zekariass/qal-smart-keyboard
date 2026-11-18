package com.qalsmartkeyboard.aigateway;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.qalsmartkeyboard.aigateway.idempotency.EncryptedReplayCache;
import com.qalsmartkeyboard.aigateway.pipeline.AiRequestLog;
import com.qalsmartkeyboard.aigateway.pipeline.AiUserContext;
import com.qalsmartkeyboard.aigateway.pipeline.Guardrails;
import com.qalsmartkeyboard.aigateway.pipeline.LanguageCatalog;
import com.qalsmartkeyboard.aigateway.pipeline.PromptRenderer;
import com.qalsmartkeyboard.aigateway.pipeline.PromptTemplates;
import com.qalsmartkeyboard.aigateway.pipeline.ReplayWithoutCache;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.privacy.ConsentGate;
import com.qalsmartkeyboard.usage.Metering;

import io.micrometer.core.instrument.MeterRegistry;

/** Wires the request pipeline (B5.3, ADR 0017) on top of the router, metering, entitlements and consents. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiGatewayProperties.class)
class AiPipelineConfiguration {

	static final String REPLAY_CACHE_SWEEPER_TASK = "ai-replay-cache-sweeper";

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

	/**
	 * B5.4: request fingerprints and the encrypted replay cache, falling back to B5.3's {@link ReplayWithoutCache} for
	 * in-flight and failed originals (ADR 0017 "Replay cache (B5.4)").
	 */
	@Bean
	@ConditionalOnMissingBean(AiIdempotency.class)
	EncryptedReplayCache aiIdempotency(JdbcClient jdbc, UserDataEncryption encryption, AiRequestLog requests,
			AiGatewayProperties properties, ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
		AiGatewayProperties.Idempotency i = properties.idempotency();
		return new EncryptedReplayCache(jdbc, encryption, new ReplayWithoutCache(requests),
				clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable(),
				new EncryptedReplayCache.Settings(i.cacheTtl(), i.fingerprintRetention(), i.sweepBatchSize()));
	}

	/**
	 * Deletes expired replay cache rows and fingerprints on the platform's db-scheduler (one instance at a time; the
	 * sweep is idempotent). Reads already ignore expired rows, so the sweep only bounds how long ciphertext lingers.
	 */
	@Bean
	@ConditionalOnBean(EncryptedReplayCache.class)
	@ConditionalOnProperty(prefix = "qal.ai.idempotency", name = "sweeper-enabled", havingValue = "true",
			matchIfMissing = true)
	RecurringTask<Void> aiReplayCacheSweeper(EncryptedReplayCache cache, AiGatewayProperties properties) {
		return Tasks.recurring(REPLAY_CACHE_SWEEPER_TASK, FixedDelay.of(properties.idempotency().sweepInterval()))
				.execute((instance, context) -> cache.sweep());
	}

	@Bean
	AiPipeline aiPipeline(EntitlementResolver entitlements, ConsentGate consents, Metering metering, AiRouter router,
			ProviderRegistry registry, PromptTemplates templates, PromptRenderer renderer, LanguageCatalog languages,
			AiUserContext users, AiRequestLog requests, AiIdempotency idempotency, AiGatewayProperties properties,
			ObjectProvider<AiHistorySink> history, ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
		AiGatewayProperties.Pipeline p = properties.pipeline();
		return new AiPipeline(entitlements, consents, metering, router, registry, templates, renderer, languages,
				users, new Guardrails(new Guardrails.Config(p.minScriptRatio())), requests, idempotency,
				history.getIfAvailable(() -> AiHistorySink.NONE), clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable(),
				new AiPipeline.Settings(p.deadline(), p.requireAiProcessingConsent(), p.maxTextCharacters(),
						p.maxDetectCharacters()));
	}

}
