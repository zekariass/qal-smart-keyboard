-- ADR 0034 (follow-up): Arabic ('ar') becomes a typing language and an AI language on the server.
--
-- 1. The baseline catalogue already lists ar (Arabic, العربية, Arab, RTL) as PLANNED. It is listed and accepted in
--    /me/typing-languages like every row of the catalogue, but it was never offered as a supported language. It is
--    raised to BETA like the launch languages (its quality gates, SD 13, are not passed any further than theirs) and
--    sorted right after English (40), before the planned expansion languages (so = 50). It is NOT a launch language:
--    nothing needs the flag (it only marks the Ethiopian launch set, and no client or job reads it), and Arabic is an
--    additional language the owner asked for after the launch set was fixed. Writing direction and script are
--    already RTL / Arab and stay; is_active is set explicitly so activation never depends on the column default.
--
-- 2. AI capabilities for ar, written like V10, V19 and V20 (BETA, auto-detection on, the same character limits):
--      TRANSLATE en <-> ar            openai/openai-llm and google-translate/google-nmt (D46), 5000 characters;
--                                     mock/mock-mt-1 and wiremock-http/mock-mt-1 like V10 (dev and test stand-ins)
--      GRAMMAR_CORRECTION, REWRITE, TONE_ADJUST      (V10, V19)  language of the text = ar, destination NULL, 5000
--      SUMMARIZE, EXPLAIN_TEXT                       (V20)       5000
--      SMART_REPLY 6000, DRAFT_DOCUMENT 12000                    (V20)
--    on openai/openai-llm and on mock/wiremock-http mock-llm-1. Google never serves transforms (D46). The routing
--    policies of V19/V20 are any-language rows, so no policy is added; providers stay DISABLED, nothing here makes a
--    call possible on its own. The prompt templates are language '*' and need no row.
--    Idempotent: ux_ai_language_capability makes a repeated insert a no-op.

-- --------------------------------------------------------------------------------------------- 1. catalogue
UPDATE supported_languages
   SET support_level = 'BETA', is_active = TRUE, sort_order = 45, updated_at = NOW()
 WHERE language_code = 'ar';

-- --------------------------------------------------------------------------------------------- 2. capabilities
INSERT INTO ai_language_capabilities
    (model_id, feature_code, source_language_id, destination_language_id, is_enabled, supports_auto_detection,
     quality_tier, max_input_characters)
SELECT md.id, 'TRANSLATE', s.id, d.id, TRUE, TRUE, 'BETA', 5000
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id
 CROSS JOIN supported_languages s
 CROSS JOIN supported_languages d
 WHERE ((p.provider_code = 'openai' AND md.model_code = 'openai-llm')
     OR (p.provider_code = 'google-translate' AND md.model_code = 'google-nmt')
     OR (p.provider_code IN ('mock', 'wiremock-http') AND md.model_code = 'mock-mt-1'))
   AND ((s.language_code = 'en' AND d.language_code = 'ar')
     OR (d.language_code = 'ar' AND s.language_code = 'en')
     OR (s.language_code = 'ar' AND d.language_code = 'en'))
ON CONFLICT DO NOTHING;

INSERT INTO ai_language_capabilities
    (model_id, feature_code, source_language_id, destination_language_id, is_enabled, supports_auto_detection,
     quality_tier, max_input_characters)
SELECT md.id, f.feature_code, s.id, NULL, TRUE, TRUE, 'BETA', f.max_chars
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id
 CROSS JOIN (VALUES ('GRAMMAR_CORRECTION', 5000), ('REWRITE', 5000), ('TONE_ADJUST', 5000),
                    ('SUMMARIZE', 5000), ('EXPLAIN_TEXT', 5000), ('SMART_REPLY', 6000), ('DRAFT_DOCUMENT', 12000))
       AS f (feature_code, max_chars)
 CROSS JOIN supported_languages s
 WHERE ((p.provider_code = 'openai' AND md.model_code = 'openai-llm')
     OR (p.provider_code IN ('mock', 'wiremock-http') AND md.model_code = 'mock-llm-1'))
   AND s.language_code = 'ar'
ON CONFLICT DO NOTHING;
