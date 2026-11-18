package com.qalsmartkeyboard.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The expiry sweeper runs as a db-scheduler recurring task on the platform scheduler (B1.4), even with the outbox
 * dispatcher switched off, and releases an abandoned reservation without anybody calling it.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.outbox.poll-interval=200ms",
		"qal.usage.sweeper-enabled=true", "qal.usage.sweep-interval=200ms", "qal.usage.reservation-ttl=1s",
		"qal.usage.credit-expiry-enabled=true", "qal.usage.credit-expiry-interval=200ms" })
@Testcontainers
class UsageSweeperSchedulerIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Autowired
	Metering metering;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	Credits credits;

	@Test
	void anAbandonedReservationIsReleasedByTheScheduledSweeper() {
		UUID user = jdbc.queryForObject(
				"INSERT INTO users (account_type, account_status) VALUES ('REGISTERED', 'ACTIVE') RETURNING id",
				UUID.class);
		Reservation r = metering.reserve(user, "TRANSLATE", 1, UUID.randomUUID().toString());

		await().atMost(Duration.ofSeconds(30)).until(() -> "RELEASED".equals(jdbc.queryForObject(
				"SELECT reservation_status FROM usage_reservations WHERE id = ?", String.class, r.id())));
		assertThat(jdbc.queryForObject("SELECT release_reason FROM usage_reservations WHERE id = ?", String.class,
				r.id())).isEqualTo("EXPIRED");
		assertThat(jdbc.queryForObject("SELECT reserved_units FROM usage_counters WHERE user_id = ?", Long.class, user))
				.isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Integer.class,
				UsageConfiguration.SWEEPER_TASK)).isOne();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduled_tasks WHERE task_name = 'outbox-dispatcher'",
				Integer.class)).isZero();
	}

	@Test
	void anExpiredCreditLotIsSweptByTheScheduledJob() {
		UUID user = jdbc.queryForObject(
				"INSERT INTO users (account_type, account_status) VALUES ('REGISTERED', 'ACTIVE') RETURNING id",
				UUID.class);
		CreditLot lot = credits.grant(user, new java.math.BigDecimal("3"), CreditSource.GRANT, "short",
				java.time.Instant.now().plusSeconds(1));

		await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
				"SELECT remaining_units FROM ai_credit_lots WHERE id = ?", java.math.BigDecimal.class, lot.id())
				.signum() == 0);
		assertThat(jdbc.queryForList("SELECT delta_units::text FROM ai_credit_transactions WHERE lot_id = ? "
				+ "AND transaction_type = 'EXPIRY'", String.class, lot.id())).containsExactly("-3.000000");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduled_tasks WHERE task_name = ?", Integer.class,
				UsageConfiguration.CREDIT_EXPIRY_TASK)).isOne();
	}
}
