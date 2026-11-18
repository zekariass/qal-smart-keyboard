package com.qalsmartkeyboard.privacy;

import java.time.Clock;

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
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.qalsmartkeyboard.entitlements.EntitlementResolver;
import com.qalsmartkeyboard.identity.AccountDeletion;
import com.qalsmartkeyboard.identity.UserDeletionParticipant;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import io.micrometer.core.instrument.MeterRegistry;

/** Account deletion (B6.2, ADR 0015): request service, executor and its db-scheduler task. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeletionProperties.class)
class DeletionConfiguration {

	/** db-scheduler task name of the deletion executor. */
	static final String EXECUTOR_TASK = "privacy-account-deletion";

	@Bean
	DeletionRequestService deletionRequestService(JdbcClient jdbc, PlatformTransactionManager transactionManager,
			AccountDeletion accounts, EntitlementResolver entitlements, OutboxPublisher outbox,
			DeletionProperties properties, ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
		return new DeletionRequestService(jdbc, new TransactionTemplate(transactionManager), accounts, entitlements,
				outbox, properties, clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable());
	}

	@Bean
	DeletionExecutor deletionExecutor(JdbcClient jdbc, PlatformTransactionManager transactionManager,
			AccountDeletion accounts, UserDataEncryption encryption,
			ObjectProvider<UserDeletionParticipant> participants, OutboxPublisher outbox, DeletionProperties properties,
			ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
		return new DeletionExecutor(jdbc, new TransactionTemplate(transactionManager), accounts, encryption,
				participants, outbox, properties, clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable());
	}

	/**
	 * Runs the executor on the platform's db-scheduler (B1.4): one run at a time per task across the cluster; the
	 * executor's row locks make even overlapping runs safe.
	 */
	@Bean
	@ConditionalOnProperty(prefix = "qal.privacy.deletion", name = "executor-enabled", havingValue = "true",
			matchIfMissing = true)
	RecurringTask<Void> accountDeletionExecutor(DeletionExecutor executor, DeletionProperties properties) {
		return Tasks.recurring(EXECUTOR_TASK, FixedDelay.of(properties.executorInterval()))
				.execute((instance, context) -> executor.runOnce());
	}
}
