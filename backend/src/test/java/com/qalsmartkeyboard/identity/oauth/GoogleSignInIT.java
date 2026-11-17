package com.qalsmartkeyboard.identity.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.RSAKey;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/**
 * B2.2 end to end on PostgreSQL with the real security chain and a <b>fake JWKS</b> (loopback server): Google sign-in
 * creates or finds the account by (provider, subject), never by e-mail, applies the account-status rules, registers the
 * device, issues a working session and keeps tokens, nonces and e-mail addresses out of the logs. Each test uses its own
 * subject and client IP so the in-memory rate-limit buckets do not interfere.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false",
		"qal.identity.oauth.google.client-ids=" + FakeGoogle.CLIENT_ID + ",ios-client.apps.googleusercontent.com",
		"qal.identity.oauth.google.jwks-min-refresh-interval=100ms",
		"qal.identity.oauth.limits.exchange-per-ip.capacity=6", "qal.identity.oauth.limits.exchange-per-ip.period=1h",
		"qal.identity.oauth.limits.exchange-per-device.capacity=3", "qal.identity.oauth.limits.exchange-per-device.period=1h" })
@AutoConfigureMockMvc
@Testcontainers
class GoogleSignInIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final FakeGoogle GOOGLE = new FakeGoogle();
	static final RSAKey KEY = FakeGoogle.newKey("it-kid-1");
	static final RSAKey ATTACKER = FakeGoogle.newKey("it-kid-1");

	static {
		GOOGLE.publish(KEY);
	}

	@DynamicPropertySource
	static void fakeJwks(DynamicPropertyRegistry registry) {
		registry.add("qal.identity.oauth.google.jwks-uri", () -> GOOGLE.jwksUri().toString());
	}

	@AfterAll
	static void stop() {
		GOOGLE.close();
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;

	// ---------------------------------------------------------------- helpers

	static String newSubject() {
		return "1" + String.format("%020d", ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
	}

	static String newIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
	}

	static String idToken(String subject, String nonce) {
		return FakeGoogle.sign(KEY, FakeGoogle.claims(subject, nonce).build());
	}

	ResultActions exchange(String provider, String body, String ip) throws Exception {
		return mvc.perform(post("/api/v1/auth/oauth/" + provider).with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	static String body(String idToken, String nonce, Boolean ageConfirmed, UUID installation) {
		StringBuilder b = new StringBuilder("{\"idToken\":\"" + idToken + "\",\"nonce\":\"" + nonce + "\"");
		if (ageConfirmed != null) {
			b.append(",\"ageConfirmed\":").append(ageConfirmed);
		}
		if (installation != null) {
			b.append(",\"device\":{\"installationId\":\"").append(installation).append("\",\"platform\":\"ANDROID\"}");
		}
		return b.append('}').toString();
	}

	ResultActions google(String subject, String nonce, Boolean ageConfirmed, String ip) throws Exception {
		return exchange("GOOGLE", body(idToken(subject, nonce), nonce, ageConfirmed, null), ip);
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	UUID userOf(String subject) {
		return jdbc.queryForObject(
				"SELECT user_id FROM user_auth_identities WHERE identity_provider = 'GOOGLE' AND provider_subject = ?",
				UUID.class, subject);
	}

	// ---------------------------------------------------------------- flow

	@Test
	void firstSignInCreatesTheAccountAndAWorkingSession() throws Exception {
		String sub = newSubject();
		MvcResult r = google(sub, "nonce-a", true, newIp()).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.isNewUser").value(true)).andExpect(jsonPath("$.user.accountType").value("REGISTERED"))
				.andExpect(jsonPath("$.user.accountStatus").value("ACTIVE"))
				.andExpect(jsonPath("$.user.displayName").value("Test User"))
				.andExpect(jsonPath("$.user.phoneNumberMasked").doesNotExist()).andReturn();
		String body = r.getResponse().getContentAsString();
		UUID userId = UUID.fromString(JsonPath.read(body, "$.user.id"));
		assertThat(userOf(sub)).isEqualTo(userId);

		Map<String, Object> user = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId);
		assertThat(user).containsEntry("account_type", "REGISTERED").containsEntry("account_status", "ACTIVE");
		assertThat(user.get("age_confirmed_at")).isNotNull();
		assertThat(user.get("last_login_at")).isNotNull();
		// the provider e-mail is kept on the identity row only; users.email is not claimed
		assertThat(user.get("email")).isNull();
		assertThat(user.get("email_normalized")).isNull();
		assertThat(user.get("phone_e164")).isNull();
		Map<String, Object> identity = jdbc.queryForMap(
				"SELECT * FROM user_auth_identities WHERE identity_provider = 'GOOGLE' AND provider_subject = ?", sub);
		assertThat(identity).containsEntry("provider_email", sub + "@example.test");
		assertThat(identity.get("last_used_at")).isNotNull();

		// the session works: access token on a protected endpoint, refresh token rotates
		String access = JsonPath.read(body, "$.accessToken");
		String refresh = JsonPath.read(body, "$.refreshToken");
		assertThat(tokens.decoder().decode(access).getSubject()).isEqualTo(userId.toString());
		assertThat(tokens.decoder().decode(access).getClaimAsString("accountType")).isEqualTo("REGISTERED");
		mvc.perform(get("/api/v1/devices").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
		mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refresh + "\"}")).andExpect(status().isOk());
		assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = ?", userId)).isEqualTo(2);
	}

	@Test
	void theSecondSignInFindsTheSameAccountByProviderAndSubject() throws Exception {
		String sub = newSubject();
		String ip = newIp();
		String first = JsonPath.read(google(sub, "n1", true, ip).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(), "$.user.id");
		// no ageConfirmed needed for an existing account; a different e-mail claim does not matter
		String token = FakeGoogle.sign(KEY, FakeGoogle.claims(sub, "n2").claim("email", "changed@example.test")
				.claim("name", "Renamed").build());
		MvcResult second = exchange("GOOGLE", body(token, "n2", null, null), ip).andExpect(status().isOk())
				.andExpect(jsonPath("$.isNewUser").value(false)).andExpect(jsonPath("$.user.id").value(first))
				.andExpect(jsonPath("$.user.displayName").value("Test User")).andReturn();
		assertThat(second.getResponse().getContentAsString()).doesNotContain("changed@example.test");
		assertThat(count("SELECT count(*) FROM user_auth_identities WHERE provider_subject = ?", sub)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT provider_email FROM user_auth_identities WHERE provider_subject = ?", String.class, sub))
				.isEqualTo("changed@example.test");
		assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = ?::uuid", first)).isEqualTo(2);
	}

	@Test
	void anUnverifiedEmailIsNotStored() throws Exception {
		String sub = newSubject();
		String token = FakeGoogle.sign(KEY, FakeGoogle.claims(sub, "n").claim("email_verified", false).build());
		exchange("GOOGLE", body(token, "n", true, null), newIp()).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT provider_email FROM user_auth_identities WHERE provider_subject = ?",
				String.class, sub)).isNull();
	}

	@Test
	void aNewAccountNeedsTheAgeConfirmationAndNothingIsWrittenWithout() throws Exception {
		String sub = newSubject();
		String ip = newIp();
		int usersBefore = count("SELECT count(*) FROM users");
		google(sub, "n", null, ip).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("ageConfirmed"));
		google(sub, "n", false, ip).andExpect(status().isBadRequest());
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(usersBefore);
		assertThat(count("SELECT count(*) FROM user_auth_identities WHERE provider_subject = ?", sub)).isZero();
		google(sub, "n", true, ip).andExpect(status().isOk()).andExpect(jsonPath("$.isNewUser").value(true));
	}

	@Test
	void anEmailThatMatchesAnotherAccountNeverLinksToIt() throws Exception {
		String victimEmail = "victim-" + newSubject() + "@example.test";
		UUID victim = UUID.randomUUID();
		jdbc.update("INSERT INTO users (id, account_type, email, email_normalized, email_verified_at, phone_e164, "
				+ "account_status, age_confirmed_at) VALUES (?, 'REGISTERED', ?, ?, now(), '+251911000111', 'ACTIVE', now())",
				victim, victimEmail, victimEmail);
		String sub = newSubject();
		String token = FakeGoogle.sign(KEY, FakeGoogle.claims(sub, "n").claim("email", victimEmail).build());
		MvcResult r = exchange("GOOGLE", body(token, "n", true, null), newIp()).andExpect(status().isOk())
				.andExpect(jsonPath("$.isNewUser").value(true)).andReturn();
		UUID created = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.user.id"));
		assertThat(created).isNotEqualTo(victim);
		assertThat(userOf(sub)).isEqualTo(created);
		assertThat(count("SELECT count(*) FROM user_auth_identities WHERE user_id = ?", victim)).isZero();
		assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, victim)).isEqualTo(victimEmail);
		assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, created)).isNull();
	}

	// ---------------------------------------------------------------- account status (B2.4 rules)

	@Test
	void accountStatusRulesMatchPhoneSignIn() throws Exception {
		String suspended = newSubject();
		google(suspended, "n", true, newIp()).andExpect(status().isOk());
		jdbc.update("UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?", userOf(suspended));
		int sessions = count("SELECT count(*) FROM user_sessions WHERE user_id = ?", userOf(suspended));
		google(suspended, "n", true, newIp()).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
		assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = ?", userOf(suspended))).isEqualTo(sessions);

		for (String dead : List.of("DELETED", "MERGED")) {
			String sub = newSubject();
			google(sub, "n", true, newIp()).andExpect(status().isOk());
			jdbc.update("UPDATE users SET account_status = ? WHERE id = ?", dead, userOf(sub));
			google(sub, "n", true, newIp()).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		}

		String pending = newSubject();
		google(pending, "n", true, newIp()).andExpect(status().isOk());
		jdbc.update("UPDATE users SET account_status = 'PENDING' WHERE id = ?", userOf(pending));
		google(pending, "n", true, newIp()).andExpect(status().isOk()).andExpect(jsonPath("$.user.accountStatus").value("ACTIVE"));

		String deletionPending = newSubject();
		google(deletionPending, "n", true, newIp()).andExpect(status().isOk());
		jdbc.update("UPDATE users SET account_status = 'DELETION_PENDING' WHERE id = ?", userOf(deletionPending));
		google(deletionPending, "n", true, newIp()).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- devices

	@Test
	void aDeviceInTheRequestIsRegisteredAndBoundToTheSession() throws Exception {
		String sub = newSubject();
		UUID installation = UUID.randomUUID();
		MvcResult r = exchange("GOOGLE", body(idToken(sub, "n"), "n", true, installation), newIp())
				.andExpect(status().isOk()).andExpect(jsonPath("$.deviceId").isNotEmpty()).andReturn();
		String json = r.getResponse().getContentAsString();
		UUID userId = UUID.fromString(JsonPath.read(json, "$.user.id"));
		UUID deviceId = UUID.fromString(JsonPath.read(json, "$.deviceId"));
		assertThat(jdbc.queryForMap("SELECT * FROM user_devices WHERE id = ?", deviceId)).containsEntry("user_id", userId)
				.containsEntry("installation_id", installation).containsEntry("platform", "ANDROID");
		assertThat(tokens.decoder().decode(JsonPath.<String>read(json, "$.accessToken")).getClaimAsString("did"))
				.isEqualTo(deviceId.toString());
	}

	// ---------------------------------------------------------------- rejected tokens

	@Test
	void rejectedTokensAre401WithAGenericDetailAndLeaveNothingBehind() throws Exception {
		String sub = newSubject();
		String ip = newIp();
		Instant now = Instant.now();
		int users = count("SELECT count(*) FROM users");
		int sessions = count("SELECT count(*) FROM user_sessions");
		List<String> bad = List.of(
				FakeGoogle.sign(ATTACKER, FakeGoogle.claims(sub, "n").build()),
				FakeGoogle.sign(KEY, FakeGoogle.claims(sub, "n").audience("someone-elses-app").build()),
				FakeGoogle.sign(KEY, FakeGoogle.claims(sub, "n").issuer("https://evil.example").build()),
				FakeGoogle.sign(KEY, FakeGoogle.claims(sub, "n").issueTime(Date.from(now.minusSeconds(7200)))
						.expirationTime(Date.from(now.minusSeconds(600))).build()),
				FakeGoogle.sign(KEY, FakeGoogle.claims(sub, "other-nonce").build()));
		for (String token : bad) {
			MvcResult r = exchange("GOOGLE", body(token, "n", true, null), ip).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.correlationId").isNotEmpty())
					.andReturn();
			assertThat(r.getResponse().getContentAsString()).doesNotContain(token).doesNotContain(sub)
					.doesNotContain("someone-elses-app").doesNotContain("evil.example");
		}
		exchange("GOOGLE", body("not.a.jwt", "n", true, null), ip).andExpect(status().isUnauthorized());
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(users);
		assertThat(count("SELECT count(*) FROM user_sessions")).isEqualTo(sessions);
	}

	@Test
	void aRotatedSigningKeyIsPickedUpThroughTheJwksRefresh() throws Exception {
		RSAKey rotated = FakeGoogle.newKey("it-kid-rotated");
		google(newSubject(), "n", true, newIp()).andExpect(status().isOk());
		try {
			GOOGLE.publish(KEY, rotated);
			Thread.sleep(250);
			String token = FakeGoogle.sign(rotated, FakeGoogle.claims(newSubject(), "n").build());
			exchange("GOOGLE", body(token, "n", true, null), newIp()).andExpect(status().isOk());
		}
		finally {
			GOOGLE.publish(KEY);
		}
	}

	@Test
	void appleAndUnknownProvidersAreRefusedWith400() throws Exception {
		String ip = newIp();
		exchange("APPLE", body(idToken(newSubject(), "n"), "n", true, null), ip).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("provider"))
				.andExpect(jsonPath("$.errors[0].code").value("PROVIDER_NOT_SUPPORTED"));
		exchange("FACEBOOK", body(idToken(newSubject(), "n"), "n", true, null), ip).andExpect(status().isBadRequest());
	}

	@Test
	void aBadBearerTokenIs401EvenOnThisAnonymousOperation() throws Exception {
		mvc.perform(post("/api/v1/auth/oauth/GOOGLE").header("Authorization", "Bearer garbage")
				.contentType(MediaType.APPLICATION_JSON).content(body(idToken(newSubject(), "n"), "n", true, null)))
				.andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- limits, concurrency, logs

	@Test
	void requestsAreRateLimitedPerIpAndPerDevice() throws Exception {
		String ip = newIp();
		for (int i = 0; i < 6; i++) {
			exchange("GOOGLE", body("garbage", "n", true, null), ip).andExpect(status().isUnauthorized());
		}
		exchange("GOOGLE", body("garbage", "n", true, null), ip).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().exists("Retry-After"))
				.andExpect(header().exists("RateLimit-Limit"));

		UUID installation = UUID.randomUUID();
		for (int i = 0; i < 3; i++) {
			exchange("GOOGLE", body("garbage", "n", true, installation), newIp()).andExpect(status().isUnauthorized());
		}
		exchange("GOOGLE", body("garbage", "n", true, installation), newIp()).andExpect(status().isTooManyRequests());
	}

	@Test
	void simultaneousFirstSignInsOfOneIdentityCreateOneAccount() throws Exception {
		String sub = newSubject();
		String token = idToken(sub, "n");
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> results = new java.util.ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String ip = newIp();
				Callable<Integer> call = () -> {
					start.await();
					return exchange("GOOGLE", body(token, "n", true, null), ip).andReturn().getResponse().getStatus();
				};
				results.add(pool.submit(call));
			}
			start.countDown();
			for (Future<Integer> f : results) {
				assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(200);
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(count("SELECT count(*) FROM user_auth_identities WHERE provider_subject = ?", sub)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM users u JOIN user_auth_identities i ON i.user_id = u.id "
				+ "WHERE i.provider_subject = ?", sub)).isEqualTo(1);
	}

	@Test
	void logsNeverContainTheIdTokenTheNonceTheEmailOrTheSessionTokens() throws Exception {
		String sub = newSubject();
		String ip = newIp();
		String nonce = "secret-nonce-" + sub;
		String good = idToken(sub, nonce);
		String bad = FakeGoogle.sign(ATTACKER, FakeGoogle.claims(sub, nonce).build());
		try (CapturedLogs logs = new CapturedLogs()) {
			exchange("GOOGLE", body(bad, nonce, true, null), ip).andExpect(status().isUnauthorized());
			MvcResult ok = exchange("GOOGLE", body(good, nonce, true, null), ip).andExpect(status().isOk()).andReturn();
			String access = JsonPath.read(ok.getResponse().getContentAsString(), "$.accessToken");
			String refresh = JsonPath.read(ok.getResponse().getContentAsString(), "$.refreshToken");

			List<String> texts = logs.texts();
			assertThat(texts).as("something was logged").isNotEmpty();
			String payload = good.split("\\.")[1];
			assertThat(texts).noneMatch(t -> t != null && (t.contains(good) || t.contains(bad) || t.contains(payload)
					|| t.contains(nonce) || t.contains(sub + "@example.test") || t.contains("Test User")
					|| t.contains(access) || t.contains(refresh) || t.contains(sub)));
		}
	}
}
