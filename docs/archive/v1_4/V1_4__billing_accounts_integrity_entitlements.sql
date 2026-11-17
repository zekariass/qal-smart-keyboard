-- ============================================================================
-- V1_4: Merchant account scoping, composite payment integrity, price overlap
-- protection and starter plan entitlements.
-- Target: Existing Ethiopian AI Keyboard v1.3 schema (PostgreSQL 17+)
--
-- Prerequisite: permission to install the btree_gist extension. The migration
-- intentionally fails if existing active price rows overlap; correct those
-- rows before retrying. Existing v1.3 attempts/mandates are retained with their
-- account fields NULL; all new attempts/mandates must supply an account binding.
-- ============================================================================

BEGIN;
-- ---------------------------------------------------------------------------
-- Version 1.4 hardening: merchant accounts, relational billing integrity,
-- active-price overlap prevention and starter plan entitlements.
-- ---------------------------------------------------------------------------

-- Used by the exclusion constraint that prevents overlapping active prices.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Each actual merchant relationship / store configuration is an account.
-- Account credentials remain in a secret manager; only opaque references live here.
CREATE TABLE payment_provider_accounts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id                 UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    account_code                VARCHAR(60) NOT NULL,
    display_name                VARCHAR(160) NOT NULL,
    country_code                CHAR(2) NOT NULL CHECK (country_code = 'ZZ' OR country_code ~ '^[A-Z]{2}$'),
    currency_code               CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code),
    environment                 VARCHAR(16) NOT NULL CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    account_status              VARCHAR(24) NOT NULL DEFAULT 'CONFIGURATION_REQUIRED'
                                CHECK (account_status IN ('CONFIGURATION_REQUIRED', 'UNDER_REVIEW', 'TESTING', 'ACTIVE', 'DEGRADED', 'SUSPENDED', 'CLOSED')),
    is_active                   BOOLEAN NOT NULL DEFAULT FALSE,
    merchant_account_reference  VARCHAR(255),
    credential_secret_reference VARCHAR(255),
    webhook_secret_reference    VARCHAR(255),
    configuration               JSONB NOT NULL DEFAULT '{}'::JSONB,
    last_verified_at            TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_provider_account_code UNIQUE (provider_id, account_code),
    CONSTRAINT ux_provider_account_scope UNIQUE (id, provider_id, country_code, currency_code, environment),
    CONSTRAINT ck_provider_account_activation CHECK (is_active = FALSE OR account_status = 'ACTIVE')
);

CREATE INDEX ix_provider_accounts_lookup
    ON payment_provider_accounts (provider_id, country_code, currency_code, environment, is_active, account_status);

COMMENT ON TABLE payment_provider_accounts IS
    'Merchant/store accounts are scoped by provider, market, currency and environment. Never store raw credentials; store secret-manager references only.';

-- A gateway may expose different methods for different merchant accounts.
-- This table is the final method-availability gate used by checkout.
CREATE TABLE payment_provider_account_methods (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_account_id         UUID NOT NULL,
    provider_id                 UUID NOT NULL,
    provider_payment_method_id  UUID NOT NULL,
    country_code                CHAR(2) NOT NULL,
    currency_code               CHAR(3) NOT NULL,
    availability_status         VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
                                CHECK (availability_status IN ('UNVERIFIED', 'APPROVAL_REQUIRED', 'AVAILABLE', 'DISABLED')),
    is_enabled                  BOOLEAN NOT NULL DEFAULT FALSE,
    supports_one_time           BOOLEAN NOT NULL DEFAULT TRUE,
    supports_auto_renew         BOOLEAN NOT NULL DEFAULT FALSE,
    requires_redirect           BOOLEAN NOT NULL DEFAULT TRUE,
    min_amount_minor            BIGINT CHECK (min_amount_minor IS NULL OR min_amount_minor >= 0),
    max_amount_minor            BIGINT CHECK (max_amount_minor IS NULL OR max_amount_minor >= 0),
    merchant_method_reference   VARCHAR(255),
    last_verified_at            TIMESTAMPTZ,
    metadata                    JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_account_method_account_scope
        FOREIGN KEY (provider_account_id, provider_id, country_code, currency_code)
        REFERENCES payment_provider_accounts (id, provider_id, country_code, currency_code) ON DELETE RESTRICT,
    CONSTRAINT fk_account_method_provider_method_scope
        FOREIGN KEY (provider_payment_method_id, provider_id, country_code, currency_code)
        REFERENCES provider_payment_methods (id, provider_id, country_code, currency_code) ON DELETE RESTRICT,
    CONSTRAINT ux_provider_account_method UNIQUE (provider_account_id, provider_payment_method_id),
    CONSTRAINT ux_provider_account_method_binding UNIQUE (id, provider_account_id, provider_id, provider_payment_method_id),
    CONSTRAINT ck_account_method_activation CHECK (is_enabled = FALSE OR availability_status = 'AVAILABLE'),
    CONSTRAINT ck_account_method_auto_renew CHECK (supports_auto_renew = FALSE OR availability_status = 'AVAILABLE'),
    CONSTRAINT ck_account_method_amount_range CHECK (min_amount_minor IS NULL OR max_amount_minor IS NULL OR max_amount_minor >= min_amount_minor)
);

CREATE INDEX ix_provider_account_methods_available
    ON payment_provider_account_methods (provider_account_id, availability_status, is_enabled);

-- Composite unique keys make it possible for child records to enforce that
-- provider, method, market, currency and environment all belong together.
ALTER TABLE provider_payment_methods
    ADD CONSTRAINT ux_provider_payment_method_scope
    UNIQUE (id, provider_id, country_code, currency_code);

ALTER TABLE billing_prices
    ADD CONSTRAINT ux_billing_price_scope
    UNIQUE (id, billing_product_id, currency_code, market_country_code, sales_channel);

ALTER TABLE payment_orders
    ADD CONSTRAINT ux_payment_order_scope
    UNIQUE (id, user_id, market_country_code, currency_code);

-- Fail with actionable errors before adding stricter foreign keys if v1.3
-- history contains inconsistent order/price or transaction/attempt snapshots.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM payment_orders o
        JOIN billing_prices bp ON bp.id = o.billing_price_id
        WHERE bp.billing_product_id <> o.billing_product_id
           OR bp.currency_code <> o.currency_code
           OR bp.market_country_code <> o.market_country_code
           OR bp.sales_channel <> o.sales_channel
           OR bp.amount_minor <> o.amount_minor
    ) THEN
        RAISE EXCEPTION 'Cannot install v1.4: existing payment orders do not match their product/price/country/channel/currency/amount snapshots.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM payment_attempts a
        JOIN payment_orders o ON o.id = a.payment_order_id
        LEFT JOIN provider_payment_methods pm ON pm.id = a.provider_payment_method_id
        WHERE a.provider_payment_method_id IS NOT NULL
          AND (pm.id IS NULL OR pm.provider_id <> a.provider_id
               OR pm.country_code <> o.market_country_code
               OR pm.currency_code <> o.currency_code)
    ) THEN
        RAISE EXCEPTION 'Cannot install v1.4: existing payment attempts reference a method from a different provider/market/currency.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM payment_transactions t
        JOIN payment_attempts a ON a.id = t.payment_attempt_id
        JOIN payment_orders o ON o.id = a.payment_order_id
        WHERE t.provider_id <> a.provider_id OR t.currency_code <> o.currency_code
    ) THEN
        RAISE EXCEPTION 'Cannot install v1.4: existing payment transactions do not match their attempt provider/currency.';
    END IF;
END $$;

ALTER TABLE payment_orders
    ADD CONSTRAINT fk_payment_order_price_product_scope
    FOREIGN KEY (billing_price_id, billing_product_id, currency_code, market_country_code, sales_channel)
    REFERENCES billing_prices (id, billing_product_id, currency_code, market_country_code, sales_channel) ON DELETE RESTRICT;

-- Include the quoted amount in the relational binding so a client/server bug cannot
-- attach a valid price ID to a different amount snapshot.
ALTER TABLE billing_prices
    ADD CONSTRAINT ux_billing_price_scope_amount
    UNIQUE (id, billing_product_id, currency_code, market_country_code, sales_channel, amount_minor);

ALTER TABLE payment_orders
    ADD CONSTRAINT fk_payment_order_price_amount_scope
    FOREIGN KEY (billing_price_id, billing_product_id, currency_code, market_country_code, sales_channel, amount_minor)
    REFERENCES billing_prices (id, billing_product_id, currency_code, market_country_code, sales_channel, amount_minor) ON DELETE RESTRICT;

-- Reject overlapping date ranges for active prices with the same product,
-- market, channel, currency, billing period and renewal model.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM billing_prices a
        JOIN billing_prices b
          ON a.id < b.id
         AND a.billing_product_id = b.billing_product_id
         AND a.market_country_code = b.market_country_code
         AND a.sales_channel = b.sales_channel
         AND a.currency_code = b.currency_code
         AND a.billing_period = b.billing_period
         AND a.renewal_model = b.renewal_model
         AND tstzrange(a.valid_from, COALESCE(a.valid_until, 'infinity'::timestamptz), '[)')
             && tstzrange(b.valid_from, COALESCE(b.valid_until, 'infinity'::timestamptz), '[)')
        WHERE a.is_active = TRUE AND b.is_active = TRUE
    ) THEN
        RAISE EXCEPTION 'Cannot install v1.4: overlapping active billing_prices exist. Retire or adjust overlapping price rows first.';
    END IF;
END $$;

ALTER TABLE billing_prices
    ADD CONSTRAINT ex_billing_prices_no_active_overlap
    EXCLUDE USING gist (
        billing_product_id WITH =,
        market_country_code WITH =,
        sales_channel WITH =,
        currency_code WITH =,
        billing_period WITH =,
        renewal_model WITH =,
        tstzrange(valid_from, COALESCE(valid_until, 'infinity'::timestamptz), '[)') WITH &&
    ) WHERE (is_active = TRUE);

-- Validate that new orders use a currently active price and the matching product type.
-- Composite FKs below also enforce exact product/price/market/channel/currency/amount.
CREATE OR REPLACE FUNCTION fn_validate_payment_order_business_rules()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_product_type VARCHAR(24);
    v_price_active BOOLEAN;
    v_price_valid_from TIMESTAMPTZ;
    v_price_valid_until TIMESTAMPTZ;
BEGIN
    SELECT product_type INTO v_product_type
    FROM billing_products WHERE id = NEW.billing_product_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Billing product does not exist';
    END IF;

    SELECT is_active, valid_from, valid_until
      INTO v_price_active, v_price_valid_from, v_price_valid_until
    FROM billing_prices WHERE id = NEW.billing_price_id;
    IF NOT FOUND OR v_price_active IS DISTINCT FROM TRUE
       OR v_price_valid_from > NOW()
       OR (v_price_valid_until IS NOT NULL AND v_price_valid_until <= NOW()) THEN
        RAISE EXCEPTION 'The selected billing price is not active at order creation time';
    END IF;

    IF (NEW.order_type IN ('SUBSCRIPTION_PURCHASE', 'SUBSCRIPTION_RENEWAL') AND v_product_type <> 'SUBSCRIPTION')
       OR (NEW.order_type = 'AI_CREDIT_TOPUP' AND v_product_type <> 'AI_CREDIT_PACK')
       OR (NEW.order_type = 'ADD_ON_PURCHASE' AND v_product_type <> 'ADD_ON') THEN
        RAISE EXCEPTION 'Order type does not match the billing product type';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_order_validate_product_price
    BEFORE INSERT ON payment_orders
    FOR EACH ROW EXECUTE FUNCTION fn_validate_payment_order_business_rules();

-- Payment attempts snapshot their order's country/currency and select a specific
-- merchant account + account-enabled method. Existing v1.3 rows are backfilled;
-- new attempts are required by a guard trigger to provide the full account binding.
ALTER TABLE payment_attempts
    ADD COLUMN market_country_code CHAR(2),
    ADD COLUMN currency_code CHAR(3),
    ADD COLUMN environment VARCHAR(16),
    ADD COLUMN provider_account_id UUID,
    ADD COLUMN provider_account_method_id UUID;

UPDATE payment_attempts a
SET market_country_code = o.market_country_code,
    currency_code = o.currency_code,
    environment = 'PRODUCTION'
FROM payment_orders o
WHERE o.id = a.payment_order_id;

ALTER TABLE payment_attempts
    ALTER COLUMN market_country_code SET NOT NULL,
    ALTER COLUMN currency_code SET NOT NULL,
    ALTER COLUMN environment SET NOT NULL;

ALTER TABLE payment_attempts
    ADD CONSTRAINT ck_payment_attempt_environment CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    ADD CONSTRAINT fk_payment_attempt_order_market_currency
        FOREIGN KEY (payment_order_id, user_id, market_country_code, currency_code)
        REFERENCES payment_orders (id, user_id, market_country_code, currency_code) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_payment_attempt_provider_method_scope
        FOREIGN KEY (provider_payment_method_id, provider_id, market_country_code, currency_code)
        REFERENCES provider_payment_methods (id, provider_id, country_code, currency_code) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_payment_attempt_provider_account_scope
        FOREIGN KEY (provider_account_id, provider_id, market_country_code, currency_code, environment)
        REFERENCES payment_provider_accounts (id, provider_id, country_code, currency_code, environment) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_payment_attempt_account_method_binding
        FOREIGN KEY (provider_account_method_id, provider_account_id, provider_id, provider_payment_method_id)
        REFERENCES payment_provider_account_methods (id, provider_account_id, provider_id, provider_payment_method_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ux_payment_attempt_provider_currency_binding
        UNIQUE (id, provider_id, currency_code);

CREATE INDEX ix_payment_attempts_provider_account
    ON payment_attempts (provider_account_id, provider_account_method_id)
    WHERE provider_account_id IS NOT NULL;

-- A financial transaction cannot claim a provider or currency different from
-- the payment attempt it belongs to.
ALTER TABLE payment_transactions
    ADD CONSTRAINT fk_payment_transaction_attempt_provider_currency
    FOREIGN KEY (payment_attempt_id, provider_id, currency_code)
    REFERENCES payment_attempts (id, provider_id, currency_code) ON DELETE RESTRICT;

-- Mandates are tied to the exact merchant account and enabled merchant method.
ALTER TABLE payment_mandates
    ADD COLUMN provider_account_id UUID,
    ADD COLUMN provider_account_method_id UUID;

ALTER TABLE payment_mandates
    ADD CONSTRAINT fk_payment_mandate_account_method_binding
    FOREIGN KEY (provider_account_method_id, provider_account_id, provider_id, provider_payment_method_id)
    REFERENCES payment_provider_account_methods (id, provider_account_id, provider_id, provider_payment_method_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ux_payment_mandate_full_binding
    UNIQUE (id, user_id, provider_id, provider_payment_method_id, provider_account_id, provider_account_method_id);

-- Subscriptions retain the same merchant account context as their mandate.
ALTER TABLE user_subscriptions
    ADD COLUMN provider_account_id UUID,
    ADD COLUMN provider_account_method_id UUID;

UPDATE user_subscriptions s
SET provider_account_id = m.provider_account_id,
    provider_account_method_id = m.provider_account_method_id
FROM payment_mandates m
WHERE s.payment_mandate_id = m.id;

ALTER TABLE user_subscriptions
    ADD CONSTRAINT fk_subscription_account_method_binding
    FOREIGN KEY (provider_account_method_id, provider_account_id, provider_id, provider_payment_method_id)
    REFERENCES payment_provider_account_methods (id, provider_account_id, provider_id, provider_payment_method_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_subscription_mandate_full_binding
    FOREIGN KEY (payment_mandate_id, user_id, provider_id, provider_payment_method_id, provider_account_id, provider_account_method_id)
    REFERENCES payment_mandates (id, user_id, provider_id, provider_payment_method_id, provider_account_id, provider_account_method_id) ON DELETE RESTRICT;

-- Guard new payment attempts: an operator-enabled provider, active merchant
-- account, enabled account method and enabled base method are all mandatory.
CREATE OR REPLACE FUNCTION fn_require_active_payment_account_for_attempt()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_order_status VARCHAR(24);
    v_order_expires_at TIMESTAMPTZ;
BEGIN
    SELECT order_status, expires_at INTO v_order_status, v_order_expires_at
    FROM payment_orders
    WHERE id = NEW.payment_order_id AND user_id = NEW.user_id;

    IF NOT FOUND OR v_order_status NOT IN ('CREATED', 'AWAITING_PAYMENT')
       OR (v_order_expires_at IS NOT NULL AND v_order_expires_at <= NOW()) THEN
        RAISE EXCEPTION 'Payment attempt requires an owned, unpaid, non-expired order';
    END IF;

    IF NEW.provider_account_id IS NULL OR NEW.provider_account_method_id IS NULL
       OR NEW.provider_payment_method_id IS NULL THEN
        RAISE EXCEPTION 'A payment attempt must select a merchant account and an account-enabled payment method';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM payment_providers p
        JOIN payment_provider_accounts a ON a.provider_id = p.id
        JOIN payment_provider_account_methods am ON am.provider_account_id = a.id
        JOIN provider_payment_methods pm ON pm.id = am.provider_payment_method_id
        WHERE p.id = NEW.provider_id
          AND p.is_active = TRUE AND p.provider_status = 'ACTIVE'
          AND a.id = NEW.provider_account_id
          AND a.country_code = NEW.market_country_code
          AND a.currency_code = NEW.currency_code
          AND a.environment = NEW.environment
          AND a.is_active = TRUE AND a.account_status = 'ACTIVE'
          AND am.id = NEW.provider_account_method_id
          AND am.provider_payment_method_id = NEW.provider_payment_method_id
          AND am.is_enabled = TRUE AND am.availability_status = 'AVAILABLE'
          AND pm.provider_id = p.id
          AND pm.is_enabled = TRUE AND pm.availability_status = 'AVAILABLE'
    ) THEN
        RAISE EXCEPTION 'The selected payment provider, merchant account or payment method is not currently enabled for this market/currency/environment';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_attempt_requires_active_merchant_account
    BEFORE INSERT ON payment_attempts
    FOR EACH ROW EXECUTE FUNCTION fn_require_active_payment_account_for_attempt();

-- A new recurring mandate also requires an account-level method approved for
-- recurring charges; one-time payment capability is not enough.
CREATE OR REPLACE FUNCTION fn_require_recurring_payment_account_method()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.provider_account_id IS NULL OR NEW.provider_account_method_id IS NULL THEN
        RAISE EXCEPTION 'A payment mandate must reference a merchant account and account-enabled payment method';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM user_consents c
        WHERE c.id = NEW.consent_record_id
          AND c.user_id = NEW.user_id
          AND c.consent_code = 'PAYMENT_MANDATE'
          AND c.is_granted = TRUE
          AND c.revoked_at IS NULL
          AND c.granted_at IS NOT NULL
          AND c.granted_at <= NEW.consented_at
    ) THEN
        RAISE EXCEPTION 'An unrevoked PAYMENT_MANDATE consent for this user is required';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM payment_provider_account_methods am
        JOIN payment_provider_accounts a ON a.id = am.provider_account_id
        JOIN provider_payment_methods pm ON pm.id = am.provider_payment_method_id
        WHERE am.id = NEW.provider_account_method_id
          AND am.provider_account_id = NEW.provider_account_id
          AND am.provider_id = NEW.provider_id
          AND am.provider_payment_method_id = NEW.provider_payment_method_id
          AND am.is_enabled = TRUE AND am.availability_status = 'AVAILABLE'
          AND am.supports_auto_renew = TRUE
          AND a.is_active = TRUE AND a.account_status = 'ACTIVE'
          AND pm.is_enabled = TRUE AND pm.availability_status = 'AVAILABLE'
          AND pm.supports_auto_renew = TRUE
    ) THEN
        RAISE EXCEPTION 'The selected merchant account method is not approved for automatic renewal';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_mandate_requires_recurring_account_method
    BEFORE INSERT ON payment_mandates
    FOR EACH ROW EXECUTE FUNCTION fn_require_recurring_payment_account_method();

-- Starter entitlements: quotas are explicitly provisional product defaults.
-- Admins may tune these values before launch; usage enforcement remains server-side.
WITH entitlement_seed(plan_code, feature_code, access_enabled, included_units, reset_period, unit_name) AS (
    VALUES
    ('FREE', 'OFFLINE_TYPING', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('FREE', 'DIRECT_SCRIPT_LAYOUT', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('FREE', 'PHONETIC_TRANSLITERATION', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('FREE', 'TRANSLATE', TRUE, 10::BIGINT, 'DAILY', 'REQUEST'),
    ('FREE', 'GRAMMAR_CORRECTION', TRUE, 10::BIGINT, 'DAILY', 'REQUEST'),
    ('FREE', 'REWRITE_NATURAL', TRUE, 3::BIGINT, 'DAILY', 'REQUEST'),
    ('FREE', 'REWRITE_PROFESSIONAL', FALSE, 0::BIGINT, 'NONE', 'REQUEST'),
    ('FREE', 'REWRITE_FRIENDLY', FALSE, 0::BIGINT, 'NONE', 'REQUEST'),
    ('FREE', 'REWRITE_POLITE', FALSE, 0::BIGINT, 'NONE', 'REQUEST'),
    ('FREE', 'SMART_REPLY', FALSE, 0::BIGINT, 'NONE', 'REQUEST'),
    ('FREE', 'SUMMARIZE_TEXT', FALSE, 0::BIGINT, 'NONE', 'REQUEST'),
    ('FREE', 'VOICE_TRANSCRIPTION', FALSE, 0::BIGINT, 'NONE', 'MINUTE'),
    ('FREE', 'VOICE_TRANSLATE', FALSE, 0::BIGINT, 'NONE', 'MINUTE'),
    ('FREE', 'TEXT_TO_SPEECH', FALSE, 0::BIGINT, 'NONE', 'MINUTE'),

    ('PREMIUM', 'OFFLINE_TYPING', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM', 'DIRECT_SCRIPT_LAYOUT', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM', 'PHONETIC_TRANSLITERATION', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM', 'TRANSLATE', TRUE, 600::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'GRAMMAR_CORRECTION', TRUE, 600::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'REWRITE_NATURAL', TRUE, 300::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'REWRITE_PROFESSIONAL', TRUE, 150::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'REWRITE_FRIENDLY', TRUE, 150::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'REWRITE_POLITE', TRUE, 150::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'SMART_REPLY', TRUE, 100::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'SUMMARIZE_TEXT', TRUE, 100::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'VOICE_TRANSCRIPTION', TRUE, 60::BIGINT, 'MONTHLY', 'MINUTE'),
    ('PREMIUM', 'VOICE_TRANSLATE', TRUE, 30::BIGINT, 'MONTHLY', 'MINUTE'),
    ('PREMIUM', 'TEXT_TO_SPEECH', TRUE, 60::BIGINT, 'MONTHLY', 'MINUTE'),

    ('PREMIUM_PLUS', 'OFFLINE_TYPING', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM_PLUS', 'DIRECT_SCRIPT_LAYOUT', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM_PLUS', 'PHONETIC_TRANSLITERATION', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM_PLUS', 'TRANSLATE', TRUE, 2000::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'GRAMMAR_CORRECTION', TRUE, 2000::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'REWRITE_NATURAL', TRUE, 1000::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'REWRITE_PROFESSIONAL', TRUE, 600::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'REWRITE_FRIENDLY', TRUE, 600::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'REWRITE_POLITE', TRUE, 600::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'SMART_REPLY', TRUE, 500::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'SUMMARIZE_TEXT', TRUE, 500::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'VOICE_TRANSCRIPTION', TRUE, 300::BIGINT, 'MONTHLY', 'MINUTE'),
    ('PREMIUM_PLUS', 'VOICE_TRANSLATE', TRUE, 150::BIGINT, 'MONTHLY', 'MINUTE'),
    ('PREMIUM_PLUS', 'TEXT_TO_SPEECH', TRUE, 300::BIGINT, 'MONTHLY', 'MINUTE')
)
INSERT INTO plan_entitlements
    (plan_id, feature_code, access_enabled, included_units, reset_period, unit_name, configuration)
SELECT p.id, e.feature_code, e.access_enabled, e.included_units, e.reset_period, e.unit_name,
       jsonb_build_object('quotaPolicyVersion', 'INITIAL_PROPOSAL_V1', 'requiresServerEnforcement', TRUE)
FROM entitlement_seed e
JOIN subscription_plans p ON p.plan_code = e.plan_code
ON CONFLICT (plan_id, feature_code) DO NOTHING;

COMMENT ON TABLE plan_entitlements IS
    'Plan feature access and allowances. Initial v1.4 quotas are provisional launch proposals and must be reviewed against AI cost, market pricing and product policy.';

-- End of v1.4 hardening block.

COMMIT;
