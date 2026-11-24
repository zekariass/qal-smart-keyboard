package com.qalsmartkeyboard.tools;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.qalsmartkeyboard.identity.security.ApiSecurityConfiguration;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.web.ServletProblemResponseWriter;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * T8.3: {@code GET /api/v1/tools/fx-rates} against {@code contracts/openapi.yaml}, behind the real security chain
 * (anonymous allowed, valid token allowed, invalid token 401), with a fixed clock for the stale flag.
 */
@WebMvcTest(FxRatesController.class)
@ContractValidated
@Import({ ApiSecurityConfiguration.class, ServletProblemResponseWriter.class, FxRatesService.class,
		FxRatesControllerTest.Fixtures.class })
class FxRatesControllerTest {

	static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
	static final AtomicReference<Optional<FxRateSnapshot>> SNAPSHOT = new AtomicReference<>();

	@TestConfiguration(proxyBeanMethods = false)
	static class Fixtures {
		@Bean
		FxRateSource fxRateSource() {
			return SNAPSHOT::get;
		}

		@Bean
		FxProperties fxProperties() {
			return new FxProperties(Duration.ofHours(48));
		}

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	AccessTokenService tokens;

	@BeforeEach
	void snapshot() {
		SNAPSHOT.set(Optional.of(new FxRateSnapshot("USD", NOW.minus(Duration.ofHours(5)),
				Map.of("ETB", new BigDecimal("155.0000000000"), "EUR", new BigDecimal("0.8600000000")), "seed")));
	}

	String bearer() {
		return "Bearer " + tokens.issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "REGISTERED").value();
	}

	@Test
	void anonymousGetsTheContractShapedSnapshotWithPublicCaching() throws Exception {
		mvc.perform(get("/api/v1/tools/fx-rates")).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "max-age=3600, public"))
				.andExpect(jsonPath("$.base").value("USD")).andExpect(jsonPath("$.asOf").value("2026-11-24T07:00:00Z"))
				.andExpect(jsonPath("$.rates.ETB").value(155.0)).andExpect(jsonPath("$.rates.EUR").value(0.86))
				.andExpect(jsonPath("$.source").value("seed")).andExpect(jsonPath("$.stale").value(false));
	}

	@Test
	void aValidTokenIsAlsoAccepted() throws Exception {
		mvc.perform(get("/api/v1/tools/fx-rates").header("Authorization", bearer())).andExpect(status().isOk());
	}

	@Test
	void anInvalidTokenIs401() throws Exception {
		mvc.perform(get("/api/v1/tools/fx-rates").header("Authorization", "Bearer not-a-token"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void anOldSnapshotIsStale() throws Exception {
		SNAPSHOT.set(Optional.of(new FxRateSnapshot("USD", NOW.minus(Duration.ofHours(49)),
				Map.of("ETB", new BigDecimal("155")), "seed")));
		mvc.perform(get("/api/v1/tools/fx-rates")).andExpect(status().isOk()).andExpect(jsonPath("$.stale").value(true));
	}

	@Test
	void noSnapshotIs503Problem() throws Exception {
		SNAPSHOT.set(Optional.empty());
		mvc.perform(get("/api/v1/tools/fx-rates")).andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
	}
}
