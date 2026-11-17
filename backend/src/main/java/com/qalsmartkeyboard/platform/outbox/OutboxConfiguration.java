package com.qalsmartkeyboard.platform.outbox;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the outbox dispatcher into db-scheduler. The Spring Boot starter of db-scheduler targets
 * Boot 3 auto-configuration, so the core library is configured explicitly here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfiguration {

    static final String TASK_NAME = "outbox-dispatcher";

    @Bean
    OutboxDispatcher outboxDispatcher(JdbcTemplate jdbc, PlatformTransactionManager txManager,
            List<OutboxEventHandler> handlers, OutboxProperties props, ObjectProvider<Clock> clock,
            MeterRegistry meters) {
        return new OutboxDispatcher(jdbc, txManager, handlers, props, clock.getIfAvailable(Clock::systemUTC),
                () -> ThreadLocalRandom.current().nextDouble(), meters);
    }

    @Bean
    @ConditionalOnProperty(prefix = "qal.outbox", name = "dispatcher-enabled", havingValue = "true", matchIfMissing = true)
    SmartLifecycle outboxScheduler(DataSource dataSource, OutboxDispatcher dispatcher, OutboxProperties props) {
        RecurringTask<Void> task = Tasks.recurring(TASK_NAME, FixedDelay.of(props.pollInterval()))
                .execute((instance, ctx) -> {
                    // drain: keep going while full batches are being claimed
                    while (dispatcher.runOnce() >= props.batchSize()) {
                        // loop
                    }
                });
        Scheduler scheduler = Scheduler.create(dataSource)
                .startTasks(task)
                .threads(1)
                .pollingInterval(props.pollInterval())
                .heartbeatInterval(java.time.Duration.ofSeconds(30))
                .schedulerName(new com.github.kagkarlsson.scheduler.SchedulerName.Fixed("qal-outbox"))
                .build();
        return new SchedulerLifecycle(scheduler);
    }

    private static final class SchedulerLifecycle implements SmartLifecycle {
        private final Scheduler scheduler;
        private volatile boolean running;

        SchedulerLifecycle(Scheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Override
        public void start() {
            scheduler.start();
            running = true;
        }

        @Override
        public void stop() {
            scheduler.stop();
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
