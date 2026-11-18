package com.qalsmartkeyboard.personalization;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.personalization.HistoryDtos.HistoryItem;
import com.qalsmartkeyboard.personalization.HistoryDtos.HistoryPage;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.ApiFieldError;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;

/**
 * The owner's view of the opt-in AI history (B5.7, contract {@code listHistory}, {@code getHistoryItem},
 * {@code updateHistoryItem}, {@code deleteHistoryItem}, {@code clearHistory}). Every statement is scoped to the
 * caller's {@code user_id}; a foreign or unknown id is 404. Items past their {@code expires_at} are invisible (the
 * retention job deletes them later), favourites never expire. Listing works whatever the history preference or consent
 * is, so a person who turned history off or withdrew the consent can still review and delete what remains.
 *
 * <p>Text is decrypted per row with its own context; a row that does not open (tampered) is left out and its id logged.
 */
@Component
class HistoryService {

	private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
	static final int DEFAULT_PAGE = 20;
	static final int MAX_PAGE = 100;

	private static final String SELECT = """
			SELECT h.id, h.feature_code, sl.language_code AS source_language, dl.language_code AS destination_language,
			       h.input_ciphertext, h.output_ciphertext, h.is_favorite, h.created_at, h.expires_at
			  FROM user_history_items h
			  LEFT JOIN supported_languages sl ON sl.id = h.source_language_id
			  LEFT JOIN supported_languages dl ON dl.id = h.destination_language_id
			 WHERE h.user_id = :user AND (h.is_favorite OR h.expires_at > :now)
			""";

	private record Row(UUID id, String feature, String source, String destination, byte[] input, byte[] output,
			boolean favorite, Instant createdAt, Instant expiresAt) {
	}

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;
	private final Clock clock;

	HistoryService(JdbcClient jdbc, UserDataEncryption encryption, ObjectProvider<Clock> clock) {
		this.jdbc = jdbc;
		this.encryption = encryption;
		this.clock = clock.getIfAvailable(Clock::systemUTC);
	}

	HistoryPage list(UUID userId, Boolean favorite, String featureCode, String cursor, int limit) {
		if (limit < 1 || limit > MAX_PAGE) {
			throw invalid("limit", "OUT_OF_RANGE", "limit must be between 1 and " + MAX_PAGE + ".");
		}
		StringBuilder sql = new StringBuilder(SELECT);
		Instant afterAt = null;
		UUID afterId = null;
		if (cursor != null) {
			try {
				String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 3);
				afterAt = Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
				afterId = UUID.fromString(parts[2]);
			}
			catch (RuntimeException e) {
				throw invalid("cursor", "INVALID", "The cursor is not valid.");
			}
			sql.append(" AND (h.created_at, h.id) < (:afterAt, :afterId)");
		}
		if (favorite != null) {
			sql.append(" AND h.is_favorite = :favorite");
		}
		if (featureCode != null) {
			sql.append(" AND h.feature_code = :feature");
		}
		sql.append(" ORDER BY h.created_at DESC, h.id DESC LIMIT :limit");
		JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("user", userId)
				.param("now", Timestamp.from(clock.instant())).param("limit", limit + 1);
		if (cursor != null) {
			spec = spec.param("afterAt", Timestamp.from(afterAt)).param("afterId", afterId);
		}
		if (favorite != null) {
			spec = spec.param("favorite", favorite);
		}
		if (featureCode != null) {
			spec = spec.param("feature", featureCode);
		}
		List<Row> rows = spec.query(HistoryService::row).list();
		boolean more = rows.size() > limit;
		List<Row> page = more ? rows.subList(0, limit) : rows;
		List<HistoryItem> items = new ArrayList<>();
		for (Row r : page) {
			HistoryItem item = open(userId, r);
			if (item != null) {
				items.add(item);
			}
		}
		String next = null;
		if (more) {
			Row last = page.get(page.size() - 1);
			String position = last.createdAt().getEpochSecond() + ":" + last.createdAt().getNano() + ":" + last.id();
			next = Base64.getUrlEncoder().withoutPadding().encodeToString(position.getBytes(StandardCharsets.UTF_8));
		}
		return new HistoryPage(items, next);
	}

	HistoryItem get(UUID userId, UUID id) {
		Row row = jdbc.sql(SELECT + " AND h.id = :id").param("user", userId)
				.param("now", Timestamp.from(clock.instant())).param("id", id).query(HistoryService::row).optional()
				.orElseThrow(HistoryService::notFound);
		HistoryItem item = open(userId, row);
		if (item == null) {
			throw notFound();
		}
		return item;
	}

	/**
	 * Marking a favourite removes the expiry. Unmarking starts a new one ({@code historyRetentionDays} from now, 30 days
	 * without a preferences row) so the item does not vanish at once; unmarking an item that is not a favourite changes
	 * nothing.
	 */
	HistoryItem setFavorite(UUID userId, UUID id, boolean favorite) {
		Instant now = clock.instant();
		int days = jdbc.sql("SELECT history_retention_days FROM user_preferences WHERE user_id = :user")
				.param("user", userId).query(Integer.class).optional().orElse(AiHistoryRecorder.DEFAULT_RETENTION_DAYS);
		int updated = jdbc.sql("""
				UPDATE user_history_items
				   SET expires_at = CASE WHEN :favorite THEN NULL WHEN is_favorite THEN :expires ELSE expires_at END,
				       is_favorite = :favorite, updated_at = :now
				 WHERE id = :id AND user_id = :user AND (is_favorite OR expires_at > :now)
				""").param("favorite", favorite).param("expires", Timestamp.from(now.plus(Duration.ofDays(days))))
				.param("now", Timestamp.from(now)).param("id", id).param("user", userId).update();
		if (updated == 0) {
			throw notFound();
		}
		return get(userId, id);
	}

	void delete(UUID userId, UUID id) {
		int deleted = jdbc.sql("""
				DELETE FROM user_history_items WHERE id = :id AND user_id = :user AND (is_favorite OR expires_at > :now)
				""").param("id", id).param("user", userId).param("now", Timestamp.from(clock.instant())).update();
		if (deleted == 0) {
			throw notFound();
		}
	}

	/** Every item of the caller, favourites and expired-but-not-yet-purged ones included. Idempotent. */
	void clear(UUID userId) {
		jdbc.sql("DELETE FROM user_history_items WHERE user_id = :user").param("user", userId).update();
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private HistoryItem open(UUID userId, Row r) {
		try {
			String input = encryption.decryptString(userId,
					EncryptionContext.of(AiHistoryRecorder.TABLE, "input_ciphertext", r.id()), r.input());
			String output = encryption.decryptString(userId,
					EncryptionContext.of(AiHistoryRecorder.TABLE, "output_ciphertext", r.id()), r.output());
			return new HistoryItem(r.id(), r.feature(), r.source(), r.destination(), input, output, r.favorite(),
					r.createdAt(), r.expiresAt());
		}
		catch (CiphertextRejectedException e) {
			log.warn("History item {} does not decrypt and is left out", r.id());
			return null;
		}
	}

	private static Row row(ResultSet rs, int n) throws SQLException {
		Timestamp expires = rs.getTimestamp("expires_at");
		return new Row(rs.getObject("id", UUID.class), rs.getString("feature_code"), rs.getString("source_language"),
				rs.getString("destination_language"), rs.getBytes("input_ciphertext"), rs.getBytes("output_ciphertext"),
				rs.getBoolean("is_favorite"), rs.getTimestamp("created_at").toInstant(),
				expires == null ? null : expires.toInstant());
	}

	static ApiException notFound() {
		return new ApiException(ApiErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, null);
	}

	static ApiException invalid(String field, String code, String message) {
		return new ApiException(ApiErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, message,
				List.of(new ApiFieldError(field, code, message)), Map.of());
	}
}
