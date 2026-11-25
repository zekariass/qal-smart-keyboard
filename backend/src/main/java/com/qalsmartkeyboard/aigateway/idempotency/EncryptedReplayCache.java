package com.qalsmartkeyboard.aigateway.idempotency;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.qalsmartkeyboard.aigateway.AiCommand;
import com.qalsmartkeyboard.aigateway.AiIdempotency;
import com.qalsmartkeyboard.aigateway.AiResult;
import com.qalsmartkeyboard.aigateway.pipeline.ReplayWithoutCache;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.KeyManagementException;
import com.qalsmartkeyboard.platform.crypto.UserDataCryptoException;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.usage.Reservation;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * The B5.4 {@link AiIdempotency} (plan rule N5, SD 8.3, SD 11.6, contract {@code IdempotencyKey}, ADR 0017 "Replay
 * cache (B5.4)"). Metering ({@code usage_reservations}, unique per user and key) already guarantees at-most-once
 * charging; this adds what metering cannot know:
 *
 * <ul>
 * <li><b>Request fingerprint</b> ({@link RequestFingerprint}): stored in {@code ai_request_fingerprints} by
 * {@link #onReserved}, before the provider call, sealed with the user's data key (context: table
 * {@code ai_request_fingerprints}, column {@code fingerprint_ciphertext}, row = request id), kept
 * {@code fingerprint-retention} (24 h). A replay whose body differs is 409 {@code IDEMPOTENCY_KEY_REUSED}, in flight or
 * not.</li>
 * <li><b>Replay cache</b>: {@link #onCompleted} seals the serialised response (contract JSON of the {@link AiResult})
 * with the user's data key (context: table {@code ai_replay_cache}, column {@code response_ciphertext}, row = request
 * id) into the UNLOGGED table {@code ai_replay_cache} for {@code cache-ttl} (at most 10 minutes). Never plaintext.</li>
 * </ul>
 *
 * <p>{@link #onReplay}, after the fingerprint check:
 * <ul>
 * <li>RESERVED (in flight): 409 {@code REQUEST_IN_PROGRESS} with {@code Retry-After}.</li>
 * <li>SETTLED: the cached response with {@code replayed = true} (no provider call, no charge); after the cache expired,
 * {@link Decision.Recompute} (uncharged, and the pipeline never caches an uncharged recompute); a key whose fingerprint
 * is gone (older than the retention) is {@code IDEMPOTENCY_KEY_REUSED}: an unverifiable body must not buy an uncharged
 * result.</li>
 * <li>RELEASED: the cached response if the original nevertheless succeeded (its reservation was swept before it
 * settled, so it was answered uncharged), else the original failure (ADR 0007 D25), as {@link ReplayWithoutCache}.</li>
 * </ul>
 *
 * <p>Reads ignore rows past {@code expires_at} (by this instance's clock), so an expired entry is never served even
 * before {@link #sweep} deletes it. Cache trouble (a database or crypto error) never changes a charge: a failed write
 * only means a later replay recomputes uncharged, and an unreadable entry is treated as a miss.
 */
public final class EncryptedReplayCache implements AiIdempotency {

	static final String FINGERPRINT_TABLE = "ai_request_fingerprints";
	static final String FINGERPRINT_COLUMN = "fingerprint_ciphertext";
	static final String CACHE_TABLE = "ai_replay_cache";
	static final String CACHE_COLUMN = "response_ciphertext";

	private static final Logger log = LoggerFactory.getLogger(EncryptedReplayCache.class);

	/** A private mapper, so the stored format does not follow application-wide JSON settings. */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * @param cacheTtl             replay cache lifetime, 0 to 10 minutes (0 = no cache)
	 * @param fingerprintRetention how long a key's fingerprint is kept (at least 24 h)
	 * @param sweepBatchSize       rows deleted per sweep statement
	 */
	public record Settings(Duration cacheTtl, Duration fingerprintRetention, int sweepBatchSize) {

		public Settings {
			if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.compareTo(Duration.ofMinutes(10)) > 0
					|| fingerprintRetention == null || !fingerprintRetention.isPositive() || sweepBatchSize < 1) {
				throw new IllegalArgumentException("invalid replay cache settings");
			}
		}
	}

	private final JdbcClient jdbc;
	private final UserDataEncryption encryption;
	private final ReplayWithoutCache uncached;
	private final Clock clock;
	private final MeterRegistry meters;
	private final Settings settings;

	public EncryptedReplayCache(JdbcClient jdbc, UserDataEncryption encryption, ReplayWithoutCache uncached, Clock clock,
			MeterRegistry meters, Settings settings) {
		this.jdbc = Objects.requireNonNull(jdbc);
		this.encryption = Objects.requireNonNull(encryption);
		this.uncached = Objects.requireNonNull(uncached);
		this.clock = Objects.requireNonNull(clock);
		this.meters = meters;
		this.settings = Objects.requireNonNull(settings);
	}

	// ============================================================================================ seam

	@Override
	public void onReserved(AiCommand command, Reservation reservation) {
		UUID user = command.caller().userId();
		byte[] digest = RequestFingerprint.of(command);
		try {
			EncryptedValue sealed = encryption.encrypt(user, fingerprintContext(reservation.id()), digest);
			Instant now = clock.instant();
			jdbc.sql("""
					INSERT INTO ai_request_fingerprints (request_id, user_id, fingerprint_ciphertext, key_version,
					                                     created_at, expires_at)
					VALUES (:id, :user, :ciphertext, :version, :now, :expires)
					ON CONFLICT (request_id) DO NOTHING
					""").param("id", reservation.id()).param("user", user).param("ciphertext", sealed.ciphertext())
					.param("version", sealed.keyVersion()).param("now", Timestamp.from(now))
					.param("expires", Timestamp.from(now.plus(settings.fingerprintRetention()))).update();
		} finally {
			Arrays.fill(digest, (byte) 0);
		}
	}

	@Override
	public Decision onReplay(AiCommand command, Reservation reservation) {
		UUID user = command.caller().userId();
		Instant now = clock.instant();
		Optional<byte[]> stored = storedFingerprint(user, reservation.id(), now);
		if (stored.isPresent()) {
			byte[] digest = RequestFingerprint.of(command);
			boolean same = RequestFingerprint.matches(stored.get(), digest);
			Arrays.fill(digest, (byte) 0);
			Arrays.fill(stored.get(), (byte) 0);
			if (!same) {
				count("key_reused");
				return new Decision.Fail(new ApiException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
						ApiErrorCode.IDEMPOTENCY_KEY_REUSED.defaultStatus(),
						"This Idempotency-Key was already used for a different request."));
			}
		}
		return switch (reservation.status()) {
			case RESERVED -> {
				count("in_progress");
				yield uncached.onReplay(command, reservation);
			}
			case SETTLED -> {
				if (stored.isEmpty()) {
					// the body cannot be compared any more: an unverified body must not get an uncharged result
					count("key_expired");
					yield new Decision.Fail(new ApiException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
							ApiErrorCode.IDEMPOTENCY_KEY_REUSED.defaultStatus(),
							"This Idempotency-Key is no longer recognised; use a new key for a new request."));
				}
				Optional<AiResult> cached = cached(command, reservation.id(), now);
				count(cached.isPresent() ? "hit" : "miss");
				yield cached.<Decision>map(Decision.Respond::new).orElseGet(Decision.Recompute::new);
			}
			case RELEASED -> {
				Optional<AiResult> cached = stored.isEmpty() ? Optional.empty() : cached(command, reservation.id(), now);
				count(cached.isPresent() ? "hit" : "released");
				yield cached.<Decision>map(Decision.Respond::new).orElseGet(() -> uncached.onReplay(command, reservation));
			}
		};
	}

	@Override
	public void onCompleted(AiCommand command, UUID requestId, AiResult result) {
		if (settings.cacheTtl().isZero() || result instanceof AiResult.DetectLanguageResponse) {
			return;
		}
		UUID user = command.caller().userId();
		byte[] body = JSON.writeValueAsBytes(result);
		try {
			EncryptedValue sealed = encryption.encrypt(user, cacheContext(requestId), body);
			Instant now = clock.instant();
			jdbc.sql("""
					INSERT INTO ai_replay_cache (request_id, user_id, response_ciphertext, key_version, created_at,
					                             expires_at)
					VALUES (:id, :user, :ciphertext, :version, :now, :expires)
					ON CONFLICT (request_id) DO NOTHING
					""").param("id", requestId).param("user", user).param("ciphertext", sealed.ciphertext())
					.param("version", sealed.keyVersion()).param("now", Timestamp.from(now))
					.param("expires", Timestamp.from(now.plus(settings.cacheTtl()))).update();
			count("stored");
		} finally {
			Arrays.fill(body, (byte) 0);
		}
	}

	// ============================================================================================ reads

	private Optional<byte[]> storedFingerprint(UUID user, UUID requestId, Instant now) {
		Optional<byte[]> sealed = jdbc.sql("""
				SELECT fingerprint_ciphertext FROM ai_request_fingerprints
				 WHERE request_id = :id AND user_id = :user AND expires_at > :now
				""").param("id", requestId).param("user", user).param("now", Timestamp.from(now)).query(byte[].class)
				.optional();
		if (sealed.isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(encryption.decrypt(user, fingerprintContext(requestId), sealed.get()));
		} catch (KeyManagementException e) {
			throw e; // KMS trouble: fail this replay (500, nothing charged) rather than guess
		} catch (UserDataCryptoException e) {
			log.warn("AI request fingerprint {} is unreadable: {}", requestId, e.getClass().getSimpleName());
			count("fingerprint_unreadable");
			return Optional.empty();
		}
	}

	private Optional<AiResult> cached(AiCommand command, UUID requestId, Instant now) {
		UUID user = command.caller().userId();
		Optional<byte[]> sealed = jdbc.sql("""
				SELECT response_ciphertext FROM ai_replay_cache
				 WHERE request_id = :id AND user_id = :user AND expires_at > :now
				""").param("id", requestId).param("user", user).param("now", Timestamp.from(now)).query(byte[].class)
				.optional();
		if (sealed.isEmpty()) {
			return Optional.empty();
		}
		Class<? extends AiResult> type = switch (command) {
			case AiCommand.Translate t -> AiResult.TranslateResponse.class;
			case AiCommand.Transform t -> AiResult.TransformResponse.class;
			case AiCommand.Reply r -> AiResult.RepliesResponse.class;
			case AiCommand.Draft d -> AiResult.DraftResponse.class;
			case AiCommand.Chat c -> AiResult.ChatResponse.class;
			case AiCommand.Detect d -> null;
		};
		if (type == null) {
			return Optional.empty();
		}
		byte[] body = null;
		try {
			body = encryption.decrypt(user, cacheContext(requestId), sealed.get());
			return Optional.of(replayed(JSON.readValue(body, type)));
		} catch (RuntimeException e) {
			// unreadable (KMS, tampered, unknown format): a miss, which recomputes uncharged
			log.warn("AI replay cache entry {} is unreadable: {}", requestId, e.getClass().getSimpleName());
			count("unreadable");
			return Optional.empty();
		} finally {
			if (body != null) {
				Arrays.fill(body, (byte) 0);
			}
		}
	}

	/** The original response, marked as coming from the replay cache (contract {@code replayed: true}). */
	static AiResult replayed(AiResult r) {
		return switch (r) {
			case AiResult.TranslateResponse t -> new AiResult.TranslateResponse(t.requestId(), t.sourceLanguage(),
					t.detectedSourceLanguage(), t.detectionConfidence(), t.destinationLanguage(), t.translatedText(),
					t.alternatives(), t.notes(), t.qualityTier(), true, t.historyItemId(), t.usage());
			case AiResult.TransformResponse t -> new AiResult.TransformResponse(t.requestId(), t.task(), t.language(),
					t.resultText(), t.edits(), t.alternatives(), t.notes(), t.qualityTier(), true, t.historyItemId(),
					t.usage());
			case AiResult.RepliesResponse x -> new AiResult.RepliesResponse(x.requestId(), x.language(),
					x.suggestions(), x.notes(), x.qualityTier(), true, x.historyItemId(), x.usage());
			case AiResult.DraftResponse d -> new AiResult.DraftResponse(d.requestId(), d.mode(), d.language(),
					d.subject(), d.body(), d.placeholders(), d.notes(), d.qualityTier(), true, d.historyItemId(),
					d.usage());
			case AiResult.ChatResponse c -> new AiResult.ChatResponse(c.requestId(), c.message(), c.language(),
					c.finishReason(), c.notes(), c.qualityTier(), true, c.usage());
			case AiResult.DetectLanguageResponse d -> d;
		};
	}

	// ============================================================================================ sweep

	/**
	 * Deletes expired replay cache rows and fingerprints, in batches. Idempotent and safe on several instances (it only
	 * deletes rows that reads already ignore).
	 *
	 * @return the number of rows deleted
	 */
	public int sweep() {
		Instant now = clock.instant();
		int cache = deleteExpired("ai_replay_cache", now);
		int fingerprints = deleteExpired("ai_request_fingerprints", now);
		if (cache + fingerprints > 0) {
			log.info("Swept {} expired AI replay cache row(s) and {} request fingerprint(s)", cache, fingerprints);
			if (meters != null) {
				meters.counter("qal.ai.idempotency.swept", "table", "ai_replay_cache").increment(cache);
				meters.counter("qal.ai.idempotency.swept", "table", "ai_request_fingerprints").increment(fingerprints);
			}
		}
		return cache + fingerprints;
	}

	private int deleteExpired(String table, Instant now) {
		int total = 0;
		int deleted;
		do {
			// table is one of two constants, never input
			deleted = jdbc.sql("DELETE FROM " + table + " WHERE request_id IN (SELECT request_id FROM " + table
					+ " WHERE expires_at <= :now LIMIT :limit)").param("now", Timestamp.from(now))
					.param("limit", settings.sweepBatchSize()).update();
			total += deleted;
		} while (deleted >= settings.sweepBatchSize());
		return total;
	}

	// ============================================================================================ helpers

	static EncryptionContext fingerprintContext(UUID requestId) {
		return EncryptionContext.of(FINGERPRINT_TABLE, FINGERPRINT_COLUMN, requestId);
	}

	static EncryptionContext cacheContext(UUID requestId) {
		return EncryptionContext.of(CACHE_TABLE, CACHE_COLUMN, requestId);
	}

	private void count(String outcome) {
		if (meters != null) {
			meters.counter("qal.ai.idempotency.replay-cache", "outcome", outcome).increment();
		}
	}
}
