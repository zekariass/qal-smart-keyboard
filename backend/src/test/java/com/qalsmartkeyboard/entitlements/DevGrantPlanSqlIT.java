package com.qalsmartkeyboard.entitlements;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * Runs the SQL of {@code infra/scripts/dev-grant-plan.sh} (the same files, psql variables replaced by literals) against
 * the migrated schema: the grant satisfies the constraints and the resolver sees the plan; revoke removes it.
 */
@SpringBootTest(properties = { "qal.outbox.dispatcher-enabled=false", "qal.entitlements.cache-ttl=0s" })
@Testcontainers
class DevGrantPlanSqlIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	EntitlementResolver resolver;

	private static String sql(String file, UUID user, String plan, int days) throws Exception {
		Path p = Path.of("..", "infra", "scripts", "sql", file);
		return Files.readString(p).replace(":'user_id'", "'" + user + "'").replace(":'plan_code'", "'" + plan + "'")
				.replace(":'days'", "'" + days + "'");
	}

	@Test
	void grantInsertSatisfiesTheConstraintsAndRevokeEndsIt() throws Exception {
		UUID user = UUID.randomUUID();
		jdbc.update("INSERT INTO users (id, account_type, account_status, phone_e164) VALUES (?, 'REGISTERED', 'ACTIVE', '+251911000001')", user);
		assertThat(resolver.resolve(user).plan().orElseThrow().planCode()).isEqualTo("FREE");

		assertThat(jdbc.queryForList(sql("dev-grant-plan.sql", user, "PREMIUM", 30))).hasSize(1);
		assertThat(resolver.resolve(user).plan().orElseThrow().planCode()).isEqualTo("PREMIUM");
		assertThat(jdbc.queryForObject("SELECT subscription_status FROM user_subscriptions WHERE user_id = ?",
				String.class, user)).isEqualTo("ACTIVE");

		assertThat(jdbc.queryForList(sql("dev-revoke-plan.sql", user, "PREMIUM", 0))).hasSize(1);
		assertThat(resolver.resolve(user).plan().orElseThrow().planCode()).isEqualTo("FREE");
	}

	@Test
	void unknownPlanInsertsNothing() throws Exception {
		UUID user = UUID.randomUUID();
		jdbc.update("INSERT INTO users (id, account_type, account_status) VALUES (?, 'REGISTERED', 'ACTIVE')", user);
		assertThat(jdbc.queryForList(sql("dev-grant-plan.sql", user, "NOPE", 30))).isEmpty();
	}
}
