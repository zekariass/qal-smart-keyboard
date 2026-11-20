-- ADR 0045 step A2 + ADR 0046: Chapa (Ethiopian alternative gateway), RevenueCat (global store billing), refunds of
-- duplicate captures and the provider-vs-ledger reconciliation report (plan M4.5).
--
-- As in V25 everything seeded here is OFF: Chapa's method, accounts and account methods stay UNVERIFIED / inactive and
-- the provider stays CONFIGURATION_REQUIRED; the RevenueCat product mappings are inactive. Go-live is a data change
-- (ADR 0045 "Chapa go-live", ADR 0046 "Go-live checklist"). Idempotent.

-- 1. Chapa Ethiopia: one hosted-checkout method (Chapa's page lets the payer choose telebirr, CBE Birr, M-Pesa, cards,
--    banks ...; Chapa documents no API that lists a merchant's pay-in methods, so the method is data, not discovered),
--    a sandbox (test key) and a production (live key) merchant account with their account-method links. Secret
--    references name environment variables only.
UPDATE payment_providers
   SET documentation_url = 'https://developer.chapa.co/',
       configuration = configuration || '{"adapter": "billing.chapa", "webhookSignature": "x-chapa-signature / chapa-signature HMAC-SHA256", "methodDiscovery": false}'::JSONB,
       updated_at = NOW()
 WHERE provider_code = 'CHAPA';

INSERT INTO provider_payment_methods
    (provider_id, method_code, provider_method_code, display_name, country_code, currency_code, method_type,
     availability_status, is_enabled, supports_one_time, supports_direct_charge, supports_auto_renew,
     requires_redirect, metadata)
SELECT id, 'CHAPA_CHECKOUT', NULL, 'Chapa (telebirr, CBE Birr, M-Pesa, cards, banks)', 'ET', 'ETB',
       'HOSTED_CHECKOUT', 'UNVERIFIED', FALSE, TRUE, FALSE, FALSE, TRUE,
       '{"source": "ADR 0045 A2", "methodsShownBy": "Chapa hosted checkout (merchant account configuration)"}'::JSONB
FROM payment_providers WHERE provider_code = 'CHAPA'
ON CONFLICT (provider_id, method_code, country_code, currency_code) DO NOTHING;

INSERT INTO payment_provider_accounts
    (provider_id, account_code, display_name, country_code, currency_code, environment, account_status, is_active,
     credential_secret_reference, webhook_secret_reference, configuration)
SELECT p.id, v.account_code, v.display_name, 'ET', 'ETB', v.environment, 'CONFIGURATION_REQUIRED', FALSE,
       'env:CHAPA_SECRET_KEY', 'env:CHAPA_WEBHOOK_SECRET', '{}'::JSONB
FROM payment_providers p
CROSS JOIN (VALUES ('chapa-et-sandbox', 'Chapa Ethiopia (test mode)', 'SANDBOX'),
                   ('chapa-et-production', 'Chapa Ethiopia (live mode)', 'PRODUCTION'))
           AS v(account_code, display_name, environment)
WHERE p.provider_code = 'CHAPA'
ON CONFLICT (provider_id, account_code) DO NOTHING;

INSERT INTO payment_provider_account_methods
    (provider_account_id, provider_id, provider_payment_method_id, country_code, currency_code, availability_status,
     is_enabled, supports_one_time, supports_auto_renew, requires_redirect)
SELECT a.id, a.provider_id, m.id, 'ET', 'ETB', 'UNVERIFIED', FALSE, TRUE, FALSE, TRUE
FROM payment_provider_accounts a
JOIN payment_providers p ON p.id = a.provider_id AND p.provider_code = 'CHAPA'
JOIN provider_payment_methods m ON m.provider_id = p.id AND m.method_code = 'CHAPA_CHECKOUT'
     AND m.country_code = 'ET' AND m.currency_code = 'ETB'
WHERE a.account_code IN ('chapa-et-sandbox', 'chapa-et-production')
ON CONFLICT (provider_account_id, provider_payment_method_id) DO NOTHING;

-- 2. RevenueCat (ADR 0046): store product identifiers -> our plans. store_purchases needs an external product mapping,
--    and a mapping needs a price row: these are STORE_MANAGED rows of the Play build (the store, not this table, sets
--    the customer price, so amount_minor is 0 and the rows are inactive: they are never listed or sold through orders).
--    Proposed Play identifiers (the Android step creates them in Play Console and RevenueCat):
--      subscriptions qal_premium, qal_premium_plus; base plans monthly, annual
--      RevenueCat product identifier = subscriptionId:basePlanId (e.g. qal_premium:monthly)
--    external_product_id = subscription id, external_offer_id = base plan id. Mappings start inactive (go-live).
INSERT INTO billing_prices
    (billing_product_id, market_country_code, sales_channel, currency_code, amount_minor, tax_behavior,
     billing_period, renewal_model, price_version, is_active, metadata)
SELECT bp.id, 'ZZ', 'ANDROID_GOOGLE_PLAY', 'USD', 0, 'NOT_APPLICABLE', v.billing_period, 'STORE_MANAGED', 1, FALSE,
       '{"storeManaged": true, "priceSetBy": "store", "amountMinorUnused": true, "source": "ADR 0046"}'::JSONB
FROM (VALUES ('PLAN_PREMIUM', 'MONTHLY'), ('PLAN_PREMIUM', 'ANNUAL'),
             ('PLAN_PREMIUM_PLUS', 'MONTHLY'), ('PLAN_PREMIUM_PLUS', 'ANNUAL'))
     AS v(product_code, billing_period)
JOIN billing_products bp ON bp.product_code = v.product_code
ON CONFLICT (billing_product_id, market_country_code, sales_channel, currency_code, billing_period, renewal_model,
             price_version) DO NOTHING;

INSERT INTO external_product_mappings
    (billing_price_id, provider_id, external_product_id, external_offer_id, environment, is_active, metadata)
SELECT pr.id, p.id, v.external_product_id, v.external_offer_id, e.environment, FALSE,
       jsonb_build_object('source', 'ADR 0046', 'revenueCatProductId', v.external_product_id || ':' || v.external_offer_id,
                          'revenueCatEntitlement', v.entitlement)
FROM (VALUES ('PLAN_PREMIUM', 'MONTHLY', 'qal_premium', 'monthly', 'premium'),
             ('PLAN_PREMIUM', 'ANNUAL', 'qal_premium', 'annual', 'premium'),
             ('PLAN_PREMIUM_PLUS', 'MONTHLY', 'qal_premium_plus', 'monthly', 'premium_plus'),
             ('PLAN_PREMIUM_PLUS', 'ANNUAL', 'qal_premium_plus', 'annual', 'premium_plus'))
     AS v(product_code, billing_period, external_product_id, external_offer_id, entitlement)
JOIN billing_products bp ON bp.product_code = v.product_code
JOIN billing_prices pr ON pr.billing_product_id = bp.id AND pr.market_country_code = 'ZZ'
     AND pr.sales_channel = 'ANDROID_GOOGLE_PLAY' AND pr.currency_code = 'USD' AND pr.billing_period = v.billing_period
     AND pr.renewal_model = 'STORE_MANAGED' AND pr.price_version = 1
JOIN payment_providers p ON p.provider_code = 'REVENUECAT'
CROSS JOIN (VALUES ('SANDBOX'), ('PRODUCTION')) AS e(environment)
ON CONFLICT ON CONSTRAINT ux_external_product_mapping DO NOTHING;

UPDATE payment_providers
   SET configuration = configuration || '{"adapter": "billing.revenuecat", "appUserId": "users.id (lower-case UUID)"}'::JSONB,
       updated_at = NOW()
 WHERE provider_code = 'REVENUECAT';

-- 3. Refunds of duplicate captures (ADR 0045 "duplicate capture"): at most one such refund per capture, so the
--    request is idempotent (ON CONFLICT DO NOTHING) however often the capture is re-verified.
CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_refund_duplicate_capture
    ON payment_refunds (capture_transaction_id)
    WHERE reason_code = 'DUPLICATE_CAPTURE';

CREATE INDEX IF NOT EXISTS ix_payment_refunds_status
    ON payment_refunds (refund_status, requested_at);

-- 4. Provider-vs-ledger reconciliation report (M4.5): reconciliation_runs (one per merchant account and window) and
--    reconciliation_items (discrepancies only; matched payments are counted in the run totals). New kinds for
--    duplicate captures and refunds, the attempt / refund the item is about, a key so an open discrepancy is reported
--    once however many runs see it, and safe details (codes, statuses, ids; never payer data).
DO $$
DECLARE
    c TEXT;
BEGIN
    FOR c IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'reconciliation_items'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%match_status%'
    LOOP
        EXECUTE format('ALTER TABLE reconciliation_items DROP CONSTRAINT %I', c);
    END LOOP;
END $$;

ALTER TABLE reconciliation_items
    ADD CONSTRAINT ck_reconciliation_item_match_status
    CHECK (match_status IN ('MATCHED', 'MISSING_INTERNAL', 'MISSING_PROVIDER', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH',
                            'STATUS_MISMATCH', 'DUPLICATE_CAPTURE', 'REFUND_PENDING', 'MANUAL_REFUND_REQUIRED'));

ALTER TABLE reconciliation_items
    ADD COLUMN IF NOT EXISTS payment_attempt_id UUID REFERENCES payment_attempts(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS payment_refund_id UUID REFERENCES payment_refunds(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS discrepancy_key VARCHAR(200),
    ADD COLUMN IF NOT EXISTS details JSONB NOT NULL DEFAULT '{}'::JSONB;

CREATE UNIQUE INDEX IF NOT EXISTS ux_reconciliation_item_open_key
    ON reconciliation_items (discrepancy_key)
    WHERE resolution_status = 'OPEN' AND discrepancy_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_reconciliation_items_resolution_created
    ON reconciliation_items (resolution_status, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_reconciliation_runs_started
    ON reconciliation_runs (started_at DESC);

COMMENT ON COLUMN reconciliation_items.discrepancy_key IS
    'ADR 0045 A2: kind + subject (attempt, refund); an OPEN item with the same key is not reported again.';
