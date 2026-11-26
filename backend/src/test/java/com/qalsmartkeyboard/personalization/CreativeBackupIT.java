package com.qalsmartkeyboard.personalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.identity.token.AccessTokenService;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.CryptoShreddedException;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T8.15 S1 "done when" (backend half): the opt-in encrypted creative backup end to end on PostgreSQL 18 through the
 * security chain, every response validated against {@code contracts/openapi.yaml}: the consent is off by default (403),
 * guests are refused, a byte-exact round trip, revisions and compare-and-set, the idempotent PUT, the caps, the
 * withdrawal deletes everything, crypto-shredding makes the content unreadable and account deletion removes the rows,
 * the content never reaches a column, log line or metric in the clear, the rate limit, and the database guards. The caps
 * and the rate limit are set small here; the defaults are checked by {@code CreativeBackupPropertiesTest}.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.creative-backup.max-item-bytes=1024",
		"qal.creative-backup.max-items-per-kind=3", "qal.creative-backup.max-total-bytes=2048",
		"qal.creative-backup.rate-limit=40" })
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class CreativeBackupIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final String BASE = "/api/v1/me/creative-backup";
	static final String MARKER = "Zebra lighthouse marmalade";
	static final String UPDATED_AT = "2026-11-26T10:15:30.123456Z";

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	AccessTokenService tokens;
	@Autowired
	UserDataEncryption encryption;
	@Autowired
	TransactionTemplate transactions;
	@Autowired
	CreativeBackupLifecycle lifecycle;
	@Autowired
	MeterRegistry meters;
	@Autowired
	JsonMapper json;

	// ======================================================================================= helpers

	UUID user(String type) {
		return jdbc.queryForObject("""
				INSERT INTO users (account_type, account_status, time_zone) VALUES (?, 'ACTIVE', 'Africa/Addis_Ababa')
				RETURNING id""", UUID.class, type);
	}

	String bearer(UUID user) {
		return bearer(user, "REGISTERED");
	}

	String bearer(UUID user, String type) {
		return "Bearer " + tokens.issue(user, UUID.randomUUID(), null, type).value();
	}

	void consent(UUID user, boolean granted) throws Exception {
		mvc.perform(put("/api/v1/me/consents/CREATIVE_BACKUP").header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"1\",\"isGranted\":" + granted + "}")).andExpect(status().isOk());
	}

	/** A registered user who granted the consent. */
	UUID backupUser() throws Exception {
		UUID u = user("REGISTERED");
		consent(u, true);
		return u;
	}

	static byte[] bytes(int n, int seed) {
		byte[] b = new byte[n];
		for (int i = 0; i < n; i++) {
			b[i] = (byte) (i * 31 + seed);
		}
		return b;
	}

	static String body(byte[] content, String updatedAt, Long expectedRevision) {
		return body("DRAWING", Base64.getEncoder().encodeToString(content), updatedAt, expectedRevision);
	}

	static String body(String kind, String content, String updatedAt, Long expectedRevision) {
		return "{\"kind\":\"" + kind + "\",\"updatedAt\":\"" + updatedAt + "\""
				+ (expectedRevision == null ? "" : ",\"expectedRevision\":" + expectedRevision) + ",\"content\":\""
				+ content + "\"}";
	}

	ResultActions putItem(UUID user, String itemId, String body) throws Exception {
		return mvc.perform(put(BASE + "/items/" + itemId).header("Authorization", bearer(user))
				.contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)));
	}

	ResultActions putItem(UUID user, String itemId, byte[] content) throws Exception {
		return putItem(user, itemId, body(content, UPDATED_AT, null));
	}

	ResultActions getItem(UUID user, String itemId) throws Exception {
		return mvc.perform(get(BASE + "/items/" + itemId).header("Authorization", bearer(user)));
	}

	ResultActions list(UUID user, String query) throws Exception {
		return mvc.perform(get(BASE + "/items" + query).header("Authorization", bearer(user)));
	}

	ResultActions summary(UUID user) throws Exception {
		return mvc.perform(get(BASE + "/summary").header("Authorization", bearer(user)));
	}

	ResultActions deleteItem(UUID user, String itemId) throws Exception {
		return mvc.perform(delete(BASE + "/items/" + itemId).header("Authorization", bearer(user)));
	}

	ResultActions clear(UUID user) throws Exception {
		return mvc.perform(delete(BASE).header("Authorization", bearer(user)));
	}

	JsonNode read(ResultActions r) throws Exception {
		return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	long rows(UUID user) {
		return jdbc.queryForObject("SELECT count(*) FROM creative_backup_items WHERE user_id = ?", Long.class, user);
	}

	Map<String, Object> row(UUID user, String itemId) {
		return jdbc.queryForMap("SELECT * FROM creative_backup_items WHERE user_id = ? AND client_item_id = ?", user,
				itemId);
	}

	static final org.springframework.test.web.servlet.ResultMatcher NO_STORE = header().string("Cache-Control",
			Matchers.containsString("no-store"));

	// ======================================================================================= gates

	@Test
	void theConsentIsOffByDefaultAndGatesEveryContentOperation() throws Exception {
		UUID u = user("REGISTERED");
		summary(u).andExpect(status().isOk()).andExpect(NO_STORE).andExpect(jsonPath("$.consentGranted").value(false))
				.andExpect(jsonPath("$.itemCount").value(0)).andExpect(jsonPath("$.maxItemBytes").value(1024))
				.andExpect(jsonPath("$.maxItemsPerKind").value(3)).andExpect(jsonPath("$.maxTotalBytes").value(2048))
				.andExpect(jsonPath("$.kinds[0].kind").value("DRAWING"));
		for (ResultActions r : List.of(list(u, ""), getItem(u, "a1"), putItem(u, "a1", bytes(10, 1)))) {
			r.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
					.andExpect(jsonPath("$.consentCode").value("CREATIVE_BACKUP"));
		}
		assertThat(rows(u)).isZero();
		// erasing never needs the consent
		deleteItem(u, "a1").andExpect(status().isNotFound());
		clear(u).andExpect(status().isNoContent());
		// a withdrawn consent gates the same way
		consent(u, true);
		consent(u, false);
		putItem(u, "a1", bytes(10, 1)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
		// granted: allowed
		consent(u, true);
		putItem(u, "a1", bytes(10, 1)).andExpect(status().isCreated());
		summary(u).andExpect(jsonPath("$.consentGranted").value(true)).andExpect(jsonPath("$.itemCount").value(1));
		// no token at all
		mvc.perform(get(BASE + "/summary")).andExpect(status().isUnauthorized());
		mvc.perform(put(BASE + "/items/a1").contentType(MediaType.APPLICATION_JSON).content(body(bytes(4, 1), UPDATED_AT,
				null))).andExpect(status().isUnauthorized());
	}

	@Test
	void guestsAreRefusedEvenWithTheConsent() throws Exception {
		UUID g = user("GUEST");
		mvc.perform(put("/api/v1/me/consents/CREATIVE_BACKUP").header("Authorization", bearer(g, "GUEST"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"consentVersion\":\"1\",\"isGranted\":true}"))
				.andExpect(status().isOk());
		String guest = bearer(g, "GUEST");
		for (var request : List.of(get(BASE + "/summary"), get(BASE + "/items"), get(BASE + "/items/a1"),
				put(BASE + "/items/a1").contentType(MediaType.APPLICATION_JSON).content(body(bytes(4, 1), UPDATED_AT, null)),
				delete(BASE + "/items/a1"), delete(BASE))) {
			mvc.perform(request.header("Authorization", guest)).andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
		}
		assertThat(rows(g)).isZero();
	}

	// ======================================================================================= round trip

	@Test
	void aRoundTripIsByteExactAndScopedToTheOwner() throws Exception {
		UUID u = backupUser();
		byte[] first = new byte[256];
		for (int i = 0; i < first.length; i++) {
			first[i] = (byte) i; // every byte value
		}
		Instant before = Instant.now();
		putItem(u, "drawing-1", first).andExpect(status().isCreated()).andExpect(NO_STORE)
				.andExpect(jsonPath("$.itemId").value("drawing-1")).andExpect(jsonPath("$.kind").value("DRAWING"))
				.andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.sizeBytes").value(256))
				.andExpect(jsonPath("$.updatedAt").value(UPDATED_AT)).andExpect(jsonPath("$.content").doesNotExist());
		String uuidId = UUID.randomUUID().toString();
		putItem(u, uuidId, bytes(100, 7)).andExpect(status().isCreated());

		JsonNode item = read(getItem(u, "drawing-1").andExpect(status().isOk()).andExpect(NO_STORE));
		assertThat(Base64.getDecoder().decode(item.get("content").asString())).isEqualTo(first);
		assertThat(item.get("revision").longValue()).isEqualTo(1L);
		assertThat(Instant.parse(item.get("storedAt").asString())).isAfterOrEqualTo(before.minusSeconds(1));
		assertThat(item.get("createdAt").asString()).isEqualTo(item.get("storedAt").asString());

		JsonNode page = read(list(u, "").andExpect(status().isOk()).andExpect(NO_STORE));
		assertThat(page.get("items")).hasSize(2);
		assertThat(page.get("items").findValuesAsString("itemId")).containsExactlyInAnyOrder("drawing-1", uuidId);
		assertThat(page.toString()).doesNotContain("content");
		summary(u).andExpect(jsonPath("$.itemCount").value(2)).andExpect(jsonPath("$.totalBytes").value(356))
				.andExpect(jsonPath("$.kinds[0].itemCount").value(2)).andExpect(jsonPath("$.kinds[0].totalBytes").value(356));

		// replace: a new revision, the new bytes, createdAt kept
		byte[] second = bytes(300, 3);
		putItem(u, "drawing-1", body(second, "2026-11-26T11:00:00+03:00", null)).andExpect(status().isOk())
				.andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.sizeBytes").value(300))
				.andExpect(jsonPath("$.updatedAt").value("2026-11-26T08:00:00Z"));
		JsonNode replaced = read(getItem(u, "drawing-1").andExpect(status().isOk()));
		assertThat(Base64.getDecoder().decode(replaced.get("content").asString())).isEqualTo(second);
		assertThat(replaced.get("createdAt").asString()).isEqualTo(item.get("createdAt").asString());

		// another user sees nothing and touches nothing
		UUID other = backupUser();
		getItem(other, "drawing-1").andExpect(status().isNotFound());
		deleteItem(other, "drawing-1").andExpect(status().isNotFound());
		assertThat(read(list(other, "")).get("items")).isEmpty();
		clear(other).andExpect(status().isNoContent());
		assertThat(rows(u)).isEqualTo(2L);

		// delete one, then all
		deleteItem(u, "drawing-1").andExpect(status().isNoContent()).andExpect(NO_STORE);
		getItem(u, "drawing-1").andExpect(status().isNotFound());
		deleteItem(u, "drawing-1").andExpect(status().isNotFound());
		clear(u).andExpect(status().isNoContent());
		clear(u).andExpect(status().isNoContent());
		assertThat(rows(u)).isZero();
	}

	@Test
	void listPagesByItemIdAndFiltersByKind() throws Exception {
		UUID u = backupUser();
		for (String id : List.of("c", "a", "b")) {
			putItem(u, id, bytes(5, id.charAt(0))).andExpect(status().isCreated());
		}
		JsonNode p1 = read(list(u, "?limit=2").andExpect(status().isOk()));
		assertThat(p1.get("items").findValuesAsString("itemId")).containsExactly("a", "b");
		JsonNode p2 = read(list(u, "?limit=2&cursor=" + p1.get("nextCursor").asString()).andExpect(status().isOk()));
		assertThat(p2.get("items").findValuesAsString("itemId")).containsExactly("c");
		assertThat(p2.has("nextCursor")).isFalse();
		assertThat(read(list(u, "?kind=DRAWING")).get("items")).hasSize(3);
		list(u, "?kind=STICKER").andExpect(status().isBadRequest());
		list(u, "?limit=0").andExpect(status().isBadRequest());
		list(u, "?limit=101").andExpect(status().isBadRequest());
		list(u, "?cursor=!!").andExpect(status().isBadRequest());
	}

	// ======================================================================================= revisions

	@Test
	void expectedRevisionIsACompareAndSet() throws Exception {
		UUID u = backupUser();
		// create-only: 0 means "must not exist"
		putItem(u, "x", body(bytes(10, 1), UPDATED_AT, 0L)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.revision").value(1));
		putItem(u, "x", body(bytes(10, 2), UPDATED_AT, 0L)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REVISION_CONFLICT")).andExpect(jsonPath("$.currentRevision").value(1));
		// the right revision
		putItem(u, "x", body(bytes(10, 3), UPDATED_AT, 1L)).andExpect(status().isOk())
				.andExpect(jsonPath("$.revision").value(2));
		// a stale one: refused, nothing changes
		putItem(u, "x", body(bytes(10, 4), UPDATED_AT, 1L)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.currentRevision").value(2));
		assertThat(Base64.getDecoder().decode(read(getItem(u, "x")).get("content").asString())).isEqualTo(bytes(10, 3));
		// an item that does not exist (deleted by another device): conflict at revision 0
		putItem(u, "gone", body(bytes(10, 1), UPDATED_AT, 4L)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.currentRevision").value(0));
		assertThat(rows(u)).isOne();
		// without expectedRevision: last writer wins
		putItem(u, "x", body(bytes(10, 5), UPDATED_AT, null)).andExpect(status().isOk())
				.andExpect(jsonPath("$.revision").value(3));
		// the kind of an item is fixed
		putItem(u, "x", body("STICKER", Base64.getEncoder().encodeToString(bytes(4, 1)), UPDATED_AT, null))
				.andExpect(status().isBadRequest());
	}

	@Test
	void anIdenticalPutIsIdempotent() throws Exception {
		UUID u = backupUser();
		byte[] content = bytes(64, 9);
		putItem(u, "same", body(content, UPDATED_AT, 0L)).andExpect(status().isCreated());
		Map<String, Object> stored = row(u, "same");
		// the retry of the create (same expectedRevision 0, now stale): 200, same revision, nothing rewritten
		JsonNode again = read(putItem(u, "same", body(content, UPDATED_AT, 0L)).andExpect(status().isOk())
				.andExpect(jsonPath("$.revision").value(1)));
		putItem(u, "same", body(content, UPDATED_AT, null)).andExpect(status().isOk())
				.andExpect(jsonPath("$.revision").value(1));
		Map<String, Object> after = row(u, "same");
		assertThat((byte[]) after.get("content_ciphertext")).isEqualTo((byte[]) stored.get("content_ciphertext"));
		assertThat(after.get("updated_at")).isEqualTo(stored.get("updated_at"));
		assertThat(again.get("storedAt").asString()).isNotBlank();
		// a different updatedAt is a change, so is different content of the same size
		putItem(u, "same", body(content, "2026-11-27T00:00:00Z", null)).andExpect(jsonPath("$.revision").value(2));
		putItem(u, "same", body(bytes(64, 10), "2026-11-27T00:00:00Z", null)).andExpect(jsonPath("$.revision").value(3));
	}

	// ======================================================================================= caps and validation

	@Test
	void theCapsAreEnforced() throws Exception {
		UUID u = backupUser();
		// one item above max-item-bytes (1024): 413 with the limit
		putItem(u, "big", bytes(1025, 1)).andExpect(status().is(413))
				.andExpect(jsonPath("$.code").value("BACKUP_ITEM_TOO_LARGE")).andExpect(jsonPath("$.maxItemBytes").value(1024));
		// a body longer than any valid one is refused before it is parsed
		putItem(u, "big", "{\"content\":\"" + "A".repeat(8000) + "\"}").andExpect(status().is(413))
				.andExpect(jsonPath("$.code").value("BACKUP_ITEM_TOO_LARGE"));
		putItem(u, "edge", bytes(1024, 1)).andExpect(status().isCreated()); // exactly the limit
		// total bytes (2048): 1024 + 1000 fit, 30 more do not
		putItem(u, "second", bytes(1000, 2)).andExpect(status().isCreated());
		putItem(u, "third", bytes(30, 3)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("BACKUP_QUOTA_EXCEEDED")).andExpect(jsonPath("$.quota").value("TOTAL_BYTES"))
				.andExpect(jsonPath("$.limit").value(2048));
		// replacing counts the difference: shrinking is fine, growing past the total is not
		putItem(u, "second", bytes(900, 2)).andExpect(status().isOk());
		putItem(u, "second", bytes(1025 - 1, 2)).andExpect(status().isOk()); // 1024 + 1024 = 2048
		putItem(u, "edge", body(bytes(1024, 5), "2026-11-27T00:00:00Z", null)).andExpect(status().isOk());
		putItem(u, "third", bytes(1, 3)).andExpect(status().isConflict()).andExpect(jsonPath("$.quota").value("TOTAL_BYTES"));
		assertThat(rows(u)).isEqualTo(2L);

		// items per kind (3)
		UUID v = backupUser();
		for (String id : List.of("i1", "i2", "i3")) {
			putItem(v, id, bytes(10, 1)).andExpect(status().isCreated());
		}
		putItem(v, "i4", bytes(10, 1)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("BACKUP_QUOTA_EXCEEDED"))
				.andExpect(jsonPath("$.quota").value("ITEMS_PER_KIND")).andExpect(jsonPath("$.limit").value(3));
		putItem(v, "i3", body(bytes(10, 2), UPDATED_AT, null)).andExpect(status().isOk()); // a replacement is no new item
		deleteItem(v, "i1").andExpect(status().isNoContent());
		putItem(v, "i4", bytes(10, 1)).andExpect(status().isCreated());
		summary(v).andExpect(jsonPath("$.itemCount").value(3)).andExpect(jsonPath("$.totalBytes").value(30));
	}

	@Test
	void invalidRequestsAre400AndStoreNothing() throws Exception {
		UUID u = backupUser();
		String ok = Base64.getEncoder().encodeToString(bytes(8, 1));
		putItem(u, "a".repeat(65), body(bytes(8, 1), UPDATED_AT, null)).andExpect(status().isBadRequest());
		putItem(u, "bad.id", body(bytes(8, 1), UPDATED_AT, null)).andExpect(status().isBadRequest());
		getItem(u, "a".repeat(65)).andExpect(status().isBadRequest());
		putItem(u, "a1", "not json").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("MALFORMED_REQUEST_BODY"));
		putItem(u, "a1", "[]").andExpect(status().isBadRequest());
		putItem(u, "a1", body("STICKER", ok, UPDATED_AT, null)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("kind"));
		putItem(u, "a1", "{\"updatedAt\":\"" + UPDATED_AT + "\",\"content\":\"" + ok + "\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
		putItem(u, "a1", body("DRAWING", ok, "yesterday", null)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("updatedAt"));
		putItem(u, "a1", body("DRAWING", "not base64!", UPDATED_AT, null)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].code").value("INVALID_BASE64"));
		putItem(u, "a1", body("DRAWING", "", UPDATED_AT, null)).andExpect(status().isBadRequest());
		putItem(u, "a1", body(bytes(8, 1), UPDATED_AT, -1L)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("expectedRevision"));
		putItem(u, "a1", "{\"kind\":\"DRAWING\",\"updatedAt\":\"" + UPDATED_AT + "\",\"expectedRevision\":\"1\","
				+ "\"content\":\"" + ok + "\"}").andExpect(status().isBadRequest());
		assertThat(rows(u)).isZero();
	}

	// ======================================================================================= privacy lifecycle

	@Test
	void withdrawingTheConsentDeletesEveryItem() throws Exception {
		UUID u = backupUser();
		UUID bystander = backupUser();
		putItem(u, "a", bytes(10, 1)).andExpect(status().isCreated());
		putItem(u, "b", bytes(10, 2)).andExpect(status().isCreated());
		putItem(bystander, "a", bytes(10, 3)).andExpect(status().isCreated());

		consent(u, false);
		assertThat(rows(u)).isZero();
		assertThat(rows(bystander)).isOne();
		list(u, "").andExpect(status().isForbidden());
		summary(u).andExpect(jsonPath("$.consentGranted").value(false)).andExpect(jsonPath("$.itemCount").value(0));
		// a repeated withdrawal is harmless; a re-grant brings nothing back
		consent(u, false);
		consent(u, true);
		assertThat(read(list(u, "").andExpect(status().isOk())).get("items")).isEmpty();
		putItem(u, "a", bytes(10, 1)).andExpect(status().isCreated()).andExpect(jsonPath("$.revision").value(1));
	}

	@Test
	void cryptoShreddingMakesTheContentUnreadableAndDeletionRemovesTheRows() throws Exception {
		UUID u = backupUser();
		byte[] content = bytes(200, 4);
		putItem(u, "shred", content).andExpect(status().isCreated());
		Map<String, Object> stored = row(u, "shred");
		UUID rowId = (UUID) stored.get("id");
		byte[] sealed = (byte[]) stored.get("content_ciphertext");
		EncryptionContext ctx = EncryptionContext.of("creative_backup_items", "content_ciphertext", rowId);
		assertThat(encryption.decrypt(u, ctx, sealed)).isEqualTo(content);
		assertThat(stored.get("key_version")).isEqualTo(1);

		transactions.executeWithoutResult(s -> encryption.cryptoShred(u)); // account deletion, step 1
		assertThatThrownBy(() -> encryption.decrypt(u, ctx, sealed)).isInstanceOf(CryptoShreddedException.class);
		Integer purged = transactions.execute(s -> lifecycle.purgeUser(u, Instant.now())); // step 2
		assertThat(purged).isOne();
		assertThat(rows(u)).isZero();
		Integer again = transactions.execute(s -> lifecycle.purgeUser(u, Instant.now())); // idempotent
		assertThat(again).isZero();
	}

	@Test
	void theContentIsEncryptedAtRestAndNeverLogged() throws Exception {
		UUID u = backupUser();
		byte[] content = (MARKER + " drawing strokes").getBytes(StandardCharsets.UTF_8);
		String encoded = Base64.getEncoder().encodeToString(content);
		Logger web = (Logger) LoggerFactory.getLogger("org.springframework.web");
		Logger http = (Logger) LoggerFactory.getLogger("org.springframework.http");
		Level webLevel = web.getLevel();
		Level httpLevel = http.getLevel();
		web.setLevel(Level.TRACE);
		http.setLevel(Level.TRACE);
		List<String> lines;
		try (CapturedLogs logs = new CapturedLogs()) {
			putItem(u, "secret", content).andExpect(status().isCreated());
			putItem(u, "secret", content).andExpect(status().isOk()); // idempotent repeat (decrypts to compare)
			assertThat(read(getItem(u, "secret")).get("content").asString()).isEqualTo(encoded);
			list(u, "").andExpect(status().isOk());
			putItem(u, "secret", body(content, UPDATED_AT, 7L)).andExpect(status().isOk()); // identical: idempotent
			putItem(u, "secret", body(content, "2026-11-27T00:00:00Z", 7L)).andExpect(status().isConflict());
			putItem(u, "secret", body("STICKER", encoded, UPDATED_AT, null)).andExpect(status().isBadRequest());
			putItem(u, "other", "{\"kind\":\"" + MARKER + "\",\"updatedAt\":\"" + MARKER + "\",\"content\":\"" + MARKER
					+ "\"}").andExpect(status().isBadRequest());
			putItem(u, "other", "{\"content\":" + "\"" + encoded + "\"").andExpect(status().isBadRequest());
			lines = logs.texts();
			assertThat(logs.size()).isPositive();
		}
		finally {
			web.setLevel(webLevel);
			http.setLevel(httpLevel);
		}
		for (String line : lines) {
			for (String secret : List.of(MARKER, "lighthouse", encoded, encoded.substring(0, 16))) {
				assertThat(line).as("log line").doesNotContain(secret);
			}
		}
		for (Meter m : meters.getMeters()) {
			assertThat(m.getId().getName()).doesNotContain("lighthouse");
			m.getId().getTags().forEach(t -> assertThat(t.getKey() + "=" + t.getValue()).doesNotContain("lighthouse"));
		}

		// at rest: the row's ciphertext opens only as this user, column and row
		Map<String, Object> stored = row(u, "secret");
		byte[] sealed = (byte[]) stored.get("content_ciphertext");
		UUID rowId = (UUID) stored.get("id");
		assertThat(encryption.decrypt(u, EncryptionContext.of("creative_backup_items", "content_ciphertext", rowId), sealed))
				.isEqualTo(content);
		assertThatThrownBy(() -> encryption.decrypt(u,
				EncryptionContext.of("creative_backup_items", "content_ciphertext", UUID.randomUUID()), sealed))
				.isInstanceOf(CiphertextRejectedException.class);

		// every character and bytea column of every table: neither the content nor its base64 anywhere
		List<String> needles = List.of(MARKER, "lighthouse", encoded);
		List<String> offenders = new ArrayList<>();
		int scanned = 0;
		for (Map<String, Object> c : jdbc.queryForList("""
				SELECT c.table_name, c.column_name, c.data_type FROM information_schema.columns c
				  JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
				 WHERE c.table_schema = current_schema() AND t.table_type = 'BASE TABLE'
				   AND c.data_type IN ('text', 'character varying', 'character', 'json', 'jsonb', 'bytea', 'ARRAY')""")) {
			String table = "\"" + c.get("table_name") + "\"";
			String column = "\"" + c.get("column_name") + "\"";
			boolean binary = "bytea".equals(c.get("data_type"));
			for (String needle : needles) {
				String sql = binary
						? "SELECT count(*) FROM " + table + " WHERE position(convert_to(?, 'UTF8') in " + column + ") > 0"
						: "SELECT count(*) FROM " + table + " WHERE strpos(" + column + "::text, ?) > 0";
				if (jdbc.queryForObject(sql, Long.class, needle) > 0) {
					offenders.add(c.get("table_name") + "." + c.get("column_name") + " holds " + needle);
				}
			}
			scanned++;
		}
		assertThat(scanned).isGreaterThan(100);
		assertThat(offenders).isEmpty();
	}

	// ======================================================================================= rate limit and guards

	@Test
	void everyOperationSharesAPerUserRateLimit() throws Exception {
		UUID u = backupUser();
		UUID other = backupUser();
		int allowed = 0;
		ResultActions last = null;
		for (int i = 0; i < 41; i++) {
			last = summary(u);
			if (last.andReturn().getResponse().getStatus() != 200) {
				break;
			}
			allowed++;
		}
		assertThat(allowed).isEqualTo(40);
		last.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(header().exists("Retry-After"));
		putItem(u, "a", bytes(4, 1)).andExpect(status().isTooManyRequests());
		list(u, "").andExpect(status().isTooManyRequests());
		summary(other).andExpect(status().isOk()); // per user
	}

	@Test
	void theDatabaseRejectsBadRows() {
		UUID u = user("REGISTERED");
		String insert = """
				INSERT INTO creative_backup_items (id, user_id, client_item_id, item_kind, revision, content_ciphertext,
				       key_version, size_bytes, client_updated_at) VALUES (?, ?, ?, ?, ?, ?, 1, ?, now())""";
		byte[] sealed = new byte[64];
		jdbc.update(insert, UUID.randomUUID(), u, "ok", "DRAWING", 1, sealed, 10);
		assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), u, "ok", "DRAWING", 1, sealed, 10))
				.isInstanceOf(DataIntegrityViolationException.class); // one row per (user, item)
		assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), u, "k1", "STICKER", 1, sealed, 10))
				.isInstanceOf(DataIntegrityViolationException.class); // unknown kind
		assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), u, "../x", "DRAWING", 1, sealed, 10))
				.isInstanceOf(DataIntegrityViolationException.class); // item id charset
		assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), u, "r0", "DRAWING", 0, sealed, 10))
				.isInstanceOf(DataIntegrityViolationException.class); // revision starts at 1
		assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), u, "pt", "DRAWING", 1, new byte[20], 10))
				.isInstanceOf(DataIntegrityViolationException.class); // shorter than an envelope of its plaintext
		assertThatThrownBy(() -> jdbc.update("INSERT INTO user_consents (user_id, consent_code, consent_version, "
				+ "is_granted) VALUES (?, 'CREATIVE_BACKUPS', '1', TRUE)", u))
				.isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("INSERT INTO user_consents (user_id, consent_code, consent_version, is_granted) "
				+ "VALUES (?, 'CREATIVE_BACKUP', '1', TRUE)", u); // the V34 CHECK knows the new purpose
	}
}
