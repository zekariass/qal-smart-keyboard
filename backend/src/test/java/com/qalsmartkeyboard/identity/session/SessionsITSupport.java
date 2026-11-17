package com.qalsmartkeyboard.identity.session;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.notifications.OtpMessage;
import com.qalsmartkeyboard.notifications.OtpSender;

/**
 * Shared helpers of the session ITs ({@link SessionsIT}, {@link RefreshGraceDisabledIT}): OTP sign-in through the API,
 * refresh, the family's rows, a protected test endpoint and an injectable clock that runs with real time plus an
 * offset (so the refresh grace window can be left without sleeping). Concrete classes declare their own container and
 * {@code @Import({WhoAmI.class, TestClock.class})}.
 */
abstract class SessionsITSupport {

	/** A protected endpoint (no real one exists before B2.5). */
	@RestController
	static class WhoAmI {
		@GetMapping("/api/v1/it-whoami")
		Map<String, Object> me(@AuthenticationPrincipal AuthenticatedUser user) {
			return Map.of("userId", user.userId().toString(), "sessionId", user.sessionId().toString());
		}
	}

	/** Real time plus an offset that only grows. Every clock user (sessions, access tokens, OTP) sees the same time. */
	static final class OffsetClock extends Clock {
		private volatile Duration offset = Duration.ZERO;

		void advance(Duration by) {
			offset = offset.plus(by);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return Instant.now().plus(offset);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestClock {
		@Bean
		OffsetClock offsetClock() {
			return new OffsetClock();
		}
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	OffsetClock clock;
	@MockitoSpyBean
	OtpSender sender;

	record Session(String userId, String sessionId, String accessToken, String refreshToken) {
	}

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

	/** The row of a refresh token. */
	Map<String, Object> row(String refreshToken) {
		return jdbc.queryForMap("SELECT * FROM user_sessions WHERE refresh_token_hash = ?", sha256(refreshToken));
	}

	long activeRows(String sessionId) {
		return family(sessionId).stream().filter(r -> "ACTIVE".equals(r.get("session_status"))).count();
	}

	int reuseEvents(String sessionId) {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
				Integer.class, SessionService.REUSE_EVENT, sessionId);
	}

	static String sha256(String token) {
		return RefreshTokens.hash(token);
	}

	/** Runs the calls at once (released together by a latch) and returns their results in submission order. */
	static <T> List<T> race(List<Callable<T>> calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.size());
		try {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> call : calls) {
				futures.add(pool.submit(() -> {
					go.await();
					return call.call();
				}));
			}
			go.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> f : futures) {
				results.add(f.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}
}
