# ADR 0040 — Translation language catalogue (and AI request reliability)

**Status:** Accepted · **Date:** 2026-11-19 · **Requested by:** product owner · **Builds on:** ADR 0007 (D46 OpenAI
primary / Google fallback), ADR 0014 (registry and routing), ADR 0023 (vendors), ADR 0031 (translation direction,
`Idempotency-Key` retries), ADR 0032 (UI strings, DRAFT am/ti/om), ADR 0034 (Arabic), ADR 0037 (translate panel) ·
**Contract:** `GET /api/v1/ai/capabilities` gains `translationLanguages`, an "any pair" item, `ETag` / `If-None-Match`
/ `304`; `GET /api/v1/languages` documented as the typing catalogue · **Migration:** V24

## Context
Translation offered the keyboard's five typing languages (am, ti, om, en, ar) and only English ↔ x pairs were routed:
the server had one `ai_language_capabilities` row per (model, feature, source, destination) pair (V10, V19, V23), and
the Android client hard-coded the same five languages in `AiLanguages.ALL`, `ServerAiActions.PLAIN_LANGUAGES`, the
translate panel's lists and the app's "Translate into" page. French → Amharic was not routed at all. The owner wants
the main world languages for translation. **Translation languages are not typing languages:** no layout, typing
language, dictionary or pack is added; the keyboard still types am, ti, om, en and ar.

The owner also saw "That took too long" for every AI call on a real phone. The cause (fixed operationally) was a phone
APK built with the emulator-only base URL `http://10.0.2.2:8080`: connections hung until the call timeout.

## Decisions

### 1. The list: Google Cloud Translation Basic (v2), all of it
The configured providers are OpenAI (`OpenAiProvider`, an LLM: any language, quality varies) and Google Cloud
Translation Basic v2 (`GoogleTranslateProvider`). Google documents its language list
(<https://cloud.google.com/translate/docs/languages>, the NMT table; 194 rows as listed on 2026-11-19, no row marked
Advanced-only). V24 adds every row the catalogue does not have yet: **183 new languages**, so **190 translation
languages** (Google's 194 minus the regional variants `fr-FR`, `fr-CA`, `pt-PT`, `pt-BR`, which `fr` and `pt` serve).
Each row: Google's English name, the autonym (native name), ISO 15924 script, writing direction (RTL: ar, he, fa, ur,
ps, sd, ug, yi, dv, ckb, ms-Arab, pa-Arab). Codes are the ones Google accepts, BCP 47 where Google offers both (`he`
not `iw`, `jv` not `jw`, `fil` not `tl`, `zh-CN` / `zh-TW`); the Google adapter maps the legacy codes Google may still
report in detection results (`iw`, `jw`, `tl`, `zh`) back to the catalogue's. OpenAI gets the same list: it translates
any language, so its capability is BETA like every seeded one (D46: the eval harness and native speakers promote or
demote per language; an operator disables a language for one model by flipping its row).

### 2. Translation-only rows: a column on the catalogue
`supported_languages.is_typing_language BOOLEAN NOT NULL DEFAULT TRUE`; the existing 11 rows stay TRUE (nothing changes
for them), the 183 new ones are FALSE. `/me/typing-languages` accepts only typing rows (`LANGUAGE_NOT_SUPPORTED`
otherwise) and `GET /languages` lists only them, so old clients see exactly the list they saw before. A translation-only
language is still an active catalogue language: a translation destination, a destination profile, an app rule or the
default destination of the preferences may name it.

Why a column and not "listed only in `ai_language_capabilities`": the two facts are independent (so and sw are planned
typing languages *and* translation languages; fr is only the latter; gez only the former), the catalogue row must exist
anyway for the foreign keys of capabilities, profiles, rules and history, and a column keeps the typing check one
indexed predicate that cannot drift with provider rows.

### 3. Routing without N × N rows: per-model language sets and one "any pair" row
- **`ai_model_translation_languages`** (`model_id`, `language_id`, `as_source`, `as_destination`, `is_enabled`): the
  languages a model translates from and into. Seeded for `openai/openai-llm`, `google-translate/google-nmt` and the
  dev/test stand-ins `mock|wiremock-http/mock-mt-1` with the 190 languages.
- **One TRANSLATE capability per model with NULL source and NULL destination.** For TRANSLATE a NULL side now means
  "any language of the model's `ai_model_translation_languages` in that role" (enabled, language active); a model
  without such rows matches nothing. No TRANSLATE row had a NULL side before, so the existing en ↔ x pair rows keep
  their meaning and keep working; transforms and detection keep "NULL = any". N languages need N rows, not N².
- `JdbcProviderDescriptorSource` loads the sets with the capabilities; `ProviderCapability` carries them
  (`sourceLanguages`, `destinationLanguages`; null = unrestricted) and `matches` checks membership. A translation into
  the source language itself never matches.
- **Order** is unchanged: the V19 any-language TRANSLATE policies (OpenAI 1, Google 2) order fr → am and es → ar like
  en → am, Google being the fallback when OpenAI fails or does not list a language.
- **Refusals** are the existing ones: a pair no active model lists → `LANGUAGE_PAIR_UNAVAILABLE` (503); an unknown or
  inactive code → `LANGUAGE_NOT_SUPPORTED` (400). The client shows "This language is not supported for this action yet." (`AiFailure.LanguageUnsupported`).
- The mock provider translates any well-formed pair (the registry decides which pairs are routed).

### 4. API: extend `GET /ai/capabilities`
No new endpoint. `AiCapabilities.translationLanguages[]` (`TranslationLanguage`: code, English and native name,
script, direction, `asSource`, `asDestination`, `supportsAutoDetection`, best `qualityTier`, `isTypingLanguage`), in
catalogue order (sort order, then English name), computed from the visible user-exposed TRANSLATE capabilities of the
active providers (pair rows and sets). It follows the plan like the items: empty when no provider is active, the plan
has no translation, or the account is ON_DEVICE_ONLY. The "any pair" row appears as one TRANSLATE item without
languages. The answer carries a strong `ETag` (SHA-256 of the body without `generatedAt`); `If-None-Match` → `304`.
`Cache-Control` moves from `no-store` to `private, no-cache` so the client may keep a copy it revalidates. `GET
/languages` (typing) was the alternative; it is unauthenticated, provider-agnostic and its old clients would have seen
183 new "typing" languages, so translation stays with the provider-driven capabilities.

The union over providers is a guide, not a promise: with different sets, a pair could combine languages no single
provider serves; the server still refuses it per request. Today both vendors list the same languages.

### 5. Android: one catalogue, fetched by `:ai-client`, used by `:ime` through the port
- `:ime` (network-free) has `TranslationCatalog` / `TranslationLanguage`, the bundled fallback `TranslationCatalog.BUNDLED`
  (the five typing languages, so first run and offline are unchanged) and `AiActions.translationCatalog()` (cached,
  never blocks). The service publishes it in `TranslationLanguages.current` for the views' names.
- `:ai-client`'s `TranslationCatalogStore` fetches `GET /ai/capabilities` on `refresh()` (a user action) and from the
  background worker, at most every 6 h unless forced, with `If-None-Match`; it keeps `translationLanguages` and the tag in
  `noBackupFilesDir/translation-catalog.json` (language names only: a plain file). An empty list means the bundled one. A
  new account invalidates the copy (the catalogue follows the plan).
- **`TranslationDirection` stays the single source of truth** for direction logic and its defaults (English for any
  other source, Amharic for English); it now takes the catalogue: an explicit source must be a catalogue source, a
  preferred or remembered destination a catalogue destination.
- **Translate panel lists:** current choice, keyboard language and recent languages (from the remembered pairs, newest
  first) first, then the keyboard's other languages, then everything else by UI-language name (collator). Each row shows
  the autonym (laid out RTL for RTL languages) and the UI-language name. A search field sits above the list; tapping it
  lets the keys type into it (the list shrinks to 150 dp above the keys, the same `FieldTyping` redirect as the
  translate box); Enter or the emoji key returns to the full-height filtered list. Search matches the UI-language name,
  English name, autonym or code, ignoring case and accents.
- **Names** come from data: the autonym from the server, the UI-language name from the keyboard's labels for its five
  languages, else `Locale.getDisplayName(uiLocale)`, else the catalogue's English name (no string resource per
  language).
- **AI Assist** Translate's output picker lists the catalogue's destinations (8 first, "More languages" for all); its
  LLM actions keep the five keyboard languages. The e-mail writer is unchanged.
- **"Translate into"** (app) lists the catalogue's destinations with a search field; offline / first run it shows the five
  languages with a note that more appear once online. Settings keep any well-formed tag (`KeyboardSettings`).

### 6. Reliability of AI requests (the "That took too long" report)
- **Short connect timeout**, separate from the call timeouts: `TransportConfig.connectTimeoutMs` defaults to 5 s (was
  10 s) and the AI client caps it at `AiTimeouts.CONNECT_SECONDS` = 5 s; call timeouts (`AiTimeouts`) are unchanged.
- **"Can't reach the server" ≠ "That took too long".** `ApiError.Unreachable` (a `Network` subtype, so older callers keep
  working) for refused, unknown host, no route and connect timeouts; `AiFailure.Unreachable` in the keyboard with its own
  message. The AI client also marks each request when a connection carries it (an OkHttp network interceptor); a failure
  before that point is `Unreachable` whatever the exception says, so a call timeout firing while connecting is no longer
  "too long". `Timeout` is kept for a server that accepted the request and answered too slowly.
- **No stuck loading:** `ServerAiActions.submit` ends every path with exactly one outcome unless the user cancelled (a
  request that cannot be built, an unreadable or unexpected answer → `Other`), and `AiRunner` turns an implementation
  that throws into `Other`. Double taps send nothing more (one request at a time), the user's text stays in the box on
  failure, and retries of `Unreachable` / `Offline` / `Timeout` / `Busy` / 5xx reuse the `Idempotency-Key` (ADR 0031).
- **Debug guard:** a debug build whose base URL host is `10.0.2.2` logs one warning (no user content) when it runs on a
  device that is not an emulator. `android/README.md` documents phone builds: `-Pqal.apiBaseUrl=http://localhost:8080`
  plus `adb reverse tcp:8080 tcp:8081`. API keys never go into the app.

## Consequences
- 190 translation languages for any pair the providers serve; the keyboard's typing languages are unchanged.
- Adding a language for translation is a catalogue row plus `ai_model_translation_languages` rows; removing one from a
  model is one `is_enabled` flip; no pair rows.
- Quality for most new languages is unverified BETA (D46). Native-speaker evaluation per language (OI-R1) decides
  promotion; the UI keeps the BETA quality note.
- Strings added in English with DRAFT am/ti/om (ADR 0032): search, "No language found", "More languages",
  "Can't reach the Qal server", the offline note.

## Verification still needed
- A real provider run (OpenAI and Google keys on the server) for a non-English pair (fr → am, es → ar) and RTL
  destinations (he, fa, ur) through the guardrails; Google detection codes for Hebrew/Javanese/Filipino/Chinese.
- On a phone: the search field's typing (list above the keys), RTL rendering of autonyms, and the unreachable message
  with a wrong base URL.
