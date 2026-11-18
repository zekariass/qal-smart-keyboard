package com.qalsmartkeyboard.aigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.aigateway.RouteResult.Outcome;
import com.qalsmartkeyboard.aigateway.RouteResult.SkipReason;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.Behavior;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.FailureMode;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreakers;
import com.qalsmartkeyboard.aigateway.spi.DataPolicyRequirement;
import com.qalsmartkeyboard.aigateway.spi.TranslateRequest;
import com.qalsmartkeyboard.aigateway.spi.TranslateResult;

/**
 * Failover against PostgreSQL 18 with real policy rows (B5.2): the two seeded providers (V10) enabled, routed by
 * ai_routing_policies, with the V12 price, budget and spend tables and the ai.kill_switch configuration row. Every
 * cache TTL is zero so a database change bites at once.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.ai.registry-cache-ttl=0s",
		"qal.ai.routing.policy-cache-ttl=0s", "qal.ai.routing.kill-switch-cache-ttl=0s",
		"qal.ai.routing.cost-cache-ttl=0s", "qal.ai.routing.breaker.window-size=4",
		"qal.ai.routing.breaker.minimum-calls=2", "qal.ai.routing.breaker.open-duration=30s",
		"qal.ai.routing.attempt-timeout=1s", "qal.ai.routing.deadline=3s" })
@Testcontainers
@Import(AiRoutingIT.Adapters.class)
class AiRoutingIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	/**
	 * The seeded rows are mock (the test configuration already creates its adapter; priority one here) and
	 * wiremock-http (the fallback, an in-process mock under that code).
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class Adapters {
		@Bean
		MockAiProvider fallbackMock() {
			return new MockAiProvider("wiremock-http", Behavior.ok());
		}
	}

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AiRouter router;
	@Autowired
	CircuitBreakers breakers;
	@Autowired
	@Qualifier("mockAiProvider")
	MockAiProvider primaryMock;
	@Autowired
	@Qualifier("fallbackMock")
	MockAiProvider fallbackMock;

	int primaryBase;

	/** Calls the primary mock received during this test (its counter is cumulative across tests). */
	int primaryCalls() {
		return primaryMock.callCount() - primaryBase;
	}

	@BeforeEach
	void enable() {
		clean();
		jdbc.update("UPDATE ai_providers SET provider_status = 'ACTIVE', dpa_signed_at = NOW() "
				+ "WHERE provider_code IN ('mock', 'wiremock-http')");
		primaryMock.setBehavior(Behavior.ok());
		fallbackMock.setBehavior(Behavior.ok());
		breakers.resetAll();
		primaryBase = primaryMock.callCount();
	}

	@AfterEach
	void clean() {
		jdbc.update("DELETE FROM ai_routing_policies");
		jdbc.update("DELETE FROM ai_provider_prices");
		jdbc.update("DELETE FROM ai_provider_budgets");
		jdbc.update("DELETE FROM ai_provider_daily_spend");
		jdbc.update("DELETE FROM app_configurations WHERE config_key = 'ai.kill_switch'");
		jdbc.update("UPDATE ai_providers SET provider_status = 'DISABLED', dpa_signed_at = NULL, "
				+ "zero_data_retention = TRUE WHERE provider_code IN ('mock', 'wiremock-http')");
	}

	void policy(String provider, int priority, int weight, boolean zdr) {
		jdbc.update("""
				INSERT INTO ai_routing_policies (feature_code, source_language_id, destination_language_id, model_id,
				       priority, traffic_weight, requires_zero_retention, is_enabled, valid_from)
				SELECT 'TRANSLATE', s.id, d.id, m.id, ?, ?, ?, TRUE, NOW() - INTERVAL '1 hour'
				  FROM ai_models m JOIN ai_providers p ON p.id = m.provider_id
				 CROSS JOIN supported_languages s CROSS JOIN supported_languages d
				 WHERE p.provider_code = ? AND m.model_code = 'mock-mt-1' AND s.language_code = 'en'
				   AND d.language_code = 'am'
				""", priority, weight, zdr, provider);
	}

	RouteResult<TranslateResult> translate(DataPolicyRequirement dp) {
		return router.route(RouteRequest.translate(TranslateRequest.of("hello", "en", "am"), null, dp));
	}

	@Test
	void policyRowsOrderTheProvidersAndFailoverServesFromTheFallback() {
		policy("mock", 10, 100, false);
		policy("wiremock-http", 20, 100, false);
		assertThat(translate(null).providerCode()).isEqualTo("mock");

		primaryMock.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		RouteResult<TranslateResult> r = translate(null);
		assertThat(r.providerCode()).isEqualTo("wiremock-http");
		assertThat(r.attempts()).extracting(a -> a.providerCode()).containsExactly("mock", "wiremock-http");

		// reversed priorities: the policy, not the registry code order, decides
		jdbc.update("UPDATE ai_routing_policies SET priority = 30 WHERE priority = 10");
		breakers.resetAll();
		primaryMock.setBehavior(Behavior.ok());
		assertThat(translate(null).providerCode()).isEqualTo("wiremock-http");
	}

	@Test
	void breakerOpensThenRoutesStraightToTheFallback() {
		policy("mock", 10, 100, false);
		policy("wiremock-http", 20, 100, false);
		primaryMock.setBehavior(Behavior.failing(FailureMode.TIMEOUT));
		translate(null);
		translate(null);
		int calls = primaryCalls();
		RouteResult<TranslateResult> r = translate(null);
		assertThat(r.providerCode()).isEqualTo("wiremock-http");
		assertThat(r.skipped()).containsExactly(new RouteResult.Skip("mock", SkipReason.CIRCUIT_OPEN));
		assertThat(primaryCalls()).isEqualTo(calls);
	}

	@Test
	void killSwitchRowRemovesAProviderImmediately() {
		policy("mock", 10, 100, false);
		policy("wiremock-http", 20, 100, false);
		assertThat(translate(null).providerCode()).isEqualTo("mock");
		jdbc.update("INSERT INTO app_configurations (config_key, platform_scope, config_value) "
				+ "VALUES ('ai.kill_switch', 'BACKEND', '{\"providers\": [\"mock\"]}'::jsonb)");
		RouteResult<TranslateResult> r = translate(null);
		assertThat(r.providerCode()).isEqualTo("wiremock-http");
		assertThat(r.skipped()).extracting(RouteResult.Skip::reason).containsExactly(SkipReason.KILL_SWITCH_PROVIDER);

		jdbc.update("UPDATE app_configurations SET config_value = '{\"features\": [\"TRANSLATE\"]}'::jsonb "
				+ "WHERE config_key = 'ai.kill_switch'");
		assertThat(translate(null).outcome()).isEqualTo(Outcome.PROVIDER_UNAVAILABLE);
		jdbc.update("UPDATE app_configurations SET is_active = FALSE WHERE config_key = 'ai.kill_switch'");
		assertThat(translate(null).providerCode()).isEqualTo("mock");
	}

	@Test
	void costCapReadsPricesAndBudgetsFromTheDatabaseAndBooksSpend() {
		policy("mock", 10, 100, false);
		policy("wiremock-http", 20, 100, false);
		jdbc.update("INSERT INTO ai_provider_prices (provider_id, feature_code, price_unit, unit_price_micros) "
				+ "SELECT id, NULL, 'INPUT_CHARACTER', 20 FROM ai_providers WHERE provider_code = 'mock'");
		jdbc.update("INSERT INTO ai_provider_budgets (provider_id, daily_soft_limit_micros, daily_hard_limit_micros) "
				+ "SELECT id, 150, 250 FROM ai_providers WHERE provider_code = 'mock'");

		for (int i = 0; i < 3; i++) {
			assertThat(translate(null).providerCode()).isEqualTo("mock"); // 100 micros each
		}
		assertThat(jdbc.queryForObject("""
				SELECT s.cost_micros FROM ai_provider_daily_spend s JOIN ai_providers p ON p.id = s.provider_id
				 WHERE p.provider_code = 'mock' AND s.spend_date = (NOW() AT TIME ZONE 'UTC')::date""",
				BigDecimal.class)).isEqualByComparingTo("300");
		RouteResult<TranslateResult> r = translate(null);
		assertThat(r.providerCode()).isEqualTo("wiremock-http");
		assertThat(r.skipped()).containsExactly(new RouteResult.Skip("mock", SkipReason.COST_CAP));
		assertThat(primaryCalls()).isEqualTo(3);

		jdbc.update("UPDATE ai_provider_budgets SET daily_hard_limit_micros = 10000");
		assertThat(translate(null).providerCode()).isEqualTo("mock");
	}

	@Test
	void zeroRetentionRequirementAndDegradedLastResortFollowTheProviderRows() {
		policy("mock", 10, 100, false);
		policy("wiremock-http", 20, 100, false);
		jdbc.update("UPDATE ai_providers SET zero_data_retention = FALSE WHERE provider_code = 'mock'");
		assertThat(translate(DataPolicyRequirement.ZERO_RETENTION).providerCode()).isEqualTo("wiremock-http");
		assertThat(primaryCalls()).isZero();

		jdbc.update("UPDATE ai_providers SET zero_data_retention = FALSE WHERE provider_code = 'wiremock-http'");
		RouteResult<TranslateResult> none = translate(DataPolicyRequirement.ZERO_RETENTION);
		assertThat(none.outcome()).isEqualTo(Outcome.ZERO_RETENTION_UNAVAILABLE);
		assertThat(none.contractCode()).isEqualTo("ZERO_RETENTION_UNAVAILABLE");

		// DEGRADED primary: not used while the ACTIVE fallback serves, used as the last resort when it fails
		jdbc.update("UPDATE ai_providers SET provider_status = 'DEGRADED' WHERE provider_code = 'mock'");
		assertThat(translate(null).providerCode()).isEqualTo("wiremock-http");
		assertThat(primaryCalls()).isZero();
		fallbackMock.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		RouteResult<TranslateResult> last = translate(null);
		assertThat(last.providerCode()).isEqualTo("mock");
		assertThat(last.servedByLastResort()).isTrue();
	}

	@Test
	void withoutPolicyRowsTheRegistryOrderApplies() {
		assertThat(translate(null).providerCode()).isEqualTo("mock"); // code order, no ai_routing_policies row
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_routing_policies", Integer.class)).isZero();
	}
}
