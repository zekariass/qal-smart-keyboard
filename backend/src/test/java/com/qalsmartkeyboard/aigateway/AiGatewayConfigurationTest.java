package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.aigateway.adapter.HttpJsonAiProvider;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;

/** Mocks are off by default, on by switch, and refused under production-like profiles (B5.1). No database. */
class AiGatewayConfigurationTest {

	@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
	@Import(AiGatewayConfiguration.class)
	static class Wiring {
		@Bean
		JdbcClient jdbcClient() {
			return org.mockito.Mockito.mock(JdbcClient.class);
		}
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);

	@Test
	void noAdapterBeansByDefault() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).doesNotHaveBean(MockAiProvider.class).doesNotHaveBean(HttpJsonAiProvider.class);
			assertThat(ctx).hasSingleBean(ProviderRegistry.class);
		});
	}

	@Test
	void mockAndHttpAdaptersAreCreatedWhenSwitchedOnInDevelopment() {
		runner.withPropertyValues("qal.ai.mock.enabled=true", "qal.ai.mock.code=mock-b", "qal.ai.http.enabled=true",
				"qal.ai.http.api-key=sk-secret-value").run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx.getBean(MockAiProvider.class).code()).isEqualTo("mock-b");
					assertThat(ctx.getBean(HttpJsonAiProvider.class).code()).isEqualTo("wiremock-http");
				});
	}

	@Test
	void configuredMockFailureModeApplies() {
		runner.withPropertyValues("qal.ai.mock.enabled=true", "qal.ai.mock.failure=UNAVAILABLE").run(ctx -> {
			var mock = ctx.getBean(MockAiProvider.class);
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> mock.translate(
					com.qalsmartkeyboard.aigateway.spi.TranslateRequest.of("x", "en", "am"),
					java.time.Duration.ofSeconds(1))).isInstanceOf(
							com.qalsmartkeyboard.aigateway.spi.ProviderException.Unavailable.class);
		});
	}

	@Test
	void bothAdaptersAreRefusedUnderProductionLikeProfiles() {
		for (String profile : new String[] { "prod", "production", "staging" }) {
			runner.withPropertyValues("spring.profiles.active=" + profile, "qal.ai.mock.enabled=true").run(
					ctx -> assertThat(ctx).hasFailed());
			runner.withPropertyValues("spring.profiles.active=" + profile, "qal.ai.http.enabled=true").run(
					ctx -> assertThat(ctx).hasFailed());
			runner.withPropertyValues("spring.profiles.active=" + profile).run(ctx -> assertThat(ctx).hasNotFailed());
		}
	}

	@Test
	void httpPropertiesNeverPrintTheKey() {
		var http = new AiGatewayProperties.Http(true, "p", "http://localhost:8089", java.time.Duration.ofSeconds(2),
				"sk-secret-value", "");
		assertThat(http.toString()).doesNotContain("sk-secret-value");
	}

	@Test
	void replayCacheTtlIsCappedAtTenMinutesAndFingerprintsAreKeptADay() {
		runner.run(ctx -> {
			AiGatewayProperties.Idempotency i = ctx.getBean(AiGatewayProperties.class).idempotency();
			assertThat(i.cacheTtl()).isEqualTo(java.time.Duration.ofMinutes(10));
			assertThat(i.fingerprintRetention()).isEqualTo(java.time.Duration.ofHours(24));
		});
		runner.withPropertyValues("qal.ai.idempotency.cache-ttl=0s").run(ctx -> assertThat(ctx).hasNotFailed());
		runner.withPropertyValues("qal.ai.idempotency.cache-ttl=11m").run(ctx -> assertThat(ctx).hasFailed());
		runner.withPropertyValues("qal.ai.idempotency.fingerprint-retention=23h")
				.run(ctx -> assertThat(ctx).hasFailed());
	}
}
