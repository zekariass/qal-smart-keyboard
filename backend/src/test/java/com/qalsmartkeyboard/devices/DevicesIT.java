package com.qalsmartkeyboard.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import com.qalsmartkeyboard.identity.session.IssuedSession;
import com.qalsmartkeyboard.identity.session.SessionContext;
import com.qalsmartkeyboard.identity.session.SessionIssuer;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.notifications.OtpMessage;
import com.qalsmartkeyboard.notifications.OtpSender;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/**
 * B2.5 end to end on PostgreSQL with the real security chain: register (idempotent per installation), update, push
 * token handling, revoke (ends the device's session families, so its refresh token fails), ownership (another user's
 * device is a 404), the device/session linkage ({@code did}) and concurrent registration.
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class DevicesIT {

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
	@Autowired
	DeviceService devices;
	@MockitoSpyBean
	OtpSender sender;

	// ---------------------------------------------------------------- helpers

	/** A signed-in user without a device (like a B2.4 session). */
	record Account(UUID userId, String accessToken, String refreshToken, String sessionId) {
	}

	static String newPhone() {
		return "+2517" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
	}

	Account signUp() {
		return transactions.execute(status -> {
			PhoneSignIn.SignedIn in = (PhoneSignIn.SignedIn) accounts.signInWithPhone(newPhone(), true, Instant.now());
			UserAccount user = in.user();
			IssuedSession s = issuer.issue(user, new SessionContext(null, "10.0.0.1", "it"));
			return new Account(user.id(), s.accessToken(), s.refreshToken(), s.sessionId().toString());
		});
	}

	static String registration(UUID installation, String platform) {
		return "{\"installationId\":\"" + installation + "\",\"platform\":\"" + platform + "\"}";
	}

	ResultActions register(Account a, String body) throws Exception {
		return mvc.perform(post("/api/v1/devices").header("Authorization", "Bearer " + a.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	ResultActions refresh(String refreshToken) throws Exception {
		return mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

	UUID registerDevice(Account a, UUID installation) throws Exception {
		MvcResult r = register(a, registration(installation, "ANDROID")).andExpect(status().isOk()).andReturn();
		return UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.id"));
	}

	Map<String, Object> deviceRow(UUID id) {
		return jdbc.queryForMap("SELECT * FROM user_devices WHERE id = ?", id);
	}

	String didOf(String accessToken) {
		return tokens.decoder().decode(accessToken).getClaimAsString("did");
	}

	// ---------------------------------------------------------------- register

	@Test
	void registerCreatesTheDeviceFromBodyAndHeadersAndIsIdempotentPerInstallation() throws Exception {
		Account a = signUp();
		UUID installation = UUID.randomUUID();
		MvcResult first = mvc.perform(post("/api/v1/devices").header("Authorization", "Bearer " + a.accessToken())
				.header("X-Client-Platform", "ANDROID").header("X-App-Version", "2.0.3")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"" + installation + "\",\"platform\":\"ANDROID\","
						+ "\"deviceName\":\"Pixel 8\",\"osVersion\":\"Android 15\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.installationId").value(installation.toString()))
				.andExpect(jsonPath("$.platform").value("ANDROID")).andExpect(jsonPath("$.deviceName").value("Pixel 8"))
				.andExpect(jsonPath("$.appVersion").value("2.0.3")).andExpect(jsonPath("$.osVersion").value("Android 15"))
				.andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.attestationStatus").value("NOT_CHECKED"))
				.andExpect(jsonPath("$.lastSeenAt").exists()).andExpect(jsonPath("$.createdAt").exists()).andReturn();
		String id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

		// again: same row, changed fields refreshed, untouched fields kept
		register(a, "{\"installationId\":\"" + installation + "\",\"platform\":\"ANDROID\",\"appVersion\":\"2.1.0\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.appVersion").value("2.1.0")).andExpect(jsonPath("$.deviceName").value("Pixel 8"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_devices WHERE user_id = ?", Integer.class, a.userId()))
				.isEqualTo(1);
		assertThat(deviceRow(UUID.fromString(id))).containsEntry("distribution_channel", "OTHER");
	}

	@Test
	void registerRejectsWebAPlatformChangeAndAHeaderMismatch() throws Exception {
		Account a = signUp();
		UUID installation = UUID.randomUUID();
		register(a, registration(installation, "WEB")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].field").value("platform"));
		registerDevice(a, installation);
		register(a, registration(installation, "IOS")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("PLATFORM_MISMATCH"));
		mvc.perform(post("/api/v1/devices").header("Authorization", "Bearer " + a.accessToken())
				.header("X-Client-Platform", "IOS").contentType(MediaType.APPLICATION_JSON)
				.content(registration(UUID.randomUUID(), "ANDROID"))).andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_devices WHERE user_id = ?", Integer.class, a.userId()))
				.isEqualTo(1);
	}

	@Test
	void everyOperationNeedsAnAccessToken() throws Exception {
		UUID id = UUID.randomUUID();
		mvc.perform(post("/api/v1/devices").contentType(MediaType.APPLICATION_JSON)
				.content(registration(UUID.randomUUID(), "ANDROID"))).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(patch("/api/v1/devices/" + id).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(delete("/api/v1/devices/" + id)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void concurrentRegistrationsOfOneInstallationYieldOneDevice() throws Exception {
		Account a = signUp();
		UUID installation = UUID.randomUUID();
		List<Callable<String>> calls = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			calls.add(() -> JsonPath.read(register(a, registration(installation, "ANDROID")).andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString(), "$.id"));
		}
		List<String> ids = race(calls);
		assertThat(ids).hasSize(6).containsOnly(ids.get(0));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_devices WHERE user_id = ?", Integer.class, a.userId()))
				.isEqualTo(1);
	}

	// ---------------------------------------------------------------- device and session linkage (did)

	@Test
	void registeringBindsTheSessionSoTheNextRefreshCarriesDid() throws Exception {
		Account a = signUp();
		assertThat(didOf(a.accessToken())).isNull();
		UUID deviceId = registerDevice(a, UUID.randomUUID());
		assertThat(jdbc.queryForObject("SELECT device_id FROM user_sessions WHERE token_family_id = ?::uuid AND session_status = 'ACTIVE'",
				UUID.class, a.sessionId())).isEqualTo(deviceId);

		MvcResult refreshed = refresh(a.refreshToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.deviceId").value(deviceId.toString())).andReturn();
		String access = JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken");
		assertThat(didOf(access)).isEqualTo(deviceId.toString());

		// a second device does not rebind a session that already has one
		register(new Account(a.userId(), access, "", a.sessionId()), registration(UUID.randomUUID(), "ANDROID"))
				.andExpect(status().isOk());
		assertThat(jdbc.queryForList("SELECT DISTINCT device_id FROM user_sessions WHERE token_family_id = ?::uuid",
				UUID.class, a.sessionId())).containsExactly(deviceId);
	}

	@Test
	void otpSignInWithADeviceRegistersItAndTheSessionCarriesIt() throws Exception {
		String phone = newPhone();
		UUID installation = UUID.randomUUID();
		MvcResult first = otpSignIn(phone, installation);
		String body = first.getResponse().getContentAsString();
		String deviceId = JsonPath.read(body, "$.deviceId");
		assertThat(didOf(JsonPath.read(body, "$.accessToken"))).isEqualTo(deviceId);
		Map<String, Object> row = deviceRow(UUID.fromString(deviceId));
		assertThat(row).containsEntry("platform", "ANDROID").containsEntry("app_version", "2.0.3")
				.containsEntry("device_status", "ACTIVE");
		assertThat(jdbc.queryForObject("SELECT device_id FROM user_sessions WHERE refresh_token_hash = ?", UUID.class,
				sha256(JsonPath.read(body, "$.refreshToken"))))
				.isEqualTo(UUID.fromString(deviceId));

		// signing in again from the same installation reuses the device
		jdbc.update("UPDATE otp_challenges SET created_at = created_at - interval '2 minutes', "
				+ "expires_at = expires_at - interval '2 minutes'");
		String second = otpSignIn(phone, installation).getResponse().getContentAsString();
		assertThat((String) JsonPath.read(second, "$.deviceId")).isEqualTo(deviceId);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_devices WHERE installation_id = ?", Integer.class,
				installation)).isEqualTo(1);
	}

	@Test
	void otpSignInWithAWebDeviceCreatesNoDevice() throws Exception {
		MvcResult web = otpSignIn(newPhone(), UUID.randomUUID(), "WEB");
		assertThat(web.getResponse().getContentAsString()).doesNotContain("deviceId");
	}

	MvcResult otpSignIn(String phone, UUID installation) throws Exception {
		return otpSignIn(phone, installation, "ANDROID");
	}

	MvcResult otpSignIn(String phone, UUID installation, String platform) throws Exception {
		String ip = "10." + ThreadLocalRandom.current().nextInt(256) + "." + ThreadLocalRandom.current().nextInt(256)
				+ "." + ThreadLocalRandom.current().nextInt(1, 255);
		MvcResult started = mvc.perform(post("/api/v1/auth/otp/start").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"" + phone + "\"}"))
				.andExpect(status().isAccepted()).andReturn();
		String challenge = JsonPath.read(started.getResponse().getContentAsString(), "$.challengeId");
		ArgumentCaptor<OtpMessage> sent = ArgumentCaptor.forClass(OtpMessage.class);
		verify(sender, atLeastOnce()).send(sent.capture());
		String code = sent.getAllValues().stream().filter(m -> m.phoneE164().equals(phone)).reduce((x, y) -> y)
				.orElseThrow().code();
		return mvc.perform(post("/api/v1/auth/otp/verify").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).header("X-App-Version", "2.0.3").contentType(MediaType.APPLICATION_JSON)
				.content("{\"challengeId\":\"" + challenge + "\",\"code\":\"" + code + "\",\"ageConfirmed\":true,"
						+ "\"device\":{\"installationId\":\"" + installation + "\",\"platform\":\"" + platform + "\"}}"))
				.andExpect(status().isOk()).andReturn();
	}

	// ---------------------------------------------------------------- update and push token

	@Test
	void updateChangesVersionsAndTheHeaderVersionFillsGaps() throws Exception {
		Account a = signUp();
		UUID id = registerDevice(a, UUID.randomUUID());
		mvc.perform(patch("/api/v1/devices/" + id).header("Authorization", "Bearer " + a.accessToken())
				.header("X-App-Version", "2.4.0").contentType(MediaType.APPLICATION_JSON)
				.content("{\"deviceName\":\"Pixel 9\",\"osVersion\":\"Android 16\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.deviceName").value("Pixel 9")).andExpect(jsonPath("$.osVersion").value("Android 16"))
				.andExpect(jsonPath("$.appVersion").value("2.4.0"));
		mvc.perform(patch("/api/v1/devices/" + id).header("Authorization", "Bearer " + a.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("{\"appVersion\":\"2.5.0\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.appVersion").value("2.5.0"))
				.andExpect(jsonPath("$.deviceName").value("Pixel 9"));
	}

	@Test
	void pushTokenIsStoredWriteOnlyReplacedClearedAndMovedBetweenDevices() throws Exception {
		Account a = signUp();
		Account b = signUp();
		String token = "fcm-" + UUID.randomUUID();
		String bearerA = "Bearer " + a.accessToken();
		try (CapturedLogs logs = new CapturedLogs()) {
			MvcResult registered = register(a, "{\"installationId\":\"" + UUID.randomUUID()
					+ "\",\"platform\":\"ANDROID\",\"pushToken\":\"" + token + "\"}").andExpect(status().isOk())
					.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))))
					.andReturn();
			UUID idA = UUID.fromString(JsonPath.read(registered.getResponse().getContentAsString(), "$.id"));
			assertThat(deviceRow(idA)).containsEntry("push_token", token).containsEntry("push_provider", "FCM");

			// replace
			String token2 = "fcm-" + UUID.randomUUID();
			mvc.perform(patch("/api/v1/devices/" + idA).header("Authorization", bearerA)
					.contentType(MediaType.APPLICATION_JSON).content("{\"pushProvider\":\"FCM\",\"pushToken\":\"" + token2 + "\"}"))
					.andExpect(status().isOk())
					.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token2))));
			assertThat(deviceRow(idA)).containsEntry("push_token", token2);

			// the same token registered by another account moves to that device (one active owner)
			UUID idB = UUID.fromString(JsonPath.read(register(b, "{\"installationId\":\"" + UUID.randomUUID()
					+ "\",\"platform\":\"ANDROID\",\"pushToken\":\"" + token2 + "\"}").andExpect(status().isOk()).andReturn()
					.getResponse().getContentAsString(), "$.id"));
			assertThat(deviceRow(idB)).containsEntry("push_token", token2);
			assertThat(deviceRow(idA)).containsEntry("push_token", null).containsEntry("push_provider", null);

			// provider must fit the platform; an empty token clears
			mvc.perform(patch("/api/v1/devices/" + idB).header("Authorization", "Bearer " + b.accessToken())
					.contentType(MediaType.APPLICATION_JSON).content("{\"pushProvider\":\"APNS\",\"pushToken\":\"x\"}"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("pushProvider"));
			assertThat(deviceRow(idB)).containsEntry("push_token", token2);
			mvc.perform(patch("/api/v1/devices/" + idB).header("Authorization", "Bearer " + b.accessToken())
					.contentType(MediaType.APPLICATION_JSON).content("{\"pushToken\":\"\"}")).andExpect(status().isOk());
			assertThat(deviceRow(idB)).containsEntry("push_token", null).containsEntry("push_provider", null);

			assertThat(logs.texts()).noneMatch(t -> t.contains(token) || t.contains(token2));
		}
	}

	// ---------------------------------------------------------------- ownership

	@Test
	void aUserCannotSeeUpdateOrRevokeAnotherUsersDevice() throws Exception {
		Account owner = signUp();
		Account intruder = signUp();
		UUID installation = UUID.randomUUID();
		UUID id = registerDevice(owner, installation);
		String bearer = "Bearer " + intruder.accessToken();

		mvc.perform(patch("/api/v1/devices/" + id).header("Authorization", bearer)
				.contentType(MediaType.APPLICATION_JSON).content("{\"deviceName\":\"stolen\"}"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(delete("/api/v1/devices/" + id).header("Authorization", bearer)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		// an unknown id looks exactly the same
		mvc.perform(delete("/api/v1/devices/" + UUID.randomUUID()).header("Authorization", bearer))
				.andExpect(status().isNotFound());
		assertThat(deviceRow(id)).containsEntry("device_status", "ACTIVE").doesNotContainEntry("device_name", "stolen");
		refresh(owner.refreshToken()).andExpect(status().isOk());

		// the list only has the caller's devices; registering the same installation id creates the caller's own row
		UUID own = registerDevice(intruder, installation);
		assertThat(own).isNotEqualTo(id);
		assertThat(devices.list(owner.userId())).extracting(d -> d.id()).containsExactly(id);
		assertThat(devices.list(intruder.userId())).extracting(d -> d.id()).containsExactly(own);
	}

	// ---------------------------------------------------------------- revoke

	@Test
	void revokingADeviceEndsItsSessionsSoRefreshFailsAndOtherDevicesKeepWorking() throws Exception {
		String phone = newPhone();
		UUID installationOne = UUID.randomUUID();
		UUID installationTwo = UUID.randomUUID();
		// one account on two devices (two sign-ins = two session families)
		MvcResult one = otpSignIn(phone, installationOne);
		jdbc.update("UPDATE otp_challenges SET created_at = created_at - interval '2 minutes', "
				+ "expires_at = expires_at - interval '2 minutes'");
		MvcResult two = otpSignIn(phone, installationTwo);
		String bodyOne = one.getResponse().getContentAsString();
		String bodyTwo = two.getResponse().getContentAsString();
		String deviceOne = JsonPath.read(bodyOne, "$.deviceId");
		String deviceTwo = JsonPath.read(bodyTwo, "$.deviceId");
		assertThat(deviceOne).isNotEqualTo(deviceTwo);
		String accessOne = JsonPath.read(bodyOne, "$.accessToken");
		String refreshOne = JsonPath.read(bodyOne, "$.refreshToken");
		String refreshTwo = JsonPath.read(bodyTwo, "$.refreshToken");
		// give device one a push token so we can see it dropped
		jdbc.update("UPDATE user_devices SET push_provider = 'FCM', push_token = 'tok-one' WHERE id = ?::uuid", deviceOne);

		// device two revokes device one
		mvc.perform(delete("/api/v1/devices/" + deviceOne).header("Authorization",
				"Bearer " + JsonPath.read(bodyTwo, "$.accessToken"))).andExpect(status().isNoContent());

		refresh(refreshOne).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		assertThat(jdbc.queryForList("SELECT session_status, revoked_reason FROM user_sessions WHERE device_id = ?::uuid",
				deviceOne)).allSatisfy(r -> {
					assertThat(r).containsEntry("session_status", "REVOKED").containsEntry("revoked_reason", "DEVICE_REVOKED");
				});
		assertThat(deviceRow(UUID.fromString(deviceOne))).containsEntry("device_status", "REVOKED")
				.containsEntry("push_token", null);
		refresh(refreshTwo).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
				Integer.class, DeviceService.REVOKED_EVENT, deviceOne)).isEqualTo(1);

		// idempotent, no second event; a revoked device cannot be updated; its access token still verifies until expiry
		mvc.perform(delete("/api/v1/devices/" + deviceOne).header("Authorization",
				"Bearer " + JsonPath.read(bodyTwo, "$.accessToken"))).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
				Integer.class, DeviceService.REVOKED_EVENT, deviceOne)).isEqualTo(1);
		mvc.perform(patch("/api/v1/devices/" + deviceOne).header("Authorization",
				"Bearer " + JsonPath.read(bodyTwo, "$.accessToken")).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound());
		assertThat(didOf(accessOne)).isEqualTo(deviceOne);
	}

	@Test
	void revokingTheCurrentDeviceEndsTheCallersOwnSession() throws Exception {
		Account a = signUp();
		UUID id = registerDevice(a, UUID.randomUUID());
		mvc.perform(delete("/api/v1/devices/" + id).header("Authorization", "Bearer " + a.accessToken()))
				.andExpect(status().isNoContent());
		refresh(a.refreshToken()).andExpect(status().isUnauthorized());
	}

	@Test
	void aRevokedInstallationCanRegisterAgainAndStartsUnattested() throws Exception {
		Account a = signUp();
		UUID installation = UUID.randomUUID();
		UUID id = registerDevice(a, installation);
		jdbc.update("UPDATE user_devices SET attestation_provider = 'PLAY_INTEGRITY', attestation_status = 'VALID', "
				+ "attestation_checked_at = now() WHERE id = ?", id);
		mvc.perform(delete("/api/v1/devices/" + id).header("Authorization", "Bearer " + a.accessToken()))
				.andExpect(status().isNoContent());
		// a new session of the same user registers the same installation again
		Account fresh = transactions.execute(status -> {
			IssuedSession session = issuer.issue(accounts.findById(a.userId()).orElseThrow(),
					new SessionContext(null, "10.0.0.2", "it"));
			return new Account(a.userId(), session.accessToken(), session.refreshToken(), session.sessionId().toString());
		});
		register(fresh, registration(installation, "ANDROID")).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.attestationStatus").value("NOT_CHECKED"));
		assertThat(deviceRow(id)).containsEntry("attestation_provider", null);
	}

	static String sha256(String value) throws Exception {
		return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

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
