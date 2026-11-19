-- ADR 0027 follow-up (2026-11-19): prompt templates v2 for DRAFT_DOCUMENT and SMART_REPLY.
--
-- The first real-provider smoke test (gpt-6-luna, reasoning effort none) showed two v1 template faults:
--   1. DRAFT_DOCUMENT NEW: "use them in the greeting and the signature" let the model greet the SENDER by name
--      ("Hi Abebe") and leave the signature as [your name].
--   2. SMART_REPLY: the model separated the replies by blank lines instead of '---' lines (the parser now also reads
--      blank-line separated short paragraphs, but the template should still ask clearly).
-- v2 is v1 with those sentences rewritten; v1 stays in the table (inactive) so feedback and history rows that point at
-- it keep their meaning. One active row per (feature, scope, family) is enforced by ux_ai_prompt_one_active_scope, so
-- v1 is switched off before v2 is inserted.

UPDATE ai_prompt_templates
   SET is_active = FALSE
 WHERE feature_code IN ('DRAFT_DOCUMENT', 'SMART_REPLY')
   AND template_version = 1
   AND language_scope = '*'
   AND model_family = '*';

INSERT INTO ai_prompt_templates
    (feature_code, template_version, language_scope, model_family, template_body, evaluation_status, is_active)
SELECT feature_code, 2, language_scope, model_family,
       CASE feature_code
           WHEN 'DRAFT_DOCUMENT' THEN replace(template_body,
               'Further messages may give the recipient''s name, the recipient''s role and the sender''s name: use them in the greeting and the signature.',
               'Further messages may give the recipient''s name, the recipient''s role and the sender''s name. The recipient is the person the e-mail is addressed to: use the recipient''s name in the greeting; without it, use a greeting without a name that fits the tone (or [recipient name] when a formal greeting needs one). The sender is the user who writes the e-mail: use the sender''s name only in the sign-off, never in the greeting, and never address the e-mail to the sender. In the user''s notes, "I", "me" and "my" mean the sender, and a person the user mentions ("my boss", "the teacher", "a colleague") is usually the recipient.')
           ELSE replace(template_body,
               'Output format: exactly {{count}} replies separated by a line that contains only ---',
               'Output format: exactly {{count}} replies, with a line that contains only --- between one reply and the next (also when the replies are short). Do not number the replies and write nothing before the first or after the last reply.')
       END,
       'APPROVED', TRUE
  FROM ai_prompt_templates
 WHERE feature_code IN ('DRAFT_DOCUMENT', 'SMART_REPLY')
   AND template_version = 1
   AND language_scope = '*'
   AND model_family = '*'
ON CONFLICT DO NOTHING;
