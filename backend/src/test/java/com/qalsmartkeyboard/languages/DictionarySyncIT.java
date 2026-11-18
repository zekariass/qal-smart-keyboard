package com.qalsmartkeyboard.languages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSetMetaData;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.testsupport.contract.ContractValidated;

/**
 * B3.6 on PostgreSQL 18 through the real security chain, every response validated against the contract. The done-when
 * round trip is {@link #twoDevicesRoundTripWithNoPlaintextAtRest}; the HMAC is recomputed here from the documented
 * encoding, independently of the service.
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@AutoConfigureMockMvc
@ContractValidated
@Testcontainers
class DictionarySyncIT {

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
	UserDataEncryption encryption;
	@Autowired
	DictionaryGuestMerge merge;

	record User(UUID id, String bearer) {
	}

	User user(boolean consent) throws Exception {
		UUID id = transactions.execute(s -> {
			String phone = "+2517" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
			return ((PhoneSignIn.SignedIn) accounts.signInWithPhone(phone, true, Instant.now())).user().id();
		});
		User u = new User(id, "Bearer " + tokens.issue(id, UUID.randomUUID(), null, "REGISTERED").value());
		if (consent) {
			consent(u, true).andExpect(status().isOk());
		}
		return u;
	}

	User guest() {
		UUID id = transactions.execute(s -> accounts.createGuest(Instant.now()).id());
		return new User(id, "Bearer " + tokens.issue(id, UUID.randomUUID(), null, "GUEST").value());
	}

	ResultActions consent(User u, boolean granted) throws Exception {
		return mvc.perform(put("/api/v1/me/consents/DICTIONARY_SYNC").header("Authorization", u.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"consentVersion\":\"1\",\"isGranted\":" + granted + "}"));
	}

	static String change(String op, String language, String word, UUID id) {
		return "{\"op\":\"" + op + "\",\"languageCode\":\"" + language + "\",\"word\":\"" + word
				+ "\",\"clientChangeId\":\"" + id + "\"}";
	}

	static String add(String language, String word) {
		return change("ADD", language, word, UUID.randomUUID());
	}

	ResultActions push(User u, String... changes) throws Exception {
		return mvc.perform(post("/api/v1/me/dictionary/changes").header("Authorization", u.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"changes\":[" + String.join(",", changes) + "]}"));
	}

	ResultActions pull(User u, String since, int limit) throws Exception {
		return mvc.perform(get("/api/v1/me/dictionary/changes?limit=" + limit + (since == null ? "" : "&since=" + since))
				.header("Authorization", u.bearer()));
	}

	/** {@code op:word} of every change on a page, in order. */
	static List<String> words(String json) {
		List<String> out = new ArrayList<>();
		int n = JsonPath.read(json, "$.changes.length()");
		for (int i = 0; i < n; i++) {
			out.add(JsonPath.read(json, "$.changes[" + i + "].op") + ":" + JsonPath.read(json, "$.changes[" + i + "].word"));
		}
		return out;
	}

	String pullAll(User u, String since) throws Exception {
		return pull(u, since, 100).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	String token(String json) {
		return JsonPath.read(json, "$.syncToken");
	}

	int rows(User u) {
		return jdbc.queryForObject("SELECT count(*) FROM user_dictionary_entries WHERE user_id = ?", Integer.class,
				u.id());
	}

	byte[] key(User u) {
		return encryption.decrypt(u.id(), DictionaryKeys.context(u.id()), jdbc.queryForObject(
				"SELECT key_ciphertext FROM user_dictionary_sync_state WHERE user_id = ?", byte[].class, u.id()));
	}

	static String expectedHmac(byte[] key, String language, String nfcWord) throws Exception {
		javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
		mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(("qal.dictionary.word.v1\0" + language + "\0" + nfcWord)
				.getBytes(StandardCharsets.UTF_8)));
	}

	// ------------------------------------------------------------------------------------------------ done-when

	@Test
	void twoDevicesRoundTripWithNoPlaintextAtRest() throws Exception {
		User u = user(true);
		String decomposed = "café"; // NFD; the server stores and returns NFC
		String nfc = Normalizer.normalize(decomposed, Normalizer.Form.NFC);
		assertThat(nfc).isNotEqualTo(decomposed);

		// device A uploads
		push(u, add("am", "ሰላም"), add("am", "ቤተሰብ"), add("ti", "ሰላም"), add("en", decomposed)).andExpect(status().isOk())
				.andExpect(jsonPath("$.applied").value(4));

		// device B (same user) downloads them identical (NFC)
		String first = pullAll(u, null);
		assertThat(words(first)).containsExactly("ADD:ሰላም", "ADD:ቤተሰብ", "ADD:ሰላም", "ADD:" + nfc);
		assertThat((String) JsonPath.read(first, "$.changes[2].languageCode")).isEqualTo("ti");
		assertThat((Boolean) JsonPath.read(first, "$.hasMore")).isFalse();
		String bToken = token(first);

		// the same word from the other device does not duplicate (new change id, same word)
		push(u, add("am", "ሰላም"), add("en", nfc)).andExpect(jsonPath("$.applied").value(2));
		assertThat(rows(u)).isEqualTo(4);
		assertThat(words(pullAll(u, bToken))).isEmpty(); // nothing new was written

		// B deletes one; A pulls the tombstone
		push(u, change("DELETE", "am", "ቤተሰብ", UUID.randomUUID())).andExpect(jsonPath("$.applied").value(1));
		String aPull = pullAll(u, bToken);
		assertThat(words(aPull)).containsExactly("DELETE:ቤተሰብ");
		assertThat(rows(u)).isEqualTo(4); // the tombstone is a row

		// a full sync shows the live words and the tombstone, versions strictly increasing
		List<Long> versions = jdbc.queryForList(
				"SELECT sync_version FROM user_dictionary_entries WHERE user_id = ? ORDER BY sync_version", Long.class,
				u.id());
		assertThat(versions).isSorted().doesNotHaveDuplicates().allMatch(v -> v > 0);

		// DB scan: no plaintext word anywhere in the user's rows of the dictionary tables
		List<byte[]> secrets = new ArrayList<>();
		for (String w : List.of("ሰላም", "ቤተሰብ", nfc, decomposed)) {
			secrets.add(w.getBytes(StandardCharsets.UTF_8));
		}
		int cells = 0;
		for (String table : List.of("user_dictionary_entries", "user_dictionary_sync_state",
				"user_dictionary_applied_changes")) {
			List<List<Object>> all = jdbc.query("SELECT * FROM " + table + " WHERE user_id = ?", (rs, n) -> {
				ResultSetMetaData md = rs.getMetaData();
				List<Object> row = new ArrayList<>();
				for (int i = 1; i <= md.getColumnCount(); i++) {
					row.add(rs.getObject(i));
				}
				return row;
			}, u.id());
			for (List<Object> row : all) {
				for (Object cell : row) {
					if (cell == null) {
						continue;
					}
					cells++;
					byte[] bytes = cell instanceof byte[] b ? b : cell.toString().getBytes(StandardCharsets.UTF_8);
					for (byte[] secret : secrets) {
						assertThat(indexOf(bytes, secret)).as(table + " must not contain a plaintext word").isEqualTo(-1);
					}
				}
			}
		}
		assertThat(cells).isPositive();
	}

	@Test
	void wordHashIsTheKeyedHmacOfLanguageAndNfcWordAndTheKeyIsNeverTheDataKey() throws Exception {
		User u = user(true);
		push(u, add("am", "ሰላም")).andExpect(status().isOk());
		byte[] key = key(u);
		assertThat(key).hasSize(32);
		String stored = jdbc.queryForObject("SELECT word_hmac FROM user_dictionary_entries WHERE user_id = ?",
				String.class, u.id());
		assertThat(stored).isEqualTo(expectedHmac(key, "am", "ሰላም")).matches("^[0-9a-f]{64}$");
		// the language is part of the input
		assertThat(expectedHmac(key, "ti", "ሰላም")).isNotEqualTo(stored);
		// the key row is ciphertext; the raw key never appears in it
		byte[] sealed = jdbc.queryForObject("SELECT key_ciphertext FROM user_dictionary_sync_state WHERE user_id = ?",
				byte[].class, u.id());
		assertThat(indexOf(sealed, key)).isEqualTo(-1);
		// two users never share a key, so the same word hashes differently
		User other = user(true);
		push(other, add("am", "ሰላም")).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT word_hmac FROM user_dictionary_entries WHERE user_id = ?", String.class,
				other.id())).isNotEqualTo(stored);
		// rotating the data key changes no hash: the same word is still one row, found by the same HMAC
		encryption.rotateKey(u.id());
		push(u, add("am", "ሰላም"), add("am", "ቤት")).andExpect(jsonPath("$.applied").value(2));
		assertThat(rows(u)).isEqualTo(2);
		assertThat(key(u)).isEqualTo(key);
		assertThat(words(pullAll(u, null))).containsExactly("ADD:ሰላም", "ADD:ቤት");
	}

	@Test
	void reUploadingTheSameChangeSetIsIdempotentAndDoesNotResurrect() throws Exception {
		User u = user(true);
		UUID addId = UUID.randomUUID();
		UUID deleteId = UUID.randomUUID();
		String addChange = change("ADD", "am", "ሰላም", addId);
		String deleteChange = change("DELETE", "am", "ሰላም", deleteId);
		String first = push(u, addChange, deleteChange).andExpect(jsonPath("$.applied").value(2)).andReturn()
				.getResponse().getContentAsString();
		assertThat(words(pullAll(u, null))).containsExactly("DELETE:ሰላም");
		// the very same set again (lost response): same answer, no change, the ADD does not bring the word back
		String again = push(u, addChange, deleteChange).andExpect(jsonPath("$.applied").value(2)).andReturn()
				.getResponse().getContentAsString();
		assertThat(token(again)).isEqualTo(token(first));
		assertThat(words(pullAll(u, null))).containsExactly("DELETE:ሰላም");
		// a new ADD with a new id does bring it back (last writer wins), as the same row
		push(u, add("am", "ሰላም")).andExpect(jsonPath("$.applied").value(1));
		assertThat(words(pullAll(u, null))).containsExactly("ADD:ሰላም");
		assertThat(rows(u)).isEqualTo(1);
		// deleting a word that is not there is a no-op
		String before = token(pullAll(u, null));
		push(u, change("DELETE", "am", "የለም", UUID.randomUUID())).andExpect(jsonPath("$.applied").value(1));
		assertThat(words(pullAll(u, before))).isEmpty();
	}

	@Test
	void pagesFollowTheTokenAndRejectionsAreReportedPerChange() throws Exception {
		User u = user(true);
		UUID bad = UUID.randomUUID();
		UUID unknown = UUID.randomUUID();
		push(u, add("am", "አንድ"), add("am", "ሁለት"), add("am", "ሦስት"), change("ADD", "am", "has space", bad),
				change("ADD", "xx", "ቃል", unknown)).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(3))
				.andExpect(jsonPath("$.rejected.length()").value(2))
				.andExpect(jsonPath("$.rejected[0].clientChangeId").value(bad.toString()))
				.andExpect(jsonPath("$.rejected[0].code").value("INVALID_WORD"))
				.andExpect(jsonPath("$.rejected[1].code").value("LANGUAGE_NOT_SUPPORTED"));
		String page1 = pull(u, null, 2).andExpect(status().isOk()).andExpect(jsonPath("$.hasMore").value(true))
				.andReturn().getResponse().getContentAsString();
		assertThat(words(page1)).containsExactly("ADD:አንድ", "ADD:ሁለት");
		String page2 = pullAll(u, token(page1));
		assertThat(words(page2)).containsExactly("ADD:ሦስት");
		assertThat((Boolean) JsonPath.read(page2, "$.hasMore")).isFalse();
		assertThat(words(pullAll(u, token(page2)))).isEmpty();
		pull(u, "garbage", 10).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	// ------------------------------------------------------------------------------------------------ gates

	@Test
	void withoutConsentOrAfterWithdrawalBothOperationsAre403ConsentRequired() throws Exception {
		User u = user(false);
		push(u, add("am", "ሰላም")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"))
				.andExpect(jsonPath("$.consentCode").value("DICTIONARY_SYNC"));
		pull(u, null, 10).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
		assertThat(rows(u)).isZero();

		consent(u, true).andExpect(status().isOk());
		push(u, add("am", "ሰላም")).andExpect(status().isOk());
		pull(u, null, 10).andExpect(status().isOk());

		consent(u, false).andExpect(status().isOk());
		push(u, add("am", "ቤት")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
		pull(u, null, 10).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
		assertThat(rows(u)).isEqualTo(1); // withdrawal stops processing and does not purge

		consent(u, true).andExpect(status().isOk());
		assertThat(words(pullAll(u, null))).containsExactly("ADD:ሰላም"); // a later grant restores access
	}

	@Test
	void guestsAre403GuestNotAllowed() throws Exception {
		User g = guest();
		push(g, add("am", "ሰላም")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
		pull(g, null, 10).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GUEST_NOT_ALLOWED"));
		assertThat(rows(g)).isZero();
	}

	@Test
	void usersSeeOnlyTheirOwnWords() throws Exception {
		User a = user(true);
		User b = user(true);
		push(a, add("am", "የእኔ")).andExpect(status().isOk());
		assertThat(words(pullAll(b, null))).isEmpty();
	}

	@Test
	void concurrentPushesOfTheSameWordsLeaveOneRowEach() throws Exception {
		User u = user(true);
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				Callable<Integer> task = () -> push(u, add("am", "ሰላም"), add("am", "ቤት"), add("am", "ውሃ")).andReturn()
						.getResponse().getStatus();
				results.add(pool.submit(task));
			}
			for (Future<Integer> f : results) {
				assertThat(f.get()).isEqualTo(200);
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(rows(u)).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT sync_counter FROM user_dictionary_sync_state WHERE user_id = ?",
				Long.class, u.id())).isEqualTo(3L);
	}

	// ------------------------------------------------------------------------------------------------ merge

	@Test
	void guestMergeKeepsTheWordsAndRecomputesTheHashUnderTheTargetsKey() throws Exception {
		User g = guest();
		User t = user(true);
		push(t, add("am", "ከፍተኛ"), add("am", "ቤት")).andExpect(status().isOk());
		// a guest dictionary (guests cannot sync today; the rows are seeded the way a future client-local import would)
		UUID am = jdbc.queryForObject("SELECT id FROM supported_languages WHERE language_code = 'am'", UUID.class);
		seedGuestWord(g, am, "ሰላም", 'a');
		seedGuestWord(g, am, "ከፍተኛ", 'b'); // the target has it: the target's entry stays
		String guestHmac = jdbc.queryForObject("SELECT word_hmac FROM user_dictionary_entries WHERE user_id = ? "
				+ "AND word_hmac = ?", String.class, g.id(), "a".repeat(64));
		assertThat(guestHmac).isNotNull();

		transactions.executeWithoutResult(s -> merge.mergeGuest(g.id(), t.id(), Instant.now()));

		assertThat(rows(g)).isZero();
		assertThat(rows(t)).isEqualTo(3);
		byte[] key = key(t);
		assertThat(jdbc.queryForList("SELECT word_hmac FROM user_dictionary_entries WHERE user_id = ?", String.class,
				t.id())).containsExactlyInAnyOrder(expectedHmac(key, "am", "ከፍተኛ"), expectedHmac(key, "am", "ቤት"),
						expectedHmac(key, "am", "ሰላም"));
		// the target's other device pulls the moved word, and the same word pushed again does not duplicate
		assertThat(words(pullAll(t, null))).containsExactly("ADD:ከፍተኛ", "ADD:ቤት", "ADD:ሰላም");
		push(t, add("am", "ሰላም")).andExpect(status().isOk());
		assertThat(rows(t)).isEqualTo(3);
	}

	@Test
	void guestMergeCreatesTheTargetsKeyWhenItHasNone() throws Exception {
		User g = guest();
		User t = user(false);
		UUID am = jdbc.queryForObject("SELECT id FROM supported_languages WHERE language_code = 'am'", UUID.class);
		seedGuestWord(g, am, "ሰላም", 'c');
		transactions.executeWithoutResult(s -> merge.mergeGuest(g.id(), t.id(), Instant.now()));
		assertThat(jdbc.queryForObject("SELECT sync_counter FROM user_dictionary_sync_state WHERE user_id = ?",
				Long.class, t.id())).isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT word_hmac FROM user_dictionary_entries WHERE user_id = ?", String.class,
				t.id())).isEqualTo(expectedHmac(key(t), "am", "ሰላም"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_dictionary_sync_state WHERE user_id = ?",
				Integer.class, g.id())).isZero();
	}

	private void seedGuestWord(User g, UUID language, String word, char fakeHmac) {
		UUID id = UUID.randomUUID();
		var sealed = encryption.encryptString(g.id(),
				com.qalsmartkeyboard.platform.crypto.EncryptionContext.of("user_dictionary_entries", "word_ciphertext", id),
				word);
		jdbc.update("""
				INSERT INTO user_dictionary_entries (id, user_id, language_id, word_ciphertext, word_hmac, key_version)
				VALUES (?, ?, ?, ?, ?, ?)
				""", id, g.id(), language, sealed.ciphertext(), String.valueOf(fakeHmac).repeat(64), sealed.keyVersion());
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
