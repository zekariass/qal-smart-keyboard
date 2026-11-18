package com.qalsmartkeyboard.usage;

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

import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UsageProperties.class)
class UsageConfiguration {

	/** db-scheduler task name of the expiry sweeper. */
	static final String SWEEPER_TASK = "usage-reservation-sweeper";

	/**
	 * Until B4.3 provides credit lots the wallet is empty (ADR 0011). B4.3 replaces this bean; tests provide their own
	 * as {@code @Primary}.
	 */
	@Bean
	CreditWallet emptyCreditWallet() {
		return new NoCreditWallet();
	}

	@Bean
	JdbcMetering metering(JdbcClient jdbc, PlatformTransactionManager transactionManager,
			EntitlementResolver entitlements, CreditWallet wallet, UsageProperties properties,
			ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
		return new JdbcMetering(jdbc, new TransactionTemplate(transactionManager), entitlements, wallet, properties,
				clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable());
	}

	/**
	 * Releases expired reservations (SD 15.3 step 6) on the platform's db-scheduler (B1.4): recurring tasks run on one
	 * instance at a time, and the sweep itself is idempotent ({@link JdbcMetering#releaseExpired()}).
	 */
	@Bean
	@ConditionalOnProperty(prefix = "qal.usage", name = "sweeper-enabled", havingValue = "true", matchIfMissing = true)
	RecurringTask<Void> usageReservationSweeper(JdbcMetering metering, UsageProperties properties) {
		return Tasks.recurring(SWEEPER_TASK, FixedDelay.of(properties.sweepInterval()))
				.execute((instance, context) -> metering.releaseExpired());
	}
}
