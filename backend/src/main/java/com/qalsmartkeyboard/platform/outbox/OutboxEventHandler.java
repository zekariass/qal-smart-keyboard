package com.qalsmartkeyboard.platform.outbox;

/**
 * Consumes outbox events of one type. Declare an implementation as a Spring bean to register it.
 *
 * <p>Delivery is at-least-once, but the dispatcher makes every handler idempotent per event id:
 * it inserts {@code (consumerName, event id)} into {@code processed_events} and then calls
 * {@link #handle} inside one database transaction. A redelivered event whose ledger row already
 * exists is skipped; a handler exception rolls back the ledger row together with the handler's own
 * writes. Consequently:
 * <ul>
 *   <li>Writes made through the Spring-managed transaction (JdbcTemplate/JPA of the primary
 *       DataSource) are applied exactly once per event id.</li>
 *   <li>External side effects (HTTP calls, push, mail) can still repeat if the process dies after the
 *       call but before commit; pass {@link OutboxEvent#id()} as the provider's idempotency key.</li>
 *   <li>The consumer name is part of the dedupe key and must stay stable once deployed.</li>
 *   <li>Handlers must finish well inside {@code qal.outbox.lease}; a longer run lets another
 *       dispatcher reclaim the event (still safe because of the ledger, but wasteful).</li>
 *   <li>Exceptions drive retry with backoff. Do not put user text in exception messages: only the
 *       exception class name is persisted, never the message.</li>
 * </ul>
 */
public interface OutboxEventHandler {

    /** Stable, unique consumer name (max 120 chars), e.g. {@code "usage.settle-on-ai-completed"}. */
    String consumerName();

    /** The {@code eventType} this handler subscribes to (exact match). */
    String eventType();

    void handle(OutboxEvent event) throws Exception;
}
