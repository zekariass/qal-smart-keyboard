package com.qalsmartkeyboard.languages;

import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.identity.AuthenticatedUser;
import com.qalsmartkeyboard.languages.DictionaryDtos.Change;
import com.qalsmartkeyboard.languages.DictionaryDtos.ChangesPage;
import com.qalsmartkeyboard.languages.DictionaryDtos.ChangesResult;
import com.qalsmartkeyboard.languages.DictionaryDtos.Rejected;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.privacy.ConsentGate;
import com.qalsmartkeyboard.privacy.ConsentType;

/**
 * {@code GET/POST /me/dictionary/changes}: encrypted personal-dictionary sync (B3.6, ADR 0018).
 *
 * <p><b>Gate</b>: registered accounts only (guest 403 {@code GUEST_NOT_ALLOWED}), then the {@code DICTIONARY_SYNC}
 * entitlement ({@code FEATURE_NOT_ENTITLED}), then the {@code DICTIONARY_SYNC} consent ({@code CONSENT_REQUIRED}).
 * Withdrawal blocks both operations and does not purge (SD 19.3); the rows stay until the user deletes the account.
 *
 * <p><b>Storage</b>: the word (NFC-normalised) is only ever stored as {@link UserDataEncryption} ciphertext bound to its
 * row; uniqueness is the keyed HMAC of {@link DictionaryKeys}. Every change gets the next per-user {@code sync_version}
 * (strictly increasing, from a counter in the per-user state row); a delete keeps the row as a tombstone.
 *
 * <p><b>Conflict rule</b>: last writer wins per word, in the order changes reach the server (batch order within a
 * request, request order across devices; pushes are serialised per user by the state-row lock). {@code ADD} of a word
 * that is live and {@code DELETE} of a word that is absent or already deleted are no-ops (no new version), so the same
 * word from two devices never duplicates. {@code changedAt} is accepted but not used for ordering: device clocks are not
 * trusted. There is no {@code REVISION_CONFLICT}: the contract's token protocol is a pull cursor, not a compare-and-set.
 *
 * <p><b>Idempotency</b>: applied {@code clientChangeId}s are remembered for 30 days; a re-uploaded change counts as
 * applied and does nothing. Rejected changes ({@code INVALID_WORD}, {@code LANGUAGE_NOT_SUPPORTED}) are not remembered.
 */
@Service
class DictionarySyncService {

	private static final Logger log = LoggerFactory.getLogger(DictionarySyncService.class);
	static final String TABLE = "user_dictionary_entries";
	static final String COLUMN = "word_ciphertext";
	private static final String FEATURE = "DICTIONARY_SYNC";
	private static final Pattern BAD_CHARACTER = Pattern.compile("[\\p{Z}\\p{Cc}\\p{Cs}\\p{Cn}]");
	private static final String TOKEN_PREFIX = "v1.";
	private static final String TOKEN_PREFIX_V2 = "v2.";
	static final int MAX_WORD = 64;

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;
	private final UserDataEncryption encryption;
	private final DictionaryKeys keys;
	private final ConsentGate consents;
	private final EntitlementResolver entitlements;

	DictionarySyncService(JdbcClient jdbc, TransactionTemplate tx, UserDataEncryption encryption, DictionaryKeys keys,
			ConsentGate consents, EntitlementResolver entitlements) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.encryption = encryption;
		this.keys = keys;
		this.consents = consents;
		this.entitlements = entitlements;
	}

	/** NFC, the one normal form of a stored word (D18). */
	static String normalize(String word) {
		return Normalizer.normalize(word, Normalizer.Form.NFC);
	}

	static boolean validWord(String nfcWord) {
		return !nfcWord.isEmpty() && nfcWord.codePointCount(0, nfcWord.length()) <= MAX_WORD
				&& !BAD_CHARACTER.matcher(nfcWord).find();
	}

	static String token(long version) {
		return token(version, 0);
	}

	/**
	 * The token of a pull cursor. {@code floor} is the user's {@code tombstone_floor} when the token was issued (D31,
	 * B6.3); it is 0 until a tombstone has been purged, and then the token is the plain {@code v1.} form, as before.
	 */
	static String token(long version, long floor) {
		if (floor <= 0) {
			return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding()
					.encodeToString(Long.toString(version).getBytes(StandardCharsets.US_ASCII));
		}
		return TOKEN_PREFIX_V2 + Base64.getUrlEncoder().withoutPadding()
				.encodeToString((version + ":" + floor).getBytes(StandardCharsets.US_ASCII));
	}

	/** A parsed sync token: the last version the client has seen and the tombstone floor when it was issued. */
	record Cursor(long version, long floor) {
	}

	/** @throws ApiException 400 {@code VALIDATION_FAILED} for a token this service did not issue */
	static long parseToken(String token) {
		return parseCursor(token).version();
	}

	/** @throws ApiException 400 {@code VALIDATION_FAILED} for a token this service did not issue */
	static Cursor parseCursor(String token) {
		if (token == null || token.isEmpty()) {
			return new Cursor(0, 0);
		}
		try {
			if (token.startsWith(TOKEN_PREFIX)) {
				long v = Long.parseLong(new String(Base64.getUrlDecoder().decode(token.substring(TOKEN_PREFIX.length())),
						StandardCharsets.US_ASCII));
				if (v >= 0) {
					return new Cursor(v, 0);
				}
			}
			else if (token.startsWith(TOKEN_PREFIX_V2)) {
				String[] parts = new String(
						Base64.getUrlDecoder().decode(token.substring(TOKEN_PREFIX_V2.length())),
						StandardCharsets.US_ASCII).split(":", -1);
				if (parts.length == 2) {
					long v = Long.parseLong(parts[0]);
					long f = Long.parseLong(parts[1]);
					if (v >= 0 && f > 0) {
						return new Cursor(v, f);
					}
				}
			}
		}
		catch (IllegalArgumentException e) {
			// falls through to the 400
		}
		throw new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, null, List.of(
				new ApiFieldError("since", "INVALID_SYNC_TOKEN", "The sync token was not issued by this service.")),
				Map.of());
	}

	private void gate(AuthenticatedUser caller) {
		if (caller.isGuest()) {
			throw new ApiException(ApiErrorCode.GUEST_NOT_ALLOWED, HttpStatus.FORBIDDEN,
					"Dictionary sync needs an account; sign in first.");
		}
		if (!entitlements.resolve(caller.userId()).isAllowed(FEATURE)) {
			throw new ApiException(ApiErrorCode.FEATURE_NOT_ENTITLED, HttpStatus.FORBIDDEN,
					"The current plan does not include dictionary sync.");
		}
		consents.requireConsent(caller.userId(), ConsentType.DICTIONARY_SYNC);
	}

	/**
	 * Changes with {@code sync_version > since}, oldest first, at most {@code limit}; deletes are tombstones. A cursor
	 * that is behind a purged tombstone (D31, B6.3: tombstones are kept 180 days) gets 410 {@code SYNC_CURSOR_EXPIRED}:
	 * the cursor's version is below the user's {@code tombstone_floor} and the floor has risen since the token was
	 * issued (a token carries the floor it was issued under, so the pages of a full download started after the purge are
	 * not refused; a pull without a token never is).
	 */
	ChangesPage pull(AuthenticatedUser caller, String since, int limit) {
		gate(caller);
		Cursor cursor = parseCursor(since);
		long from = cursor.version();
		UUID user = caller.userId();
		record Row(UUID id, String language, byte[] word, boolean deleted, Instant updatedAt, long version) {
		}
		List<Row> rows = jdbc.sql("""
				SELECT e.id, l.language_code, e.word_ciphertext, e.deleted_at IS NOT NULL, e.updated_at, e.sync_version
				FROM user_dictionary_entries e JOIN supported_languages l ON l.id = e.language_id
				WHERE e.user_id = :user AND e.sync_version > :since ORDER BY e.sync_version LIMIT :limit
				""").param("user", user).param("since", from).param("limit", limit + 1)
				.query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getBytes(3), rs.getBoolean(4),
						rs.getTimestamp(5).toInstant(), rs.getLong(6)))
				.list();
		// D31: read the floor after the rows. A purge commits its deletes and the raised floor together, so a purge that
		// ran before the rows were read is visible here too, and one that ran in between only makes this answer stricter.
		long floor = jdbc.sql("SELECT tombstone_floor FROM user_dictionary_sync_state WHERE user_id = :user")
				.param("user", user).query(Long.class).optional().orElse(0L);
		if (from > 0 && floor > Math.max(cursor.floor(), from)) {
			throw new ApiException(ApiErrorCode.SYNC_CURSOR_EXPIRED, HttpStatus.GONE,
					"Deleted words this device has not seen were purged; discard the sync cursor and download the"
							+ " dictionary again (pull without a since token).");
		}
		boolean more = rows.size() > limit;
		List<Row> page = more ? rows.subList(0, limit) : rows;
		List<Change> changes = new ArrayList<>(page.size());
		long last = from;
		for (Row row : page) {
			last = row.version();
			try {
				String word = encryption.decryptString(user, EncryptionContext.of(TABLE, COLUMN, row.id()), row.word());
				changes.add(new Change(row.deleted() ? "DELETE" : "ADD", row.language(), word, row.id(),
						row.updatedAt()));
			}
			catch (CiphertextRejectedException e) {
				log.warn("Dictionary pull: entry {} does not decrypt and is skipped", row.id());
			}
		}
		return new ChangesPage(changes, token(last, floor), more);
	}

	ChangesResult push(AuthenticatedUser caller, List<Change> changes) {
		gate(caller);
		UUID user = caller.userId();
		return tx.execute(status -> {
			DictionaryKeys.State state = keys.lock(user);
			try {
				long[] counter = { state.counter() };
				jdbc.sql("""
						DELETE FROM user_dictionary_applied_changes
						WHERE user_id = :user AND applied_at < NOW() - INTERVAL '30 days'
						""").param("user", user).update();
				int applied = 0;
				List<Rejected> rejected = new ArrayList<>();
				for (Change change : changes) {
					boolean add = "ADD".equals(change.op());
					String word = normalize(change.word());
					if (!validWord(word)) {
						rejected.add(new Rejected(change.clientChangeId(), "INVALID_WORD"));
						continue;
					}
					UUID languageId = jdbc
							.sql("SELECT id FROM supported_languages WHERE language_code = :code"
									+ (add ? " AND is_active" : ""))
							.param("code", change.languageCode()).query(UUID.class).optional().orElse(null);
					if (languageId == null) {
						rejected.add(new Rejected(change.clientChangeId(), "LANGUAGE_NOT_SUPPORTED"));
						continue;
					}
					int fresh = jdbc.sql("""
							INSERT INTO user_dictionary_applied_changes (user_id, client_change_id) VALUES (:user, :id)
							ON CONFLICT DO NOTHING
							""").param("user", user).param("id", change.clientChangeId()).update();
					applied++;
					if (fresh == 0) {
						continue; // a re-upload
					}
					String hmac = DictionaryKeys.hmac(state.key(), change.languageCode(), word);
					if (add) {
						add(user, languageId, word, hmac, counter);
					}
					else {
						delete(user, languageId, hmac, counter);
					}
				}
				if (counter[0] != state.counter()) {
					keys.saveCounter(user, counter[0]);
				}
				return new ChangesResult(applied, rejected.isEmpty() ? null : rejected, token(counter[0]));
			}
			finally {
				Arrays.fill(state.key(), (byte) 0);
			}
		});
	}

	private void add(UUID user, UUID languageId, String word, String hmac, long[] counter) {
		record Existing(UUID id, boolean deleted) {
		}
		Existing existing = jdbc.sql("""
				SELECT id, deleted_at IS NOT NULL FROM user_dictionary_entries
				WHERE user_id = :user AND language_id = :language AND word_hmac = :hmac
				""").param("user", user).param("language", languageId).param("hmac", hmac)
				.query((rs, n) -> new Existing(rs.getObject(1, UUID.class), rs.getBoolean(2))).optional().orElse(null);
		if (existing != null && !existing.deleted()) {
			return; // the same word from another device: nothing to do
		}
		UUID id = existing != null ? existing.id() : UUID.randomUUID();
		EncryptedValue sealed = encryption.encryptString(user, EncryptionContext.of(TABLE, COLUMN, id), word);
		long version = ++counter[0];
		if (existing == null) {
			jdbc.sql("""
					INSERT INTO user_dictionary_entries (id, user_id, language_id, word_ciphertext, word_hmac, key_version,
					    sync_version)
					VALUES (:id, :user, :language, :word, :hmac, :keyVersion, :version)
					""").param("id", id).param("user", user).param("language", languageId)
					.param("word", sealed.ciphertext(), Types.BINARY).param("hmac", hmac)
					.param("keyVersion", sealed.keyVersion()).param("version", version).update();
		}
		else {
			jdbc.sql("""
					UPDATE user_dictionary_entries SET word_ciphertext = :word, key_version = :keyVersion, deleted_at = NULL,
					       sync_version = :version, updated_at = NOW() WHERE id = :id
					""").param("word", sealed.ciphertext(), Types.BINARY).param("keyVersion", sealed.keyVersion())
					.param("version", version).param("id", id).update();
		}
	}

	private void delete(UUID user, UUID languageId, String hmac, long[] counter) {
		UUID id = jdbc.sql("""
				SELECT id FROM user_dictionary_entries WHERE user_id = :user AND language_id = :language
				AND word_hmac = :hmac AND deleted_at IS NULL
				""").param("user", user).param("language", languageId).param("hmac", hmac).query(UUID.class).optional()
				.orElse(null);
		if (id == null) {
			return;
		}
		jdbc.sql("""
				UPDATE user_dictionary_entries SET deleted_at = NOW(), sync_version = :version, updated_at = NOW()
				WHERE id = :id
				""").param("version", ++counter[0]).param("id", id).update();
	}
}
