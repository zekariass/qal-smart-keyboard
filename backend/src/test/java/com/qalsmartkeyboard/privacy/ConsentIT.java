package com.qalsmartkeyboard.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.account.UserAccountStore.PhoneSignIn;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.privacy.ConsentDtos.ConsentDto;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B3.5 on PostgreSQL 18 through the real security chain, responses validated against the contract. The done-when: grant,
 * the dependent feature works; withdraw, the same call is blocked with {@code CONSENT_REQUIRED}; re-grant, it works again;
 * the history shows the three rows in order. The dependent feature is {@code PUT /me/preferences} with
 * {@code historyEnabled} (needs {@code SAVE_AI_HISTORY}).
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class ConsentIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	record User(UUID id, String bearer) {
	}

	@Autowired
	MockMvc mvc;
	/** Same application and security chain, no contract validation: the contract lists no 403 for PUT /me/preferences. */
	MockMvc unvalidated;
	@Autowired
	WebApplicationContext context;
	@Autowired
	@Qualifier("springSecurityFilterChain")
	Filter securityChain;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	UserAccountStore accounts;
	@Autowired
	TransactionTemplate transactions;
	@Autowired
	ConsentGate gate;
	@Autowired
	ConsentService consents;

	@BeforeEach
	void buildUnvalidatedMvc() {
		unvalidated = MockMvcBuilders.webAppContextSetup(context).addFilters(securityChain).build();
	}

	User user() {
		UUID id = transactions.execute(s -> {
			String phone = "+2517" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
			return ((PhoneSignIn.SignedIn) accounts.signInWithPhone(phone, true, Instant.now())).user().id();
		});
		return new User(id, "Bearer " + tokens.issue(id, UUID.randomUUID(), null, "REGISTERED").value());
	}

	User guest() {
		UUID id = transactions.execute(s -> accounts.createGuest(Instant.now()).id());
		return new User(id, "Bearer " + tokens.issue(id, UUID.randomUUID(), null, "GUEST").value());
	}

	ResultActions decide(User u, String code, String version, boolean granted) throws Exception {
		return mvc.perform(put("/api/v1/me/consents/" + code).header("Authorization", u.bearer())
				.header("X-Client-Platform", "ANDROID").contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"" + version + "\",\"isGranted\":" + granted
						+ ",\"details\":{\"surface\":\"settings\"}}"));
	}

	ResultActions savePreferences(User u, int revision, boolean history) throws Exception {
		return unvalidated.perform(put("/api/v1/me/preferences").header("Authorization", u.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"revision\":" + revision + ",\"historyEnabled\":" + history + "}"));
	}

	@Test
	void grantWithdrawRegrantGatesTheDependentFeatureAndKeepsHistory() throws Exception {
		User u = user();
		// Not granted: blocked, with the consent type as an extension member.
		savePreferences(u, 0, true).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
				.andExpect(jsonPath("$.consentCode").value("SAVE_AI_HISTORY"));
		assertThat(gate.hasConsent(u.id(), ConsentType.SAVE_AI_HISTORY)).isFalse();

		decide(u, "SAVE_AI_HISTORY", "1", true).andExpect(status().isOk()).andExpect(jsonPath("$.isGranted").value(true))
				.andExpect(jsonPath("$.revokedAt").doesNotExist());
		savePreferences(u, 0, true).andExpect(status().isOk()).andExpect(jsonPath("$.historyEnabled").value(true))
				.andExpect(jsonPath("$.revision").value(1));

		// Withdrawal: new writes are blocked and the stored switch is turned off (a revisioned write).
		decide(u, "SAVE_AI_HISTORY", "1", false).andExpect(status().isOk())
				.andExpect(jsonPath("$.isGranted").value(false)).andExpect(jsonPath("$.revokedAt").exists())
				.andExpect(jsonPath("$.grantedAt").exists());
		assertThat(gate.hasConsent(u.id(), ConsentType.SAVE_AI_HISTORY)).isFalse();
		unvalidated.perform(get("/api/v1/me/preferences").header("Authorization", u.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.historyEnabled").value(false)).andExpect(jsonPath("$.revision").value(2));
		savePreferences(u, 2, true).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
		assertThatThrownBy(() -> gate.requireConsent(u.id(), ConsentType.SAVE_AI_HISTORY))
				.isInstanceOf(ApiException.class);

		// Re-grant: works again.
		decide(u, "SAVE_AI_HISTORY", "2", true).andExpect(status().isOk());
		savePreferences(u, 2, true).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
		gate.requireConsent(u.id(), ConsentType.SAVE_AI_HISTORY);

		// History: all three decisions, oldest first; the list shows the latest only.
		List<ConsentDto> history = consents.history(u.id(), ConsentType.SAVE_AI_HISTORY);
		assertThat(history).extracting(ConsentDto::isGranted).containsExactly(true, false, true);
		assertThat(history).extracting(ConsentDto::consentVersion).containsExactly("1", "1", "2");
		assertThat(jdbc.queryForList("SELECT source_platform FROM user_consents WHERE user_id = ?", String.class,
				u.id())).containsOnly("ANDROID");
		assertThat(jdbc.queryForObject("SELECT metadata->'details'->>'surface' FROM user_consents WHERE id = ?",
				String.class, history.get(0).id())).isEqualTo("settings");
		mvc.perform(get("/api/v1/me/consents").header("Authorization", u.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].consentVersion").value("2"))
				.andExpect(jsonPath("$.items[0].isGranted").value(true));
	}

	@Test
	void consentsAreIndependentPerTypeAndPerUser() throws Exception {
		User a = user();
		User b = user();
		decide(a, "ANALYTICS", "1", true).andExpect(status().isOk());
		decide(a, "DICTIONARY_SYNC", "1", true).andExpect(status().isOk());
		decide(a, "DICTIONARY_SYNC", "1", false).andExpect(status().isOk());
		assertThat(gate.hasConsent(a.id(), ConsentType.ANALYTICS)).isTrue();
		assertThat(gate.hasConsent(a.id(), ConsentType.DICTIONARY_SYNC)).isFalse();
		assertThat(gate.hasConsent(b.id(), ConsentType.ANALYTICS)).isFalse();
		mvc.perform(get("/api/v1/me/consents").header("Authorization", a.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2));
		mvc.perform(get("/api/v1/me/consents").header("Authorization", b.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void termsAndPrivacyCannotBeWithdrawnAndBadInputIs4xx() throws Exception {
		User u = user();
		decide(u, "TERMS_ACCEPTANCE", "1", true).andExpect(status().isOk());
		decide(u, "TERMS_ACCEPTANCE", "1", false).andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		decide(u, "PRIVACY_NOTICE", "1", false).andExpect(status().isUnprocessableContent());
		assertThat(gate.hasConsent(u.id(), ConsentType.TERMS_ACCEPTANCE)).isTrue();
		decide(u, "NOT_A_CONSENT", "1", true).andExpect(status().isBadRequest());
		mvc.perform(put("/api/v1/me/consents/ANALYTICS").header("Authorization", u.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"consentVersion\":\"1\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/me/consents")).andExpect(status().isUnauthorized());
	}

	@Test
	void guestsMayDecideAndTheLogStaysAppendOnly() throws Exception {
		User g = guest();
		decide(g, "ANALYTICS", "1", false).andExpect(status().isOk());
		assertThat(gate.hasConsent(g.id(), ConsentType.ANALYTICS)).isFalse();
		UUID id = jdbc.queryForObject("SELECT id FROM user_consents WHERE user_id = ?", UUID.class, g.id());
		assertThatThrownBy(() -> jdbc.update("UPDATE user_consents SET is_granted = TRUE WHERE id = ?", id))
				.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update("DELETE FROM user_consents WHERE id = ?", id))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void everyConsentTypeMatchesTheSchemaCheckConstraint() throws Exception {
		User u = user();
		for (ConsentType t : ConsentType.values()) {
			decide(u, t.name(), "1", true).andExpect(status().isOk());
			assertThat(t.gatedFeatures()).isNotEmpty();
		}
		assertThat(consents.latest(u.id())).hasSize(ConsentType.values().length);
	}
}
