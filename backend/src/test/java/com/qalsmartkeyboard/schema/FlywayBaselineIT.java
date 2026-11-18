package com.qalsmartkeyboard.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
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
 * Implementation plan step B1.2: the application boots against a fresh PostgreSQL and
 * Flyway applies V1__baseline.sql, so the live schema matches the documented one.
 */
@SpringBootTest
@Testcontainers
class FlywayBaselineIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void baselineIsAppliedByTheBootedApplicationAndValidates() {
        MigrationInfo[] all = flyway.info().all();
        assertEquals(7, all.length, "V1 baseline, V2 outbox/scheduler (B1.4), V3 OTP phone ciphertext (B2.1),"
                + " V4 session SUPERSEDED_RETRY reason (B2.4 follow-up), V5 attestation nonces (B2.3), V6 language-resource discovery columns (B3.1), V7 preferences revisions (B3.3)");
        assertEquals("1", all[0].getVersion().getVersion());
        assertEquals("baseline", all[0].getDescription());
        assertEquals(MigrationState.SUCCESS, all[0].getState());
        assertEquals("2", all[1].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[1].getState());
        assertEquals("4", all[3].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[3].getState());
        assertEquals("5", all[4].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[4].getState());
        assertEquals("6", all[5].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[5].getState());
        assertEquals("7", all[6].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[6].getState());
        flyway.validate(); // throws if an applied migration was edited
    }

    @Test
    void schemaMatchesDocs() {
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'"
                        + " and table_name <> 'flyway_schema_history'", Integer.class);
        // 86 baseline tables + scheduled_tasks and processed_events from V2 + attestation_nonces from V5
        // + user_typing_language_revisions from V7
        assertEquals(90, tables);
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "select success from flyway_schema_history where version='1'", Boolean.class)));
    }
}
