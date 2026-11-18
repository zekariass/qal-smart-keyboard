package com.qalsmartkeyboard.platform.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.sentry.Sentry;

/** ADR 0024: without a DSN (local, CI, tests) the application starts and Sentry stays disabled. */
@SpringBootTest(properties = "sentry.dsn=")
@Testcontainers
class SentryContextIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Autowired
	ApplicationContext context;

	@Test
	void blankDsnLeavesSentryDisabled() {
		assertTrue(context.containsBean("qalSentryOptions"), "the hardening is registered either way");
		assertFalse(Sentry.isEnabled(), "no DSN, no Sentry");
	}
}
