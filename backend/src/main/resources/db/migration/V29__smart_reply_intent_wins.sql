-- SMART_REPLY v4 (T8.13 device test, 2026-11-24): a preset intent must decide every reply's stance.
--
-- With v3 a message pasted from the clipboard (no notes) and the intent AGREE still produced one
-- declining reply out of three: the earlier rule "Without notes, give clearly different options, such as
-- agreeing, asking for a detail and politely declining" competed with the later intent line and the model
-- followed it. v4 = v3 with two edits:
--   1. the varied-options default applies only without notes AND without an intent;
--   2. the intent line says the replies then differ only in wording and detail, never in stance.
-- Same pattern as V28: switch the old version off (one active row per scope is enforced by
-- ux_ai_prompt_one_active_scope), insert the new one APPROVED and active; v3 stays for history/feedback rows.
-- The DO block fails the migration if either replace() matched nothing (v4 would silently equal v3).

UPDATE ai_prompt_templates
   SET is_active = FALSE
 WHERE feature_code = 'SMART_REPLY' AND template_version = 3
   AND language_scope = '*' AND model_family = '*' AND is_active;

INSERT INTO ai_prompt_templates
    (feature_code, template_version, language_scope, model_family, template_body, evaluation_status, is_active)
SELECT feature_code, 4, language_scope, model_family,
       replace(replace(template_body,
           'Without notes, give useful, clearly different options, such as agreeing, asking for a detail and politely declining.',
           'Without notes and without an intent, give useful, clearly different options, such as agreeing, asking for a detail and politely declining.'),
           'When an intent is set, every reply takes that stance; the user''s own notes still apply on top of it.',
           'When an intent is set, every reply takes that stance and the replies differ only in wording and detail: never add a reply with another stance (with AGREE or ACCEPT_INVITATION no reply declines; with DECLINE_POLITELY none accepts). The user''s own notes still apply on top of it.'),
       'APPROVED', TRUE
  FROM ai_prompt_templates
 WHERE feature_code = 'SMART_REPLY' AND template_version = 3
   AND language_scope = '*' AND model_family = '*'
   AND NOT EXISTS (SELECT 1 FROM ai_prompt_templates x WHERE x.feature_code = 'SMART_REPLY' AND x.is_active
                     AND x.language_scope = '*' AND x.model_family = '*')
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ai_prompt_templates
                    WHERE feature_code = 'SMART_REPLY' AND template_version = 4 AND is_active
                      AND language_scope = '*' AND model_family = '*'
                      AND template_body LIKE '%Without notes and without an intent%'
                      AND template_body LIKE '%never add a reply with another stance%') THEN
        RAISE EXCEPTION 'V29: SMART_REPLY v4 was not derived from v3 (template text did not match)';
    END IF;
END $$;
