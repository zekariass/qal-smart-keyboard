package com.qalsmartkeyboard.privacy;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.identity.UserExportParticipant;
import com.qalsmartkeyboard.platform.crypto.CryptoShreddedException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the data export archives (SD 19.4, plan B6.1, ADR 0022), asynchronously, on the platform's db-scheduler.
 *
 * <p>One run ({@link #runOnce()}): puts abandoned {@code PROCESSING} exports back in the queue, works through the
 * {@code REQUESTED} ones, then expires the finished ones whose link ended. One export ({@link #process}):
 * <ol>
 * <li><b>Claim</b> (own transaction): {@code FOR UPDATE SKIP LOCKED}, {@code REQUESTED} to {@code PROCESSING}; two
 * instances never build one export.</li>
 * <li><b>Collect</b> in one read-only, repeatable-read transaction: every {@link UserExportParticipant} writes its
 * sections (so the archive is one consistent snapshot) into an {@link ExportArchive}; the JSON document is rendered
 * with its manifest.</li>
 * <li><b>Seal</b>: the document is AES-256-GCM encrypted with the user's data key
 * ({@link UserDataEncryption}, context {@code data_export_requests / archive / <export id>}) and the plaintext is wiped.
 * The object stored is that ciphertext under a random 256-bit key ({@code exports/<hex>}): not guessable, not derived
 * from the user. Crypto-shredding the user, or deleting the request row, makes it unreadable for good.</li>
 * <li><b>Publish</b> (own transaction): {@code READY}, the object key and the expiry ({@code link-ttl}, 7 days), only if
 * the row is still {@code PROCESSING}; if the account was deleted meanwhile the row is gone and the object is deleted
 * again.</li>
 * </ol>
 * Any failure marks the export {@code FAILED} with the exception class only (messages can carry SQL values); the person
 * requests it again. A shredded or closed account fails with {@code ACCOUNT_CLOSED}: a deleted account cannot export.
 */
class DataExportJob {

	private static final Logger log = LoggerFactory.getLogger(DataExportJob.class);

	static final String TABLE = "data_export_requests";
	static final String COLUMN = "archive";
	private static final SecureRandom RANDOM = new SecureRandom();

	/** What the archive ciphertext is bound to: the table, the {@code archive} column and the export id. */
	static EncryptionContext context(UUID exportId) {
		return EncryptionContext.of(TABLE, COLUMN, exportId);
	}

	private final JdbcClient jdbc;
	private final TransactionTemplate tx;
	private final TransactionTemplate snapshot;
	private final UserDataEncryption encryption;
	private final ObjectProvider<UserExportParticipant> participants;
	private final ExportObjectStore store;
	private final DataExportProperties properties;
	private final JsonMapper mapper;
	private final Clock clock;
	private final MeterRegistry meters;

	DataExportJob(JdbcClient jdbc, TransactionTemplate tx, TransactionTemplate snapshot, UserDataEncryption encryption,
			ObjectProvider<UserExportParticipant> participants, ExportObjectStore store, DataExportProperties properties,
			JsonMapper mapper, Clock clock, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.snapshot = snapshot;
		this.encryption = encryption;
		this.participants = participants;
		this.store = store;
		this.properties = properties;
		this.mapper = mapper;
		this.clock = clock;
		this.meters = meters;
	}

	/** One scheduler run at the current time. @return the number of exports built or expired */
	int runOnce() {
		return runOnce(clock.instant());
	}

	int runOnce(Instant now) {
		int handled = requeueAbandoned(now);
		for (UUID id = claim(now); id != null; id = claim(now)) {
			process(id);
			handled++;
		}
		return handled + expireFinished(now);
	}

	/** {@code PROCESSING} for longer than {@code stale-after}: the instance died; queue it again. */
	int requeueAbandoned(Instant now) {
		return jdbc.sql("""
				UPDATE data_export_requests SET request_status = 'REQUESTED', updated_at = :now
				WHERE request_status = 'PROCESSING' AND updated_at < :stale
				""").param("now", Timestamp.from(now)).param("stale", Timestamp.from(now.minus(properties.staleAfter())))
				.update();
	}

	/** Takes the oldest {@code REQUESTED} export for this run. @return its id, or {@code null} when none is waiting */
	UUID claim(Instant now) {
		return tx.execute(status -> {
			UUID id = jdbc.sql("""
					SELECT id FROM data_export_requests WHERE request_status = 'REQUESTED'
					ORDER BY requested_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
					""").query(UUID.class).optional().orElse(null);
			if (id != null) {
				jdbc.sql("UPDATE data_export_requests SET request_status = 'PROCESSING', updated_at = :now WHERE id = :id")
						.param("now", Timestamp.from(now)).param("id", id).update();
			}
			return id;
		});
	}

	/** Builds, seals, stores and publishes one claimed ({@code PROCESSING}) export; failures are recorded, not thrown. */
	void process(UUID exportId) {
		UUID userId = jdbc.sql("SELECT user_id FROM data_export_requests WHERE id = :id AND request_status = 'PROCESSING'")
				.param("id", exportId).query(UUID.class).optional().orElse(null);
		if (userId == null) {
			return;
		}
		String objectKey = null;
		try {
			if (encryption.isShredded(userId) || !accountOpen(userId)) {
				fail(exportId, "ACCOUNT_CLOSED");
				return;
			}
			ExportArchive archive = new ExportArchive();
			List<UserExportParticipant> ordered = participants.stream()
					.sorted(Comparator.comparingInt(UserExportParticipant::exportOrder)).toList();
			snapshot.executeWithoutResult(status -> {
				for (UserExportParticipant participant : ordered) {
					participant.exportUser(userId, archive);
				}
			});
			Instant generatedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); // the column's precision
			Instant expiresAt = generatedAt.plus(properties.linkTtl());
			byte[] plain = archive.render(mapper, exportId, userId, generatedAt, expiresAt);
			EncryptedValue sealed;
			try {
				if (plain.length > properties.maxArchiveBytes()) {
					fail(exportId, "ARCHIVE_TOO_LARGE");
					return;
				}
				sealed = encryption.encrypt(userId, context(exportId), plain);
			}
			finally {
				Arrays.fill(plain, (byte) 0);
			}
			objectKey = newObjectKey();
			store.put(objectKey, sealed.ciphertext());
			String key = objectKey;
			Integer published = tx.execute(status -> jdbc.sql("""
					UPDATE data_export_requests SET request_status = 'READY', export_object_key = :key,
					       completed_at = :now, download_expires_at = :expires, processing_error = NULL, updated_at = :now
					WHERE id = :id AND request_status = 'PROCESSING'
					""").param("key", key).param("now", Timestamp.from(generatedAt))
					.param("expires", Timestamp.from(expiresAt)).param("id", exportId).update());
			if (published == null || published == 0) {
				// the request vanished (account deleted while the archive was built): do not leave the object behind
				deleteQuietly(objectKey);
				log.info("Data export {} was withdrawn while it was built; its archive object was deleted", exportId);
				return;
			}
			count("ready");
			log.info("Data export {} (user {}) ready: {} sections, {} rows, {} bytes sealed, link valid until {}", exportId,
					userId, archive.sectionCount(), archive.rowCount(), sealed.ciphertext().length, expiresAt);
		}
		catch (CryptoShreddedException shredded) {
			deleteQuietly(objectKey);
			fail(exportId, "ACCOUNT_CLOSED");
		}
		catch (RuntimeException e) {
			deleteQuietly(objectKey);
			fail(exportId, e.getClass().getName());
		}
	}

	/** Expires finished exports whose link has ended: deletes the object, then marks the row. */
	int expireFinished(Instant now) {
		List<DataExportService.Export> due = jdbc.sql(DataExportService.SELECT + """
				 WHERE request_status IN ('READY', 'DOWNLOADED') AND download_expires_at <= :now
				 ORDER BY download_expires_at LIMIT 100
				""").param("now", Timestamp.from(now)).query(DataExportService::map).list();
		int expired = 0;
		for (DataExportService.Export export : due) {
			try {
				if (export.objectKey() != null) {
					store.delete(export.objectKey());
				}
			}
			catch (RuntimeException e) {
				log.warn("Data export {}: the expired archive object could not be deleted ({}); retrying next run",
						export.id(), e.getClass().getName());
				continue;
			}
			expired += jdbc.sql("""
					UPDATE data_export_requests SET request_status = 'EXPIRED', export_object_key = NULL, updated_at = :now
					WHERE id = :id AND request_status IN ('READY', 'DOWNLOADED')
					""").param("now", Timestamp.from(now)).param("id", export.id()).update();
		}
		if (expired > 0) {
			count("expired");
			log.info("{} data export(s) expired and their archive objects deleted", expired);
		}
		return expired;
	}

	private boolean accountOpen(UUID userId) {
		return jdbc.sql("SELECT count(*) FROM users WHERE id = :u AND deleted_at IS NULL AND account_status <> 'MERGED'")
				.param("u", userId).query(Integer.class).single() == 1;
	}

	private void fail(UUID exportId, String reason) {
		String recorded = reason.length() > 500 ? reason.substring(0, 500) : reason;
		jdbc.sql("""
				UPDATE data_export_requests SET request_status = 'FAILED', processing_error = :error, updated_at = :now
				WHERE id = :id AND request_status = 'PROCESSING'
				""").param("error", recorded).param("now", Timestamp.from(clock.instant())).param("id", exportId).update();
		count("failed");
		log.error("Data export {} failed: {}", exportId, recorded);
	}

	private void deleteQuietly(String objectKey) {
		if (objectKey == null) {
			return;
		}
		try {
			store.delete(objectKey);
		}
		catch (RuntimeException e) {
			log.warn("An export archive object could not be deleted ({}); the bucket lifecycle removes it",
					e.getClass().getName());
		}
	}

	/** {@code exports/} plus 256 random bits in hex: unguessable and unrelated to the user or the export id. */
	static String newObjectKey() {
		byte[] random = new byte[32];
		RANDOM.nextBytes(random);
		return "exports/" + HexFormat.of().formatHex(random);
	}

	private void count(String outcome) {
		if (meters != null) {
			meters.counter("qal.privacy.data_export", "outcome", outcome).increment();
		}
	}
}
