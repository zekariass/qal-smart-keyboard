package com.qalsmartkeyboard.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves that the database guards in V1__baseline.sql reject bad data
 * (implementation plan step F0.3, system design §21.2).
 *
 * <p>Each test runs in its own transaction that is rolled back, against a schema
 * applied by Flyway exactly as production applies it. Check numbers (01–26) match
 * the original schema smoke test so results stay traceable.
 *
 * <p>A "rejection" only counts when PostgreSQL refuses the data. Syntax errors and
 * unknown tables/columns (SQLSTATE class 42) fail the test instead, so a broken
 * test statement can never pass as a working guard.
 */
@Testcontainers
class SchemaGuardsIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

    private Connection conn;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void openTransactionWithFixture() throws SQLException {
        conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        conn.setAutoCommit(false);

        // ctx holds fixture ids by name; pg_temp.c('name') reads them back inside SQL.
        exec("CREATE TEMP TABLE ctx (k TEXT PRIMARY KEY, v UUID) ON COMMIT DROP");
        exec("CREATE FUNCTION pg_temp.c(p_k TEXT) RETURNS UUID LANGUAGE sql AS $$ SELECT v FROM ctx WHERE k = p_k $$");

        exec("""
                WITH u AS (
                    INSERT INTO users (email, email_normalized, phone_e164, account_status, market_country_code, market_source)
                    VALUES ('Guard@Example.test', 'guard@example.test', '+251911000000', 'ACTIVE', 'ET', 'USER_SELECTED')
                    RETURNING id)
                INSERT INTO ctx SELECT 'user', id FROM u""");
        exec("UPDATE payment_providers SET provider_status = 'ACTIVE', is_active = TRUE WHERE provider_code = 'CHAPA'");
        exec("INSERT INTO ctx SELECT 'chapa', id FROM payment_providers WHERE provider_code = 'CHAPA'");
        exec("""
                WITH a AS (
                    INSERT INTO payment_provider_accounts
                        (provider_id, account_code, display_name, country_code, currency_code, environment, account_status, is_active)
                    VALUES (pg_temp.c('chapa'), 'chapa-et-prod', 'Chapa Ethiopia', 'ET', 'ETB', 'PRODUCTION', 'ACTIVE', TRUE)
                    RETURNING id)
                INSERT INTO ctx SELECT 'account', id FROM a""");
        exec("""
                WITH m AS (
                    INSERT INTO provider_payment_methods
                        (provider_id, method_code, display_name, country_code, currency_code, method_type,
                         availability_status, is_enabled, supports_direct_charge, supports_auto_renew)
                    VALUES (pg_temp.c('chapa'), 'TELEBIRR', 'telebirr', 'ET', 'ETB', 'MOBILE_MONEY', 'AVAILABLE', TRUE, TRUE, TRUE)
                    RETURNING id)
                INSERT INTO ctx SELECT 'method', id FROM m""");
        exec("""
                WITH am AS (
                    INSERT INTO payment_provider_account_methods
                        (provider_account_id, provider_id, provider_payment_method_id, country_code, currency_code,
                         availability_status, is_enabled, supports_auto_renew)
                    VALUES (pg_temp.c('account'), pg_temp.c('chapa'), pg_temp.c('method'), 'ET', 'ETB', 'AVAILABLE', TRUE, TRUE)
                    RETURNING id)
                INSERT INTO ctx SELECT 'account_method', id FROM am""");
        exec("UPDATE billing_products SET product_status = 'ACTIVE' WHERE product_code = 'PLAN_PREMIUM'");
        exec("INSERT INTO ctx SELECT 'product', id FROM billing_products WHERE product_code = 'PLAN_PREMIUM'");
        // V25 seeds provisional ET prices (ADR 0045); the guards below need their own price rows in that scope.
        exec("DELETE FROM billing_prices WHERE metadata->>'source' = 'ADR 0045'");
        exec("""
                WITH p AS (
                    INSERT INTO billing_prices
                        (billing_product_id, market_country_code, sales_channel, currency_code, amount_minor, tax_behavior,
                         billing_period, renewal_model, valid_from)
                    VALUES (pg_temp.c('product'), 'ET', 'ANDROID_DIRECT', 'ETB', 29900, 'INCLUSIVE', 'MONTHLY', 'MANUAL_RENEW',
                            NOW() - INTERVAL '1 hour')
                    RETURNING id)
                INSERT INTO ctx SELECT 'price', id FROM p""");
    }

    @AfterEach
    void rollback() throws SQLException {
        if (conn != null) {
            conn.rollback();
            conn.close();
        }
    }

    // ------------------------------------------------------------------ orders

    @Test
    @DisplayName("01–04 orders must match the server price and product type")
    void orderAmountsAndTypes() throws SQLException {
        createOrder(); // 01: correct server price accepted

        assertRejected("02 wrong list amount", orderInsert("ORD-2", "SUBSCRIPTION_PURCHASE", 100, 100, "idem-order-2"));
        assertRejected("03 charged amount != list - discount + tax",
                orderInsert("ORD-3", "SUBSCRIPTION_PURCHASE", 29900, 100, "idem-order-3"));
        assertRejected("04 order type / product type mismatch",
                orderInsert("ORD-4", "AI_CREDIT_TOPUP", 29900, 29900, "idem-order-4"));
    }

    // ------------------------------------------------------------------ attempts

    @Test
    @DisplayName("05–07 attempts require an active merchant account in the same environment")
    void attemptMerchantAccountRules() throws SQLException {
        createOrder();

        exec("UPDATE payment_provider_accounts SET is_active = FALSE WHERE id = pg_temp.c('account')");
        assertRejected("05 attempt on inactive merchant account", attemptInsert(1, "PRODUCTION", "idem-attempt-1"));

        exec("UPDATE payment_provider_accounts SET is_active = TRUE WHERE id = pg_temp.c('account')");
        createAttempt(); // 06: active account accepted

        assertRejected("07 environment mismatching the merchant account", attemptInsert(2, "SANDBOX", "idem-attempt-2"));
    }

    // ------------------------------------------------------------------ mandates

    @Test
    @DisplayName("08–10 recurring mandates require a current PAYMENT_MANDATE consent")
    void mandateConsent() throws SQLException {
        exec("""
                WITH c AS (
                    INSERT INTO user_consents (user_id, consent_code, consent_version, is_granted)
                    VALUES (pg_temp.c('user'), 'TERMS_ACCEPTANCE', 'v1', TRUE) RETURNING id)
                INSERT INTO ctx SELECT 'terms_consent', id FROM c""");
        assertRejected("08 mandate citing a non-mandate consent", mandateInsert("terms_consent", "MANDATE-1", "NOW()"));

        exec("""
                WITH c AS (
                    INSERT INTO user_consents (user_id, consent_code, consent_version, is_granted)
                    VALUES (pg_temp.c('user'), 'PAYMENT_MANDATE', 'v1', TRUE) RETURNING id)
                INSERT INTO ctx SELECT 'mandate_consent', id FROM c""");
        exec(mandateInsert("mandate_consent", "MANDATE-2", "NOW()")); // 09: accepted

        exec("""
                INSERT INTO user_consents (user_id, consent_code, consent_version, is_granted, recorded_at)
                VALUES (pg_temp.c('user'), 'PAYMENT_MANDATE', 'v1', FALSE, NOW() + INTERVAL '1 second')""");
        assertRejected("10 mandate after consent revocation",
                mandateInsert("mandate_consent", "MANDATE-3", "NOW() + INTERVAL '2 seconds'"));
    }

    // ------------------------------------------------------------------ prices

    @Test
    @DisplayName("11 overlapping active prices for the same product/market/channel are rejected")
    void priceOverlap() throws SQLException {
        assertRejected("11 overlapping active price", """
                INSERT INTO billing_prices
                    (billing_product_id, market_country_code, sales_channel, currency_code, amount_minor, tax_behavior,
                     billing_period, renewal_model, price_version, valid_from)
                VALUES (pg_temp.c('product'), 'ET', 'ANDROID_DIRECT', 'ETB', 24900, 'INCLUSIVE', 'MONTHLY', 'MANUAL_RENEW', 2, NOW())""");
    }

    // ------------------------------------------------------------------ transliteration (F14)

    @Test
    @DisplayName("12–15 transliteration profile lifecycle, including F14 reorder after retirement")
    void transliterationProfileLifecycle() throws SQLException {
        exec("""
                WITH k AS (INSERT INTO resource_signing_keys (key_id, public_key) VALUES ('guard-key-1', 'AAAA') RETURNING id)
                INSERT INTO ctx SELECT 'sign_key', id FROM k""");
        exec("""
                WITH r AS (
                    INSERT INTO language_resources
                        (language_id, resource_type, resource_version, artifact_uri, sha256_checksum, signature_key_id,
                         manifest_signature, publication_status, published_at, release_channel, rollout_percentage)
                    SELECT id, 'TRANSLITERATION_RULES', '1.0.0', 'https://cdn.example.test/am/translit-1.0.0.bin',
                           repeat('a', 64), pg_temp.c('sign_key'), 'sig', 'PUBLISHED', NOW(), 'STABLE', 100
                    FROM supported_languages WHERE language_code = 'am'
                    RETURNING id)
                INSERT INTO ctx SELECT 'am_rules', id FROM r""");
        exec("INSERT INTO ctx SELECT 'am_profile', id FROM transliteration_profiles WHERE profile_code = 'AM_PHONETIC_QWERTY_TO_ETHIOPIC'");

        // 12: profile can be activated with published, signed rules
        exec("""
                UPDATE transliteration_profiles SET rules_resource_id = pg_temp.c('am_rules'), profile_status = 'ACTIVE'
                 WHERE id = pg_temp.c('am_profile')""");
        exec("""
                INSERT INTO user_typing_languages (user_id, language_id, input_mode, transliteration_profile_id, sort_order)
                SELECT pg_temp.c('user'), id, 'PHONETIC_LATIN_TO_NATIVE', pg_temp.c('am_profile'), 10
                FROM supported_languages WHERE language_code = 'am'""");

        assertRejected("13 unpublishing the resource behind an ACTIVE profile",
                "UPDATE language_resources SET publication_status = 'RETIRED' WHERE id = pg_temp.c('am_rules')");

        // 14 (F14): retiring a profile must not block unrelated edits such as reordering
        exec("UPDATE transliteration_profiles SET profile_status = 'RETIRED' WHERE id = pg_temp.c('am_profile')");
        exec("UPDATE user_typing_languages SET sort_order = 5 WHERE user_id = pg_temp.c('user')");

        exec("""
                UPDATE user_typing_languages SET input_mode = 'DIRECT_SCRIPT_LAYOUT', transliteration_profile_id = NULL
                 WHERE user_id = pg_temp.c('user')""");
        assertRejected("15 re-selecting a retired profile", """
                UPDATE user_typing_languages SET input_mode = 'PHONETIC_LATIN_TO_NATIVE', transliteration_profile_id = pg_temp.c('am_profile')
                 WHERE user_id = pg_temp.c('user')""");
    }

    // ------------------------------------------------------------------ entitlements

    @Test
    @DisplayName("16–17 entitlements only on gated features and with the feature's metering unit")
    void entitlementGating() throws SQLException {
        assertRejected("16 entitlement on a free TYPING feature", """
                INSERT INTO plan_entitlements (plan_id, feature_code, metering_unit, access_enabled, included_units, reset_period)
                SELECT id, 'PHONETIC_TRANSLITERATION', 'NONE', TRUE, NULL, 'NONE' FROM subscription_plans WHERE plan_code = 'PREMIUM_PLUS'
                ON CONFLICT DO NOTHING""");
        assertRejected("17 entitlement metering unit differs from feature", """
                UPDATE plan_entitlements SET metering_unit = 'REQUEST'
                 WHERE feature_code = 'SPEECH_TO_TEXT'
                   AND plan_id = (SELECT id FROM subscription_plans WHERE plan_code = 'PREMIUM')""");
    }

    // ------------------------------------------------------------------ payment ledger

    @Test
    @DisplayName("18–21 payment transactions are append-only and consistent with their attempt")
    void paymentLedgerAppendOnly() throws SQLException {
        createOrder();
        createAttempt();
        exec("""
                WITH t AS (
                    INSERT INTO payment_transactions
                        (payment_attempt_id, provider_id, transaction_kind, transaction_status, amount_minor, currency_code,
                         idempotency_key, verified_at)
                    VALUES (pg_temp.c('attempt'), pg_temp.c('chapa'), 'CAPTURE', 'SUCCEEDED', 29900, 'ETB', 'cap-1', NOW())
                    RETURNING id)
                INSERT INTO ctx SELECT 'capture', id FROM t""");

        assertRejected("18 UPDATE on payment_transactions",
                "UPDATE payment_transactions SET amount_minor = 1 WHERE id = pg_temp.c('capture')");
        assertRejected("19 DELETE on payment_transactions",
                "DELETE FROM payment_transactions WHERE id = pg_temp.c('capture')");
        assertRejected("20 transaction currency differs from its attempt", """
                INSERT INTO payment_transactions
                    (payment_attempt_id, provider_id, transaction_kind, transaction_status, amount_minor, currency_code,
                     idempotency_key, verified_at)
                VALUES (pg_temp.c('attempt'), pg_temp.c('chapa'), 'CAPTURE', 'SUCCEEDED', 29900, 'USD', 'cap-2', NOW())""");

        // 21: refund bound to a capture of the same provider and currency is accepted
        exec("""
                INSERT INTO payment_refunds (capture_transaction_id, provider_id, currency_code, refund_reference, amount_minor)
                VALUES (pg_temp.c('capture'), pg_temp.c('chapa'), 'ETB', 'REF-1', 1000)""");
    }

    // ------------------------------------------------------------------ consents (append-only exceptions)

    @Test
    @DisplayName("22–24 append-only consents allow only FK cascade and retention purge deletes")
    void consentAppendOnlyExceptions() throws SQLException {
        // 22: deleting a user cascades into append-only user_consents
        exec("""
                WITH u AS (
                    INSERT INTO users (email, email_normalized, account_status) VALUES ('two@example.test', 'two@example.test', 'ACTIVE')
                    RETURNING id)
                INSERT INTO ctx SELECT 'user2', id FROM u""");
        exec("""
                INSERT INTO user_consents (user_id, consent_code, consent_version, is_granted)
                VALUES (pg_temp.c('user2'), 'TERMS_ACCEPTANCE', 'v1', TRUE)""");
        exec("DELETE FROM users WHERE id = pg_temp.c('user2')");
        assertEquals(0, count("SELECT count(*) FROM user_consents WHERE user_id = pg_temp.c('user2')"));

        exec("""
                WITH c AS (
                    INSERT INTO user_consents (user_id, consent_code, consent_version, is_granted)
                    VALUES (pg_temp.c('user'), 'TERMS_ACCEPTANCE', 'v1', TRUE) RETURNING id)
                INSERT INTO ctx SELECT 'terms_consent', id FROM c""");
        assertRejected("23 direct DELETE on append-only user_consents",
                "DELETE FROM user_consents WHERE id = pg_temp.c('terms_consent')");

        // 24: the retention job may purge when it sets app.retention_purge for its transaction
        exec("SET LOCAL app.retention_purge = 'on'");
        exec("DELETE FROM user_consents WHERE id = pg_temp.c('terms_consent')");
        exec("SET LOCAL app.retention_purge = 'off'");
        assertEquals(0, count("SELECT count(*) FROM user_consents WHERE id = pg_temp.c('terms_consent')"));
    }

    // ------------------------------------------------------------------ organisation seats

    @Test
    @DisplayName("25 seat assignments cannot exceed the subscription's seat_count")
    void seatLimit() throws SQLException {
        exec("""
                WITH o AS (INSERT INTO organizations (org_slug, display_name) VALUES ('guard-org', 'Guard Org') RETURNING id)
                INSERT INTO ctx SELECT 'org', id FROM o""");
        exec("""
                WITH u AS (
                    INSERT INTO users (email, email_normalized, account_status) VALUES ('three@example.test', 'three@example.test', 'ACTIVE')
                    RETURNING id)
                INSERT INTO ctx SELECT 'user3', id FROM u""");
        exec("""
                INSERT INTO organization_members (organization_id, user_id, member_role)
                VALUES (pg_temp.c('org'), pg_temp.c('user'), 'OWNER'), (pg_temp.c('org'), pg_temp.c('user3'), 'MEMBER')""");
        exec("""
                WITH s AS (
                    INSERT INTO organization_subscriptions (organization_id, plan_id, seat_count, subscription_status)
                    SELECT pg_temp.c('org'), id, 1, 'ACTIVE' FROM subscription_plans WHERE plan_code = 'BUSINESS'
                    RETURNING id)
                INSERT INTO ctx SELECT 'org_sub', id FROM s""");
        exec("""
                INSERT INTO organization_seat_assignments (organization_subscription_id, organization_id, user_id)
                VALUES (pg_temp.c('org_sub'), pg_temp.c('org'), pg_temp.c('user'))""");

        assertRejected("25 seat beyond seat_count", """
                INSERT INTO organization_seat_assignments (organization_subscription_id, organization_id, user_id)
                VALUES (pg_temp.c('org_sub'), pg_temp.c('org'), pg_temp.c('user3'))""");
    }

    // ------------------------------------------------------------------ usage reservations

    @Test
    @DisplayName("26 a usage reservation can be settled or released only once")
    void reservationClosesOnce() throws SQLException {
        exec("INSERT INTO ctx VALUES ('reservation', gen_random_uuid())");
        exec("""
                INSERT INTO usage_events (id, event_type, usage_counter_id, subject_type, subject_id, feature_code, units, expires_at)
                VALUES (pg_temp.c('reservation'), 'RESERVE', gen_random_uuid(), 'USER', pg_temp.c('user'), 'TRANSLATE', 1,
                        NOW() + INTERVAL '15 minutes')""");
        exec("""
                INSERT INTO usage_events (id, event_type, reservation_id, usage_counter_id, subject_type, subject_id, feature_code, units)
                VALUES (gen_random_uuid(), 'SETTLE', pg_temp.c('reservation'), gen_random_uuid(), 'USER', pg_temp.c('user'), 'TRANSLATE', 1)""");

        String sqlState = assertRejected("26 second close of a reservation", """
                INSERT INTO usage_events (id, event_type, reservation_id, usage_counter_id, subject_type, subject_id, feature_code, units)
                VALUES (gen_random_uuid(), 'RELEASE', pg_temp.c('reservation'), gen_random_uuid(), 'USER', pg_temp.c('user'), 'TRANSLATE', 1)""");
        assertEquals("23505", sqlState, "expected a unique_violation");
    }

    // ------------------------------------------------------------------ fixture helpers

    private void createOrder() throws SQLException {
        exec("WITH o AS (" + orderInsert("ORD-1", "SUBSCRIPTION_PURCHASE", 29900, 29900, "idem-order-1")
                + " RETURNING id) INSERT INTO ctx SELECT 'order', id FROM o");
    }

    private void createAttempt() throws SQLException {
        exec("WITH a AS (" + attemptInsert(1, "PRODUCTION", "idem-attempt-1")
                + " RETURNING id) INSERT INTO ctx SELECT 'attempt', id FROM a");
    }

    private static String orderInsert(String ref, String orderType, long listMinor, long amountMinor, String idem) {
        return """
                INSERT INTO payment_orders
                    (order_reference, user_id, billing_product_id, billing_price_id, order_type, sales_channel,
                     market_country_code, currency_code, list_amount_minor, amount_minor, idempotency_key)
                VALUES ('%s', pg_temp.c('user'), pg_temp.c('product'), pg_temp.c('price'), '%s',
                        'ANDROID_DIRECT', 'ET', 'ETB', %d, %d, '%s')""".formatted(ref, orderType, listMinor, amountMinor, idem);
    }

    private static String attemptInsert(int attemptNumber, String environment, String idem) {
        return """
                INSERT INTO payment_attempts
                    (payment_order_id, user_id, attempt_number, provider_id, provider_account_id, provider_payment_method_id,
                     provider_account_method_id, market_country_code, currency_code, environment, idempotency_key)
                VALUES (pg_temp.c('order'), pg_temp.c('user'), %d, pg_temp.c('chapa'), pg_temp.c('account'), pg_temp.c('method'),
                        pg_temp.c('account_method'), 'ET', 'ETB', '%s', '%s')""".formatted(attemptNumber, environment, idem);
    }

    private static String mandateInsert(String consentKey, String reference, String consentedAt) {
        return """
                INSERT INTO payment_mandates
                    (user_id, provider_id, provider_account_id, provider_payment_method_id, provider_account_method_id,
                     consent_record_id, provider_mandate_reference, currency_code, interval_unit, consent_text_version, consented_at)
                VALUES (pg_temp.c('user'), pg_temp.c('chapa'), pg_temp.c('account'), pg_temp.c('method'), pg_temp.c('account_method'),
                        pg_temp.c('%s'), '%s', 'ETB', 'MONTH', 'v1', %s)""".formatted(consentKey, reference, consentedAt);
    }

    // ------------------------------------------------------------------ JDBC helpers

    private void exec(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private long count(String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Runs {@code sql} expecting the database to refuse it, then rolls back to a savepoint
     * so the test can continue. Returns the SQLSTATE of the rejection.
     */
    private String assertRejected(String check, String sql) throws SQLException {
        Savepoint sp = conn.setSavepoint();
        try {
            exec(sql);
        } catch (SQLException e) {
            conn.rollback(sp);
            String state = e.getSQLState();
            assertFalse(state != null && state.startsWith("42"),
                    () -> "check " + check + " failed for a test-authoring reason, not a guard: " + e.getMessage());
            return state;
        }
        fail("check " + check + ": statement was accepted but must be rejected");
        return null; // unreachable
    }
}
