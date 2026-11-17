package com.qalsmartkeyboard.identity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.identity.token.TestAccessTokens;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/**
 * B2.4 end to end on PostgreSQL with the real security chain: sign in by OTP, use the access token, rotate the refresh
 * token, detect reuse (the family is revoked), the refresh grace window for lost responses (ADR 0007 D8, default 30 s,
 * driven by the injected clock), concurrent retries, log out, expire. {@link RefreshGraceDisabledIT} covers the window
 * switched off.
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@Import({ SessionsITSupport.WhoAmI.class, SessionsITSupport.TestClock.class })
class SessionsIT extends SessionsITSupport {

	static final Duration GRACE = Duration.ofSeconds(30);

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	/** Moves the injected clock past the grace window of every rotation so far. */
	void leaveGraceWindow() {
		clock.advance(GRACE.plusSeconds(1));
	}

	// ---------------------------------------------------------------- access tokens and the chain

	@Test
	void accessTokenFromSignInOpensProtectedEndpointsAndAnonymousOnesNeedNone() throws Exception {
		Session s = signIn(newPhone());
		whoAmI(s.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(s.userId()))
				.andExpect(jsonPath("$.sessionId").value(s.sessionId()));
		mvc.perform(get("/api/v1/it-whoami")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.correlationId").exists());
		mvc.perform(get("/api/v1/config")).andExpect(status().isOk());
		// unknown paths are not revealed to anonymous callers
		mvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());

		String expired = TestAccessTokens.issuedAt(tokens, Instant.now().minusSeconds(3600), UUID.fromString(s.userId()),
				UUID.fromString(s.sessionId()));
		whoAmI(expired).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
	}

	@Test
	void sessionRowStoresOnlyTheHashOfTheRefreshToken() throws Exception {
		Session s = signIn(newPhone());
		List<Map<String, Object>> rows = family(s.sessionId());
		assertThat(rows).hasSize(1);
		Map<String, Object> row = rows.get(0);
		assertThat(row.get("refresh_token_hash")).isEqualTo(sha256(s.refreshToken()));
		assertThat(row.get("session_status")).isEqualTo("ACTIVE");
		assertThat(row.get("last_used_at")).as("never presented yet").isNull();
		assertThat(row.values()).noneMatch(v -> v != null && v.toString().contains(s.refreshToken()));
		assertThat(s.refreshToken()).hasSize(43); // 256 bits, base64url
	}

	// ---------------------------------------------------------------- rotation

	@Test
	void refreshRotatesWithinTheFamily() throws Exception {
		Session first = signIn(newPhone());
		Session second = session(refresh(first.refreshToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer")).andExpect(jsonPath("$.user.id").value(first.userId()))
				.andReturn());
		assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
		assertThat(second.sessionId()).isEqualTo(first.sessionId());
		whoAmI(second.accessToken()).andExpect(status().isOk());

		Map<String, Object> old = row(first.refreshToken());
		Map<String, Object> next = row(second.refreshToken());
		assertThat(family(first.sessionId())).hasSize(2);
		assertThat(old.get("session_status")).isEqualTo("ROTATED");
		assertThat(old.get("replaced_by_session_id")).isEqualTo(next.get("id"));
		assertThat(old.get("rotated_at")).isNotNull();
		assertThat(old.get("last_used_at")).isNotNull();
		assertThat(next.get("session_status")).isEqualTo("ACTIVE");
		assertThat(next.get("last_used_at")).isNull();

		// and again: every use rotates
		session(refresh(second.refreshToken()).andExpect(status().isOk()).andReturn());
		assertThat(family(first.sessionId())).hasSize(3);
	}

	/** The step's "done when": presenting an already-rotated token (outside the grace window) revokes the family. */
	@Test
	void replayOfARotatedRefreshTokenRevokesTheFamily() throws Exception {
		String phone = newPhone();
		Session original = signIn(phone);
		Session rotated = session(refresh(original.refreshToken()).andExpect(status().isOk()).andReturn());
		Session otherDevice = signIn(phone); // a second, independent family of the same user
		leaveGraceWindow();

		try (CapturedLogs logs = new CapturedLogs()) {
			refresh(original.refreshToken()).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
			assertThat(logs.texts()).noneMatch(t -> t != null
					&& (t.contains(original.refreshToken()) || t.contains(rotated.refreshToken())));
		}
		// the legitimate-looking successor is dead too
		refresh(rotated.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		// replaying again keeps answering "reused"
		refresh(original.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));

		assertThat(activeRows(original.sessionId())).isZero();
		assertThat(row(original.refreshToken()).get("reuse_detected_at")).isNotNull();
		assertThat(row(rotated.refreshToken()).get("session_status")).isEqualTo("REVOKED");
		assertThat(row(rotated.refreshToken()).get("revoked_reason")).isEqualTo("TOKEN_REUSE");
		assertThat(reuseEvents(original.sessionId())).as("one event per incident").isEqualTo(1);

		// another sign-in of the same user is not affected
		refresh(otherDevice.refreshToken()).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- grace window (ADR 0007 D8)

	@Test
	void retryWithinTheGraceWindowRotatesAgainAndKillsTheUnusedSuccessor() throws Exception {
		Session s = signIn(newPhone());
		Session lost = session(refresh(s.refreshToken()).andExpect(status().isOk()).andReturn()); // response "lost"
		clock.advance(GRACE.minusSeconds(5));

		Session retried = session(refresh(s.refreshToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.user.id").value(s.userId())).andReturn());
		assertThat(retried.sessionId()).isEqualTo(s.sessionId());
		assertThat(retried.refreshToken()).isNotIn(s.refreshToken(), lost.refreshToken());
		whoAmI(retried.accessToken()).andExpect(status().isOk());

		Map<String, Object> presented = row(s.refreshToken());
		Map<String, Object> superseded = row(lost.refreshToken());
		Map<String, Object> current = row(retried.refreshToken());
		assertThat(family(s.sessionId())).hasSize(3);
		assertThat(presented.get("session_status")).isEqualTo("ROTATED");
		assertThat(presented.get("replaced_by_session_id")).as("the chain continues from the retry")
				.isEqualTo(current.get("id"));
		assertThat(presented.get("reuse_detected_at")).isNull();
		assertThat(superseded.get("session_status")).isEqualTo("REVOKED");
		assertThat(superseded.get("revoked_reason")).isEqualTo("SUPERSEDED_RETRY");
		assertThat(current.get("session_status")).isEqualTo("ACTIVE");
		assertThat(reuseEvents(s.sessionId())).isZero();

		// the new successor is a normal token
		Session after = session(refresh(retried.refreshToken()).andExpect(status().isOk()).andReturn());
		assertThat(after.sessionId()).isEqualTo(s.sessionId());
		assertThat(activeRows(s.sessionId())).isOne();
	}

	@Test
	void theWindowIsMeasuredFromTheFirstRotationSoRetriesDoNotExtendIt() throws Exception {
		Session s = signIn(newPhone());
		refresh(s.refreshToken()).andExpect(status().isOk());
		clock.advance(Duration.ofSeconds(20));
		refresh(s.refreshToken()).andExpect(status().isOk()); // within 30 s of the rotation
		clock.advance(Duration.ofSeconds(11)); // 31 s after the rotation, 11 s after the retry
		refresh(s.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
		assertThat(activeRows(s.sessionId())).isZero();
	}

	@Test
	void presentingASupersededSuccessorRevokesTheFamily() throws Exception {
		Session s = signIn(newPhone());
		Session lost = session(refresh(s.refreshToken()).andExpect(status().isOk()).andReturn());
		Session retried = session(refresh(s.refreshToken()).andExpect(status().isOk()).andReturn());

		// the "lost" successor shows up after all: two parties hold tokens of this family
		refresh(lost.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
		assertThat(activeRows(s.sessionId())).isZero();
		assertThat(row(retried.refreshToken()).get("revoked_reason")).isEqualTo("TOKEN_REUSE");
		assertThat(reuseEvents(s.sessionId())).isEqualTo(1);
		refresh(retried.refreshToken()).andExpect(status().isUnauthorized());
	}

	@Test
	void retryAfterTheWindowRevokesTheFamily() throws Exception {
		Session s = signIn(newPhone());
		Session lost = session(refresh(s.refreshToken()).andExpect(status().isOk()).andReturn());
		leaveGraceWindow();

		refresh(s.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
		assertThat(activeRows(s.sessionId())).isZero();
		assertThat(row(lost.refreshToken()).get("revoked_reason")).isEqualTo("TOKEN_REUSE");
		assertThat(reuseEvents(s.sessionId())).isEqualTo(1);
	}

	@Test
	void retryAfterTheSuccessorWasUsedRevokesTheFamily() throws Exception {
		Session s = signIn(newPhone());
		Session next = session(refresh(s.refreshToken()).andExpect(status().isOk()).andReturn());
		Session nextButOne = session(refresh(next.refreshToken()).andExpect(status().isOk()).andReturn());

		// well within the window, but the successor has been used: this is a replay, not a lost response
		refresh(s.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
		assertThat(activeRows(s.sessionId())).isZero();
		assertThat(row(nextButOne.refreshToken()).get("revoked_reason")).isEqualTo("TOKEN_REUSE");
		assertThat(reuseEvents(s.sessionId())).isEqualTo(1);
	}

	@Test
	void retryAfterTheSuccessorWasPresentedWithoutRotatingRevokesTheFamily() throws Exception {
		Session s = signIn(newPhone());
		Session next = session(refresh(s.refreshToken()).andExpect(status().isOk()).andReturn());
		jdbc.update("UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?::uuid", s.userId());
		refresh(next.refreshToken()).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
		jdbc.update("UPDATE users SET account_status = 'ACTIVE' WHERE id = ?::uuid", s.userId());
		assertThat(row(next.refreshToken()).get("last_used_at")).as("presented").isNotNull();

		refresh(s.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
		assertThat(activeRows(s.sessionId())).isZero();
	}

	@Test
	void grandparentTokenRevokesTheFamily() throws Exception {
		Session s = signIn(newPhone());
		Session child = session(refresh(s.refreshToken()).andExpect(status().isOk()).andReturn());
		Session grandchild = session(refresh(child.refreshToken()).andExpect(status().isOk()).andReturn());

		// s is two generations old: its direct successor (child) is ROTATED, so no grace
		refresh(s.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
		assertThat(activeRows(s.sessionId())).isZero();
		refresh(grandchild.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void concurrentGraceRetriesLeaveExactlyOneActiveSuccessor() throws Exception {
		Session s = signIn(newPhone());
		Session lost = session(refresh(s.refreshToken()).andExpect(status().isOk()).andReturn());
		int racers = 8;
		Callable<MvcResult> retry = () -> refresh(s.refreshToken()).andReturn();
		List<MvcResult> done = race(Collections.nCopies(racers, retry));

		// the family lock serialises them: each retry finds the previous one's successor unused and supersedes it
		assertThat(done).allMatch(r -> r.getResponse().getStatus() == 200);
		List<String> issued = new ArrayList<>();
		for (MvcResult r : done) {
			issued.add(JsonPath.read(r.getResponse().getContentAsString(), "$.refreshToken"));
		}
		List<Map<String, Object>> rows = family(s.sessionId());
		assertThat(rows).hasSize(2 + racers);
		List<Map<String, Object>> active = rows.stream().filter(r -> "ACTIVE".equals(r.get("session_status"))).toList();
		assertThat(active).as("exactly one valid successor").hasSize(1);
		assertThat(row(s.refreshToken()).get("replaced_by_session_id")).isEqualTo(active.get(0).get("id"));
		assertThat(rows).filteredOn(r -> "REVOKED".equals(r.get("session_status"))).hasSize(racers)
				.allMatch(r -> "SUPERSEDED_RETRY".equals(r.get("revoked_reason")));
		assertThat(row(lost.refreshToken()).get("revoked_reason")).isEqualTo("SUPERSEDED_RETRY");
		String winner = issued.stream().filter(t -> sha256(t).equals(active.get(0).get("refresh_token_hash")))
				.findFirst().orElseThrow();

		// any other returned token is a superseded successor: presenting it revokes the family
		String loser = issued.stream().filter(t -> !t.equals(winner)).findFirst().orElseThrow();
		refresh(loser).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
		assertThat(activeRows(s.sessionId())).isZero();
		refresh(winner).andExpect(status().isUnauthorized());
	}

	/** A retry racing the first use of the successor: grace then reuse, or reuse; never two valid tokens. */
	@Test
	void retryRacingTheFirstUseOfTheSuccessorNeverLeavesTwoValidTokens() throws Exception {
		for (int round = 0; round < 5; round++) {
			Session s = signIn(newPhone());
			Session next = session(refresh(s.refreshToken()).andExpect(status().isOk()).andReturn());
			Callable<MvcResult> retryOld = () -> refresh(s.refreshToken()).andReturn();
			Callable<MvcResult> useSuccessor = () -> refresh(next.refreshToken()).andReturn();
			List<MvcResult> done = race(List.of(retryOld, useSuccessor));

			assertThat(done).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(200, 401);
			MvcResult rejected = done.stream().filter(r -> r.getResponse().getStatus() == 401).findFirst().orElseThrow();
			assertThat(rejected.getResponse().getContentAsString()).contains("REFRESH_TOKEN_REUSED");
			// either order ends with the family revoked, including the token the 200 handed out
			assertThat(activeRows(s.sessionId())).isZero();
			assertThat(reuseEvents(s.sessionId())).isEqualTo(1);
			String handedOut = JsonPath.read(done.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst()
					.orElseThrow().getResponse().getContentAsString(), "$.refreshToken");
			refresh(handedOut).andExpect(status().isUnauthorized());
		}
	}

	// ---------------------------------------------------------------- logout, expiry, status

	@Test
	void logoutRevokesTheCallersFamilyAndAllDevicesRevokesEveryFamily() throws Exception {
		String phone = newPhone();
		Session a = signIn(phone);
		Session b = signIn(phone);
		Session c = signIn(phone);

		mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + a.accessToken()))
				.andExpect(status().isNoContent());
		refresh(a.refreshToken()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		assertThat(family(a.sessionId())).allMatch(r -> "LOGOUT".equals(r.get("revoked_reason")));
		Session b2 = session(refresh(b.refreshToken()).andExpect(status().isOk()).andReturn());

		mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + b2.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("{\"allDevices\":true}")).andExpect(status().isNoContent());
		refresh(b2.refreshToken()).andExpect(status().isUnauthorized());
		refresh(c.refreshToken()).andExpect(status().isUnauthorized());
		// within the window, but b's successor b2 was logged out: no grace for a dead family
		refresh(b.refreshToken()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
		assertThat(activeRows(b.sessionId())).isZero();
		// logging out twice is harmless (the access token is stateless and still valid for its lifetime)
		mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + a.accessToken()))
				.andExpect(status().isNoContent());
		mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
	}

	@Test
	void expiredOrUnknownRefreshTokensAreRejected() throws Exception {
		Session s = signIn(newPhone());
		jdbc.update("UPDATE user_sessions SET created_at = now() - interval '61 days', "
				+ "expires_at = now() - interval '1 day' WHERE token_family_id = ?::uuid", s.sessionId());
		refresh(s.refreshToken()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
		assertThat(family(s.sessionId()).get(0).get("session_status")).isEqualTo("EXPIRED");
		refresh(s.refreshToken()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

		refresh("not-a-token-we-issued").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		refresh("").andExpect(status().isBadRequest());
	}

	@Test
	void suspendedUserCannotRefresh() throws Exception {
		Session s = signIn(newPhone());
		jdbc.update("UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?::uuid", s.userId());
		refresh(s.refreshToken()).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
		jdbc.update("UPDATE users SET account_status = 'ACTIVE' WHERE id = ?::uuid", s.userId());
		refresh(s.refreshToken()).andExpect(status().isOk());
	}
}
