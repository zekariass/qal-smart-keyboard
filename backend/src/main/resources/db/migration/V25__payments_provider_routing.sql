-- ADR 0045 (plan steps M4.1, M4.6, ArifPay part of M4.9): billing core with a country- and channel-configurable
-- provider resolution layer. ArifPay is the Ethiopian default, Chapa the alternative, RevenueCat the global default.
--
-- Everything seeded here is OFF: providers, merchant accounts and methods stay inactive / UNVERIFIED, market/channel
-- policies stay REVIEW_REQUIRED (an APPROVED row needs a named reviewer, ck_billing_policy_approval) and the plan
-- products stay DRAFT. Nothing becomes purchasable until an operator completes onboarding and flips those switches
-- (ADR 0045 "Go-live switches"). Idempotent.
--
-- 1. billing_provider_routes: per (country, client platform, distribution channel) the ORDERED payment providers.
--    The resolution layer walks them by priority and offers every provider that passes the SD 16.3 eligibility rules;
--    the first eligible one is the default. Country 'ZZ' is the global default used when a country has no routes of
--    its own (or the billing country is unknown / not trusted). Switching the Ethiopian default between ArifPay and
--    Chapa, or moving ET to RevenueCat, is a data change: replace the rows (admin PUT
--    /admin/v1/billing/provider-routes/{country}/{platform}/{channel} or the SQL in ADR 0045). revision implements
--    N12 optimistic concurrency for that admin operation (one revision per scope, kept equal on all its rows).
--    billing_market_channel_policies (V1) stays the fail-closed policy gate; routes only order providers.
CREATE TABLE billing_provider_routes (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code            CHAR(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    client_platform         VARCHAR(12) NOT NULL CHECK (client_platform IN ('ANDROID', 'IOS', 'WEB')),
    distribution_channel    VARCHAR(24) NOT NULL
                            CHECK (distribution_channel IN ('GOOGLE_PLAY', 'APPLE_APP_STORE', 'DIRECT_DOWNLOAD', 'WEB')),
    provider_id             UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    priority                SMALLINT NOT NULL CHECK (priority BETWEEN 1 AND 100),
    is_enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    notes                   TEXT,
    revision                INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_billing_provider_route_provider UNIQUE (country_code, client_platform, distribution_channel, provider_id),
    CONSTRAINT ux_billing_provider_route_priority UNIQUE (country_code, client_platform, distribution_channel, priority)
        DEFERRABLE INITIALLY DEFERRED
);

COMMENT ON TABLE billing_provider_routes IS
    'ADR 0045: ordered payment providers per billing country (ZZ = global default) and client channel. Order only; '
    'eligibility still needs provider/account/method/adapter/market-policy gates (SD 16.3).';

-- 2. Receipt numbers (SD 16.10, D21): sequential per market and year, allocated under a row lock inside the
--    fulfilment transaction (ET-2026-000001).
CREATE TABLE billing_receipt_sequences (
    market_country_code     CHAR(2) NOT NULL CHECK (market_country_code ~ '^[A-Z]{2}$'),
    receipt_year            SMALLINT NOT NULL CHECK (receipt_year BETWEEN 2000 AND 9999),
    last_value              BIGINT NOT NULL DEFAULT 0 CHECK (last_value >= 0),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (market_country_code, receipt_year)
);

-- 3. HOSTED_CHECKOUT method type: the provider's own page lets the payer pick a wallet, bank or card (ArifPay hosted
--    checkout fills paymentMethods from the merchant account). The inline CHECK of V1 is replaced by name lookup.
DO $$
DECLARE
    c TEXT;
BEGIN
    FOR c IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'provider_payment_methods'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%method_type%' AND pg_get_constraintdef(oid) LIKE '%MOBILE_MONEY%'
    LOOP
        EXECUTE format('ALTER TABLE provider_payment_methods DROP CONSTRAINT %I', c);
    END LOOP;
END $$;

ALTER TABLE provider_payment_methods
    ADD CONSTRAINT ck_provider_payment_method_type
    CHECK (method_type IN ('MOBILE_MONEY', 'BANK_TRANSFER', 'CARD', 'PAYPAL', 'APP_STORE', 'MANUAL', 'HOSTED_CHECKOUT'));

-- 4. RevenueCat: the global, store-managed option (Google Play / App Store purchases through RevenueCat). Its adapter
--    and webhooks arrive in step A2; until then rule 2 (no adapter, no option) keeps it unavailable. Store purchases
--    use store_purchases, never payment_orders (N6).
INSERT INTO payment_providers
    (provider_code, display_name, provider_type, provider_status, is_active, supports_webhooks, supports_refunds,
     supports_payment_verification, documentation_url, configuration)
VALUES
    ('REVENUECAT', 'RevenueCat (Google Play / App Store)', 'APP_STORE', 'CONFIGURATION_REQUIRED', FALSE, TRUE, FALSE,
     TRUE, 'https://www.revenuecat.com/docs', '{"adapterStep": "A2"}'::JSONB)
ON CONFLICT (provider_code) DO NOTHING;

UPDATE payment_providers
   SET documentation_url = 'https://developer.arifpay.net/docs', updated_at = NOW()
 WHERE provider_code = 'ARIFPAY';

-- 5. ArifPay Ethiopia: one hosted-checkout method, a sandbox and a production merchant account (secret references
--    name environment variables; the values never enter the database), each linked to the method. All UNVERIFIED /
--    CONFIGURATION_REQUIRED and disabled until merchant onboarding is confirmed (OI-09, OI-PAY-1..5).
INSERT INTO provider_payment_methods
    (provider_id, method_code, provider_method_code, display_name, country_code, currency_code, method_type,
     availability_status, is_enabled, supports_one_time, supports_direct_charge, supports_auto_renew,
     requires_redirect, metadata)
SELECT id, 'ARIFPAY_CHECKOUT', NULL, 'ArifPay (telebirr, CBE Birr, M-Pesa, banks, cards)', 'ET', 'ETB',
       'HOSTED_CHECKOUT', 'UNVERIFIED', FALSE, TRUE, FALSE, FALSE, TRUE,
       '{"source": "ADR 0045", "methodsShownBy": "ArifPay checkout page (merchant account configuration)"}'::JSONB
FROM payment_providers WHERE provider_code = 'ARIFPAY'
ON CONFLICT (provider_id, method_code, country_code, currency_code) DO NOTHING;

INSERT INTO payment_provider_accounts
    (provider_id, account_code, display_name, country_code, currency_code, environment, account_status, is_active,
     credential_secret_reference, webhook_secret_reference, configuration)
SELECT p.id, v.account_code, v.display_name, 'ET', 'ETB', v.environment, 'CONFIGURATION_REQUIRED', FALSE,
       'env:QAL_ARIFPAY_API_KEY', 'env:QAL_ARIFPAY_WEBHOOK_SECRET', '{}'::JSONB
FROM payment_providers p
CROSS JOIN (VALUES ('arifpay-et-sandbox', 'ArifPay Ethiopia (sandbox)', 'SANDBOX'),
                   ('arifpay-et-production', 'ArifPay Ethiopia (production)', 'PRODUCTION'))
           AS v(account_code, display_name, environment)
WHERE p.provider_code = 'ARIFPAY'
ON CONFLICT (provider_id, account_code) DO NOTHING;

INSERT INTO payment_provider_account_methods
    (provider_account_id, provider_id, provider_payment_method_id, country_code, currency_code, availability_status,
     is_enabled, supports_one_time, supports_auto_renew, requires_redirect)
SELECT a.id, a.provider_id, m.id, 'ET', 'ETB', 'UNVERIFIED', FALSE, TRUE, FALSE, TRUE
FROM payment_provider_accounts a
JOIN payment_providers p ON p.id = a.provider_id AND p.provider_code = 'ARIFPAY'
JOIN provider_payment_methods m ON m.provider_id = p.id AND m.method_code = 'ARIFPAY_CHECKOUT'
     AND m.country_code = 'ET' AND m.currency_code = 'ETB'
WHERE a.account_code IN ('arifpay-et-sandbox', 'arifpay-et-production')
ON CONFLICT (provider_account_id, provider_payment_method_id) DO NOTHING;

-- 6. Routes (product owner, 2026-11-20): Ethiopia -> ArifPay (default, priority 1), Chapa (alternative, priority 2;
--    inactive until its adapter exists, step A2) for the direct APK and the Play build; everything else -> RevenueCat.
--    ET on the Play build is allowed while Google Play's billing system is unavailable in Ethiopia (Google's Payments
--    policy FAQ: the Play billing requirement does not apply where Play billing is unavailable; Ethiopia is listed).
--    MONITORED CONDITION (ADR 0045, OI-PAY-6): when Google announces Play billing for Ethiopia, switch ET/ANDROID/
--    GOOGLE_PLAY to REVENUECAT with STORE_BILLING (config only). Its market policy below still needs a named reviewer.
INSERT INTO billing_provider_routes (country_code, client_platform, distribution_channel, provider_id, priority, notes)
SELECT v.country_code, v.client_platform, v.distribution_channel, p.id, v.priority, v.notes
FROM (VALUES
        ('ET', 'ANDROID', 'DIRECT_DOWNLOAD', 'ARIFPAY', 1, 'ADR 0045: Ethiopian default.'),
        ('ET', 'ANDROID', 'DIRECT_DOWNLOAD', 'CHAPA', 2, 'ADR 0045: alternative; adapter in step A2.'),
        ('ET', 'ANDROID', 'GOOGLE_PLAY', 'ARIFPAY', 1,
         'ADR 0045: allowed while Google Play billing is unavailable in Ethiopia; monitored, switch to REVENUECAT when it launches (OI-PAY-6).'),
        ('ET', 'ANDROID', 'GOOGLE_PLAY', 'CHAPA', 2,
         'ADR 0045: alternative; adapter in step A2. Same monitored Play-billing condition as ARIFPAY.'),
        ('ZZ', 'ANDROID', 'GOOGLE_PLAY', 'REVENUECAT', 1, 'ADR 0045: global default (store-managed); adapter in step A2.'),
        ('ZZ', 'ANDROID', 'DIRECT_DOWNLOAD', 'REVENUECAT', 1,
         'ADR 0045: global default; a sideloaded APK has no Play Billing, step A2 decides (RevenueCat Web Billing?).'),
        ('ZZ', 'IOS', 'APPLE_APP_STORE', 'REVENUECAT', 1, 'ADR 0045: global default (App Store); adapter in step A2.')
     ) AS v(country_code, client_platform, distribution_channel, provider_code, priority, notes)
JOIN payment_providers p ON p.provider_code = v.provider_code
ON CONFLICT (country_code, client_platform, distribution_channel, provider_id) DO NOTHING;

-- 7. Market/channel policies for those scopes, all REVIEW_REQUIRED (fail closed). A POLICY_REVIEWER approves them
--    (reviewed_by_user_id, reviewed_at, purchase_ui_allowed, external_payment_allowed for gateways) once the legal
--    and store reviews are done.
INSERT INTO billing_market_channel_policies
    (country_code, client_platform, distribution_channel, purchase_flow, approval_status, purchase_ui_allowed,
     external_payment_allowed, policy_source_url, policy_notes)
VALUES
    ('ET', 'ANDROID', 'DIRECT_DOWNLOAD', 'LOCAL_GATEWAY_IN_APP', 'REVIEW_REQUIRED', FALSE, FALSE, NULL,
     'ADR 0045: ArifPay hosted checkout in the direct APK (SD 16.8: the direct flavour MAY use local gateways, subject to local law).'),
    ('ET', 'ANDROID', 'GOOGLE_PLAY', 'LOCAL_GATEWAY_IN_APP', 'REVIEW_REQUIRED', FALSE, FALSE,
     'https://support.google.com/googleplay/android-developer/answer/10281818',
     'ADR 0045: ArifPay in the Play build is allowed while Google Play billing is unavailable in Ethiopia (Payments policy FAQ answer/10281818; countries answer/12201481; no merchant registration answer/9306917). Monitored: when Google notifies that Play billing launches in ET, switch this scope to REVENUECAT + STORE_BILLING (OI-PAY-6).'),
    ('ZZ', 'ANDROID', 'GOOGLE_PLAY', 'STORE_BILLING', 'REVIEW_REQUIRED', FALSE, FALSE, NULL,
     'ADR 0045: global default through RevenueCat (step A2).'),
    ('ZZ', 'ANDROID', 'DIRECT_DOWNLOAD', 'STORE_BILLING', 'REVIEW_REQUIRED', FALSE, FALSE, NULL,
     'ADR 0045: global default for the direct APK; step A2 decides the flow.'),
    ('ZZ', 'IOS', 'APPLE_APP_STORE', 'STORE_BILLING', 'REVIEW_REQUIRED', FALSE, FALSE, NULL,
     'ADR 0045: global default (App Store through RevenueCat, step A2).')
ON CONFLICT (country_code, client_platform, distribution_channel) DO NOTHING;

-- 8. PROVISIONAL Ethiopian price book (OI-05: final ETB price points after cost modelling; OI-10: VAT). ETB, tax
--    INCLUSIVE, MANUAL_RENEW 30-day (MONTHLY) and annual terms (SD 16.5) for the direct APK and the Play build. The
--    products stay DRAFT, so no order can be created until finance activates them (the order trigger requires ACTIVE).
INSERT INTO billing_prices
    (billing_product_id, market_country_code, sales_channel, currency_code, amount_minor, tax_behavior,
     billing_period, renewal_model, price_version, is_active, metadata)
SELECT bp.id, 'ET', ch.sales_channel, 'ETB', v.amount_minor, 'INCLUSIVE', v.billing_period, 'MANUAL_RENEW', 1, TRUE,
       '{"provisional": true, "openItems": ["OI-05", "OI-10"], "source": "ADR 0045"}'::JSONB
FROM (VALUES ('PLAN_PREMIUM', 'MONTHLY', 29900), ('PLAN_PREMIUM', 'ANNUAL', 299000),
             ('PLAN_PREMIUM_PLUS', 'MONTHLY', 59900), ('PLAN_PREMIUM_PLUS', 'ANNUAL', 599000))
     AS v(product_code, billing_period, amount_minor)
JOIN billing_products bp ON bp.product_code = v.product_code
CROSS JOIN (VALUES ('ANDROID_DIRECT'), ('ANDROID_GOOGLE_PLAY')) AS ch(sales_channel)
ON CONFLICT (billing_product_id, market_country_code, sales_channel, currency_code, billing_period, renewal_model,
             price_version) DO NOTHING;
