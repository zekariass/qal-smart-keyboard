package com.qalsmartkeyboard.platform.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * An outbox event as delivered to handlers. {@code payloadJson} holds ids and metadata only.
 *
 * @param attempt 1-based delivery attempt number (a crash or failure increments it)
 */
public record OutboxEvent(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        int attempt,
        Instant createdAt) {
}
