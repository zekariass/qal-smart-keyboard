package com.qalsmartkeyboard.administration;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.platform.CorrelationId;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the append-only {@code audit_events} trail (V1, SD 18/21, Review D25) for admin actions.
 *
 * <p>Rows carry who did what to which entity, the outcome, the request's correlation id and a small metadata map of ids,
 * codes and enum values: never user text, pack content, tokens or secrets. {@link #record} joins the caller's transaction
 * (the row commits with the change it describes); {@link #recordIndependently} commits on its own, for refusals whose
 * request transaction rolls back.
 */
@Component
public class AuditTrail {

	public enum Outcome {
		SUCCESS, FAILURE, DENIED
	}

	/**
	 * One audit row.
	 *
	 * @param actorUserId      the admin ({@code users.id}); {@code actor_type} is {@code ADMIN}
	 * @param approvedByUserId the four-eyes approver, if the action needed one (must differ from the actor)
	 * @param actionCode       e.g. {@code LANGUAGE_RESOURCE_PUBLISH} (max 100)
	 * @param entityType       e.g. {@code language_resource} (max 80)
	 * @param entityId         the entity's id (max 100), or null when it is not known (a rejected upload)
	 * @param metadata         ids, codes and enum values only
	 */
	public record Entry(UUID actorUserId, UUID approvedByUserId, String actionCode, String entityType, String entityId,
			Outcome outcome, Map<String, ?> metadata) {

		public Entry {
			Objects.requireNonNull(actionCode, "actionCode");
			Objects.requireNonNull(entityType, "entityType");
			Objects.requireNonNull(outcome, "outcome");
			metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
		}
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcTemplate jdbc;
	private final TransactionTemplate independent;
	private final JsonMapper json;
	private final Clock clock;

	AuditTrail(JdbcTemplate jdbc, PlatformTransactionManager transactions, JsonMapper json, ObjectProvider<Clock> clock) {
		this.jdbc = jdbc;
		this.independent = new TransactionTemplate(transactions);
		this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.json = json;
		this.clock = clock.getIfAvailable(Clock::systemUTC);
	}

	/** Writes the row in the current transaction (if any). */
	public UUID record(Entry entry) {
		require(entry.actionCode(), 100, "actionCode");
		require(entry.entityType(), 80, "entityType");
		if (entry.entityId() != null && entry.entityId().length() > 100) {
			throw new IllegalArgumentException("entityId must be at most 100 characters");
		}
		String metadata;
		try {
			metadata = json.writeValueAsString(entry.metadata());
		}
		catch (JacksonException e) {
			throw new IllegalArgumentException("audit metadata is not serialisable");
		}
		UUID id = uuid7();
		String correlation = CorrelationId.current().map(c -> c.length() > 100 ? c.substring(0, 100) : c).orElse(null);
		jdbc.update("""
				INSERT INTO audit_events (id, actor_type, actor_user_id, approved_by_user_id, action_code, entity_type,
				    entity_id, outcome, correlation_id, metadata, created_at)
				VALUES (?, 'ADMIN', ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""", id, entry.actorUserId(), entry.approvedByUserId(),
				entry.actionCode(), entry.entityType(), entry.entityId(), entry.outcome().name(), correlation, metadata,
				OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
		return id;
	}

	/** Writes the row in its own transaction, so it survives the rollback of the request that was refused. */
	public UUID recordIndependently(Entry entry) {
		return independent.execute(status -> record(entry));
	}

	/**
	 * The most recent successful {@code actionCode} on the entity by someone other than {@code notBy}: the four-eyes
	 * approval of an action (Review D25). Approvals live in the audit trail itself, which is append-only.
	 */
	public Optional<UUID> approver(String actionCode, String entityType, String entityId, UUID notBy) {
		return jdbc.query("""
				SELECT actor_user_id FROM audit_events
				WHERE action_code = ? AND entity_type = ? AND entity_id = ? AND outcome = 'SUCCESS'
				  AND actor_user_id IS NOT NULL AND actor_user_id <> ?
				ORDER BY created_at DESC, id DESC LIMIT 1""", (rs, i) -> rs.getObject(1, UUID.class), actionCode, entityType,
				entityId, notBy).stream().findFirst();
	}

	/** RFC 9562 UUIDv7 (high-volume tables use UUIDv7 keys, SD 20.1). */
	private UUID uuid7() {
		long ms = clock.millis();
		long msb = (ms << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
		long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
		return new UUID(msb, lsb);
	}

	private static void require(String value, int max, String name) {
		if (value == null || value.isBlank() || value.length() > max) {
			throw new IllegalArgumentException(name + " must be 1-" + max + " characters");
		}
	}
}
