package com.qalsmartkeyboard.platform.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Plan step B1.4: transactional outbox, dispatcher, retry/backoff and idempotent consumers against a real
 * PostgreSQL. The dispatcher is driven by hand with an injected clock (the db-scheduler poller is disabled
 * here and covered by {@link OutboxSchedulerIT}).
 */
@SpringBootTest(properties = "qal.outbox.dispatcher-enabled=false")
@Testcontainers
class OutboxIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

    static final String TYPE = "test.happened";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    OutboxPublisher publisher;
    @Autowired
    DataSource dataSource;

    TransactionTemplate tx;
    MutableClock clock;

    @BeforeEach
    void clean() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_effects (event_id UUID, consumer TEXT)");
        jdbc.execute("TRUNCATE outbox_events, processed_events, test_effects");
        tx = new TransactionTemplate(txManager);
        clock = // the publisher stamps events with the real clock; start the test clock safely after it
        new MutableClock(Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    // ---- helpers ----------------------------------------------------------------

    /** Writes to test_effects inside the dispatcher's transaction; counts body invocations per event. */
    class EffectHandler implements OutboxEventHandler {
        final String consumer;
        final Map<UUID, AtomicInteger> bodyCalls = new ConcurrentHashMap<>();
        volatile int failuresLeft;
        volatile RuntimeException failure = new IllegalStateException("boom with user text: hello");

        EffectHandler(String consumer, int failuresLeft) {
            this.consumer = consumer;
            this.failuresLeft = failuresLeft;
        }

        @Override
        public String consumerName() {
            return consumer;
        }

        @Override
        public String eventType() {
            return TYPE;
        }

        @Override
        public void handle(OutboxEvent event) {
            bodyCalls.computeIfAbsent(event.id(), k -> new AtomicInteger()).incrementAndGet();
            beforeEffect(event);
            jdbc.update("INSERT INTO test_effects (event_id, consumer) VALUES (?, ?)", event.id(), consumer);
            if (failuresLeft > 0) {
                failuresLeft--;
                throw failure; // rolls back the effect and the dedupe row
            }
        }

        void beforeEffect(OutboxEvent event) {
        }

        int totalCalls() {
            return bodyCalls.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    OutboxProperties props(int batch) {
        return new OutboxProperties(false, Duration.ofSeconds(1), batch, Duration.ofSeconds(60),
                Duration.ofSeconds(5), Duration.ofHours(1));
    }

    OutboxDispatcher dispatcher(int batch, OutboxEventHandler... handlers) {
        return new OutboxDispatcher(jdbc, txManager, List.of(handlers), props(batch), clock, () -> 0.0,
                new SimpleMeterRegistry());
    }

    UUID publish() {
        return tx.execute(s -> publisher.publish("subscription", "agg-1", TYPE, Map.of("subscriptionId", "s1", "n", 1)));
    }

    String status(UUID id) {
        return jdbc.queryForObject("SELECT event_status FROM outbox_events WHERE id = ?", String.class, id);
    }

    int attempts(UUID id) {
        return jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, id);
    }

    int effects() {
        return jdbc.queryForObject("SELECT count(*) FROM test_effects", Integer.class);
    }

    Instant availableAt(UUID id) {
        return jdbc.queryForObject("SELECT available_at FROM outbox_events WHERE id = ?", OffsetDateTime.class, id)
                .toInstant();
    }

    // ---- (a) publishing -----------------------------------------------------------

    @Test
    void rolledBackTransactionProducesNoEvent() {
        tx.executeWithoutResult(s -> {
            publisher.publish("subscription", "agg-1", TYPE, Map.of("k", "v"));
            s.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
    }

    @Test
    void publishWithoutTransactionFailsFast() {
        assertThrows(IllegalStateException.class, () -> publisher.publish("a", "b", TYPE, Map.of()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
    }

    @Test
    void publishedRowUsesUuidV7AndPendingStatus() {
        UUID id = publish();
        assertEquals(7, id.version());
        assertEquals("PENDING", status(id));
        assertEquals(0, attempts(id));
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE id = ?", String.class, id);
        assertTrue(payload.contains("subscriptionId"));
    }

    // ---- (b) happy path -----------------------------------------------------------

    @Test
    void happyPathDeliversOnceAndMarksPublished() {
        EffectHandler h = new EffectHandler("c1", 0);
        UUID id = publish();
        assertEquals(1, dispatcher(10, h).runOnce());
        assertEquals("PUBLISHED", status(id));
        assertEquals(1, attempts(id));
        assertEquals(1, effects());
        assertNotNull(jdbc.queryForObject("SELECT published_at FROM outbox_events WHERE id = ?", Object.class, id));
        assertEquals(0, dispatcher(10, h).runOnce(), "nothing left to claim");
    }

    @Test
    void eventWithoutHandlersIsPublished() {
        UUID id = publish();
        dispatcher(10).runOnce();
        assertEquals("PUBLISHED", status(id));
    }

    @Test
    void futureEventsAreNotClaimed() {
        EffectHandler h = new EffectHandler("c1", 0);
        UUID id = publish();
        jdbc.update("UPDATE outbox_events SET available_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(clock.instant().plusSeconds(10), java.time.ZoneOffset.UTC), id);
        assertEquals(0, dispatcher(10, h).runOnce());
        clock.advance(Duration.ofSeconds(11));
        assertEquals(1, dispatcher(10, h).runOnce());
    }

    // ---- (c) crash survival, once-effective delivery ---------------------------------

    @Test
    void eventSurvivesCrashAfterClaimAndIsDeliveredByFreshDispatcher() {
        EffectHandler h = new EffectHandler("c1", 0);
        UUID id = publish();

        OutboxDispatcher crashed = dispatcher(10, h);
        assertEquals(1, crashed.claim().size()); // claimed, marked PROCESSING with a lease ... then the process dies
        assertEquals("PROCESSING", status(id));
        assertEquals(0, effects());

        assertEquals(0, dispatcher(10, h).runOnce(), "lease still held: other instances must not take it");
        clock.advance(Duration.ofSeconds(61)); // lease expires

        assertEquals(1, dispatcher(10, h).runOnce());
        assertEquals("PUBLISHED", status(id));
        assertEquals(2, attempts(id), "the crashed claim counted as an attempt");
        assertEquals(1, effects());
    }

    /** Handler effect commits, then the process dies before the event is marked PUBLISHED. */
    @Test
    void crashAfterHandlerEffectIsRedeliveredButEffectAppliedExactlyOnce() {
        EffectHandler h = new EffectHandler("c1", 0);
        UUID id = publish();

        OutboxDispatcher crashing = new OutboxDispatcher(jdbc, txManager, List.of(h), props(10), clock, () -> 0.0,
                new SimpleMeterRegistry()) {
            @Override
            void markPublished(UUID eventId, int attempt) {
                throw new SimulatedCrash();
            }
        };
        assertThrows(SimulatedCrash.class, crashing::runOnce);
        assertEquals("PROCESSING", status(id));
        assertEquals(1, effects(), "effect and dedupe row were committed before the crash");
        assertEquals(1, h.totalCalls());

        clock.advance(Duration.ofSeconds(61));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OutboxDispatcher fresh = new OutboxDispatcher(jdbc, txManager, List.of(h), props(10), clock, () -> 0.0, meters);
        assertEquals(1, fresh.runOnce()); // delivered a second time ...

        assertEquals("PUBLISHED", status(id));
        assertEquals(2, attempts(id), "event was delivered twice");
        assertEquals(1, h.totalCalls(), "... but the idempotent consumer skipped the second delivery");
        assertEquals(1, effects(), "exactly one effect");
        assertEquals(1.0, meters.counter("qal.outbox.duplicates.skipped").count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, id));
    }

    @Test
    void failureOfOneHandlerRetriesEventWithoutRepeatingTheOthers() {
        EffectHandler ok = new EffectHandler("ok", 0);
        EffectHandler flaky = new EffectHandler("flaky", 1);
        UUID id = publish();
        OutboxDispatcher d = dispatcher(10, ok, flaky);
        d.runOnce();
        assertEquals("FAILED", status(id));
        clock.advance(Duration.ofMinutes(1));
        d.runOnce();
        assertEquals("PUBLISHED", status(id));
        assertEquals(1, ok.totalCalls(), "handler that already succeeded is not applied again");
        assertEquals(2, flaky.totalCalls());
        assertEquals(2, effects(), "one effect per consumer");
    }

    class SimulatedCrash extends RuntimeException {
    }

    /** A slow worker whose lease expired must not overwrite the state of the worker that reclaimed the event. */
    @Test
    void staleWorkerOutcomeDoesNotOverwriteReclaimedEvent() {
        EffectHandler failing = new EffectHandler("c1", 1);
        EffectHandler ok = new EffectHandler("c1", 0);
        UUID id = publish();

        OutboxDispatcher slow = dispatcher(10, failing);
        List<OutboxEvent> slowClaim = slow.claim(); // attempt 1, then the worker stalls past its lease
        clock.advance(Duration.ofSeconds(61));
        OutboxDispatcher reclaimer = dispatcher(10, ok);
        List<OutboxEvent> freshClaim = reclaimer.claim(); // attempt 2
        assertEquals(1, slowClaim.size());
        assertEquals(1, freshClaim.size());

        slow.deliver(slowClaim.get(0)); // stale worker fails late
        assertEquals("PROCESSING", status(id), "stale failure must not reset the reclaimed event to FAILED");
        assertEquals(2, attempts(id));

        reclaimer.deliver(freshClaim.get(0));
        assertEquals("PUBLISHED", status(id));
        assertEquals(1, effects());
    }

    // ---- (d) retry with backoff ---------------------------------------------------

    @Test
    void failedEventIsRetriedWithExponentialBackoffThenSucceeds() {
        EffectHandler h = new EffectHandler("c1", 2);
        UUID id = publish();
        OutboxDispatcher d = dispatcher(10, h); // jitter 0 => delay = half of the exponential value

        Instant t0 = clock.instant();
        d.runOnce();
        assertEquals("FAILED", status(id));
        assertEquals(1, attempts(id));
        assertEquals(t0.plusMillis(2500), availableAt(id)); // base 5s / 2
        // class name only, never the (user-text bearing) exception message
        assertEquals("java.lang.IllegalStateException",
                jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE id = ?", String.class, id));
        assertEquals(0, effects(), "failed attempt rolled back its effect");

        assertEquals(0, d.runOnce(), "backoff not elapsed");
        clock.advance(Duration.ofMillis(2500));
        d.runOnce();
        assertEquals("FAILED", status(id));
        assertEquals(2, attempts(id));
        assertEquals(clock.instant().plusSeconds(5), availableAt(id)); // 10s / 2

        clock.advance(Duration.ofSeconds(5));
        d.runOnce();
        assertEquals("PUBLISHED", status(id));
        assertEquals(3, attempts(id));
        assertNull(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE id = ?", String.class, id));
        assertEquals(1, effects());
    }

    @Test
    void backoffIsCappedAndJittered() {
        OutboxDispatcher low = new OutboxDispatcher(jdbc, txManager, List.of(), props(1), clock, () -> 0.0,
                new SimpleMeterRegistry());
        OutboxDispatcher high = new OutboxDispatcher(jdbc, txManager, List.of(), props(1), clock, () -> 1.0,
                new SimpleMeterRegistry());
        assertEquals(Duration.ofMillis(2500), low.backoff(1));
        assertEquals(Duration.ofSeconds(5), high.backoff(1));
        assertEquals(Duration.ofSeconds(30 * 60), low.backoff(60));
        assertEquals(Duration.ofHours(1), high.backoff(60));
    }

    // ---- (e) dead ------------------------------------------------------------------

    @Test
    void eventBecomesDeadAfterMaxAttempts() {
        EffectHandler h = new EffectHandler("c1", Integer.MAX_VALUE);
        UUID id = publish();
        jdbc.update("UPDATE outbox_events SET max_attempts = 3 WHERE id = ?", id);
        OutboxDispatcher d = dispatcher(10, h);
        for (int i = 1; i <= 3; i++) {
            assertEquals(1, d.runOnce());
            clock.advance(Duration.ofHours(2));
        }
        assertEquals("DEAD", status(id));
        assertEquals(3, attempts(id));
        assertEquals("java.lang.IllegalStateException",
                jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE id = ?", String.class, id));
        assertEquals(0, d.runOnce(), "dead events are never claimed again");
        assertEquals(3, h.totalCalls());
    }

    @Test
    void crashOnTheFinalAttemptEndsDeadWithoutInvokingHandlers() {
        EffectHandler h = new EffectHandler("c1", 0);
        UUID id = publish();
        jdbc.update("UPDATE outbox_events SET max_attempts = 2 WHERE id = ?", id);
        dispatcher(10, h).claim(); // attempt 1 crashes
        clock.advance(Duration.ofSeconds(61));
        dispatcher(10, h).claim(); // attempt 2 crashes
        clock.advance(Duration.ofSeconds(61));
        assertEquals(0, dispatcher(10, h).runOnce());
        assertEquals("DEAD", status(id));
        assertEquals(0, h.totalCalls());
    }

    // ---- (f) concurrency -----------------------------------------------------------

    @Test
    void rowsLockedByAnotherTransactionAreSkippedNotWaitedOn() throws Exception {
        EffectHandler h = new EffectHandler("c1", 0);
        UUID locked = publish();
        UUID free = publish();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM outbox_events WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, locked);
                ps.executeQuery();
            }
            assertEquals(1, dispatcher(10, h).runOnce(), "did not block on the locked row, took the free one");
            assertEquals("PUBLISHED", status(free));
            assertEquals("PENDING", status(locked));
            c.rollback();
        }
        assertEquals(1, dispatcher(10, h).runOnce());
        assertEquals("PUBLISHED", status(locked));
    }

    @Test
    void twoDispatchersNeverDeliverTheSameEventConcurrently() throws Exception {
        int events = 6;
        Set<UUID> ids = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < events; i++) {
            ids.add(publish());
        }
        CountDownLatch aInHandler = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Map<UUID, AtomicInteger> active = new ConcurrentHashMap<>();
        AtomicInteger overlaps = new AtomicInteger();
        Thread[] aThread = new Thread[1];
        EffectHandler h = new EffectHandler("c1", 0) {
            @Override
            void beforeEffect(OutboxEvent event) {
                if (active.computeIfAbsent(event.id(), k -> new AtomicInteger()).incrementAndGet() > 1) {
                    overlaps.incrementAndGet();
                }
                if (Thread.currentThread() == aThread[0]) {
                    aInHandler.countDown();
                    try {
                        assertTrue(release.await(30, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                active.get(event.id()).decrementAndGet();
            }
        };
        OutboxDispatcher a = dispatcher(3, h);
        OutboxDispatcher b = dispatcher(3, h);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> fa = pool.submit(() -> {
            aThread[0] = Thread.currentThread();
            return a.runOnce();
        });
        assertTrue(aInHandler.await(30, TimeUnit.SECONDS));
        // A holds 3 claimed events (one blocked in its handler); B must take the other 3 and finish
        assertEquals(3, b.runOnce());
        assertEquals(3L, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_status = 'PUBLISHED'",
                Long.class));
        assertEquals(3L, jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_status = 'PROCESSING'",
                Long.class));
        release.countDown();
        assertEquals(3, fa.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, overlaps.get());
        assertEquals(events, h.totalCalls());
        assertEquals(events, effects());
        for (UUID id : ids) {
            assertEquals("PUBLISHED", status(id));
            assertEquals(1, h.bodyCalls.get(id).get(), "each event handled exactly once");
        }
    }

    @Test
    void racingDispatchersDeliverEveryEventExactlyOnce() throws Exception {
        int events = 60;
        for (int i = 0; i < events; i++) {
            publish();
        }
        EffectHandler h = new EffectHandler("c1", 0);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < 4; t++) {
            OutboxDispatcher d = dispatcher(5, h);
            futures.add(pool.submit(() -> {
                start.await();
                int total = 0;
                int n;
                while ((n = d.runOnce()) > 0) {
                    total += n;
                }
                return total;
            }));
        }
        start.countDown();
        int claimedTotal = 0;
        for (Future<Integer> f : futures) {
            claimedTotal += f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(events, claimedTotal, "no event claimed twice");
        assertEquals(events, h.totalCalls());
        assertEquals(events, effects());
        assertEquals((long) events, jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_status = 'PUBLISHED' AND attempts = 1", Long.class));
        assertFalse(h.bodyCalls.values().stream().anyMatch(c -> c.get() != 1));
    }

    /** Mutable test clock. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
