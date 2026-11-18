-- Premium keyboard themes: a boolean, unmetered, entitlement-gated PLATFORM feature.
--
-- The keyboard (client) decides which theme it draws; this feature only says whether the account may use the
-- premium ones. It is granted to PREMIUM and PREMIUM_PLUS and carried in the entitlement token as an offline gate
-- (qal.entitlements.token.offline-features), so a keyboard that is offline keeps its premium theme inside the
-- token's offline grace (ADR 0013). V1 is never edited: the SeedSql test helper reads V1 and this file.

INSERT INTO feature_catalog
    (feature_code, display_name, category, metering_unit, is_entitlement_gated, credit_cost_per_unit, release_phase, description)
VALUES
    ('PREMIUM_THEMES', 'Premium keyboard themes', 'PLATFORM', 'NONE', TRUE, 0, 'P2',
     'Premium keyboard colour themes; the client falls back to a free theme without it.')
ON CONFLICT (feature_code) DO NOTHING;

WITH enabled_seed (plan_code, feature_code, included_units, reset_period) AS (
    VALUES
    ('PREMIUM',      'PREMIUM_THEMES', NULL::BIGINT, 'NONE'),
    ('PREMIUM_PLUS', 'PREMIUM_THEMES', NULL::BIGINT, 'NONE')
)
INSERT INTO plan_entitlements
    (plan_id, feature_code, metering_unit, is_entitlement_gated, access_enabled, included_units, reset_period, configuration)
SELECT p.id, f.feature_code, f.metering_unit, TRUE, TRUE, e.included_units, e.reset_period,
       jsonb_build_object('quotaPolicyVersion', 'PROVISIONAL_V2', 'requiresServerEnforcement', TRUE)
FROM enabled_seed e
JOIN subscription_plans p ON p.plan_code = e.plan_code
JOIN feature_catalog f ON f.feature_code = e.feature_code
ON CONFLICT (plan_id, feature_code) DO NOTHING;

-- Explicit "disabled" rows for every other plan (same rule as V1: every plan x gated feature has a row).
INSERT INTO plan_entitlements
    (plan_id, feature_code, metering_unit, is_entitlement_gated, access_enabled, included_units, reset_period, configuration)
SELECT p.id, f.feature_code, f.metering_unit, TRUE, FALSE, NULL, 'NONE',
       jsonb_build_object('quotaPolicyVersion', 'PROVISIONAL_V2', 'requiresServerEnforcement', TRUE)
FROM subscription_plans p
CROSS JOIN feature_catalog f
WHERE f.feature_code = 'PREMIUM_THEMES'
  AND p.plan_code IN ('GUEST', 'FREE', 'PREMIUM', 'PREMIUM_PLUS', 'BUSINESS')
ON CONFLICT (plan_id, feature_code) DO NOTHING;
