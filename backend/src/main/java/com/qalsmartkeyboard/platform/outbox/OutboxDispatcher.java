package com.qalsmartkeyboard.platform.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims due outbox events and delivers them to {@link OutboxEventHandler}s.
 *
 * <p>Safe to run on many instances: events are claimed with {@code FOR UPDATE SKIP LOCKED} and
 * marked PROCESSING with a lease ({@code locked_until}). Each delivery attempt (including one that
 * crashes) increments {@code attempts}. A lease that expires without an outcome is reclaimed;
 * once {@code attempts} reaches {@code max_attempts} the event becomes DEAD.
 *
 * <p>No ordering guarantee exists between events, including events of one aggregate.
 */
class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Map<String, List<OutboxEventHandler>> handlersByType;
    private final OutboxProperties props;
    private final Clock clock;
    private final DoubleSupplier jitter;
    private final Counter delivered;
    private final Counter failed;
    private final Counter dead;
    private final Counter reclaimed;
    private final Counter duplicatesSkipped;

    OutboxDispatcher(JdbcTemplate jdbc, PlatformTransactionManager txManager, List<OutboxEventHandler> handlers,
            OutboxProperties props, Clock clock, DoubleSupplier jitter, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.props = props;
        this.clock = clock;
        this.jitter = jitter;
        this.handlersByType = new HashMap<>();
        Map<String, String> seen = new HashMap<>();
        for (OutboxEventHandler h : handlers) {
            String key = h.eventType() + "|" + h.consumerName();
            if (h.consumerName() == null || h.consumerName().isBlank() || h.consumerName().length() > 120
                    || seen.put(key, key) != null) {
                throw new IllegalStateException("outbox handler consumerName must be unique per event type and 1-120 chars");
            }
            handlersByType.computeIfAbsent(h.eventType(), k -> new ArrayList<>()).add(h);
        }
        this.delivered = meters.counter("qal.outbox.attempts", "outcome", "published");
        this.failed = meters.counter("qal.outbox.attempts", "outcome", "failed");
        this.dead = meters.counter("qal.outbox.attempts", "outcome", "dead");
        this.reclaimed = meters.counter("qal.outbox.attempts", "outcome", "lease_reclaimed");
        this.duplicatesSkipped = meters.counter("qal.outbox.duplicates.skipped");
        registerGauge(meters, "qal.outbox.pending", "PENDING','FAILED");
        registerGauge(meters, "qal.outbox.dead", "DEAD");
    }

    private void registerGauge(MeterRegistry meters, String name, String statuses) {
        String sql = "SELECT count(*) FROM outbox_events WHERE event_status IN ('" + statuses + "')";
        long[] cache = {0, 0};
        Gauge.builder(name, () -> {
            long now = System.currentTimeMillis();
            if (now - cache[1] > 15_000) {
                try {
                    Long n = jdbc.queryForObject(sql, Long.class);
                    cache[0] = n == null ? 0 : n;
                    cache[1] = now;
                } catch (RuntimeException e) {
                    log.warn("outbox gauge {} unavailable", name);
                }
            }
            return cache[0];
        }).register(meters);
    }

    /** One poll: claim a batch, deliver it. Returns the number of events claimed for delivery. */
    int runOnce() {
        List<OutboxEvent> claimed = claim();
        for (OutboxEvent event : claimed) {
            deliver(event);
        }
        return claimed.size();
    }

    /** Claims due events (and expired leases) in one short transaction; the claim is committed before handlers run. */
    List<OutboxEvent> claim() {
        Instant now = clock.instant();
        OffsetDateTime nowTs = ts(now);
        OffsetDateTime leaseEnd = ts(now.plus(props.lease()));
        List<OutboxEvent> result = new ArrayList<>();
        tx.executeWithoutResult(status -> {
            List<Claimed> rows = jdbc.query("""
                    SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload,
                           attempts, max_attempts, created_at, event_status
                      FROM outbox_events
                     WHERE (event_status IN ('PENDING', 'FAILED') AND available_at <= ?)
                        OR (event_status = 'PROCESSING' AND locked_until <= ?)
                     ORDER BY available_at
                     LIMIT ?
                       FOR UPDATE SKIP LOCKED
                    """, (rs, i) -> new Claimed(
                    new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
                            rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"),
                            rs.getInt("attempts") + 1, rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                    rs.getInt("max_attempts"), "PROCESSING".equals(rs.getString("event_status"))),
                    nowTs, nowTs, props.batchSize());
            for (Claimed c : rows) {
                if (c.wasLeaseExpired()) {
                    reclaimed.increment();
                    log.warn("outbox lease expired, reclaiming event id={} type={}", c.event().id(), c.event().eventType());
                }
                if (c.event().attempt() > c.maxAttempts()) {
                    // a crash consumed the last attempt: give up without invoking handlers again
                    jdbc.update("""
                            UPDATE outbox_events SET event_status = 'DEAD', locked_until = NULL,
                                   last_error = 'lease expired after final attempt'
                             WHERE id = ?
                            """, c.event().id());
                    dead.increment();
                    log.error("outbox event dead id={} type={}", c.event().id(), c.event().eventType());
                    continue;
                }
                jdbc.update("""
                        UPDATE outbox_events SET event_status = 'PROCESSING', attempts = ?, locked_until = ?
                         WHERE id = ?
                        """, c.event().attempt(), leaseEnd, c.event().id());
                result.add(c.event());
            }
        });
        return result;
    }

    /** Package-private for tests that interleave two dispatchers' claims and deliveries. */
    void deliver(OutboxEvent event) {
        try {
            for (OutboxEventHandler handler : handlersByType.getOrDefault(event.eventType(), List.of())) {
                invokeOnce(handler, event);
            }
        } catch (Exception e) {
            recordFailure(event, e);
            return;
        }
        // Not guarded: if this throws (crash), the event stays PROCESSING and the lease expires.
        markPublished(event.id(), event.attempt());
        delivered.increment();
        log.debug("outbox published id={} type={}", event.id(), event.eventType());
    }

    /** Dedupe ledger insert and handler effect share one transaction: effect applied once per (consumer, event id). */
    private void invokeOnce(OutboxEventHandler handler, OutboxEvent event) {
        tx.executeWithoutResult(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO processed_events (consumer, event_id) VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """, handler.consumerName(), event.id());
            if (inserted == 0) {
                duplicatesSkipped.increment();
                log.info("outbox duplicate delivery skipped id={} type={} consumer={}",
                        event.id(), event.eventType(), handler.consumerName());
                return;
            }
            try {
                handler.handle(event);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new HandlerFailure(e);
            }
        });
    }

    /**
     * Outcome updates are fenced by the claimed attempt number: a worker whose lease expired and whose event was
     * reclaimed by another worker (attempts incremented) must not overwrite the new owner's state.
     */
    void markPublished(UUID id, int attempt) {
        jdbc.update("""
                UPDATE outbox_events SET event_status = 'PUBLISHED', published_at = ?, locked_until = NULL,
                       last_error = NULL
                 WHERE id = ? AND event_status = 'PROCESSING' AND attempts = ?
                """, ts(clock.instant()), id, attempt);
    }

    private void recordFailure(OutboxEvent event, Exception e) {
        Throwable cause = e instanceof HandlerFailure && e.getCause() != null ? e.getCause() : e;
        // class name only: exception messages may echo user content
        String error = truncate(cause.getClass().getName());
        Integer maxAttempts = jdbc.queryForObject("SELECT max_attempts FROM outbox_events WHERE id = ?",
                Integer.class, event.id());
        if (maxAttempts != null && event.attempt() >= maxAttempts) {
            jdbc.update("""
                    UPDATE outbox_events SET event_status = 'DEAD', locked_until = NULL, last_error = ?
                     WHERE id = ? AND event_status = 'PROCESSING' AND attempts = ?
                    """, error, event.id(), event.attempt());
            dead.increment();
            log.error("outbox event dead id={} type={} attempts={}", event.id(), event.eventType(), event.attempt());
            return;
        }
        Instant next = clock.instant().plus(backoff(event.attempt()));
        jdbc.update("""
                UPDATE outbox_events SET event_status = 'FAILED', locked_until = NULL, last_error = ?, available_at = ?
                 WHERE id = ? AND event_status = 'PROCESSING' AND attempts = ?
                """, error, ts(next), event.id(), event.attempt());
        failed.increment();
        log.warn("outbox attempt failed id={} type={} attempt={} error={}", event.id(), event.eventType(),
                event.attempt(), error);
    }

    /** Exponential backoff with equal jitter: half the delay fixed, half random. */
    Duration backoff(int attempt) {
        long baseMs = props.baseBackoff().toMillis();
        long capMs = props.maxBackoff().toMillis();
        long exp = attempt - 1 >= 40 ? capMs : Math.min(capMs, baseMs << (attempt - 1));
        long delay = Math.min(capMs, exp);
        return Duration.ofMillis(delay / 2 + (long) (jitter.getAsDouble() * (delay / 2)));
    }

    private static String truncate(String s) {
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH);
    }

    private static OffsetDateTime ts(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private record Claimed(OutboxEvent event, int maxAttempts, boolean wasLeaseExpired) {
    }

    /** Wraps checked handler exceptions so they roll back the transaction. */
    private static final class HandlerFailure extends RuntimeException {
        HandlerFailure(Throwable cause) {
            super(null, cause, false, false);
        }
    }
}
