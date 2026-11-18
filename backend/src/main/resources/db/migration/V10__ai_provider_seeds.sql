-- B5.1 (ADR 0014): seed the two development stand-in providers, INACTIVE.
--
-- V1 created the AI registry tables empty. No real vendor is seeded: the provider shortlist is decided at human gate G0
-- (ADR 0001 OI-R1, OI-P5) and each vendor is added by its own migration/operator action together with its adapter.
--
--   mock           in-process deterministic adapter (MockAiProvider), dev and test only
--   wiremock-http  HTTP adapter pointed at the local compose WireMock stubs (HttpJsonAiProvider), dev and test only
--
-- Both rows are DISABLED ("inactive" in the plan; the V1 status vocabulary is ACTIVE/DEGRADED/DISABLED): nothing routes
-- to them until an operator enables them. Their adapter beans exist only when qal.ai.mock.enabled / qal.ai.http.enabled
-- is set, which the application refuses under the prod/production/staging profiles, so even an enabled row cannot be
-- routed in production (a row without an adapter is never routable). Data stays on the developer machine, hence ZDR and
-- no-training are TRUE; dpa_signed_at stays NULL, so enabling still needs the ck_ai_provider_active_contract steps and
-- the second admin of implementation note 12 where applicable. Models are ACTIVE so the provider flag is the one gate.
-- Capabilities are BETA so development pipelines find a route with the default minimum tier; they say nothing about
-- real quality.

INSERT INTO ai_providers
    (provider_code, display_name, provider_type, provider_status, zero_data_retention, trains_on_customer_data,
     processing_regions, configuration)
VALUES
    ('mock',          'Mock AI provider (dev/test)',        'MULTI', 'DISABLED', TRUE, FALSE, ARRAY['local'],
     '{"environments": ["local", "dev", "test"], "adapter": "MockAiProvider"}'::JSONB),
    ('wiremock-http', 'WireMock HTTP AI stubs (dev/test)',  'MULTI', 'DISABLED', TRUE, FALSE, ARRAY['local'],
     '{"environments": ["local", "dev", "test"], "adapter": "HttpJsonAiProvider"}'::JSONB)
ON CONFLICT (provider_code) DO NOTHING;

INSERT INTO ai_models (provider_id, model_code, display_name, model_kind, model_status)
SELECT p.id, m.model_code, m.display_name, m.model_kind, 'ACTIVE'
  FROM ai_providers p
 CROSS JOIN (VALUES
        ('mock-mt-1',  'Mock machine translation', 'MT'),
        ('mock-llm-1', 'Mock LLM transform',       'LLM'),
        ('mock-lid-1', 'Mock language identifier', 'LID')
    ) AS m (model_code, display_name, model_kind)
 WHERE p.provider_code IN ('mock', 'wiremock-http')
ON CONFLICT (provider_id, model_code) DO NOTHING;

-- Translation: Amharic, Tigrinya and Afaan Oromoo to and from English, auto-detection of the source supported.
INSERT INTO ai_language_capabilities
    (model_id, feature_code, source_language_id, destination_language_id, is_enabled, supports_auto_detection,
     quality_tier, max_input_characters)
SELECT md.id, 'TRANSLATE', s.id, d.id, TRUE, TRUE, 'BETA', 5000
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id AND p.provider_code IN ('mock', 'wiremock-http')
 CROSS JOIN supported_languages s
 CROSS JOIN supported_languages d
 WHERE md.model_code = 'mock-mt-1'
   AND ((s.language_code = 'en' AND d.language_code IN ('am', 'ti', 'om'))
     OR (d.language_code = 'en' AND s.language_code IN ('am', 'ti', 'om')))
ON CONFLICT DO NOTHING;

-- LLM transforms: language of the text in source_language_id, destination NULL.
INSERT INTO ai_language_capabilities
    (model_id, feature_code, source_language_id, destination_language_id, is_enabled, supports_auto_detection,
     quality_tier, max_input_characters)
SELECT md.id, f.feature_code, s.id, NULL, TRUE, TRUE, 'BETA', 5000
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id AND p.provider_code IN ('mock', 'wiremock-http')
 CROSS JOIN (VALUES ('GRAMMAR_CORRECTION'), ('REWRITE'), ('TONE_ADJUST')) AS f (feature_code)
 CROSS JOIN supported_languages s
 WHERE md.model_code = 'mock-llm-1'
   AND s.language_code IN ('am', 'ti', 'om', 'en')
ON CONFLICT DO NOTHING;

-- Language identification: any language, no destination.
INSERT INTO ai_language_capabilities
    (model_id, feature_code, source_language_id, destination_language_id, is_enabled, supports_auto_detection,
     quality_tier, max_input_characters)
SELECT md.id, 'DETECT_LANGUAGE', NULL, NULL, TRUE, TRUE, 'BETA', 1000
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id AND p.provider_code IN ('mock', 'wiremock-http')
 WHERE md.model_code = 'mock-lid-1'
ON CONFLICT DO NOTHING;
