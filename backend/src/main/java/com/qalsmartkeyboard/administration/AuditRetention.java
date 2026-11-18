package com.qalsmartkeyboard.administration;

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
 * Retention (B6.3, ADR 0020) of {@code audit_events} (SD 19.2: "2 years (security), 7 years (financial admin
 * actions)"). The table has no category column, so the split is by {@code entity_type}:
 * <ul>
 * <li>the entity types in {@link #SECURITY_ENTITY_TYPES} (admin actions on language packs, datasets and signing keys) are
 * kept <b>2 years</b>;</li>
 * <li>every other entity type is kept <b>7 years</b>. The default is the longer period on purpose: an audit row of a type
 * nobody classified yet (a future billing or pricing action) must not be shortened by accident. A module that adds a
 * non-financial audit entity type adds it to the list in the same change.</li>
 * </ul>
 * The table is append-only; the batch runs under {@code app.retention_purge}. {@code actor_user_id} is a logical
 * reference, so a deleted account's rows stay (pseudonymised, ADR 0015) until their period ends.
 */
@Component
class AuditRetention implements RetentionParticipant {

	static final String SECURITY = "audit-events-security";
	static final String FINANCIAL = "audit-events-financial";

	/** Entity types written by the code today that are not financial (see {@code PackPublishingService}, {@code SigningKeyAdminService}). */
	static final String[] SECURITY_ENTITY_TYPES = { "language_resource", "language_dataset", "resource_signing_key" };

	private final JdbcClient jdbc;

	AuditRetention(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int order() {
		return 600;
	}

	@Override
	public List<RetentionRule> rules() {
		return List.of(
				new RetentionRule(SECURITY, "administration", RetentionAction.DELETE, Duration.ofDays(730), "SD 19.2",
						"audit_events of the security entity types (language packs, datasets, signing keys), counted from created_at"),
				new RetentionRule(FINANCIAL, "administration", RetentionAction.DELETE, Duration.ofDays(2557), "SD 19.2",
						"audit_events of every other entity type (financial admin actions and anything unclassified), counted from created_at"));
	}

	@Override
	public int purge(RetentionRule rule, Instant now, Instant cutoff, int limit) {
		boolean security = SECURITY.equals(rule.code());
		if (!security && !FINANCIAL.equals(rule.code())) {
			throw new IllegalArgumentException("Not an executable audit rule: " + rule.code());
		}
		RetentionSupport.allowAppendOnlyPurge(jdbc);
		return jdbc.sql("DELETE FROM audit_events WHERE id IN (SELECT id FROM audit_events WHERE created_at < :cutoff AND "
				+ (security ? "" : "NOT ") + "(entity_type IN (:types)) LIMIT :limit FOR UPDATE SKIP LOCKED)")
				.param("cutoff", Timestamp.from(cutoff)).param("types", List.of(SECURITY_ENTITY_TYPES)).param("limit", limit)
				.update();
	}
}
