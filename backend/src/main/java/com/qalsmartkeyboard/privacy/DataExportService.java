package com.qalsmartkeyboard.privacy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.crypto.CryptoShreddedException;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.platform.ratelimit.RateLimitDecision;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code requestDataExport}, {@code getDataExport} and the archive download (SD 19.4, plan B6.1, ADR 0022). The request
 * only records a {@code REQUESTED} row; the {@link DataExportJob} builds the archive asynchronously.
 *
 * <ul>
 * <li><b>One in flight per user:</b> asking while a request is {@code REQUESTED} or {@code PROCESSING} answers with that
 * request (the partial unique index {@code ux_data_export_one_open} is the last line against a race).</li>
 * <li><b>Rate limit:</b> at most {@code max-per-window} requests (not counting failed ones) per {@code window} (3 per
 * 24 h): 429 {@code RATE_LIMITED} with {@code Retry-After}. Counted from the table, so it holds across instances and
 * restarts.</li>
 * <li><b>Who may ask:</b> any account that still has its data: {@code ACTIVE}, {@code PENDING}, {@code SUSPENDED}
 * (data subject rights do not depend on standing) and {@code DELETION_PENDING}: during the 14-day grace period the
 * person can still take their data with them. A closed or shredded account (the deletion executor has started) cannot
 * (its tokens are refused anyway; the check is the second line).</li>
 * <li><b>Ownership:</b> every read goes through {@code user_id = caller}; another user's id, an unknown id and an
 * export that is not downloadable look the same (404).</li>
 * <li><b>Expiry:</b> an export is {@code READY} until {@code download_expires_at} (7 days). From then on it reads as
 * {@code EXPIRED} at once, even before the sweeper has deleted the object.</li>
 * <li><b>Download:</b> the archive object is ciphertext under the user's data key; it is opened here, for the owner only,
 * and streamed. Nothing readable ever leaves the backend except to the authenticated owner.</li>
 * </ul>
 */
class DataExportService {

	private static final Logger log = LoggerFactory.getLogger(DataExportService.class);

	/** What the API reports; {@code DOWNLOADED} is shown as {@code READY} (the contract has no such state). */
	record Export(UUID id, UUID userId, String status, Instant requestedAt, Instant completedAt, Instant downloadExpiresAt,
			String objectKey) {

		/** The status the API shows at {@code now}. */
		String apiStatus(Instant now) {
			return switch (status) {
				case "READY", "DOWNLOADED" -> downloadExpiresAt != null && !downloadExpiresAt.isAfter(now) ? "EXPIRED" : "READY";
				default -> status;
			};
		}
	}

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;
	private final UserDataEncryption encryption;
	private final ExportObjectStore store;
	private final DataExportProperties properties;
	private final Clock clock;
	private final MeterRegistry meters;

	DataExportService(JdbcClient jdbc, TransactionTemplate tx, UserDataEncryption encryption, ExportObjectStore store,
			DataExportProperties properties, Clock clock, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.encryption = encryption;
		this.store = store;
		this.properties = properties;
		this.clock = clock;
		this.meters = meters;
	}

	/** @return the caller's open request if there is one, else a new {@code REQUESTED} one */
	Export request(UUID userId) {
		Instant now = clock.instant();
		try {
			return tx.execute(status -> {
				String account = jdbc.sql("""
						SELECT account_status FROM users WHERE id = :u AND deleted_at IS NULL FOR NO KEY UPDATE
						""").param("u", userId).query(String.class).optional().orElse(null);
				if (account == null || "DELETED".equals(account) || "MERGED".equals(account)
						|| encryption.isShredded(userId)) {
					throw new ApiException(ApiErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN, null);
				}
				Optional<Export> open = jdbc.sql(SELECT + " WHERE user_id = :u AND request_status IN ('REQUESTED', 'PROCESSING')")
						.param("u", userId).query(DataExportService::map).optional();
				if (open.isPresent()) {
					return open.get();
				}
				Instant windowStart = now.minus(properties.window());
				List<Timestamp> recent = jdbc.sql("""
						SELECT requested_at FROM data_export_requests
						WHERE user_id = :u AND request_status <> 'FAILED' AND requested_at > :since
						ORDER BY requested_at
						""").param("u", userId).param("since", Timestamp.from(windowStart)).query(Timestamp.class).list();
				if (recent.size() >= properties.maxPerWindow()) {
					Instant free = recent.get(recent.size() - properties.maxPerWindow()).toInstant()
							.plus(properties.window());
					long wait = Math.max(1, Duration.between(now, free).toSeconds() + 1);
					count("rate_limited");
					throw new ApiException(ApiErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
							"Too many data exports requested. Try again later.", List.of(), Map.of(),
							Map.of(RateLimitDecision.RETRY_AFTER, Long.toString(wait)));
				}
				UUID id = UUID.randomUUID();
				jdbc.sql("""
						INSERT INTO data_export_requests (id, user_id, request_status, requested_at, updated_at)
						VALUES (:id, :u, 'REQUESTED', :now, :now)
						""").param("id", id).param("u", userId).param("now", Timestamp.from(now)).update();
				count("requested");
				log.info("Data export {} requested by user {}", id, userId);
				return new Export(id, userId, "REQUESTED", now, null, null, null);
			});
		}
		catch (DuplicateKeyException race) {
			// a concurrent request of the same user won the unique index: answer with it
			return jdbc.sql(SELECT + " WHERE user_id = :u AND request_status IN ('REQUESTED', 'PROCESSING')")
					.param("u", userId).query(DataExportService::map).optional().orElseThrow(() -> race);
		}
	}

	/** @throws ApiException 404 unless the export exists and belongs to {@code userId} */
	Export get(UUID userId, UUID exportId) {
		return jdbc.sql(SELECT + " WHERE id = :id AND user_id = :u").param("id", exportId).param("u", userId)
				.query(DataExportService::map).optional().orElseThrow(DataExportService::notFound);
	}

	/**
	 * The plaintext archive of a {@code READY} export of the caller.
	 *
	 * @throws ApiException 404 when the export is not the caller's, not ready, expired, or its object is gone or can no
	 *                      longer be opened (the user's keys were destroyed)
	 */
	byte[] download(UUID userId, UUID exportId) {
		Instant now = clock.instant();
		Export export = get(userId, exportId);
		if (!"READY".equals(export.apiStatus(now)) || export.objectKey() == null) {
			throw notFound();
		}
		byte[] sealed = store.get(export.objectKey(), properties.maxArchiveBytes() + 1024)
				.orElseThrow(() -> {
					log.warn("Data export {}: the archive object is missing", exportId);
					return notFound();
				});
		byte[] plain;
		try {
			plain = encryption.decrypt(userId, DataExportJob.context(exportId), sealed);
		}
		catch (CryptoShreddedException shredded) {
			throw notFound();
		}
		jdbc.sql("""
				UPDATE data_export_requests SET request_status = 'DOWNLOADED', updated_at = :now
				WHERE id = :id AND user_id = :u AND request_status = 'READY'
				""").param("now", Timestamp.from(now)).param("id", exportId).param("u", userId).update();
		count("downloaded");
		log.info("Data export {} downloaded", exportId);
		return plain;
	}

	static final String SELECT = """
			SELECT id, user_id, request_status, requested_at, completed_at, download_expires_at, export_object_key
			FROM data_export_requests""";

	static Export map(ResultSet rs, int row) throws SQLException {
		return new Export(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getString("request_status"), instant(rs.getTimestamp("requested_at")),
				instant(rs.getTimestamp("completed_at")), instant(rs.getTimestamp("download_expires_at")),
				rs.getString("export_object_key"));
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}

	static ApiException notFound() {
		return new ApiException(ApiErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND, null);
	}

	private void count(String outcome) {
		if (meters != null) {
			meters.counter("qal.privacy.data_export", "outcome", outcome).increment();
		}
	}
}
