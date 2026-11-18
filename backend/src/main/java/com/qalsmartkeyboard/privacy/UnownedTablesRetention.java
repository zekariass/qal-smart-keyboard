package com.qalsmartkeyboard.privacy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.qalsmartkeyboard.platform.retention.RetentionAction;
import com.qalsmartkeyboard.platform.retention.RetentionParticipant;
import com.qalsmartkeyboard.platform.retention.RetentionRule;
import com.qalsmartkeyboard.platform.retention.RetentionSupport;

/**
 * Retention (B6.3, ADR 0020) for tables whose owning module has no code yet (billing, speech and realtime), like
 * {@link UnownedTablesDeletion}: <b>when a module starts writing one of these tables it takes the rule over into its own
 * participant and removes it here</b> (rule codes stay the same, so configuration keeps working).
 *
 * <ul>
 * <li>{@code payment_webhook_events} (SD 19.2 "webhook payloads: 18 months", redacted already): deleted after 18 months
 * unless a ledger row points at it (RESTRICT); a referenced event keeps its row, id and payload hash and loses the
 * redacted payload (ANONYMISE).</li>
 * <li>{@code voice_jobs} and {@code realtime_sessions}: metadata only (no audio, no transcript). The SD names no period;
 * they follow {@code ai_requests} (13 months), which they summarise. A voice job whose audio object has not been deleted
 * ({@code audio_object_key} set, {@code audio_deleted_at} null) is never purged: a row delete would orphan the file.</li>
 * <li>Audio objects: deleted after processing, with a 24 h bucket lifecycle as the backstop (SD 19.2); the lifecycle is
 * configured on the bucket, not here.</li>
 * <li>Financial records (orders, attempts, transactions, receipts, mandates, subscriptions, store purchases, their event
 * ledgers): <b>kept</b>, "as required by tax law (assume 10 years until confirmed)" (SD 19.2). No financial writer exists
 * yet, so nothing can be that old; the purge of rows past the confirmed period is added with the billing module
 * (M4), together with the pseudonymisation rules of ADR 0015.</li>
 * </ul>
 */
@Component
class UnownedTablesRetention implements RetentionParticipant {

	static final String WEBHOOK_EVENTS = "payment-webhook-events";
	static final String WEBHOOK_PAYLOADS = "payment-webhook-payloads-referenced";
	static final String VOICE_JOBS = "voice-jobs";
	static final String REALTIME_SESSIONS = "realtime-sessions";
	static final String AUDIO_OBJECTS = "audio-objects";
	static final String FINANCIAL_RECORDS = "financial-records";

	private static final Duration MONTHS_13 = Duration.ofDays(396);
	private static final Duration MONTHS_18 = Duration.ofDays(548);

	private final JdbcClient jdbc;

	UnownedTablesRetention(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int order() {
		return 800;
	}

	@Override
	public List<RetentionRule> rules() {
		return List.of(
				new RetentionRule(WEBHOOK_EVENTS, "billing", RetentionAction.DELETE, MONTHS_18, "SD 19.2",
						"payment_webhook_events not referenced by a ledger row, counted from received_at"),
				new RetentionRule(WEBHOOK_PAYLOADS, "billing", RetentionAction.ANONYMISE, MONTHS_18, "SD 19.2",
						"redacted_payload of payment_webhook_events a ledger row references: cleared, row kept"),
				new RetentionRule(VOICE_JOBS, "speech", RetentionAction.DELETE, MONTHS_13, "UNDEFINED",
						"voice_jobs metadata without a pending audio object, counted from created_at"),
				new RetentionRule(REALTIME_SESSIONS, "realtime", RetentionAction.DELETE, MONTHS_13, "UNDEFINED",
						"realtime_sessions metadata, counted from created_at"),
				new RetentionRule(AUDIO_OBJECTS, "speech", RetentionAction.DELETE_BY_OWNER, Duration.ofHours(24),
						"SD 19.2, SD 8.1", "Temporary audio objects: deleted after processing; 24 h bucket lifecycle backstop"),
				new RetentionRule(FINANCIAL_RECORDS, "billing", RetentionAction.KEEP, Duration.ofDays(3650),
						"SD 19.2 (assumed 10 years until confirmed)",
						"Orders, attempts, transactions, receipts, mandates, subscriptions, store purchases and their event ledgers"));
	}

	@Override
	public int purge(RetentionRule rule, Instant now, Instant cutoff, int limit) {
		return switch (rule.code()) {
			case WEBHOOK_EVENTS -> RetentionSupport.deleteBatch(jdbc, "payment_webhook_events", "id", """
					received_at < :cutoff
					AND NOT EXISTS (SELECT 1 FROM store_purchase_events e WHERE e.webhook_event_id = payment_webhook_events.id)
					""", now, cutoff, limit);
			case WEBHOOK_PAYLOADS -> jdbc.sql("""
					UPDATE payment_webhook_events SET redacted_payload = '{}'::jsonb
					WHERE id IN (SELECT w.id FROM payment_webhook_events w
					             WHERE w.received_at < :cutoff AND w.redacted_payload <> '{}'::jsonb
					             LIMIT :limit FOR UPDATE SKIP LOCKED)
					""").param("cutoff", Timestamp.from(cutoff)).param("limit", limit).update();
			case VOICE_JOBS -> RetentionSupport.deleteBatch(jdbc, "voice_jobs", "id",
					"created_at < :cutoff AND (audio_object_key IS NULL OR audio_deleted_at IS NOT NULL)", now, cutoff,
					limit);
			case REALTIME_SESSIONS -> RetentionSupport.deleteBatch(jdbc, "realtime_sessions", "id",
					"created_at < :cutoff", now, cutoff, limit);
			default -> throw new IllegalArgumentException("Not an executable rule of this participant: " + rule.code());
		};
	}
}
