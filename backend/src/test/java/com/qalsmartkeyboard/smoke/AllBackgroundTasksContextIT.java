package com.qalsmartkeyboard.smoke;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;

/**
 * Guard: the shared test config switches every db-scheduler task off, so a context that only exists in production
 * (all background jobs on) was never booted by any test — a bean-name clash between the retention task and a
 * retention participant once made the real application fail to start while every test passed. This test boots the
 * application with the production defaults for every background task.
 */
@SpringBootTest(properties = {
        "qal.outbox.dispatcher-enabled=true",
        "qal.usage.sweeper-enabled=true",
        "qal.usage.credit-expiry-enabled=true",
        "qal.privacy.deletion.executor-enabled=true",
        "qal.retention.enabled=true",
        "qal.ai.idempotency.sweeper-enabled=true" })
@Testcontainers
class AllBackgroundTasksContextIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

    @Autowired
    ApplicationContext context;

    @Test
    @SuppressWarnings("rawtypes")
    void applicationStartsWithEveryBackgroundTaskEnabled() {
        Map<String, RecurringTask> tasks = context.getBeansOfType(RecurringTask.class);
        assertFalse(tasks.isEmpty(), "recurring tasks are registered");
        List<String> names = tasks.values().stream().map(t -> t.getName()).toList();
        for (String expected : List.of("privacy-retention", "privacy-account-deletion", "usage-reservation-sweeper", "usage-credit-expiry",
                "ai-replay-cache-sweeper")) {
            assertTrue(names.contains(expected), () -> "missing recurring task " + expected + " in " + names);
        }
    }
}
