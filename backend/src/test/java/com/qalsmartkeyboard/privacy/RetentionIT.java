package com.qalsmartkeyboard.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.aigateway.idempotency.EncryptedReplayCache;
import com.qalsmartkeyboard.platform.retention.RetentionAction;
import com.qalsmartkeyboard.platform.retention.RetentionParticipant;
import com.qalsmartkeyboard.platform.retention.RetentionRule;
import com.qalsmartkeyboard.platform.retention.RetentionSupport;
import com.qalsmartkeyboard.testsupport.logging.CapturedLogs;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * B6.3 done-when on PostgreSQL 18 with an injected clock: for every data class, rows just inside the period survive and
 * rows just outside are purged (the boundary itself survives: the comparison is strict); the job is idempotent and
 * resumable; two instances never double-delete; the financial and legal tables are untouched; a failing rule does not
 * stop the others; nothing but rule codes and counts reaches the logs. The dictionary tombstone resync (D31) is
 * {@code DictionaryResyncIT}.
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@Testcontainers
class RetentionIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
	static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	JdbcClient client;
	@Autowired
	PlatformTransactionManager transactionManager;
	@Autowired
	RetentionPolicy policy;
	@Autowired
	RetentionProperties properties;
	@Autowired
	List<RetentionParticipant> participants;
	@Autowired
	EncryptedReplayCache replayCache;

	// ------------------------------------------------------------------------------------------------ helpers

	SimpleMeterRegistry meters = new SimpleMeterRegistry();

	RetentionJob job() {
		return job(policy, properties.batchSize(), properties.maxBatchesPerRule());
	}

	RetentionJob job(RetentionPolicy p, int batch, int maxBatches) {
		return new RetentionJob(client, new TransactionTemplate(transactionManager), p,
				new RetentionProperties(true, "0 0 3 * * *", batch, maxBatches, Map.of()), CLOCK, meters);
	}

	RetentionJob.Result run() {
		RetentionJob.Result result = job().run();
		assertThat(result.failed()).isEmpty();
		assertThat(result.skipped()).isEmpty();
		return result;
	}

	Duration period(String code) {
		return policy.rule(code).orElseThrow().period();
	}

	/** Strictly younger than the period (cutoff itself): survives. */
	Instant atCutoff(String code) {
		return NOW.minus(period(code));
	}

	Instant inside(String code) {
		return atCutoff(code).plusSeconds(1);
	}

	Instant outside(String code) {
		return atCutoff(code).minusSeconds(1);
	}

	static Timestamp ts(Instant i) {
		return Timestamp.from(i);
	}

	int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	boolean exists(String table, String column, Object id) {
		return count("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", id) > 0;
	}

	static String hex64() {
		return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
	}

	UUID user(String status, Instant deletedAt, Instant updatedAt) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO users (id, account_status, deleted_at, updated_at) VALUES (?, ?, ?, ?)", id, status,
				deletedAt == null ? null : ts(deletedAt), ts(updatedAt == null ? NOW : updatedAt));
		return id;
	}

	UUID activeUser() {
		return user("ACTIVE", null, null);
	}

	UUID mergedGuest(Instant mergedAt) {
		UUID target = activeUser();
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO users (id, account_type, account_status, merged_into_user_id, updated_at)"
				+ " VALUES (?, 'GUEST', 'MERGED', ?, ?)", id, target, ts(mergedAt));
		return id;
	}

	UUID consent(UUID user) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO user_consents (id, user_id, consent_code, consent_version, is_granted)"
				+ " VALUES (?, ?, 'TERMS_ACCEPTANCE', '1', TRUE)", id, user);
		return id;
	}

	// ------------------------------------------------------------------------------------------------ registry

	@Test
	void everyDataClassOfSd192HasAnEntryWithOwnerPeriodAndAction() {
		List<String> codes = policy.rules().stream().map(RetentionRule::code).toList();
		assertThat(codes).doesNotHaveDuplicates().contains("otp-challenges", "attestation-nonces", "user-sessions",
				"password-reset-tokens", "ai-request-fingerprints", "ai-replay-cache", "processed-events",
				"outbox-published", "outbox-dead", "dictionary-applied-changes", "dictionary-tombstones",
				"user-history-items", "ai-requests", "usage-reservations", "user-consents-deleted-accounts",
				"merged-guest-accounts", "deleted-account-tombstones", "data-export-requests", "payment-webhook-events",
				"audit-events-security", "audit-events-financial", "operational-logs", "audio-objects",
				"financial-records", "usage-events", "ai-credit-ledger");
		assertThat(policy.rules()).allSatisfy(r -> {
			assertThat(r.ownerModule()).isNotBlank();
			assertThat(r.reference()).isNotBlank();
			assertThat(r.description()).isNotBlank();
			assertThat(r.action()).isNotNull();
			if (r.action() != RetentionAction.KEEP) {
				assertThat(r.period()).isNotNull();
			}
		});
		// SD 19.2 periods
		assertThat(period("dictionary-tombstones")).isEqualTo(Duration.ofDays(180)); // D31
		assertThat(period("ai-request-fingerprints")).isEqualTo(Duration.ofHours(24));
		assertThat(period("dictionary-applied-changes")).isEqualTo(Duration.ofDays(30));
		assertThat(period("user-consents-deleted-accounts")).isEqualTo(Duration.ofDays(30));
		assertThat(period("deleted-account-tombstones")).isEqualTo(Duration.ofDays(30));
		assertThat(period("audit-events-security")).isEqualTo(Duration.ofDays(730));
		assertThat(period("audit-events-financial")).isEqualTo(Duration.ofDays(2557));
		assertThat(period("ai-requests")).isEqualTo(Duration.ofDays(396));
		assertThat(period("payment-webhook-events")).isEqualTo(Duration.ofDays(548));
		assertThat(policy.rule("financial-records").orElseThrow().action()).isEqualTo(RetentionAction.KEEP);
		assertThat(policy.rule("usage-events").orElseThrow().action()).isEqualTo(RetentionAction.KEEP);
		assertThat(policy.rule("ai-credit-ledger").orElseThrow().action()).isEqualTo(RetentionAction.KEEP);
		assertThat(policy.rule("ai-replay-cache").orElseThrow().action()).isEqualTo(RetentionAction.DELETE_BY_OWNER);
	}

	@Test
	void overridesApplyAndBadOnesStopTheApplication() {
		RetentionPolicy overridden = new RetentionPolicy(participants, Map.of("otp-challenges", Duration.ofHours(48)));
		assertThat(overridden.rule("otp-challenges").orElseThrow().period()).isEqualTo(Duration.ofHours(48));
		assertThat(overridden.rule("attestation-nonces").orElseThrow().period()).isEqualTo(Duration.ofHours(1));
		assertThatThrownBy(() -> new RetentionPolicy(participants, Map.of("otp-challenge", Duration.ofHours(1))))
				.hasMessageContaining("unknown retention rule");
		assertThatThrownBy(() -> new RetentionPolicy(participants, Map.of("financial-records", Duration.ofDays(1))))
				.hasMessageContaining("cannot be changed");
		assertThatThrownBy(() -> new RetentionPolicy(participants, Map.of("ai-replay-cache", Duration.ofDays(1))))
				.hasMessageContaining("cannot be changed");
		assertThatThrownBy(() -> new RetentionPolicy(List.of(participants.get(0), participants.get(0)), Map.of()))
				.hasMessageContaining("Duplicate retention rule");
	}

	// ------------------------------------------------------------------------------------------------ identity

	@Test
	void otpChallengesAttestationNoncesAndPasswordResetTokens() {
		UUID user = activeUser();
		List<UUID> otpGone = new ArrayList<>();
		List<UUID> otpKept = new ArrayList<>();
		for (Instant expires : List.of(outside("otp-challenges"), inside("otp-challenges"), atCutoff("otp-challenges"))) {
			UUID id = UUID.randomUUID();
			jdbc.update("INSERT INTO otp_challenges (id, phone_hash, code_hash, purpose, phone_ciphertext, created_at,"
					+ " expires_at) VALUES (?, ?, 'x', 'SIGN_IN', ?, ?, ?)", id, hex64(), new byte[32],
					ts(expires.minusSeconds(300)), ts(expires));
			(expires.isBefore(atCutoff("otp-challenges")) ? otpGone : otpKept).add(id);
		}
		UUID nonceGone = UUID.randomUUID();
		UUID nonceKept = UUID.randomUUID();
		for (UUID[] n : new UUID[][] { { nonceGone, null }, { nonceKept, null } }) {
			Instant expires = n[0] == nonceGone ? outside("attestation-nonces") : inside("attestation-nonces");
			jdbc.update("INSERT INTO attestation_nonces (id, nonce_hash, created_at, expires_at) VALUES (?, ?, ?, ?)",
					n[0], hex64(), ts(expires.minusSeconds(300)), ts(expires));
		}
		UUID resetGone = UUID.randomUUID();
		UUID resetKept = UUID.randomUUID();
		for (UUID id : List.of(resetGone, resetKept)) {
			Instant expires = id == resetGone ? outside("password-reset-tokens") : inside("password-reset-tokens");
			jdbc.update("INSERT INTO password_reset_tokens (id, user_id, token_hash, created_at, expires_at)"
					+ " VALUES (?, ?, ?, ?, ?)", id, user, hex64(), ts(expires.minusSeconds(300)), ts(expires));
		}

		RetentionJob.Result result = run();

		assertThat(result.purged()).containsEntry("otp-challenges", 1).containsEntry("attestation-nonces", 1)
				.containsEntry("password-reset-tokens", 1);
		otpGone.forEach(id -> assertThat(exists("otp_challenges", "id", id)).isFalse());
		otpKept.forEach(id -> assertThat(exists("otp_challenges", "id", id)).isTrue());
		assertThat(exists("attestation_nonces", "id", nonceGone)).isFalse();
		assertThat(exists("attestation_nonces", "id", nonceKept)).isTrue();
		assertThat(exists("password_reset_tokens", "id", resetGone)).isFalse();
		assertThat(exists("password_reset_tokens", "id", resetKept)).isTrue();
	}

	UUID session(UUID user, UUID family, Instant expires, String status, UUID replacedBy) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO user_sessions (id, user_id, token_family_id, refresh_token_hash, session_status,"
				+ " replaced_by_session_id, rotated_at, revoked_at, created_at, expires_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, user, family, hex64(), status, replacedBy,
				"ROTATED".equals(status) ? ts(expires.minusSeconds(60)) : null,
				"REVOKED".equals(status) ? ts(expires.minusSeconds(60)) : null, ts(expires.minus(Duration.ofDays(1))),
				ts(expires));
		return id;
	}

	@Test
	void sessionsGoAsWholeFamiliesOnlyWhenEverySessionExpiredLongAgo() {
		UUID user = activeUser();
		Instant old = outside("user-sessions");
		Instant edge = inside("user-sessions");
		// family A: a rotated chain, expired and revoked sessions, all outside the period: deleted without breaking
		// ck_session_rotation (the successor is inserted first)
		UUID a = UUID.randomUUID();
		UUID a2 = session(user, a, old, "ACTIVE", null);
		UUID a1 = session(user, a, old.minusSeconds(10), "ROTATED", a2);
		UUID a3 = session(user, a, old, "REVOKED", null);
		// family B: one old session, one live: nothing may go (the rotated row detects reuse)
		UUID b = UUID.randomUUID();
		UUID b2 = session(user, b, NOW.plus(Duration.ofDays(20)), "ACTIVE", null);
		UUID b1 = session(user, b, old.minusSeconds(10), "ROTATED", b2);
		// family C: its newest session is just inside the period
		UUID c = UUID.randomUUID();
		UUID c1 = session(user, c, old, "EXPIRED", null);
		UUID c2 = session(user, c, edge, "EXPIRED", null);
		// family D: exactly at the cutoff
		UUID d1 = session(user, UUID.randomUUID(), atCutoff("user-sessions"), "EXPIRED", null);

		assertThat(run().purged()).containsEntry("user-sessions", 3);

		for (UUID gone : List.of(a1, a2, a3)) {
			assertThat(exists("user_sessions", "id", gone)).isFalse();
		}
		for (UUID kept : List.of(b1, b2, c1, c2, d1)) {
			assertThat(exists("user_sessions", "id", kept)).isTrue();
		}
	}

	@Test
	void mergedGuestsAreDeletedWithTheirRowsThirtyDaysAfterTheMerge() {
		UUID gone = mergedGuest(outside("merged-guest-accounts"));
		UUID kept = mergedGuest(inside("merged-guest-accounts"));
		UUID atEdge = mergedGuest(atCutoff("merged-guest-accounts"));
		UUID goneConsent = consent(gone); // append-only; it goes with the guest (cascade, trigger depth 2)
		UUID keptConsent = consent(kept);

		assertThat(run().purged()).containsEntry("merged-guest-accounts", 1);

		assertThat(exists("users", "id", gone)).isFalse();
		assertThat(exists("user_consents", "id", goneConsent)).isFalse();
		assertThat(exists("users", "id", kept)).isTrue();
		assertThat(exists("users", "id", atEdge)).isTrue();
		assertThat(exists("user_consents", "id", keptConsent)).isTrue();
	}

	@Test
	void mergedGuestWithARestrictingFinancialRowIsKept() {
		UUID guest = mergedGuest(outside("merged-guest-accounts"));
		jdbc.update("INSERT INTO ai_credit_accounts (user_id) VALUES (?)", guest);

		run();

		assertThat(exists("users", "id", guest)).isTrue();
		assertThat(exists("ai_credit_accounts", "user_id", guest)).isTrue();
	}

	@Test
	void deletedAccountConsentsAndTombstonesFollowThirtyDaysAfterDeletion() {
		UUID gone = user("DELETED", outside("deleted-account-tombstones"), null);
		UUID inside = user("DELETED", inside("deleted-account-tombstones"), null);
		UUID live = activeUser();
		UUID goneConsent1 = consent(gone);
		UUID goneConsent2 = consent(gone);
		UUID insideConsent = consent(inside);
		UUID liveConsent = consent(live);
		// a deleted account whose financial row keeps the tombstone: its consents still go
		UUID financial = user("DELETED", outside("deleted-account-tombstones"), null);
		UUID financialConsent = consent(financial);
		jdbc.update("INSERT INTO ai_credit_accounts (user_id) VALUES (?)", financial);
		jdbc.update("INSERT INTO user_deletion_requests (user_id, request_status) VALUES (?, 'CANCELLED')", gone);

		RetentionJob.Result result = run();

		assertThat(result.purged()).containsEntry("user-consents-deleted-accounts", 3)
				.containsEntry("deleted-account-tombstones", 1);
		assertThat(exists("user_consents", "id", goneConsent1)).isFalse();
		assertThat(exists("user_consents", "id", goneConsent2)).isFalse();
		assertThat(exists("user_consents", "id", financialConsent)).isFalse();
		assertThat(exists("users", "id", gone)).isFalse();
		assertThat(exists("user_deletion_requests", "user_id", gone)).isFalse(); // cascades with the tombstone
		assertThat(exists("users", "id", financial)).isTrue(); // RESTRICT: kept for the legal period
		assertThat(exists("users", "id", inside)).isTrue();
		assertThat(exists("user_consents", "id", insideConsent)).isTrue();
		assertThat(exists("users", "id", live)).isTrue();
		assertThat(exists("user_consents", "id", liveConsent)).isTrue();
	}

	// ------------------------------------------------------------------------------------------------ privacy

	@Test
	void historyItemsPastTheirExpiryAreDeletedAndFavouritesStay() {
		UUID user = activeUser();
		UUID gone = history(user, outside("user-history-items"), false);
		UUID inside = history(user, inside("user-history-items"), false);
		UUID atEdge = history(user, atCutoff("user-history-items"), false);
		UUID favourite = history(user, null, true);

		assertThat(run().purged()).containsEntry("user-history-items", 1);

		assertThat(exists("user_history_items", "id", gone)).isFalse();
		for (UUID kept : List.of(inside, atEdge, favourite)) {
			assertThat(exists("user_history_items", "id", kept)).isTrue();
		}
	}

	UUID history(UUID user, Instant expires, boolean favourite) {
		UUID id = UUID.randomUUID();
		Instant created = expires == null ? NOW.minus(Duration.ofDays(400)) : expires.minus(Duration.ofDays(30));
		jdbc.update("INSERT INTO user_history_items (id, user_id, feature_code, input_ciphertext, output_ciphertext,"
				+ " key_version, is_favorite, created_at, expires_at) VALUES (?, ?, 'TRANSLATE', ?, ?, 1, ?, ?, ?)", id,
				user, new byte[] { 1 }, new byte[] { 2 }, favourite, ts(created), expires == null ? null : ts(expires));
		return id;
	}

	@Test
	void expiredDataExportsAreDeleted() {
		UUID gone = export("READY", outside("data-export-requests"));
		UUID inside = export("READY", inside("data-export-requests"));
		UUID neverCompleted = exportRequested(outside("data-export-requests"));
		UUID recentRequest = exportRequested(inside("data-export-requests"));

		assertThat(run().purged()).containsEntry("data-export-requests", 2);

		assertThat(exists("data_export_requests", "id", gone)).isFalse();
		assertThat(exists("data_export_requests", "id", neverCompleted)).isFalse();
		assertThat(exists("data_export_requests", "id", inside)).isTrue();
		assertThat(exists("data_export_requests", "id", recentRequest)).isTrue();
	}

	UUID export(String status, Instant downloadExpires) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO data_export_requests (id, user_id, request_status, export_object_key, requested_at,"
				+ " completed_at, download_expires_at) VALUES (?, ?, ?, 'exports/x.zip', ?, ?, ?)", id, activeUser(),
				status, ts(downloadExpires.minus(Duration.ofDays(8))), ts(downloadExpires.minus(Duration.ofDays(7))),
				ts(downloadExpires));
		return id;
	}

	UUID exportRequested(Instant requestedAt) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO data_export_requests (id, user_id, request_status, requested_at)"
				+ " VALUES (?, ?, 'REQUESTED', ?)", id, activeUser(), ts(requestedAt));
		return id;
	}

	// ------------------------------------------------------------------------------------------------ platform

	@Test
	void outboxAndProcessedEvents() {
		UUID published = outbox("PUBLISHED", NOW, outside("outbox-published"));
		UUID publishedKept = outbox("PUBLISHED", NOW, inside("outbox-published"));
		UUID dead = outbox("DEAD", outside("outbox-dead"), null);
		UUID deadKept = outbox("DEAD", inside("outbox-dead"), null);
		UUID pending = outbox("PENDING", NOW.minus(Duration.ofDays(900)), null); // never purged by age
		UUID failed = outbox("FAILED", NOW.minus(Duration.ofDays(900)), null);
		UUID eventOld = UUID.randomUUID();
		UUID eventNew = UUID.randomUUID();
		jdbc.update("INSERT INTO processed_events (consumer, event_id, processed_at) VALUES ('c', ?, ?), ('c', ?, ?)",
				eventOld, ts(outside("processed-events")), eventNew, ts(inside("processed-events")));

		RetentionJob.Result result = run();

		assertThat(result.purged()).containsEntry("outbox-published", 1).containsEntry("outbox-dead", 1)
				.containsEntry("processed-events", 1);
		assertThat(exists("outbox_events", "id", published)).isFalse();
		assertThat(exists("outbox_events", "id", dead)).isFalse();
		for (UUID kept : List.of(publishedKept, deadKept, pending, failed)) {
			assertThat(exists("outbox_events", "id", kept)).isTrue();
		}
		assertThat(exists("processed_events", "event_id", eventOld)).isFalse();
		assertThat(exists("processed_events", "event_id", eventNew)).isTrue();
	}

	UUID outbox(String status, Instant createdAt, Instant publishedAt) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, event_status, created_at,"
				+ " published_at) VALUES (?, 'x', 'y', 'z', ?, ?, ?)", id, status, ts(createdAt),
				publishedAt == null ? null : ts(publishedAt));
		return id;
	}

	// ------------------------------------------------------------------------------------------------ AI and usage

	@Test
	void aiFingerprintsAndRequestMetadata() {
		UUID user = activeUser();
		UUID fpGone = fingerprint(user, outside("ai-request-fingerprints"));
		UUID fpKept = fingerprint(user, inside("ai-request-fingerprints"));
		UUID reqGone = aiRequest(user, outside("ai-requests"));
		UUID reqKept = aiRequest(user, inside("ai-requests"));

		assertThat(run().purged()).containsEntry("ai-request-fingerprints", 1).containsEntry("ai-requests", 1);

		assertThat(exists("ai_request_fingerprints", "request_id", fpGone)).isFalse();
		assertThat(exists("ai_request_fingerprints", "request_id", fpKept)).isTrue();
		assertThat(exists("ai_requests", "id", reqGone)).isFalse();
		assertThat(exists("ai_requests", "id", reqKept)).isTrue();
	}

	UUID fingerprint(UUID user, Instant created) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO ai_request_fingerprints (request_id, user_id, fingerprint_ciphertext, key_version,"
				+ " created_at, expires_at) VALUES (?, ?, ?, 1, ?, ?)", id, user, new byte[] { 1 }, ts(created),
				ts(created.plus(Duration.ofHours(24))));
		return id;
	}

	UUID aiRequest(UUID user, Instant created) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO ai_requests (id, subject_type, user_id, feature_code, surface, created_at)"
				+ " VALUES (?, 'USER', ?, 'TRANSLATE', 'KEYBOARD', ?)", id, user, ts(created));
		return id;
	}

	@Test
	void replayCacheIsLeftToItsOwnSweeperAndTheJobDoesNotRepeatIt() {
		UUID user = activeUser();
		UUID expired = UUID.randomUUID();
		UUID live = UUID.randomUUID();
		Instant real = Instant.now();
		for (Object[] row : new Object[][] { { expired, real.minus(Duration.ofMinutes(20)), real.minus(Duration.ofMinutes(10)) },
				{ live, real, real.plus(Duration.ofMinutes(5)) } }) {
			jdbc.update("INSERT INTO ai_replay_cache (request_id, user_id, response_ciphertext, key_version, created_at,"
					+ " expires_at) VALUES (?, ?, ?, 1, ?, ?)", row[0], user, new byte[] { 1 }, ts((Instant) row[1]),
					ts((Instant) row[2]));
		}

		run();
		assertThat(exists("ai_replay_cache", "request_id", expired)).isTrue(); // not the job's business

		replayCache.sweep(); // B5.4's owner sweeper does it
		assertThat(exists("ai_replay_cache", "request_id", expired)).isFalse();
		assertThat(exists("ai_replay_cache", "request_id", live)).isTrue();
	}

	@Test
	void closedUsageReservationsAfterThePeriodAndOpenOnesNever() {
		UUID user = activeUser();
		UUID counter = UUID.randomUUID();
		jdbc.update("INSERT INTO usage_counters (id, subject_type, user_id, feature_code, period_start, period_end)"
				+ " VALUES (?, 'USER', ?, 'TRANSLATE', ?, ?)", counter, user, ts(NOW.minus(Duration.ofDays(500))),
				ts(NOW.plus(Duration.ofDays(500))));
		UUID gone = reservation(user, counter, "RELEASED", outside("usage-reservations"));
		UUID settledGone = reservation(user, counter, "SETTLED", outside("usage-reservations"));
		UUID inside = reservation(user, counter, "RELEASED", inside("usage-reservations"));
		UUID open = reservation(user, counter, "RESERVED", null); // created long ago, still open

		assertThat(run().purged()).containsEntry("usage-reservations", 2);

		assertThat(exists("usage_reservations", "id", gone)).isFalse();
		assertThat(exists("usage_reservations", "id", settledGone)).isFalse();
		assertThat(exists("usage_reservations", "id", inside)).isTrue();
		assertThat(exists("usage_reservations", "id", open)).isTrue();
	}

	UUID reservation(UUID user, UUID counter, String status, Instant closedAt) {
		UUID id = UUID.randomUUID();
		Instant created = (closedAt == null ? NOW.minus(Duration.ofDays(900)) : closedAt.minus(Duration.ofMinutes(1)));
		String sql = switch (status) {
			case "SETTLED" -> "INSERT INTO usage_reservations (id, user_id, feature_code, usage_counter_id, idempotency_key,"
					+ " metering_unit, reservation_status, estimated_units, allowance_units, credit_units,"
					+ " credit_cost_per_unit, credits_held, expires_at, created_at, closed_at, actual_units,"
					+ " settled_allowance_units, settled_credit_units, credits_charged, uncovered_units)"
					+ " VALUES (?, ?, 'TRANSLATE', ?, ?, 'REQUEST', 'SETTLED', 1, 1, 0, 0, 0, ?, ?, ?, 1, 1, 0, 0, 0)";
			case "RELEASED" -> "INSERT INTO usage_reservations (id, user_id, feature_code, usage_counter_id, idempotency_key,"
					+ " metering_unit, reservation_status, estimated_units, allowance_units, credit_units,"
					+ " credit_cost_per_unit, credits_held, expires_at, created_at, closed_at, release_reason)"
					+ " VALUES (?, ?, 'TRANSLATE', ?, ?, 'REQUEST', 'RELEASED', 1, 1, 0, 0, 0, ?, ?, ?, 'FAILED')";
			default -> "INSERT INTO usage_reservations (id, user_id, feature_code, usage_counter_id, idempotency_key,"
					+ " metering_unit, reservation_status, estimated_units, allowance_units, credit_units,"
					+ " credit_cost_per_unit, credits_held, expires_at, created_at, closed_at)"
					+ " VALUES (?, ?, 'TRANSLATE', ?, ?, 'REQUEST', 'RESERVED', 1, 1, 0, 0, 0, ?, ?, ?)";
		};
		jdbc.update(sql, id, user, counter, id.toString(), ts(created.plus(Duration.ofMinutes(5))), ts(created),
				closedAt == null ? null : ts(closedAt));
		return id;
	}

	// ------------------------------------------------------------------------------------------------ languages

	@Test
	void dictionaryAppliedChangesAndTombstonesWithTheResyncFloor() {
		UUID user = activeUser();
		UUID lang = jdbc.queryForObject("SELECT id FROM supported_languages WHERE language_code = 'am'", UUID.class);
		jdbc.update("INSERT INTO user_dictionary_sync_state (user_id, key_ciphertext, key_version, sync_counter)"
				+ " VALUES (?, ?, 1, 50)", user, new byte[] { 1 });
		UUID changeGone = UUID.randomUUID();
		UUID changeKept = UUID.randomUUID();
		jdbc.update("INSERT INTO user_dictionary_applied_changes (user_id, client_change_id, applied_at)"
				+ " VALUES (?, ?, ?), (?, ?, ?)", user, changeGone, ts(outside("dictionary-applied-changes")), user,
				changeKept, ts(inside("dictionary-applied-changes")));
		UUID tombOld1 = entry(user, lang, 7, outside("dictionary-tombstones"));
		UUID tombOld2 = entry(user, lang, 12, outside("dictionary-tombstones").minus(Duration.ofDays(30)));
		UUID tombInside = entry(user, lang, 30, inside("dictionary-tombstones"));
		UUID tombEdge = entry(user, lang, 31, atCutoff("dictionary-tombstones"));
		UUID live = entry(user, lang, 40, null);
		// another user without any tombstone keeps floor 0
		UUID other = activeUser();
		jdbc.update("INSERT INTO user_dictionary_sync_state (user_id, key_ciphertext, key_version, sync_counter)"
				+ " VALUES (?, ?, 1, 5)", other, new byte[] { 1 });

		RetentionJob.Result result = run();

		assertThat(result.purged()).containsEntry("dictionary-applied-changes", 1).containsEntry("dictionary-tombstones",
				2);
		assertThat(exists("user_dictionary_applied_changes", "client_change_id", changeGone)).isFalse();
		assertThat(exists("user_dictionary_applied_changes", "client_change_id", changeKept)).isTrue();
		assertThat(exists("user_dictionary_entries", "id", tombOld1)).isFalse();
		assertThat(exists("user_dictionary_entries", "id", tombOld2)).isFalse();
		for (UUID kept : List.of(tombInside, tombEdge, live)) {
			assertThat(exists("user_dictionary_entries", "id", kept)).isTrue();
		}
		// D31: the floor is the highest purged tombstone version; a repeat does not change it
		assertThat(jdbc.queryForObject("SELECT tombstone_floor FROM user_dictionary_sync_state WHERE user_id = ?",
				Long.class, user)).isEqualTo(12L);
		assertThat(jdbc.queryForObject("SELECT tombstone_floor FROM user_dictionary_sync_state WHERE user_id = ?",
				Long.class, other)).isZero();
		assertThat(run().total()).isZero();
		assertThat(jdbc.queryForObject("SELECT tombstone_floor FROM user_dictionary_sync_state WHERE user_id = ?",
				Long.class, user)).isEqualTo(12L);
	}

	UUID entry(UUID user, UUID lang, long version, Instant deletedAt) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO user_dictionary_entries (id, user_id, language_id, word_ciphertext, word_hmac,"
				+ " key_version, sync_version, deleted_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?)", id, user, lang,
				new byte[] { 1 }, hex64(), version, deletedAt == null ? null : ts(deletedAt));
		return id;
	}

	// ------------------------------------------------------------------------------------------------ administration

	@Test
	void auditEventsTwoYearsForSecurityAndSevenYearsForTheRest() {
		UUID securityGone = audit("language_resource", outside("audit-events-security"));
		UUID securityKept = audit("resource_signing_key", inside("audit-events-security"));
		// an unclassified or financial entity type is NOT shortened to two years
		UUID financialAtTwoYears = audit("payment_order", outside("audit-events-security"));
		UUID financialGone = audit("payment_order", outside("audit-events-financial"));
		UUID financialKept = audit("price", inside("audit-events-financial"));

		assertThat(run().purged()).containsEntry("audit-events-security", 1).containsEntry("audit-events-financial", 1);

		assertThat(exists("audit_events", "id", securityGone)).isFalse();
		assertThat(exists("audit_events", "id", financialGone)).isFalse();
		for (UUID kept : List.of(securityKept, financialAtTwoYears, financialKept)) {
			assertThat(exists("audit_events", "id", kept)).isTrue();
		}
		// the ledger stays append-only for everyone else
		assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE id = ?", securityKept))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
	}

	UUID audit(String entityType, Instant created) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO audit_events (id, action_code, entity_type, created_at) VALUES (?, 'X', ?, ?)", id,
				entityType, ts(created));
		return id;
	}

	// ------------------------------------------------------------------------------------------------ billing, voice

	@Test
	void webhookPayloadsVoiceAndRealtimeMetadata() {
		UUID provider = jdbc.queryForObject("SELECT id FROM payment_providers LIMIT 1", UUID.class);
		UUID hookGone = webhook(provider, outside("payment-webhook-events"));
		UUID hookKept = webhook(provider, inside("payment-webhook-events"));
		UUID voiceGone = voice(outside("voice-jobs"), false, false);
		UUID voiceKept = voice(inside("voice-jobs"), false, false);
		UUID voicePendingAudio = voice(outside("voice-jobs"), true, false); // its object still exists: never purged
		UUID voiceAudioDeleted = voice(outside("voice-jobs"), true, true);
		UUID rtGone = realtime(outside("realtime-sessions"));
		UUID rtKept = realtime(inside("realtime-sessions"));

		RetentionJob.Result result = run();

		assertThat(result.purged()).containsEntry("payment-webhook-events", 1).containsEntry("voice-jobs", 2)
				.containsEntry("realtime-sessions", 1);
		assertThat(exists("payment_webhook_events", "id", hookGone)).isFalse();
		assertThat(exists("payment_webhook_events", "id", hookKept)).isTrue();
		assertThat(exists("voice_jobs", "id", voiceGone)).isFalse();
		assertThat(exists("voice_jobs", "id", voiceAudioDeleted)).isFalse();
		assertThat(exists("voice_jobs", "id", voiceKept)).isTrue();
		assertThat(exists("voice_jobs", "id", voicePendingAudio)).isTrue();
		assertThat(exists("realtime_sessions", "id", rtGone)).isFalse();
		assertThat(exists("realtime_sessions", "id", rtKept)).isTrue();
	}

	@Test
	void referencedWebhookPayloadsAreClearedNotDeleted() {
		// the delete rule is pushed out of the way, so only the anonymise rule sees the row (a ledger row that
		// references an event keeps it, the payload goes)
		RetentionPolicy p = new RetentionPolicy(participants,
				Map.of("payment-webhook-events", Duration.ofDays(36500)));
		UUID provider = jdbc.queryForObject("SELECT id FROM payment_providers LIMIT 1", UUID.class);
		UUID old = webhook(provider, outside("payment-webhook-payloads-referenced"));
		UUID young = webhook(provider, inside("payment-webhook-payloads-referenced"));

		RetentionJob.Result result = job(p, 100, 10).run();

		assertThat(result.purged()).containsEntry("payment-webhook-payloads-referenced", 1);
		assertThat(jdbc.queryForObject("SELECT redacted_payload::text FROM payment_webhook_events WHERE id = ?",
				String.class, old)).isEqualTo("{}");
		assertThat(jdbc.queryForObject("SELECT redacted_payload ->> 'k' FROM payment_webhook_events WHERE id = ?",
				String.class, young)).isEqualTo("v");
		assertThat(job(p, 100, 10).run().total()).isZero(); // idempotent: a cleared payload is not touched again
	}

	UUID webhook(UUID provider, Instant received) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO payment_webhook_events (id, provider_id, external_event_id, event_type, payload_sha256,"
				+ " redacted_payload, received_at) VALUES (?, ?, ?, 'x', ?, '{\"k\":\"v\"}'::jsonb, ?)", id, provider,
				id.toString(), hex64(), ts(received));
		return id;
	}

	UUID voice(Instant created, boolean audioObject, boolean audioDeleted) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO voice_jobs (id, feature_code, audio_object_key, audio_retention_until,"
				+ " audio_deleted_at, created_at) VALUES (?, 'SPEECH_TO_TEXT', ?, ?, ?, ?)", id,
				audioObject ? "audio/" + id : null, audioObject ? ts(created.plus(Duration.ofDays(1))) : null,
				audioDeleted ? ts(created.plus(Duration.ofDays(1))) : null, ts(created));
		return id;
	}

	UUID realtime(Instant created) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO realtime_sessions (id, feature_code, language_a_id, language_b_id, ticket_hash,"
				+ " ticket_expires_at, max_duration_seconds, created_at) VALUES (?, 'VOICE_TO_VOICE',"
				+ " (SELECT id FROM supported_languages WHERE language_code = 'am'),"
				+ " (SELECT id FROM supported_languages WHERE language_code = 'en'), ?, ?, 60, ?)", id, hex64(),
				ts(created.plus(Duration.ofMinutes(5))), ts(created));
		return id;
	}

	// ------------------------------------------------------------------------------------------------ financial, safety

	@Test
	void financialAndLedgerTablesAreNeverTouchedWhateverTheirAge() {
		UUID user = activeUser();
		Instant ancient = NOW.minus(Duration.ofDays(20 * 365));
		UUID usageEvent = UUID.randomUUID();
		jdbc.update("INSERT INTO usage_events (id, event_type, usage_counter_id, subject_type, subject_id, feature_code,"
				+ " units, created_at) VALUES (?, 'ADJUST', ?, 'USER', ?, 'TRANSLATE', 5, ?)", usageEvent,
				UUID.randomUUID(), user, ts(ancient));
		UUID creditAccount = UUID.randomUUID();
		jdbc.update("INSERT INTO ai_credit_accounts (id, user_id, created_at) VALUES (?, ?, ?)", creditAccount, user,
				ts(ancient));
		int ledgerBefore = count("SELECT count(*) FROM usage_events");

		run();
		run();

		assertThat(exists("usage_events", "id", usageEvent)).isTrue();
		assertThat(exists("ai_credit_accounts", "id", creditAccount)).isTrue();
		assertThat(count("SELECT count(*) FROM usage_events")).isEqualTo(ledgerBefore);
		// no executable rule belongs to a kept class, and the kept classes are documented with their reason
		assertThat(policy.rules()).filteredOn(r -> r.action() == RetentionAction.KEEP)
				.extracting(RetentionRule::code).contains("usage-events", "ai-credit-ledger", "financial-records",
						"usage-counters");
	}

	@Test
	void idempotentAndResumableInBoundedBatches() {
		for (int i = 0; i < 25; i++) {
			jdbc.update("INSERT INTO processed_events (consumer, event_id, processed_at) VALUES ('resume', ?, ?)",
					UUID.randomUUID(), ts(outside("processed-events")));
		}
		RetentionPolicy p = policy;
		// 10 per batch, one batch per run: three runs to finish, never more than 10 per run for this rule
		int first = job(p, 10, 1).run().purged().get("processed-events");
		assertThat(first).isEqualTo(10);
		assertThat(count("SELECT count(*) FROM processed_events WHERE consumer = 'resume'")).isEqualTo(15);
		int second = job(p, 10, 1).run().purged().get("processed-events");
		assertThat(second).isEqualTo(10);
		int third = job(p, 10, 1).run().purged().get("processed-events");
		assertThat(third).isEqualTo(5);
		assertThat(count("SELECT count(*) FROM processed_events WHERE consumer = 'resume'")).isZero();
		assertThat(job(p, 10, 1).run().total()).isZero(); // idempotent
		// one run with a larger batch cap does it all
		for (int i = 0; i < 25; i++) {
			jdbc.update("INSERT INTO processed_events (consumer, event_id, processed_at) VALUES ('resume', ?, ?)",
					UUID.randomUUID(), ts(outside("processed-events")));
		}
		assertThat(job(p, 10, 100).run().purged()).containsEntry("processed-events", 25);
	}

	@Test
	void twoInstancesNeverDeleteTheSameRowTwice() throws Exception {
		int rows = 300;
		for (int i = 0; i < rows; i++) {
			jdbc.update("INSERT INTO otp_challenges (phone_hash, code_hash, purpose, phone_ciphertext, created_at,"
					+ " expires_at) VALUES (?, 'x', 'SIGN_IN', ?, ?, ?)", hex64(), new byte[32],
					ts(outside("otp-challenges").minusSeconds(300)), ts(outside("otp-challenges")));
		}
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch go = new CountDownLatch(1);
			Callable<RetentionJob.Result> instance = () -> {
				go.await();
				return job(policy, 7, 1000).run();
			};
			Future<RetentionJob.Result> a = pool.submit(instance);
			Future<RetentionJob.Result> b = pool.submit(instance);
			go.countDown();
			RetentionJob.Result ra = a.get(60, TimeUnit.SECONDS);
			RetentionJob.Result rb = b.get(60, TimeUnit.SECONDS);
			assertThat(ra.failed()).isEmpty();
			assertThat(rb.failed()).isEmpty();
			// every row deleted exactly once: the counts add up to the rows and not a row more
			assertThat(ra.purged().get("otp-challenges") + rb.purged().get("otp-challenges")).isEqualTo(rows);
			assertThat(count("SELECT count(*) FROM otp_challenges WHERE expires_at < ?", ts(atCutoff("otp-challenges"))))
					.isZero();
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void rowsAnotherTransactionHoldAreSkippedNotWaitedFor() throws Exception {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			UUID id = UUID.randomUUID();
			ids.add(id);
			jdbc.update("INSERT INTO processed_events (consumer, event_id, processed_at) VALUES ('skip', ?, ?)", id,
					ts(outside("processed-events")));
		}
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
				client.sql("SELECT event_id FROM processed_events WHERE consumer = 'skip' ORDER BY event_id LIMIT 4"
						+ " FOR UPDATE").query(UUID.class).list();
				locked.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
			assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
			int deleted = tx.execute(s -> RetentionSupport.deleteBatchByCtid(client, "processed_events",
					"consumer = 'skip' AND processed_at < :cutoff", NOW, atCutoff("processed-events"), 100));
			assertThat(deleted).isEqualTo(6); // the four locked rows were skipped, nothing blocked
			release.countDown();
			holder.get(30, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(count("SELECT count(*) FROM processed_events WHERE consumer = 'skip'")).isEqualTo(4);
		run();
		assertThat(count("SELECT count(*) FROM processed_events WHERE consumer = 'skip'")).isZero();
	}

	@Test
	void aRuleWhoseLockIsHeldElsewhereIsSkippedAndLeftIntact() throws Exception {
		UUID event = UUID.randomUUID();
		jdbc.update("INSERT INTO processed_events (consumer, event_id, processed_at) VALUES ('lock', ?, ?)", event,
				ts(outside("processed-events")));
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
				client.sql("SELECT pg_advisory_xact_lock(hashtextextended('qal.retention.processed-events', 0))")
						.query().singleRow();
				locked.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
			assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
			RetentionJob.Result result = job().run();
			assertThat(result.skipped()).containsExactly("processed-events");
			assertThat(exists("processed_events", "event_id", event)).isTrue();
			release.countDown();
			holder.get(30, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}
		run();
		assertThat(exists("processed_events", "event_id", event)).isFalse();
	}

	@Test
	void aFailingRuleIsCountedAndTheOtherRulesStillRun() {
		RetentionParticipant failing = new RetentionParticipant() {
			@Override
			public int order() {
				return 1;
			}

			@Override
			public List<RetentionRule> rules() {
				return List.of(new RetentionRule("always-fails", "test", RetentionAction.DELETE, Duration.ZERO,
						"test", "a rule that throws"));
			}

			@Override
			public int purge(RetentionRule rule, Instant now, Instant cutoff, int limit) {
				throw new IllegalStateException("secret-value-in-a-database-message");
			}
		};
		List<RetentionParticipant> all = new ArrayList<>(participants);
		all.add(failing);
		UUID event = UUID.randomUUID();
		jdbc.update("INSERT INTO processed_events (consumer, event_id, processed_at) VALUES ('fail', ?, ?)", event,
				ts(outside("processed-events")));

		try (CapturedLogs logs = new CapturedLogs()) {
			RetentionJob.Result result = job(new RetentionPolicy(all, Map.of()), 100, 10).run();

			assertThat(result.failed()).containsExactly("always-fails");
			assertThat(exists("processed_events", "event_id", event)).isFalse(); // ran after the failure
			assertThat(meters.get("qal.retention.failures").tag("rule", "always-fails").counter().count())
					.isEqualTo(1.0);
			assertThat(logs.texts()).anyMatch(t -> t.contains("always-fails"));
			assertThat(logs.texts()).noneMatch(t -> t.contains("secret-value"));
		}
	}

	@Test
	void metricsCountRowsPerRuleAndLogsCarryNoContent() {
		String marker = hex64();
		UUID user = activeUser();
		jdbc.update("INSERT INTO otp_challenges (phone_hash, code_hash, purpose, phone_ciphertext, created_at, expires_at)"
				+ " VALUES (?, 'x', 'SIGN_IN', ?, ?, ?)", marker, new byte[32],
				ts(outside("otp-challenges").minusSeconds(300)), ts(outside("otp-challenges")));
		jdbc.update("INSERT INTO ai_requests (id, subject_type, user_id, feature_code, surface, created_at,"
				+ " idempotency_key) VALUES (?, 'USER', ?, 'TRANSLATE', 'KEYBOARD', ?, ?)", UUID.randomUUID(), user,
				ts(outside("ai-requests")), "idem-" + marker);
		SimpleMeterRegistry fresh = new SimpleMeterRegistry();
		RetentionJob counted = new RetentionJob(client, new TransactionTemplate(transactionManager), policy,
				new RetentionProperties(true, "0 0 3 * * *", 100, 10, Map.of()), CLOCK, fresh);

		try (CapturedLogs logs = new CapturedLogs()) {
			RetentionJob.Result result = counted.run();
			assertThat(result.failed()).isEmpty();
			assertThat(fresh.get("qal.retention.purged").tag("rule", "otp-challenges").tag("action", "DELETE").counter()
					.count()).isEqualTo(1.0);
			assertThat(fresh.get("qal.retention.purged").tag("rule", "ai-requests").counter().count()).isEqualTo(1.0);
			assertThat(fresh.get("qal.retention.run").timer().count()).isEqualTo(1);
			assertThat(logs.texts()).anyMatch(t -> t.contains("otp-challenges"));
			assertThat(logs.texts()).noneMatch(t -> t.contains(marker));
		}
	}
}
