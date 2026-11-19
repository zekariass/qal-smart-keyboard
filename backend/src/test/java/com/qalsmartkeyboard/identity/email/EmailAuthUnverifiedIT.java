package com.qalsmartkeyboard.identity.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.identity.attestation.MockAttestationTokens;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * ADR 0033 with e-mail verification <b>off</b> and no e-mail provider ({@code none}): the owner's quick-test setup and
 * what production runs until a vendor exists. Sign-up signs in at once with an unverified address; flows that need
 * an e-mail answer 503 {@code EMAIL_NOT_CONFIGURED} without telling anything about the address.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.identity.email.verification-required=false",
		"qal.notifications.email.provider=none" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class EmailAuthUnverifiedIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final String PASSWORD = "bunna and injera 4ever";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;

	static String newEmail() {
		return "quick" + ThreadLocalRandom.current().nextInt(1_000_000_000) + "@example.com";
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

	ResultActions register(String email, String ip, String bearer, UUID installation) throws Exception {
		String device = installation == null ? ""
				: ",\"device\":{\"installationId\":\"" + installation + "\",\"platform\":\"ANDROID\"}";
		return call("/api/v1/auth/email/register", """
				{"email":"%s","password":"%s","ageConfirmed":true%s}""".formatted(email, PASSWORD, device), ip, bearer);
	}

	@Test
	void signUpSignsInAtOnceWithAnUnverifiedAddress() throws Exception {
		String email = newEmail();
		String ip = newIp();
		UUID installation = UUID.randomUUID();
		MvcResult r = register(email, ip, null, installation).andExpect(status().isCreated())
				.andExpect(jsonPath("$.tokenType").value("Bearer")).andExpect(jsonPath("$.isNewUser").value(true))
				.andExpect(jsonPath("$.deviceId").exists()).andExpect(jsonPath("$.user.email").value(email))
				.andExpect(jsonPath("$.user.emailVerified").value(false)).andReturn();
		UUID user = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.user.id"));
		assertThat(jdbc.queryForObject("SELECT email_verified_at IS NULL FROM users WHERE id = ?", Boolean.class, user))
				.isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM email_challenges", Integer.class)).isZero();
		call("/api/v1/auth/email/login", """
				{"email":"%s","password":"%s"}""".formatted(email.toUpperCase(), PASSWORD), ip, null)
				.andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(user.toString()))
				.andExpect(jsonPath("$.user.emailVerified").value(false));
		register(email.toUpperCase(), ip, null, null).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
		// the refresh token works like any other session's
		String refresh = JsonPath.read(r.getResponse().getContentAsString(), "$.refreshToken");
		call("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refresh + "\"}", ip, null).andExpect(status().isOk());
	}

	@Test
	void aGuestIsUpgradedInPlaceBySignUp() throws Exception {
		String ip = newIp();
		UUID installation = UUID.randomUUID();
		String nonce = JsonPath.read(mvc.perform(post("/api/v1/auth/attestation-nonces").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		})).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.nonce");
		String guest = mvc.perform(post("/api/v1/auth/guest").with(r -> {
			r.setRemoteAddr(ip);
			return r;
		}).contentType(MediaType.APPLICATION_JSON).content("""
				{"installationId":"%s","platform":"ANDROID",
				 "attestation":{"provider":"PLAY_INTEGRITY","token":"%s","nonce":"%s"}}
				""".formatted(installation, MockAttestationTokens.playIntegrity(nonce), nonce)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String guestId = JsonPath.read(guest, "$.user.id");
		register(newEmail(), ip, JsonPath.read(guest, "$.accessToken"), installation).andExpect(status().isCreated())
				.andExpect(jsonPath("$.user.id").value(guestId)).andExpect(jsonPath("$.mergedGuestUserId").value(guestId))
				.andExpect(jsonPath("$.user.accountType").value("REGISTERED"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_auth_identities WHERE user_id = ?::uuid "
				+ "AND identity_provider = 'EMAIL'", Integer.class, guestId)).isEqualTo(1);
	}

	@Test
	void withoutAnEmailProviderResetAndResendAnswer503ForEveryAddress() throws Exception {
		String email = newEmail();
		String ip = newIp();
		register(email, ip, null, null).andExpect(status().isCreated());
		for (String address : new String[] { email, newEmail() }) {
			call("/api/v1/auth/password/forgot", "{\"email\":\"" + address + "\"}", ip, null)
					.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EMAIL_NOT_CONFIGURED"));
			call("/api/v1/auth/password/reset", """
					{"email":"%s","token":"123456","newPassword":"a brand new passphrase"}""".formatted(address), ip,
					null).andExpect(status().isServiceUnavailable());
		}
		call("/api/v1/auth/email/verify/resend", "{\"challengeId\":\"" + UUID.randomUUID() + "\"}", ip, null)
				.andExpect(status().isServiceUnavailable());
	}
}
