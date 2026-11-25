-- Plan step T8.10 follow-up (owner rule 2026-11-25: "all billing outside Ethiopia uses Google Play billing with
-- RevenueCat"): prepaid AI credit packs bought as Play CONSUMABLES through RevenueCat (ADR 0046, ADR 0048 section 4).
-- Ethiopia keeps the local gateways (V25/V31). Idempotent.
--
-- As in V26/V31 nothing becomes purchasable by this migration alone: the new store price rows and product mappings are
-- inactive, the packs stay as V31 left them (DRAFT), the ZZ AI_CREDITS offering stays OFF and the ZZ GOOGLE_PLAY policy
-- stays REVIEW_REQUIRED. Go-live is data (backend README "Store-billed credit packs: go-live").
--
-- Design (one table, not a linked one): a consumable is a store_purchases row like a subscription, because both need the
-- same provider, product mapping, environment, status and append-only store_purchase_events; what differs is
--   * purchase_type CONSUMABLE (subscriptions are SUBSCRIPTION, the default for every existing row);
--   * billing_product_id: the pack (AI_CREDIT_PACK) the mapping resolves to, fixed when the purchase is first verified;
--   * store_transaction_id: the store's transaction id (Play order id GPA.xxxx, RevenueCat store_transaction_id), unique
--     per provider. It is the idempotency key: the client report, the NON_RENEWING_PURCHASE webhook, replays and
--     concurrent calls all resolve to the same row (purchase_token_hash = sha256 of provider|CONSUMABLE|store|transaction,
--     also unique per provider);
--   * the credit lot it produced: ai_credit_lots.store_purchase_id (one lot per purchase, unique), never a
--     user_subscriptions row.
-- A consumable's status: PENDING (verified, not granted: the pack is not sellable in the mapping's market right now),
-- ACTIVE (granted), REFUNDED (store refund: the unused credits were reversed), REVOKED, EXPIRED (verified too late to
-- grant a lot that would still be valid).

-- 1. store_purchases: consumables.
ALTER TABLE store_purchases
    ADD COLUMN IF NOT EXISTS purchase_type VARCHAR(16) NOT NULL DEFAULT 'SUBSCRIPTION',
    ADD COLUMN IF NOT EXISTS billing_product_id UUID REFERENCES billing_products(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS store_transaction_id VARCHAR(255);

ALTER TABLE store_purchases DROP CONSTRAINT IF EXISTS ck_store_purchase_type;
ALTER TABLE store_purchases
    ADD CONSTRAINT ck_store_purchase_type CHECK (purchase_type IN ('SUBSCRIPTION', 'CONSUMABLE'));

ALTER TABLE store_purchases DROP CONSTRAINT IF EXISTS ck_store_purchase_consumable_fields;
ALTER TABLE store_purchases
    ADD CONSTRAINT ck_store_purchase_consumable_fields CHECK (
        (purchase_type = 'SUBSCRIPTION' AND store_transaction_id IS NULL)
        OR (purchase_type = 'CONSUMABLE' AND billing_product_id IS NOT NULL AND store_transaction_id IS NOT NULL
            AND auto_renewing = FALSE
            AND purchase_status IN ('PENDING', 'ACTIVE', 'EXPIRED', 'REVOKED', 'REFUNDED'))
    );

CREATE UNIQUE INDEX IF NOT EXISTS ux_store_purchase_consumable_transaction
    ON store_purchases (provider_id, store_transaction_id)
    WHERE purchase_type = 'CONSUMABLE';

COMMENT ON COLUMN store_purchases.purchase_type IS
    'T8.10 store packs: SUBSCRIPTION (ADR 0046, user_subscriptions) or CONSUMABLE (a credit pack, one ai_credit_lots row).';
COMMENT ON COLUMN store_purchases.store_transaction_id IS
    'CONSUMABLE only: the store transaction id (Play order id), unique per provider: the idempotency key of the grant.';

-- 2. A PURCHASE lot is backed by exactly one of: a paid gateway order (V1) or a verified store purchase. Never neither,
--    never both; other lot sources never point at a store purchase. One lot per store purchase. The lot id stays
--    deterministic (JdbcCredits: user | PURCHASE | "store:" + store purchase id), so a replayed grant hits the primary key.
ALTER TABLE ai_credit_lots
    ADD COLUMN IF NOT EXISTS store_purchase_id UUID REFERENCES store_purchases(id) ON DELETE RESTRICT;

ALTER TABLE ai_credit_lots DROP CONSTRAINT IF EXISTS ck_credit_lot_purchase_order;
ALTER TABLE ai_credit_lots DROP CONSTRAINT IF EXISTS ck_credit_lot_purchase_backing;
ALTER TABLE ai_credit_lots
    ADD CONSTRAINT ck_credit_lot_purchase_backing CHECK (
        (lot_source = 'PURCHASE' AND num_nonnulls(payment_order_id, store_purchase_id) = 1)
        OR (lot_source <> 'PURCHASE' AND store_purchase_id IS NULL)
    );

CREATE UNIQUE INDEX IF NOT EXISTS ux_credit_lot_store_purchase
    ON ai_credit_lots (store_purchase_id)
    WHERE store_purchase_id IS NOT NULL;

-- 3. Store identifiers of the packs (as V26 for the plans): a mapping needs a price row, so each pack gets an INACTIVE
--    STORE_MANAGED one-off row on the global market of the Play build (amount 0 USD: the store sets and charges the
--    price; the row is never sold through orders). Proposed Play in-app product ids (consumables; the Android step and
--    the owner create them in Play Console and RevenueCat): credits_100, credits_500, credits_1500. RevenueCat product
--    identifier = the Play product id; suggested RevenueCat offering "credits" with custom packages of the same names
--    (metadata, exposed to the app as Price.storeOfferingId / storePackageId). external_offer_id is NULL (in-app
--    products have no base plan). Mappings start inactive in both environments (go-live).
INSERT INTO billing_prices
    (billing_product_id, market_country_code, sales_channel, currency_code, amount_minor, tax_behavior,
     billing_period, renewal_model, price_version, is_active, metadata)
SELECT bp.id, 'ZZ', 'ANDROID_GOOGLE_PLAY', 'USD', 0, 'NOT_APPLICABLE', 'ONE_TIME', 'STORE_MANAGED', 1, FALSE,
       '{"storeManaged": true, "priceSetBy": "store", "amountMinorUnused": true, "storeProductType": "CONSUMABLE", "source": "T8.10 store packs"}'::JSONB
FROM billing_products bp
WHERE bp.product_code IN ('CREDITS_100', 'CREDITS_500', 'CREDITS_1500') AND bp.product_type = 'AI_CREDIT_PACK'
ON CONFLICT (billing_product_id, market_country_code, sales_channel, currency_code, billing_period, renewal_model,
             price_version) DO NOTHING;

INSERT INTO external_product_mappings
    (billing_price_id, provider_id, external_product_id, external_offer_id, environment, is_active, metadata)
SELECT pr.id, p.id, v.external_product_id, NULL, e.environment, FALSE,
       jsonb_build_object('source', 'T8.10 store packs', 'storeProductType', 'CONSUMABLE',
                          'revenueCatProductId', v.external_product_id, 'revenueCatOffering', 'credits',
                          'revenueCatPackage', v.external_product_id)
FROM (VALUES ('CREDITS_100', 'credits_100'), ('CREDITS_500', 'credits_500'), ('CREDITS_1500', 'credits_1500'))
     AS v(product_code, external_product_id)
JOIN billing_products bp ON bp.product_code = v.product_code
JOIN billing_prices pr ON pr.billing_product_id = bp.id AND pr.market_country_code = 'ZZ'
     AND pr.sales_channel = 'ANDROID_GOOGLE_PLAY' AND pr.currency_code = 'USD' AND pr.billing_period = 'ONE_TIME'
     AND pr.renewal_model = 'STORE_MANAGED' AND pr.price_version = 1
JOIN payment_providers p ON p.provider_code = 'REVENUECAT'
CROSS JOIN (VALUES ('SANDBOX'), ('PRODUCTION')) AS e(environment)
ON CONFLICT ON CONSTRAINT ux_external_product_mapping DO NOTHING;

-- The V31 GBP guide rows stay inactive references (never listed, never orderable): the store owns the price.
UPDATE billing_prices
   SET metadata = metadata || '{"storeConsumable": "credits_<units> through RevenueCat (V32)"}'::JSONB,
       updated_at = NOW()
 WHERE market_country_code = 'ZZ' AND sales_channel = 'ANDROID_GOOGLE_PLAY' AND currency_code = 'GBP'
   AND renewal_model = 'NONE' AND NOT is_active
   AND billing_product_id IN (SELECT id FROM billing_products WHERE product_type = 'AI_CREDIT_PACK');

-- 4. When a pack is sellable through a store, as one function so the catalogue (billing) and the QUOTA_EXCEEDED hint
--    (usage) cannot drift apart: the pack is ACTIVE, the market's offerings include AI credits (fn_billing_offering_enabled,
--    V31: the market's row, else ZZ, else off) and an ACTIVE store mapping exists on a STORE_MANAGED price row of the
--    market or of ZZ (the global store catalogue), for the sales channel and environment (NULL = any). The market/channel
--    policy (STORE_BILLING, approved) is checked by the caller (ProviderRouting), not here.
CREATE OR REPLACE FUNCTION fn_billing_store_pack_sellable(p_product UUID, p_market CHAR(2), p_sales_channel VARCHAR,
                                                          p_environment VARCHAR)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM billing_products bp
        JOIN billing_prices pr ON pr.billing_product_id = bp.id
        JOIN external_product_mappings m ON m.billing_price_id = pr.id
        WHERE bp.id = p_product
          AND bp.product_type = 'AI_CREDIT_PACK' AND bp.product_status = 'ACTIVE'
          AND pr.renewal_model = 'STORE_MANAGED' AND pr.billing_period = 'ONE_TIME'
          AND pr.market_country_code IN (p_market, 'ZZ')
          AND (p_sales_channel IS NULL OR pr.sales_channel = p_sales_channel)
          AND m.is_active AND (p_environment IS NULL OR m.environment = p_environment)
          AND fn_billing_offering_enabled(p_market, bp.product_type))
$$;
