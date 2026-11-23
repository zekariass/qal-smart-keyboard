-- Extended tools feature catalogue (plan T8.1, ADR 0048, requirements Implementation_notes.md).
--
-- Sixteen new canonical codes (Architecture Review 5.1): five TEXT_AI writing tools, the new MEDIA_AI
-- category for P3 sketch/image tasks, five PLATFORM boolean gates for the premium tier of the on-device
-- creative tools, and the always-free DRAW_SKETCH marker. Entitlement seeds carry the PROVISIONAL_V2
-- quotas of SD 15.2; credit_cost_per_unit values are provisional pending AI cost modelling, like V1's.
-- V1 is never edited: the category CHECK is extended in place and the SeedSql test helper reads V1,
-- V18 and this file.

-- MEDIA_AI is a new category; the baseline CHECK names itself feature_catalog_category_check.
ALTER TABLE feature_catalog DROP CONSTRAINT feature_catalog_category_check;
ALTER TABLE feature_catalog
    ADD CONSTRAINT feature_catalog_category_check
    CHECK (category IN ('TYPING', 'TEXT_AI', 'VOICE', 'REALTIME', 'PLATFORM', 'MEDIA_AI'));

INSERT INTO feature_catalog
    (feature_code, display_name, category, metering_unit, is_entitlement_gated, credit_cost_per_unit, release_phase, description)
VALUES
    ('FORMAT_TEXT',             'Text formatting',           'TEXT_AI',  'REQUEST', TRUE,  1, 'P1', 'Notes to bullets, numbered lists, paragraphs, reports and meeting notes.'),
    ('AI_CHAT',                 'Writing assistant chat',    'TEXT_AI',  'REQUEST', TRUE,  2, 'P2', 'Stateless writing-assistant chat; the client supplies the visible turns.'),
    ('SOCIAL_POST',             'Social media posts',        'TEXT_AI',  'REQUEST', TRUE,  2, 'P2', 'Platform-aware posts, captions and hashtags.'),
    ('FORM_ASSIST',             'Form-filling assistant',    'TEXT_AI',  'REQUEST', TRUE,  2, 'P2', 'Application and form answers; always flagged verify-before-relying (R-7).'),
    ('OCR_SCAN',                'Text scanner (OCR)',        'TEXT_AI',  'REQUEST', TRUE,  2, 'P2', 'Server OCR including Ethiopic script, handwriting and complex layouts.'),
    ('SKETCH_ENHANCE',          'Sketch enhancement',        'MEDIA_AI', 'REQUEST', TRUE,  3, 'P3', 'AI cleanup and enhancement of a user sketch.'),
    ('DOODLE_TO_IMAGE',         'Doodle to image',           'MEDIA_AI', 'REQUEST', TRUE,  3, 'P3', 'Sketch to generated image.'),
    ('HANDWRITING_RECOGNITION', 'Handwriting recognition',   'MEDIA_AI', 'REQUEST', TRUE,  2, 'P3', 'Handwritten text to typed text via /ai/ocr HANDWRITING mode.'),
    ('AI_STICKER_GEN',          'AI sticker generation',     'MEDIA_AI', 'REQUEST', TRUE,  3, 'P3', 'AI-generated stickers.'),
    ('AI_IMAGE_GEN',            'AI image generation',       'MEDIA_AI', 'REQUEST', TRUE,  3, 'P3', 'AI card designs and general image generation.'),
    ('DRAW_SKETCH',             'Draw and sketch',           'PLATFORM', 'NONE',    FALSE, 0, 'P1', 'On-device drawing canvas. Always free (R-8).'),
    ('STICKER_MAKER',           'Sticker maker',             'PLATFORM', 'NONE',    TRUE,  0, 'P2', 'Premium sticker packs; basic sticker creation is free.'),
    ('GIF_MAKER',               'GIF maker',                 'PLATFORM', 'NONE',    TRUE,  0, 'P2', 'Premium editing features; basic GIF creation is free.'),
    ('CARD_MAKER',              'Card maker',                'PLATFORM', 'NONE',    TRUE,  0, 'P2', 'Premium templates; basic cards are free.'),
    ('IMAGE_ANNOTATOR',         'Image annotator',           'PLATFORM', 'NONE',    TRUE,  0, 'P2', 'Advanced annotation tools; basic annotation is free.'),
    ('QR_GENERATE',             'QR code generator',         'PLATFORM', 'NONE',    TRUE,  0, 'P1', 'Branded QR designs; basic QR codes are free.')
ON CONFLICT (feature_code) DO NOTHING;

WITH enabled_seed (plan_code, feature_code, included_units, reset_period) AS (
    VALUES
    -- FREE
    ('FREE', 'FORMAT_TEXT', 3::BIGINT, 'DAILY'),
    ('FREE', 'FORM_ASSIST', 3::BIGINT, 'DAILY'),
    ('FREE', 'OCR_SCAN',    3::BIGINT, 'DAILY'),

    -- PREMIUM
    ('PREMIUM', 'FORMAT_TEXT',             300::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'AI_CHAT',                 100::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'SOCIAL_POST',             100::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'FORM_ASSIST',             100::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'OCR_SCAN',                100::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'SKETCH_ENHANCE',          20::BIGINT,     'MONTHLY'),
    ('PREMIUM', 'DOODLE_TO_IMAGE',         20::BIGINT,     'MONTHLY'),
    ('PREMIUM', 'HANDWRITING_RECOGNITION', 30::BIGINT,     'MONTHLY'),
    ('PREMIUM', 'AI_STICKER_GEN',          30::BIGINT,     'MONTHLY'),
    ('PREMIUM', 'AI_IMAGE_GEN',            30::BIGINT,     'MONTHLY'),
    ('PREMIUM', 'STICKER_MAKER',           NULL::BIGINT,   'NONE'),
    ('PREMIUM', 'GIF_MAKER',               NULL::BIGINT,   'NONE'),
    ('PREMIUM', 'CARD_MAKER',              NULL::BIGINT,   'NONE'),
    ('PREMIUM', 'IMAGE_ANNOTATOR',         NULL::BIGINT,   'NONE'),
    ('PREMIUM', 'QR_GENERATE',             NULL::BIGINT,   'NONE'),

    -- PREMIUM_PLUS
    ('PREMIUM_PLUS', 'FORMAT_TEXT',             1000::BIGINT,   'MONTHLY'),
    ('PREMIUM_PLUS', 'AI_CHAT',                 400::BIGINT,    'MONTHLY'),
    ('PREMIUM_PLUS', 'SOCIAL_POST',             300::BIGINT,    'MONTHLY'),
    ('PREMIUM_PLUS', 'FORM_ASSIST',             300::BIGINT,    'MONTHLY'),
    ('PREMIUM_PLUS', 'OCR_SCAN',                300::BIGINT,    'MONTHLY'),
    ('PREMIUM_PLUS', 'SKETCH_ENHANCE',          60::BIGINT,     'MONTHLY'),
    ('PREMIUM_PLUS', 'DOODLE_TO_IMAGE',         60::BIGINT,     'MONTHLY'),
    ('PREMIUM_PLUS', 'HANDWRITING_RECOGNITION', 100::BIGINT,    'MONTHLY'),
    ('PREMIUM_PLUS', 'AI_STICKER_GEN',          100::BIGINT,    'MONTHLY'),
    ('PREMIUM_PLUS', 'AI_IMAGE_GEN',            100::BIGINT,    'MONTHLY'),
    ('PREMIUM_PLUS', 'STICKER_MAKER',           NULL::BIGINT,   'NONE'),
    ('PREMIUM_PLUS', 'GIF_MAKER',               NULL::BIGINT,   'NONE'),
    ('PREMIUM_PLUS', 'CARD_MAKER',              NULL::BIGINT,   'NONE'),
    ('PREMIUM_PLUS', 'IMAGE_ANNOTATOR',         NULL::BIGINT,   'NONE'),
    ('PREMIUM_PLUS', 'QR_GENERATE',             NULL::BIGINT,   'NONE'),

    -- BUSINESS (per seat)
    ('BUSINESS', 'FORMAT_TEXT',             2000::BIGINT,   'MONTHLY'),
    ('BUSINESS', 'AI_CHAT',                 1000::BIGINT,   'MONTHLY'),
    ('BUSINESS', 'SOCIAL_POST',             1000::BIGINT,   'MONTHLY'),
    ('BUSINESS', 'FORM_ASSIST',             500::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'OCR_SCAN',                1000::BIGINT,   'MONTHLY'),
    ('BUSINESS', 'SKETCH_ENHANCE',          200::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'DOODLE_TO_IMAGE',         200::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'HANDWRITING_RECOGNITION', 300::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'AI_STICKER_GEN',          300::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'AI_IMAGE_GEN',            300::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'STICKER_MAKER',           NULL::BIGINT,   'NONE'),
    ('BUSINESS', 'GIF_MAKER',               NULL::BIGINT,   'NONE'),
    ('BUSINESS', 'CARD_MAKER',              NULL::BIGINT,   'NONE'),
    ('BUSINESS', 'IMAGE_ANNOTATOR',         NULL::BIGINT,   'NONE'),
    ('BUSINESS', 'QR_GENERATE',             NULL::BIGINT,   'NONE')
)
INSERT INTO plan_entitlements
    (plan_id, feature_code, metering_unit, is_entitlement_gated, access_enabled, included_units, reset_period, configuration)
SELECT p.id, f.feature_code, f.metering_unit, TRUE, TRUE, e.included_units, e.reset_period,
       jsonb_build_object('quotaPolicyVersion', 'PROVISIONAL_V2', 'requiresServerEnforcement', TRUE)
FROM enabled_seed e
JOIN subscription_plans p ON p.plan_code = e.plan_code
JOIN feature_catalog f ON f.feature_code = e.feature_code
ON CONFLICT (plan_id, feature_code) DO NOTHING;

-- Explicit "disabled" rows for every other plan (same rule as V1/V18: every plan x gated feature has a row).
-- DRAW_SKETCH is ungated, so it gets no plan_entitlements rows at all (the binding CHECK forbids them).
INSERT INTO plan_entitlements
    (plan_id, feature_code, metering_unit, is_entitlement_gated, access_enabled, included_units, reset_period, configuration)
SELECT p.id, f.feature_code, f.metering_unit, TRUE, FALSE,
       CASE WHEN f.metering_unit = 'NONE' THEN NULL ELSE 0 END,
       'NONE',
       jsonb_build_object('quotaPolicyVersion', 'PROVISIONAL_V2', 'requiresServerEnforcement', TRUE)
FROM subscription_plans p
CROSS JOIN feature_catalog f
WHERE f.feature_code IN ('FORMAT_TEXT', 'AI_CHAT', 'SOCIAL_POST', 'FORM_ASSIST', 'OCR_SCAN',
                         'SKETCH_ENHANCE', 'DOODLE_TO_IMAGE', 'HANDWRITING_RECOGNITION',
                         'AI_STICKER_GEN', 'AI_IMAGE_GEN', 'STICKER_MAKER', 'GIF_MAKER',
                         'CARD_MAKER', 'IMAGE_ANNOTATOR', 'QR_GENERATE')
  AND p.plan_code IN ('GUEST', 'FREE', 'PREMIUM', 'PREMIUM_PLUS', 'BUSINESS')
ON CONFLICT (plan_id, feature_code) DO NOTHING;
