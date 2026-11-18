package com.qalsmartkeyboard.languages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;
import com.qalsmartkeyboard.identity.account.UserAccountStore;
import com.qalsmartkeyboard.identity.account.UserAccountStore.PhoneSignIn;
import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.privacy.RetentionJob;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * D31 (ADR 0007, B6.3): dictionary tombstones are purged after 180 days, and a client whose sync cursor is older than a
 * purged tombstone gets 410 {@code SYNC_CURSOR_EXPIRED} (a contract-validated Problem) and downloads the dictionary
 * again. The purge is the real retention job; the tombstone is aged by moving its {@code deleted_at} 181 days back.
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class DictionaryResyncIT {

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
	TransactionTemplate transactions;
	@Autowired
	RetentionJob retention;

	record User(UUID id, String bearer) {
	}

	User user() throws Exception {
		UUID id = transactions.execute(s -> {
			String phone = "+2519" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
			return ((PhoneSignIn.SignedIn) accounts.signInWithPhone(phone, true, Instant.now())).user().id();
		});
		User u = new User(id, "Bearer " + tokens.issue(id, UUID.randomUUID(), null, "REGISTERED").value());
		mvc.perform(put("/api/v1/me/consents/DICTIONARY_SYNC").header("Authorization", u.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"consentVersion\":\"1\",\"isGranted\":true}"))
				.andExpect(status().isOk());
		return u;
	}

	static String change(String op, String word) {
		return "{\"op\":\"" + op + "\",\"languageCode\":\"am\",\"word\":\"" + word + "\",\"clientChangeId\":\""
				+ UUID.randomUUID() + "\"}";
	}

	ResultActions push(User u, String... changes) throws Exception {
		return mvc.perform(post("/api/v1/me/dictionary/changes").header("Authorization", u.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"changes\":[" + String.join(",", changes) + "]}"));
	}

	ResultActions pull(User u, String since, int limit) throws Exception {
		return mvc.perform(get("/api/v1/me/dictionary/changes?limit=" + limit + (since == null ? "" : "&since=" + since))
				.header("Authorization", u.bearer()));
	}

	String pullOk(User u, String since, int limit) throws Exception {
		return pull(u, since, limit).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	static List<String> words(String json) {
		List<String> out = new ArrayList<>();
		int n = JsonPath.read(json, "$.changes.length()");
		for (int i = 0; i < n; i++) {
			out.add(JsonPath.read(json, "$.changes[" + i + "].op") + ":" + JsonPath.read(json, "$.changes[" + i + "].word"));
		}
		return out;
	}

	static String token(String json) {
		return JsonPath.read(json, "$.syncToken");
	}

	@Test
	void aCursorBehindAPurgedTombstoneMustResyncAndEveryoneElseIsUntouched() throws Exception {
		User u = user();
		push(u, change("ADD", "ሰላም"), change("ADD", "ቤት"), change("ADD", "ውሃ")).andExpect(status().isOk());
		String staleCursor = token(pullOk(u, null, 100)); // a device that synced all three words (version 3)
		String fullBefore = pullOk(u, null, 100);
		assertThat(words(fullBefore)).containsExactly("ADD:ሰላም", "ADD:ቤት", "ADD:ውሃ");

		// another device deletes one word: the tombstone gets version 4
		push(u, change("DELETE", "ቤት")).andExpect(status().isOk());
		String caughtUpCursor = token(pullOk(u, staleCursor, 100)); // this device pulls the tombstone: version 4
		assertThat(caughtUpCursor).isNotEqualTo(staleCursor);

		// a tombstone younger than 180 days survives the job, and nothing changes for anybody
		jdbc.update("UPDATE user_dictionary_entries SET deleted_at = NOW() - INTERVAL '179 days' WHERE user_id = ?"
				+ " AND deleted_at IS NOT NULL", u.id());
		RetentionJob.Result young = retention.run();
		assertThat(young.failed()).isEmpty();
		assertThat(floor(u)).isZero();
		assertThat(words(pullOk(u, staleCursor, 100))).containsExactly("DELETE:ቤት");

		// 181 days: purged, and the user's floor becomes the tombstone's version
		jdbc.update("UPDATE user_dictionary_entries SET deleted_at = NOW() - INTERVAL '181 days' WHERE user_id = ?"
				+ " AND deleted_at IS NOT NULL", u.id());
		RetentionJob.Result result = retention.run();
		assertThat(result.failed()).isEmpty();
		assertThat(result.purged()).containsEntry("dictionary-tombstones", 1);
		assertThat(floor(u)).isEqualTo(4L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_dictionary_entries WHERE user_id = ?", Integer.class,
				u.id())).isEqualTo(2);

		// the device that never saw the delete: 410, a Problem with the stable code (contract-validated)
		pull(u, staleCursor, 100).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("SYNC_CURSOR_EXPIRED"))
				.andExpect(jsonPath("$.status").value(410))
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"));

		// the device that saw it is fine: its cursor is at the purged version
		assertThat(words(pullOk(u, caughtUpCursor, 100))).isEmpty();

		// the resync: pull without a token, in pages. The pages' tokens carry the floor, so the download never trips
		String page1 = pullOk(u, null, 1);
		assertThat(words(page1)).containsExactly("ADD:ሰላም");
		assertThat((Boolean) JsonPath.read(page1, "$.hasMore")).isTrue();
		String page2 = pullOk(u, token(page1), 1);
		assertThat(words(page2)).containsExactly("ADD:ውሃ");
		String done = token(page2);
		assertThat(words(pullOk(u, done, 1))).isEmpty();

		// a push after the purge still works and its changes reach a cursor issued after the purge
		push(u, change("ADD", "አበባ")).andExpect(status().isOk());
		assertThat(words(pullOk(u, done, 100))).containsExactly("ADD:አበባ");

		// the purged word can be added again (fresh row)
		push(u, change("ADD", "ቤት")).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));
		assertThat(words(pullOk(u, done, 100))).containsExactly("ADD:አበባ", "ADD:ቤት");

		// a token the service did not issue is still 400
		pull(u, "v2.", 10).andExpect(status().isBadRequest());
		pull(u, "v2.MDow", 10).andExpect(status().isBadRequest()); // "0:0": a v2 token has a positive floor
	}

	@Test
	void aUserWithoutPurgedTombstonesNeverSeesTheError() throws Exception {
		User u = user();
		push(u, change("ADD", "ሰላም")).andExpect(status().isOk());
		String cursor = token(pullOk(u, null, 100));
		push(u, change("DELETE", "ሰላም")).andExpect(status().isOk());
		jdbc.update("UPDATE user_dictionary_entries SET deleted_at = NOW() - INTERVAL '10 days' WHERE user_id = ?",
				u.id());
		assertThat(retention.run().failed()).isEmpty();
		assertThat(floor(u)).isZero();
		assertThat(words(pullOk(u, cursor, 100))).containsExactly("DELETE:ሰላም");
		assertThat(words(pullOk(u, "v1.MA", 100))).containsExactly("DELETE:ሰላም"); // version 0 = a fresh client
	}

	long floor(User u) {
		return jdbc.queryForObject("SELECT tombstone_floor FROM user_dictionary_sync_state WHERE user_id = ?", Long.class,
				u.id());
	}
}
