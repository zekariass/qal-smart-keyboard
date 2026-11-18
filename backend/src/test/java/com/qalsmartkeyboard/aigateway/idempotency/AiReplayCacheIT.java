package com.qalsmartkeyboard.aigateway.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.aigateway.AiCommand;
import com.qalsmartkeyboard.aigateway.AiCommand.Caller;
import com.qalsmartkeyboard.aigateway.AiIdempotency;
import com.qalsmartkeyboard.aigateway.AiPipeline;
import com.qalsmartkeyboard.aigateway.AiResult;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.Behavior;
import com.qalsmartkeyboard.aigateway.adapter.MockAiProvider.FailureMode;
import com.qalsmartkeyboard.aigateway.routing.CircuitBreakers;
import com.qalsmartkeyboard.aigateway.spi.AiFeature;
import com.qalsmartkeyboard.aigateway.spi.UserText;
import com.qalsmartkeyboard.platform.ApiErrorCode;
import com.qalsmartkeyboard.platform.ApiException;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.CryptoShreddedException;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

/**
 * B5.4 "done when": duplicate retries against PostgreSQL 18 with the B5.1 mock provider and real metering. One
 * charge per key in every case: retry within the cache TTL (replayed from the cache, no provider call), after the cache
 * expired (recomputed, uncharged, not re-cached), concurrent duplicates (one provider call, the rest
 * {@code REQUEST_IN_PROGRESS}), another body under the same key ({@code IDEMPOTENCY_KEY_REUSED}), a failed original
 * (its failure again). Cache rows and fingerprints are ciphertext bound to their row, swept after expiry and purged on
 * account deletion. Time is a {@link OffsetClock} bean that the pipeline, metering and the cache all use; the sweeper
 * task is off in tests and {@link EncryptedReplayCache#sweep()} is called directly.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s",
		"qal.ai.registry-cache-ttl=0s", "qal.ai.routing.policy-cache-ttl=0s", "qal.ai.routing.kill-switch-cache-ttl=0s",
		"qal.ai.routing.cost-cache-ttl=0s", "qal.ai.pipeline.catalog-cache-ttl=0s" })
@Testcontainers
@Import(AiReplayCacheIT.Time.class)
class AiReplayCacheIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	/** Distinctive input text: no log line, cache row or fingerprint row may ever contain it. */
	static final String SECRET = "Zebra lighthouse 7741 marmalade";

	/** The system clock plus an offset a test can move forward. */
	static final class OffsetClock extends Clock {

		private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

		void advance(Duration d) {
			offset.updateAndGet(o -> o.plus(d));
		}

		void reset() {
			offset.set(Duration.ZERO);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return Clock.offset(Clock.system(zone), offset.get());
		}

		@Override
		public Instant instant() {
			return Instant.now().plus(offset.get());
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Time {
		@Bean
		OffsetClock testClock() {
			return new OffsetClock();
		}
	}

	@Autowired
	AiPipeline pipeline;
	@Autowired
	AiIdempotency idempotency;
	@Autowired
	EncryptedReplayCache cache;
	@Autowired
	AiIdempotencyDeletion deletion;
	@Autowired
	UserDataEncryption encryption;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	CircuitBreakers breakers;
	@Autowired
	@Qualifier("mockAiProvider")
	MockAiProvider mock;
	@Autowired
	OffsetClock clock;

	@BeforeEach
	void enable() {
		clock.reset();
		jdbc.update("UPDATE ai_providers SET provider_status = 'ACTIVE', dpa_signed_at = NOW() "
				+ "WHERE provider_code = 'mock'");
		mock.setBehavior(Behavior.ok());
		breakers.resetAll();
	}

	@AfterEach
	void disable() {
		clock.reset();
		mock.setBehavior(Behavior.ok());
		jdbc.update("UPDATE ai_providers SET provider_status = 'DISABLED', dpa_signed_at = NULL "
				+ "WHERE provider_code = 'mock'");
	}

	// ======================================================================================= helpers

	UUID user() {
		return jdbc.queryForObject("""
				INSERT INTO users (account_type, account_status, time_zone) VALUES ('REGISTERED', 'ACTIVE', 'UTC')
				RETURNING id""", UUID.class);
	}

	static String key() {
		return UUID.randomUUID().toString();
	}

	static AiCommand.Translate translate(UUID user, String key, String text, String destination) {
		return AiCommand.Translate.of(Caller.of(user, key), text, "en", destination);
	}

	/** {@code used_units, reserved_units} summed over the user's windows of the feature. */
	long[] counter(UUID user, String feature) {
		return jdbc.queryForObject("""
				SELECT COALESCE(SUM(used_units), 0), COALESCE(SUM(reserved_units), 0) FROM usage_counters
				 WHERE user_id = ? AND feature_code = ?""",
				(rs, n) -> new long[] { rs.getLong(1), rs.getLong(2) }, user, feature);
	}

	List<String> ledger(UUID user) {
		return jdbc.queryForList("SELECT event_type FROM usage_events WHERE subject_id = ? ORDER BY created_at, id",
				String.class, user);
	}

	long rows(String table, UUID user) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Long.class, user);
	}

	long aiRequests(UUID user) {
		return jdbc.queryForObject("SELECT count(*) FROM ai_requests WHERE user_id = ?", Long.class, user);
	}

	ApiErrorCode failure(AiCommand command) {
		AtomicReference<ApiErrorCode> code = new AtomicReference<>();
		assertThatThrownBy(() -> pipeline.execute(command)).isInstanceOfSatisfying(ApiException.class, e -> {
			code.set(e.code());
			assertThat(e.getMessage()).doesNotContain(SECRET);
		});
		return code.get();
	}

	void awaitProviderCall(int before) throws InterruptedException {
		for (int i = 0; i < 500 && mock.callCount() == before; i++) {
			Thread.sleep(10);
		}
		assertThat(mock.callCount()).isGreaterThan(before);
	}

	// ======================================================================================= done-when

	@Test
	void aRetryWithinTheTtlReplaysTheIdenticalResponseWithNoProviderCallAndNoSecondCharge() {
		assertThat(idempotency).isSameAs(cache); // the B5.4 bean replaced B5.3's ReplayWithoutCache
		UUID user = user();
		String k = key();
		try (CapturedLogs logs = new CapturedLogs()) {
			AiResult.TranslateResponse first = (AiResult.TranslateResponse) pipeline.execute(translate(user, k, SECRET,
					"om"));
			int calls = mock.callCount();
			assertThat(first.replayed()).isFalse();
			assertThat(rows("ai_replay_cache", user)).isEqualTo(1);
			assertThat(rows("ai_request_fingerprints", user)).isEqualTo(1);

			for (int i = 0; i < 3; i++) {
				clock.advance(Duration.ofMinutes(3)); // 9 minutes in: still inside the 10-minute TTL
				AiResult again = pipeline.execute(translate(user, k, SECRET, "om"));
				assertThat(again).isEqualTo(EncryptedReplayCache.replayed(first)); // field for field
				assertThat(((AiResult.TranslateResponse) again).replayed()).isTrue();
				assertThat(((AiResult.TranslateResponse) again).requestId()).isEqualTo(first.requestId());
				assertThat(((AiResult.TranslateResponse) again).usage()).isEqualTo(first.usage());
			}
			assertThat(mock.callCount()).isEqualTo(calls);
			for (String line : logs.texts()) {
				assertThat(line).doesNotContain(SECRET);
			}
		}
		assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
		assertThat(ledger(user)).containsExactly("RESERVE", "SETTLE");
		assertThat(aiRequests(user)).isEqualTo(1);

		// the transform response round-trips through the cache too
		String g = key();
		AiCommand.Transform grammar = new AiCommand.Transform(Caller.of(user, g), AiFeature.GRAMMAR_CORRECTION,
				UserText.of("  " + SECRET + "  please "), "en", "SPELLING_ONLY", null, null, null, null);
		AiResult.TransformResponse fixed = (AiResult.TransformResponse) pipeline.execute(grammar);
		int calls = mock.callCount();
		assertThat(pipeline.execute(grammar)).isEqualTo(EncryptedReplayCache.replayed(fixed));
		assertThat(mock.callCount()).isEqualTo(calls);
		assertThat(counter(user, "GRAMMAR_CORRECTION")).containsExactly(1, 0);
	}

	@Test
	void afterTheCacheExpiredARetryIsRecomputedWithoutASecondChargeAndIsNotCachedAgain() {
		UUID user = user();
		String k = key();
		AiResult.TranslateResponse first = (AiResult.TranslateResponse) pipeline.execute(translate(user, k, SECRET,
				"om"));
		UUID cached = jdbc.queryForObject("SELECT request_id FROM ai_replay_cache WHERE user_id = ?", UUID.class, user);
		assertThat(cached).isEqualTo(first.requestId());
		int calls = mock.callCount();

		clock.advance(Duration.ofMinutes(10).plusSeconds(1));
		// not swept yet: the row is still there, but reads ignore it
		assertThat(rows("ai_replay_cache", user)).isEqualTo(1);
		AiResult.TranslateResponse recomputed = (AiResult.TranslateResponse) pipeline.execute(translate(user, k,
				SECRET, "om"));
		assertThat(mock.callCount()).isEqualTo(calls + 1);
		assertThat(recomputed.translatedText()).isEqualTo(first.translatedText());
		assertThat(recomputed.replayed()).isFalse();
		assertThat(recomputed.usage().usageSource()).isEqualTo("UNMETERED");
		assertThat(recomputed.usage().unitsCharged()).isZero();
		assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
		assertThat(ledger(user)).containsExactly("RESERVE", "SETTLE");
		// the uncharged recompute is not cached (only the original, now expired, row exists)
		assertThat(jdbc.queryForList("SELECT request_id FROM ai_replay_cache WHERE user_id = ?", UUID.class, user))
				.containsExactly(first.requestId());

		assertThat(cache.sweep()).isPositive();
		assertThat(rows("ai_replay_cache", user)).isZero();
		assertThat(rows("ai_request_fingerprints", user)).isEqualTo(1); // kept 24 h to recognise the key

		// a further retry: recomputed again, still one charge
		AiResult.TranslateResponse again = (AiResult.TranslateResponse) pipeline.execute(translate(user, k, SECRET,
				"om"));
		assertThat(again.usage().unitsCharged()).isZero();
		assertThat(mock.callCount()).isEqualTo(calls + 2);
		assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
		assertThat(rows("ai_replay_cache", user)).isZero();
	}

	@Test
	void concurrentDuplicatesMakeOneProviderCallAndOneChargeTheOthersAreInProgress() throws Exception {
		UUID user = user();
		String k = key();
		mock.setBehavior(Behavior.slow(Duration.ofMillis(1500)));
		int calls = mock.callCount();
		int n = 6;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<AiResult>> futures = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			for (int i = 0; i < n; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return pipeline.execute(translate(user, k, SECRET, "om"));
				}));
			}
			start.countDown();
			List<AiResult> succeeded = new ArrayList<>();
			List<ApiException> refused = new ArrayList<>();
			for (Future<AiResult> f : futures) {
				try {
					succeeded.add(f.get(10, TimeUnit.SECONDS));
				} catch (java.util.concurrent.ExecutionException e) {
					assertThat(e.getCause()).isInstanceOf(ApiException.class);
					refused.add((ApiException) e.getCause());
				}
			}
			assertThat(succeeded).hasSize(1);
			assertThat(refused).hasSize(n - 1).allSatisfy(e -> {
				assertThat(e.code()).isEqualTo(ApiErrorCode.REQUEST_IN_PROGRESS);
				assertThat(e.status().value()).isEqualTo(409);
				assertThat(e.headers()).containsKey("Retry-After");
			});
			assertThat(mock.callCount()).isEqualTo(calls + 1);
			assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
			assertThat(ledger(user)).containsExactly("RESERVE", "SETTLE");

			// a duplicate arriving after completion gets the cached original
			mock.setBehavior(Behavior.ok());
			AiResult late = pipeline.execute(translate(user, k, SECRET, "om"));
			assertThat(late).isEqualTo(EncryptedReplayCache.replayed(succeeded.get(0)));
			assertThat(mock.callCount()).isEqualTo(calls + 1);
			assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void theSameKeyWithAnotherBodyIsRefusedInFlightAndAfterwards() throws Exception {
		UUID user = user();
		String k = key();
		mock.setBehavior(Behavior.slow(Duration.ofMillis(1200)));
		int before = mock.callCount();
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<AiResult> original = pool.submit(() -> pipeline.execute(translate(user, k, SECRET, "om")));
			awaitProviderCall(before);
			// in flight: the fingerprint is stored before the provider call
			assertThat(failure(translate(user, k, SECRET + " v2", "om"))).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
			assertThat(failure(translate(user, k, SECRET, "om"))).isEqualTo(ApiErrorCode.REQUEST_IN_PROGRESS);
			assertThat(original.get(10, TimeUnit.SECONDS)).isNotNull();
		} finally {
			pool.shutdownNow();
		}
		mock.setBehavior(Behavior.ok());
		int calls = mock.callCount();
		assertThat(calls).isEqualTo(before + 1);

		// settled: text, language, options or feature differ -> 409, no provider call, no charge
		assertThat(failure(translate(user, k, SECRET + ".", "om"))).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
		assertThat(failure(translate(user, k, SECRET, "ti"))).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
		assertThat(failure(new AiCommand.Translate(Caller.of(user, k), UserText.of(SECRET), "en", "om", "POLITE", null,
				null, null, null, null))).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
		// another feature is already caught by metering (feature differs from the reservation)
		assertThat(failure(AiCommand.Transform.of(Caller.of(user, k), AiFeature.GRAMMAR_CORRECTION, SECRET, "en")))
				.isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
		assertThat(mock.callCount()).isEqualTo(calls);
		assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
		// the original body still replays from the cache
		assertThat(((AiResult.TranslateResponse) pipeline.execute(translate(user, k, SECRET, "om"))).replayed())
				.isTrue();

		// transform: mode and tone are part of the body
		String g = key();
		pipeline.execute(new AiCommand.Transform(Caller.of(user, g), AiFeature.GRAMMAR_CORRECTION, UserText.of(SECRET),
				"en", "GRAMMAR", null, null, null, null));
		assertThat(failure(new AiCommand.Transform(Caller.of(user, g), AiFeature.GRAMMAR_CORRECTION,
				UserText.of(SECRET), "en", "SPELLING_ONLY", null, null, null, null)))
				.isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
		assertThat(counter(user, "GRAMMAR_CORRECTION")).containsExactly(1, 0);
	}

	@Test
	void aFailedOriginalReplaysItsFailureEvenOnceTheProviderIsBack() {
		UUID user = user();
		String k = key();
		mock.setBehavior(Behavior.failing(FailureMode.UNAVAILABLE));
		assertThat(failure(translate(user, k, SECRET, "om"))).isEqualTo(ApiErrorCode.PROVIDER_UNAVAILABLE);
		int calls = mock.callCount();
		assertThat(failure(translate(user, k, SECRET, "om"))).isEqualTo(ApiErrorCode.PROVIDER_UNAVAILABLE);
		mock.setBehavior(Behavior.ok());
		breakers.resetAll();
		assertThat(failure(translate(user, k, SECRET, "om"))).isEqualTo(ApiErrorCode.PROVIDER_UNAVAILABLE); // D25
		assertThat(failure(translate(user, k, SECRET + "!", "om"))).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
		assertThat(mock.callCount()).isEqualTo(calls);
		assertThat(counter(user, "TRANSLATE")).containsExactly(0, 0);
		assertThat(ledger(user)).containsExactly("RESERVE", "RELEASE");
		assertThat(rows("ai_replay_cache", user)).isZero(); // failures are never cached
		// a new key is a new attempt
		assertThat(pipeline.execute(translate(user, key(), SECRET, "om"))).isNotNull();
		assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
	}

	@Test
	void cacheRowsAndFingerprintsAreCiphertextBoundToTheirRowAndTheCacheIsUnlogged() throws Exception {
		UUID user = user();
		String k = key();
		AiResult.TranslateResponse r = (AiResult.TranslateResponse) pipeline.execute(translate(user, k, SECRET, "om"));
		byte[] response = jdbc.queryForObject("SELECT response_ciphertext FROM ai_replay_cache WHERE request_id = ?",
				byte[].class, r.requestId());
		byte[] fingerprint = jdbc.queryForObject(
				"SELECT fingerprint_ciphertext FROM ai_request_fingerprints WHERE request_id = ?", byte[].class,
				r.requestId());

		HexFormat hex = HexFormat.of();
		String digestOfText = hex.formatHex(MessageDigest.getInstance("SHA-256")
				.digest(SECRET.getBytes(StandardCharsets.UTF_8)));
		String digestOfBody = hex.formatHex(RequestFingerprint.of(translate(user, k, SECRET, "om")));
		for (byte[] stored : List.of(response, fingerprint)) {
			String asText = new String(stored, StandardCharsets.UTF_8);
			String asHex = hex.formatHex(stored);
			assertThat(asText).doesNotContain(SECRET).doesNotContain(r.translatedText()).doesNotContain("translatedText");
			assertThat(asHex).doesNotContain(hex.formatHex(SECRET.getBytes(StandardCharsets.UTF_8)))
					.doesNotContain(digestOfText).doesNotContain(digestOfBody);
		}
		for (String table : List.of("ai_replay_cache", "ai_request_fingerprints")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " t WHERE t::text LIKE ? OR t::text LIKE ?",
					Long.class, "%" + SECRET + "%", "%" + k + "%")).as(table).isZero();
		}
		assertThat(fingerprint).hasSizeGreaterThan(32); // sealed, not the bare digest

		// sealed for this user and this row: opens there, and nowhere else
		String json = encryption.decryptString(user, EncryptedReplayCache.cacheContext(r.requestId()), response);
		assertThat(json).contains(r.translatedText()).contains("\"translatedText\"");
		assertThat(encryption.decrypt(user, EncryptedReplayCache.fingerprintContext(r.requestId()), fingerprint))
				.isEqualTo(RequestFingerprint.of(translate(user, k, SECRET, "om")));
		assertThatThrownBy(() -> encryption.decrypt(user, EncryptedReplayCache.cacheContext(UUID.randomUUID()),
				response)).isInstanceOf(CiphertextRejectedException.class);
		assertThatThrownBy(() -> encryption.decrypt(user, EncryptionContext.of("ai_request_fingerprints",
				"fingerprint_ciphertext", r.requestId()), response)).isInstanceOf(CiphertextRejectedException.class);
		UUID other = user();
		encryption.encryptString(other, EncryptedReplayCache.cacheContext(r.requestId()), "x"); // give it a key
		assertThatThrownBy(() -> encryption.decrypt(other, EncryptedReplayCache.cacheContext(r.requestId()), response))
				.isInstanceOf(CiphertextRejectedException.class);

		// a tampered cache row is a miss: recomputed, never charged twice
		jdbc.update("UPDATE ai_replay_cache SET response_ciphertext = ? WHERE request_id = ?",
				java.util.Arrays.copyOf(response, response.length - 1), r.requestId());
		int calls = mock.callCount();
		AiResult.TranslateResponse recomputed = (AiResult.TranslateResponse) pipeline.execute(translate(user, k,
				SECRET, "om"));
		assertThat(recomputed.usage().unitsCharged()).isZero();
		assertThat(mock.callCount()).isEqualTo(calls + 1);
		assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);

		assertThat(jdbc.queryForObject("SELECT relpersistence::text FROM pg_class WHERE relname = 'ai_replay_cache'",
				String.class)).isEqualTo("u");
		assertThat(jdbc.queryForObject(
				"SELECT relpersistence::text FROM pg_class WHERE relname = 'ai_request_fingerprints'", String.class))
				.isEqualTo("p");
	}

	@Test
	void fingerprintsAreSweptAfterTheRetentionAndTheKeyIsThenRefusedNotRecomputed() {
		UUID user = user();
		String k = key();
		pipeline.execute(translate(user, k, SECRET, "om"));
		int calls = mock.callCount();

		clock.advance(Duration.ofHours(24).plusMinutes(1));
		// expired but not swept: already ignored
		assertThat(rows("ai_request_fingerprints", user)).isEqualTo(1);
		assertThat(failure(translate(user, k, SECRET, "om"))).isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
		assertThat(cache.sweep()).isGreaterThanOrEqualTo(2);
		assertThat(rows("ai_request_fingerprints", user)).isZero();
		assertThat(rows("ai_replay_cache", user)).isZero();
		// an unverifiable body never buys an uncharged result
		assertThat(failure(translate(user, k, "something else entirely", "om")))
				.isEqualTo(ApiErrorCode.IDEMPOTENCY_KEY_REUSED);
		assertThat(mock.callCount()).isEqualTo(calls);
		assertThat(counter(user, "TRANSLATE")).containsExactly(1, 0);
		assertThat(cache.sweep()).isZero(); // idempotent
	}

	@Test
	void accountDeletionPurgesTheRowsAndCryptoShreddingMakesLeftoversUnreadable() {
		UUID user = user();
		UUID bystander = user();
		AiResult.TranslateResponse r = (AiResult.TranslateResponse) pipeline.execute(translate(user, key(), SECRET,
				"om"));
		pipeline.execute(translate(bystander, key(), SECRET, "om"));
		byte[] response = jdbc.queryForObject("SELECT response_ciphertext FROM ai_replay_cache WHERE request_id = ?",
				byte[].class, r.requestId());

		encryption.cryptoShred(user); // B6.2 step 1 (privacy) runs before the participants
		assertThatThrownBy(() -> encryption.decrypt(user, EncryptedReplayCache.cacheContext(r.requestId()), response))
				.isInstanceOf(CryptoShreddedException.class);

		assertThat(deletion.purgeUser(user, Instant.now())).isEqualTo(2);
		assertThat(rows("ai_replay_cache", user)).isZero();
		assertThat(rows("ai_request_fingerprints", user)).isZero();
		assertThat(deletion.purgeUser(user, Instant.now())).isZero(); // idempotent
		assertThat(rows("ai_replay_cache", bystander)).isEqualTo(1);
		assertThat(rows("ai_request_fingerprints", bystander)).isEqualTo(1);
	}

	@Test
	void anUnmeteredDetectionIsNeitherFingerprintedNorCached() {
		UUID user = user();
		pipeline.execute(new AiCommand.Detect(Caller.of(user, null), UserText.of(SECRET), List.of()));
		assertThat(rows("ai_replay_cache", user)).isZero();
		assertThat(rows("ai_request_fingerprints", user)).isZero();
		Map<String, Object> row = jdbc.queryForMap("SELECT request_status FROM ai_requests WHERE user_id = ?", user);
		assertThat(row.get("request_status")).isEqualTo("SUCCEEDED");
	}
}
