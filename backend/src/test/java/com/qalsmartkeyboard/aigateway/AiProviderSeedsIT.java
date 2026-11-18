package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.DataPolicyRequirement;
import com.qalsmartkeyboard.aigateway.spi.ProviderDescriptor;
import com.qalsmartkeyboard.aigateway.spi.ProviderStatus;
import com.qalsmartkeyboard.aigateway.spi.RouteQuery;

/**
 * V10 seeds against real PostgreSQL and the registry reading them back (B5.1): every provider is seeded DISABLED, so
 * nothing routes until an operator enables one; enabling the mock row makes exactly the mock adapter routable.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false" })
@Testcontainers
class AiProviderSeedsIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ProviderRegistry registry;
	@Autowired
	ProviderDescriptorSource source;

	static final RouteQuery EN_AM = RouteQuery.of(AiFeature.TRANSLATE, "en", "am");

	@Test
	void everySeededProviderIsInactiveAndNoRealVendorIsSeeded() {
		List<String> statuses = jdbc.queryForList("SELECT DISTINCT provider_status FROM ai_providers", String.class);
		assertThat(statuses).containsExactly("DISABLED");
		assertThat(jdbc.queryForList("SELECT provider_code FROM ai_providers ORDER BY provider_code", String.class))
				.containsExactly("mock", "wiremock-http");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_providers WHERE dpa_signed_at IS NOT NULL", Integer.class))
				.isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_providers WHERE secret_reference IS NOT NULL",
				Integer.class)).isZero();
	}

	@Test
	void seededCapabilityMatrix() {
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ai_language_capabilities c JOIN ai_models m ON m.id = c.model_id
				 JOIN ai_providers p ON p.id = m.provider_id
				 WHERE p.provider_code = 'mock' AND c.feature_code = 'TRANSLATE'""", Integer.class)).isEqualTo(6);
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM ai_language_capabilities c JOIN ai_models m ON m.id = c.model_id
				 JOIN ai_providers p ON p.id = m.provider_id
				 WHERE p.provider_code = 'mock' AND c.feature_code IN ('GRAMMAR_CORRECTION', 'REWRITE', 'TONE_ADJUST')""",
				Integer.class)).isEqualTo(12);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_language_capabilities WHERE feature_code = 'DETECT_LANGUAGE'",
				Integer.class)).isEqualTo(2);
	}

	@Test
	void registryLoadsTheSeedsButRoutesNothingWhileInactive() {
		List<ProviderDescriptor> all = source.loadAll();
		assertThat(all).extracting(ProviderDescriptor::code).containsExactly("mock", "wiremock-http");
		assertThat(all).allSatisfy(d -> {
			assertThat(d.status()).isEqualTo(ProviderStatus.DISABLED);
			assertThat(d.dataPolicy().trainsOnCustomerData()).isFalse();
			assertThat(d.dataPolicy().zeroDataRetention()).isTrue();
			assertThat(d.dataPolicy().processingRegions()).containsExactly("local");
			assertThat(d.capabilities()).hasSize(19);
		});
		for (AiFeature feature : AiFeature.values()) {
			assertThat(registry.resolve(RouteQuery.of(feature, "en", "am"))).isEmpty();
		}
		assertThat(registry.activeDescriptors()).isEmpty();
	}

	@Test
	void enablingTheMockRowRoutesOnlyTheMockAdapter() {
		jdbc.update("UPDATE ai_providers SET provider_status = 'ACTIVE', dpa_signed_at = NOW() "
				+ "WHERE provider_code IN ('mock', 'wiremock-http')");
		try {
			// wiremock-http has no adapter bean in this context (qal.ai.http.enabled is off): a row never creates one
			assertThat(registry.resolve(EN_AM)).extracting(r -> r.code()).containsExactly("mock");
			assertThat(registry.resolve(EN_AM.withDataPolicy(DataPolicyRequirement.ZERO_RETENTION)))
					.extracting(r -> r.code()).containsExactly("mock");
			assertThat(registry.resolve(RouteQuery.of(AiFeature.DETECT_LANGUAGE, null, null))).hasSize(1);
			assertThat(registry.resolve(RouteQuery.of(AiFeature.GRAMMAR_CORRECTION, "am", null))).hasSize(1);
			assertThat(registry.resolve(RouteQuery.of(AiFeature.TRANSLATE, null, "am"))).hasSize(1); // auto-detect
			assertThat(registry.resolve(RouteQuery.of(AiFeature.TRANSLATE, "en", "fr"))).isEmpty();
			assertThat(registry.resolve(RouteQuery.of(AiFeature.SUMMARIZE, "en", null))).isEmpty();

			jdbc.update("UPDATE ai_providers SET zero_data_retention = FALSE WHERE provider_code = 'mock'");
			assertThat(registry.resolve(EN_AM.withDataPolicy(DataPolicyRequirement.ZERO_RETENTION))).isEmpty();
			assertThat(registry.resolve(EN_AM)).hasSize(1);
		} finally {
			jdbc.update("UPDATE ai_providers SET provider_status = 'DISABLED', dpa_signed_at = NULL, "
					+ "zero_data_retention = TRUE WHERE provider_code IN ('mock', 'wiremock-http')");
		}
		assertThat(registry.resolve(EN_AM)).isEmpty();
	}
}
