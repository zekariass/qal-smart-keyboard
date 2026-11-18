package com.qalsmartkeyboard.privacy;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.qalsmartkeyboard.identity.UserExportParticipant;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Data export (B6.1, ADR 0022): the request service, the job and its db-scheduler task, and the export bucket client.
 * Fails closed: under the prod/production/staging profiles the application does not start without export-bucket
 * credentials (an {@link ExportObjectStore} bean from elsewhere, such as a test double, replaces this one).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataExportProperties.class)
class DataExportConfiguration {

	/** db-scheduler task name of the export job. */
	static final String JOB_TASK = "privacy-data-export";
	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";

	@Bean
	ExportObjectStore exportObjectStore(DataExportProperties properties, Environment environment,
			ObjectProvider<Clock> clock) {
		DataExportProperties.Storage storage = properties.storage();
		if (!storage.configured()) {
			if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
				throw new IllegalStateException("qal.privacy.export.storage.endpoint, access-key and secret-key are required "
						+ "under the prod/production/staging profiles (env QAL_EXPORTS_S3_ENDPOINT, "
						+ "QAL_EXPORTS_S3_ACCESS_KEY, QAL_EXPORTS_S3_SECRET_KEY)");
			}
			return new UnconfiguredExportObjectStore();
		}
		return new S3ExportObjectStore(storage.endpoint(), storage.bucket(), storage.region(), storage.pathStyle(),
				storage.accessKey(), storage.secretKey(), clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	DataExportService dataExportService(JdbcClient jdbc, PlatformTransactionManager transactionManager,
			UserDataEncryption encryption, ExportObjectStore store, DataExportProperties properties,
			ObjectProvider<Clock> clock, ObjectProvider<MeterRegistry> meters) {
		return new DataExportService(jdbc, new TransactionTemplate(transactionManager), encryption, store, properties,
				clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable());
	}

	@Bean
	DataExportJob dataExportJob(JdbcClient jdbc, PlatformTransactionManager transactionManager,
			UserDataEncryption encryption, ObjectProvider<UserExportParticipant> participants, ExportObjectStore store,
			DataExportProperties properties, JsonMapper mapper, ObjectProvider<Clock> clock,
			ObjectProvider<MeterRegistry> meters) {
		TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
		snapshot.setReadOnly(true);
		snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		return new DataExportJob(jdbc, new TransactionTemplate(transactionManager), snapshot, encryption, participants,
				store, properties, mapper, clock.getIfAvailable(Clock::systemUTC), meters.getIfAvailable());
	}

	/** Runs the job on the platform's db-scheduler (B1.4): one run at a time per task across the cluster. */
	@Bean
	@ConditionalOnProperty(prefix = "qal.privacy.export", name = "job-enabled", havingValue = "true",
			matchIfMissing = true)
	RecurringTask<Void> dataExportTask(DataExportJob job, DataExportProperties properties) {
		return Tasks.recurring(JOB_TASK, FixedDelay.of(properties.pollInterval()))
				.execute((instance, context) -> job.runOnce());
	}
}
