package com.qalsmartkeyboard.platform.outbox;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the outbox dispatcher into db-scheduler. The Spring Boot starter of db-scheduler targets
 * Boot 3 auto-configuration, so the core library is configured explicitly here.
 *
 * <p>The scheduler is shared: any module may contribute a {@link RecurringTask} bean (for example the usage module's
 * reservation sweeper, B4.2) and it runs on this scheduler, one instance at a time across the cluster.
 * {@code qal.outbox.dispatcher-enabled=false} only leaves the outbox task out; the scheduler starts when at least one
 * task remains.
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
    SmartLifecycle outboxScheduler(DataSource dataSource, OutboxDispatcher dispatcher, OutboxProperties props,
            ObjectProvider<RecurringTask<?>> contributed) {
        List<RecurringTask<?>> tasks = new ArrayList<>();
        if (props.dispatcherEnabled()) {
            tasks.add(Tasks.recurring(TASK_NAME, FixedDelay.of(props.pollInterval()))
                    .execute((instance, ctx) -> {
                        // drain: keep going while full batches are being claimed
                        while (dispatcher.runOnce() >= props.batchSize()) {
                            // loop
                        }
                    }));
        }
        contributed.orderedStream().forEach(tasks::add);
        if (tasks.isEmpty()) {
            return new SchedulerLifecycle(null);
        }
        Scheduler scheduler = Scheduler.create(dataSource)
                .startTasks(tasks)
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
            if (scheduler != null) {
                scheduler.start();
            }
            running = true;
        }

        @Override
        public void stop() {
            if (scheduler != null) {
                scheduler.stop();
            }
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
