-- Plan step T8.10 (SD 15.6, ADR 0048 section 4): prepaid AI credit packs sold through the SD 16 order pipeline, and a
-- per-market offering switch (owner decision 2026-11-24) so a country can sell subscriptions only, credits only, or
-- both, without a deploy. Idempotent.
--
-- As in V25/V26 nothing becomes purchasable by this migration alone: the packs stay DRAFT (the order trigger requires
-- ACTIVE), the ET market/channel policies and ArifPay/Chapa accounts stay as V25/V26 left them (REVIEW_REQUIRED /
-- inactive). Activation is a data change through the admin API (backend README "Credit packs: go-live").
--
-- Credit packs need NO policy or route rows of their own: billing_market_channel_policies and billing_provider_routes
-- are per (country, platform, distribution channel), not per product, so the ET rows of V25 (ArifPay default, Chapa
-- alternative, LOCAL_GATEWAY_IN_APP) apply to packs exactly as to plans. What a market sells is the new
-- billing_market_offerings table below.

-- 1. The three packs (SD 15.6 "Starter / Standard / Pro"; Implementation_notes "PREPAID AI CREDITS"). Credit units and
--    validity are product data, editable by FINANCE through PATCH /admin/v1/billing/products/{productCode}. Validity:
--    SD 15.4/15.6 default 365 days. CREDITS_500 exists since V1 and is updated in place, never duplicated.
UPDATE billing_products
   SET display_name = 'Standard: 500 AI credits',
       description = 'One-off purchase, no subscription: 500 AI credits used after your plan allowance, valid 365 days.',
       metadata = metadata || '{"package": "STANDARD", "source": "T8.10, SD 15.6"}'::JSONB,
       updated_at = NOW()
 WHERE product_code = 'CREDITS_500' AND product_type = 'AI_CREDIT_PACK';

INSERT INTO billing_products
    (product_code, product_type, credit_units, credit_validity_days, display_name, description, product_status, metadata)
VALUES
    ('CREDITS_100', 'AI_CREDIT_PACK', 100, 365, 'Starter: 100 AI credits',
     'One-off purchase, no subscription: 100 AI credits used after your plan allowance, valid 365 days.', 'DRAFT',
     '{"package": "STARTER", "source": "T8.10, SD 15.6"}'::JSONB),
    ('CREDITS_1500', 'AI_CREDIT_PACK', 1500, 365, 'Pro: 1,500 AI credits',
     'One-off purchase, no subscription: 1,500 AI credits used after your plan allowance, valid 365 days.', 'DRAFT',
     '{"package": "PRO", "source": "T8.10, SD 15.6"}'::JSONB)
ON CONFLICT (product_code) DO NOTHING;

-- 2. Ethiopian price book for the packs (owner decision 2026-11-24): 49 / 199 / 499 ETB, tax INCLUSIVE (VAT treatment
--    still OI-10), one-off (ONE_TIME, renewal NONE: no term, no mandate, nothing to cancel), for the direct APK and the
--    Play build (the same two sales channels as the V25 plan prices). FINANCE changes them with
--    PUT /admin/v1/billing/products/{productCode}/prices/{marketCountry}/{salesChannel}.
INSERT INTO billing_prices
    (billing_product_id, market_country_code, sales_channel, currency_code, amount_minor, tax_behavior,
     billing_period, renewal_model, price_version, is_active, metadata)
SELECT bp.id, 'ET', ch.sales_channel, 'ETB', v.amount_minor, 'INCLUSIVE', 'ONE_TIME', 'NONE', 1, TRUE,
       '{"ownerDecision": "2026-11-24", "openItems": ["OI-10"], "source": "T8.10"}'::JSONB
FROM (VALUES ('CREDITS_100', 4900), ('CREDITS_500', 19900), ('CREDITS_1500', 49900)) AS v(product_code, amount_minor)
JOIN billing_products bp ON bp.product_code = v.product_code
CROSS JOIN (VALUES ('ANDROID_DIRECT'), ('ANDROID_GOOGLE_PLAY')) AS ch(sales_channel)
ON CONFLICT (billing_product_id, market_country_code, sales_channel, currency_code, billing_period, renewal_model,
             price_version) DO NOTHING;

-- 3. PROVISIONAL global guide prices (SD 15.6: GBP 0.99 / 3.99 / 9.99; pricing is an open owner question, ADR 0048).
--    Outside Ethiopia packs would be store consumables through RevenueCat, which is NOT implemented yet (T8.10
--    follow-up: store_purchases links subscriptions only, and a PURCHASE lot needs a payment order). These rows are
--    therefore INACTIVE references on the global market (ZZ) of the Play build: never listed, never orderable.
INSERT INTO billing_prices
    (billing_product_id, market_country_code, sales_channel, currency_code, amount_minor, tax_behavior,
     billing_period, renewal_model, price_version, is_active, metadata)
SELECT bp.id, 'ZZ', 'ANDROID_GOOGLE_PLAY', 'GBP', v.amount_minor, 'INCLUSIVE', 'ONE_TIME', 'NONE', 1, FALSE,
       '{"provisional": true, "guidePrice": true, "storeConsumable": "follow-up", "source": "T8.10, SD 15.6"}'::JSONB
FROM (VALUES ('CREDITS_100', 99), ('CREDITS_500', 399), ('CREDITS_1500', 999)) AS v(product_code, amount_minor)
JOIN billing_products bp ON bp.product_code = v.product_code
ON CONFLICT (billing_product_id, market_country_code, sales_channel, currency_code, billing_period, renewal_model,
             price_version) DO NOTHING;

-- 4. What each market sells (owner decision 2026-11-24). One row per (market, offering); a market without a row for an
--    offering inherits the ZZ row (the default for every country, the owner's '*'), and with no ZZ row either the
--    offering is OFF (fail closed). Enforced in the catalogue (GET /billing/options, /billing/products, which also
--    feeds order pricing) and therefore at order creation (422 PRODUCT_NOT_AVAILABLE). It never touches existing
--    subscriptions, credit lots or entitlements (they do not depend on the country). revision: N12 optimistic
--    concurrency per market for PUT /admin/v1/billing/market-offerings/{countryCode}.
CREATE TABLE billing_market_offerings (
    market_country_code     CHAR(2) NOT NULL CHECK (market_country_code ~ '^[A-Z]{2}$'),
    offering                VARCHAR(20) NOT NULL CHECK (offering IN ('SUBSCRIPTIONS', 'AI_CREDITS')),
    is_enabled              BOOLEAN NOT NULL,
    revision                INTEGER NOT NULL DEFAULT 1 CHECK (revision > 0),
    notes                   TEXT,
    updated_by_user_id      UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (market_country_code, offering)
);

COMMENT ON TABLE billing_market_offerings IS
    'T8.10: product families a market sells (SUBSCRIPTIONS, AI_CREDITS). Missing row -> the ZZ row -> off. '
    'Catalogue and order gate only; never affects existing subscriptions, lots or entitlements.';

-- Defaults that keep today's behaviour: every market sells subscriptions as before; credits only where packs are
-- priced (ET). ET is NOT switched to credits-only here: the owner does that through the admin API.
INSERT INTO billing_market_offerings (market_country_code, offering, is_enabled, notes)
VALUES
    ('ZZ', 'SUBSCRIPTIONS', TRUE, 'T8.10 default: every market sells plans (behaviour before T8.10).'),
    ('ZZ', 'AI_CREDITS', FALSE, 'T8.10 default: credit packs only where a market enables them.'),
    ('ET', 'AI_CREDITS', TRUE, 'T8.10: ETB pack prices seeded (owner decision 2026-11-24).')
ON CONFLICT (market_country_code, offering) DO NOTHING;

-- The rule above as one function, so the catalogue (billing) and the QUOTA_EXCEEDED hint (usage) cannot drift apart.
-- ADD_ON products have no offering yet and are never offered (fail closed).
CREATE OR REPLACE FUNCTION fn_billing_offering_enabled(p_market CHAR(2), p_product_type VARCHAR)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT COALESCE(
        (SELECT o.is_enabled FROM billing_market_offerings o
          WHERE o.market_country_code = p_market
            AND o.offering = CASE p_product_type WHEN 'SUBSCRIPTION' THEN 'SUBSCRIPTIONS'
                                                 WHEN 'AI_CREDIT_PACK' THEN 'AI_CREDITS' END),
        (SELECT o.is_enabled FROM billing_market_offerings o
          WHERE o.market_country_code = 'ZZ'
            AND o.offering = CASE p_product_type WHEN 'SUBSCRIPTION' THEN 'SUBSCRIPTIONS'
                                                 WHEN 'AI_CREDIT_PACK' THEN 'AI_CREDITS' END),
        FALSE)
$$;
