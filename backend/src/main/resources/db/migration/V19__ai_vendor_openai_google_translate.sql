-- ADR 0007 D46, ADR 0023: the first real AI vendors, OpenAI and Google Cloud Translation, seeded DISABLED.
--
--   openai            OpenAiProvider: primary for TRANSLATE and the LLM transforms GRAMMAR_CORRECTION, REWRITE, TONE_ADJUST
--   google-translate  GoogleTranslateProvider (Cloud Translation - Basic, v2): TRANSLATE fallback only, never transforms
--
-- Nothing here makes a call possible on its own:
--   * provider_status = 'DISABLED'. Enabling needs dpa_signed_at (ck_ai_provider_active_contract) and the adapter bean,
--     which exists only when QAL_OPENAI_API_KEY (+ QAL_OPENAI_MODEL) / QAL_GOOGLE_TRANSLATE_API_KEY is set.
--   * the routing policies are seeded is_enabled = FALSE. An enabled policy is an allow-list for its feature (ADR 0014
--     "Routing"), so enabled rows naming only these vendors would starve the dev/test mock providers; the operator
--     enables them together with the providers.
--
-- Data policy (confirm against the signed terms before enabling):
--   * openai: zero_data_retention FALSE until OpenAI grants ZDR to the organisation (requested by the product owner);
--     the adapter still sends store=false on every call. trains_on_customer_data FALSE (API data is not used for
--     training by default per OpenAI's API data-usage terms). processing_regions {us}: default API processing.
--   * google-translate: zero_data_retention TRUE. Google's Cloud Translation data-usage page says the text "is held
--     briefly in-memory in order to perform the translation" and is not used to train or improve its models.
--     processing_regions {global}: the v2 endpoint offers no region choice, so any organisation region allow-list
--     excludes it (fail closed).
--
-- Model rows. ai_models.model_code is the registry's handle, not the vendor model id: the OpenAI model is configuration
-- (QAL_OPENAI_MODEL), so one row 'openai-llm' stands for whatever model is configured, and changing the model needs no
-- migration. The adapters report these codes in ProviderCallMetadata so ai_requests.model_id resolves.
-- configuration.modelFamily selects prompt templates (ai_prompt_templates.model_family, ADR 0017).
--
-- Capabilities are BETA (the tier V10 used; BETA is user-exposed with the QUALITY note): D46 still requires the
-- eval harness and native speakers per pair before a pair is marketed (OI-R1), and an operator demotes or promotes rows
-- on that evidence. Google language detection is EXPERIMENTAL (not routed with the default minimum tier) because
-- Amharic vs Tigrinya detection has not been evaluated.
--
-- No prices or budgets are seeded (V12: unpriced = free, unbudgeted = uncapped). OpenAI prices depend on the configured
-- model; set ai_provider_prices (INPUT_TOKEN / OUTPUT_TOKEN for openai, INPUT_CHARACTER for google-translate) and an
-- ai_provider_budgets hard limit from the vendors' current price lists when enabling (ADR 0023).

INSERT INTO ai_providers
    (provider_code, display_name, provider_type, provider_status, zero_data_retention, trains_on_customer_data,
     processing_regions, configuration)
VALUES
    ('openai',           'OpenAI',                             'LLM',         'DISABLED', FALSE, FALSE, ARRAY['us'],
     '{"adapter": "OpenAiProvider", "api": "chat-completions", "store": false, "modelFrom": "QAL_OPENAI_MODEL", "keyFrom": "QAL_OPENAI_API_KEY"}'::JSONB),
    ('google-translate', 'Google Cloud Translation (Basic v2)', 'TRANSLATION', 'DISABLED', TRUE,  FALSE, ARRAY['global'],
     '{"adapter": "GoogleTranslateProvider", "api": "translate-v2", "keyFrom": "QAL_GOOGLE_TRANSLATE_API_KEY"}'::JSONB)
ON CONFLICT (provider_code) DO NOTHING;

INSERT INTO ai_models (provider_id, model_code, display_name, model_kind, model_status, configuration)
SELECT p.id, m.model_code, m.display_name, m.model_kind, 'ACTIVE', m.configuration::JSONB
  FROM ai_providers p
  JOIN (VALUES
        ('openai',           'openai-llm', 'OpenAI model from QAL_OPENAI_MODEL',      'LLM', '{"modelFamily": "openai"}'),
        ('google-translate', 'google-nmt', 'Google Cloud Translation NMT (Basic v2)', 'MT',  '{"modelFamily": "google-nmt"}'),
        ('google-translate', 'google-lid', 'Google Cloud Translation detect (v2)',    'LID', '{"modelFamily": "google-lid"}')
    ) AS m (provider_code, model_code, display_name, model_kind, configuration) ON m.provider_code = p.provider_code
ON CONFLICT (provider_id, model_code) DO NOTHING;

-- TRANSLATE: Amharic, Tigrinya and Afaan Oromoo to and from English on both vendors, source auto-detection supported
-- (Google reports the detected language; OpenAI translates from whatever the text is and reports none).
INSERT INTO ai_language_capabilities
    (model_id, feature_code, source_language_id, destination_language_id, is_enabled, supports_auto_detection,
     quality_tier, max_input_characters)
SELECT md.id, 'TRANSLATE', s.id, d.id, TRUE, TRUE, 'BETA', 5000
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id
 CROSS JOIN supported_languages s
 CROSS JOIN supported_languages d
 WHERE ((p.provider_code = 'openai' AND md.model_code = 'openai-llm')
     OR (p.provider_code = 'google-translate' AND md.model_code = 'google-nmt'))
   AND ((s.language_code = 'en' AND d.language_code IN ('am', 'ti', 'om'))
     OR (d.language_code = 'en' AND s.language_code IN ('am', 'ti', 'om')))
ON CONFLICT DO NOTHING;

-- LLM transforms: OpenAI only (D46: Google is never used for transforms). Language of the text in source_language_id.
INSERT INTO ai_language_capabilities
    (model_id, feature_code, source_language_id, destination_language_id, is_enabled, supports_auto_detection,
     quality_tier, max_input_characters)
SELECT md.id, f.feature_code, s.id, NULL, TRUE, TRUE, 'BETA', 5000
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id AND p.provider_code = 'openai'
 CROSS JOIN (VALUES ('GRAMMAR_CORRECTION'), ('REWRITE'), ('TONE_ADJUST')) AS f (feature_code)
 CROSS JOIN supported_languages s
 WHERE md.model_code = 'openai-llm'
   AND s.language_code IN ('am', 'ti', 'om', 'en')
ON CONFLICT DO NOTHING;

-- Language identification on Google: EXPERIMENTAL, so not routed until evaluated and promoted.
INSERT INTO ai_language_capabilities
    (model_id, feature_code, source_language_id, destination_language_id, is_enabled, supports_auto_detection,
     quality_tier, max_input_characters)
SELECT md.id, 'DETECT_LANGUAGE', NULL, NULL, TRUE, TRUE, 'EXPERIMENTAL', 1000
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id AND p.provider_code = 'google-translate'
 WHERE md.model_code = 'google-lid'
ON CONFLICT DO NOTHING;

-- Routing (D46): TRANSLATE any pair -> openai first, google-translate second; transforms -> openai only. Disabled
-- until the operator enables them with the providers (see the header). Any-language rows: a narrower row added later
-- for one pair replaces these for that pair.
INSERT INTO ai_routing_policies
    (feature_code, source_language_id, destination_language_id, model_id, execution_target, priority, traffic_weight,
     requires_zero_retention, is_enabled, notes)
SELECT r.feature_code, NULL, NULL, md.id, 'SERVER', r.priority, 100, FALSE, FALSE, r.notes
  FROM (VALUES
        ('TRANSLATE',          'openai',           'openai-llm', 1, 'ADR 0007 D46: OpenAI primary for translation'),
        ('TRANSLATE',          'google-translate', 'google-nmt', 2, 'ADR 0007 D46: Google Cloud Translation fallback'),
        ('GRAMMAR_CORRECTION', 'openai',           'openai-llm', 1, 'ADR 0007 D46: OpenAI for LLM transforms'),
        ('REWRITE',            'openai',           'openai-llm', 1, 'ADR 0007 D46: OpenAI for LLM transforms'),
        ('TONE_ADJUST',        'openai',           'openai-llm', 1, 'ADR 0007 D46: OpenAI for LLM transforms')
    ) AS r (feature_code, provider_code, model_code, priority, notes)
  JOIN ai_providers p ON p.provider_code = r.provider_code
  JOIN ai_models md ON md.provider_id = p.id AND md.model_code = r.model_code
 WHERE NOT EXISTS (SELECT 1 FROM ai_routing_policies x WHERE x.model_id = md.id AND x.feature_code = r.feature_code
                     AND x.source_language_id IS NULL AND x.destination_language_id IS NULL);
