package com.qalsmartkeyboard.personalization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.personalization.CreativeBackupDtos.Item;
import com.qalsmartkeyboard.personalization.CreativeBackupDtos.ItemMetadata;
import com.qalsmartkeyboard.personalization.CreativeBackupDtos.ItemPage;
import com.qalsmartkeyboard.personalization.CreativeBackupDtos.KindUsage;
import com.qalsmartkeyboard.personalization.CreativeBackupDtos.Summary;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.privacy.ConsentChanged;
import com.qalsmartkeyboard.privacy.ConsentGate;
import com.qalsmartkeyboard.privacy.ConsentType;

/**
 * The opt-in encrypted cloud backup of creative assets (T8.15, ADR 0050), contract tag {@code CreativeBackup}.
 *
 * <ul>
 * <li><b>Storage.</b> One {@code creative_backup_items} row per (user, client item id). The content is an opaque blob
 * the client produced; it is sealed with the user's data key ({@link UserDataEncryption}, context
 * {@code creative_backup_items / content_ciphertext / <row id>}) and only the ciphertext is stored. Plaintext columns are
 * listing metadata only (kind, size, revision, the client's {@code updatedAt}, server times).</li>
 * <li><b>Concurrency.</b> Last writer wins, with optional compare-and-set: a PUT that names {@code expectedRevision}
 * must match the stored revision ({@code 0} = the item must not exist), otherwise 409 {@code REVISION_CONFLICT} with
 * {@code currentRevision}. The server assigns revisions (1 on create, +1 on every change). Every write of a user is
 * serialised by a transaction-scoped advisory lock, which also makes the caps exact and orders writes against a
 * consent withdrawal (which takes the same lock).</li>
 * <li><b>Idempotent PUT.</b> A PUT whose kind, {@code updatedAt} and content equal the stored item changes nothing and
 * returns the stored revision, whatever {@code expectedRevision} says: a retried PUT is always safe.</li>
 * <li><b>Caps</b> ({@link CreativeBackupProperties}): item size (413 {@code BACKUP_ITEM_TOO_LARGE}), items per kind and
 * total bytes per user (409 {@code BACKUP_QUOTA_EXCEEDED} with {@code quota} and {@code limit}).</li>
 * <li><b>Withdrawal</b> of {@code CREATIVE_BACKUP} deletes every item of the user in the withdrawal's own transaction
 * ({@link #onConsentChanged}).</li>
 * </ul>
 * Nothing here logs, counts or throws with content, item ids or sizes: logs carry the user id and counts only.
 */
@Component
class CreativeBackupService {

	private static final Logger log = LoggerFactory.getLogger(CreativeBackupService.class);

	static final String TABLE = "creative_backup_items";
	static final String COLUMN = "content_ciphertext";
	/** Item kinds; keep equal to the V34 CHECK and the contract enum {@code CreativeBackupItemKind}. */
	static final List<String> KINDS = List.of("DRAWING");
	static final Pattern ITEM_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
	static final int DEFAULT_PAGE = 50;
	static final int MAX_PAGE = 100;

	private static final Set<String> KIND_SET = Set.copyOf(KINDS);

	private static final String COLUMNS = """
			id, client_item_id, item_kind, revision, size_bytes, client_updated_at, created_at, updated_at, key_version
			""";

	/** A validated PUT body; holds the content, so {@code toString} does not print it. */
	record PutRequest(String kind, Instant updatedAt, Long expectedRevision, byte[] content) {

		@Override
		public String toString() {
			return "PutRequest[" + kind + "]";
		}
	}

	/** The outcome of a PUT: the stored metadata and whether this call created the item. */
	record Saved(ItemMetadata item, boolean created) {
	}

	private record Row(UUID id, String itemId, String kind, long revision, int size, Instant updatedAt,
			Instant createdAt, Instant storedAt, int keyVersion) {

		ItemMetadata metadata() {
			return new ItemMetadata(itemId, kind, revision, size, updatedAt, createdAt, storedAt);
		}
	}

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;
	private final ConsentGate consents;
	private final TransactionTemplate transactions;
	private final CreativeBackupProperties properties;
	private final Clock clock;

	CreativeBackupService(JdbcClient jdbc, UserDataEncryption encryption, ConsentGate consents,
			TransactionTemplate transactions, CreativeBackupProperties properties, ObjectProvider<Clock> clock) {
		this.jdbc = jdbc;
		this.encryption = encryption;
		this.consents = consents;
		this.transactions = transactions;
		this.properties = properties;
		this.clock = clock.getIfAvailable(Clock::systemUTC);
	}

	static EncryptionContext context(UUID rowId) {
		return EncryptionContext.of(TABLE, COLUMN, rowId);
	}

	// ------------------------------------------------------------------------------------------------ reads

	ItemPage list(UUID userId, String kind, String cursor, int limit) {
		if (limit < 1 || limit > MAX_PAGE) {
			throw invalid("limit", "OUT_OF_RANGE", "limit must be between 1 and " + MAX_PAGE + ".");
		}
		if (kind != null && !KIND_SET.contains(kind)) {
			throw invalid("kind", "INVALID", "kind is not a known item kind.");
		}
		String after = null;
		if (cursor != null) {
			try {
				if (cursor.length() > 128) {
					throw new IllegalArgumentException();
				}
				after = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			}
			catch (IllegalArgumentException e) {
				throw invalid("cursor", "INVALID", "The cursor is not valid.");
			}
			if (!ITEM_ID.matcher(after).matches()) {
				throw invalid("cursor", "INVALID", "The cursor is not valid.");
			}
		}
		List<Row> rows = jdbc.sql("SELECT " + COLUMNS + " FROM creative_backup_items WHERE user_id = :user"
				+ (kind == null ? "" : " AND item_kind = :kind") + (after == null ? "" : " AND client_item_id > :after")
				+ " ORDER BY client_item_id LIMIT :limit").param("user", userId).param("kind", kind)
				.param("after", after).param("limit", limit + 1).query(CreativeBackupService::row).list();
		boolean more = rows.size() > limit;
		List<Row> page = more ? rows.subList(0, limit) : rows;
		String next = more ? Base64.getUrlEncoder().withoutPadding()
				.encodeToString(page.get(page.size() - 1).itemId().getBytes(StandardCharsets.UTF_8)) : null;
		return new ItemPage(page.stream().map(Row::metadata).toList(), next);
	}

	Item get(UUID userId, String itemId) {
		requireItemId(itemId);
		record Sealed(Row row, byte[] ciphertext) {
		}
		Sealed s = jdbc.sql("SELECT " + COLUMNS + ", content_ciphertext FROM creative_backup_items "
				+ "WHERE user_id = :user AND client_item_id = :item").param("user", userId).param("item", itemId)
				.query((rs, n) -> new Sealed(row(rs, n), rs.getBytes("content_ciphertext"))).optional()
				.orElseThrow(CreativeBackupService::notFound);
		byte[] content = encryption.decrypt(userId, context(s.row().id()), s.ciphertext());
		try {
			Row r = s.row();
			return new Item(r.itemId(), r.kind(), r.revision(), r.size(), r.updatedAt(), r.createdAt(), r.storedAt(),
					Base64.getEncoder().encodeToString(content));
		}
		finally {
			Arrays.fill(content, (byte) 0);
		}
	}

	Summary summary(UUID userId) {
		Map<String, KindUsage> byKind = new LinkedHashMap<>();
		for (String k : KINDS) {
			byKind.put(k, new KindUsage(k, 0, 0));
		}
		jdbc.sql("""
				SELECT item_kind, count(*) AS n, coalesce(sum(size_bytes), 0) AS bytes FROM creative_backup_items
				WHERE user_id = :user GROUP BY item_kind
				""").param("user", userId).query((rs, n) -> new KindUsage(rs.getString("item_kind"), rs.getInt("n"),
				rs.getLong("bytes"))).list().forEach(u -> byKind.put(u.kind(), u));
		int count = byKind.values().stream().mapToInt(KindUsage::itemCount).sum();
		long bytes = byKind.values().stream().mapToLong(KindUsage::totalBytes).sum();
		return new Summary(consents.hasConsent(userId, ConsentType.CREATIVE_BACKUP), count, bytes,
				properties.maxItemBytes(), properties.maxItemsPerKind(), properties.maxTotalBytes(),
				List.copyOf(byKind.values()));
	}

	// ------------------------------------------------------------------------------------------------ writes

	/**
	 * Creates or replaces one item. The consent is checked again under the user's lock, so a withdrawal that commits
	 * first is always seen and one that commits later deletes what this call stored.
	 */
	Saved put(UUID userId, String itemId, PutRequest in) {
		requireItemId(itemId);
		if (in.content().length > properties.maxItemBytes()) {
			throw tooLarge();
		}
		return transactions.execute(status -> {
			lock(userId);
			consents.requireConsent(userId, ConsentType.CREATIVE_BACKUP);
			Instant updatedAt = in.updatedAt().truncatedTo(ChronoUnit.MICROS);
			record Current(Row row, byte[] ciphertext) {
			}
			Current current = jdbc.sql("SELECT " + COLUMNS + ", content_ciphertext FROM creative_backup_items "
					+ "WHERE user_id = :user AND client_item_id = :item").param("user", userId).param("item", itemId)
					.query((rs, n) -> new Current(row(rs, n), rs.getBytes("content_ciphertext"))).optional().orElse(null);
			Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
			if (current == null) {
				if (in.expectedRevision() != null && in.expectedRevision() != 0) {
					throw conflict(0);
				}
				checkQuota(userId, in.kind(), 0, in.content().length, true);
				UUID id = UUID.randomUUID();
				EncryptedValue sealed = encryption.encrypt(userId, context(id), in.content());
				jdbc.sql("""
						INSERT INTO creative_backup_items (id, user_id, client_item_id, item_kind, revision,
						       content_ciphertext, key_version, size_bytes, client_updated_at, created_at, updated_at)
						VALUES (:id, :user, :item, :kind, 1, :ct, :kv, :size, :updatedAt, :now, :now)
						""").param("id", id).param("user", userId).param("item", itemId).param("kind", in.kind())
						.param("ct", sealed.ciphertext()).param("kv", sealed.keyVersion())
						.param("size", in.content().length).param("updatedAt", Timestamp.from(updatedAt))
						.param("now", Timestamp.from(now)).update();
				return new Saved(new ItemMetadata(itemId, in.kind(), 1, in.content().length, updatedAt, now, now), true);
			}
			Row row = current.row();
			if (!row.kind().equals(in.kind())) {
				throw invalid("kind", "KIND_IMMUTABLE", "The kind of an existing item cannot change.");
			}
			if (row.updatedAt().equals(updatedAt) && row.size() == in.content().length
					&& sameContent(userId, row.id(), current.ciphertext(), in.content())) {
				return new Saved(row.metadata(), false); // a repeat of the stored state: nothing changes
			}
			if (in.expectedRevision() != null && in.expectedRevision() != row.revision()) {
				throw conflict(row.revision());
			}
			checkQuota(userId, in.kind(), row.size(), in.content().length, false);
			EncryptedValue sealed = encryption.encrypt(userId, context(row.id()), in.content());
			long revision = row.revision() + 1;
			jdbc.sql("""
					UPDATE creative_backup_items SET revision = :revision, content_ciphertext = :ct, key_version = :kv,
					       size_bytes = :size, client_updated_at = :updatedAt, updated_at = :now
					 WHERE id = :id
					""").param("revision", revision).param("ct", sealed.ciphertext()).param("kv", sealed.keyVersion())
					.param("size", in.content().length).param("updatedAt", Timestamp.from(updatedAt))
					.param("now", Timestamp.from(now)).param("id", row.id()).update();
			return new Saved(new ItemMetadata(itemId, row.kind(), revision, in.content().length, updatedAt,
					row.createdAt(), now), false);
		});
	}

	void delete(UUID userId, String itemId) {
		requireItemId(itemId);
		int deleted = jdbc.sql("DELETE FROM creative_backup_items WHERE user_id = :user AND client_item_id = :item")
				.param("user", userId).param("item", itemId).update();
		if (deleted == 0) {
			throw notFound();
		}
	}

	/** Every item of the user. Idempotent. */
	int clear(UUID userId) {
		return jdbc.sql("DELETE FROM creative_backup_items WHERE user_id = :user").param("user", userId).update();
	}

	/**
	 * Withdrawing {@code CREATIVE_BACKUP} deletes every backed-up item of the user, in the withdrawal's own transaction
	 * (it commits or rolls back with the decision), after waiting for a PUT of the same user that is in flight. A later
	 * re-grant brings nothing back; the client uploads again. Idempotent.
	 */
	@EventListener
	void onConsentChanged(ConsentChanged event) {
		if (event.type() == ConsentType.CREATIVE_BACKUP && !event.granted()) {
			lock(event.userId());
			int deleted = clear(event.userId());
			if (deleted > 0) {
				log.info("Creative backup of user {}: {} items deleted on consent withdrawal", event.userId(), deleted);
			}
		}
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private void lock(UUID userId) {
		lock(jdbc, userId);
	}

	/** The per-user write lock of the backup (transaction-scoped); every writer of the table takes it. */
	static void lock(JdbcClient jdbc, UUID userId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))").param("k", "qal.creative_backup:" + userId)
				.query((rs, n) -> 1).list();
	}

	private void checkQuota(UUID userId, String kind, int oldSize, int newSize, boolean adding) {
		record Usage(int kindItems, long totalBytes) {
		}
		Usage u = jdbc.sql("""
				SELECT count(*) FILTER (WHERE item_kind = :kind) AS kind_items, coalesce(sum(size_bytes), 0) AS bytes
				  FROM creative_backup_items WHERE user_id = :user
				""").param("kind", kind).param("user", userId)
				.query((rs, n) -> new Usage(rs.getInt("kind_items"), rs.getLong("bytes"))).single();
		if (adding && u.kindItems() >= properties.maxItemsPerKind()) {
			throw quota("ITEMS_PER_KIND", properties.maxItemsPerKind());
		}
		if (u.totalBytes() - oldSize + newSize > properties.maxTotalBytes()) {
			throw quota("TOTAL_BYTES", properties.maxTotalBytes());
		}
	}

	private boolean sameContent(UUID userId, UUID rowId, byte[] ciphertext, byte[] candidate) {
		byte[] stored = encryption.decrypt(userId, context(rowId), ciphertext);
		try {
			return MessageDigest.isEqual(stored, candidate);
		}
		finally {
			Arrays.fill(stored, (byte) 0);
		}
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getString("client_item_id"), rs.getString("item_kind"),
				rs.getLong("revision"), rs.getInt("size_bytes"), rs.getTimestamp("client_updated_at").toInstant(),
				rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
				rs.getInt("key_version"));
	}

	static void requireItemId(String itemId) {
		if (itemId == null || !ITEM_ID.matcher(itemId).matches()) {
			throw invalid("itemId", "INVALID", "itemId must be 1-64 characters of A-Z, a-z, 0-9, '_' or '-'.");
		}
	}

	static ApiException notFound() {
		return new ApiException(ApiErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, null);
	}

	static ApiException invalid(String field, String code, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, message,
				List.of(new ApiFieldError(field, code, message)), Map.of());
	}

	ApiException tooLarge() {
		return new ApiException(ApiErrorCode.BACKUP_ITEM_TOO_LARGE, HttpStatus.CONTENT_TOO_LARGE,
				"The item is larger than the backup allows.", List.of(),
				Map.of("maxItemBytes", (long) properties.maxItemBytes()));
	}

	private static ApiException quota(String quota, long limit) {
		return new ApiException(ApiErrorCode.BACKUP_QUOTA_EXCEEDED, HttpStatus.CONFLICT,
				"The backup storage limit is reached. Delete items to make room.", List.of(),
				Map.of("quota", quota, "limit", limit));
	}

	private static ApiException conflict(long currentRevision) {
		return new ApiException(ApiErrorCode.REVISION_CONFLICT, HttpStatus.CONFLICT,
				"The item changed since it was read. Read it again and retry.", List.of(),
				Map.of("currentRevision", currentRevision));
	}
}
