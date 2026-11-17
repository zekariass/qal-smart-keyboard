package com.qalsmartkeyboard.identity.guest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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

/**
 * B2.3 fail closed: a deployment with no verifier adapter (what production is until the real Play Integrity and App
 * Attest adapters exist) still issues nonces, but never creates a guest, even for a token in the mock format, and never
 * marks a device attested. The production-profile refusal of the mock switch itself is tested in
 * {@code AttestationVerifierConfigurationTest}.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.identity.attestation.mock.enabled=false" })
@AutoConfigureMockMvc
@Testcontainers
class GuestFailClosedIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	UserAccountStore accounts;
	@Autowired
	SessionIssuer issuer;
	@Autowired
	TransactionTemplate transactions;

	String nonce() throws Exception {
		MvcResult r = mvc.perform(post("/api/v1/auth/attestation-nonces")).andExpect(status().isCreated()).andReturn();
		return JsonPath.read(r.getResponse().getContentAsString(), "$.nonce");
	}

	@Test
	void noVerifierMeansNoGuestEvenWithAWellFormedMockToken() throws Exception {
		String nonce = nonce();
		int before = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
		mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"" + UUID.randomUUID() + "\",\"platform\":\"ANDROID\",\"attestation\":"
						+ "{\"provider\":\"PLAY_INTEGRITY\",\"token\":\"" + MockAttestationTokens.playIntegrity(nonce)
						+ "\",\"nonce\":\"" + nonce + "\"}}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
		String ios = nonce();
		mvc.perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"" + UUID.randomUUID() + "\",\"platform\":\"IOS\",\"attestation\":"
						+ "{\"provider\":\"APP_ATTEST\",\"keyId\":\"k\",\"token\":\"" + MockAttestationTokens.appAttest(ios)
						+ "\",\"nonce\":\"" + ios + "\"}}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_devices WHERE attestation_status = 'VALID'",
				Integer.class)).isZero();
	}

	@Test
	void noVerifierLeavesADeviceUnattested() throws Exception {
		String access = transactions.execute(status -> {
			PhoneSignIn.SignedIn in = (PhoneSignIn.SignedIn) accounts.signInWithPhone("+251711223344", true,
					Instant.now());
			UserAccount user = in.user();
			IssuedSession s = issuer.issue(user, new SessionContext(null, "10.0.0.1", "it"));
			return s.accessToken();
		});
		MvcResult registered = mvc.perform(post("/api/v1/devices").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"installationId\":\"" + UUID.randomUUID() + "\",\"platform\":\"ANDROID\"}"))
				.andExpect(status().isOk()).andReturn();
		String deviceId = JsonPath.read(registered.getResponse().getContentAsString(), "$.id");
		String nonce = nonce();
		mvc.perform(post("/api/v1/devices/" + deviceId + "/attestations").header("Authorization", "Bearer " + access)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"provider\":\"PLAY_INTEGRITY\",\"token\":\"" + MockAttestationTokens.playIntegrity(nonce)
						+ "\",\"nonce\":\"" + nonce + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.attestationStatus").value("UNSUPPORTED"));
		assertThat(jdbc.queryForObject("SELECT attestation_status FROM user_devices WHERE id = ?::uuid", String.class,
				deviceId)).isEqualTo("UNSUPPORTED");
	}
}
