package com.qalsmartkeyboard.identity.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.notifications.OtpMessage;
import com.qalsmartkeyboard.notifications.OtpSender;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/**
 * B2.1 end to end on PostgreSQL: start → SMS (logging mock, code captured from the sender) → verify, including
 * lockout, expiry, cooldown, replay, supersession, per-number/IP/device limits and log hygiene, behind the real
 * security chain and with real sessions (B2.4). Each test uses its own number and client IP so the in-memory buckets
 * do not interfere.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false",
		"qal.identity.otp.limits.start-per-ip.capacity=4", "qal.identity.otp.limits.start-per-ip.period=1h",
		"qal.identity.otp.limits.start-per-device.capacity=3", "qal.identity.otp.limits.start-per-device.period=1h",
		"qal.identity.otp.limits.start-per-phone.capacity=3", "qal.identity.otp.limits.start-per-phone.period=1h",
		"qal.identity.otp.limits.verify-per-ip.capacity=12", "qal.identity.otp.limits.verify-per-ip.period=1h",
		"qal.identity.otp.limits.verify-per-device.capacity=3", "qal.identity.otp.limits.verify-per-device.period=1h" })
@AutoConfigureMockMvc
@Testcontainers
class PhoneOtpIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@MockitoSpyBean
	OtpSender sender;

	// ---------------------------------------------------------------- helpers

	static String newPhone() {
		return "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
	}

	static String newIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
	}

	ResultActions start(String phone, String ip) throws Exception {
		return mvc.perform(post("/api/v1/auth/otp/start").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"" + phone + "\"}"));
	}

	UUID startOk(String phone, String ip) throws Exception {
		MvcResult r = start(phone, ip).andExpect(status().isAccepted()).andReturn();
		return UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.challengeId"));
	}

	/** The code last sent to {@code phone} by the (spied) sender. */
	String lastCode(String phone) {
		ArgumentCaptor<OtpMessage> captor = ArgumentCaptor.forClass(OtpMessage.class);
		verify(sender, atLeastOnce()).send(captor.capture());
		List<OtpMessage> all = captor.getAllValues();
		for (int i = all.size() - 1; i >= 0; i--) {
			if (all.get(i).phoneE164().equals(phone)) {
				return all.get(i).code();
			}
		}
		throw new AssertionError("no code sent to the number");
	}

	ResultActions verifyCode(UUID challenge, String code, String ip, Boolean ageConfirmed, UUID installation)
			throws Exception {
		StringBuilder body = new StringBuilder("{\"challengeId\":\"" + challenge + "\",\"code\":\"" + code + "\"");
		if (ageConfirmed != null) {
			body.append(",\"ageConfirmed\":").append(ageConfirmed);
		}
		if (installation != null) {
			body.append(",\"device\":{\"installationId\":\"").append(installation).append("\",\"platform\":\"ANDROID\"}");
		}
		body.append('}');
		return mvc.perform(post("/api/v1/auth/otp/verify").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
	}

	/** Moves a challenge into the past (both columns, so ck_otp_expiry still holds). */
	void age(UUID challenge, String interval) {
		jdbc.update("UPDATE otp_challenges SET created_at = created_at - CAST(? AS interval), "
				+ "expires_at = expires_at - CAST(? AS interval) WHERE id = ?", interval, interval, challenge);
	}

	static String wrong(String code) {
		return code.equals("000000") ? "000001" : "000000";
	}

	// ---------------------------------------------------------------- flow

	@Test
	void fullFlowCreatesTheAccountOnceAndSignsInAgain() throws Exception {
		String phone = newPhone();
		String ip = newIp();
		UUID challenge = startOk(phone, ip);
		String code = lastCode(phone);
		assertThat(code).matches("^[0-9]{6}$");

		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM otp_challenges WHERE id = ?", challenge);
		assertThat((String) row.get("code_hash")).startsWith("v1:").doesNotContain(code);
		assertThat((String) row.get("phone_hash")).matches("^[0-9a-f]{64}$");
		assertThat(new String((byte[]) row.get("phone_ciphertext"), java.nio.charset.StandardCharsets.ISO_8859_1))
				.doesNotContain(phone.substring(1));
		assertThat(((Number) row.get("attempts")).intValue()).isZero();
		assertThat(((Number) row.get("max_attempts")).intValue()).isEqualTo(5);

		// new account needs the age confirmation; the code stays usable
		verifyCode(challenge, code, ip, null, null).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("ageConfirmed"));
		MvcResult ok = verifyCode(challenge, code, ip, true, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.isNewUser").value(true)).andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.user.accountStatus").value("ACTIVE"))
				.andExpect(jsonPath("$.user.phoneVerified").value(true)).andReturn();
		String userId = JsonPath.read(ok.getResponse().getContentAsString(), "$.user.id");

		assertThat(jdbc.queryForObject("SELECT phone_e164 FROM users WHERE id = ?::uuid", String.class, userId))
				.isEqualTo(phone);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_auth_identities WHERE user_id = ?::uuid "
				+ "AND identity_provider = 'PHONE' AND provider_subject = ?", Integer.class, userId, phone)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM otp_challenges WHERE id = ?", Boolean.class,
				challenge)).isTrue();

		// second sign-in (after the cooldown) finds the same account
		age(challenge, "2 minutes");
		UUID second = startOk(phone, ip);
		verifyCode(second, lastCode(phone), ip, null, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.isNewUser").value(false)).andExpect(jsonPath("$.user.id").value(userId));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE phone_e164 = ?", Integer.class, phone))
				.isEqualTo(1);
	}

	@Test
	void replayOfAUsedCodeIsRejected() throws Exception {
		String phone = newPhone();
		String ip = newIp();
		UUID challenge = startOk(phone, ip);
		String code = lastCode(phone);
		verifyCode(challenge, code, ip, true, null).andExpect(status().isOk());
		verifyCode(challenge, code, ip, true, null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("OTP_INVALID"));
	}

	@Test
	void wrongCodeCountsAttemptsAndTheFifthLocksTheChallenge() throws Exception {
		String phone = newPhone();
		String ip = newIp();
		UUID challenge = startOk(phone, ip);
		String code = lastCode(phone);
		for (int i = 1; i <= 4; i++) {
			verifyCode(challenge, wrong(code), ip, true, null).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("OTP_INVALID"))
					.andExpect(jsonPath("$.attemptsRemaining").value(5 - i)); // D32
			assertThat(jdbc.queryForObject("SELECT attempts FROM otp_challenges WHERE id = ?", Integer.class, challenge))
					.isEqualTo(i);
		}
		verifyCode(challenge, wrong(code), ip, true, null).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("OTP_TOO_MANY_ATTEMPTS"));
		// locked: even the right code no longer works, and nothing more is counted
		verifyCode(challenge, code, ip, true, null).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("OTP_TOO_MANY_ATTEMPTS"));
		assertThat(jdbc.queryForObject("SELECT attempts FROM otp_challenges WHERE id = ?", Integer.class, challenge))
				.isEqualTo(5);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE phone_e164 = ?", Integer.class, phone)).isZero();
	}

	@Test
	void rightCodeOnTheFifthAttemptStillWorks() throws Exception {
		String phone = newPhone();
		String ip = newIp();
		UUID challenge = startOk(phone, ip);
		String code = lastCode(phone);
		for (int i = 0; i < 4; i++) {
			verifyCode(challenge, wrong(code), ip, true, null).andExpect(status().isUnauthorized());
		}
		verifyCode(challenge, code, ip, true, null).andExpect(status().isOk());
	}

	@Test
	void expiredCodeIsRejected() throws Exception {
		String phone = newPhone();
		String ip = newIp();
		UUID challenge = startOk(phone, ip);
		age(challenge, "6 minutes");
		verifyCode(challenge, lastCode(phone), ip, true, null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
	}

	@Test
	void resendWithinTheCooldownIs429WithRetryAfter() throws Exception {
		String phone = newPhone();
		String ip = newIp();
		startOk(phone, ip);
		MvcResult r = start(phone, ip).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().exists("Retry-After"))
				.andReturn();
		assertThat(Integer.parseInt(r.getResponse().getHeader("Retry-After"))).isBetween(1, 60);
	}

	@Test
	void aNewChallengeSupersedesTheOldOne() throws Exception {
		String phone = newPhone();
		String ip = newIp();
		UUID first = startOk(phone, ip);
		String firstCode = lastCode(phone);
		age(first, "2 minutes");
		UUID second = startOk(phone, ip);
		String secondCode = lastCode(phone);
		verifyCode(first, firstCode, ip, true, null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("OTP_INVALID")).andExpect(jsonPath("$.attemptsRemaining").doesNotExist());
		verifyCode(second, secondCode, ip, true, null).andExpect(status().isOk());
	}

	@Test
	void unknownChallengeLooksLikeAWrongCode() throws Exception {
		verifyCode(UUID.randomUUID(), "123456", newIp(), true, null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("OTP_INVALID")).andExpect(jsonPath("$.attemptsRemaining").doesNotExist());
	}

	// ---------------------------------------------------------------- limits

	@Test
	void perPhoneLimitAppliesAcrossIps() throws Exception {
		String phone = newPhone();
		for (int i = 0; i < 3; i++) {
			UUID c = startOk(phone, newIp());
			age(c, "2 minutes");
		}
		start(phone, newIp()).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(header().string("RateLimit-Limit", "3")).andExpect(header().string("RateLimit-Remaining", "0"))
				.andExpect(header().exists("Retry-After")).andExpect(header().exists("RateLimit-Reset"));
	}

	@Test
	void perIpLimitAppliesAcrossNumbers() throws Exception {
		String ip = newIp();
		for (int i = 0; i < 4; i++) {
			start(newPhone(), ip).andExpect(status().isAccepted()).andExpect(header().string("RateLimit-Limit", "4"))
					.andExpect(header().string("RateLimit-Remaining", Integer.toString(3 - i)));
		}
		String phone = newPhone();
		start(phone, ip).andExpect(status().isTooManyRequests()).andExpect(header().string("RateLimit-Limit", "4"));
		// the rejected request did not use up the victim number's budget or send anything
		assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_challenges WHERE ip_address = CAST(? AS inet)",
				Integer.class, ip)).isEqualTo(4);
		startOk(phone, newIp());
	}

	@Test
	void perDeviceVerifyLimit() throws Exception {
		UUID installation = UUID.randomUUID();
		for (int i = 0; i < 3; i++) {
			verifyCode(UUID.randomUUID(), "123456", newIp(), true, installation).andExpect(status().isUnauthorized());
		}
		verifyCode(UUID.randomUUID(), "123456", newIp(), true, installation).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().string("RateLimit-Limit", "3"));
		verifyCode(UUID.randomUUID(), "123456", newIp(), true, UUID.randomUUID()).andExpect(status().isUnauthorized());
	}

	/** ADR 0007 D9 (1): with an {@code installationId}, start is also limited per device, across numbers and IPs. */
	@Test
	void perDeviceStartLimit() throws Exception {
		UUID installation = UUID.randomUUID();
		for (int i = 0; i < 3; i++) {
			startWithDevice(newPhone(), newIp(), installation).andExpect(status().isAccepted());
		}
		String phone = newPhone();
		startWithDevice(phone, newIp(), installation).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().string("RateLimit-Limit", "3"));
		// another installation (or none) is not affected, and the rejected start did not touch the number
		startWithDevice(phone, newIp(), UUID.randomUUID()).andExpect(status().isAccepted());
	}

	ResultActions startWithDevice(String phone, String ip, UUID installation) throws Exception {
		return mvc.perform(post("/api/v1/auth/otp/start").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON)
				.content("{\"phoneNumber\":\"" + phone + "\",\"installationId\":\"" + installation + "\"}"));
	}

	@Test
	void ethiopianLandlineIsRejectedBeforeAnySms() throws Exception {
		start("+251111234567", newIp()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_PHONE_NUMBER"));
	}

	/** ADR 0007 D48: other countries get 400 PHONE_REGION_NOT_SUPPORTED, no SMS and no challenge. */
	@Test
	void nonEthiopianNumbersAreRefusedWithoutSmsOrChallenge() throws Exception {
		Integer before = jdbc.queryForObject("SELECT count(*) FROM otp_challenges", Integer.class);
		for (String phone : List.of("+447700900123", "+12025550123")) {
			start(phone, newIp()).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("PHONE_REGION_NOT_SUPPORTED"));
		}
		verify(sender, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers
				.argThat(m -> m.phoneE164().startsWith("+44") || m.phoneE164().startsWith("+1")));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_challenges", Integer.class)).isEqualTo(before);
	}

	@Test
	void refusedRegionStillCountsAgainstTheIpBudget() throws Exception {
		String ip = newIp();
		for (int i = 0; i < 4; i++) {
			start("+447700900123", ip).andExpect(status().isBadRequest());
		}
		start("+447700900123", ip).andExpect(status().isTooManyRequests());
	}

	@Test
	void safaricomNumbersAreRefusedWithTheirOwnCodeAndNoSms() throws Exception {
		Integer before = jdbc.queryForObject("SELECT count(*) FROM otp_challenges", Integer.class);
		String phone = "+2517" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
		start(phone, newIp()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PHONE_CARRIER_NOT_SUPPORTED"));
		verify(sender, org.mockito.Mockito.never())
				.send(org.mockito.ArgumentMatchers.argThat(m -> m.phoneE164().equals(phone)));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM otp_challenges", Integer.class)).isEqualTo(before);
	}

	/** DoD: the V3 guard the flow relies on (every challenge carries its sealed number) rejects bad rows. */
	@Test
	void schemaRejectsChallengesWithoutASealedNumber() {
		String insert = "INSERT INTO otp_challenges (phone_hash, code_hash, purpose, expires_at, phone_ciphertext) "
				+ "VALUES (repeat('a', 64), 'v1:00', 'SIGN_IN', now() + interval '5 minutes', ?)";
		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> jdbc.update(insert, (Object) null))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, (Object) new byte[5]))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
				.hasMessageContaining("ck_otp_phone_ciphertext");
	}

	@Test
	void suspendedAccountCannotSignIn() throws Exception {
		String phone = newPhone();
		String ip = newIp();
		UUID first = startOk(phone, ip);
		verifyCode(first, lastCode(phone), ip, true, null).andExpect(status().isOk());
		jdbc.update("UPDATE users SET account_status = 'SUSPENDED' WHERE phone_e164 = ?", phone);
		age(first, "2 minutes");
		UUID second = startOk(phone, ip);
		verifyCode(second, lastCode(phone), ip, true, null).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
	}

	// ---------------------------------------------------------------- logging hygiene (SD 18)

	@Test
	void noCodeNumberOrTokenReachesTheLogs() throws Exception {
		String phone = newPhone();
		String ip = newIp();
		try (CapturedLogs logs = new CapturedLogs()) {
			UUID challenge = startOk(phone, ip);
			String code = lastCode(phone);
			verifyCode(challenge, wrong(code), ip, true, null).andExpect(status().isUnauthorized());
			MvcResult ok = verifyCode(challenge, code, ip, true, null).andExpect(status().isOk()).andReturn();
			String access = JsonPath.read(ok.getResponse().getContentAsString(), "$.accessToken");
			String refresh = JsonPath.read(ok.getResponse().getContentAsString(), "$.refreshToken");

			List<String> texts = logs.texts();
			assertThat(texts).as("something was logged (mock SMS at least)").isNotEmpty();
			assertThat(texts).noneMatch(t -> t != null && (t.contains(code) || t.contains(phone.substring(4))
					|| t.contains(access) || t.contains(refresh)));
		}
	}
}
