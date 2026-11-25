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
        assertEquals(32, all.length, "V1 baseline, V2 outbox/scheduler (B1.4), V3 OTP phone ciphertext (B2.1),"
                + " V4 session SUPERSEDED_RETRY reason (B2.4 follow-up), V5 attestation nonces (B2.3), V6 language-resource discovery columns (B3.1), V7 preferences revisions (B3.3),"
                + " V8 destination profiles and app rules (B3.4), V9 usage reservations (B4.2), V10 AI provider seeds (B5.1), V11 account deletion progress (B6.2), V12 AI provider cost control (B5.2), V13 dictionary sync (B3.6),"
                + " V14 AI replay cache (B5.4), V15 language-resource datasets (B3.2),"
                + " V16 retention: dictionary tombstone floor and purge indexes (B6.3), V17 AI feedback content and history uniqueness (B5.6, B5.7), V18 premium themes feature, V19 OpenAI and Google Translate providers (ADR 0023),"
                + " V20 AI text actions and e-mail writer (ADR 0027), V21 prompt templates v2 for e-mail names and reply splitting, V22 e-mail sign-in challenges (ADR 0033), V23 Arabic language (ADR 0034),"
                + " V24 translation language catalogue (ADR 0040), V25 payments provider routing (ADR 0045),"
                + " V26 Chapa, RevenueCat mappings, refunds and reconciliation (ADR 0045 A2, ADR 0046),"
                + " V27 extended tools catalogue (T8.1, ADR 0048), V28 AI format, reply intents and document types (T8.2, ADR 0048),"
                + " V29 smart-reply template v4: a preset intent decides every reply (T8.13),"
                + " V30 FX rate snapshots (T8.3), V31 prepaid credit packs and market offerings (T8.10),"
                + " V32 store-billed credit packs (T8.10, RevenueCat consumables)");
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
        assertEquals("13", all[12].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[12].getState());
        assertEquals("14", all[13].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[13].getState());
        assertEquals("15", all[14].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[14].getState());
        assertEquals("16", all[15].getVersion().getVersion());
        assertEquals("17", all[16].getVersion().getVersion());
        assertEquals("18", all[17].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[17].getState());
        assertEquals("19", all[18].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[18].getState());
        assertEquals("20", all[19].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[19].getState());
        assertEquals(MigrationState.SUCCESS, all[15].getState());
        assertEquals("22", all[21].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[21].getState());
        assertEquals("23", all[22].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[22].getState());
        assertEquals("25", all[24].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[24].getState());
        assertEquals("26", all[25].getVersion().getVersion());
        assertEquals(MigrationState.SUCCESS, all[25].getState());
        assertEquals("31", all[30].getVersion().getVersion());
        assertEquals("prepaid credit packs and market offerings", all[30].getDescription());
        assertEquals(MigrationState.SUCCESS, all[30].getState());
        assertEquals("32", all[31].getVersion().getVersion());
        assertEquals("store billed credit packs", all[31].getDescription());
        assertEquals(MigrationState.SUCCESS, all[31].getState());
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
        // + user_dictionary_sync_state and user_dictionary_applied_changes from V13
        // + ai_request_fingerprints and ai_replay_cache (UNLOGGED) from V14 + language_resource_datasets from V15
        // + email_challenges from V22 + ai_model_translation_languages from V24
        // + billing_provider_routes and billing_receipt_sequences from V25 (V26 adds no table)
        // + fx_rate_snapshots from V30 + billing_market_offerings from V31 (V32 adds columns, no table)
        assertEquals(107, tables);
        assertEquals("u", jdbc.queryForObject(
                "select relpersistence::text from pg_class where relname = 'ai_replay_cache'", String.class));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject(
                "select success from flyway_schema_history where version='1'", Boolean.class)));
    }
}
