# Qal Smart Keyboard — System Design

| | |
|---|---|
| **Document status** | Implementation baseline |
| **Version** | 2.0 (consolidated redesign; supersedes v1.4) |
| **Date** | 17 November 2026 |
| **Product name** | Qal Smart Keyboard (code package `com.qalsmartkeyboard`; design files keep the `Ethiopian_AI_Keyboard_*` names) |
| **Companion documents** | `Ethiopian_AI_Keyboard_Database_Design_v2_0.sql` (Flyway `V1__baseline.sql`), `Ethiopian_AI_Keyboard_OpenAPI_v2_0.yaml`, `Ethiopian_AI_Keyboard_Architecture_Review_v2_0.md` (findings and decision record D1–D32) |

> **Normative language.** **MUST / MUST NOT** are release-blocking requirements. **SHOULD** means the default unless a recorded decision overrides it. **MAY** means optional.

---

## 1. Executive summary

The product is an AI-assisted multilingual **keyboard**, launched first for Ethiopians in Ethiopia and in the diaspora. It will later expand to more African languages and markets. The keyboard is the main interface. Companion apps on Android and iOS handle onboarding, account, language settings, voice and conversation experiences, opt-in history, subscriptions and privacy controls.

> **Write naturally in your language. Communicate with the world.**

Users type in the language they know, using Ge'ez/Fidel directly, phonetic Latin-to-Ge'ez, Qubee or English. They can translate, correct or rewrite a draft, review the result and insert it into whatever app they are using. **The keyboard never sends messages.** The host app and the user stay in control of sending.

Five architectural principles shape everything else:

1. **Offline-first typing.** Layouts, transliteration, dictionaries, suggestions and autocorrection run on the device in a shared Rust core. Keystrokes MUST NOT leave the device.
2. **Explicit, minimal AI.** Text or audio goes to the server only when the user explicitly invokes an AI action. Only the selected content is sent, and the server keeps no content unless the user opts into history.
3. **Provider-neutral everything.** AI providers, payment rails and app stores sit behind adapters and data-driven registries. Entitlements do not depend on how they were paid for.
4. **Ethiopia first, Africa-ready.** ETB, local payment rails, phone-number identity, Ethiopian calendar and time conventions, low-bandwidth networks and low-end devices are first-class requirements. Languages, markets, prices and providers are data, not code.
5. **Boring backend.** A Spring Boot modular monolith on PostgreSQL, with a transactional outbox and a DB-backed scheduler. Additional infrastructure is added only when measurements justify it.

## 2. Goals, non-goals and design principles

### 2.1 Goals

1. High-quality typing for **Amharic, Tigrinya, Afaan Oromoo and English** at launch. The set is to be confirmed with user research and native speakers.
2. Three input modes for Ethiopic-script languages: **direct Fidel layout**, **Latin QWERTY phonetic → Ge'ez** (live composing), and **ABC passthrough**. Qubee Latin input for Afaan Oromoo.
3. Saved per-language input modes, a saved default translation destination, and one-tap switching of both from inside the keyboard.
4. Translation, grammar correction, rewriting, tone adjustment, smart replies, summarisation, explanation and document drafting, each with a review step before insertion.
5. Voice: dictation, speech translation, text-to-speech/pronunciation, voice-to-voice and live conversation mode (companion app).
6. Cultural intelligence: honorific and addressee-aware translation, Ethiopian calendar and time conventions, idiom adaptation.
7. Personalisation that respects privacy: destination profiles, personal writing style, user dictionary and glossaries.
8. Subscriptions, credits and payments through Ethiopian rails (Chapa, ArifPay, Telebirr) and the app stores, all converging on one entitlement model.
9. Enterprise and partner products: organisation seats, glossaries and a metered language API.
10. Expansion to more Ethiopian and African languages, and to on-device AI models, without schema or app rewrites.

### 2.2 Non-goals

- Replacing WhatsApp, Telegram, email or any host app. Building a chat network.
- Reading messages from other apps, monitoring the clipboard, or pressing Send on the user's behalf.
- Storing keystrokes, drafts, transcripts or audio for analytics or model training.
- Training foundation MT, ASR or LLM models from scratch before launch.
- Supporting every Ethiopian language on day one.
- Microservices, Kubernetes or an event-streaming platform before scale justifies them.

### 2.3 Product rules (normative)

- **R-1:** The keyboard is an input method, not a message sender. It prepares, transforms and inserts text. The user sends it.
- **R-2:** Ordinary typing, all input modes, offline dictionaries and suggestions are **free and unmetered** in every plan, including for guests.
- **R-3:** Nothing typed is transmitted unless the user invokes an AI action on that specific text.
- **R-4:** Every AI result is shown for review before insertion. Nothing is auto-inserted or auto-sent.
- **R-5:** Transliteration is not translation. It runs locally and never calls the backend.
- **R-6:** A language or task is advertised only after it passes its quality gate (§21.5). Accepting a language code is not the same as supporting the language.

## 3. Users, markets and journeys

### 3.1 User groups

- People in Ethiopia who communicate across Amharic, Tigrinya, Afaan Oromoo and English.
- Diaspora users (UK, US, EU, Middle East) writing to family in Ethiopian languages and to colleagues in English or Arabic.
- Diaspora youth who understand spoken Amharic or Tigrinya but cannot write Fidel, and who type romanised text ("selam endet neh"). They are a key audience for phonetic input and for `SCRIPT_CONVERSION`.
- Professionals who need formal letters and email in Amharic or English.
- Learners who want pronunciation and romanised readings.
- Organisations (NGOs, government, media, businesses) that need consistent terminology and an API.

### 3.2 Market conditions that drive the design

| Condition | Design response |
|---|---|
| Many entry-level Android devices (2–3 GB RAM, older OS versions) | Memory and latency budgets (§17.1); compact memory-mapped dictionaries; on-device AI gated by device capability |
| Expensive or unstable data; regional connectivity shutdowns | Offline-first typing; small delta language packs; Wi-Fi-only large downloads; short AI timeouts with clear offline states |
| Phone-number-centric identity and mobile money (Telebirr, M-Pesa, CBE Birr) | Phone OTP sign-in; local gateways; manual renewal by default |
| Limited app-store payment availability for Ethiopian accounts | Market/channel billing policy that fails closed; `direct` Android distribution flavour (§16.8) |
| Ethiopian calendar (ዓ.ም.) and Ethiopian clock (hours counted from 6:00) | Calendar and time-notation preferences; cultural options on AI requests |
| Amharic/Tigrinya inflect for addressee gender, number and honorific status | Addressee-aware translation with alternatives (§11.5) |

### 3.3 Core journeys

**J1 — Type in Amharic, send in English.**
1. The user types an Amharic draft.
2. They tap **Translate**.
3. The keyboard shows the saved destination (English) and lets them change it for this one translation.
4. Only that draft and the chosen options are sent over TLS.
5. The server checks entitlement and usage, routes the request, and returns the result plus any alternatives.
6. The keyboard shows the original and the translation side by side.
7. The user taps **Insert**. The composing text is replaced, or the result is inserted at the cursor.
8. The user presses Send in the host app.

**J2 — Phonetic Ge'ez on QWERTY.**
1. The user picks Amharic with `PHONETIC_LATIN_TO_NATIVE`.
2. They type `selam`. The composing region shows `ሰላም` live, and the candidate strip shows alternatives.
3. Space commits the word. The **ABC** key switches to passthrough temporarily.
4. Names, URLs, emails and numbers are preserved.
5. Everything runs offline in `keyboard-core` (§7).

**J3 — Romanised sentence to Fidel (`SCRIPT_CONVERSION`).** A diaspora user has typed or pasted a whole romanised sentence: "ene dehna negn, ante endet neh?". They tap **Convert to Fidel**. Because this needs sentence context to resolve ambiguity, it is an explicit server AI action, unlike live transliteration. The result goes through review and insert.

**J4 — Destination profiles.**
1. The user creates the profiles "Mum → Amharic, honorific, feminine" and "Work → English, professional".
2. In the keyboard, the destination chip lists these profiles.
3. On Android, the user can optionally say "In WhatsApp, default to Mum". This creates a per-app rule stored as an HMAC (§10.3).
4. The keyboard never knows who the recipient really is. The user's choice is the signal.

**J5 — Voice dictation.**
1. The user taps the microphone.
2. **Android keyboard:** if `RECORD_AUDIO` has been granted (through a companion-app activity, since an IME cannot show the runtime-permission prompt), audio streams to `SPEECH_TO_TEXT`. Otherwise the system recogniser or the companion app is used.
3. **iOS:** the companion app captures audio, or the user uses system dictation. The keyboard extension does not record.
4. The transcript is shown for review and then inserted. Audio is not retained.

**J6 — Live conversation (companion app).** Two people share one phone in a face-to-face split screen. Each speaks in turn. The app streams audio, and the other person sees and hears the translation. The session is billed in server-measured seconds, and no audio or transcript is stored (§12.3).

**J7 — Understand and reply.** The user explicitly copies, selects or shares an incoming message. The keyboard or companion app explains or translates it and offers smart replies in the user's language. Smart replies work only on text the user supplied.

**J8 — Professional drafting.** In the companion app (or a compact flow in the keyboard), the user picks *Letter → formal → Amharic*. They enter key points and get a draft with subject and body. They edit it, then copy or insert it.

**J9 — Pay in ETB.**
1. The user chooses Premium for 30 days.
2. The server prices the order in ETB and returns only eligible methods (for example Chapa → Telebirr).
3. The user pays on the provider's hosted checkout.
4. A webhook arrives, and the server verifies the payment server-to-server.
5. The entitlement starts. Renewal is manual by default, with reminders.

## 4. Feature scope, phasing and traceability

Phases: **P0** first useful release (Android). **P1** early follow-up. **P2** differentiation. **P3** expansion and B2B.

| # | Feature | Phase | Surfaces | Execution | Backend module(s) | Key data | Key API |
|---|---|---|---|---|---|---|---|
| 1 | Android keyboard | P0 | Android IME | Local | — | — | — |
| 2 | Android companion and onboarding | P0 | Android app | — | identity, devices, preferences, privacy | users, user_devices, user_consents | `/auth/*`, `/devices`, `/me/*` |
| 3 | Amharic, Tigrinya, Afaan Oromoo, English | P0 | All | Local + server | languages | supported_languages, language_resources | `/languages`, `/language-resources` |
| 4 | Direct Ge'ez/Fidel input | P0 | Keyboards | Local (`keyboard-core`) | languages | language_resources (KEYBOARD_LAYOUT) | — |
| 5 | Latin QWERTY phonetic → Ge'ez | P0 | Keyboards | Local | languages, preferences | transliteration_profiles, user_typing_languages | `/me/typing-languages` |
| 6 | ABC passthrough | P0 | Keyboards | Local | — | — | — |
| 7 | Offline dictionaries and suggestions | P0 | Keyboards | Local | languages | language_resources (DICTIONARY, FREQUENCY_LIST) | `/language-resources` |
| 8 | Language switching | P0 | Keyboards | Local | preferences | user_typing_languages | `/me/typing-languages` |
| 9 | Translation | P0 | Keyboard, companion | Server | ai-gateway, text-services | ai_requests, usage_* | `/ai/translate` |
| 10 | Grammar correction | P0 | Keyboard, companion | Server | text-services | — | `/ai/transform` (`GRAMMAR_CORRECTION`) |
| 11 | Basic AI rewriting | P0 | Keyboard, companion | Server | text-services | — | `/ai/transform` (`REWRITE`) |
| 12 | Backend authentication and preferences | P0 | All | Server | identity, preferences | users, user_sessions, user_preferences | `/auth/*`, `/me/preferences` |
| 13 | Language resource management | P0 | Admin, clients | Server + CDN | languages, administration | language_resources, resource_signing_keys | `/language-resources`, `/admin/v1/*` |
| 14 | Privacy and security controls | P0 | All | Both | privacy, identity | user_consents, user_data_keys, deletion/export | `/me/consents`, `/me/data-exports` |
| 15 | iOS keyboard and companion app | P1 | iOS | Local + server | — | — | same as Android |
| 16 | Android voice-to-text | P1 | Android IME, companion | Server (on-device later) | speech | voice_jobs | `/speech/*` |
| 17 | iOS companion voice-to-text | P1 | iOS companion | Server | speech | voice_jobs | `/speech/*` |
| 18 | Tone adjustment | P1 | Keyboard, companion | Server | text-services | — | `/ai/transform` (`TONE_ADJUST`) |
| 19 | Smart replies | P1 | Keyboard, companion | Server | text-services | — | `/ai/replies` |
| 20 | Per-contact destination preferences → **destination profiles** | P1 | Keyboard, companion | Local + sync | preferences | user_destination_profiles, user_app_destination_rules | `/me/destination-profiles` |
| 21 | Optional translation history | P1 | Companion, keyboard | Server (encrypted) | privacy, text-services | user_history_items | `/me/history` |
| 22 | Subscriptions and store validation | P1 | Companions | Server | billing, entitlements | store_purchases, user_subscriptions | `/billing/store-purchases` |
| 23 | Language pack updates | P1 | Clients | CDN | languages | language_resources (channels, deltas) | `/language-resources` |
| 24 | Payment integrations (Chapa, ArifPay, Telebirr, manual bank transfer) | P1 | Companions, web | Server | billing | payment_* tables | `/billing/*` |
| 25 | Voice-to-voice translation | P2 | Companion | Server streaming | realtime | realtime_sessions | `/realtime/sessions` |
| 26 | Live conversation mode | P2 | Companion | Server streaming | realtime | realtime_sessions | `/realtime/sessions` |
| 27 | Cultural intelligence | P2 | All AI | Server | ai-gateway, text-services | prompt templates, capability matrix | addressee and cultural options on `/ai/*` |
| 28 | Advanced code-switching | P2 | Keyboard + server | Local + server | text-services | LANGUAGE_ID_MODEL resources | `/ai/transform` (`SCRIPT_CONVERSION`), `/ai/detect-language` |
| 29 | Personal writing styles | P2 | All AI | Local + server | personalization | user_style_profiles | `/me/style-profiles/{lang}` |
| 30 | Ethiopian names and places dictionary expansion | P2 | Clients, admin | Local + curation | languages | language_terms, term_suggestions, language_datasets | `/language-resources`, `/admin/v1/*` |
| 31 | Summarisation | P2 | Companion, keyboard | Server | text-services | — | `/ai/transform` (`SUMMARIZE`) |
| 32 | Professional email and letter drafting | P2 | Companion | Server | text-services | — | `/ai/drafts` |
| 33 | Text-to-speech and pronunciation | P2 | Companion, keyboard | Server + local reverse transliteration | speech | — | `/speech/synthesis` |
| 34 | Additional African languages | P3 | All | Data-driven | languages | supported_languages and related | — |
| 35 | On-device AI models | P3 | Clients | Local | languages, ai-gateway | language_resources (model types) | `/language-resources`, `/ai/capabilities` |
| 36 | Enterprise and language API products | P3 | Partner API, org admin | Server | organizations, partner-api | organizations, api_clients, api_keys, glossaries | `/organizations/*`, `/partner/v1/*` |

## 5. System architecture

### 5.1 Context

```mermaid
flowchart LR
    subgraph Device[User device]
      IME[Android IME / iOS Keyboard Extension]
      APP[Companion app]
      CORE[[keyboard-core - Rust]]
      PACKS[(Signed language packs and on-device models)]
      IME --> CORE
      APP --> CORE
      CORE --> PACKS
    end

    subgraph Edge[Edge]
      CDN[CDN - packs and models]
      WAF[TLS, WAF and rate limiting]
    end

    subgraph Backend[Spring Boot modular monolith]
      API[App API /api/v1]
      PAPI[Partner API /partner/v1]
      ADM[Admin API /admin/v1]
      RT[Realtime gateway - WebSocket]
      MODS[Domain modules]
      OUTBOX[Outbox and scheduler]
    end

    ADMINUI[Next.js admin console]
    DB[(PostgreSQL)]
    OBJ[(Object storage: packs, temporary audio, exports)]
    KMS[(KMS / secret manager)]
    AIP[AI providers: MT, LLM, ASR, TTS]
    PAY[Payment rails: Chapa, ArifPay, Telebirr, bank]
    STORES[Google Play / App Store]

    IME -- explicit AI actions only --> WAF
    APP --> WAF
    WAF --> API
    WAF --> PAPI
    WAF --> RT
    ADMINUI --> ADM
    PACKS <-. download and verify .- CDN
    API --> MODS
    PAPI --> MODS
    ADM --> MODS
    RT --> MODS
    MODS --> DB
    MODS --> OUTBOX
    OUTBOX --> DB
    MODS --> OBJ
    MODS --> KMS
    MODS --> AIP
    MODS --> PAY
    MODS --> STORES
    PAY -- webhooks --> WAF
    STORES -- server notifications --> WAF
    OBJ --> CDN
```

### 5.2 Trust boundaries

| Boundary | What crosses | Controls |
|---|---|---|
| Device → backend | Explicit AI payloads, preferences, encrypted sync items, purchase evidence | TLS 1.2+ (1.3 preferred), JWT, device attestation for guests, request size limits, rate limits |
| Backend → AI providers | The minimum text or audio needed for the task | Provider data-policy routing (ZDR where required), redacted logs, timeouts and circuit breakers |
| Payment/store → backend | Webhooks and notifications | Signature verification, idempotent inbox, server-to-server re-verification |
| Admin → backend | Configuration and catalogue changes | Workforce OIDC, MFA, RBAC, four-eyes approval for high-risk actions, audit log |
| CDN → device | Language packs and models | Ed25519-signed manifests, pinned public keys, SHA-256 checksums, atomic install |

## 6. Client architecture

### 6.1 Shared `keyboard-core` (Rust)

The typing engine is a single Rust library. UniFFI generates Kotlin bindings (JNI, Android) and Swift bindings (static library, iOS). Decision D2 explains why.

| Component | Responsibility |
|---|---|
| `composer` | Composition state machine: Latin buffer → live native candidate, commit rules, candidate replacement, backspace over composition |
| `translit` | Executes signed `TRANSLITERATION_RULES` (longest-match rules plus contextual rules), with forward (Latin → Ethiopic) and reverse (Ethiopic → Latin) directions |
| `segmenter` | Unicode grapheme and word segmentation; handles the Ethiopic word separator `፡`, Qubee apostrophe (hudhaa) as part of a word, and protected tokens |
| `lexicon` | Memory-mapped FST/trie dictionaries, frequency lists, user dictionary overlay, protected terms |
| `ranker` | Candidate ranking: rule score + unigram/bigram frequency + user history (local) + context |
| `autocorrect` | Conservative per-language correction that never "corrects" tokens from another enabled language |
| `langid` | Lightweight local language and script identification for code-switching (§7.5) |
| `numerals_calendar` | Ethiopic numerals ፩–፻፼, Ethiopian ↔ Gregorian date conversion, Ethiopian clock formatting (used by suggestions and the cultural-options UI) |

Requirements:

- **Deterministic:** the same profile version, input and context MUST produce the same output.
- **No I/O on the key path:** files are memory-mapped at session start. There is no network access inside the core.
- **Memory budget:** ≤ 25 MB resident for typing in one language (iOS extension safety, §6.3).
- **Golden test vectors** (keystrokes → composing candidates → committed text → alternatives) are versioned with each rules resource. They run in core CI and as binding smoke tests on both platforms.

### 6.2 Android

**Stack:** Kotlin, `InputMethodService`, Jetpack Compose for the companion and settings UI, a custom `View` for the key surface (for frame-time control), and Hilt. **One APK** contains both the IME service and the companion app. Gradle modules: `:core-bindings`, `:ime`, `:companion`, `:data`, `:billing-play`, `:billing-direct`. Product flavours are `play` and `direct` (D32).

| Component | Responsibility |
|---|---|
| `KeyboardImeService` | IME lifecycle, `EditorInfo` inspection (input type, IME options, `packageName` for per-app rules) |
| `KeyboardSurface` | Key rendering, long-press popups (Fidel vowel orders), candidate strip, mode/destination chips, AI toolbar |
| `InputConnectionAdapter` | `setComposingText` / `commitText` / `finishComposingText`; batch edits; handles host quirks |
| `CoreSession` | Wraps the `keyboard-core` handle for each input session |
| `AiActionCoordinator` | Review sheet, request lifecycle, retry with the same `Idempotency-Key`, offline states |
| `VoiceCoordinator` | Microphone capture where `RECORD_AUDIO` is granted (the permission is requested from a companion activity); otherwise `SpeechRecognizer` or hand-off to the companion app |
| `ResourceManager` | Pack download (WorkManager, resumable, Wi-Fi-only for large items), signature and checksum verification, atomic swap, last-known-good rollback |
| `SecureStore` | Tokens in Android Keystore-backed encrypted storage; the IME and companion share the same app sandbox |

Host-editor rules:

- In password fields (`TYPE_TEXT_VARIATION_PASSWORD` and related), or when `IME_FLAG_NO_PERSONALIZED_LEARNING` is set, the keyboard MUST disable learning, suggestions history and AI actions, and SHOULD default to Latin or direct input.
- Email, URI and number fields use Latin or direct input. Transliteration is never forced into them.
- The keyboard MUST handle apps that reject composing text by falling back to commit-only mode.

### 6.3 iOS

**Stack:** Swift. A UIKit keyboard extension (`UIInputViewController`), a SwiftUI companion app, `keyboard-core` as an XCFramework, App Group and shared keychain access group.

| Component | Responsibility |
|---|---|
| `KeyboardViewController` | Lifecycle, `needsInputModeSwitchKey` globe key, trait handling |
| `KeyboardLayoutView` | Keys, candidate bar, mode/destination chips, compact AI toolbar |
| `DocumentProxyAdapter` | `textDocumentProxy` editing. iOS has no true composing region, so the adapter maintains its own composing buffer and replaces it with `deleteBackward` + `insertText` (and `setMarkedText` where reliable) |
| `AiActionCoordinator` | Available only with Full Access; otherwise shows "Open app to translate" |
| `CompanionApp` | Onboarding (enable keyboard, explain Full Access honestly), account, voice, conversation mode, drafting, history, subscriptions (StoreKit 2), privacy controls |

iOS constraints (normative):

- Custom keyboards are unavailable in secure fields and in some system contexts, and host apps can refuse them. Features MUST degrade gracefully.
- **Without Full Access** (D15), the extension has no network, no shared App Group container and no shared keychain. It MUST still type fully offline using **language packs bundled inside the extension**. Pack updates then arrive with app updates.
- **With Full Access**, the extension reads packs that the companion downloaded into the App Group container and uses the session token shared through the keychain access group.
- The extension MUST NOT record audio. Voice runs in the companion app or through system dictation.
- **Memory:** the extension runs under a tight, undocumented memory ceiling (crashes are commonly observed in the tens of megabytes). Budget ≤ 40 MB peak and avoid large model loads in the extension (§11.9).
- On iOS the keyboard cannot reliably identify the host app, so per-app destination rules are Android-only.

### 6.4 Keyboard ↔ companion integration

| Concern | Android | iOS |
|---|---|---|
| Shared settings and cache | Same app sandbox (Room/DataStore) | App Group container (Full Access needed for the extension to read updates) |
| Tokens | Same process family, Keystore | Shared keychain access group (Full Access) |
| Open companion for a task | Explicit `Intent` with a task payload stored locally and passed by reference ID | Deep link using an app URL scheme. The text payload is handed over through the App Group, never in the URL |
| Return to host app | User switches back; result placed on an in-keyboard "pending insert" chip | Same; the keyboard shows the pending result when it reappears |

### 6.5 Admin console

A Next.js app with its own deployment. It authenticates with workforce OIDC + MFA and talks only to `/admin/v1`. Main areas:

- **Languages and resources:** upload, sign, stage, publish and roll back.
- **Profiles and terms:** transliteration profiles, term moderation, datasets and licences.
- **AI:** providers, models, routing, prompts, evaluations.
- **Plans and entitlements.**
- **Billing:** prices, providers, merchant accounts, method mappings, market/channel policies, manual payment review, reconciliation.
- **Organisations.**
- **Feature flags and configuration.**
- **Audit viewer.**

High-risk changes go through a four-eyes approval flow (D25).

## 7. Language engine

### 7.1 Input modes

| Mode | Keys shown | Output | Languages |
|---|---|---|---|
| `LATIN_PASSTHROUGH` | Latin QWERTY | Latin, unchanged | English; temporary ABC in any language |
| `PHONETIC_LATIN_TO_NATIVE` | Latin QWERTY | Ethiopic, live composing | Amharic, Tigrinya (and future Ethiopic-script languages) |
| `DIRECT_SCRIPT_LAYOUT` | Fidel keys | Ethiopic | Amharic, Tigrinya |
| Qubee (`LATIN_PASSTHROUGH` + Oromo lexicon) | Latin QWERTY (+ apostrophe key) | Latin Qubee | Afaan Oromoo |

Rules:

- The mode is a saved choice for each language (`user_typing_languages`). It is synced as a preference and cached locally. **No API is ever called per keystroke.**
- The mode indicator shows language and mode, for example `አማ · ABC→አ`. A one-tap **ABC** toggle switches between passthrough and the language's phonetic mode, and long-pressing the globe/mode key cycles through layouts. Users never need Settings to switch.
- English is never silently transliterated. Afaan Oromoo is never converted to Ge'ez by default.

### 7.2 Ethiopic specifics

- **Script coverage.** Unicode blocks Ethiopic (U+1200–137F), Ethiopic Supplement (U+1380–139F), Ethiopic Extended (U+2D80–2DDF), Extended-A (U+AB00–AB2F) and Extended-B (U+1E7E0–1E7FF). Packs declare which ranges they emit. Output is NFC-normalised.
- **Syllabary structure.** Each consonant has seven (sometimes eight) vowel orders plus labialised forms. The direct layout uses consonant keys and selects the vowel order with a long-press popup or a second key. Both interactions are configurable in the layout resource.
- **Phonetic scheme.** Start from an established ASCII convention (SERA-style). Keep **separate Amharic and Tigrinya profiles** (D16). Tigrinya, for example, needs ቐ (qhe) and ኸ (xe) series that Amharic does not use. Native-speaker panels approve the mappings.
- **Main ambiguity sources**, all handled as candidates:
  - Homophone letter families: ሀ/ሐ/ኀ, ሰ/ሠ, አ/ዐ, ጸ/ፀ. Most users type the "common" one. Dictionary frequency picks the conventional spelling, and alternatives remain one tap away.
  - The sixth order (ə or no vowel): `s` → ስ.
  - Gemination, which Fidel does not mark. It is an input no-op but matters for TTS.
  - Labialised forms (`gWa` → ጓ).
- **Punctuation and numerals.** ። ፣ ፤ ፥ ፦ ፧ ፨ and the word separator ፡ are available on long-press and through phonetic sequences defined in the profile. Ethiopic numerals ፩…፼ are offered as alternatives for typed digits when the user's `numeralStyle` is `ETHIOPIC`.
- **Editing.** Backspace inside a composition removes the last Latin input unit and recomputes. After commit, it deletes one grapheme cluster, never a UTF-16 code unit. Candidate taps replace only the active composing region.

### 7.3 Afaan Oromoo (Qubee)

- Doubled vowels (`aa`, `ee`), the consonant digraphs (ch, dh, ny, ph, sh, and the rarer ts/zy) and the apostrophe/hudhaa (`'`) are part of words. The segmenter MUST NOT treat the apostrophe as a word boundary, and the layout puts it on the primary layer.
- Autocorrect and suggestions use an Oromo lexicon and respect dialect variants (spelling variants are stored in `language_terms.variants`).

### 7.4 Dictionaries, suggestions and user learning

- **Shipped packs:** `DICTIONARY` (FST + frequencies), `FREQUENCY_LIST` (bigram), `PROTECTED_TERMS` (names and places), `EMOJI_KEYWORDS`.
- **Local user model:** words the user commits outside sensitive fields update a local, on-device frequency overlay. It is never uploaded. "Clear learned words" is available in settings.
- **User dictionary:** entries added explicitly by the user. Sync is optional (`DICTIONARY_SYNC`) and encrypted with the user's data key (D17).
- **Names and places expansion:** curated `language_terms` (person names with gender, place names with region, cultural terms), sourced from licensed datasets (`language_datasets`) and moderated community suggestions (`term_suggestions`). Releases go out as new `PROTECTED_TERMS`/`DICTIONARY` pack versions.

### 7.5 Code-switching

Mixed-language typing is normal: Amharic with English terms, Oromo with Amharic loanwords, romanised Amharic in Latin.

- **Local (P0–P1):**
  - per-token language and script ID in `keyboard-core`
  - autocorrect suppression for tokens that are valid in another enabled language
  - candidates from all enabled languages' lexicons, with the active language preferred
  - protected tokens always preserved
- **Server (P2):**
  - `SCRIPT_CONVERSION`: whole-sentence romanised → Ge'ez using context
  - `DETECT_LANGUAGE` for mixed input before translation
  - translation prompts that preserve deliberate English terms when `preserveCodeSwitching` is set
- The user always has a manual override, through the language chip or by tapping a candidate.

### 7.6 Language-resource format and distribution

Each resource has a signed manifest:

```json
{
  "resourceId": "uuid",
  "languageCode": "am",
  "resourceType": "TRANSLITERATION_RULES",
  "version": "1.3.0",
  "platform": "SHARED",
  "releaseChannel": "STABLE",
  "minAppVersion": "1.4.0",
  "artifactUrl": "https://cdn.example.com/packs/am/translit/1.3.0.bin",
  "sha256": "…",
  "sizeBytes": 182044,
  "baseResourceId": null,
  "deviceRequirements": { "minRamMb": 0 },
  "licenseCode": "PROPRIETARY",
  "profile": { "code": "AM_PHONETIC_QWERTY_TO_ETHIOPIC", "sourceScript": "Latn", "targetScript": "Ethi", "normalization": "NFC", "reversible": true },
  "signatureKeyId": "packs-2026-01"
}
```

The manifest travels with its artifact in one signed container. The Ed25519 signature covers the **exact transmitted manifest bytes** prefixed with the domain-separation context `QAL-PACK-MANIFEST-v1\0` (never a re-serialised JSON form), and the manifest commits to the artifact by `sha256` and `sizeBytes`. Byte layout, verification order, limits, key handling, rollback rule and test vectors: [`specs/pack-format-v1.md`](specs/pack-format-v1.md).

Distribution rules:

- Clients verify the **signature against pinned public keys**, then the **SHA-256** of the artifact, before activating anything. They install atomically and keep the last known-good version for rollback.
- **Release channels** `INTERNAL → BETA → STABLE`, with a staged `rollout_percentage` decided by a stable hash of the installation ID.
- **Delta updates:** if the device holds `baseResourceId`, the client downloads the delta artifact, otherwise the full artifact.
- **Signing-key rotation:** `resource_signing_keys` publishes the next key before it is used. The app embeds at least the current and next public keys, and `GET /resource-signing-keys` can add keys only if the response is signed by an already-trusted key.
- **iOS:** baseline packs are bundled in the extension (§6.3).

### 7.7 Language quality gates

A language or mode is marked GA only when it has:

1. A keyboard layout approved by native speakers.
2. For Ethiopic phonetic input, a documented and versioned transliteration specification with ambiguity rules, plus a golden corpus of at least 2,000 vectors that the core passes on both platforms.
3. A licensed wordlist or spelling corpus (licence recorded in `language_datasets`).
4. Names and protected-terms coverage.
5. Prediction and autocorrection acceptance tests.
6. For every advertised AI task or language pair: an evaluation run (§11.8) that meets the threshold for its `quality_tier`.
7. For voice: ASR/TTS evaluations in quiet and noisy conditions, including accents.

## 8. Backend architecture

### 8.1 Stack

| Concern | Choice |
|---|---|
| Runtime | **Java 25 LTS** (minimum 21), virtual threads for I/O-bound provider calls |
| Framework | **Spring Boot 4.1.x** (project is on 4.1.2; pin a tested patch release), Spring Security, Spring Modulith to enforce module boundaries |
| API | REST + JSON under `/api/v1`, `/partner/v1`, `/admin/v1`; WebSocket for realtime; SSE for streaming text in the companion app |
| Contract | OpenAPI 3.1 (`Ethiopian_AI_Keyboard_OpenAPI_v2_0.yaml`); contract tests in CI; generated Kotlin/Swift clients |
| Database | **PostgreSQL 18** recommended (17 is the minimum the schema supports); Flyway; HikariCP |
| Async | Transactional outbox + db-scheduler (PostgreSQL-backed); no broker until measured need |
| Cache / rate limiting | Bucket4j on PostgreSQL first; Redis/Valkey when measured load requires it. The encrypted ephemeral AI-result cache (≤ 10 min TTL) starts in an **UNLOGGED** PostgreSQL table (B5.4, ADR 0017) and moves to Redis/Valkey only when load requires it |
| Storage | S3-compatible object storage: language packs (behind the CDN), temporary audio (private bucket, 24 h lifecycle), data exports (private bucket, 7-day lifecycle) |
| Secrets / keys | Cloud KMS + secret manager; envelope encryption for user data keys |
| Observability | OpenTelemetry traces/metrics/logs; structured JSON logs with redaction |
| Packaging | OCI image; one deployable (`api`). `realtime` and `worker` run as the same image with different Spring profiles so they can scale independently |

### 8.2 Modules

| Module | Responsibilities |
|---|---|
| `identity` | Phone OTP, OAuth (Google/Apple), email/password, guest accounts, sessions and refresh rotation, account lifecycle, guest merge |
| `devices` | Installations, push tokens, attestation (Play Integrity / App Attest), resource-installation reports |
| `languages` | Language catalogue, resources, signing keys, transliteration profiles, terms, datasets, suggestions moderation |
| `preferences` | Preferences, typing languages, destination profiles, app rules, dictionary sync |
| `personalization` | Style profiles; injecting style parameters into AI requests |
| `ai-gateway` | Provider/model registry, routing policies, prompt templates, guardrails, cost tracking, evaluation and feedback |
| `text-services` | Translate, transform tasks, replies, drafts, detect-language; glossary enforcement; cultural options |
| `speech` | Upload sessions, transcription and speech translation jobs, synthesis, voices |
| `realtime` | WebSocket sessions, streaming ASR → MT → TTS pipeline, turn-taking, server-measured billing |
| `entitlements` | Plan resolution, entitlement token signing, allowance checks |
| `usage` | Reservations and settlement, counters, credits ledger and lots |
| `billing` | Products, prices, promotions, orders, attempts, mandates, transactions, refunds, webhooks, receipts, reconciliation |
| `stores` | Google Play / App Store verification, acknowledgement, notifications |
| `organizations` | Organisations, members, invitations, seats, API clients/keys |
| `glossaries` | User and organisation glossaries |
| `partner-api` | `/partner/v1` façade over text-services, speech, languages (server-side transliteration via `keyboard-core` JNI) |
| `privacy` | Consents, history, data export, deletion, crypto-shredding, retention purges |
| `notifications` | Push (FCM/APNs), SMS (OTP and reminders), email; templated and localised |
| `configuration` | Feature flags, remote config, minimum supported app versions |
| `administration` | Admin RBAC, four-eyes approvals, audit |
| `platform` | Outbox, scheduler, idempotency, rate limiting, correlation IDs, error mapping |

Modules expose Java interfaces and publish domain events through the outbox. Cross-module table access is forbidden, and Spring Modulith verification enforces this in CI.

### 8.3 Cross-cutting conventions

- **Transactions:** one per command. External calls (AI, payments) happen **outside** database transactions. Reservations and settlement are short transactions with row locks.
- **Idempotency:** an `Idempotency-Key` is required on state-changing billable calls. Keys are stored with a hash of the request body for 24 hours. Same key + different body → `409 IDEMPOTENCY_KEY_REUSED`.
- **Time:** UTC `timestamptz` everywhere. Market time zones are used only for quota windows and display.
- **IDs:** UUIDs. High-volume append tables use UUIDv7 generated by the application (D18).
- **Outbox:** a domain change and its event are written in the same transaction. A relay (db-scheduler) delivers each event to its handlers at least once, so handlers must be idempotent.

## 9. Identity, devices and sessions

### 9.1 Account model

| Account type | How created | Can do | Plan |
|---|---|---|---|
| None (no account) | — | All offline typing | — |
| `GUEST` | `POST /auth/guest` with a valid Play Integrity / App Attest verdict | Small AI trial (`GUEST` plan) | GUEST |
| `REGISTERED` | Phone OTP, Google, Apple, or email+password | Everything; purchases; sync; organisations | FREE or paid |

When a guest signs in, the guest account is **merged**: preferences, consents and remaining trial usage move to the registered user, and the guest row gets `merged_into_user_id`.

### 9.2 Sign-in methods

- **Phone OTP (primary in Ethiopia):** E.164 numbers; 6-digit code; at most 5 attempts per challenge; resend cooldown; per-number and per-IP limits; codes stored only as hashes. The SMS provider is pluggable (local aggregator for +251, an international provider for diaspora numbers).
- **Google / Apple:** the client sends an ID token, which the server verifies against the issuer's JWKS. Sign in with Apple is mandatory on iOS whenever other social logins are offered.
- **Email + password:** Argon2id hashes, breached-password check, verification email.
- **Admins:** workforce OIDC with MFA only. Consumer credentials never grant admin roles.

### 9.3 Sessions and tokens

- **Access token:** JWT (EdDSA or ES256), 15 minutes, claims `sub`, `sid`, `did` (device), `plan` (hint only), `scope`.
- **Refresh token:** opaque, 256-bit, stored as a SHA-256 hash, sliding 60 days. It **rotates on every use**, and `token_family_id` links the chain. If an already-rotated token is presented, the whole family is revoked and the user is notified.
- **Grace window for lost responses (amendment, ADR 0007 D8):** on slow mobile networks a refresh response can be lost, and the client then retries with the token that was just rotated. Such a retry rotates again instead of revoking the family only if it arrives within a short window of that token's *first* rotation (default 30 s, at most 2 min, 0 disables) and the successor issued by the lost response has never been presented. The unused successor is revoked (`SUPERSEDED_RETRY`) and a new one is issued in the same family. Presenting a superseded successor later counts as reuse, because two parties then hold tokens of the family. Every other reuse (after the window, successor already used, a token two or more generations old) revokes the family as above. Clients still serialise refresh calls.
- Tokens on the device live in Android Keystore-backed storage or the iOS Keychain (shared access group).
- Logout revokes the session. "Sign out everywhere" revokes all of the user's families.

### 9.4 Devices and integrity

- `POST /devices` registers an installation (platform, app version, OS version, distribution channel, push token, and iOS Full Access state when the keyboard reports it).
- Attestation is anti-replay: the client first gets a single-use nonce from `POST /auth/attestation-nonces`, then embeds it in the Play Integrity or App Attest request.
- Attestation verdicts are stored on `user_devices`. They are required for guest AI and used as a risk signal for rate limits. They never gate offline typing.

## 10. Preferences and personalisation

### 10.1 Preferences

`user_preferences` stores:

- source detection mode and default destination
- default translation style
- calendar system, time notation and numeral style
- default formality and addressee gender
- AI processing preference (`SERVER | PREFER_ON_DEVICE | ON_DEVICE_ONLY`)
- history enablement and retention
- keyboard look and feel

Clients cache preferences and work fully offline with the cached copy. Sync uses optimistic concurrency: each synced document (preferences, typing languages, app rules) carries an integer `revision`. A write with a stale revision returns `409 REVISION_CONFLICT`, and the client merges the server copy and retries.

### 10.2 Typing languages

`/me/typing-languages` replaces the user's ordered list. Each item carries `inputMode`, `transliterationProfileCode`, `conversionTrigger` (`LIVE_COMPOSING | WORD_BOUNDARY | MANUAL`), `showTransliterationCandidates` and `unknownTokenBehavior` (`PRESERVE_LATIN | SUGGEST_ONLY`).

The server resolves the profile code to an `ACTIVE` profile for that language. When a profile is retired, clients fall back to the newest active profile of the same language and notify the user. Existing rows stay valid and editable (the v1.4 trigger defect F14 is fixed).

### 10.3 Destination profiles (replaces per-contact preferences)

A keyboard is never told who the recipient is, so per-contact preferences are implemented as follows (D10):

1. **Destination profiles** (`user_destination_profiles`). User-named contexts such as "Mum" or "Work" carry a destination language, formality, addressee gender and default tone. The label is encrypted with the user's data key. The destination chip lists these profiles next to plain languages.
2. **Per-app rules (Android only)** (`user_app_destination_rules`). These map `HMAC(userKey, packageName)` to a profile or language. The keyboard computes the HMAC locally from `EditorInfo.packageName`, so the server never learns which apps the user has.
3. **Local recency.** The keyboard MAY pre-select the profile the user last chose in the same app. This state stays on the device.
4. **Optional contact linking** happens in the companion app and stays **on-device only**. Contact data is never uploaded.

The profile's addressee settings feed cultural intelligence (§11.5) automatically.

### 10.4 Personal writing style

- `user_style_profiles` (per language) holds explicit settings: formality, length preference, emoji usage, greeting and sign-off (encrypted), and preferred phrases (encrypted).
- **Learned hints (opt-in, `PERSONAL_STYLE` consent).** The device derives structured hints such as average sentence length, preferred greetings and formality drift. It learns only from AI results the user **accepted** and from their explicit edits to them, never from general keystrokes. Only the structured hints are synced.
- At request time, the gateway injects style as **structured parameters** into the prompt template, never as raw user samples.
- Users can view, edit, reset or disable their style at any time.

## 11. AI platform

### 11.1 Gateway pipeline

Every server AI call goes through the same pipeline:

```text
authenticate → validate (size, languages, options) → resolve entitlements → RESERVE usage
→ select route (capability × routing policy × data policy × health) → build prompt (template vN + style + glossary + cultural options)
→ call provider (timeout, circuit breaker, single fallback if quality-equivalent) → guardrails → SETTLE usage
→ write ai_requests metadata (no content) → optional encrypted history write (opt-in) → respond
```

On failure, usage is RELEASEd and the client receives a typed Problem response (§14.4).

### 11.2 Providers, models and routing

- Providers are recorded in `ai_providers`. Each has a **data policy**: `zero_data_retention`, `trains_on_customer_data`, `processing_regions` and `dpa_signed_at`. Providers that train on customer data MUST NOT be routed user content.
- Models are recorded in `ai_models`, with kind `LLM | MT | ASR | TTS | LID`, deployment `SERVER | ON_DEVICE`, and licence.
- **Capability matrix** (`ai_language_capabilities`): which model supports which feature for which language pair, at which `quality_tier` (`UNVERIFIED | EXPERIMENTAL | BETA | PRODUCTION`). Only `BETA` and `PRODUCTION` capabilities are exposed to users. `BETA` capabilities carry a visible quality notice.
- **Routing policies** (`ai_routing_policies`): priority, `traffic_weight` (for A/B tests and gradual migration), a `requires_zero_retention` flag, and validity windows. Organisation data policies (§13) further restrict the eligible routes.
- **Candidate provider classes to evaluate:** commercial MT APIs with Amharic/Tigrinya/Oromo support, frontier LLMs (strong on rewriting, tone and drafting; Amharic quality varies by model and MUST be measured), and commercial ASR/TTS with `am-ET` support.
- Open-weight models MAY be self-hosted only after a **licence check**. Several well-known multilingual MT and speech models (for example NLLB-200, MMS and SeamlessM4T) are released under **non-commercial** licences and are not eligible for commercial use.

### 11.3 Task catalogue

| Feature code | Endpoint | Input | Output |
|---|---|---|---|
| `TRANSLATE` | `POST /ai/translate` | text ≤ 5,000 chars (keyboard UI caps at 2,000), source (or auto), destination, style, addressee, cultural options, glossary, destination profile | translation, detected source, alternatives, notes |
| `GRAMMAR_CORRECTION` | `POST /ai/transform` | text, mode `GRAMMAR \| SPELLING_ONLY` | corrected text plus edit spans |
| `REWRITE` | `POST /ai/transform` | text, mode `NATURAL \| SHORTEN \| EXPAND \| CLARIFY` | rewritten text |
| `TONE_ADJUST` | `POST /ai/transform` | text, tone `FRIENDLY \| POLITE \| PROFESSIONAL \| CONCISE \| APOLOGETIC \| HONORIFIC` | rewritten text |
| `SUMMARIZE` | `POST /ai/transform` | text ≤ 20,000 chars (companion), target length | summary |
| `EXPLAIN_TEXT` | `POST /ai/transform` | text, explanation language | meaning, idioms and cultural notes |
| `SCRIPT_CONVERSION` | `POST /ai/transform` | romanised Amharic/Tigrinya sentence | Ge'ez text plus per-token alternatives |
| `SMART_REPLY` | `POST /ai/replies` | user-supplied context text, reply language, count 1–5 | short replies |
| `DRAFT_DOCUMENT` | `POST /ai/drafts` | document type, language, purpose, key points, recipient formality | subject (optional) and body |
| `DETECT_LANGUAGE` | `POST /ai/detect-language` | text | ranked languages and scripts |

`GET /ai/capabilities` tells clients which tasks and pairs are currently available, at which tier and with which execution mode (server or on-device), so the UI never offers what cannot be served.

### 11.4 Prompting and guardrails

- **Templates** (`ai_prompt_templates`) are versioned per feature, language scope and model family. Promoting a template requires an evaluation run that is no worse than the current version.
- User content is always passed as **data**, delimited and labelled as untrusted. Models get no tools. Instructions embedded in user text are ignored, and prompt-injection tests are part of the evaluation suite.
- **Output checks:**
  - length bounds
  - expected script (for example ≥ 90% Ethiopic letters for `am`/`ti` output, excluding protected tokens)
  - language ID matches the requested destination
  - no-op detection (output identical to input when a change was expected)
  - protected tokens (names, URLs, numbers, glossary terms) preserved
- A failed check triggers one retry on the same route and then a fallback route. If both fail, the response is `OUTPUT_GUARDRAIL_FAILED` with no charge.
- **Safety:** provider safety filters plus a lightweight policy check. Requests are not refused merely because of the language they are in.

### 11.5 Cultural intelligence

Amharic and Tigrinya inflect verbs, pronouns and possessives for the addressee's **gender, number and honorific status**. English "Are you coming?" has several correct Amharic renderings depending on who is addressed. The design therefore treats addressee context as first-class input:

- **`addressee`**: `formality` is `INFORMAL | FORMAL | HONORIFIC` and `gender` is `MASCULINE | FEMININE | PLURAL | UNKNOWN`. Its value comes from the request, then the destination profile, then user preferences.
- When `gender = UNKNOWN` and the target language inflects for it, the response returns the most neutral acceptable rendering plus **alternatives** for each variant. The review sheet shows them as chips, for example "to a man / to a woman / formal".
- **`calendar`** (`GREGORIAN | ETHIOPIAN`) and **`timeNotation`** (`INTERNATIONAL | ETHIOPIAN`) convert dates and times when translating between Ethiopian and international contexts. Example: 7:00 international ↔ 1:00 in the Ethiopian clock, "በጠዋቱ 1 ሰዓት". The response includes a note whenever a conversion was applied.
- **`numerals`**: `WESTERN | ETHIOPIC`.
- **`adaptIdioms`**: when true, idioms are rendered by meaning and a note explains the original.
- **Notes** (type `CULTURAL`, `AMBIGUITY`, `CONVERSION`, `QUALITY`) are short, localised and optional to display.
- Native-speaker evaluation of honorific, gender and idiom handling is part of the quality gate for `PRODUCTION` tier.

### 11.6 Idempotency and retries for AI

This replaces v1.4, where the original-result promise contradicted the no-content rule (F3):

- `Idempotency-Key` is required on every AI POST. Usage is reserved and settled **at most once per key**.
- An encrypted ephemeral result cache (≤ 10 minutes; AES-GCM under the **user's data key**, so crypto-shred covers it; keyed by user + idempotency key and checked against an encrypted request fingerprint; stored in an UNLOGGED PostgreSQL table so it never reaches WAL archives, backups or replicas) lets a retry return the cached result without a second provider call or charge (B5.4, ADR 0017).
- If no cache entry exists, the server recomputes **without charging again**.
- PostgreSQL never stores AI input or output text in plaintext. AI output exists only as ciphertext under the user's data key: in the ≤ 10-minute unlogged replay cache above, and in encrypted opt-in history.

### 11.7 History (opt-in)

- `user_history_items` stores the source and result ciphertext under the user data key, together with feature, languages, a favourite flag and `expires_at`.
- Retention is user-selectable from 1 to 365 days (default 30).
- History is written only when **both** `HISTORY_SYNC` access and the `SAVE_AI_HISTORY` consent are active.
- Users can delete one item, delete everything, or turn history off. Turning it off deletes existing items.

### 11.8 Evaluation and feedback loop

- `ai_evaluation_runs` records automatic metrics per capability (chrF++, COMET where the language is supported, script-validity rate) and native-speaker ratings (adequacy, fluency, register/honorific correctness).
- A `quality_tier` can be promoted only on evidence from evaluation runs. Every promotion is audited.
- `ai_feedback` records a rating (−1/+1), reason codes (`WRONG_MEANING`, `WRONG_FORMALITY`, `WRONG_GENDER`, `NAMES_CHANGED`, `UNNATURAL`, `OTHER`) and optional encrypted comment text, which is stored only with `FEEDBACK_TEXT` consent. Feedback is **never** used to train third-party models.

### 11.9 On-device AI (P3)

- **Model types** are distributed as language resources: `LANGUAGE_ID_MODEL`, `SPELL_MODEL`, `TRANSLATION_MODEL`, `ASR_MODEL`, `TTS_VOICE` and `ON_DEVICE_LLM`. Manifests declare `deviceRequirements` (`minRamMb`, accelerator, minimum OS version).
- **Runtimes:** ONNX Runtime / LiteRT on Android, Core ML on iOS. The runtime is chosen per model and benchmarked on the reference devices.
- **Placement:**
  - Small models (language ID, spell) MAY run inside the keyboard.
  - Larger models run in the **companion app** only on iOS, because of the extension memory ceiling.
  - On Android, larger models MAY run in a separate `:ai` process to protect the IME's memory.
- **Routing:** `/ai/capabilities` advertises `execution: ON_DEVICE | SERVER | HYBRID` per task. With `PREFER_ON_DEVICE`, on-device runs first and falls back to server only with the user's consent per action. With `ON_DEVICE_ONLY`, server AI is never called.
- **Entitlement:** `ON_DEVICE_AI_MODELS` is checked offline with the signed entitlement token (§15.5). On-device inference is not metered.
- Downloads are Wi-Fi-only by default, resumable, show their size upfront, and can be deleted.

## 12. Voice and realtime

### 12.1 Dictation and speech jobs

| Flow | Path |
|---|---|
| Short utterance (≤ 30 s) | Inline audio in `POST /speech/transcriptions` (Opus/OGG or PCM16) |
| Longer audio | `POST /speech/upload-sessions` → signed PUT URL (≤ 5 min expiry, size-capped) → `POST /speech/transcriptions {uploadSessionId}` → poll `GET /speech/transcriptions/{jobId}` |
| Streaming dictation (Android keyboard, P1+) | Realtime session in `DICTATION` sub-mode over WebSocket (§12.3) |

- Metering uses **provider-confirmed audio seconds, rounded up to a whole second per request** (F8). Quotas are displayed in minutes.
- Audio is never retained by default. Temporary objects are deleted right after processing, and a 24-hour lifecycle rule backs this up. `voice_jobs` stores metadata only.

### 12.2 Text-to-speech and pronunciation

- `POST /speech/synthesis` returns short-lived audio, and `GET /speech/voices` lists voices by language. Usage is metered in characters.
- **Pronunciation mode** returns audio plus a romanised reading. The romanisation is produced **locally** by reverse transliteration (Ge'ez → Latin) through `keyboard-core`, which works offline and costs nothing.
- TTS output for public, non-personal strings (for example UI phrases or dictionary headwords) MAY be cached at the CDN. User text is never cached.

### 12.3 Realtime sessions (voice-to-voice, live conversation, streaming dictation)

**Session setup:**
1. `POST /realtime/sessions` with `mode` and the language pair. The server checks entitlements and remaining seconds.
2. The response contains `sessionId`, `websocketUrl`, a single-use `ticket` (60-second expiry, hash stored in `realtime_sessions`) and the required audio format.
3. The client connects to `wss://…/realtime/v1` within 60 seconds and passes the ticket in the `Sec-WebSocket-Protocol` header as `ticket.<ticket>`. The ticket is never put in the URL, where proxies would log it.
4. Close codes: `4001` invalid or expired ticket, `4002` quota exhausted, `4003` maximum session duration reached, `4500` upstream provider failure.

**Protocol:**

| Direction | Message | Purpose |
|---|---|---|
| Client → server | Binary frames | PCM16 16 kHz mono (or Opus) audio, 20–40 ms frames |
| Client → server | `{"type":"start","speaker":"A"}` | Start of a turn |
| Client → server | `{"type":"speaker_switch","speaker":"B"}` | Switch speaker |
| Client → server | `{"type":"stop"}` | End of turn / session |
| Server → client | `partial_transcript` | Live ASR hypothesis |
| Server → client | `final_transcript` | Committed segment with language |
| Server → client | `translation` | Translated segment (with addressee and notes) |
| Server → client | `tts_audio` | Binary or base64 audio chunks for the translated segment |
| Server → client | `usage` | Seconds used and remaining (about every 10 s) |
| Server → client | `error` | Typed error; the session may continue or close |

**Pipeline:** streaming ASR with VAD-based endpointing → MT/LLM per final segment (cultural options apply) → streaming TTS. Each stage runs through the AI gateway's routing and data policies.

**Latency budget:** ≤ 2.5 s p50 and ≤ 4 s p95 from end of speech to the start of translated audio, measured on 4G in Addis Ababa.

**Billing:** server-measured wall-clock seconds of open audio streaming. Usage is RESERVEd in 60-second blocks and SETTLEd on close. Sessions are closed when allowance runs out, after a 15-second warning.

**Storage and scaling:** no audio or transcript is stored, only `realtime_sessions` metadata. Realtime runs on the `realtime` profile instances with sticky WebSocket routing, and it can be extracted into its own service without contract changes.

**Placement:** conversation mode and voice-to-voice are **companion-app** features on both platforms. The Android keyboard uses only streaming dictation.

## 13. Enterprise and partner products

### 13.1 Organisations

- `organizations` record a data policy: `zero_retention_required` and `allowed_processing_regions`.
- Members have roles `OWNER | ADMIN | BILLING | MEMBER`. Invitations go by email or phone.
- **Seats:** an `organization_subscriptions` row (plan `BUSINESS`, `seat_count`, contract period) is invoiced through the `PARTNER`/`ADMIN` sales channel. `organization_seat_assignments` give members the BUSINESS entitlements (D8 resolution).
- Organisation data policy **overrides** personal settings while a member is acting in the organisation context. For example, when `zero_retention_required` is set, routes without ZDR are excluded and history is disabled.

### 13.2 Glossaries

- Glossaries are owned by a user (`CUSTOM_GLOSSARY` entitlement) or by an organisation.
- Each has a source/target language pair and an enforcement mode, `PREFER` or `STRICT`. Entries carry `do_not_translate`, case sensitivity and notes.
- Enforcement uses placeholder protection before the provider call plus verification afterwards. A violation triggers a retry, then the response is returned with a `QUALITY` note.

### 13.3 Partner API (`/partner/v1`)

- **Authentication:** `Authorization: Bearer sk_live_…` or `sk_test_…`. Keys are stored as `key_prefix` + SHA-256 hash, and the secret is shown once at creation. Keys carry scopes (`translate`, `transform`, `transliterate`, `detect_language`, `speech`, `usage_read`) and limits (per minute and per day).
- **Endpoints:** `translate`, `transform`, `transliterate` (server-side `keyboard-core`, deterministic, both directions), `detect-language`, `speech/transcriptions`, `languages` and `usage`.
- **Metering:** usage is charged to the `api_client`, under the organisation's plan or contract allowances, then credits. Monthly usage export is available for invoicing.
- **Environments:** sandbox keys hit sandbox routes (deterministic mock providers) and never incur provider cost.
- The partner API has its own rate limits and quotas, **isolated** from consumer traffic so a noisy integration cannot harm keyboard users.

## 14. API design

### 14.1 Conventions

- **Surfaces:**
  - `/api/v1`: apps, user JWT.
  - `/partner/v1`: API key.
  - `/admin/v1`: workforce OIDC + MFA, not in the public contract.
- **Format:** JSON with camelCase. UUID identifiers. ISO-8601 UTC timestamps. Money as `{ "amountMinor": 49900, "currency": "ETB", "exponent": 2 }`.
- **Request headers:** `Idempotency-Key` (UUID) on billable or state-changing commands; `X-Client-Platform`, `X-App-Version` and `X-Client-Surface` (`KEYBOARD | COMPANION`) for compatibility and analytics. Clients send `Accept-Language` for localised messages.
- **Response headers:** `X-Correlation-Id`, plus the RateLimit headers (`RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`) and `Retry-After` on 429/503.
- **Pagination:** cursor-based (`cursor`, `limit` ≤ 100, `nextCursor`).
- **Compatibility:** additive changes only within `v1`; clients MUST ignore unknown fields. `GET /config` returns `minSupportedAppVersion` for forced upgrades.
- **Text limits:** enforced per task before any provider call.

### 14.2 Endpoint summary

The OpenAPI file is authoritative. This table is an overview.

| Area | Endpoints |
|---|---|
| Auth | `POST /auth/attestation-nonces`, `/auth/otp/start`, `/auth/otp/verify`, `/auth/oauth/{provider}`, `/auth/email/register`, `/auth/email/login`, `/auth/password/forgot`, `/auth/password/reset`, `/auth/refresh`, `/auth/logout`, `/auth/guest` |
| Devices | `POST /devices`, `PATCH/DELETE /devices/{deviceId}`, `POST /devices/{deviceId}/attestations`, `POST /devices/{deviceId}/resource-installations` |
| Account and privacy | `GET/PATCH /me`, `POST/DELETE /me/deletion-request`, `POST /me/data-exports`, `GET /me/data-exports/{exportId}`, `GET /me/consents`, `PUT /me/consents/{consentCode}` |
| Config | `GET /config`, `GET /ai/capabilities` |
| Languages | `GET /languages`, `GET /languages/{languageCode}/transliteration-profiles`, `GET /language-resources`, `GET /language-resources/{resourceId}`, `GET /resource-signing-keys` |
| Preferences | `GET/PUT /me/preferences`, `GET/PUT /me/typing-languages`, `/me/destination-profiles` (CRUD), `GET/PUT /me/app-destination-rules`, `GET/PUT /me/style-profiles/{languageCode}`, `GET/POST /me/dictionary/changes` |
| AI | `POST /ai/translate`, `/ai/transform`, `/ai/replies`, `/ai/drafts`, `/ai/detect-language`, `/ai/feedback` |
| History | `GET/DELETE /me/history`, `GET/PATCH/DELETE /me/history/{itemId}` |
| Speech | `POST /speech/upload-sessions`, `/speech/transcriptions`, `GET /speech/transcriptions/{jobId}`, `POST /speech/translations`, `/speech/synthesis`, `GET /speech/voices` |
| Realtime | `POST /realtime/sessions`, `GET /realtime/sessions/{sessionId}`, `POST /realtime/sessions/{sessionId}/end`; WebSocket `/realtime/v1` |
| Entitlements and usage | `GET /me/entitlements`, `/me/usage`, `/me/credits`, `/me/credits/transactions` |
| Billing | `GET /billing/options`, `/billing/products`; `POST /billing/promotions/validate`; `POST /billing/orders`; `GET /billing/orders/{orderId}`; `POST /billing/orders/{orderId}/cancel`; `POST /billing/orders/{orderId}/attempts`; `GET …/attempts/{attemptId}`; `POST /billing/orders/{orderId}/manual-transfer-claims`; `POST /billing/mandates`; `DELETE /billing/mandates/{mandateId}`; `POST /billing/store-purchases`; `GET /me/subscriptions`; `POST /me/subscriptions/{id}/renew`, `/cancel-renewal`; `GET /me/receipts`, `/me/receipts/{receiptId}` |
| Webhooks | `POST /billing/webhooks/{providerCode}/{accountCode}`, `POST /billing/store-notifications/{providerCode}` |
| Organisations | `GET /me/organizations`, `POST /organizations`, `GET/PATCH /organizations/{orgId}`, members, invitations, seats, usage, API clients and keys |
| Glossaries | `GET/POST /glossaries`, `GET/PATCH/DELETE /glossaries/{glossaryId}`, `GET /glossaries/{glossaryId}/entries`, `POST /glossaries/{glossaryId}/entries:batch` |
| Partner | `POST /partner/v1/translate`, `/transform`, `/transliterate`, `/detect-language`, `/speech/transcriptions`; `GET /partner/v1/speech/transcriptions/{jobId}`, `/usage`, `/languages` |

### 14.3 Example: translation with cultural options

```http
POST /api/v1/ai/translate
Authorization: Bearer eyJ…
Idempotency-Key: 6f1c2b7e-0d7a-4b8e-9a52-3c4a1d2e9f10
X-Client-Surface: KEYBOARD
```

```json
{
  "text": "Are you coming to the coffee ceremony tomorrow at 9 am?",
  "sourceLanguage": "en",
  "destinationLanguage": "am",
  "style": "NATURAL",
  "addressee": { "formality": "HONORIFIC", "gender": "UNKNOWN" },
  "culturalOptions": { "calendar": "GREGORIAN", "timeNotation": "ETHIOPIAN", "numerals": "WESTERN", "adaptIdioms": true },
  "preserveNames": true
}
```

```json
{
  "requestId": "0193b2c4-7e1a-7c3e-9f00-5b1d2a6c8e44",
  "detectedSourceLanguage": "en",
  "destinationLanguage": "am",
  "translatedText": "ነገ ከጠዋቱ 3 ሰዓት በቡና ሥነ ሥርዓቱ ላይ ይገኛሉ?",
  "alternatives": [],
  "notes": [
    { "type": "CONVERSION", "message": "9:00 AM was written as 3 o'clock in the Ethiopian clock." }
  ],
  "qualityTier": "BETA",
  "usage": { "featureCode": "TRANSLATE", "unitsCharged": 1, "usageSource": "PLAN_ALLOWANCE", "remaining": 9 }
}
```

The honorific form needs no gender alternatives, which is why `alternatives` is empty. The example output is illustrative and is not an approved translation.

### 14.4 Errors

All errors use RFC 9457 `application/problem+json`:

```json
{
  "type": "https://api.example.com/problems/quota-exceeded",
  "title": "Daily translation allowance used",
  "status": 429,
  "detail": "You have used all 10 free translations for today.",
  "instance": "/api/v1/ai/translate",
  "code": "QUOTA_EXCEEDED",
  "correlationId": "d8d14c6e-f6da-4c1b-8be8-6be9b5c4f1f5",
  "featureCode": "TRANSLATE",
  "resetAt": "2026-11-18T21:00:00Z",
  "creditBalance": 0,
  "upgradeOptions": ["PLAN_PREMIUM", "CREDITS_500"]
}
```

These stable `code` values are part of the contract, and clients switch on `code`, never on `title`. The canonical list is the `Problem.code` description in the OpenAPI contract; new codes are added there first. Framework errors map to `NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405), `NOT_ACCEPTABLE` (406) and `UNSUPPORTED_MEDIA_TYPE` (415); malformed bodies and failed validation map to `VALIDATION_FAILED` (400) with per-field `errors[]`; `APP_VERSION_UNSUPPORTED` is HTTP 426, and unexpected failures are `INTERNAL_ERROR` (500) with no detail.

## 15. Plans, entitlements, usage and credits

### 15.1 Commercial model

- **Always free (R-2):** typing in every mode, offline dictionaries and suggestions, local transliteration, and local reverse transliteration (pronunciation romanisation).
- **GUEST:** a small daily trial of text AI for attested anonymous installs.
- **FREE:** daily text-AI allowance, a little dictation and TTS, history and dictionary sync.
- **PREMIUM / PREMIUM_PLUS:** larger allowances, tone, replies, summaries, drafting, voice translation, voice-to-voice, personal style and on-device models. PREMIUM_PLUS adds live conversation and glossaries.
- **AI credit packs:** top-ups that are used after the plan allowance runs out. They are digital goods, so store billing rules apply wherever the store policy does.
- **BUSINESS:** per-seat organisation plan with glossaries, zero-retention routing, and pooled partner-API usage under contract.

### 15.2 Starter entitlement matrix (provisional)

Seeded with `quotaPolicyVersion = PROVISIONAL_V2`. These values MUST be re-validated against modelled provider cost and closed-beta usage before public launch. All values are editable data.

| Feature (unit) | GUEST | FREE | PREMIUM | PREMIUM_PLUS | BUSINESS (per seat) |
|---|---:|---:|---:|---:|---:|
| `TRANSLATE` (req) | 3/day | 10/day | 600/mo | 2,000/mo | 3,000/mo |
| `GRAMMAR_CORRECTION` (req) | 3/day | 10/day | 600/mo | 2,000/mo | 3,000/mo |
| `REWRITE` (req) | 1/day | 3/day | 300/mo | 1,000/mo | 2,000/mo |
| `TONE_ADJUST` (req) | — | — | 300/mo | 1,000/mo | 2,000/mo |
| `SMART_REPLY` (req) | — | — | 150/mo | 500/mo | 1,000/mo |
| `SUMMARIZE` (req) | — | — | 100/mo | 500/mo | 1,000/mo |
| `EXPLAIN_TEXT` (req) | — | 3/day | 100/mo | 500/mo | 1,000/mo |
| `DRAFT_DOCUMENT` (req) | — | — | 50/mo | 200/mo | 500/mo |
| `SCRIPT_CONVERSION` (req) | 2/day | 5/day | 300/mo | 1,000/mo | 2,000/mo |
| `SPEECH_TO_TEXT` (sec) | — | 10 min/mo | 60 min/mo | 300 min/mo | 600 min/mo |
| `SPEECH_TRANSLATE` (sec) | — | — | 30 min/mo | 150 min/mo | 300 min/mo |
| `TEXT_TO_SPEECH` (chars) | — | 5,000/mo | 100,000/mo | 500,000/mo | 1,000,000/mo |
| `VOICE_TO_VOICE` (sec) | — | — | 15 min/mo | 90 min/mo | 180 min/mo |
| `LIVE_CONVERSATION` (sec) | — | — | — | 60 min/mo | 120 min/mo |
| `HISTORY_SYNC` | — | ✓ | ✓ | ✓ | ✓ |
| `DICTIONARY_SYNC` | — | ✓ | ✓ | ✓ | ✓ |
| `PERSONAL_STYLE` | — | — | ✓ | ✓ | ✓ |
| `CUSTOM_GLOSSARY` | — | — | — | ✓ | ✓ |
| `ON_DEVICE_AI_MODELS` | — | — | ✓ | ✓ | ✓ |

`DETECT_LANGUAGE` and every typing feature are unmetered and not in this table. Speech is metered internally in seconds, so 10 min = 600 s.

### 15.3 Metering algorithm

1. **Resolve** the subject (user, organisation or API client) and the effective plan (§15.5).
2. **Window.** DAILY windows use the market time zone (`users.time_zone`, default `Africa/Addis_Ababa`). MONTHLY windows are anchored to the active paid period start; for FREE they are calendar months in the market time zone.
3. **Reserve.** In one short transaction, `SELECT … FOR UPDATE` the `usage_counters` row, check that `used + reserved + estimate ≤ included`, and if so increment `reserved_units` and append a `usage_events` RESERVE. If the allowance is not enough, reserve the remainder against credits (§15.4). If neither covers it, return `QUOTA_EXCEEDED`.
4. **Execute** the AI call outside the transaction.
5. **Settle.** Convert the reservation into actual units (SETTLE), or RELEASE it on failure. Text requests are always 1 unit. Audio uses actual seconds. TTS uses actual characters.
6. **Sweep.** Reservations older than 15 minutes are released by the scheduler.
7. Client-side counters are display-only. The server is authoritative.

### 15.4 AI credits

- Purchases and grants create `ai_credit_lots` (`initial_units`, `remaining_units`, `expires_at`; default validity 365 days).
- Consumption draws from the **lots that expire soonest first**, and every movement is a `ai_credit_transactions` ledger row with `balance_after`.
- Cost = `units × feature_catalog.credit_cost_per_unit`, kept exactly to 6 decimal places because audio is costed per second. Balances are shown rounded down to whole credits. For example, one translation costs 1 credit and one minute of dictation costs 10 credits.
- When a credit purchase is refunded, the unconsumed remainder of that lot is reversed. Consumed credits are handled by the refund policy and never driven negative.
- A daily job expires lots and writes `EXPIRY` transactions.

### 15.5 Entitlement resolution and the entitlement token

- **Effective plan:**
  - Take the highest `plan_rank` among the user's subscriptions with status `ACTIVE` or `GRACE_PERIOD` and the user's active organisation seat assignments.
  - If there are none: `FREE` for registered users, `GUEST` for guests.
  - Allowances are **not** summed across subscriptions.
- **Overlapping purchases:**
  - Buying the same plan while active **extends** it (a new allocation starts at `current_period_end`).
  - Buying a higher plan is an **upgrade** (§16.6).
- **`GET /me/entitlements`** returns resolved features (access, included, used, remaining, reset time) plus an **entitlement token**:
  - A JWS signed with Ed25519 (`kid` from a rotating key set published at `/.well-known/jwks.json`).
  - Claims (names as in the contract's `EntitlementToken`; amended in B4.4, ADR 0013): `iss`, `aud`, `sub`, `deviceId`, `planCode`, `features` (booleans only), `policyVersion`, `ver`, `iat`, `exp` (+24 h), `offlineGraceUntil` (+72 h), `jti`. `GET /me/entitlements/token` issues a token on its own.
  - Clients use it **only** for offline gates, currently `ON_DEVICE_AI_MODELS` and premium on-device packs. Every server-metered feature is re-checked on the server.

## 16. Billing and payments

### 16.1 Principles

1. **Entitlement ≠ payment rail.** Plans and entitlements say what a user may do. Billing products and prices say what is sold. Providers and methods say how it is paid. Every successful payment, whatever the rail, converges on `user_subscriptions` or `ai_credit_lots`.
2. **Server-priced.** The client sends a product code and context, never an amount.
3. **Verify, then grant.** Redirects and callbacks are UX only. A grant happens only after server-to-server verification of reference, amount, currency, environment and merchant account.
4. **Fail closed.** Missing adapter, inactive provider, account or method, an unapproved market/channel policy, or an unconfirmed recurring capability all mean the option is not offered.
5. **Money:** integer minor units plus the currency exponent; no floats; ETB is first-class.
6. **Append-only ledgers** for financial transactions and credits, with refunds, reversals and chargebacks as new rows.

### 16.2 Building blocks

| Concept | Tables |
|---|---|
| Plans and entitlements | `subscription_plans`, `plan_entitlements`, `feature_catalog` |
| Sellable items and price books | `billing_products`, `billing_prices` (market, channel, currency, period, renewal model, tax behaviour; non-overlapping active validity), `billing_currencies`, `promotion_codes` |
| Providers and methods | `payment_providers` (global Active switch + lifecycle status), `provider_payment_methods` (provider × country × currency) |
| Merchant accounts | `payment_provider_accounts` (provider × country × currency × environment; secret references only), `payment_provider_account_methods` (the final method gate for each account) |
| Market policy | `billing_market_channel_policies` (country × platform × distribution channel → purchase flow; fails closed) |
| Gateway payments | `payment_orders`, `payment_attempts`, `payment_transactions`, `payment_refunds`, `payment_webhook_events`, `manual_payment_reviews`, `payment_receipts` |
| Recurring | `payment_mandates`, `payment_mandate_events` (+ `PAYMENT_MANDATE` consent) |
| App stores | `store_purchases`, `store_purchase_events`, `external_product_mappings` |
| Fulfilment | `user_subscriptions`, `subscription_payment_allocations`, `subscription_events`, `ai_credit_lots` |
| Organisations | `organization_subscriptions`, `organization_seat_assignments` |
| Finance ops | `reconciliation_runs`, `reconciliation_items` |

### 16.3 Checkout eligibility predicate

A gateway method is offered, and an attempt can be created, only if **all** of the following hold. The database guard trigger enforces items 1 and 3–5 as defence in depth.

1. `payment_providers.is_active = TRUE` and `provider_status = 'ACTIVE'`.
2. An adapter is registered for `provider_code`. A database row alone never creates an integration.
3. A merchant account matches the order's country, currency and environment, with `is_active = TRUE` and `account_status = 'ACTIVE'`.
4. The base `provider_payment_methods` row is enabled and `AVAILABLE`.
5. The account-method row is enabled and `AVAILABLE`, and the amount is within its minimum and maximum.
6. The market/channel policy is `APPROVED`, within its validity window, and permits the purchase flow for this client build.
7. For recurring charges: the base method and the account method both support auto-renew, a provider-approved mandate exists, and the user's `PAYMENT_MANDATE` consent is unrevoked.

Clients see only public method codes. Account IDs and secret references never leave the server.

### 16.4 Ethiopia payment strategy

- **Chapa first.** Server-side hosted checkout, ETB, and the methods enabled on the merchant account discovered at runtime (for example Telebirr, CBE Birr, M-Pesa, bank transfer and cards, as listed by the provider). Live method lists are refreshed from the provider's method-discovery API into `payment_provider_account_methods`. Direct/inline wallet charges are added only when approved for the account.
- **ArifPay second.** Hosted checkout, C2B, verification. Its recurring capability stays `APPROVAL_REQUIRED` until confirmed in writing for the specific method and account and proven in sandbox and live.
- **Telebirr Direct (optional).** Its subscription/mandate flow requires a merchant-applied mandate template and customer signing in the Telebirr app. It is used only after approval.
- **SantimPay, M-Pesa direct, Flutterwave, Pesapal, Paystack, Interswitch, Stripe, PayPal** are seeded **inactive**. Each is activated only once merchant onboarding, legal entity, settlement, markets, methods, webhook verification and sandbox tests are complete. M-Pesa *through* Chapa is a Chapa method, not the `MPESA` provider.
- **Manual bank transfer (optional fallback).** Each order gets a unique reference. A user-submitted reference or receipt is a *claim*, which is reconciled against bank data or approved by an authorised finance reviewer. A screenshot alone never grants access.
- **Licensing context:** the National Bank of Ethiopia lists Chapa, ArifPay and SantimPay among licensed payment system operators. A licence does not guarantee that a given method is available for a given merchant.

### 16.5 Renewal model

- **Default in Ethiopia:** a fixed 30-day or annual term bought as a one-time payment (`MANUAL_RENEW`). Reminders go out at T−7, T−3 and T−0 days by push and SMS, and the user taps Renew to create a new order.
- **`AUTO_RENEW`** only applies with a provider-approved mandate on an account method approved for recurring charges, plus explicit consent to amount, frequency and cancellation terms. A one-off wallet charge is **never** treated as a mandate.
- **`STORE_MANAGED`:** Play/App Store subscriptions, which renew on the store side.
- **Renewal failure:** a configurable grace period (default 3 days, `GRACE_PERIOD`), then `EXPIRED`. Payment history is never deleted.
- **Cancelling auto-renew** calls the provider's mandate cancellation where supported, records the outcome, and stops scheduling debits immediately.

### 16.6 Orders, amounts, promotions and upgrades

- **Order snapshot:** `list_amount_minor` (FK-bound to the exact price row), `discount_minor`, `tax_minor` and `amount_minor`, with `amount = list − discount + tax` (tax is added only for `EXCLUSIVE` prices).
- **Promotions:** `promotion_codes` support percentage or fixed discounts, redemption limits, validity and product scope. They are validated server-side, and redemptions are counted when the order is paid.
- **Upgrades** (`SUBSCRIPTION_UPGRADE`):
  1. Compute the unused value of the current term pro rata by remaining seconds.
  2. Apply it as `discount_minor` on the higher plan's order.
  3. On payment, end the old subscription (`UPGRADED` event) and start the new one immediately.
  4. Downgrades take effect at the end of the current term.
- **Attempts:** an order can have several attempts (for example a Telebirr attempt fails and the user retries with CBE Birr). An attempt binds the order, provider, merchant account and account method. The order is paid at most once, and any extra captures are refunded automatically.

### 16.7 App stores

- **Google Play.**
  - The client completes Play Billing and sends `purchaseToken` to `POST /billing/store-purchases`.
  - The server verifies it with the Play Developer API, **acknowledges within 3 days** (unacknowledged purchases are refunded by Google), and stores a `store_purchases` row with the token hash and ciphertext.
  - It then upserts the subscription. Real-time developer notifications (Pub/Sub push) arrive at `/billing/store-notifications/GOOGLE_PLAY`.
- **Apple.**
  - StoreKit 2 sends the signed transaction to the server, which verifies it with the App Store Server API.
  - App Store Server Notifications V2 arrive at `/billing/store-notifications/APPLE_APP_STORE`.
  - `appAccountToken` = the user's UUID, so transactions can be linked.
- Store renewals, refunds and revocations update `store_purchases` → `user_subscriptions` and append `store_purchase_events`. They do not go through `payment_orders`.

### 16.8 App-store and distribution policy gates

Store purchase rules are independent of what a gateway can do, and they are reviewed separately.

- **Google Play:**
  - Digital goods normally require Play Billing in Play-distributed apps unless an exception or an enrolled alternative-billing programme applies. Those programmes are market-specific.
  - Google's tables list Ethiopia as supported for developer registration but not for merchant registration for profiles located there, and say the Play Billing requirement does not apply where Play Billing is unavailable to buyers.
  - Verify actual buyer availability in Play Console before choosing the Ethiopian Play-build purchase UI.
  - The `direct` flavour (APK distributed outside Play) MAY use local gateways, subject to local law.
- **Apple:**
  - In-App Purchase is generally required for digital features. External-purchase links and alternative payments are limited to specific storefronts and entitlements.
  - The multiplatform rule lets users access purchases made elsewhere, but the same items must also be offered as IAP.
  - No Chapa/Telebirr link, button or purchase call-to-action appears in the iOS app unless the storefront rule permits it and App Review has confirmed it. Moving checkout into the companion app does not exempt it.
- **Consumption-only builds:** where policy allows, a build MAY let users sign in and use entitlements bought elsewhere, with **no** purchase UI, links or pricing prompts. This is a release configuration, not a loophole.
- **Backend enforcement:**
  - The market is determined from account, storefront and user-selected country signals, not from IP address. The evidence is stored in `users.market_source`.
  - `billing_market_channel_policies` is evaluated before options are returned. Missing, expired, `REVIEW_REQUIRED` or `BLOCKED` means no purchase UI.

### 16.9 Webhook processing and fulfilment

1. Receive at `/billing/webhooks/{providerCode}/{accountCode}`. Verify the signature with **that account's** webhook secret. If the signature is invalid, store the event as `INVALID` and return `401`.
2. Insert into `payment_webhook_events` keyed on `(provider_id, external_event_id)`. If the provider sends no event ID, use `sha256(payload)`. Return the provider's expected acknowledgement **immediately**.
3. An outbox handler fetches the transaction from the provider's API. It never trusts the webhook body alone.
4. Match the merchant reference, order reference, amount, currency, method, environment and account, and check for duplicates or reversals.
5. In **one** database transaction:
   - update the attempt and order
   - append `payment_transactions`
   - fulfil (subscription allocation or credit lot)
   - issue `payment_receipts`
   - append `subscription_events` and `audit_events`
   - enqueue notifications

   Row locks and unique constraints make redelivery safe.
6. Refunds, reversals and chargebacks append ledger rows and apply the configured entitlement or credit-reversal policy.

### 16.10 Reconciliation, tax and receipts

- **Daily reconciliation** per merchant account: provider settlement or transaction reports are matched against `payment_transactions`. Mismatches become `reconciliation_items` for finance review. Provider fees are recorded as `FEE` transactions.
- **Tax:** `billing_prices.tax_behavior` is `INCLUSIVE`, `EXCLUSIVE` or `NOT_APPLICABLE`. Ethiopian VAT and invoice/receipt requirements, and diaspora VAT/GST on digital services, MUST be confirmed by an accountant before prices go live.
- **Receipts:** a sequential `receipt_number` per market, with the localised receipt available in-app (`GET /me/receipts`).
- Card PANs, CVVs, wallet PINs and raw credentials are never stored. Only provider references and redacted metadata are kept, for the legally required retention period.

## 17. Non-functional requirements

### 17.1 Performance budgets

These are measured on named reference devices: low (2 GB RAM, Android 10), mid (4 GB, Android 13) and high (current flagship); and iPhone SE (2nd gen) and a current iPhone.

| Metric | Budget |
|---|---|
| Key-down to glyph or composing update, p95 | ≤ 30 ms mid-range; ≤ 50 ms low-end |
| Candidate strip update, p95 | ≤ 30 ms mid-range |
| Keyboard cold show (first input view), p95 | ≤ 300 ms mid-range; ≤ 600 ms low-end |
| Resident memory while typing, one language | ≤ 60 MB Android IME process; ≤ 40 MB iOS extension peak |
| Disk per language, typing packs | ≤ 15 MB (Ethiopic); ≤ 25 MB (English) |
| AI translate ≤ 500 chars, server time p95 | ≤ 2.5 s; client hard timeout 8 s |
| Realtime end-of-speech → translated audio | ≤ 2.5 s p50, ≤ 4 s p95 |
| API availability (monthly) | 99.9% for `/api/v1`; typing unaffected by outages |

There MUST NOT be synchronous disk or network access on the key path. Pack loading happens at input start, and the first-ever load is backgrounded with a fallback layout.

### 17.2 Resilience

- Every provider call has a timeout and a circuit breaker. Retries are limited to idempotent or transient failures. Fallback is allowed only to a route of equal or better `quality_tier`; otherwise the request returns a clear `PROVIDER_UNAVAILABLE`.
- Payment and store calls are idempotent with provider idempotency keys where supported.
- PostgreSQL has PITR backups (RPO ≤ 5 min, RTO ≤ 1 h) and quarterly restore drills.
- The CDN serves packs, so pack availability does not depend on API availability.

### 17.3 Scaling path

1. One `api` deployment (2+ instances) + managed PostgreSQL + object storage/CDN.
2. Split the `worker` and `realtime` profiles onto their own instances when CPU or connection load demands it.
3. Add Redis/Valkey for rate limiting and the AI result cache once PostgreSQL contention is measured.
4. Add read replicas for admin and reporting queries.
5. Partition high-volume tables (D18).
6. Extract `realtime` or `partner-api` as separate services only if isolation or team ownership requires it.

## 18. Security

- **Transport:** TLS everywhere, HSTS, no plaintext fallback. Certificate pinning is not used for the API (operational risk), but **pack signature keys are pinned** (§7.6).
- **Authentication:** §9. Admins use OIDC + MFA with short sessions and IP allow-lists for `/admin/v1`.
- **Authorisation:** user-scoped resources are checked by owner. Organisation resources are checked by role. Admin uses RBAC permissions plus four-eyes approval for high-risk actions (D25).
- **Secrets:** provider and merchant credentials, webhook secrets and signing keys live in the secret manager or KMS. The database stores references only. Keys are rotated on a schedule and immediately on suspected compromise.
- **Data encryption:** encrypted storage at rest (cloud default). User-content columns use **application-level envelope encryption** with per-user data keys (`user_data_keys`, D17). Store purchase tokens and mandate references are encrypted with service keys.
- **Input handling:** strict DTO validation, size limits before parsing, JSON depth limits and an allow-listed redirect URL domain for checkout returns.
- **Abuse:** Bucket4j limits per IP, device, user, API key and task. OTP limits. Attestation for guests. Anomaly alerts on AI spend per subject.
- **Supply chain:** dependency and secret scanning, SBOM per build, signed container images, reproducible builds of `keyboard-core`, and Android/iOS app-signing keys in hardware-backed stores.
- **Logging hygiene:** never log tokens, OTPs, user text, transcripts, audio URLs, prompts or provider raw errors. A redaction filter is applied at the logger and verified by tests.
- **Mobile:** obfuscation (R8), root/jailbreak signals used only for risk scoring (never to block typing), and WebView checkout restricted to provider domains.
- **Testing:** threat model per release train, annual third-party penetration test, and a public vulnerability disclosure policy.

## 19. Privacy and compliance

### 19.1 Privacy commitments (user-facing and normative)

1. Typing stays on the device. Keystrokes are never transmitted, logged or used for analytics.
2. Text and audio are sent only when the user taps an AI action, and only that content is sent.
3. Content is not stored by default. History is opt-in, encrypted and deletable.
4. Audio is never stored by default.
5. User content is never used to train our models or third-party models. Provider contracts MUST prohibit training.
6. Offline typing works without an account. AI and network features can be switched off entirely.
7. No advertising SDKs. Personal data is never sold or shared for advertising.

### 19.2 Data classification and retention

| Data class | Examples | Storage | Default retention |
|---|---|---|---|
| Keystrokes, drafts | Everything typed | **Device only** (local learning overlay) | Until the user clears it |
| AI request content | Text sent to translate/rewrite | Transient in memory; optional ≤ 10 min encrypted cache | Not persisted |
| AI request metadata | Feature, languages, counts, cost, latency | `ai_requests` | 13 months, then aggregated |
| History (opt-in) | Source and result text | `user_history_items` (encrypted) | User-selected 1–365 days (default 30) |
| Audio | Dictation, conversation | Memory / temporary object | Deleted after processing; ≤ 24 h lifecycle backstop |
| Account and preferences | Profile, settings, consents | PostgreSQL | Life of account + 30 days |
| Financial records | Orders, transactions, receipts | PostgreSQL | As required by tax law (assume 10 years until confirmed) |
| Audit events | Admin and security actions | PostgreSQL | 2 years (security), 7 years (financial admin actions) |
| Webhook payloads | Redacted provider events | PostgreSQL | 18 months |
| Operational logs | Redacted | Log store | 30 days |

### 19.3 Consent

Consent is recorded as append-only rows (code, version, granted/revoked, timestamp, platform). The consent codes are:

`TERMS_ACCEPTANCE`, `PRIVACY_NOTICE`, `AI_PROCESSING`, `SAVE_AI_HISTORY`, `SAVE_AUDIO_HISTORY`, `DICTIONARY_SYNC`, `PERSONAL_STYLE`, `FEEDBACK_TEXT`, `COMMUNITY_CONTRIBUTION`, `PAYMENT_MANDATE`, `ANALYTICS`, `MARKETING`.

Optional consents can be revoked at any time, and revoking one stops the related processing and triggers deletion where applicable.

### 19.4 Data subject rights

- **Export:** `POST /me/data-exports` produces a JSON + CSV archive of account, preferences, consents, decrypted history, subscriptions and receipts. It is delivered through a signed URL that expires after 7 days.
- **Deletion:** `POST /me/deletion-request` starts a 14-day cancellable window, then:
  1. Destroy the user data key, which crypto-shreds all encrypted content including copies in backups.
  2. Delete personal rows.
  3. Pseudonymise financial records that must be retained.
  4. Revoke sessions and mandates.
  5. Request store-side handling where applicable.
- **Correction:** through profile endpoints.

### 19.5 Regulatory frame (confirm with counsel)

- **Ethiopia:** Personal Data Protection Proclamation No. 1321/2024 (lawful basis, data-subject rights, cross-border transfer conditions, registration duties). Also NBE payment rules and Ethiopian consumer protection rules.
- **Diaspora:** GDPR / UK GDPR (DPIA, records of processing, SCCs/IDTA for transfers, EU/UK representative if required), US state privacy laws, and app-store privacy labels / Data safety forms that match the actual behaviour.
- **Minors:** onboarding age gate. The minimum age is 13, or the local digital age of consent if higher. Account features are not offered below it, but offline typing stays available.
- **AI transparency:** label AI output in the review UI, publish the provider list and processing regions, and follow emerging AI-transparency obligations in target markets.

## 20. Data architecture

The full schema is in `Ethiopian_AI_Keyboard_Database_Design_v2_0.sql` (Flyway `V1__baseline.sql`).

### 20.1 Principles

- UUID primary keys. UTC `timestamptz`. Explicit CHECK lists. Every foreign key has a deliberate `ON DELETE`. Composite foreign keys enforce cross-table consistency (price ↔ product ↔ market ↔ channel ↔ currency ↔ amount; attempt ↔ order ↔ provider ↔ account ↔ method; mandate/subscription ↔ account method).
- **No content tables.** There is no keystroke, draft, transcript or audio table. `ai_requests` holds metadata only.
- **Encrypted user content.** History, synced dictionary words, destination-profile labels, free-text style fields and feedback comments are stored as ciphertext under per-user data keys (crypto-shredding).
- **Catalogues are data.** Languages, features, plans, entitlements, providers, methods, accounts, prices, policies and routing are rows, not enums in code, so markets and languages can be added without schema changes.
- **Ledgers are append-only.** `payment_transactions`, `ai_credit_transactions`, `usage_events`, `audit_events`, `subscription_events`, `payment_mandate_events`, `store_purchase_events` and `user_consents` reject UPDATE/DELETE through a trigger. A revoked consent is a new row, and the latest row wins. The only exceptions are FK cascades from an account deletion and retention purges run with `SET LOCAL app.retention_purge = 'on'`.
- **High-volume tables are never foreign-key targets** (`ai_requests`, `usage_events`, `audit_events`, `outbox_events`). They use UUIDv7 keys and BRIN time indexes, and they are partitioned when they reach about 50 million rows (D18).
- **Large binaries live outside PostgreSQL.** Dictionaries, models and audio are stored in object storage, and the database keeps metadata and checksums.

### 20.2 Domain map

| Domain | Main tables |
|---|---|
| Identity | users, user_password_credentials, user_auth_identities, otp_challenges, user_devices, user_sessions, password_reset_tokens, user_data_keys |
| Organisations | organizations, organization_members, organization_invitations, api_clients, api_keys |
| Languages | supported_languages, resource_signing_keys, language_resources, transliteration_profiles, device_language_resource_installations, language_datasets, language_terms, term_suggestions, user_dictionary_entries |
| Features | feature_catalog |
| Preferences | user_preferences, user_typing_languages, user_destination_profiles, user_app_destination_rules, user_style_profiles |
| Privacy | user_consents, user_history_items, data_export_requests, user_deletion_requests |
| AI | ai_providers, ai_models, ai_language_capabilities, ai_routing_policies, ai_prompt_templates, ai_evaluation_runs, ai_requests, ai_feedback |
| Voice | voice_jobs, realtime_sessions |
| Glossaries | glossaries, glossary_entries |
| Catalogue and pricing | subscription_plans, plan_entitlements, billing_currencies, billing_products, billing_prices, promotion_codes, external_product_mappings |
| Payments | payment_providers, provider_payment_methods, payment_provider_accounts, payment_provider_account_methods, billing_market_channel_policies, payment_orders, payment_attempts, payment_mandates, payment_mandate_events, payment_transactions, payment_refunds, payment_webhook_events, manual_payment_reviews, payment_receipts, reconciliation_runs, reconciliation_items |
| Stores | store_purchases, store_purchase_events |
| Subscriptions | user_subscriptions, subscription_payment_allocations, subscription_events, organization_subscriptions, organization_seat_assignments |
| Usage and credits | ai_credit_accounts, ai_credit_lots, ai_credit_transactions, usage_counters, usage_events |
| Configuration | feature_flags, user_feature_overrides, app_configurations |
| Admin and operations | admin_roles, user_admin_roles, audit_events, outbox_events |

### 20.3 Migration policy

- `V1__baseline.sql` is applied once to each new environment. After it has been applied to any shared environment it is **never edited**. All later changes are forward-only `V{n}__*.sql` migrations, reviewed and tested against a production-sized snapshot.
- Expand → migrate → contract for breaking changes. Long-running index builds use `CREATE INDEX CONCURRENTLY` in separate non-transactional migrations.
- Seed data uses `ON CONFLICT DO NOTHING`, so admin edits are never overwritten.

## 21. Testing strategy

### 21.1 `keyboard-core`

- Golden vectors per transliteration profile version (keystrokes → composing → committed → alternatives), including homophone families, labialised forms, punctuation, protected tokens, backspace and candidate replacement.
- Property-based tests: segmentation never splits grapheme clusters; NFC idempotence; reversibility round-trips where a profile declares `reversible`.
- Benchmarks in CI for key-path latency and memory on representative dictionaries. A regression of more than 10% fails the build.
- Fuzzing of rule and pack parsers; malformed or unsigned packs MUST be rejected.

### 21.2 Backend

- Unit tests for entitlement resolution, metering windows (time-zone edges), reservation and settlement, credit FIFO, proration, policy evaluation, guardrails and prompt selection.
- Integration tests with Testcontainers PostgreSQL. Migrations are applied from scratch on every run.
- Database constraint tests: each schema guard (price overlap, attempt eligibility, mandate consent, append-only ledgers, entitlement feature gating) has a test that proves it rejects bad data.
- Provider adapters are tested with recorded fixtures and deterministic mocks. Webhook signature, idempotency, out-of-order and duplicate-delivery cases are covered.
- Contract tests: OpenAPI validation of every controller response; client generation in CI.
- Load tests: AI endpoints at 3× forecast peak with provider latency injected; realtime sessions at target concurrency.

### 21.3 Native apps

- **Android:** IME lifecycle, rotation, split screen and foldables, OEM keyboards and gesture navigation; input types (password, email, URI, number, multiline); apps that reject composing text; low-memory kills.
- **iOS:** extension lifecycle, Full Access on and off, bundled-pack-only mode, secure fields, host apps that refuse custom keyboards, memory pressure. Testing is on physical devices.
- **Both:** offline operation, AI provider outages, return from companion app, pending-insert flow, accessibility (TalkBack/VoiceOver, large text, contrast).

### 21.4 Language acceptance

Native-speaker panels (at least 5 per language, covering regional variety, age and gender) test:

- common and informal writing
- names and places
- code-switching
- formal and honorific registers
- autocorrect harm (corrections that change meaning)
- translation meaning preservation
- addressee and gender handling
- ASR in quiet and noisy conditions

### 21.5 Release gates

| Gate | Requirement |
|---|---|
| R1 Language | §7.7 criteria met for each language and mode marked GA |
| R2 AI quality | Each advertised feature × pair has an evaluation run meeting its tier threshold; `UNVERIFIED`/`EXPERIMENTAL` never exposed |
| R3 Privacy | Data-flow review: no content in logs, analytics or crash reports (automated log-scrub tests pass); store privacy labels match behaviour |
| R4 Security | Threat model updated; no open high/critical findings; dependencies scanned |
| R5 Cost | Provider cost per active user modelled; quotas and kill switches configured; spend alerts live |
| R6 Billing | Sandbox and live small-value tests per method; reconciliation run passes; refund path tested; market/channel policies reviewed and approved |
| R7 Licensing | Every shipped model, dataset and dictionary has a licence that permits commercial use (`language_datasets`, `ai_models.license_code`) |
| R8 Platform | Android: Play policy review (IME disclosures, Data safety). iOS: extension tested on devices against current App Review guidelines |

## 22. Observability and product analytics

- **Operational metrics:**
  - request rate, latency and errors by endpoint, feature, provider and language pair
  - provider cost per feature and per subject
  - quota denials
  - realtime session latency and drop rate
  - payment funnel by method (created → attempted → verified)
  - webhook lag
  - pack download and install success
  - crash-free sessions
  - keyboard cold-start and key-path latency (sampled on the device)
- **Tracing:** OpenTelemetry spans across the gateway pipeline and provider calls, with correlation IDs returned to clients.
- **Crash reporting:** breadcrumbs and custom keys MUST NOT contain typed text, clipboard content, transcripts or AI output. The IME process disables view-hierarchy and screenshot capture.
- **Product analytics:** off by default unless the `ANALYTICS` consent is granted (or the jurisdiction permits a legitimate-interest default, as confirmed by counsel). The event schema is allow-listed and reviewed. Properties never include content.
- **Alerting:** SLO burn-rate alerts; anomaly alerts on AI spend, OTP volume, payment failures and webhook signature failures.

## 23. Deployment and environments

| Environment | Purpose | Providers |
|---|---|---|
| `local` | Docker Compose: PostgreSQL 18, MinIO, mock AI/payment/SMS providers | Mocks only |
| `dev` | Shared integration | Provider sandboxes, test merchant accounts (`SANDBOX`) |
| `staging` | Production-like; release candidates; load tests | Sandboxes; store sandbox/TestFlight/internal testing tracks |
| `production` | Live | Production merchant accounts; isolated secrets and KMS keys |

- **Hosting:** choose a region close to Ethiopia with good Ethiopian latency and a compliant transfer basis. Measure from Addis Ababa on Ethio telecom and Safaricom networks before deciding. Packs are served from a CDN with African points of presence.
- **Deployment:** OCI images through CI/CD with blue-green or rolling deploys. Flyway runs as a gated pre-deploy job. Feature flags handle gradual rollout.
- **Mobile releases:** staged rollouts (Play percentages, App Store phased release). `minSupportedAppVersion` enforced through `/config`. Packs follow their own release channels independent of app releases.
- The admin console is deployed separately behind SSO with an IP allow-list.

## 24. Repository structure

```text
ethiopian-ai-keyboard/
├── core/                         # Rust keyboard-core (UniFFI)
│   ├── src/                      # composer, translit, segmenter, lexicon, ranker, langid, calendar
│   ├── bindings/                 # generated Kotlin/Swift
│   └── tests/golden/             # golden vectors per profile version
├── android/                      # Gradle (Kotlin DSL), rootProject "Qal Smart Keyboard"
│   ├── app/                      # application module: Compose companion UI; assembles ime, data, billing-*; play/direct flavours
│   ├── ime/                      # library: InputMethodService, keyboard surface
│   ├── data/                     # library: API client, storage, sync
│   └── billing-play/ billing-direct/   # libraries
├── ios/
│   ├── KeyboardExtension/
│   ├── CompanionApp/
│   └── Shared/                   # App Group, keychain, API client
├── backend/                      # Maven (pom.xml, mvnw); base package com.qalsmartkeyboard
│   └── src/main/java/com/qalsmartkeyboard/{identity,devices,languages,preferences,personalization,
│       aigateway,textservices,speech,realtime,entitlements,usage,billing,stores,
│       organizations,glossaries,partnerapi,privacy,notifications,configuration,
│       administration,platform}/
│   └── src/main/resources/db/migration/V1__baseline.sql
├── admin-web/                    # Next.js admin console
├── language-data/                # source rules, wordlists (licensed), build scripts → signed packs
├── contracts/
│   └── openapi.yaml              # synced copy of docs/Ethiopian_AI_Keyboard_OpenAPI_v2_0.yaml (docs/ is canonical; CI fails on drift)
├── infra/
│   ├── compose/                  # docker-compose.yml: PostgreSQL 18, MinIO, WireMock providers
│   └── deploy/                   # deployment manifests, environment config (no secrets)
├── .github/workflows/            # CI pipelines (or the CI system's equivalent)
├── docs/
│   ├── Ethiopian_AI_Keyboard_System_Design_v2_0.md
│   ├── Ethiopian_AI_Keyboard_Database_Design_v2_0.sql
│   ├── Ethiopian_AI_Keyboard_OpenAPI_v2_0.yaml
│   ├── Ethiopian_AI_Keyboard_Architecture_Review_v2_0.md
│   ├── adr/                      # future decision records (D33+)
│   └── archive/v1_4/
└── README.md
```

## 25. Roadmap

Each milestone has exit criteria. Durations are indicative, assume a team of about 6–8, and must be re-planned after M0.

| Milestone | Scope | Exit criteria |
|---|---|---|
| **M0 — Foundations** (4–6 wks) | Native-speaker panels; draft AM/TI transliteration specs and golden corpora; provider evaluation (MT/LLM/ASR for am/ti/om); OpenAPI and schema baseline; CI; `keyboard-core` skeleton; privacy notice draft; Play/App Store and payment policy checks started | Specs reviewed; provider shortlist with scores; repository and CI green |
| **M1 — Offline keyboard alpha (Android)** | `keyboard-core` (phonetic AM/TI, direct Fidel, Qubee, English), dictionaries, ABC, switching, settings; companion onboarding; bundled packs | Golden corpus 100% pass; §17.1 budgets met on reference devices; 30+ native testers |
| **M2 — Backend + P0 AI** | Identity (phone OTP, Google, guest + attestation), devices, preferences sync, pack pipeline + admin publishing + signing, AI gateway, translate/grammar/rewrite, metering (GUEST/FREE), consents, export, deletion | Closed beta; R2–R5 passed for P0 features |
| **M3 — P0 public launch (Android, Ethiopia + diaspora)** | Hardening, observability, Play listing, Data safety; free plans only | R1–R5, R8 passed; crash-free ≥ 99.5% |
| **M4 — Monetisation** | Chapa hosted checkout (ETB), manual renewal + reminders, receipts, reconciliation, credits, Play Billing where eligible, `direct` flavour, ArifPay behind a flag | R6 passed; live real-money pilot; zero unverified grants |
| **M5 — P1** | iOS keyboard + companion (StoreKit 2); Android streaming dictation; iOS companion voice; tone; smart replies; destination profiles + app rules; opt-in history; pack channels and deltas | iOS App Review approved; voice evaluation thresholds met |
| **M6 — P2 differentiation** | `SCRIPT_CONVERSION`; cultural intelligence to PRODUCTION tier; explain, summarise, drafting; TTS + pronunciation; personal style; names/places programme (datasets + community suggestions); voice-to-voice and live conversation (companion) | Native-speaker honorific/gender evaluation passed; realtime latency budget met |
| **M7 — P3 expansion and B2B** | New languages (candidates: Somali, Afar, Sidama, Wolaytta, Swahili), on-device models (language ID/spell first, then MT/ASR), organisations, seats, glossaries, partner API GA | Per-language gates R1/R2/R7; partner SLA and isolation tests passed |

## 26. Decisions and open questions

### 26.1 Decisions

The decision record is in the Architecture Review (D1–D32). These are the decisions with the most structural impact:

- Offline-first typing in a shared Rust core.
- No keystrokes leave the device.
- Explicit, reviewed AI actions only.
- Spring Boot modular monolith on PostgreSQL with outbox.
- Phone OTP + guest-with-attestation identity.
- Feature catalogue + reservation-based metering + credit lots.
- Signed entitlement token for offline gates only.
- Destination profiles instead of per-contact preferences.
- Addressee-aware cultural intelligence.
- Realtime voice as a module with a WebSocket protocol.
- Provider-neutral billing with merchant-account scoping, fail-closed market policy and manual renewal by default in Ethiopia.
- Store purchases kept separate from gateway orders.
- Per-user envelope encryption with crypto-shredding.
- Four-eyes approval for high-risk admin actions.

### 26.2 Open questions (owner → due by)

| Question | Owner | Due |
|---|---|---|
| Final launch-language set and dialect coverage (incl. Tigrinya Ethiopia vs Eritrea conventions) | Product + language leads | M0 |
| Approved AM/TI phonetic schemes and onboarding default (phonetic vs Fidel) | Language panels | M0 |
| Initial MT/LLM/ASR/TTS providers per pair, with ZDR contracts | AI lead | M0 |
| Hosting region and transfer basis under the Ethiopian PDP Proclamation | Legal + infra | M0 |
| Final quotas and ETB/GBP/USD/EUR price points after cost modelling | Product + finance | M3 |
| Play Billing buyer availability for Ethiopian accounts; need for `direct` flavour | Product + legal | M3 |
| Apple storefront purchase path for Ethiopian users | Product + legal | M5 |
| Chapa merchant onboarding, methods, limits, settlement, refunds | Finance | M4 |
| ArifPay recurring and Telebirr mandate approval for target methods | Finance | M4+ |
| VAT/receipt obligations in Ethiopia and diaspora markets | Finance | M4 |
| SMS/OTP aggregator for +251 and cost per message | Engineering | M2 |
| Rust capacity (or D2 fallback) | Engineering manager | M0 |

---

## Appendix A — Glossary

| Term | Meaning |
|---|---|
| Fidel (ፊደል) | The Ge'ez/Ethiopic syllabary used for Amharic, Tigrinya and others |
| Qubee | The Latin-based alphabet used for Afaan Oromoo |
| Transliteration | Converting the *writing system* of input (Latin phonetic → Ethiopic) without changing meaning; local and offline |
| Script conversion | AI-assisted whole-sentence romanised → Ge'ez conversion using context; a server feature |
| Composing region | Provisional text the IME can still change before commit |
| Destination profile | A user-named context (e.g. "Mum") with destination language and addressee settings |
| Entitlement token | Signed, short-lived statement of boolean entitlements for offline gates |
| ZDR | Zero data retention (provider contract setting) |
| Merchant account | A specific provider account scoped by country, currency and environment |
| Mandate | Provider-approved, user-consented permission for recurring debits |

## Appendix B — External references (checked 16 November 2026)

Re-check before release, because APIs, payment methods and store policies change.

- Chapa API v2 — https://docs.chapa.global/docs/v2 ; payment methods — https://docs.chapa.global/docs/v2/integrations/payment-methods ; webhooks — https://developer.chapa.co/integrations/webhooks
- ArifPay — https://developer.arifpay.net/docs/api/overview
- Telebirr subscription payments — https://developer.ethiotelecom.et/docs/Schedule%20Payment/Introduction
- National Bank of Ethiopia payment system operators — https://nbe.gov.et/payment-instrument-issuers-system-operators/
- Google Play payments policy — https://support.google.com/googleplay/android-developer/answer/9858738 ; FAQ — https://support.google.com/googleplay/android-developer/answer/10281818 ; alternative billing locations — https://support.google.com/googleplay/answer/11174377 ; developer/merchant registration locations — https://support.google.com/googleplay/android-developer/answer/9306917
- Apple App Review Guidelines §3.1 — https://developer.apple.com/app-store/review/guidelines/ ; accepted payment methods — https://support.apple.com/en-gb/111741
- Apple custom keyboard guidance (Full Access, restrictions) — https://developer.apple.com/documentation/uikit/keyboards_and_input/creating_a_custom_keyboard
- Android IME guide — https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- Play Integrity API — https://developer.android.com/google/play/integrity ; App Attest — https://developer.apple.com/documentation/devicecheck
- Unicode Ethiopic charts — https://www.unicode.org/charts/ (Ethiopic, Supplement, Extended, Extended-A, Extended-B)
- RFC 9457 Problem Details — https://www.rfc-editor.org/rfc/rfc9457

## Appendix C — Core product rule

**The keyboard is an input method, not a message sender.** It may prepare, transform and insert text into the active field. The user remains responsible for reviewing and sending content in the host application.
