package com.qalsmartkeyboard.identity.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.identity.account.UserAccount;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.account.UserAccountStore.PhoneSignIn;
import com.qalsmartkeyboard.identity.attestation.MockAttestationTokens;
import com.qalsmartkeyboard.identity.session.IssuedSession;
import com.qalsmartkeyboard.identity.session.SessionContext;
import com.qalsmartkeyboard.identity.session.SessionIssuer;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/**
 * B2.3 end to end on PostgreSQL with the real security chain and the mock verifiers: attestation nonces (single use,
 * expiry, hashed at rest, bound to the caller and platform, rate limited), guest creation (GUEST account and plan,
 * device, session, refresh), every way to fail attestation (always {@code GUEST_NOT_ALLOWED}, nothing created), replay,
 * concurrency, and {@code POST /devices/{id}/attestations}. {@link GuestFailClosedIT} covers a deployment without a
 * verifier.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false",
		"qal.identity.attestation.limits.nonce-per-ip.capacity=5", "qal.identity.attestation.limits.nonce-per-ip.period=1h",
		"qal.identity.attestation.limits.guest-per-ip.capacity=4", "qal.identity.attestation.limits.guest-per-ip.period=1h",
		"qal.identity.attestation.limits.guest-per-installation.capacity=3",
		"qal.identity.attestation.limits.guest-per-installation.period=1h" })
@AutoConfigureMockMvc
@Testcontainers
class GuestAttestationIT {

	static final String NONCES = "/api/v1/auth/attestation-nonces";
	static final String GUEST = "/api/v1/auth/guest";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	UserAccountStore accounts;
	@Autowired
	SessionIssuer issuer;
	@Autowired
	TransactionTemplate transactions;

	// ---------------------------------------------------------------- helpers

	/** A client address of its own, so the in-memory per-IP buckets of one test never touch another's. */
	static String newIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
	}

	ResultActions nonceRequest(String ip, String platform, String bearer) throws Exception {
		var request = post(NONCES).with(r -> {
			r.setRemoteAddr(ip);
			return r;
		});
		if (platform != null) {
			request.header("X-Client-Platform", platform);
		}
		if (bearer != null) {
			request.header("Authorization", "Bearer " + bearer);
		}
		return mvc.perform(request);
	}

	String nonce() throws Exception {
		return nonceRequest(newIp(), null, null).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString().replaceAll(".*\"nonce\":\"([^\"]+)\".*", "$1");
	}

	static String evidence(String provider, String token, String nonce, String keyId) {
		return "{\"provider\":\"" + provider + "\",\"token\":\"" + token + "\",\"nonce\":\"" + nonce + "\""
				+ (keyId == null ? "" : ",\"keyId\":\"" + keyId + "\"") + "}";
	}

	static String androidEvidence(String nonce) {
		return evidence("PLAY_INTEGRITY", MockAttestationTokens.playIntegrity(nonce), nonce, null);
	}

	static String guestBody(UUID installation, String platform, String attestationJson) {
		return "{\"installationId\":\"" + installation + "\",\"platform\":\"" + platform + "\""
				+ (attestationJson == null ? "" : ",\"attestation\":" + attestationJson) + "}";
	}

	ResultActions guest(String body) throws Exception {
		return guest(body, newIp());
	}

	ResultActions guest(String body, String ip) throws Exception {
		return mvc.perform(post(GUEST).with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).header("X-App-Version", "2.0.3").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	int guestUsers() {
		return jdbc.queryForObject("SELECT count(*) FROM users WHERE account_type = 'GUEST'", Integer.class);
	}

	void assertGuestNotAllowed(ResultActions result) throws Exception {
		result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"))
				.andExpect(jsonPath("$.status").value(403)).andExpect(jsonPath("$.correlationId").exists())
				.andExpect(header().exists("X-Correlation-Id"));
	}

	record Account(UUID userId, String accessToken, String sessionId) {
	}

	Account signUp() {
		return transactions.execute(status -> {
			PhoneSignIn.SignedIn in = (PhoneSignIn.SignedIn) accounts.signInWithPhone(
					"+2517" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)), true,
					Instant.now());
			UserAccount user = in.user();
			IssuedSession s = issuer.issue(user, new SessionContext(null, "10.0.0.1", "it"));
			return new Account(user.id(), s.accessToken(), s.sessionId().toString());
		});
	}

	// ---------------------------------------------------------------- nonces

	@Test
	void nonceIs201With256RandomBitsValidFiveMinutesAndStoredOnlyAsAHash() throws Exception {
		Instant before = Instant.now();
		MvcResult result = nonceRequest(newIp(), "ANDROID", null).andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.nonce").isString())
				.andExpect(jsonPath("$.expiresAt").isString()).andReturn();
		String body = result.getResponse().getContentAsString();
		String nonce = JsonPath.read(body, "$.nonce");
		assertThat(nonce).matches("^[A-Za-z0-9_-]{43}$");
		Instant expiresAt = Instant.parse(JsonPath.read(body, "$.expiresAt"));
		assertThat(Duration.between(before, expiresAt)).isBetween(Duration.ofMinutes(4).plusSeconds(55),
				Duration.ofMinutes(5).plusSeconds(5));

		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM attestation_nonces WHERE nonce_hash = ?",
				AttestationNonceHashes.sha256(nonce));
		assertThat(row).containsEntry("platform", "ANDROID").containsEntry("user_id", null)
				.containsEntry("consumed_at", null);
		// the clear value is nowhere in the table
		assertThat(jdbc.queryForObject("SELECT count(*) FROM attestation_nonces WHERE nonce_hash = ? OR id::text = ?",
				Integer.class, nonce, nonce)).isZero();
		assertThat(nonce()).isNotEqualTo(nonce());
	}

	@Test
	void nonceRequestsAreRateLimitedPerIp() throws Exception {
		String ip = newIp();
		for (int i = 0; i < 5; i++) {
			nonceRequest(ip, null, null).andExpect(status().isCreated());
		}
		nonceRequest(ip, null, null).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().exists("Retry-After"))
				.andExpect(header().string("RateLimit-Limit", "5"));
		nonceRequest(newIp(), null, null).andExpect(status().isCreated());
	}

	@Test
	void anInvalidBearerTokenOnTheNonceEndpointIs401() throws Exception {
		nonceRequest(newIp(), null, "garbage").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	// ---------------------------------------------------------------- guest happy path

	@Test
	void guestWithAValidAttestationGetsAGuestAccountOnTheGuestPlanADeviceAndASession() throws Exception {
		String nonce = nonce();
		UUID installation = UUID.randomUUID();
		MvcResult result = guest(guestBody(installation, "ANDROID", androidEvidence(nonce))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.isNewUser").value(true)).andExpect(jsonPath("$.user.accountType").value("GUEST"))
				.andExpect(jsonPath("$.user.accountStatus").value("ACTIVE")).andExpect(jsonPath("$.deviceId").isString())
				.andReturn();
		String body = result.getResponse().getContentAsString();
		UUID userId = UUID.fromString(JsonPath.read(body, "$.user.id"));
		UUID deviceId = UUID.fromString(JsonPath.read(body, "$.deviceId"));

		// account: GUEST, no identity, no subscription row (the GUEST plan is resolved from the account type, SD 15.5)
		Map<String, Object> user = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId);
		assertThat(user).containsEntry("account_type", "GUEST").containsEntry("account_status", "ACTIVE")
				.containsEntry("email", null).containsEntry("phone_e164", null);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_auth_identities WHERE user_id = ?", Integer.class,
				userId)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_subscriptions WHERE user_id = ?", Integer.class,
				userId)).isZero();
		// the plan a guest resolves to exists, is active and carries the seeded trial allowances (TRANSLATE 3/day)
		assertThat(jdbc.queryForMap("SELECT plan_status, plan_rank FROM subscription_plans WHERE plan_code = 'GUEST'"))
				.containsEntry("plan_status", "ACTIVE").containsEntry("plan_rank", -10);
		assertThat(jdbc.queryForObject("""
				SELECT included_units FROM plan_entitlements e JOIN subscription_plans p ON p.id = e.plan_id
				WHERE p.plan_code = 'GUEST' AND e.feature_code = 'TRANSLATE'""", Long.class)).isEqualTo(3L);

		// device: attested VALID by Play Integrity, platform and app version captured
		assertThat(jdbc.queryForMap("SELECT * FROM user_devices WHERE id = ?", deviceId))
				.containsEntry("user_id", userId).containsEntry("installation_id", installation)
				.containsEntry("platform", "ANDROID").containsEntry("app_version", "2.0.3")
				.containsEntry("attestation_status", "VALID").containsEntry("attestation_provider", "PLAY_INTEGRITY")
				.containsEntry("device_status", "ACTIVE");
		// session: bound to the device, token carries GUEST and did
		var claims = tokens.decoder().decode(JsonPath.read(body, "$.accessToken"));
		assertThat(claims.getSubject()).isEqualTo(userId.toString());
		assertThat(claims.getClaimAsString("accountType")).isEqualTo("GUEST");
		assertThat(claims.getClaimAsString("did")).isEqualTo(deviceId.toString());
		assertThat(jdbc.queryForObject("SELECT device_id FROM user_sessions WHERE user_id = ?", UUID.class, userId))
				.isEqualTo(deviceId);

		// the refresh token works and keeps the account type
		mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + JsonPath.read(body, "$.refreshToken") + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.user.accountType").value("GUEST"))
				.andExpect(jsonPath("$.deviceId").value(deviceId.toString()));
	}

	@Test
	void anIosGuestNeedsTheAppAttestKeyId() throws Exception {
		String nonce = nonce();
		UUID installation = UUID.randomUUID();
		assertGuestNotAllowed(guest(guestBody(installation, "IOS",
				evidence("APP_ATTEST", MockAttestationTokens.appAttest(nonce), nonce, null))));
		String second = nonce();
		guest(guestBody(installation, "IOS",
				evidence("APP_ATTEST", MockAttestationTokens.appAttest(second), second, "key-1")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.user.accountType").value("GUEST"));
		assertThat(jdbc.queryForMap("SELECT platform, attestation_provider, attestation_status FROM user_devices "
				+ "WHERE installation_id = ?", installation)).containsEntry("platform", "IOS")
				.containsEntry("attestation_provider", "APP_ATTEST").containsEntry("attestation_status", "VALID");
	}

	@Test
	void guestCreationIsLoggedWithoutTokensOrNonces() throws Exception {
		String nonce = nonce();
		try (CapturedLogs logs = new CapturedLogs()) {
			MvcResult result = guest(guestBody(UUID.randomUUID(), "ANDROID", androidEvidence(nonce)))
					.andExpect(status().isOk()).andReturn();
			String refresh = JsonPath.read(result.getResponse().getContentAsString(), "$.refreshToken");
			String access = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
			assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID", androidEvidence(nonce)))); // replay
			assertThat(logs.texts()).noneMatch(t -> t.contains(nonce) || t.contains(refresh) || t.contains(access)
					|| t.contains("mock-play-integrity"));
		}
	}

	// ---------------------------------------------------------------- no valid attestation: GUEST_NOT_ALLOWED

	/** The "done when" of B2.3, in every way a guest can lack a valid attestation. */
	@Test
	void guestWithoutValidAttestationGetsGuestNotAllowedAndNothingIsCreated() throws Exception {
		int before = guestUsers();
		int devicesBefore = jdbc.queryForObject("SELECT count(*) FROM user_devices", Integer.class);
		UUID installation = UUID.randomUUID();
		String fresh = nonce();
		String other = nonce();

		// no attestation at all, and an empty one
		assertGuestNotAllowed(guest(guestBody(installation, "ANDROID", null)));
		assertGuestNotAllowed(guest(guestBody(installation, "ANDROID", "{}")));
		assertGuestNotAllowed(guest(guestBody(installation, "ANDROID", "null")));
		// a token that is not the test format
		assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID",
				evidence("PLAY_INTEGRITY", "garbage", fresh, null))));
		// the nonce was burned by that attempt, so even the right token for it fails now (single use)
		assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID", androidEvidence(fresh))));
		// a nonce the server never issued
		String unknown = "Z".repeat(43);
		assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID", androidEvidence(unknown))));
		// a verdict that covers another nonce than the one presented
		assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID",
				evidence("PLAY_INTEGRITY", MockAttestationTokens.playIntegrity("Y".repeat(43)), other, null))));
		// a verdict the verifier rejects
		String third = nonce();
		assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID",
				evidence("PLAY_INTEGRITY", MockAttestationTokens.rejectedPlayIntegrity(third), third, null))));
		// the wrong provider for the platform
		String fourth = nonce();
		assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID",
				evidence("APP_ATTEST", MockAttestationTokens.appAttest(fourth), fourth, "k"))));

		assertThat(guestUsers()).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_devices", Integer.class)).isEqualTo(devicesBefore);
	}

	@Test
	void guestRequestsAreCheckedForShapeAndPlatform() throws Exception {
		guest("{\"platform\":\"ANDROID\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		guest(guestBody(UUID.randomUUID(), "ANDROID", evidence("CARRIER_PIGEON", "x", "y", null)))
				.andExpect(status().isBadRequest());
		// WEB has no device and no attestation provider
		String nonce = nonce();
		guest(guestBody(UUID.randomUUID(), "WEB", androidEvidence(nonce))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("platform"));
		// and the nonce was not spent by the refused request
		guest(guestBody(UUID.randomUUID(), "ANDROID", androidEvidence(nonce))).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- nonce single use, expiry, binding

	@Test
	void aReplayedNonceFailsEvenWithAnotherInstallation() throws Exception {
		String nonce = nonce();
		guest(guestBody(UUID.randomUUID(), "ANDROID", androidEvidence(nonce))).andExpect(status().isOk());
		int guests = guestUsers();
		assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID", androidEvidence(nonce))));
		assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID", androidEvidence(nonce))));
		assertThat(guestUsers()).isEqualTo(guests);
		assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM attestation_nonces WHERE nonce_hash = ?",
				Boolean.class, AttestationNonceHashes.sha256(nonce))).isTrue();
	}

	@Test
	void anExpiredNonceFails() throws Exception {
		String nonce = nonce();
		jdbc.update("UPDATE attestation_nonces SET created_at = created_at - interval '10 minutes', "
				+ "expires_at = expires_at - interval '10 minutes' WHERE nonce_hash = ?",
				AttestationNonceHashes.sha256(nonce));
		int guests = guestUsers();
		assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID", androidEvidence(nonce))));
		assertThat(guestUsers()).isEqualTo(guests);
	}

	@Test
	void aNonceIssuedForAnotherPlatformOrAUserCannotBeUsedForAGuest() throws Exception {
		int guests = guestUsers();
		// issued for iOS, presented for Android
		String ios = nonceRequest(newIp(), "IOS", null).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString().replaceAll(".*\"nonce\":\"([^\"]+)\".*", "$1");
		assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID", androidEvidence(ios))));
		// a platform-bound nonce works for its platform
		guest(guestBody(UUID.randomUUID(), "IOS",
				evidence("APP_ATTEST", MockAttestationTokens.appAttest(ios), ios, "k"))).andExpect(status().isOk());

		// issued to a signed-in user, so an anonymous guest request cannot spend it
		Account user = signUp();
		String bound = nonceRequest(newIp(), null, user.accessToken()).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString().replaceAll(".*\"nonce\":\"([^\"]+)\".*", "$1");
		assertThat(jdbc.queryForObject("SELECT user_id FROM attestation_nonces WHERE nonce_hash = ?", UUID.class,
				AttestationNonceHashes.sha256(bound))).isEqualTo(user.userId());
		assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID", androidEvidence(bound))));
		assertThat(guestUsers()).isEqualTo(guests + 1);
	}

	@Test
	void concurrentRequestsWithOneNonceCreateAtMostOneGuest() throws Exception {
		String nonce = nonce();
		int before = guestUsers();
		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			UUID installation = UUID.randomUUID();
			calls.add(() -> guest(guestBody(installation, "ANDROID", androidEvidence(nonce))).andReturn().getResponse()
					.getStatus());
		}
		List<Integer> statuses = race(calls);
		assertThat(statuses).containsOnly(200, 403);
		assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
		assertThat(guestUsers()).isEqualTo(before + 1);
	}

	// ---------------------------------------------------------------- one guest per installation, limits

	@Test
	void aSecondGuestForTheSameInstallationIsRefusedAndConcurrentOnesCreateOne() throws Exception {
		UUID installation = UUID.randomUUID();
		String first = nonce();
		guest(guestBody(installation, "ANDROID", androidEvidence(first))).andExpect(status().isOk());
		String second = nonce();
		assertGuestNotAllowed(guest(guestBody(installation, "ANDROID", androidEvidence(second))));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_devices WHERE installation_id = ?", Integer.class,
				installation)).isEqualTo(1);

		UUID raced = UUID.randomUUID();
		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			String n = nonce();
			calls.add(() -> guest(guestBody(raced, "ANDROID", androidEvidence(n))).andReturn().getResponse().getStatus());
		}
		List<Integer> statuses = race(calls);
		assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_devices WHERE installation_id = ?", Integer.class,
				raced)).isEqualTo(1);
	}

	@Test
	void guestCreationIsRateLimitedPerInstallationAndPerIp() throws Exception {
		UUID installation = UUID.randomUUID();
		for (int i = 0; i < 3; i++) {
			assertGuestNotAllowed(guest(guestBody(installation, "ANDROID", null)));
		}
		guest(guestBody(installation, "ANDROID", null)).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().exists("Retry-After"));

		String ip = newIp();
		for (int i = 0; i < 4; i++) {
			assertGuestNotAllowed(guest(guestBody(UUID.randomUUID(), "ANDROID", null), ip));
		}
		guest(guestBody(UUID.randomUUID(), "ANDROID", null), ip).andExpect(status().isTooManyRequests())
				.andExpect(header().string("RateLimit-Limit", "4"));
	}

	// ---------------------------------------------------------------- POST /devices/{id}/attestations

	ResultActions attest(Account a, UUID deviceId, String body) throws Exception {
		return mvc.perform(post("/api/v1/devices/" + deviceId + "/attestations")
				.header("Authorization", "Bearer " + a.accessToken()).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	UUID registerDevice(Account a, String platform) throws Exception {
		MvcResult r = mvc.perform(post("/api/v1/devices").header("Authorization", "Bearer " + a.accessToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"" + UUID.randomUUID() + "\",\"platform\":\"" + platform + "\"}"))
				.andExpect(status().isOk()).andReturn();
		return UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.id"));
	}

	@Test
	void deviceAttestationStoresTheVerdictOnTheDeviceAndAReplayFails() throws Exception {
		Account a = signUp();
		UUID deviceId = registerDevice(a, "ANDROID");
		// the nonce can be bound to the user
		String nonce = nonceRequest(newIp(), "ANDROID", a.accessToken()).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString().replaceAll(".*\"nonce\":\"([^\"]+)\".*", "$1");
		attest(a, deviceId, androidEvidence(nonce)).andExpect(status().isOk())
				.andExpect(jsonPath("$.attestationStatus").value("VALID")).andExpect(jsonPath("$.checkedAt").isString())
				.andExpect(jsonPath("$.validUntil").isString());
		assertThat(jdbc.queryForMap("SELECT attestation_status, attestation_provider, attestation_checked_at "
				+ "FROM user_devices WHERE id = ?", deviceId)).containsEntry("attestation_status", "VALID")
				.containsEntry("attestation_provider", "PLAY_INTEGRITY").doesNotContainEntry("attestation_checked_at", null);

		// replay of the same verdict: FAILED, stored
		attest(a, deviceId, androidEvidence(nonce)).andExpect(status().isOk())
				.andExpect(jsonPath("$.attestationStatus").value("FAILED"));
		assertThat(jdbc.queryForObject("SELECT attestation_status FROM user_devices WHERE id = ?", String.class, deviceId))
				.isEqualTo("FAILED");
	}

	@Test
	void deviceAttestationRejectsBadVerdictsWrongProviderAndForeignNonces() throws Exception {
		Account a = signUp();
		Account b = signUp();
		UUID deviceId = registerDevice(a, "ANDROID");

		String n1 = nonce();
		attest(a, deviceId, evidence("PLAY_INTEGRITY", MockAttestationTokens.rejectedPlayIntegrity(n1), n1, null))
				.andExpect(status().isOk()).andExpect(jsonPath("$.attestationStatus").value("FAILED"));
		String n2 = nonce();
		attest(a, deviceId, evidence("APP_ATTEST", MockAttestationTokens.appAttest(n2), n2, "k"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.attestationStatus").value("FAILED"));
		// a nonce issued to user b cannot be spent by user a
		String bound = nonceRequest(newIp(), null, b.accessToken()).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString().replaceAll(".*\"nonce\":\"([^\"]+)\".*", "$1");
		attest(a, deviceId, androidEvidence(bound)).andExpect(status().isOk())
				.andExpect(jsonPath("$.attestationStatus").value("FAILED"));
		// missing token is a 400
		attest(a, deviceId, "{\"provider\":\"PLAY_INTEGRITY\",\"nonce\":\"" + n1 + "\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("token"));
		// a valid one still passes afterwards
		String ok = nonce();
		attest(a, deviceId, androidEvidence(ok)).andExpect(status().isOk())
				.andExpect(jsonPath("$.attestationStatus").value("VALID"));
	}

	@Test
	void deviceAttestationIsOwnerOnlyAndNeedsAnActiveDevice() throws Exception {
		Account owner = signUp();
		Account intruder = signUp();
		UUID deviceId = registerDevice(owner, "ANDROID");
		String nonce = nonce();
		attest(intruder, deviceId, androidEvidence(nonce)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		// the intruder did not burn the owner's chance: the nonce is still unspent
		assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM attestation_nonces WHERE nonce_hash = ?",
				Boolean.class, AttestationNonceHashes.sha256(nonce))).isTrue();
		attest(owner, UUID.randomUUID(), androidEvidence(nonce)).andExpect(status().isNotFound());

		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/api/v1/devices/" + deviceId).header("Authorization", "Bearer " + owner.accessToken()))
				.andExpect(status().isNoContent());
		attest(owner, deviceId, androidEvidence(nonce)).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/devices/" + deviceId + "/attestations").contentType(MediaType.APPLICATION_JSON)
				.content(androidEvidence(nonce))).andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- helpers

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

	/** SHA-256 hex of a nonce, as the server stores it. */
	static final class AttestationNonceHashes {
		static String sha256(String nonce) throws Exception {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
	}
}
