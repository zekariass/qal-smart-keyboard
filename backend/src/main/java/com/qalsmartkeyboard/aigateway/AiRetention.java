package com.qalsmartkeyboard.aigateway;

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
 * Retention (B6.3, ADR 0020) of the AI tables.
 *
 * <ul>
 * <li>{@code ai_replay_cache}: <b>deleted by its owner</b> ({@code EncryptedReplayCache.sweep}, B5.4): at most 10
 * minutes (SD 11.6), enforced by a CHECK on the table, UNLOGGED so it never reaches backups. Listed for completeness; the
 * job does not repeat the sweep.</li>
 * <li>{@code ai_request_fingerprints}: 24 hours from creation (SD 8.3 "keys are stored with a hash of the request body
 * for 24 hours"). The sweeper above already deletes them at {@code expires_at}; this rule is the backstop for a
 * deployment that runs with the sweeper switched off, and it keeps the period in one place for the operator.</li>
 * <li>{@code ai_requests}: <b>13 months</b> (SD 19.2 "13 months, then aggregated"). The SD's "aggregated" has no target
 * yet (no rollup table exists), so the rows are deleted; cost and usage reporting must roll them up before then (open
 * item of ADR 0020). Metadata only, never an FK target.</li>
 * </ul>
 */
@Component
class AiRetention implements RetentionParticipant {

	static final String REPLAY_CACHE = "ai-replay-cache";
	static final String FINGERPRINTS = "ai-request-fingerprints";
	static final String REQUESTS = "ai-requests";

	private final JdbcClient jdbc;

	AiRetention(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int order() {
		return 300;
	}

	@Override
	public List<RetentionRule> rules() {
		return List.of(
				new RetentionRule(REPLAY_CACHE, "aigateway", RetentionAction.DELETE_BY_OWNER, Duration.ofMinutes(10),
						"SD 11.6", "ai_replay_cache: encrypted responses, swept by EncryptedReplayCache (B5.4)"),
				new RetentionRule(FINGERPRINTS, "aigateway", RetentionAction.DELETE, Duration.ofHours(24),
						"SD 8.3, ADR 0017", "ai_request_fingerprints, counted from created_at (backstop to the B5.4 sweeper)"),
				new RetentionRule(REQUESTS, "aigateway", RetentionAction.DELETE, Duration.ofDays(396),
						"SD 19.2 (13 months, then aggregated)", "ai_requests metadata, counted from created_at"));
	}

	@Override
	public int purge(RetentionRule rule, Instant now, Instant cutoff, int limit) {
		return switch (rule.code()) {
			case FINGERPRINTS -> RetentionSupport.deleteBatch(jdbc, "ai_request_fingerprints", "request_id",
					"created_at < :cutoff", now, cutoff, limit);
			case REQUESTS -> RetentionSupport.deleteBatch(jdbc, "ai_requests", "id", "created_at < :cutoff", now, cutoff,
					limit);
			default -> throw new IllegalArgumentException("Not an executable AI rule: " + rule.code());
		};
	}
}
