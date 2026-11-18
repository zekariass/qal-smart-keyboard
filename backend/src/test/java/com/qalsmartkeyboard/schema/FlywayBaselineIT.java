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
        assertEquals(13, all.length, "V1 baseline, V2 outbox/scheduler (B1.4), V3 OTP phone ciphertext (B2.1),"
                + " V4 session SUPERSEDED_RETRY reason (B2.4 follow-up), V5 attestation nonces (B2.3), V6 language-resource discovery columns (B3.1), V7 preferences revisions (B3.3),"
                + " V8 destination profiles and app rules (B3.4), V9 usage reservations (B4.2), V10 AI provider seeds (B5.1), V11 account deletion progress (B6.2), V12 AI provider cost control (B5.2), V14 dictionary sync (B3.6; V13 is reserved for B5.3)");
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
        assertEquals("8", all[7].getVersion().getVersion());
        assertEquals("9", all[8].getVersion().getVersion());
        assertEquals("10", all[9].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[9].getState());
        assertEquals(MigrationState.SUCCESS, all[7].getState());
        assertEquals("11", all[10].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[10].getState());
        assertEquals("12", all[11].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[11].getState());
        assertEquals("14", all[12].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[12].getState());
        flyway.validate(); // throws if an applied migration was edited
    }

    @Test
    void schemaMatchesDocs() {
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' and table_type='BASE TABLE'"
                        + " and table_name <> 'flyway_schema_history'", Integer.class);
        // 86 baseline tables + scheduled_tasks and processed_events from V2 + attestation_nonces from V5
        // + user_typing_language_revisions from V7 + user_app_rule_revisions and user_app_rule_keys from V8 + usage_reservations from V9
        // + ai_provider_prices, ai_provider_budgets and ai_provider_daily_spend from V12
        // + user_dictionary_sync_state and user_dictionary_applied_changes from V14
        assertEquals(98, tables);
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "select success from flyway_schema_history where version='1'", Boolean.class)));
    }
}
