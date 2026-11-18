package com.qalsmartkeyboard.entitlements.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.PublicKey;
import java.sql.Timestamp;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.account.UserAccountStore.PhoneSignIn;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B4.4 on PostgreSQL 18 with the real V1 plan seed, through the real security chain, contract-validated: the token
 * reflects the resolved plan (PREMIUM opens {@code ON_DEVICE_AI_MODELS}, FREE and GUEST do not), verifies against the
 * served key set, and suspended, deletion-pending and merged accounts get no token even with a still-valid access
 * token.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class EntitlementTokenIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	record User(UUID id, String bearer) {
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService accessTokens;
	@Autowired
	UserAccountStore accounts;
	@Autowired
	TransactionTemplate transactions;
	@Autowired
	EntitlementTokenService tokens;

	User registered() {
		UUID id = transactions.execute(s -> {
			String phone = "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
			return ((PhoneSignIn.SignedIn) accounts.signInWithPhone(phone, true, Instant.now())).user().id();
		});
		return new User(id, "Bearer " + accessTokens.issue(id, UUID.randomUUID(), null, "REGISTERED").value());
	}

	User guest() {
		UUID id = transactions.execute(s -> accounts.createGuest(Instant.now()).id());
		return new User(id, "Bearer " + accessTokens.issue(id, UUID.randomUUID(), null, "GUEST").value());
	}

	EntitlementTokenVerifier.Verified fetchAndVerify(User u) throws Exception {
		String body = mvc.perform(get("/api/v1/me/entitlements/token").header("Authorization", u.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return verifier().verify(JSONObjectUtils.parse(body).get("jws").toString(), Instant.now(), u.id(), null);
	}

	EntitlementTokenVerifier verifier() throws Exception {
		String keys = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		return new EntitlementTokenVerifier(pinned(keys), "qal-smart-keyboard", "qal-keyboard", Duration.ofMinutes(5));
	}

	static Map<String, PublicKey> pinned(String jwks) throws ParseException {
		Map<String, PublicKey> pinned = new LinkedHashMap<>();
		for (JWK jwk : JWKSet.parse(jwks).getKeys()) {
			pinned.put(jwk.getKeyID(), EntitlementTokenKeys.publicFromRaw(((OctetKeyPair) jwk).getDecodedX()));
		}
		return pinned;
	}

	@Test
	void premiumOpensTheOnDeviceGateFreeAndGuestDoNot() throws Exception {
		User premium = registered();
		subscribe(premium.id(), "PREMIUM");
		var p = fetchAndVerify(premium);
		assertThat(p.planCode()).isEqualTo("PREMIUM");
		assertThat(p.features()).containsExactly(Map.entry("ON_DEVICE_AI_MODELS", true), Map.entry("PREMIUM_THEMES", true));
		assertThat(p.allows("PREMIUM_THEMES", false)).isTrue();
		assertThat(p.policyVersion()).isEqualTo("PROVISIONAL_V2");
		assertThat(p.allows("ON_DEVICE_AI_MODELS", false)).isTrue();

		User free = registered();
		var f = fetchAndVerify(free);
		assertThat(f.planCode()).isEqualTo("FREE");
		assertThat(f.features()).containsExactly(Map.entry("ON_DEVICE_AI_MODELS", false), Map.entry("PREMIUM_THEMES", false));

		User guest = guest();
		var g = fetchAndVerify(guest);
		assertThat(g.planCode()).isEqualTo("GUEST");
		assertThat(g.features()).containsExactly(Map.entry("ON_DEVICE_AI_MODELS", false), Map.entry("PREMIUM_THEMES", false));
	}

	@Test
	void suspendedAndDeletionPendingAccountsGetNoTokenWhileTheirAccessTokenStillWorks() throws Exception {
		User user = registered();
		subscribe(user.id(), "PREMIUM");
		fetchAndVerify(user);

		jdbc.update("UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?", user.id());
		mvc.perform(get("/api/v1/me/entitlements/token").header("Authorization", user.bearer()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));

		jdbc.update("UPDATE users SET account_status = 'DELETION_PENDING' WHERE id = ?", user.id());
		mvc.perform(get("/api/v1/me/entitlements/token").header("Authorization", user.bearer()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DELETION_PENDING"));

		jdbc.update("UPDATE users SET account_status = 'ACTIVE' WHERE id = ?", user.id());
		assertThat(fetchAndVerify(user).features()).containsEntry("ON_DEVICE_AI_MODELS", true);
	}

	@Test
	void aMergedGuestGetsNoToken() throws Exception {
		User guest = guest();
		fetchAndVerify(guest);
		User target = registered();
		jdbc.update("UPDATE users SET account_status = 'MERGED', merged_into_user_id = ? WHERE id = ?", target.id(),
				guest.id());
		// the security chain refuses the inactive guest's token (B2.6) ...
		mvc.perform(get("/api/v1/me/entitlements/token").header("Authorization", guest.bearer()))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("GUEST_SESSION_INVALID"));
		// ... and the issuer refuses the merged row on its own
		assertThatThrownBy(() -> tokens.issue(guest.id(), null)).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo(ApiErrorCode.GUEST_SESSION_INVALID));
		// the account it was merged into gets its own token
		assertThat(fetchAndVerify(target).subject()).isEqualTo(target.id());
	}

	private void subscribe(UUID user, String plan) {
		Instant start = Instant.now().minus(Duration.ofDays(1));
		Instant end = Instant.now().plus(Duration.ofDays(29));
		jdbc.update("""
				INSERT INTO user_subscriptions (user_id, plan_id, source_type, billing_mode, subscription_status,
				    current_period_start, current_period_end)
				VALUES (?, (SELECT id FROM subscription_plans WHERE plan_code = ?), 'PAYMENT', 'MANUAL_RENEW', 'ACTIVE', ?, ?)""",
				user, plan, Timestamp.from(start), Timestamp.from(end));
	}
}
