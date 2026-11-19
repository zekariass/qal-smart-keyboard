-- ADR 0027: AI text actions (summarise, explain, simplify, make professional, reply suggestions, any output language)
-- and the AI e-mail writer (DRAFT_DOCUMENT: new e-mail, reply to an e-mail, improve a draft).
--
-- 1. Capabilities (BETA, like V10/V19) for SMART_REPLY, SUMMARIZE, EXPLAIN_TEXT and DRAFT_DOCUMENT, language of the
--    input in source_language_id (am, ti, om, en), destination NULL (the output language is a request option, as for
--    the V19 transforms), auto-detection on (a draft's input language is never declared):
--      openai/openai-llm                      the production LLM (D46)
--      mock/mock-llm-1, wiremock-http/mock-llm-1  the dev/test stand-ins, so local development and tests can route
--    max_input_characters: SUMMARIZE and EXPLAIN_TEXT 5000 (the transform text limit); SMART_REPLY 6000 (message 5000
--    + the user's notes 1000); DRAFT_DOCUMENT 12000 (e-mail answered or draft 8000 + notes 2000 + names).
--    The providers stay as V10/V19 left them (DISABLED): nothing here makes a call possible on its own.
--
-- 2. Routing policies: openai priority 1 for the four features, seeded is_enabled = FALSE for V19's reason (an enabled
--    policy is an allow-list and would starve the dev/test mock providers); the operator enables them together with
--    the openai provider (ADR 0023 "Enabling in production").
--
-- 3. Prompt templates, version 1, any language ('*') and any model family ('*'), for GRAMMAR_CORRECTION, REWRITE,
--    TONE_ADJUST, SUMMARIZE, EXPLAIN_TEXT, SMART_REPLY and DRAFT_DOCUMENT (TRANSLATE keeps the adapter's built-in
--    translation frame). They are seeded APPROVED and active, a deliberate exception to ADR 0017's "none seeded until
--    G0": the reply and e-mail endpoints parse a fixed output format (replies separated by '---' lines, a first
--    'Subject:' line) that only these instructions ask for, and the features must preserve facts and never invent
--    commitments, which the adapters' one-line fallbacks do not say. They are instructions to the model in English,
--    not user-facing wording. The SD 11.4 evaluation gate applies to every later version (insert v2 with
--    evaluation_status DRAFT, evaluate, then switch is_active). An operator can turn one off without a deploy:
--    UPDATE ai_prompt_templates SET is_active = FALSE WHERE feature_code = '...' AND template_version = 1;
--    The adapter then falls back to its built-in task line. A row is not inserted where an active template for the
--    same (feature, '*', '*') already exists (an environment that activated its own keeps it).
--    User text is never a placeholder (PromptRenderer): the adapters send it as separate, untrusted user messages.

-- --------------------------------------------------------------------------------------------- 1. capabilities
INSERT INTO ai_language_capabilities
    (model_id, feature_code, source_language_id, destination_language_id, is_enabled, supports_auto_detection,
     quality_tier, max_input_characters)
SELECT md.id, f.feature_code, s.id, NULL, TRUE, TRUE, 'BETA', f.max_chars
  FROM ai_models md
  JOIN ai_providers p ON p.id = md.provider_id
 CROSS JOIN (VALUES ('SMART_REPLY', 6000), ('SUMMARIZE', 5000), ('EXPLAIN_TEXT', 5000), ('DRAFT_DOCUMENT', 12000))
       AS f (feature_code, max_chars)
 CROSS JOIN supported_languages s
 WHERE ((p.provider_code = 'openai' AND md.model_code = 'openai-llm')
     OR (p.provider_code IN ('mock', 'wiremock-http') AND md.model_code = 'mock-llm-1'))
   AND s.language_code IN ('am', 'ti', 'om', 'en')
ON CONFLICT DO NOTHING;

-- --------------------------------------------------------------------------------------------- 2. routing
INSERT INTO ai_routing_policies
    (feature_code, source_language_id, destination_language_id, model_id, execution_target, priority, traffic_weight,
     requires_zero_retention, is_enabled, notes)
SELECT r.feature_code, NULL, NULL, md.id, 'SERVER', 1, 100, FALSE, FALSE, 'ADR 0027: OpenAI for text actions and e-mail'
  FROM (VALUES ('SMART_REPLY'), ('SUMMARIZE'), ('EXPLAIN_TEXT'), ('DRAFT_DOCUMENT')) AS r (feature_code)
  JOIN ai_providers p ON p.provider_code = 'openai'
  JOIN ai_models md ON md.provider_id = p.id AND md.model_code = 'openai-llm'
 WHERE NOT EXISTS (SELECT 1 FROM ai_routing_policies x WHERE x.model_id = md.id AND x.feature_code = r.feature_code
                     AND x.source_language_id IS NULL AND x.destination_language_id IS NULL);

-- --------------------------------------------------------------------------------------------- 3. templates
-- Placeholders: PromptRenderer.PLACEHOLDERS. Lines of 30+ characters must never be copied into an answer (the
-- PROMPT_LEAK guardrail compares them with the output), so no long example sentences.
INSERT INTO ai_prompt_templates
    (feature_code, template_version, language_scope, model_family, template_body, evaluation_status, is_active)
SELECT t.feature_code, 1, '*', '*', t.body, 'APPROVED', TRUE
  FROM (VALUES
('GRAMMAR_CORRECTION', $tmpl$
Task: correct the text in message 1. Correction mode: {{mode}} (empty or GRAMMAR = spelling, grammar and punctuation; SPELLING_ONLY = spelling only).
Rules:
- Change only what is wrong. Keep the meaning, the wording, the tone, the register and the language of the text.
- Keep every name, date, time, number, amount, link and e-mail address exactly as written. Never add or remove information.
- Amharic and Tigrinya: keep the Ge'ez (Ethiopic) script and Ethiopic punctuation (። ፣ ፤ ፦); never romanise. Afaan Oromoo: use standard Qubee spelling (double vowels and consonants, the apostrophe for the glottal stop).
- Words the user mixed in from another language (English terms in an Amharic message) stay as they are unless misspelt.
- If the text is already correct, return it unchanged.
{{outputLanguageInstruction}}
Return only the corrected text.
$tmpl$),
('REWRITE', $tmpl$
Task: rewrite the text in message 1. Mode: {{mode}}.
- NATURAL (or empty): fluent, natural wording a native speaker would use.
- SHORTEN: say the same in clearly fewer words; keep every key fact.
- EXPAND: develop the text with more detail and smoother transitions, without inventing facts.
- CLARIFY: make it clear and unambiguous; fix confusing order and vague references.
- SIMPLIFY: short sentences and common everyday words that a reader with basic reading skills understands; replace or briefly explain jargon, legal or technical terms; keep every fact.
Target length: {{targetLength}} (SHORT, MEDIUM or LONG; empty = what the mode implies).
Rules:
- Keep the meaning, the intent and the point of view of the writer.
- Keep every name, date, time, number, amount, link and e-mail address exactly as written. Never add facts, promises or details that are not in the text.
- {{addresseeInstruction}}
- {{outputLanguageInstruction}} If that is not the language of the text, rewrite and translate in one step; keep personal names in their usual spelling.
- Amharic and Tigrinya are written in Ge'ez (Ethiopic) script with Ethiopic punctuation; Afaan Oromoo in Qubee.
Return only the rewritten text.
$tmpl$),
('TONE_ADJUST', $tmpl$
Task: rewrite the text in message 1 in the tone below, keeping its message. Tone: {{tone}}.
- PROFESSIONAL: clear, courteous and businesslike; complete sentences; no slang, no emoji; fit for work messages, clients and managers.
- POLITE: courteous and considerate; soften requests and refusals.
- FRIENDLY: warm and approachable, still respectful.
- CONCISE: short and direct, without filler.
- APOLOGETIC: acknowledge the problem and apologise sincerely, without admitting anything the text does not admit.
- HONORIFIC: the most respectful register; in Amharic and Tigrinya use honorific pronouns and verb forms (Amharic እርስዎ, Tigrinya ንስኹም), as for elders, officials and clergy.
Target length: {{targetLength}} (empty = about the original length).
Rules:
- Keep the meaning and the facts: every name, date, time, number, amount, link and e-mail address stays exactly as written. Never add facts, promises or commitments.
- {{addresseeInstruction}}
- {{outputLanguageInstruction}} If that is not the language of the text, change the tone and translate in one step.
- Amharic and Tigrinya in Ge'ez (Ethiopic) script; Afaan Oromoo in Qubee.
Return only the rewritten text.
$tmpl$),
('SUMMARIZE', $tmpl$
Task: summarise the text in message 1 for the user (a message, e-mail, article or chat the user selected).
Rules:
- Capture the main points, decisions, requests, deadlines and figures; leave out repetition, greetings and small talk.
- Use only information in the text. Never add facts, opinions, advice or conclusions of your own.
- Keep names, dates, times, numbers and amounts exactly as written; do not convert calendars, clocks or currencies.
- If the text is a conversation, say who asked, offered or decided what.
- Length: {{targetLength}} (SHORT = one or two sentences; MEDIUM or empty = a short paragraph or up to five "- " bullet points; LONG = a fuller summary with bullet points).
- {{outputLanguageInstruction}} Write the summary in that language even when the text is in another one; keep personal and place names in their usual spelling.
- Amharic and Tigrinya in Ge'ez (Ethiopic) script; Afaan Oromoo in Qubee.
- Plain text only: no headings, no Markdown emphasis, no introduction such as "Here is a summary".
Return only the summary.
$tmpl$),
('EXPLAIN_TEXT', $tmpl$
Task: explain the text in message 1 to the user, who wants to understand it.
Rules:
- Say plainly what the text means and what the writer wants, asks or offers, in a few sentences.
- Explain idioms, proverbs, slang, abbreviations, technical, legal or official terms and cultural or religious references found in the text, including Ethiopian proverbs, holidays, titles and forms of address.
- Dates in the Ethiopian calendar and times in the Ethiopian clock (hours counted from 06:00): give them as written and add the Gregorian or international equivalent in brackets when you are sure of it. {{calendarContext}}
- If a passage is ambiguous, say so briefly instead of guessing.
- Use only the text and widely known background; never invent facts about the people or the situation.
- Keep names, numbers and amounts exactly as written.
- {{outputLanguageInstruction}} Amharic and Tigrinya in Ge'ez (Ethiopic) script; Afaan Oromoo in Qubee.
- Plain text in short paragraphs or "- " bullet points; no headings and no introduction.
Return only the explanation.
$tmpl$),
('SMART_REPLY', $tmpl$
Task: suggest {{count}} different replies the user could send in answer to the message in message 1, which the user received in a chat app, SMS or e-mail.
Rules:
- If a further message holds the user's own notes, every reply must say what the notes say (accept, decline, propose a time, ask a question), even when the notes are in another language. Without notes, give useful, clearly different options, such as agreeing, asking for a detail and politely declining.
- Write as the user, replying to the sender. Tone: {{tone}} (empty = natural for the conversation; HONORIFIC = respectful honorific forms).
- {{addresseeInstruction}}
- Never invent facts, times, places, prices or commitments that are in neither the message nor the notes. When a reply needs a detail the user has not given, put a short placeholder in square brackets in the reply language, such as [time].
- Keep names, dates, numbers and amounts exactly as written.
- Each reply is ready to send: one to three sentences in the register of the conversation; no signature, and a greeting only if the message is a formal e-mail.
- {{outputLanguageInstruction}} Amharic and Tigrinya in Ge'ez (Ethiopic) script; Afaan Oromoo in Qubee.
- Variation {{variation}}: 0 = your best set; a higher number means the user asked for new suggestions, so give replies that differ in wording and approach from an earlier set.
Output format: exactly {{count}} replies separated by a line that contains only ---
No numbering, no quotation marks, no labels, nothing else.
$tmpl$),
('DRAFT_DOCUMENT', $tmpl$
Task: write an e-mail for the user. Mode: {{mode}}. Follow only the description of this mode.
- NEW: message 1 is the user's description of what the e-mail must communicate, possibly informal and possibly in another language than the e-mail. Write a complete, well-structured e-mail that says all of it.
- REPLY: message 1 is the e-mail the user received and message 2 holds the user's notes on what to answer, possibly in another language. Write the user's reply to that e-mail: say what the notes say, answer the questions of the sender that the notes cover, and greet the sender by the name the e-mail shows.
- IMPROVE: message 1 is the user's own draft; a further message may hold notes on what to change. Correct grammar and spelling and improve clarity, structure and tone while keeping the content and the intent.
Further messages may give the recipient's name, the recipient's role and the sender's name: use them in the greeting and the signature.
Rules for every mode:
- Facts: keep every name, date, time, number, amount, address, link and e-mail address exactly as the user gave it. Never invent facts, promises, deadlines, prices, attachments or commitments the user did not state. When the e-mail needs a detail the user has not given (a date, a reference number, a name), write a short placeholder in square brackets in the language of the e-mail, such as [date] or [recipient name], instead of guessing.
- Meaning: express the user's intent faithfully; do not add requests, apologies, refusals or offers the user did not ask for.
- Tone: {{tone}}. PROFESSIONAL = clear, courteous, businesslike. FORMAL = official and respectful, for authorities, institutions and senior people. FRIENDLY = warm and personal, still polite. CASUAL = relaxed and conversational, for people the user knows well. POLITE = courteous and considerate, softening requests. PERSUASIVE = a clear case with reasons and one specific call to action, without exaggeration or pressure. APOLOGETIC = sincere responsibility and what will be done, only as far as the user's text goes. CONCISE = as short as possible while complete.
- {{addresseeInstruction}}
- Length: {{targetLength}} (SHORT = a few sentences; MEDIUM = two to four short paragraphs; LONG = fuller but focused).
- Structure: a greeting line; an opening sentence that states the purpose; the body in short paragraphs; a clear closing or next step; a sign-off and the sender's name, or [your name] when it is not known.
- Culture: the users are often Ethiopians writing to international recipients. Write natural, idiomatic e-mail in the target language, not a word-for-word rendering of Amharic, Tigrinya or Afaan Oromoo phrasing, and keep the courtesy of the original without long formulas. Ethiopian names are a given name followed by the father's name: keep them as given and never invent a surname or a title.
- Dates and times: {{culturalInstruction}} Convert between the Ethiopian and Gregorian calendars or clocks only as these instructions say, check a conversion against today's date in both calendars, and when unsure keep the date as written and mark it (Ethiopian calendar). {{calendarContext}}
- {{outputLanguageInstruction}} Amharic and Tigrinya in Ge'ez (Ethiopic) script; Afaan Oromoo in Qubee.
- Variation {{variation}}: 0 = your best version; a higher number means the user asked to regenerate, so write a clearly different version (other wording and structure) with the same facts.
Output format: the first line is the subject line, then one blank line, then the e-mail body.
The subject line starts with the English label "Subject:" in every language, followed by a short, specific subject in the language of the e-mail.
REPLY: write "Subject: Re: " and the original subject when message 1 shows one, otherwise a short subject.
IMPROVE: write a subject line only if the draft has one.
Plain text: no Markdown, no quotation marks around the e-mail, nothing after the signature.
$tmpl$)
    ) AS t (feature_code, body)
 WHERE NOT EXISTS (SELECT 1 FROM ai_prompt_templates x WHERE x.feature_code = t.feature_code AND x.is_active
                     AND x.language_scope = '*' AND x.model_family = '*')
ON CONFLICT DO NOTHING;
