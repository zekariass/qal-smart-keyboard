package com.qalsmartkeyboard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T8.3 against the V30 seed in PostgreSQL, through the whole stack: {@link StaticFxRateSource} SQL, the service and the
 * endpoint behind the real security chain (anonymous).
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class FxRatesIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void restoreSeed() {
		jdbc.update("DELETE FROM fx_rate_snapshots WHERE source <> 'seed'");
		jdbc.update("UPDATE fx_rate_snapshots SET as_of = now()");
	}

	@Test
	void theSeedIsServedFreshAndLabelledAsSeed() throws Exception {
		mvc.perform(get("/api/v1/tools/fx-rates")).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "max-age=3600, public"))
				.andExpect(jsonPath("$.base").value("USD")).andExpect(jsonPath("$.source").value("seed"))
				.andExpect(jsonPath("$.stale").value(false)).andExpect(jsonPath("$.rates.ETB").isNumber())
				.andExpect(jsonPath("$.rates.USD").doesNotExist());
		List<String> quotes = jdbc.queryForList("SELECT quote_currency FROM fx_rate_snapshots ORDER BY 1", String.class);
		assertTrue(quotes.containsAll(Set.of("ETB", "EUR", "GBP", "AED", "SAR", "KES", "CAD", "AUD", "CNY", "INR", "JPY")));
		assertEquals(11, quotes.size());
	}

	@Test
	void theNewestSnapshotWinsAndAnAgedOneIsStale() throws Exception {
		jdbc.update("UPDATE fx_rate_snapshots SET as_of = now() - interval '3 days'");
		mvc.perform(get("/api/v1/tools/fx-rates")).andExpect(status().isOk()).andExpect(jsonPath("$.stale").value(true));
		jdbc.update("""
				INSERT INTO fx_rate_snapshots (base_currency, quote_currency, rate, as_of, source)
				VALUES ('USD', 'ETB', 160.5, now(), 'test')""");
		mvc.perform(get("/api/v1/tools/fx-rates")).andExpect(status().isOk()).andExpect(jsonPath("$.stale").value(false))
				.andExpect(jsonPath("$.source").value("test")).andExpect(jsonPath("$.rates.ETB").value(160.5))
				.andExpect(jsonPath("$.rates.EUR").doesNotExist());
	}
}
