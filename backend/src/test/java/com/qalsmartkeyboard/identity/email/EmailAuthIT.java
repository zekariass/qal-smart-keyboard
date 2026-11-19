package com.qalsmartkeyboard.identity.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.identity.AccountDeletion;
import com.qalsmartkeyboard.identity.ExportWriter;
import com.qalsmartkeyboard.identity.UserDeletionParticipant;
import com.qalsmartkeyboard.identity.UserExportParticipant;
import com.qalsmartkeyboard.identity.attestation.MockAttestationTokens;
import com.qalsmartkeyboard.notifications.EmailCodeMessage;
import com.qalsmartkeyboard.notifications.EmailCodeSender;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/**
 * ADR 0033 end to end on PostgreSQL with e-mail verification <b>required</b> (the default): sign-up through the code,
 * the challenge binding, resend, login with lockout and the unverified-account path, squatting takeover, guest linking,
 * forgot/reset (no enumeration, single use, sessions revoked), deletion and export. Every response is checked against
 * the contract. Each test uses its own address and client IP so the in-memory buckets do not interfere.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false",
		"qal.identity.email.limits.send-per-address.capacity=8", "qal.identity.email.limits.send-per-address.period=1h" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class EmailAuthIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final String PASSWORD = "bunna and injera 4ever";
	static final String OTHER_PASSWORD = "tej and tibs at noon";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ApplicationContext context;
	@Autowired
	PlatformTransactionManager transactionManager;
	@MockitoSpyBean
	EmailCodeSender sender;
	@MockitoSpyBean
	PasswordHasher hasher;

	// ---------------------------------------------------------------- helpers

	static String newEmail() {
		return "User" + ThreadLocalRandom.current().nextInt(1_000_000_000) + "@Example.com";
	}

	static String newIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
	}

	ResultActions call(String path, String json, String ip, String bearer) throws Exception {
		return mvc.perform(post(path).with(r -> {
			r.setRemoteAddr(ip);
			if (bearer != null) {
				r.addHeader("Authorization", "Bearer " + bearer);
			}
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	ResultActions register(String email, String password, String ip, String bearer) throws Exception {
		return call("/api/v1/auth/email/register", """
				{"email":"%s","password":"%s","displayName":"Selam","ageConfirmed":true}
				""".formatted(email, password), ip, bearer);
	}

	ResultActions login(String email, String password, String ip, String bearer) throws Exception {
		return call("/api/v1/auth/email/login", """
				{"email":"%s","password":"%s"}""".formatted(email, password), ip, bearer);
	}

	ResultActions verifyCode(String challenge, String code, String ip, String bearer) throws Exception {
		return call("/api/v1/auth/email/verify", """
				{"challengeId":"%s","code":"%s"}""".formatted(challenge, code), ip, bearer);
	}

	static String json(MvcResult r, String path) throws Exception {
		return JsonPath.read(r.getResponse().getContentAsString(), path).toString();
	}

	/** The codes e-mailed to {@code to} (case-insensitive), oldest first. */
	List<EmailCodeMessage> mails(String to) {
		ArgumentCaptor<EmailCodeMessage> captor = ArgumentCaptor.forClass(EmailCodeMessage.class);
		try {
			verify(sender, atLeastOnce()).send(captor.capture());
		}
		catch (AssertionError e) {
			return List.of();
		}
		return captor.getAllValues().stream().filter(m -> m.to().equalsIgnoreCase(to)).toList();
	}

	String lastCode(String to, EmailCodeMessage.Kind kind) {
		List<EmailCodeMessage> all = mails(to);
		assertThat(all).isNotEmpty();
		EmailCodeMessage last = all.get(all.size() - 1);
		assertThat(last.kind()).isEqualTo(kind);
		return last.code();
	}

	static String wrong(String code) {
		return code.equals("000000") ? "000001" : "000000";
	}

	/** Sign-up with verification: register (202), code, verify (200). @return the AuthSession JSON */
	MvcResult signUp(String email, String ip, String bearer) throws Exception {
		MvcResult r = register(email, PASSWORD, ip, bearer).andExpect(status().isAccepted()).andReturn();
		String challenge = json(r, "$.challengeId");
		return verifyCode(challenge, lastCode(email, EmailCodeMessage.Kind.SIGN_UP), ip, null)
				.andExpect(status().isOk()).andReturn();
	}

	/** An account as verification-off sign-up leaves it: e-mail identity, password, address not verified. */
	UUID unverifiedAccount(String email, String password) {
		EmailAddresses.Address a = EmailAddresses.parse(email);
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO users (id, account_type, email, email_normalized, account_status, age_confirmed_at)
				VALUES (?, 'REGISTERED', ?, ?, 'ACTIVE', now())""", id, a.address(), a.normalized());
		jdbc.update("""
				INSERT INTO user_auth_identities (user_id, identity_provider, provider_subject, provider_email)
				VALUES (?, 'EMAIL', ?, ?)""", id, a.normalized(), a.address());
		jdbc.update("INSERT INTO user_password_credentials (user_id, password_hash) VALUES (?, ?)", id,
				hasher.hash(PasswordPolicy.normalize(password)));
		return id;
	}

	/** @return the guest's access token and user id */
	String[] guest(UUID installation) throws Exception {
		String ip = newIp();
		String nonce = JsonPath.read(mvc.perform(post("/api/v1/auth/attestation-nonces").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		})).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.nonce");
		String body = """
				{"installationId":"%s","platform":"ANDROID",
				 "attestation":{"provider":"PLAY_INTEGRITY","token":"%s","nonce":"%s"}}
				""".formatted(installation, MockAttestationTokens.playIntegrity(nonce), nonce);
		String json = mvc.perform(post("/api/v1/auth/guest").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		return new String[] { JsonPath.read(json, "$.accessToken"), JsonPath.read(json, "$.user.id") };
	}

	int activeSessions(UUID user) {
		return jdbc.queryForObject("SELECT count(*) FROM user_sessions WHERE user_id = ? AND session_status = 'ACTIVE'",
				Integer.class, user);
	}

	// ---------------------------------------------------------------- sign-up with verification

	@Test
	void signUpCreatesNothingUntilTheCodeProvesTheMailbox() throws Exception {
		String email = newEmail();
		String ip = newIp();
		MvcResult r = register(email, PASSWORD, ip, null).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.codeLength").value(6)).andExpect(jsonPath("$.expiresAt").exists())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
				.andReturn();
		String challenge = json(r, "$.challengeId");
		String code = lastCode(email, EmailCodeMessage.Kind.SIGN_UP);
		assertThat(code).matches("^[0-9]{6}$");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email_normalized = ?", Integer.class,
				email.toLowerCase())).isZero();
		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM email_challenges WHERE id = ?::uuid", challenge);
		assertThat((String) row.get("code_hash")).startsWith("v1:").doesNotContain(code);
		assertThat((String) row.get("pending_password_hash")).startsWith("$argon2id$").doesNotContain(PASSWORD);
		assertThat(new String((byte[]) row.get("payload_ciphertext"), java.nio.charset.StandardCharsets.ISO_8859_1))
				.doesNotContainIgnoringCase("example");
		assertThat(row.get("user_id")).isNull();

		verifyCode(challenge, wrong(code), ip, null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("OTP_INVALID")).andExpect(jsonPath("$.attemptsRemaining").value(4));
		MvcResult ok = verifyCode(challenge, code, ip, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.isNewUser").value(true)).andExpect(jsonPath("$.user.emailVerified").value(true))
				.andExpect(jsonPath("$.user.email").value(email.replace("Example.com", "example.com")))
				.andExpect(jsonPath("$.user.displayName").value("Selam"))
				.andExpect(jsonPath("$.user.accountStatus").value("ACTIVE")).andReturn();
		UUID user = UUID.fromString(json(ok, "$.user.id"));
		assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE id = ?", Boolean.class,
				user)).isTrue();
		assertThat(jdbc.queryForObject("""
				SELECT provider_subject FROM user_auth_identities WHERE user_id = ? AND identity_provider = 'EMAIL'""",
				String.class, user)).isEqualTo(email.toLowerCase());
		assertThat(jdbc.queryForObject("SELECT password_hash FROM user_password_credentials WHERE user_id = ?",
				String.class, user)).isEqualTo(row.get("pending_password_hash"));
		// single use
		verifyCode(challenge, code, ip, null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("OTP_INVALID")).andExpect(jsonPath("$.attemptsRemaining").doesNotExist());
		// the address is taken now; another sign-up is a conflict, sign-in works (case-insensitive)
		register(email.toUpperCase(), OTHER_PASSWORD, ip, null).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
		login(email.toLowerCase(), PASSWORD, ip, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.isNewUser").value(false)).andExpect(jsonPath("$.user.id").value(user.toString()));
	}

	@Test
	void fiveWrongCodesLockTheChallenge() throws Exception {
		String email = newEmail();
		String ip = newIp();
		String challenge = json(register(email, PASSWORD, ip, null).andExpect(status().isAccepted()).andReturn(),
				"$.challengeId");
		String code = lastCode(email, EmailCodeMessage.Kind.SIGN_UP);
		for (int i = 0; i < 4; i++) {
			verifyCode(challenge, wrong(code), ip, null).andExpect(status().isUnauthorized());
		}
		verifyCode(challenge, wrong(code), ip, null).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("OTP_TOO_MANY_ATTEMPTS"));
		verifyCode(challenge, code, ip, null).andExpect(status().isTooManyRequests());
		// a resend gives a fresh code and fresh attempts
		jdbc.update("UPDATE email_challenges SET last_sent_at = last_sent_at - interval '61 seconds' WHERE id = ?::uuid",
				challenge);
		call("/api/v1/auth/email/verify/resend", "{\"challengeId\":\"" + challenge + "\"}", ip, null)
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.challengeId").value(challenge));
		String fresh = lastCode(email, EmailCodeMessage.Kind.SIGN_UP);
		verifyCode(challenge, fresh, ip, null).andExpect(status().isOk());
	}

	@Test
	void aCodeOnlyCompletesTheSignUpItWasSentFor() throws Exception {
		String email = newEmail();
		String victim = json(register(email, PASSWORD, newIp(), null).andExpect(status().isAccepted()).andReturn(),
				"$.challengeId");
		String victimCode = lastCode(email, EmailCodeMessage.Kind.SIGN_UP);
		jdbc.update("UPDATE email_challenges SET last_sent_at = last_sent_at - interval '61 seconds'");
		// someone else signs up with the same address and their own password; the mailbox gets a second code
		String attacker = json(register(email, OTHER_PASSWORD, newIp(), null).andExpect(status().isAccepted())
				.andReturn(), "$.challengeId");
		String attackerCode = lastCode(email, EmailCodeMessage.Kind.SIGN_UP);
		assertThat(attacker).isNotEqualTo(victim);
		// the mailbox owner types the newest code into their own app: it does not fit their challenge...
		if (!attackerCode.equals(victimCode)) {
			verifyCode(victim, attackerCode, newIp(), null).andExpect(status().isUnauthorized());
		}
		// ...and their own code completes their sign-up with their password
		verifyCode(victim, victimCode, newIp(), null).andExpect(status().isOk());
		login(email, PASSWORD, newIp(), null).andExpect(status().isOk());
		// the other sign-up is void once the address has a verified account
		verifyCode(attacker, attackerCode, newIp(), null).andExpect(status().isUnauthorized());
		login(email, OTHER_PASSWORD, newIp(), null).andExpect(status().isUnauthorized());
	}

	@Test
	void resendHonoursTheCooldownAndReplacesTheCode() throws Exception {
		String email = newEmail();
		String ip = newIp();
		String challenge = json(register(email, PASSWORD, ip, null).andExpect(status().isAccepted()).andReturn(),
				"$.challengeId");
		String first = lastCode(email, EmailCodeMessage.Kind.SIGN_UP);
		String resend = "{\"challengeId\":\"" + challenge + "\"}";
		call("/api/v1/auth/email/verify/resend", resend, ip, null).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED")).andExpect(header().exists("Retry-After"));
		// same address again within the cooldown is refused as well
		register(email, PASSWORD, ip, null).andExpect(status().isTooManyRequests());
		jdbc.update("UPDATE email_challenges SET last_sent_at = last_sent_at - interval '61 seconds' WHERE id = ?::uuid",
				challenge);
		call("/api/v1/auth/email/verify/resend", resend, ip, null).andExpect(status().isAccepted());
		String second = lastCode(email, EmailCodeMessage.Kind.SIGN_UP);
		assertThat(mails(email)).hasSize(2);
		if (!second.equals(first)) {
			verifyCode(challenge, first, ip, null).andExpect(status().isUnauthorized());
		}
		verifyCode(challenge, second, ip, null).andExpect(status().isOk());
		call("/api/v1/auth/email/verify/resend", resend, ip, null).andExpect(status().isUnauthorized());
		call("/api/v1/auth/email/verify/resend", "{\"challengeId\":\"" + UUID.randomUUID() + "\"}", ip, null)
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OTP_INVALID"));
	}

	@Test
	void weakPasswordsAndBadAddressesAreValidationErrors() throws Exception {
		String ip = newIp();
		for (String[] c : new String[][] { { "short", "PASSWORD_TOO_SHORT" }, { "password123", "PASSWORD_TOO_COMMON" },
				{ "Password2024!", "PASSWORD_TOO_COMMON" }, { "qwertyuiop", "PASSWORD_TOO_COMMON" } }) {
			register(newEmail(), c[0], ip, null).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
					.andExpect(jsonPath("$.errors[0].field").value("password"))
					.andExpect(jsonPath("$.errors[0].code").value(c[1]));
		}
		String email = newEmail();
		register(email, email.toLowerCase(), ip, null).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("PASSWORD_MATCHES_EMAIL"));
		register("not-an-address", PASSWORD, ip, null).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("email"))
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_EMAIL"));
		call("/api/v1/auth/email/register", """
				{"email":"%s","password":"%s","ageConfirmed":false}""".formatted(newEmail(), PASSWORD), ip, null)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("ageConfirmed"));
		assertThat(mails(email)).isEmpty();
	}

	// ---------------------------------------------------------------- login

	@Test
	void unknownAddressAndWrongPasswordLookTheSameAndStillHash() throws Exception {
		String email = newEmail();
		String ip = newIp();
		signUp(email, ip, null);
		clearInvocations(hasher);
		login(newEmail(), PASSWORD, ip, null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		// no account: the hasher still ran one verification (against its dummy hash)
		verify(hasher).matches(any(), isNull());
		String unknownDetail = login(newEmail(), PASSWORD, ip, null).andReturn().getResponse().getContentAsString();
		String wrongDetail = login(email, OTHER_PASSWORD, ip, null).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andReturn().getResponse()
				.getContentAsString();
		assertThat(JsonPath.<String>read(unknownDetail, "$.detail")).isEqualTo(JsonPath.read(wrongDetail, "$.detail"));
	}

	@Test
	void tenWrongPasswordsLockTheCredentialUntilTheLockEnds() throws Exception {
		String email = newEmail();
		String ip = newIp();
		UUID user = UUID.fromString(json(signUp(email, ip, null), "$.user.id"));
		for (int i = 0; i < 9; i++) {
			login(email, OTHER_PASSWORD, ip, null).andExpect(status().isUnauthorized());
		}
		login(email, OTHER_PASSWORD, ip, null).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED")).andExpect(header().exists("Retry-After"));
		// even the right password is refused while locked
		login(email, PASSWORD, ip, null).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
		jdbc.update("UPDATE user_password_credentials SET locked_until = now() - interval '1 second' WHERE user_id = ?",
				user);
		login(email, PASSWORD, ip, null).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT failed_attempts FROM user_password_credentials WHERE user_id = ?",
				Integer.class, user)).isZero();
	}

	@Test
	void anUnverifiedAccountMustVerifyBeforeItSignsIn() throws Exception {
		String email = newEmail();
		String ip = newIp();
		UUID user = unverifiedAccount(email, PASSWORD);
		MvcResult first = login(email, PASSWORD, ip, null).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED")).andExpect(jsonPath("$.codeLength").value(6))
				.andExpect(jsonPath("$.resendAvailableAt").exists()).andReturn();
		String challenge = json(first, "$.challengeId");
		String code = lastCode(email, EmailCodeMessage.Kind.VERIFY_EMAIL);
		// a wrong password says nothing about verification
		login(email, OTHER_PASSWORD, ip, null).andExpect(status().isUnauthorized());
		// again within the cooldown: the same challenge, no second e-mail
		login(email, PASSWORD, ip, null).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.challengeId").value(challenge));
		assertThat(mails(email)).hasSize(1);
		verifyCode(challenge, code, ip, null).andExpect(status().isOk()).andExpect(jsonPath("$.isNewUser").value(false))
				.andExpect(jsonPath("$.user.id").value(user.toString()))
				.andExpect(jsonPath("$.user.emailVerified").value(true));
		login(email, PASSWORD, ip, null).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- squatting

	@Test
	void theMailboxOwnerTakesOverAnUnverifiedAccount() throws Exception {
		String email = newEmail();
		String ip = newIp();
		// a squatter registered the address while verification was off and holds a session
		UUID squatted = unverifiedAccount(email, OTHER_PASSWORD);
		jdbc.update("UPDATE user_password_credentials SET password_hash = ? WHERE user_id = ?",
				hasher.hash(PasswordPolicy.normalize(OTHER_PASSWORD)), squatted);
		MvcResult squatter = login(email, OTHER_PASSWORD, ip, null).andExpect(status().isForbidden()).andReturn();
		assertThat(json(squatter, "$.code")).isEqualTo("EMAIL_NOT_VERIFIED");
		// give the squatter a live session as it could have had while verification was off
		jdbc.update("""
				INSERT INTO user_sessions (user_id, token_family_id, refresh_token_hash, expires_at)
				VALUES (?, gen_random_uuid(), md5(random()::text) || md5(random()::text), now() + interval '30 days')""",
				squatted);
		assertThat(activeSessions(squatted)).isEqualTo(1);
		// the real owner signs up: not a conflict, because the address was never proven
		jdbc.update("UPDATE email_challenges SET last_sent_at = last_sent_at - interval '61 seconds'");
		String challenge = json(register(email, PASSWORD, newIp(), null).andExpect(status().isAccepted()).andReturn(),
				"$.challengeId");
		verifyCode(challenge, lastCode(email, EmailCodeMessage.Kind.SIGN_UP), newIp(), null).andExpect(status().isOk())
				.andExpect(jsonPath("$.user.id").value(squatted.toString())).andExpect(jsonPath("$.isNewUser").value(false))
				.andExpect(jsonPath("$.user.emailVerified").value(true));
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM user_sessions WHERE user_id = ? AND revoked_reason = 'PASSWORD_CHANGED'""",
				Integer.class, squatted)).isEqualTo(1);
		login(email, OTHER_PASSWORD, newIp(), null).andExpect(status().isUnauthorized());
		login(email, PASSWORD, newIp(), null).andExpect(status().isOk());
		// the squatter's pending verification code is void as well
		verifyCode(json(squatter, "$.challengeId"), "123456", newIp(), null).andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- guests

	@Test
	void aGuestIsUpgradedByVerifiedSignUpAndMergedBySignIn() throws Exception {
		String[] first = guest(UUID.randomUUID());
		String guest = first[0];
		String guestId = first[1];
		String email = newEmail();
		String ip = newIp();
		String challenge = json(register(email, PASSWORD, ip, guest).andExpect(status().isAccepted()).andReturn(),
				"$.challengeId");
		verifyCode(challenge, lastCode(email, EmailCodeMessage.Kind.SIGN_UP), ip, guest).andExpect(status().isOk())
				.andExpect(jsonPath("$.user.id").value(guestId)).andExpect(jsonPath("$.mergedGuestUserId").value(guestId))
				.andExpect(jsonPath("$.isNewUser").value(true)).andExpect(jsonPath("$.user.accountType").value("REGISTERED"));

		String[] other = guest(UUID.randomUUID());
		String second = other[0];
		String secondId = other[1];
		login(email, PASSWORD, ip, second).andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(guestId))
				.andExpect(jsonPath("$.mergedGuestUserId").value(secondId));
		assertThat(jdbc.queryForObject("SELECT account_status FROM users WHERE id = ?::uuid", String.class, secondId))
				.isEqualTo("MERGED");
		// the merged guest's token can no longer link anything
		login(email, PASSWORD, ip, second).andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- forgot / reset

	@Test
	void passwordResetDoesNotTellWhetherAnAccountExistsAndEndsEverySession() throws Exception {
		String email = newEmail();
		String ip = newIp();
		UUID user = UUID.fromString(json(signUp(email, ip, null), "$.user.id"));
		login(email, PASSWORD, ip, null).andExpect(status().isOk());
		assertThat(activeSessions(user)).isEqualTo(2);
		for (int i = 0; i < 10; i++) {
			login(email, OTHER_PASSWORD, ip, null);
		}
		login(email, PASSWORD, ip, null).andExpect(status().isTooManyRequests());

		String unknown = newEmail();
		call("/api/v1/auth/password/forgot", "{\"email\":\"" + unknown + "\"}", ip, null).andExpect(status().isAccepted());
		call("/api/v1/auth/password/forgot", "{\"email\":\"" + email + "\"}", ip, null).andExpect(status().isAccepted());
		verify(sender, timeout(5000).atLeastOnce())
				.send(org.mockito.ArgumentMatchers.argThat(m -> m.kind() == EmailCodeMessage.Kind.PASSWORD_RESET
						&& m.to().equalsIgnoreCase(email)));
		assertThat(mails(unknown)).isEmpty();
		String code = lastCode(email, EmailCodeMessage.Kind.PASSWORD_RESET);

		String reset = "{\"email\":\"%s\",\"token\":\"%s\",\"newPassword\":\"%s\"}";
		// every failure is the same answer, for a known and an unknown address
		call("/api/v1/auth/password/reset", reset.formatted(unknown, code, OTHER_PASSWORD), ip, null)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"));
		call("/api/v1/auth/password/reset", reset.formatted(email, wrong(code), OTHER_PASSWORD), ip, null)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESET_CODE_INVALID"))
				.andExpect(jsonPath("$.attemptsRemaining").doesNotExist());
		// a weak new password does not use the code up
		call("/api/v1/auth/password/reset", reset.formatted(email, code, "password123"), ip, null)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("newPassword"));
		call("/api/v1/auth/password/reset", reset.formatted(email, code, OTHER_PASSWORD), ip, null)
				.andExpect(status().isNoContent());
		assertThat(activeSessions(user)).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT count(*) FROM user_sessions WHERE user_id = ? AND revoked_reason = 'PASSWORD_CHANGED'""",
				Integer.class, user)).isEqualTo(2);
		// single use; the lock is gone; the old password is gone
		call("/api/v1/auth/password/reset", reset.formatted(email, code, "a brand new passphrase"), ip, null)
				.andExpect(status().isBadRequest());
		login(email, PASSWORD, ip, null).andExpect(status().isUnauthorized());
		login(email, OTHER_PASSWORD, ip, null).andExpect(status().isOk());
	}

	@Test
	void resettingAnUnverifiedAccountVerifiesIt() throws Exception {
		String email = newEmail();
		String ip = newIp();
		UUID user = unverifiedAccount(email, PASSWORD);
		call("/api/v1/auth/password/forgot", "{\"email\":\"" + email + "\"}", ip, null).andExpect(status().isAccepted());
		verify(sender, timeout(5000).atLeastOnce())
				.send(org.mockito.ArgumentMatchers.argThat(m -> m.to().equalsIgnoreCase(email)));
		String code = lastCode(email, EmailCodeMessage.Kind.PASSWORD_RESET);
		call("/api/v1/auth/password/reset", """
				{"email":"%s","token":"%s","newPassword":"%s"}""".formatted(email, code, OTHER_PASSWORD), ip, null)
				.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE id = ?", Boolean.class,
				user)).isTrue();
		login(email, OTHER_PASSWORD, ip, null).andExpect(status().isOk())
				.andExpect(jsonPath("$.user.emailVerified").value(true));
	}

	// ---------------------------------------------------------------- deletion and export

	@Test
	void deletionRemovesTheCredentialAndFreesTheAddressAndExportHasNoHash() throws Exception {
		String email = newEmail();
		String ip = newIp();
		UUID user = UUID.fromString(json(signUp(email, ip, null), "$.user.id"));

		List<String> exported = new ArrayList<>();
		UserExportParticipant identityExport = context.getBeansOfType(UserExportParticipant.class).values().stream()
				.filter(p -> p.getClass().getSimpleName().equals("IdentityExport")).findFirst().orElseThrow();
		identityExport.exportUser(user, new ExportWriter() {
			@Override
			public void section(String code, String dataClass, String description, List<Map<String, Object>> rows) {
				exported.add(code + "=" + rows);
			}

			@Override
			public void excluded(String code, String reason) {
			}
		});
		assertThat(exported).anyMatch(s -> s.startsWith("account=") && s.toLowerCase().contains(email.toLowerCase())
				&& s.contains("emailVerifiedAt="));
		assertThat(exported).anyMatch(s -> s.startsWith("authIdentities=") && s.contains("EMAIL"));
		assertThat(String.join("\n", exported)).doesNotContain("$argon2id$").doesNotContain("passwordHash");

		AccountDeletion deletion = context.getBean(AccountDeletion.class);
		UserDeletionParticipant identityPurge = context.getBeansOfType(UserDeletionParticipant.class).values().stream()
				.filter(p -> p.getClass().getSimpleName().equals("IdentityDeletionParticipant")).findFirst()
				.orElseThrow();
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		Instant now = Instant.now();
		tx.executeWithoutResult(s -> deletion.closeAccount(user, now));
		// closed: the address signs in no more and is free for a new account at once
		login(email, PASSWORD, ip, null).andExpect(status().isUnauthorized());
		tx.executeWithoutResult(s -> identityPurge.purgeUser(user, now));
		tx.executeWithoutResult(s -> deletion.anonymiseAccount(user, now));
		for (String table : new String[] { "user_password_credentials", "user_auth_identities", "email_challenges" }) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, user))
					.as(table).isZero();
		}
		assertThat(jdbc.queryForObject("SELECT email IS NULL AND email_normalized IS NULL FROM users WHERE id = ?",
				Boolean.class, user)).isTrue();
		jdbc.update("UPDATE email_challenges SET last_sent_at = last_sent_at - interval '61 seconds'");
		MvcResult again = signUp(email, newIp(), null);
		assertThat(json(again, "$.user.id")).isNotEqualTo(user.toString());
	}

	@Test
	void closedAccountInDeletionDoesNotBlockANewSignUp() throws Exception {
		String email = newEmail();
		UUID user = UUID.fromString(json(signUp(email, newIp(), null), "$.user.id"));
		new TransactionTemplate(transactionManager)
				.executeWithoutResult(s -> context.getBean(AccountDeletion.class).closeAccount(user, Instant.now()));
		jdbc.update("UPDATE email_challenges SET last_sent_at = last_sent_at - interval '61 seconds'");
		// the EMAIL identity of the closed account is still there until the purge step; it is released
		MvcResult again = signUp(email, newIp(), null);
		assertThat(json(again, "$.user.id")).isNotEqualTo(user.toString());
	}

	// ---------------------------------------------------------------- hygiene

	@Test
	void logsNeverCarryPasswordsCodesOrAddresses() throws Exception {
		String email = newEmail();
		try (CapturedLogs logs = new CapturedLogs()) {
			String ip = newIp();
			MvcResult r = register(email, PASSWORD, ip, null).andExpect(status().isAccepted()).andReturn();
			String code = lastCode(email, EmailCodeMessage.Kind.SIGN_UP);
			verifyCode(json(r, "$.challengeId"), wrong(code), ip, null);
			verifyCode(json(r, "$.challengeId"), code, ip, null).andExpect(status().isOk());
			login(email, OTHER_PASSWORD, ip, null);
			login(email, PASSWORD, ip, null).andExpect(status().isOk());
			assertThat(logs.texts()).noneMatch(t -> t.contains(PASSWORD)).noneMatch(t -> t.contains(OTHER_PASSWORD))
					.noneMatch(t -> t.contains(code)).noneMatch(t -> t.toLowerCase().contains(email.toLowerCase()));
		}
		verify(sender, never()).send(org.mockito.ArgumentMatchers.argThat(m -> m.code().length() != 6));
	}
}
