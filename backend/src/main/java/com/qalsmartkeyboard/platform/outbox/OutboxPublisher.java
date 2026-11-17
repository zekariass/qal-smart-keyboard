package com.qalsmartkeyboard.platform.outbox;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an outbox event in the caller's transaction, so the event exists if and only if the
 * domain change commits.
 *
 * <p><b>Privacy rule:</b> the payload carries ids, enum values, counters and timestamps only. Never put
 * keystrokes, drafts, transcripts, prompts, AI output, names, phone numbers or any other user text in
 * a payload or in {@code aggregateId}; consumers must load what they need from the owning module.
 */
@Component
public class OutboxPublisher {

    static final int DEFAULT_MAX_ATTEMPTS = 10;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    OutboxPublisher(JdbcTemplate jdbc, JsonMapper json, ObjectProvider<Clock> clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    /**
     * @param aggregateType owning aggregate, e.g. {@code "subscription"} (max 80 chars)
     * @param aggregateId   id of the aggregate (max 100 chars), an identifier and never user text
     * @param eventType     routing key handlers subscribe to, e.g. {@code "subscription.activated"} (max 120)
     * @param payload       ids and metadata only; serialised as JSON
     * @return the generated UUIDv7 event id
     * @throws IllegalStateException if called outside an active transaction
     */
    public UUID publish(String aggregateType, String aggregateId, String eventType, Map<String, ?> payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "OutboxPublisher.publish requires an active transaction (write the event with the domain change)");
        }
        require(aggregateType, 80, "aggregateType");
        require(aggregateId, 100, "aggregateId");
        require(eventType, 120, "eventType");
        String body;
        try {
            body = json.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("outbox payload is not serialisable");
        }
        UUID id = Uuid7.generate(clock);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload,
                                           event_status, attempts, max_attempts, available_at, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING', 0, ?, ?, ?)
                """, id, aggregateType, aggregateId, eventType, body, DEFAULT_MAX_ATTEMPTS, now, now);
        return id;
    }

    private static void require(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be 1-" + max + " characters");
        }
    }
}
