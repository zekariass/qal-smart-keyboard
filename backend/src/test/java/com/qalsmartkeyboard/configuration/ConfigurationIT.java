package com.qalsmartkeyboard.configuration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * B1.6 against real {@code feature_flags} / {@code app_configurations} rows (V1 constraints apply) in PostgreSQL, through
 * the whole stack: repository SQL, snapshot cache, {@code /config} and the 426 gate.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.config.cache-ttl=0s" })
@AutoConfigureMockMvc
@Testcontainers
@Import({ ConfigurationIT.TestConfig.class, ConfigurationIT.GatedController.class })
class ConfigurationIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@RestController
	static class GatedController {
		@GetMapping("/api/v1/it-gated")
		Map<String, Object> ok() {
			return Map.of("ok", true);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestConfig {
		/** Authentication arrives with B2.4; without a chain Spring Boot's default would answer 401. */
		@Bean
		SecurityFilterChain openChain(HttpSecurity http) throws Exception {
			return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).csrf(c -> c.disable()).build();
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.update("DELETE FROM feature_flags");
		jdbc.update("DELETE FROM app_configurations");
	}

	private void config(String key, String scope, int version, String json, boolean active) {
		jdbc.update("INSERT INTO app_configurations (config_key, platform_scope, config_version, config_value, is_active)"
				+ " VALUES (?, ?, ?, ?::jsonb, ?)", key, scope, version, json, active);
	}

	private void flag(String code, boolean on, int pct, String scope, String minVersion, String targeting) {
		jdbc.update("INSERT INTO feature_flags (flag_code, is_enabled, rollout_percentage, platform_scope, min_app_version,"
				+ " targeting) VALUES (?, ?, ?, ?, ?, ?::jsonb)", code, on, pct, scope, minVersion, targeting);
	}

	@Test
	void configIsResolvedFromRealRows() throws Exception {
		config("minSupportedAppVersion", "ALL", 1, "\"1.0.0\"", true);
		config("minSupportedAppVersion", "ANDROID", 2, "\"2.0.0\"", true);
		config("recommendedAppVersion", "ANDROID", 1, "\"2.3.0\"", true);
		config("typing.maxSuggestions", "ALL", 3, "5", true);
		config("typing.maxSuggestions", "ANDROID", 1, "7", true);
		config("old.value", "ALL", 1, "\"stale\"", false); // inactive: not served
		config("backend.only", "BACKEND", 1, "\"secret\"", true); // never served
		flag("smart_reply", true, 100, "ALL", null, "{}");
		flag("android_beta", true, 100, "ANDROID", "2.1.0", "{}");
		flag("ios_only", true, 100, "IOS", null, "{}");
		flag("disabled", false, 100, "ALL", null, "{}");
		flag("half", true, 50, "ALL", null, "{}");
		flag("et_only", true, 100, "ALL", null, "{\"markets\":[\"ET\"]}");

		mvc.perform(get("/api/v1/config").header("X-Client-Platform", "ANDROID").header("X-App-Version", "2.1.0"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.minSupportedAppVersion").value("2.0.0"))
				.andExpect(jsonPath("$.recommendedAppVersion").value("2.3.0"))
				.andExpect(jsonPath("$.configVersion").value(2))
				.andExpect(jsonPath("$.values['typing.maxSuggestions']").value(7))
				.andExpect(jsonPath("$.values['old.value']").doesNotExist())
				.andExpect(jsonPath("$.values['backend.only']").doesNotExist())
				.andExpect(jsonPath("$.values['minSupportedAppVersion']").doesNotExist())
				.andExpect(jsonPath("$.flags.smart_reply").value(true))
				.andExpect(jsonPath("$.flags.android_beta").value(true))
				.andExpect(jsonPath("$.flags.ios_only").value(false)).andExpect(jsonPath("$.flags.disabled").value(false))
				.andExpect(jsonPath("$.flags.half").value(false)).andExpect(jsonPath("$.flags.et_only").value(false))
				.andExpect(jsonPath("$.serverTime").isNotEmpty());

		mvc.perform(get("/api/v1/config").header("X-Client-Platform", "ANDROID").header("X-App-Version", "2.0.9"))
				.andExpect(jsonPath("$.flags.android_beta").value(false));
		mvc.perform(get("/api/v1/config").header("X-Client-Platform", "IOS").header("X-App-Version", "1.0.0"))
				.andExpect(jsonPath("$.minSupportedAppVersion").value("1.0.0"))
				.andExpect(jsonPath("$.values['typing.maxSuggestions']").value(5))
				.andExpect(jsonPath("$.flags.ios_only").value(true));
	}

	@Test
	void emptyTablesGiveAnOpenConfig() throws Exception {
		mvc.perform(get("/api/v1/config")).andExpect(status().isOk())
				.andExpect(jsonPath("$.minSupportedAppVersion").value("0.0.0"))
				.andExpect(jsonPath("$.configVersion").value(0)).andExpect(jsonPath("$.flags").isEmpty())
				.andExpect(jsonPath("$.values").isEmpty());
	}

	@Test
	void oldVersionGets426ProblemUsingTheMinimumStoredInTheDatabase() throws Exception {
		config("minSupportedAppVersion", "ANDROID", 1, "\"2.0.0\"", true);

		mvc.perform(get("/api/v1/it-gated").header("X-Client-Platform", "ANDROID").header("X-App-Version", "1.9.0"))
				.andExpect(status().isUpgradeRequired())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("APP_VERSION_UNSUPPORTED"))
				.andExpect(jsonPath("$.correlationId").isNotEmpty())
				.andExpect(jsonPath("$.minSupportedAppVersion").value("2.0.0"));
		mvc.perform(get("/api/v1/it-gated").header("X-Client-Platform", "ANDROID").header("X-App-Version", "2.0.0"))
				.andExpect(status().isOk());
		// the ANDROID minimum does not apply to iOS
		mvc.perform(get("/api/v1/it-gated").header("X-Client-Platform", "IOS").header("X-App-Version", "1.0.0"))
				.andExpect(status().isOk());
		// raising the minimum takes effect (cache TTL is 0 in this test)
		config("minSupportedAppVersion", "ALL", 1, "\"3.0.0\"", true);
		mvc.perform(get("/api/v1/it-gated").header("X-Client-Platform", "IOS").header("X-App-Version", "1.0.0"))
				.andExpect(status().isUpgradeRequired());
		// /config stays reachable for the old client
		mvc.perform(get("/api/v1/config").header("X-Client-Platform", "ANDROID").header("X-App-Version", "1.9.0"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.minSupportedAppVersion").value("2.0.0"));
	}
}
