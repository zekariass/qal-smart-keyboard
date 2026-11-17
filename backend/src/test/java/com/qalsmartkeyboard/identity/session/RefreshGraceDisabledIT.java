package com.qalsmartkeyboard.identity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;

/**
 * {@code qal.identity.tokens.refresh-reuse-grace=0} switches the grace window off (ADR 0007 D8): every presentation of
 * a rotated token is reuse again, exactly as in B2.4 / SD 9.3, including an immediate retry and concurrent refreshes.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.identity.tokens.refresh-reuse-grace=0" })
@AutoConfigureMockMvc
@Testcontainers
@Import({ SessionsITSupport.WhoAmI.class, SessionsITSupport.TestClock.class })
class RefreshGraceDisabledIT extends SessionsITSupport {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Test
	void immediateRetryWithTheRotatedTokenRevokesTheFamily() throws Exception {
		Session s = signIn(newPhone());
		Session lost = session(refresh(s.refreshToken()).andExpect(status().isOk()).andReturn());

		refresh(s.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
		assertThat(activeRows(s.sessionId())).isZero();
		assertThat(row(lost.refreshToken()).get("revoked_reason")).isEqualTo("TOKEN_REUSE");
		assertThat(reuseEvents(s.sessionId())).isEqualTo(1);
		refresh(lost.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void concurrentRefreshOfOneTokenNeverMintsTwoValidSuccessors() throws Exception {
		Session s = signIn(newPhone());
		int racers = 8;
		Callable<MvcResult> call = () -> refresh(s.refreshToken()).andReturn();
		List<MvcResult> done = race(Collections.nCopies(racers, call));

		long ok = done.stream().filter(r -> r.getResponse().getStatus() == 200).count();
		assertThat(ok).as("exactly one refresh wins").isEqualTo(1);
		for (MvcResult r : done) {
			if (r.getResponse().getStatus() != 200) {
				assertThat(r.getResponse().getStatus()).isEqualTo(401);
				assertThat(r.getResponse().getContentAsString()).contains("REFRESH_TOKEN_REUSED");
			}
		}
		List<Map<String, Object>> rows = family(s.sessionId());
		assertThat(rows).as("one successor only").hasSize(2);
		// SD 9.3: the losers presented an already-rotated token, so the family (incl. the winner's successor) is revoked
		assertThat(rows).noneMatch(r -> "ACTIVE".equals(r.get("session_status")));
		String winnersRefresh = JsonPath.read(done.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst()
				.orElseThrow().getResponse().getContentAsString(), "$.refreshToken");
		refresh(winnersRefresh).andExpect(status().isUnauthorized());
	}
}
