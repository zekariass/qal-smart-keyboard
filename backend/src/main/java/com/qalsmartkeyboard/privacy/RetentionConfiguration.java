package com.qalsmartkeyboard.privacy;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.qalsmartkeyboard.platform.retention.RetentionParticipant;

import io.micrometer.core.instrument.MeterRegistry;

/** Retention (B6.3, ADR 0020): the policy registry, the job and its db-scheduler task. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetentionProperties.class)
class RetentionConfiguration {

	/** db-scheduler task name of the retention job. */
	static final String TASK = "privacy-retention";

	@Bean
	RetentionPolicy retentionPolicy(List<RetentionParticipant> participants, RetentionProperties properties) {
		return new RetentionPolicy(participants, properties.periods());
	}

	@Bean
	RetentionJob retentionJob(JdbcClient jdbc, PlatformTransactionManager transactionManager, RetentionPolicy policy,
			RetentionProperties properties, ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
		return new RetentionJob(jdbc, new TransactionTemplate(transactionManager), policy, properties,
				clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable());
	}

	/** Runs the job on the platform's db-scheduler (B1.4): one run at a time across the cluster, daily by default. */
	@Bean
	@ConditionalOnProperty(prefix = "qal.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
	RecurringTask<Void> privacyRetentionTask(RetentionJob job, RetentionProperties properties) {
		return Tasks.recurring(TASK, Schedules.cron(properties.cron(), ZoneOffset.UTC))
				.execute((instance, context) -> job.run());
	}
}
