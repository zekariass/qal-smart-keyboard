package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.qalsmartkeyboard.aigateway.idempotency.EncryptedReplayCache;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.privacy.ConsentGate;
import com.qalsmartkeyboard.usage.Metering;

/** B5.4 wiring without a database: the replay cache is the default seam, and its sweeper is a scheduler task. */
class AiPipelineConfigurationTest {

	@Configuration(proxyBeanMethods = false)
	@Import(AiPipelineConfiguration.class)
	static class Dependencies {
		@Bean
		JdbcClient jdbcClient() {
			return mock(JdbcClient.class);
		}

		@Bean
		UserDataEncryption userDataEncryption() {
			return mock(UserDataEncryption.class);
		}

		@Bean
		EntitlementResolver entitlementResolver() {
			return mock(EntitlementResolver.class);
		}

		@Bean
		ConsentGate consentGate() {
			return mock(ConsentGate.class);
		}

		@Bean
		Metering metering() {
			return mock(Metering.class);
		}

		@Bean
		AiRouter aiRouter() {
			return mock(AiRouter.class);
		}

		@Bean
		ProviderRegistry providerRegistry() {
			return mock(ProviderRegistry.class);
		}
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(Dependencies.class);

	@Test
	void theEncryptedReplayCacheIsTheSeamAndItsSweeperIsScheduled() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(AiIdempotency.class)).isInstanceOf(EncryptedReplayCache.class);
			assertThat(ctx.getBeansOfType(RecurringTask.class).values()).singleElement()
					.satisfies(t -> assertThat(t.getName()).isEqualTo("ai-replay-cache-sweeper"));
		});
	}

	@Test
	void theSweeperCanBeSwitchedOff() {
		runner.withPropertyValues("qal.ai.idempotency.sweeper-enabled=false").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).hasSingleBean(EncryptedReplayCache.class).doesNotHaveBean(RecurringTask.class);
		});
	}

	@Test
	void anotherSeamReplacesTheCacheAndItsSweeper() {
		AiIdempotency custom = (command, reservation) -> new AiIdempotency.Decision.Recompute();
		runner.withBean(AiIdempotency.class, () -> custom).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(AiIdempotency.class)).isSameAs(custom);
			assertThat(ctx).doesNotHaveBean(EncryptedReplayCache.class).doesNotHaveBean(RecurringTask.class);
		});
	}
}
