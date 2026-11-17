package com.qalsmartkeyboard.identity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.identity.token.TestAccessTokens;
import com.qalsmartkeyboard.notifications.OtpMessage;
import com.qalsmartkeyboard.notifications.OtpSender;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/**
 * B2.4 end to end on PostgreSQL with the real security chain: sign in by OTP, use the access token, rotate the refresh
 * token, detect reuse (the family is revoked), race concurrent refreshes, log out, expire.
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@Import(SessionsIT.WhoAmI.class)
class SessionsIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	/** A protected endpoint (no real one exists before B2.5). */
	@RestController
	static class WhoAmI {
		@GetMapping("/api/v1/it-whoami")
		Map<String, Object> me(@AuthenticationPrincipal AuthenticatedUser user) {
			return Map.of("userId", user.userId().toString(), "sessionId", user.sessionId().toString());
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@MockitoSpyBean
	OtpSender sender;

	record Session(String userId, String sessionId, String accessToken, String refreshToken) {
	}

	// ---------------------------------------------------------------- helpers

	static String randomIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
	}

	static String newPhone() {
		return "+2517" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
	}

	/** Phone OTP sign-in (anonymous endpoints) → session. */
	Session signIn(String phone) throws Exception {
		String ip = randomIp();
		// a repeated sign-in of one number must not hit the resend cooldown: move earlier challenges into the past
		jdbc.update("UPDATE otp_challenges SET created_at = created_at - interval '2 minutes', "
				+ "expires_at = expires_at - interval '2 minutes'");
		MvcResult started = mvc.perform(post("/api/v1/auth/otp/start").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"" + phone + "\"}"))
				.andExpect(status().isAccepted()).andReturn();
		String challenge = JsonPath.read(started.getResponse().getContentAsString(), "$.challengeId");
		ArgumentCaptor<OtpMessage> sent = ArgumentCaptor.forClass(OtpMessage.class);
		verify(sender, atLeastOnce()).send(sent.capture());
		String code = sent.getAllValues().stream().filter(m -> m.phoneE164().equals(phone)).reduce((a, b) -> b)
				.orElseThrow().code();
		MvcResult verified = mvc.perform(post("/api/v1/auth/otp/verify").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON)
				.content("{\"challengeId\":\"" + challenge + "\",\"code\":\"" + code + "\",\"ageConfirmed\":true}"))
				.andExpect(status().isOk()).andReturn();
		return session(verified);
	}

	Session session(MvcResult result) throws Exception {
		String body = result.getResponse().getContentAsString();
		String access = JsonPath.read(body, "$.accessToken");
		return new Session(JsonPath.read(body, "$.user.id"), tokens.decoder().decode(access).getClaimAsString("sid"),
				access, JsonPath.read(body, "$.refreshToken"));
	}

	ResultActions refresh(String refreshToken) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

	ResultActions whoAmI(String accessToken) throws Exception {
		return mvc.perform(get("/api/v1/it-whoami").header("Authorization", "Bearer " + accessToken));
	}

	List<Map<String, Object>> family(String sessionId) {
		return jdbc.queryForList("SELECT * FROM user_sessions WHERE token_family_id = ?::uuid ORDER BY created_at",
				sessionId);
	}

	static String sha256(String token) {
		return RefreshTokens.hash(token);
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

		List<Map<String, Object>> rows = family(first.sessionId());
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0).get("session_status")).isEqualTo("ROTATED");
		assertThat(rows.get(0).get("replaced_by_session_id")).isEqualTo(rows.get(1).get("id"));
		assertThat(rows.get(0).get("rotated_at")).isNotNull();
		assertThat(rows.get(1).get("session_status")).isEqualTo("ACTIVE");
		assertThat(rows.get(1).get("refresh_token_hash")).isEqualTo(sha256(second.refreshToken()));

		// and again: every use rotates
		session(refresh(second.refreshToken()).andExpect(status().isOk()).andReturn());
		assertThat(family(first.sessionId())).hasSize(3);
	}

	/** The step's "done when": presenting an already-rotated token revokes the whole family. */
	@Test
	void replayOfARotatedRefreshTokenRevokesTheFamily() throws Exception {
		String phone = newPhone();
		Session original = signIn(phone);
		Session rotated = session(refresh(original.refreshToken()).andExpect(status().isOk()).andReturn());
		Session otherDevice = signIn(phone); // a second, independent family of the same user

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

		List<Map<String, Object>> rows = family(original.sessionId());
		assertThat(rows).noneMatch(r -> "ACTIVE".equals(r.get("session_status")));
		assertThat(rows.get(0).get("reuse_detected_at")).isNotNull();
		assertThat(rows.get(1).get("session_status")).isEqualTo("REVOKED");
		assertThat(rows.get(1).get("revoked_reason")).isEqualTo("TOKEN_REUSE");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
				Integer.class, SessionService.REUSE_EVENT, original.sessionId())).as("one event per incident").isEqualTo(1);

		// another sign-in of the same user is not affected
		refresh(otherDevice.refreshToken()).andExpect(status().isOk());
	}

	@Test
	void concurrentRefreshOfOneTokenNeverMintsTwoValidSuccessors() throws Exception {
		Session s = signIn(newPhone());
		int racers = 8;
		ExecutorService pool = Executors.newFixedThreadPool(racers);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<MvcResult>> results = new ArrayList<>();
		for (int i = 0; i < racers; i++) {
			results.add(pool.submit(() -> {
				go.await();
				return refresh(s.refreshToken()).andReturn();
			}));
		}
		go.countDown();
		List<MvcResult> done = new ArrayList<>();
		for (Future<MvcResult> f : results) {
			done.add(f.get(60, TimeUnit.SECONDS));
		}
		pool.shutdown();

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
