package com.qalsmartkeyboard.platform.outbox;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The db-scheduler recurring task started by the application delivers an event end to end. */
@SpringBootTest(properties = "qal.outbox.poll-interval=100ms")
@Testcontainers
@Import(OutboxSchedulerIT.Handlers.class)
class OutboxSchedulerIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

    static final CopyOnWriteArrayList<UUID> HANDLED = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Handlers {
        @Bean
        OutboxEventHandler schedulerTestHandler() {
            return new OutboxEventHandler() {
                public String consumerName() {
                    return "scheduler-it";
                }

                public String eventType() {
                    return "scheduler.test";
                }

                public void handle(OutboxEvent event) {
                    HANDLED.add(event.id());
                }
            };
        }
    }

    @Autowired
    OutboxPublisher publisher;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void schedulerPollsAndDeliversPublishedEvent() {
        UUID id = new TransactionTemplate(txManager)
                .execute(s -> publisher.publish("agg", "1", "scheduler.test", Map.of("k", 1)));
        await().atMost(Duration.ofSeconds(30)).until(() -> HANDLED.contains(id));
        await().atMost(Duration.ofSeconds(10)).until(() -> "PUBLISHED".equals(jdbc.queryForObject(
                "SELECT event_status FROM outbox_events WHERE id = ?", String.class, id)));
        assertEquals(1, HANDLED.stream().filter(id::equals).count());
    }
}
