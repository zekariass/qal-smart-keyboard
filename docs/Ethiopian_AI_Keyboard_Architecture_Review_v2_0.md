# Qal Smart Keyboard — Architecture Review and v2.0 Decision Record

**Reviewed baseline:** System Design v1.4, Database Design v1.4, `V1_4__billing_accounts_integrity_entitlements.sql`
**Result:** v2.0 design set (System Design, consolidated database baseline, OpenAPI contract)
**Date:** 17 November 2026
**Status:** Accepted for implementation planning

---

## 1. Summary

The v1.4 set was strong on three things: privacy (no keystroke collection), the offline-first transliteration requirement, and billing integrity (provider-neutral entitlements, merchant-account scoping and fail-closed market policy). The v2.0 revision keeps all of these.

It needed work in five areas:

1. **Feasibility gaps.** Some requirements could not be built on the target platforms as written. Examples: per-contact destination languages, offline iOS language-pack updates without Full Access, and the AI idempotent-replay promise.
2. **Coverage gaps.** Roughly half of the requested feature list had no architecture, data model or API: live conversation, voice-to-voice, the enterprise/language API, on-device models, personal writing style, cultural intelligence, advanced code-switching and dictionary expansion workflows.
3. **Engineering-cost risk.** Two separate native implementations of the typing and transliteration engine would drift apart, and both would need to pass the same golden tests anyway.
4. **Schema hygiene.** The "fresh install" schema embedded upgrade-migration preflights and ALTER blocks. Task codes were duplicated in CHECK lists. Several integrity and logic bugs existed (section 3).
5. **Document hygiene.** Section numbering was out of order, the doc referred to an OpenAPI file that did not exist, the error format was inconsistent, and the repository layout did not match the actual document names.

Nothing is deployed (the repository contains documentation only). v2.0 therefore **replaces** the v1.2 → v1.3 → v1.4 migration chain with one clean Flyway baseline (`V1__baseline.sql`). The v1.4 files are archived under `docs/archive/v1_4/`.

---

## 2. v2.0 document set

| File | Purpose |
|---|---|
| `Ethiopian_AI_Keyboard_System_Design_v2_0.md` | Product, platform, backend, AI, voice, billing, privacy, operations and roadmap design |
| `Ethiopian_AI_Keyboard_Database_Design_v2_0.sql` | Consolidated PostgreSQL 17+ baseline schema and seed data (Flyway `V1__baseline.sql`) |
| `Ethiopian_AI_Keyboard_OpenAPI_v2_0.yaml` | OpenAPI 3.1 contract for the app API (`/api/v1`) and the partner API (`/partner/v1`) |
| `Ethiopian_AI_Keyboard_Architecture_Review_v2_0.md` | This file: findings, decisions and canonical names |
| `archive/v1_4/*` | Superseded v1.4 documents, kept for traceability |

---

## 3. Findings

Severity: **C** = blocks a requirement or causes data/financial harm; **H** = significant rework if left; **M** = quality/maintainability; **L** = editorial.

| # | Sev | Area | Finding (v1.4) | Resolution (v2.0) |
|---|---|---|---|---|
| F1 | C | Product/platform | **Per-contact destination languages cannot work as designed.** A keyboard (Android IME or iOS extension) is never told who the recipient is; `contact_reference_hash` had no source. | Replaced by **destination profiles**: user-named recipients/contexts selected from the destination chip, optional Android **per-app default rules** (from `EditorInfo.packageName`), and optional on-device contact linking. The server stores only HMACs and encrypted labels. (D10) |
| F2 | C | iOS | Without Full Access, an iOS keyboard extension **cannot read the App Group container**. Packs that the companion app downloads are invisible to the keyboard. | Baseline language packs are **bundled inside the extension**. Downloaded updates are used only when Full Access is on; otherwise they arrive with app updates. (D15) |
| F3 | C | API/privacy | "Persist the idempotency key and return the original result" contradicted "never store AI text". | Idempotency guarantees **at-most-once charging**. A result is replayed only from an encrypted, ≤10-minute ephemeral cache; otherwise it is recomputed **without a second charge**. PostgreSQL never stores the text. (D12) |
| F4 | C | Billing | Store-managed subscriptions (Play/StoreKit) were forced through `payment_orders`/`payment_attempts`, and the merchant-account trigger required gateway-style accounts and methods for every attempt. Store renewals happen outside our order flow. | New `store_purchases` and `store_purchase_events` tables. Orders and attempts are **gateway/manual only**, and store purchases fulfil the same `user_subscriptions`. (D23) |
| F5 | H | Engine | Separate Kotlin and Swift transliteration engines meant double cost and a permanent parity risk. | **Shared Rust `keyboard-core`** with UniFFI bindings. Native UI stays native. (D2) |
| F6 | H | Coverage | No architecture for live conversation, voice-to-voice, enterprise/partner API, on-device AI, personal style, cultural intelligence, code-switching/romanised-Amharic conversion, or dictionary expansion. | New modules, tables and endpoints (D9, D11, D13, D14, D27, D29). The full feature matrix is in System Design §4. |
| F7 | H | Identity | Email-centric login. In Ethiopia phone numbers are the dominant identity (Telebirr, M-Pesa, Telegram). | **Phone OTP** is first-class, next to Google, Apple and email. Guest accounts are attestation-protected. (D3) |
| F8 | H | Metering | Voice was rounded **up to a whole minute per request**, so a 4-second dictation cost 1 minute. Keyboard dictation is many short utterances. | Speech is metered in **seconds** (rounded up per request to a whole second). TTS is metered in **characters**. Quotas are shown to users as minutes. (D5) |
| F9 | H | Metering | `usage_counters.reserved_units` had no reservation records, so a crashed request leaked its reservation forever. AI credits had expiry but no lots. | `usage_events` reservation ledger (RESERVE → SETTLE/RELEASE with expiry sweep). `ai_credit_lots` with FIFO-by-expiry consumption. (D6, D7) |
| F10 | H | Entitlements | No rule for multiple active subscriptions, upgrades or offline premium checks. | Plan rank resolution, upgrade proration via order discounts, and a **signed entitlement token** for offline-only gates. (D8, D22) |
| F11 | H | Schema | The fresh-install schema contained upgrade preflight `DO` blocks and ALTER-based "hardening" on empty tables. | One consolidated baseline with final table shapes inline. |
| F12 | H | Schema | Task/feature codes were duplicated in CHECK lists on two tables. `plan_entitlements.feature_code` was unconstrained, so new AI tasks needed migrations in several places. | `feature_catalog` table, referenced by entitlements, capabilities, prompts, requests and usage. (D5) |
| F13 | M | Schema bug | Arabic was seeded with the default `writing_direction = 'LTR'`. | Seeded as RTL. |
| F14 | M | Schema bug | The typing-language trigger re-validated the profile on **every** update. Once a profile was retired, users could not even reorder their languages. | The trigger validates only on INSERT or when `input_mode`/`transliteration_profile_id` changes. |
| F15 | M | Schema | `payment_attempts` idempotency was unique per *provider* rather than per user. `payment_refunds` was not bound to its capture's provider/currency. `payment_webhook_events` had no merchant account although webhook secrets are per account. | Fixed with `(user_id, idempotency_key)`, composite refund bindings, and an account-scoped webhook route and column. |
| F16 | M | Schema | The FREE plan was seeded as a sellable `billing_product`. `ai_models.supported_languages`/`supported_tasks` arrays duplicated the capability matrix. | FREE is not sold. The arrays are removed. |
| F17 | M | Privacy | The user dictionary synced plaintext words to the server. | On-device by default. Optional sync stores ciphertext plus a keyed HMAC under a per-user data key (crypto-shredding on deletion). (D17) |
| F18 | M | Scale | `ai_requests`/`audit_events` were FK targets, which blocks later partitioning. Random UUIDv4 keys hurt index locality on hot append tables. | High-volume append tables are **never FK targets** (logical references only). UUIDv7 keys for them. Partitioning plan documented. (D18) |
| F19 | M | Ops | No outbox. Webhook processing, notifications and renewal reminders had no reliable async mechanism. | `outbox_events` (transactional outbox) plus a DB-backed scheduler. (D19) |
| F20 | M | Billing | No tax, discount, promotion or receipt model. | Prices carry `tax_behavior`. Orders snapshot list/discount/tax/total. `promotion_codes` and `payment_receipts` added. (D21, D22) |
| F21 | M | AI governance | Provider data-handling (retention, training, region) was prose only. There was no routing priority and no evaluation or feedback store. | Data-policy columns on `ai_providers`, `ai_routing_policies`, `ai_evaluation_runs` and `ai_feedback`. (D12, D28) |
| F22 | M | Language data | No provenance or licensing record for corpora and models. Several attractive open models (e.g. NLLB-200, MMS, SeamlessM4T) are **non-commercial** licensed. | `language_datasets` with licence and permitted-use fields. A model and dataset licence gate is part of the release gates. |
| F23 | L | Docs | §9.7 came before §9.6. §13.4.1/§13.4.2 sat after Appendix D. The OpenAPI file was referenced but missing. Appendix B was not RFC 9457. The repository tree named non-existent files. | Rewritten structure. OpenAPI file created. RFC 9457 Problem Details throughout. |
| F24 | L | Stack | Java 21 and PostgreSQL 17 baseline. | **Java 25 LTS**, **Spring Boot 4.1.x**, **PostgreSQL 18** recommended (17 is the minimum supported by the schema). |

---

## 4. Decisions (v2.0)

Each decision is binding for v2.0 unless it is superseded by a later decision record.

**D1 — Single baseline.** The repository is greenfield. The schema ships as Flyway `V1__baseline.sql`. From then on every change is a forward-only migration; the baseline is never edited after it has been applied to any shared environment.

**D2 — Shared Rust typing core.** `keyboard-core` (Rust, UniFFI → Kotlin/JNI and Swift) owns:
- transliteration composition
- tokenisation and grapheme-safe editing
- protected-token detection (URL, email, number, emoji)
- dictionary lookup over compact memory-mapped FST/trie files
- candidate ranking
- code-switch heuristics

Golden test vectors run once against the core in CI and again through each platform binding as smoke tests.

- Native UI, IME/extension lifecycle and host-editor adapters stay native.
- Kotlin Multiplatform was rejected for the iOS extension because of its runtime and memory overhead inside the extension ceiling.
- If the team lacks Rust capacity, fall back to two native engines that share the golden-vector suite. This is accepted only as a recorded risk.

**D3 — Identity.**
- Offline typing never requires an account.
- Sign-in methods: phone OTP, Google, Apple, email+password.
- An anonymous **guest** account can be created per installation for a small AI trial, but only after a device-integrity check (Play Integrity / App Attest).
- Purchases, history sync and organisation membership require a registered account. Upgrading a guest merges its data into the registered user.
- Admin users authenticate through workforce OIDC with MFA (`WORKFORCE_OIDC`), never through consumer login.

**D4 — Sessions.** Refresh tokens rotate on every use. Each token belongs to a `token_family_id`. Presenting an already-rotated token revokes the whole family (reuse detection). Access tokens are JWTs that live 10–15 minutes.

**D5 — Feature catalogue and metering units.** `feature_catalog` is the single source of feature codes. Typing features are catalogued for flags only and are **never** entitlement-gated. Metering units are `REQUEST`, `SECOND` (audio), `CHARACTER` (TTS) and `NONE`. Canonical codes are listed in §5.1.

**D6 — Usage metering.**
- The order of consumption is: plan allowance → AI credits → `QUOTA_EXCEEDED`.
- Each request first RESERVEs the estimated units in `usage_events` and on the matching `usage_counters` row (row lock). It then SETTLEs the actual units, or RELEASEs them on failure. Reservations older than 15 minutes are released by a sweeper.
- DAILY windows use the user's market time zone (`Africa/Addis_Ababa` for ET).
- MONTHLY windows are anchored to the paid subscription period start; for Free they are calendar months in the market time zone.
- Usage can be charged to one of three subjects: a user, an organisation, or a partner API client.

**D7 — Credits.** Purchases and grants create `ai_credit_lots` with an expiry. Consumption takes from the lots that expire soonest first. `ai_credit_transactions` is an append-only ledger that references the lot. Credit cost per unit is set per feature in `feature_catalog.credit_cost_per_unit`.

**D8 — Entitlement resolution.**
- The effective plan is the highest `plan_rank` among the user's ACTIVE/GRACE_PERIOD subscriptions and organisation seat assignments. Allowances are not summed.
- `GET /me/entitlements` returns the resolved entitlements plus a **signed entitlement token** (JWS, Ed25519, 24-hour validity, 72-hour offline grace).
- Clients may use the token only for **offline** gates, such as on-device premium models. Every server-metered feature is re-checked on the server.

**D9 — Organisations and partner API.**
- **Organisations:** `organizations`, `organization_members` (OWNER/ADMIN/BILLING/MEMBER), `organization_invitations`, `organization_subscriptions` (seat-based, invoiced through the PARTNER/ADMIN channel) and `organization_seat_assignments`.
- **Partner API:** `api_clients` and `api_keys`. Keys are stored as a prefix plus a SHA-256 hash, with scopes and rate limits.
- **Glossaries:** `glossaries` (owned by a user or an organisation) and `glossary_entries`. They are enforced during translation and rewriting.
- Organisations can require zero data retention, which restricts routing to providers flagged `zero_data_retention`.
- The partner API lives at `/partner/v1`. It offers translate, transform, transliterate (server-side, using the same Rust core), speech and usage.

**D10 — Destination profiles (replaces per-contact preferences).**
- `user_destination_profiles`: an encrypted label, destination language, formality, addressee gender and default tone.
- `user_app_destination_rules` (Android only): an HMAC of the host package name mapped to a profile or language. iOS does not reliably expose the host app.
- Optional contact linking happens **on-device only** in the companion app.
- The keyboard can suggest a profile from local recency. The server never receives contact data or plaintext package names.

**D11 — Cultural intelligence.**
- Translation and rewrite requests carry `addressee {formality: INFORMAL|FORMAL|HONORIFIC, gender: MASCULINE|FEMININE|PLURAL|UNKNOWN}`. Amharic and Tigrinya verbs and pronouns inflect for both, and የእርስዎ/honorific forms matter socially.
- Options also include `calendar: GREGORIAN|ETHIOPIAN`, `timeNotation: INTERNATIONAL|ETHIOPIAN` (Ethiopian clock hours count from 6:00), `numerals: WESTERN|ETHIOPIC` and `adaptIdioms`.
- Responses may include `alternatives` (for example, gender variants when the addressee is UNKNOWN) and `notes` (cultural or ambiguity notes).

**D12 — AI gateway.**
- Routing is driven by `ai_routing_policies`, which select by task, language pair, priority, traffic weight, data-policy constraints and health.
- Each provider records its data policy: zero retention, whether it trains on customer data, processing regions and DPA status.
- Guardrails run on output: length bounds, an expected-script check (e.g. Ethiopic output for `am`/`ti`), language-ID check, no-op detection and prompt-injection isolation. User text is always data and never instructions.
- Optional SSE streaming is available for companion-app tasks. The keyboard uses non-streaming calls with a hard 8-second client timeout.
- Idempotency follows F3.

**D13 — Realtime voice.**
- A `realtime` module provides `POST /realtime/sessions`, which returns a short-lived WebSocket ticket.
- The pipeline is streaming ASR → MT → TTS, with turn-taking for conversation mode (two speakers, face-to-face split screen in the companion app).
- Session metadata is stored only in `realtime_sessions`. Billing uses server-measured seconds.
- It runs as a module with its own executor (virtual threads) and can be extracted into a separately deployed gateway later.

**D14 — On-device AI.**
- New resource types: `ON_DEVICE_LLM`, `ASR_MODEL`, `TTS_VOICE`, `TRANSLATION_MODEL`, `LANGUAGE_ID_MODEL` and `SPELL_MODEL`.
- The manifest declares device requirements (minimum RAM, accelerator, OS). Downloads are Wi-Fi-only by default, resumable, and delta-capable.
- Routing mode per task is `SERVER | ON_DEVICE | HYBRID`, and the user can set a "Prefer on-device" privacy preference.
- On iOS, large models run in the companion app only.
- Every model must pass the licence gate before it ships.

**D15 — iOS extension constraints.**
- The extension has a tight memory ceiling.
- Network access, the shared App Group container and the shared keychain all require Full Access.
- Without Full Access the keyboard is fully functional for offline typing using packs bundled in the extension.
- With Full Access it can read packs downloaded by the companion app and the session tokens shared through the keychain access group.

**D16 — Language resources.**
- Release channels: `INTERNAL`, `BETA`, `STABLE`. Staged rollout percentage. Delta resources reference `base_resource_id`.
- `resource_signing_keys` supports key rotation, and clients pin the active public keys.
- Transliteration profiles may be reversible (Ge'ez → Latin) for pronunciation and romanised display.

**D17 — Per-user encryption.**
- `user_data_keys` holds a KMS-wrapped data key per user.
- These are encrypted with it: history items, synced dictionary words, destination-profile labels, free-text style settings and consented feedback text.
- Destroying the key on account deletion crypto-shreds all of it, including copies in backups.

**D18 — High-volume tables.**
- `ai_requests`, `usage_events`, `audit_events` and `outbox_events` use application-generated UUIDv7 keys (or PostgreSQL 18 `uuidv7()`) and BRIN indexes on `created_at`.
- No foreign key points *to* these tables.
- Declarative monthly partitioning is introduced when any of them passes about 50 million rows or retention purges become expensive.

**D19 — Async processing.**
- A transactional outbox (`outbox_events`) plus a PostgreSQL-backed scheduler (e.g. db-scheduler) handle webhook processing, store-notification processing, notifications, renewal reminders, reservation sweeps, audio deletion, retention purges and data exports.
- No message broker until it is measured to be needed.

**D20 — Webhooks.** Gateway webhooks arrive at `/api/v1/billing/webhooks/{providerCode}/{accountCode}`. Store notifications arrive at `/api/v1/billing/store-notifications/{providerCode}`. Events are stored with `provider_account_id`. If a provider has no event ID, a synthetic one is derived from `sha256(payload)`.

**D21 — Tax and receipts.** Each price carries `tax_behavior` (`INCLUSIVE | EXCLUSIVE | NOT_APPLICABLE`). Each order snapshots `tax_minor`. Successful payments issue `payment_receipts` with a sequential receipt number per market. Ethiopian VAT treatment must be confirmed with an accountant before launch.

**D22 — Order amounts and upgrades.**
- Orders snapshot `list_amount_minor` (FK-bound to the price), `discount_minor`, `tax_minor` and `amount_minor`.
- `amount_minor = list − discount + (tax if EXCLUSIVE)`.
- An upgrade (`SUBSCRIPTION_UPGRADE`) takes effect immediately. The unused value of the current term is applied as a server-computed discount, and the old subscription ends with the event `UPGRADED`.
- Promotions are recorded in `promotion_codes`.

**D23 — App-store purchases.**
- `store_purchases` stores: the purchase-token hash and ciphertext, the original transaction ID, the product mapping, status, expiry and `acknowledged_at`. Google Play requires acknowledgement within 3 days or the purchase is refunded.
- Google Play RTDN (Pub/Sub push) and App Store Server Notifications V2 are received, then verified with the Play Developer API or App Store Server API.

**D24 — API surfaces.**
- `/api/v1`: app API, authenticated with user JWTs.
- `/partner/v1`: partner API, authenticated with API keys.
- `/admin/v1`: admin API, authenticated through workforce OIDC with MFA.
- All errors use RFC 9457 `application/problem+json`, with the extension members `code` and `correlationId`.

**D25 — Admin.** Seeded roles are SUPER_ADMIN, LANGUAGE_EDITOR, LANGUAGE_REVIEWER, AI_OPERATOR, FINANCE, SUPPORT, POLICY_REVIEWER and AUDITOR, each with a permission list. These high-risk actions require **four-eyes approval**, enforced in the application and recorded in `audit_events`:
- activating a payment provider or merchant account
- approving a market/channel billing policy
- publishing a STABLE language resource
- activating a transliteration profile
- changing prices

**D26 — Compliance.**
- Ethiopia: Personal Data Protection Proclamation No. 1321/2024.
- Diaspora: GDPR/UK GDPR.
- US: state privacy laws as applicable.
- Minimum age: 13, or the local digital age of consent if higher, enforced by an onboarding age gate.
- No advertising SDKs and no sale or sharing of personal data. Legal counsel must confirm cross-border transfer mechanisms.

**D27 — Dictionary expansion.**
- `language_datasets` records provenance and licences.
- `language_terms` gains variants, region, name gender, frequency rank and dataset link.
- `term_suggestions` is a moderated community-contribution workflow: opt-in, reviewer approval, and contributor credit if the contributor consents.

**D28 — Quality loop.**
- `ai_evaluation_runs` stores automatic metrics (chrF++, COMET where valid) and native-speaker ratings per capability.
- `ai_feedback` stores ratings and reason codes. Text is stored only with explicit consent, encrypted.
- A capability's `quality_tier` can be promoted only on evidence from evaluation runs.

**D29 — Personal writing style.**
- `user_style_profiles` holds explicit settings per language: formality, length, emoji use, greeting and sign-off (encrypted), and preferred phrases (encrypted).
- Optional "learned hints" are computed **on-device** from accepted AI results, never from keystrokes, and synced only as structured parameters.
- Raw samples are never sent.

**D30 — History.** `user_history_items` replaces `user_saved_ai_results`. It is opt-in, encrypted with the user data key, has user-selectable retention (default 30 days, maximum 365) and supports favourites.

**D31 — Abuse controls.** Rate limiting with Bucket4j, PostgreSQL-backed at first and moved to Redis when measured. Limits apply per IP, device, user, API key and task. Device attestation results are stored on `user_devices`. Guest AI requires a valid attestation.

**D32 — Android distribution.** Product flavours `play` (Play Billing, or consumption-only where policy requires) and `direct` (local gateways where allowed). The flavour is selected per market by `billing_market_channel_policies`. One APK contains both the IME service and the companion app.

---

## 5. Canonical names (v2.0)

The schema, the OpenAPI contract and the System Design must use exactly these names.

### 5.1 Feature codes (`feature_catalog.feature_code`)

| Code | Category | Unit | Entitlement-gated | Notes |
|---|---|---|---|---|
| `OFFLINE_TYPING` | TYPING | NONE | No | Always free |
| `DIRECT_SCRIPT_LAYOUT` | TYPING | NONE | No | Always free |
| `PHONETIC_TRANSLITERATION` | TYPING | NONE | No | Always free, local |
| `OFFLINE_SUGGESTIONS` | TYPING | NONE | No | Always free, local |
| `DETECT_LANGUAGE` | TEXT_AI | NONE | No | Rate-limited only |
| `TRANSLATE` | TEXT_AI | REQUEST | Yes | |
| `GRAMMAR_CORRECTION` | TEXT_AI | REQUEST | Yes | Includes spelling mode |
| `REWRITE` | TEXT_AI | REQUEST | Yes | natural / shorten / expand / clarify |
| `TONE_ADJUST` | TEXT_AI | REQUEST | Yes | friendly / polite / professional / concise / apologetic / honorific |
| `SMART_REPLY` | TEXT_AI | REQUEST | Yes | Only on text the user explicitly supplied |
| `SUMMARIZE` | TEXT_AI | REQUEST | Yes | |
| `EXPLAIN_TEXT` | TEXT_AI | REQUEST | Yes | Meaning, idiom and cultural notes |
| `DRAFT_DOCUMENT` | TEXT_AI | REQUEST | Yes | Email, letter, formal application, etc. |
| `SCRIPT_CONVERSION` | TEXT_AI | REQUEST | Yes | Whole-sentence romanised Amharic/Tigrinya → Ge'ez, AI-assisted (distinct from local transliteration) |
| `SPEECH_TO_TEXT` | VOICE | SECOND | Yes | |
| `SPEECH_TRANSLATE` | VOICE | SECOND | Yes | |
| `TEXT_TO_SPEECH` | VOICE | CHARACTER | Yes | Includes pronunciation playback |
| `VOICE_TO_VOICE` | REALTIME | SECOND | Yes | Single-turn speech → translated speech |
| `LIVE_CONVERSATION` | REALTIME | SECOND | Yes | Two-party turn-taking mode |
| `HISTORY_SYNC` | PLATFORM | NONE | Yes | Boolean access |
| `DICTIONARY_SYNC` | PLATFORM | NONE | Yes | Boolean access |
| `PERSONAL_STYLE` | PLATFORM | NONE | Yes | Boolean access |
| `CUSTOM_GLOSSARY` | PLATFORM | NONE | Yes | Boolean access |
| `ON_DEVICE_AI_MODELS` | PLATFORM | NONE | Yes | Boolean access, offline-checked by entitlement token |

### 5.2 Plans

- `GUEST` (rank −10, not sellable): applied automatically to attested anonymous accounts. It has a small daily text-AI trial and no voice.
- `FREE` (rank 0, default).
- `PREMIUM` (rank 10).
- `PREMIUM_PLUS` (rank 20).
- `BUSINESS` (rank 30, audience BUSINESS, DRAFT).

Starter quotas are listed in System Design §15.2. They remain **provisional** until AI cost has been modelled and closed-beta usage measured.

### 5.3 API surface

The OpenAPI file is authoritative. Main resources: `auth`, `devices`, `me`, `consents`, `config`, `languages`, `language-resources`, `preferences`, `typing-languages`, `destination-profiles`, `app-destination-rules`, `style-profile`, `dictionary`, `ai/*`, `history`, `speech/*`, `realtime/*`, `entitlements`, `usage`, `credits`, `billing/*`, `subscriptions`, `organizations`, `glossaries`, `partner/v1/*`.

---

## 6. Open risks carried forward

| Risk | Owner | Mitigation |
|---|---|---|
| Amharic/Tigrinya/Oromo MT and ASR quality from commercial providers is uneven | AI lead | Evaluation sets per pair, `quality_tier` gating and native-speaker panels before marketing a pair |
| Google Play buyer availability for Ethiopian accounts and Apple storefront options | Product + legal | Market/channel policy table fails closed; `direct` Android flavour |
| Recurring local payments (Telebirr mandate, ArifPay recurring) need provider approval | Finance | MANUAL_RENEW default; mandates only when approved |
| Regional internet shutdowns and low-bandwidth networks | Engineering | Offline-first typing, small delta packs, short AI timeouts, graceful offline UX |
| Low-end devices (2 GB RAM, Android Go) | Mobile lead | Memory and latency budgets (System Design §17.1); on-device AI gated by capability |
| Rust capacity in the team | Engineering manager | D2 fallback path |
| Model and dataset licensing (non-commercial open models) | AI lead + legal | `language_datasets` licence gate; release gate R7 |

---

## 7. Refinements adopted while building and validating the baseline

These came up while writing and testing the schema and contract. They are part of v2.0.

| # | Refinement | Where |
|---|---|---|
| V1 | `user_consents` is append-only. A revocation is a new row with `is_granted = FALSE`, and the latest row per (user, code) wins. The mandate guard checks this. | SQL §6, System Design §19.3 |
| V2 | AI credits are `NUMERIC(18,6)`, because per-second audio costs are fractional. Balances are displayed rounded down. | SQL §15, OpenAPI credit schemas, System Design §15.4 |
| V3 | `ai_providers` fails closed. Providers default to `DISABLED` and cannot be enabled without `dpa_signed_at` and `trains_on_customer_data = FALSE` (self-hosted providers excepted). | SQL §7 |
| V4 | Device attestation uses server-issued single-use nonces (`POST /auth/attestation-nonces`) to prevent token replay. | OpenAPI, System Design §9.4 |
| V5 | Synced settings documents use an integer `revision` for optimistic concurrency. A stale write returns `409 REVISION_CONFLICT`. | OpenAPI, System Design §10.1 |
| V6 | The realtime ticket is sent in `Sec-WebSocket-Protocol`, never in the URL. Close codes are 4001, 4002, 4003 and 4500. | OpenAPI, System Design §12.3 |
| V7 | `plan_entitlements` has a composite FK to `feature_catalog (feature_code, metering_unit, is_entitlement_gated)`, so typing features and unit mismatches are rejected without a trigger. | SQL §10 |
| V8 | `payment_transactions` rows are written only after verification (`SUCCEEDED`/`FAILED`). `CHARGEBACK_REVERSAL` was added, and non-capture kinds require a parent transaction. | SQL §12 |
| V9 | The append-only guard allows FK cascades (account deletion) and deletes run under `SET LOCAL app.retention_purge = 'on'` (retention purges). Every other UPDATE or DELETE is rejected. | SQL §18 |
| V10 | Signing keys are served at `GET /resource-signing-keys` to avoid a path clash with `/language-resources/{resourceId}`. | OpenAPI, System Design §7.6 |
| V11 | Glossaries have an enforcement mode, `PREFER` or `STRICT`. | SQL §9, OpenAPI, System Design §13.2 |

**Validation evidence (17 Nov 2026):**
- The baseline applies with `ON_ERROR_STOP=1` on PostgreSQL 17 and 18 and creates 86 tables.
- A rollback-only smoke test passes 26 checks on both versions. It covers price/amount binding, attempt eligibility, mandate consent, price overlap, transliteration activation and the F14 fix, entitlement gating, append-only ledgers, refund binding, seat limits and reservation settlement.
