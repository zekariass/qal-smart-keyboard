package com.qalsmartkeyboard.platform.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.sentry.Sentry;
import io.sentry.SentryOptions;

/**
 * ADR 0024: with a (fake) DSN the application starts, Sentry is on with the hardened options even when properties ask
 * for more, and nothing is sent at startup. The DSN points at a local socket that only counts connections.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext
class SentryEnabledContextIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	static final ServerSocket FAKE_INGEST = openSocket();

	private static ServerSocket openSocket() {
		try {
			return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void sentry(DynamicPropertyRegistry registry) {
		registry.add("sentry.dsn", () -> "http://public@127.0.0.1:" + FAKE_INGEST.getLocalPort() + "/1");
		// Someone sets these by mistake: the hardening still wins.
		registry.add("sentry.send-default-pii", () -> "true");
		registry.add("sentry.max-request-body-size", () -> "always");
		registry.add("sentry.traces-sample-rate", () -> "1.0");
	}

	@AfterAll
	static void closeSentry() throws IOException {
		Sentry.close(); // the SDK is global: do not leak an enabled client into later test classes
		FAKE_INGEST.close();
	}

	@Test
	void fakeDsnStartsSentryHardenedAndSendsNothingAtStartup() throws IOException {
		assertTrue(Sentry.isEnabled(), "a DSN turns Sentry on");
		SentryOptions options = Sentry.getCurrentScopes().getOptions();
		assertFalse(options.isSendDefaultPii());
		assertEquals(SentryOptions.RequestSize.NONE, options.getMaxRequestBodySize());
		assertNull(options.getTracesSampleRate());
		assertEquals(0, options.getMaxBreadcrumbs());
		assertNotNull(options.getBeforeSend());
		assertNotNull(options.getBeforeBreadcrumb());

		FAKE_INGEST.setSoTimeout(1_500);
		assertThrows(SocketTimeoutException.class, FAKE_INGEST::accept, "no request reaches the ingest at startup");
	}
}
