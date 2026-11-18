package com.qalsmartkeyboard.platform;

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
 * Retention (B6.3, ADR 0020) of the platform tables: the outbox and the consumer dedupe table. Neither holds personal
 * data (ids and metadata only, V1/V2), so the periods are about operations, not privacy.
 *
 * <ul>
 * <li>{@code outbox_events} PUBLISHED: 7 days after publication (no document gives a period; the dispatcher never
 * reads a published row again).</li>
 * <li>{@code outbox_events} DEAD: 30 days after creation, long enough to investigate and requeue (SD 19.2 "operational
 * logs 30 days" is the nearest rule).</li>
 * <li>{@code processed_events}: 35 days, longer than the DEAD period, so an event requeued by an operator is still
 * deduplicated by its consumers.</li>
 * <li>{@code scheduled_tasks} (db-scheduler) holds one row per scheduled task and no history, so nothing accumulates.</li>
 * <li>Operational logs are not in the database: their 30 days (SD 19.2) is a log-store setting.</li>
 * </ul>
 */
@Component
class PlatformRetention implements RetentionParticipant {

	static final String OUTBOX_PUBLISHED = "outbox-published";
	static final String OUTBOX_DEAD = "outbox-dead";
	static final String PROCESSED_EVENTS = "processed-events";
	static final String OPERATIONAL_LOGS = "operational-logs";

	private final JdbcClient jdbc;

	PlatformRetention(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int order() {
		return 50;
	}

	@Override
	public List<RetentionRule> rules() {
		return List.of(
				new RetentionRule(OUTBOX_PUBLISHED, "platform", RetentionAction.DELETE, Duration.ofDays(7), "UNDEFINED",
						"outbox_events with status PUBLISHED, counted from published_at"),
				new RetentionRule(OUTBOX_DEAD, "platform", RetentionAction.DELETE, Duration.ofDays(30),
						"SD 19.2 (operational logs 30 days, by analogy)",
						"outbox_events with status DEAD, counted from created_at"),
				new RetentionRule(PROCESSED_EVENTS, "platform", RetentionAction.DELETE, Duration.ofDays(35),
						"UNDEFINED", "processed_events (consumer dedupe), counted from processed_at"),
				new RetentionRule(OPERATIONAL_LOGS, "platform", RetentionAction.DELETE_BY_OWNER, Duration.ofDays(30),
						"SD 19.2", "Redacted operational logs in the log store: a log-store retention setting, not a table"));
	}

	@Override
	public int purge(RetentionRule rule, Instant now, Instant cutoff, int limit) {
		return switch (rule.code()) {
			case OUTBOX_PUBLISHED -> RetentionSupport.deleteBatch(jdbc, "outbox_events", "id",
					"event_status = 'PUBLISHED' AND published_at < :cutoff", now, cutoff, limit);
			case OUTBOX_DEAD -> RetentionSupport.deleteBatch(jdbc, "outbox_events", "id",
					"event_status = 'DEAD' AND created_at < :cutoff", now, cutoff, limit);
			case PROCESSED_EVENTS -> RetentionSupport.deleteBatchByCtid(jdbc, "processed_events",
					"processed_at < :cutoff", now, cutoff, limit);
			default -> throw new IllegalArgumentException("Not an executable platform rule: " + rule.code());
		};
	}
}
