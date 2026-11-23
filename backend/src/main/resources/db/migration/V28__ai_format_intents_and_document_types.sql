-- ADR 0048 (T8.2): the FORMAT_TEXT transform task, smart-reply intents, four new contract tones
-- (ROMANTIC, CONFIDENT, FUNNY, PERSUASIVE) and four newly served document types
-- (JOB_APPLICATION, CV, COVER_LETTER, BUSINESS_MESSAGE).
--
-- 1. Capabilities (BETA, V20's shape) for FORMAT_TEXT: openai/openai-llm and the dev/test stand-ins
--    mock + wiremock-http mock-llm-1; the V23 language set (am, ti, om, en, ar), destination NULL and
--    auto-detection on like the other transforms; max_input_characters 5000 (the transform text limit).
--    The providers stay as they were (DISABLED): nothing here makes a call possible on its own.
--
-- 2. Routing policy: openai priority 1 for FORMAT_TEXT, seeded is_enabled = FALSE for V19/V20's reason
--    (an enabled policy is an allow-list and would starve the dev/test mock providers).
--
-- 3. Prompt templates (one active row per (feature, '*', '*') is enforced by
--    ux_ai_prompt_one_active_scope, so the previous versions are switched off first):
--      FORMAT_TEXT v1   new; seeded APPROVED and active under V20's exception: the output shape is a
--                       contract format the instructions must ask for, and the no-added-facts rule is
--                       a guardrail the adapters' one-line fallbacks do not state.
--      TONE_ADJUST v2   v1 plus the descriptions of the four new tone values.
--      SMART_REPLY v3   v2 plus the intent line (a preset stance every reply takes; the user's notes
--                       still apply on top).
--      DRAFT_DOCUMENT v3 the v2 template generalised from "e-mail" to the five served document types:
--                       a CV has no greeting, subject or sign-off; the letter types keep the e-mail
--                       structure. A literal body: the generalisation touches too many sentences for
--                       a chained replace.
--    Older versions stay in the table (feedback and history rows point at them). The FORMAT_TEXT row
--    is not inserted where an active template for (FORMAT_TEXT, '*', '*') already exists (V20's rule:
--    an environment that activated its own keeps it).

-- --------------------------------------------------------------------------------------------- 1. capabilities
INSERT INTO ai_language_capabilities
    (model_id, feature_code, source_language_id, destination_language_id, is_enabled, supports_auto_detection,
     quality_tier, max_input_characters)
SELECT md.id, 'FORMAT_TEXT', s.id, NULL, TRUE, TRUE, 'BETA', 5000
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id
 CROSS JOIN supported_languages s
 WHERE ((p.provider_code = 'openai' AND md.model_code = 'openai-llm')
     OR (p.provider_code IN ('mock', 'wiremock-http') AND md.model_code = 'mock-llm-1'))
   AND s.language_code IN ('am', 'ti', 'om', 'en', 'ar')
ON CONFLICT DO NOTHING;

-- --------------------------------------------------------------------------------------------- 2. routing
INSERT INTO ai_routing_policies
    (feature_code, source_language_id, destination_language_id, model_id, execution_target, priority, traffic_weight,
     requires_zero_retention, is_enabled, notes)
SELECT 'FORMAT_TEXT', NULL, NULL, md.id, 'SERVER', 1, 100, FALSE, FALSE, 'ADR 0048: OpenAI for text formatting'
  FROM ai_providers p
  JOIN ai_models md ON md.provider_id = p.id AND md.model_code = 'openai-llm'
 WHERE p.provider_code = 'openai'
   AND NOT EXISTS (SELECT 1 FROM ai_routing_policies x WHERE x.model_id = md.id
                     AND x.feature_code = 'FORMAT_TEXT'
                     AND x.source_language_id IS NULL AND x.destination_language_id IS NULL);

-- --------------------------------------------------------------------------------------------- 3. templates
UPDATE ai_prompt_templates
   SET is_active = FALSE
 WHERE language_scope = '*' AND model_family = '*' AND is_active
   AND ((feature_code = 'TONE_ADJUST' AND template_version = 1)
     OR (feature_code = 'SMART_REPLY' AND template_version = 2)
     OR (feature_code = 'DRAFT_DOCUMENT' AND template_version = 2));

-- FORMAT_TEXT v1 (new feature; the mock reads the "Format: ..." line back to shape its answer)
INSERT INTO ai_prompt_templates
    (feature_code, template_version, language_scope, model_family, template_body, evaluation_status, is_active)
SELECT 'FORMAT_TEXT', 1, '*', '*', $tmpl$
Task: format the text in message 1. Format: {{format}} (empty = BULLETS).
- BULLETS: a "- " bullet list, one point per item or fact.
- NUMBERED_LIST: a numbered list ("1. ", "2. ") when order or count matters.
- PARAGRAPHS: flowing paragraphs with no lists and no headings.
- REPORT: a short structured report with a title line and section headings.
- MEETING_NOTES: a title line, then sections for the points discussed, the decisions and the action items (with owner and date when the text gives them).
Rules:
- Use only information in the text. Never add facts, decisions, owners, dates or details of your own. When a section needs a detail the text does not give, put a short placeholder in square brackets, such as [owner] or [date].
- Keep every name, date, time, number, amount, link and e-mail address exactly as written.
- {{addresseeInstruction}}
- {{outputLanguageInstruction}} If that is not the language of the text, format and translate in one step.
- Amharic and Tigrinya in Ge'ez (Ethiopic) script; Afaan Oromoo in Qubee.
Return only the formatted text.
$tmpl$, 'APPROVED', TRUE
 WHERE NOT EXISTS (SELECT 1 FROM ai_prompt_templates x WHERE x.feature_code = 'FORMAT_TEXT' AND x.is_active
                     AND x.language_scope = '*' AND x.model_family = '*')
ON CONFLICT DO NOTHING;

-- TONE_ADJUST v2 = v1 plus the ADR 0048 tone values
INSERT INTO ai_prompt_templates
    (feature_code, template_version, language_scope, model_family, template_body, evaluation_status, is_active)
SELECT feature_code, 2, language_scope, model_family,
       replace(template_body,
           '- HONORIFIC: the most respectful register; in Amharic and Tigrinya use honorific pronouns and verb forms (Amharic እርስዎ, Tigrinya ንስኹም), as for elders, officials and clergy.',
           '- HONORIFIC: the most respectful register; in Amharic and Tigrinya use honorific pronouns and verb forms (Amharic እርስዎ, Tigrinya ንስኹም), as for elders, officials and clergy.
- ROMANTIC: warm, affectionate and personal, for a partner; never sleazy.
- CONFIDENT: self-assured and direct; no hedging, no apologies the text does not make.
- FUNNY: light, friendly humour and playful wording that keeps the message clear; never mocking the reader or the subject.
- PERSUASIVE: a clear case with reasons and one specific call to action, without exaggeration or pressure.'),
       'APPROVED', TRUE
  FROM ai_prompt_templates
 WHERE feature_code = 'TONE_ADJUST' AND template_version = 1
   AND language_scope = '*' AND model_family = '*'
   AND NOT EXISTS (SELECT 1 FROM ai_prompt_templates x WHERE x.feature_code = 'TONE_ADJUST' AND x.is_active
                     AND x.language_scope = '*' AND x.model_family = '*')
ON CONFLICT DO NOTHING;

-- SMART_REPLY v3 = v2 plus the intent line
INSERT INTO ai_prompt_templates
    (feature_code, template_version, language_scope, model_family, template_body, evaluation_status, is_active)
SELECT feature_code, 3, language_scope, model_family,
       replace(template_body,
           '- Write as the user, replying to the sender. Tone: {{tone}} (empty = natural for the conversation; HONORIFIC = respectful honorific forms).',
           '- Intent: {{intent}} (empty = no preset). AGREE = agree or say yes; DISAGREE_POLITELY = disagree courteously; ASK_CLARIFICATION = ask for the detail needed to answer; ACCEPT_INVITATION = accept the invitation; DECLINE_POLITELY = decline courteously; THANK_SENDER = thank the sender. When an intent is set, every reply takes that stance; the user''s own notes still apply on top of it.
- Write as the user, replying to the sender. Tone: {{tone}} (empty = natural for the conversation; HONORIFIC = respectful honorific forms).'),
       'APPROVED', TRUE
  FROM ai_prompt_templates
 WHERE feature_code = 'SMART_REPLY' AND template_version = 2
   AND language_scope = '*' AND model_family = '*'
   AND NOT EXISTS (SELECT 1 FROM ai_prompt_templates x WHERE x.feature_code = 'SMART_REPLY' AND x.is_active
                     AND x.language_scope = '*' AND x.model_family = '*')
ON CONFLICT DO NOTHING;

-- DRAFT_DOCUMENT v3: the v2 e-mail template generalised to the five served document types (literal body)
INSERT INTO ai_prompt_templates
    (feature_code, template_version, language_scope, model_family, template_body, evaluation_status, is_active)
SELECT 'DRAFT_DOCUMENT', 3, '*', '*', $tmpl$
Task: write a document for the user. Document type: {{documentType}}. Mode: {{mode}}. Follow only the description of this mode.
Document types:
- EMAIL: an e-mail.
- JOB_APPLICATION: a formal letter applying for a job: what the user offers, why they fit the position, a clear request for consideration.
- CV: a curriculum vitae: short sections (contact, summary, experience, education, skills) built only from the facts the user gave; no greeting and no sign-off.
- COVER_LETTER: a formal letter that accompanies a job application or a CV.
- BUSINESS_MESSAGE: a short formal message or memo to a client, a partner or an office.
Modes:
- NEW: message 1 is the user's description of what the document must communicate, possibly informal and possibly in another language than the document. Write a complete, well-structured document that says all of it.
- REPLY: message 1 is the e-mail or message the user received and message 2 holds the user's notes on what to answer, possibly in another language. Write the user's reply to that message: say what the notes say, answer the questions of the sender that the notes cover, and greet the sender by the name the message shows.
- IMPROVE: message 1 is the user's own draft; a further message may hold notes on what to change. Correct grammar and spelling and improve clarity, structure and tone while keeping the content and the intent.
Further messages may give the recipient's name, the recipient's role and the sender's name. The recipient is the person the document is addressed to: use the recipient's name in the greeting; without it, use a greeting without a name that fits the tone (or [recipient name] when a formal greeting needs one). The sender is the user who writes the document: use the sender's name only in the sign-off, never in the greeting, and never address the document to the sender. In the user's notes, "I", "me" and "my" mean the sender, and a person the user mentions ("my boss", "the teacher", "a colleague") is usually the recipient.
Rules for every mode:
- Facts: keep every name, date, time, number, amount, address, link and e-mail address exactly as the user gave it. Never invent facts, promises, deadlines, prices, attachments or commitments the user did not state. When the document needs a detail the user has not given (a date, a reference number, a name, a qualification), write a short placeholder in square brackets in the language of the document, such as [date] or [recipient name], instead of guessing.
- Meaning: express the user's intent faithfully; do not add requests, apologies, refusals or offers the user did not ask for.
- Tone: {{tone}}. PROFESSIONAL = clear, courteous, businesslike. FORMAL = official and respectful, for authorities, institutions and senior people. FRIENDLY = warm and personal, still polite. CASUAL = relaxed and conversational, for people the user knows well. POLITE = courteous and considerate, softening requests. PERSUASIVE = a clear case with reasons and one specific call to action, without exaggeration or pressure. APOLOGETIC = sincere responsibility and what will be done, only as far as the user's text goes. CONCISE = as short as possible while complete.
- {{addresseeInstruction}}
- Length: {{targetLength}} (SHORT = a few sentences; MEDIUM = two to four short paragraphs; LONG = fuller but focused). A CV follows its sections instead of paragraphs and may use "- " bullet lines.
- Structure for EMAIL, JOB_APPLICATION, COVER_LETTER and BUSINESS_MESSAGE: a greeting line; an opening sentence that states the purpose; the body in short paragraphs; a clear closing or next step; a sign-off and the sender's name, or [your name] when it is not known. A CV has no greeting and no sign-off: only section headings and bullet lines.
- Culture: the users are often Ethiopians writing to international recipients. Write natural, idiomatic text in the target language, not a word-for-word rendering of Amharic, Tigrinya or Afaan Oromoo phrasing, and keep the courtesy of the original without long formulas. Ethiopian names are a given name followed by the father's name: keep them as given and never invent a surname or a title.
- Dates and times: {{culturalInstruction}} Convert between the Ethiopian and Gregorian calendars or clocks only as these instructions say, check a conversion against today's date in both calendars, and when unsure keep the date as written and mark it (Ethiopian calendar). {{calendarContext}}
- {{outputLanguageInstruction}} Amharic and Tigrinya in Ge'ez (Ethiopic) script; Afaan Oromoo in Qubee.
- Variation {{variation}}: 0 = your best version; a higher number means the user asked to regenerate, so write a clearly different version (other wording and structure) with the same facts.
Output format for EMAIL, JOB_APPLICATION, COVER_LETTER and BUSINESS_MESSAGE: the first line is the subject line, then one blank line, then the body.
The subject line starts with the English label "Subject:" in every language, followed by a short, specific subject in the language of the document.
REPLY: write "Subject: Re: " and the original subject when message 1 shows one, otherwise a short subject.
IMPROVE: write a subject line only if the draft has one.
CV: no subject line and no greeting; the first line is the candidate's name or [name].
Plain text: no Markdown, no quotation marks around the document, nothing after the signature.
$tmpl$, 'APPROVED', TRUE
 WHERE NOT EXISTS (SELECT 1 FROM ai_prompt_templates x WHERE x.feature_code = 'DRAFT_DOCUMENT' AND x.is_active
                     AND x.language_scope = '*' AND x.model_family = '*')
ON CONFLICT DO NOTHING;
