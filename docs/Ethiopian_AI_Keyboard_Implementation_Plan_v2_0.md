# Qal Smart Keyboard — Implementation Plan v2.0

**Audience:** AI coding agents (and humans supervising them). This is an execution plan, not a design. The design is already decided.

## 0. How to use this document

1. Work **one step at a time, in order**. Each step has an ID (e.g. `B2.3`), inputs, outputs and a **Done when** check. Do not start a step until its `Depends on` steps are done.
2. **Sources of truth (read before coding, never contradict):**

| Concern | File |
|---|---|
| Architecture, rules, behaviour | `docs/Ethiopian_AI_Keyboard_System_Design_v2_0.md` ("SD") |
| Database schema (single baseline migration) | `docs/Ethiopian_AI_Keyboard_Database_Design_v2_0.sql` ("SQL") |
| HTTP/WebSocket contract | `docs/Ethiopian_AI_Keyboard_OpenAPI_v2_0.yaml` ("API") |
| Why decisions were made, F1–F24, D1–D32 | `docs/Ethiopian_AI_Keyboard_Architecture_Review_v2_0.md` ("Review") |

3. If SD, SQL and API disagree, **stop and report** the conflict; do not silently pick one. If the code needs a contract change, change the contract file first, then the code, in the same change.
4. Ignore `docs/archive/v1_4/`. It is obsolete.
5. Anything marked **DRAFT**, **PROVISIONAL** or **UNVERIFIED** in the docs (transliteration profiles, quotas, plan BUSINESS, products) is a placeholder. Build it config-driven so values can change without code changes.
6. Each step ends with a small, reviewable change plus tests. Prefer many small PRs over one large one.

### 0.1 Non-negotiable rules (violating any is a bug)

| # | Rule | Source |
|---|---|---|
| N1 | Typing works fully **offline**. No network call on the key path, ever. | SD R-1 |
| N2 | **No user content** (typed text, clipboard, transcripts, AI output) in logs, analytics, crash reports or provider-training data. Provider calls use zero-data-retention / no-training policies. | SD §19, R3 |
| N3 | Keyboard never knows the recipient. There are **destination profiles**, not per-contact prefs. Android per-app rules store an HMAC of the package name, never the raw name. | Review F1 |
| N4 | iOS extension must work **without Full Access**: baseline packs are bundled in the extension; network/App Group/shared keychain only when Full Access is on. | Review F2 |
| N5 | AI idempotency: a retry is **never charged twice**. Replay result only from the ≤10 min encrypted cache; otherwise recompute without charge. | Review F3 |
| N6 | Store purchases (Play/App Store) use their own tables, **not** gateway orders. | Review F4 |
| N7 | Billing: fail-closed market/channel policy; append-only ledgers; never grant entitlement from an unverified payment. | SD §16 |
| N8 | Metering is reserve → settle/release. Voice is metered in **seconds**. Credits are FIFO lots by expiry, `NUMERIC(18,6)`. | SD §15 |
| N9 | Never expose `UNVERIFIED`/`EXPERIMENTAL` language/AI features to users. | SD R2 |
| N10 | Every shipped model/dataset/dictionary needs a commercial-use licence recorded. NLLB-200, MMS and SeamlessM4T are **non-commercial** → not shippable. | SD R7 |
| N11 | Packs and entitlement tokens are **signed** (Ed25519). Reject unsigned/malformed. | SD §7, §15 |
| N12 | Errors are RFC 9457 Problem Details using the codes in API. Mutable resources use `revision` optimistic concurrency. | SD §14 |
| N13 | Per-user data uses envelope encryption so deletion can crypto-shred. | SD §19 |

### 0.2 Target stack (do not substitute without an ADR in `docs/adr/`)

- **Core:** Rust `keyboard-core`, bound with UniFFI to Kotlin and Swift.
- **Android:** Kotlin, `InputMethodService` IME + Jetpack Compose companion. One APK, flavours `play` and `direct`.
- **iOS:** Swift, keyboard extension + SwiftUI companion, App Group, StoreKit 2.
- **Backend:** Maven project (already scaffolded: `backend/pom.xml`, package `com.qalsmartkeyboard`), Java 25 target, Spring Boot 4.x modular monolith with Spring Modulith, PostgreSQL 18 (17 minimum), Flyway (`V1__baseline.sql`), transactional outbox + db-scheduler.
- **Admin:** Next.js in `admin-web/`. **Local infra:** Docker Compose (PostgreSQL, MinIO, mock providers).
- **Repo layout:** SD §24 (`core/ android/ ios/ backend/ admin-web/ language-data/ contracts/ infra/ docs/`).

### 0.3 Definition of done (applies to every step)

- Code compiles; unit tests for new logic pass; no new lint errors.
- Behaviour matches API and SQL exactly (field names, enums, error codes).
- No content in logs (N2). New endpoints appear in API with an entitlement/feature code from `feature_catalog`.
- If a DB guard or constraint is relied on, a test proves it rejects bad data (SD §21.2).

## 0.4 Status tracker

**Status values:** ⬜ TODO · 🟦 IN PROGRESS · 🟥 BLOCKED (add reason in the Notes log) · 🟩 DONE (only after "Done when" is verified) · ⏭️ SKIPPED (add reason).

**Update protocol (agents MUST follow):**
1. Before starting a step, set its Status to 🟦 in the phase table below.
2. When finished and verified, set 🟩 and append one line to the **Notes log** (§0.5): date, step ID, PR/commit, what was verified.
3. If blocked (human gate, contract conflict, missing decision), set 🟥 and log the reason; pick another step whose dependencies are 🟩.
4. Update the progress table below whenever a status changes. Never mark 🟩 with failing tests.

### Progress summary

| Phase | Name | Steps | 🟩 Done | 🟦 In progress | 🟥 Blocked | ⬜ Todo | % |
|---|---|---|---|---|---|---|---|
| Phase 0 | Foundations (M0) | 6 | 6 | 0 | 0 | 0 | 100% |
| Phase 1 | keyboard-core + Android offline alpha (M1) | 22 | 3 | 0 | 0 | 19 | 14% |
| Phase 2 | Backend + P0 AI (M2) | 39 | 0 | 0 | 0 | 39 | 0% |
| Phase 3 | Hardening + launch (M3) | 6 | 0 | 0 | 0 | 6 | 0% |
| Phase 4 | Monetisation (M4) | 10 | 0 | 0 | 0 | 10 | 0% |
| Phase 5 | iOS + voice + P1 (M5) | 13 | 0 | 0 | 0 | 13 | 0% |
| Phase 6 | Differentiation (M6) | 8 | 0 | 0 | 0 | 8 | 0% |
| Phase 7 | Expansion + B2B (M7) | 5 | 0 | 0 | 0 | 5 | 0% |
| **Total** | | **109** | 9 | 0 | 0 | 100 | 8% |

### Human gates

| Gate | Needed before | Status |
|---|---|---|
| G0 Native-speaker panel, AM/TI draft review, provider shortlist | Phase 1 finalisation, B5.1 | ⬜ TODO |
| G1 30+ native testers sign off the Android alpha | Phase 2 public use | ⬜ TODO |
| G2 Closed beta; R2–R5 passed for P0 | Phase 3 | ⬜ TODO |
| G3 Play Billing eligibility in Ethiopia confirmed | M4.7, M4.8 | ⬜ TODO |
| G4 Quota/cost model approved (replaces PROVISIONAL_V2) | Phase 4 pricing | ⬜ TODO |
| G5 iOS App Review approved | Phase 5 exit | ⬜ TODO |

## 0.5 Notes log

Append newest at the bottom. Format: `YYYY-MM-DD | step ID | status | PR/commit | note`.

| Date | Step | Status | PR/commit | Note |
|---|---|---|---|---|
| 2026-11-17 | F0.1 | 🟩 DONE | eb7a580 | Skeleton per SD §24 with READMEs; root `.gitignore`; `git init`; `contracts/openapi.yaml` + `contracts/sync.sh --check` (passes); root README prerequisites and build commands. Android: `ime`, `data`, `billing-play`, `billing-direct` library modules + `play`/`direct` flavours; `assemblePlayDebug` and `assembleDirectDebug` succeed. Backend: `V1__baseline.sql` in Flyway folder; `contextLoads` passes on PG18 (compose, port 5434, 86 tables) with `-Djava.version=21`. Open: JDK 25 not installed locally. |
| 2026-11-17 | F0.2 | 🟩 DONE | 1c757ca, fcd569a, 62c7d78 | GitHub Actions `ci.yml`: contract + migration drift check, Redocly lint (annotations), schema on PG17/PG18 (86 tables), backend `verify` on Java 25 + PG18, Android play/direct assemble + unit tests, Rust job (skips until `core/Cargo.toml`). Run 44088993925 all green. Fixed 11 unquoted OpenAPI descriptions that YAML split on commas. Open: 4 Redocly warnings (2 unused realtime schemas, 1 example/oneOf mismatch, example.com server); iOS/admin-web jobs to add at I5.1/H3.6. |
| 2026-11-17 | F0.3 | 🟩 DONE | 979df1b | `backend/src/test/java/com/qalsmartkeyboard/schema/SchemaGuardsIT.java`: Testcontainers PG18 + Flyway, 10 tests covering smoke checks 01–26, each in a rolled-back transaction; SQLSTATE 42xxx (test-authoring errors) cannot count as a rejection. Failsafe runs `*IT` in `mvnw verify`. Mutation check: removing `payment_transactions` from the append-only trigger list fails check 18. Image overridable with `-Dqal.test.postgres-image=postgres:17`. |
| 2026-11-17 | F0.4 | 🟩 DONE | 8263cf7 | Compose: postgres:18 (5434), MinIO (9000/9001; Chainguard image pinned by digest because `minio/minio` is no longer pullable) + init creating `qal-language-packs`, `qal-exports`, `qal-audio-temp`; WireMock 3.13.1 (8089) with 13 stubs under `/sms`, `/ai`, `/chapa`; `X-Mock-Scenario: slow|error` for fallback tests. All healthy, stubs verified with curl. Open: Chapa stub shapes are from memory and must be checked against Chapa docs before M4.2; AI request/response shapes are placeholders until providers are chosen; MinIO digest needs manual repinning. |
| 2026-11-17 | F0.5 | 🟩 DONE | 160ab8f | `docs/adr/0001-open-items.md`: 24 OPEN items (12 from SD §26.2 verbatim, 7 review risks, 5 Phase 0 findings) with owner, due milestone and blocked plan steps. Human gate G0 still open. |
| 2026-11-17 | F0.6 | 🟩 DONE | 160ab8f | `language-data/eval/`: stdlib-only Python harness (`python -m qal_eval run`), JSON configs (keys only via env var names), `mock_http` adapter + registry, chrF/chrF++/WER/CER/script-ok/latency metrics, CSV output; 22 unit tests (also in CI). Ran end to end against WireMock incl. error/slow scenarios. Open: sample references are synthetic and not native-reviewed; chrF averaging not verified against sacrebleu; dataset licences in README to verify. |
| 2026-11-17 | C1.1 | 🟩 DONE | caf044a | Rust 1.91.0 crate `qal-keyboard-core` (UniFFI 0.32 proc-macros; lib/cdylib/staticlib); module stubs per SD §7; FFI API `KeyboardEngine` (new, load_pack, set_input_mode, on_key, select_candidate, reset, version) + records/enums/`CoreError`. `set_input_mode` refuses modes that do not fit the language or are not implemented, so keys are never silently swallowed. 10 tests; Kotlin + Swift bindings generate; built in Docker (`rust:1.91`), Rust not installed locally. Release profile uses `strip = "debuginfo"` (full strip removes UniFFI metadata). Proceeded with Rust per design recommendation; OI-12 still OPEN. |
| 2026-11-17 | C1.2 | 🟩 DONE | 5784269, 5ad22df | Pack container v1 (`docs/specs/pack-format-v1.md`, ADR 0002): signature over exact manifest bytes with domain separation, strict Ed25519, pinned keys with revocation, SHA-256 + size, limits, downgrade protection, deltas verified but host-applied. API: fallible `KeyboardEngine::new`, `EngineConfig.trusted_keys/pack_limits`, richer `PackInfo`, `PackRejectReason`, `loaded_packs()`. 63 tests (exhaustive bit-flip/truncation, proptest), 20 cross-language vectors (independently reproduced with OpenSSL), cargo-fuzz 2 targets ~76 s each, 0 crashes. OpenAPI `LanguageResourceManifest` now discovery-only (no `signature`, + `containerSizeBytes`). Hosts must persist installed versions (A1.6/A2.3/I5.2). |
| 2026-11-17 | C1.3 | 🟩 DONE | see git log | Fidel syllable table generated from Unicode names (`language-data/scripts/gen_fidel_table.py`: 78 series, 481 forms; reviewer re-checked every form against its Unicode name), mirrored into `core/src/composer/fidel_table.rs` with a staleness test (in CI). Layout format `docs/specs/keyboard-layout-v1.md`; DRAFT am (34 series) and ti (35, + ቐ) layouts in `language-data/layouts/`. Core: `KEYBOARD_LAYOUT` packs validated after signature check (`INVALID_ARTIFACT`), `fidel_layout()`, `KeyEvent::FidelConsonant/FidelVowel`, LONG_PRESS + TWO_KEY (default 6th order), DirectFidel only when a layout is loaded. 93 Rust tests + 8 Python tests. Open for G0 panel: ኸ in Amharic, homophone series on main keys, default order 6 vs 1. Fuzz crate not rebuilt this step. |

---

## Phase 0 — Foundations (milestone M0)

Goal: repo, CI, contracts and decisions that unblock everything else. Many items need **humans** (native speakers, legal); the agent prepares scaffolding and records open items.

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| F0.1 | Create the repo skeleton exactly per SD §24 (folders already created, each with a README stating its purpose); sync API to `contracts/openapi.yaml` (docs/ is canonical; CI fails on drift). Add build instructions per module. | — | Tree matches §24; each module has a placeholder build that passes. | 🟩 DONE |
| F0.2 | CI pipeline: Rust (`cargo fmt/clippy/test`), backend (Gradle or Maven build + Testcontainers), Android (assemble + unit tests, Gradle at `android/`), iOS (xcodebuild on macOS runner), OpenAPI lint (`npx @redocly/cli@1 lint contracts/openapi.yaml`), SQL apply test on PG17 and PG18. | F0.1 | CI green on an empty change. | 🟩 DONE |
| F0.3 | Port `scratchpad/schema_smoke_test.sql` ideas into `backend/src/test/.../SchemaGuardsIT`: apply `V1__baseline.sql` on Testcontainers PG18 and assert the 26 guard checks (price overlap, append-only ledgers, entitlement gating, etc.). | F0.1 | Test passes; fails if a guard is removed. | 🟩 DONE |
| F0.4 | `infra/compose/docker-compose.yml`: PostgreSQL 18, MinIO, mock SMS/AI/payment providers (WireMock). | F0.1 | `docker compose up` gives a working local stack. | 🟩 DONE |
| F0.5 | Record human-owned open items in `docs/adr/0001-open-items.md` from SD §26.2: transliteration scheme sign-off, provider shortlist, Play Billing eligibility in Ethiopia, quota/cost model, hosting region. | — | File exists with owner + due milestone per item. | 🟩 DONE |
| F0.6 | Provider evaluation harness: `language-data/eval/` scripts that run a test set (am/ti/om/en) through candidate MT/LLM/ASR providers and write scores to CSV. No user data; use public/test corpora. | F0.4 | Harness runs against mocks; real providers pluggable by config. | 🟩 DONE |

**Human gate G0:** native-speaker panel recruited; AM/TI transliteration draft reviewed; provider shortlist chosen. Agents may proceed with Phase 1 in parallel using DRAFT profiles.

---

## Phase 1 — `keyboard-core` and Android offline alpha (milestone M1)

Goal: a fully offline Android keyboard for Amharic, Tigrinya, Afaan Oromoo and English. Read SD §6–7 first.

### 1A. `keyboard-core` (Rust)

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| C1.1 | Crate scaffold: modules `composer, translit, segmenter, lexicon, ranker, langid, calendar`; public API surface designed for UniFFI (no lifetimes across FFI). | F0.1 | `cargo test` passes; `uniffi-bindgen` generates Kotlin and Swift. | 🟩 DONE |
| C1.2 | **Pack format + loader**: versioned binary/JSON pack with manifest, Ed25519 signature verify, size limits. Reject unsigned/malformed. | C1.1 | Fuzz target on parser; tampered pack rejected in tests. | 🟩 DONE |
| C1.3 | **Direct Fidel input** model (fidel layout data, 7 orders, labialised forms) driven by data, not hard-coded. | C1.2 | Golden vectors pass. | 🟩 DONE |
| C1.4 | **Phonetic Latin→Ge'ez transliteration engine** driven by profile data (rules, longest-match, composing buffer, alternatives, backspace, protected tokens). Load AM and TI DRAFT profiles from `language-data/`. | C1.2 | Golden corpus per profile version: keystrokes → composing → committed → alternatives, 100% pass. | ⬜ TODO |
| C1.5 | Qubee (Afaan Oromoo Latin) and English handling; ABC passthrough mode. | C1.1 | Unit tests. | ⬜ TODO |
| C1.6 | **Lexicon**: compact trie/FST from wordlists, prefix lookup, user dictionary overlay. | C1.2 | Lookup meets §17.1 latency budget in benchmark. | ⬜ TODO |
| C1.7 | **Ranker**: candidate ranking, next-word suggestions, autocorrect with a "harm guard" (never auto-apply a correction that is low-confidence; suggestions only). | C1.6 | Tests incl. homophone families. | ⬜ TODO |
| C1.8 | **Code-switching / language ID** (cheap on-device detector) to pick candidate languages per word. | C1.6 | Eval set accuracy recorded. | ⬜ TODO |
| C1.9 | **Calendar utilities**: Ethiopian calendar, Ethiopian clock (6:00 offset), Ethiopic numerals ↔ Arabic numerals. | C1.1 | Property tests incl. leap years, Pagume. | ⬜ TODO |
| C1.10 | Property tests (segmenter never splits grapheme clusters; NFC idempotence; reversibility where declared) and CI benchmarks (>10% regression fails). | C1.3–C1.9 | CI gate active. | ⬜ TODO |

### 1B. Language data pipeline

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| L1.1 | `language-data/` build script: source rules + licensed wordlists → signed packs (`.pack` + manifest), keys from a dev keystore. | C1.2 | Pack builds reproducibly; signature verifies in core. | ⬜ TODO |
| L1.2 | Record licence per source in a data manifest (feeds `language_datasets`). Block build if licence field is missing or non-commercial (N10). | L1.1 | Build fails on an unlicensed source (test). | ⬜ TODO |
| L1.3 | Seed packs: AM, TI, OM, EN baseline (small), meeting size budgets from SD §17.1. | L1.1 | Packs in `android/ime/src/main/assets` and `ios/.../Resources`. | ⬜ TODO |

### 1C. Android IME + companion

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| A1.1 | Gradle project, flavours `play`/`direct`, UniFFI binding integration (`cargo-ndk`), ABI splits, min SDK per SD. | C1.1 | App installs; calls a core function. | ⬜ TODO |
| A1.2 | `InputMethodService` skeleton, keyboard surface (Compose or custom View — pick per SD §6 and record ADR), input-type handling (password, email, URI, number, multiline). | A1.1 | Types ASCII into any app. | ⬜ TODO |
| A1.3 | Wire composer + candidate strip; composing text with commit/replace; handle apps that reject composing text (fallback to direct commit). | A1.2, C1.4 | Phonetic AM typing works end to end in a test app. | ⬜ TODO |
| A1.4 | Layouts: Fidel grid, phonetic QWERTY, Qubee, English, ABC; language switching (long-press space / globe); number/symbol pages; Ethiopian punctuation. | A1.3 | All four languages typeable. | ⬜ TODO |
| A1.5 | Local settings store (DataStore): enabled languages, order, active transliteration profile, themes, haptics, key height. Typing language triggers per SD §7 (do **not** block reorder when a profile is retired — Review F14). | A1.4 | Persisted across restarts. | ⬜ TODO |
| A1.6 | Pack manager (bundled-only for now): load, verify signature, version-check. | C1.2, A1.1 | Corrupt pack ignored; falls back to bundled. | ⬜ TODO |
| A1.7 | Companion onboarding: enable IME, select IME, language selection, tutorial, privacy notice. | A1.5 | New-user flow tested on device. | ⬜ TODO |
| A1.8 | Offline user dictionary (add/learn/remove), local only for now. Encrypted at rest. | C1.6 | Learned words survive restart; no network. | ⬜ TODO |
| A1.9 | Performance + QA: reference-device budgets (§17.1: key latency, cold start, memory); lifecycle tests (rotation, split screen, foldables, low-memory kill). | A1.1–A1.8 | Budgets met on low-end device; 30+ testers sign-off (human gate G1). | ⬜ TODO |

**Exit M1:** golden corpus 100% pass; budgets met; native testers.

---

## Phase 2 — Backend foundation + P0 AI (milestone M2)

Goal: identity, devices, preferences, packs, AI gateway, translate/grammar/rewrite, metering for GUEST/FREE. Read SD §8–11, §14–15 and walk the API tags in order.

### 2A. Backend skeleton

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| B1.1 | Spring Boot 4 project with Spring Modulith packages from SD §24; `ApplicationModules.verify()` test in CI. | F0.2 | Module boundary test passes. | ⬜ TODO |
| B1.2 | Flyway with `V1__baseline.sql` copied unchanged from docs; never edit applied migrations — later changes are `V2__…`. | F0.3 | App boots; schema matches. | ⬜ TODO |
| B1.3 | Platform module: Problem Details error handling with API error codes, correlation ID filter, request validation, OpenAPI contract tests (every controller response validated against `contracts/openapi.yaml`), generated client types in CI. | B1.1 | Contract test suite green. | ⬜ TODO |
| B1.4 | Outbox + db-scheduler infrastructure (outbox table dispatcher, retry/backoff, idempotent consumers). | B1.2 | Test: event survives crash and is delivered once-effectively. | ⬜ TODO |
| B1.5 | Per-user envelope encryption service (KMS adapter: local dev KMS + cloud KMS interface), key-per-user, crypto-shred operation. | B1.2 | Round-trip + shred test. | ⬜ TODO |
| B1.6 | Configuration module: `/config` (feature flags, `minSupportedAppVersion`, remote config), `APP_VERSION_UNSUPPORTED` (426) enforcement. | B1.3 | Old version gets 426 Problem. | ⬜ TODO |

### 2B. Identity and devices

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| B2.1 | Phone OTP (SMS provider interface + mock, rate limits per phone/IP/device, anomaly counters). | B1.3 | Flow tested incl. lockout and replay. | ⬜ TODO |
| B2.2 | Google sign-in (ID token verification). | B1.3 | Test with fake JWKS. | ⬜ TODO |
| B2.3 | Attestation nonce endpoint + **guest accounts** gated by Play Integrity / App Attest verifier interfaces (mock now, real later). Guests get GUEST plan. | B2.1 | Guest without valid attestation gets `GUEST_NOT_ALLOWED`. | ⬜ TODO |
| B2.4 | Access JWT + **refresh-token rotation with families and reuse detection** (reuse revokes the family). | B2.1 | Replay test revokes family. | ⬜ TODO |
| B2.5 | Devices: register, list, revoke, push-token, platform/app-version capture. | B2.4 | API tests. | ⬜ TODO |
| B2.6 | Account linking (guest → phone/Google) preserving data and entitlements. | B2.3 | Test. | ⬜ TODO |

### 2C. Languages, packs, preferences

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| B3.1 | Languages/profiles/packs read APIs and `/resource-signing-keys`; pack manifest + delta metadata; release channels. | B1.2 | Client can discover and download pack metadata. | ⬜ TODO |
| B3.2 | Admin pack publishing: upload → validate licence/signature → stage → publish channel (stable/beta). Signing keys in KMS. | B3.1 | Unlicensed/unsigned pack rejected. | ⬜ TODO |
| B3.3 | Preferences + settings sync with **`revision`** optimistic concurrency (`REVISION_CONFLICT`). | B2.4 | Concurrent-write test. | ⬜ TODO |
| B3.4 | **Destination profiles** (encrypted label, tone, language, formality, addressee gender) and Android app-rule HMAC mapping. | B3.3, B1.5 | Server never stores raw package name or plaintext label. | ⬜ TODO |
| B3.5 | Consents (`COMMUNITY_CONTRIBUTION`, `PERSONAL_STYLE`, `ANALYTICS`, …) append-only with history; gating helper `requireConsent`. | B2.4 | Withdrawal blocks dependent features. | ⬜ TODO |
| B3.6 | Personal dictionary sync (encrypted, revision-based). | B3.3, B1.5 | Round-trip test. | ⬜ TODO |

### 2D. Entitlements and metering

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| B4.1 | Entitlement resolver: plan-rank resolution across sources (subscription, store purchase, org, partner), per SD §15. Reads seeded `plan_entitlements` (PROVISIONAL_V2, config-driven). | B1.2 | Unit tests per plan × feature. | ⬜ TODO |
| B4.2 | **Metering engine**: `RESERVE → SETTLE/RELEASE` in `usage_events`, window calculation with timezone edges, DAILY/MONTHLY/NONE reset, SECOND/CHARACTER units, fractional credits. Expired reservations auto-released by scheduler. | B4.1 | Concurrency tests (no overspend under parallel requests). | ⬜ TODO |
| B4.3 | Credit lots: grant, FIFO by expiry consumption, ledger rows append-only. | B4.2 | FIFO + expiry tests. | ⬜ TODO |
| B4.4 | Signed **entitlement token** (JWS Ed25519, 24 h TTL, 72 h offline grace) endpoint; client uses it only for offline gates. | B4.1 | Verify + expiry tests. | ⬜ TODO |
| B4.5 | `/usage` and `/entitlements` read APIs. | B4.2 | Contract tests. | ⬜ TODO |

### 2E. AI gateway and P0 text services

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| B5.1 | Provider adapter SPI (`translate`, `llmTransform`, `detectLanguage`) with mock + 1–2 real adapters chosen at gate G0; per-provider data policy flags (ZDR, no-training). Providers seeded inactive. | F0.6 | Adapter contract tests with recorded fixtures. | ⬜ TODO |
| B5.2 | **Routing policies**: language-pair × feature → ordered providers, fallback, timeout, circuit breaker, kill switch, cost cap. | B5.1 | Failover test. | ⬜ TODO |
| B5.3 | **Pipeline**: auth → entitlement → reserve → (idempotency) → prompt build → provider → guardrails (script/language-ID check, refusal/length checks) → settle → respond. Prompt templates versioned in DB/resources, with addressee formality/gender and Ethiopian-calendar context. | B5.2, B4.2 | End-to-end mock test. | ⬜ TODO |
| B5.4 | **Idempotency (N5)**: `Idempotency-Key` header; at-most-once charge; encrypted ≤10 min replay cache; `REQUEST_IN_PROGRESS` on concurrent duplicate. | B5.3, B1.5 | Duplicate-retry tests incl. after cache expiry (recompute, no second charge). | ⬜ TODO |
| B5.5 | Endpoints: `/translate`, `/grammar`, `/rewrite` (and `/detect-language`, `/transliterate`). Metadata only persisted (feature, languages, units, latency, provider, status) — **no content**. | B5.3 | Contract tests; log-scrub test proves no content in logs. | ⬜ TODO |
| B5.6 | AI feedback endpoint (`rating` −1/+1, no content unless user opts in). | B5.5 | Test. | ⬜ TODO |
| B5.7 | Translation history: opt-in, encrypted, retention policy, list/delete. | B5.5, B1.5 | Off by default; test. | ⬜ TODO |

### 2F. Privacy lifecycle

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| B6.1 | Data export job (async, encrypted download link, expiry). | B3.x, B5.7 | Export contains all user data classes in SD §19. | ⬜ TODO |
| B6.2 | Account deletion: soft-delete grace, then crypto-shred + purge; append-only exceptions documented in SD. | B1.5 | Test proves data unreadable after shred. | ⬜ TODO |
| B6.3 | Retention scheduler for logs, caches, history, OTP records. | B1.4 | Purge tests. | ⬜ TODO |

### 2G. Android connects to backend

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| A2.1 | API client generated from OpenAPI; auth interceptor with refresh rotation; Problem Details mapping; certificate pinning decision per SD §18. | B1.3 | Works against local stack. | ⬜ TODO |
| A2.2 | Sign-in UX (phone OTP, Google, guest with attestation via Play Integrity). Tokens in Keystore-backed storage. | A2.1, B2.3 | Flow tested. | ⬜ TODO |
| A2.3 | Pack updates: check, download, verify, atomic swap, rollback. | B3.1, A1.6 | Tamper test. | ⬜ TODO |
| A2.4 | Settings/dictionary/destination-profile sync (revision conflict handling). | B3.3–B3.6 | Offline edit → sync → merge test. | ⬜ TODO |
| A2.5 | AI toolbar in the IME: translate / grammar / rewrite on selected or composing text; strictly non-blocking; offline or quota-denied states degrade gracefully; cached entitlement token for gating. **Never send text without an explicit user action.** | A2.2, B5.5 | Works; typing latency unaffected (benchmark). | ⬜ TODO |
| A2.6 | Consent screens, privacy dashboard, export/delete UI. | B3.5, B6.1–B6.2 | Flow tested. | ⬜ TODO |

**Human gate G2 / exit M2:** closed beta; R2 (AI quality evaluation per pair), R3 (log scrub), R4 (security), R5 (cost model, kill switches, spend alerts) pass for P0 features.

---

## Phase 3 — Hardening and public launch (milestone M3)

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| H3.1 | Observability: OpenTelemetry tracing across gateway, metrics from SD §22, SLO burn-rate alerts, AI spend anomaly alerts, OTP-abuse alerts. Crash reporter configured with content-free breadcrumbs; IME disables screenshot/view-hierarchy capture. | B5.5 | Dashboards live in staging. | ⬜ TODO |
| H3.2 | Security hardening: threat model update, dependency scanning, secrets management, rate limiting, WAF rules, admin console SSO + IP allow-list, pen-test fixes. | all | No open high/critical findings (R4). | ⬜ TODO |
| H3.3 | Load tests: AI endpoints at 3× forecast peak with injected provider latency; verify no overspend and graceful degradation. | B5.5 | Report stored in `docs/`. | ⬜ TODO |
| H3.4 | Staging/production environments, Flyway gated pre-deploy job, blue-green/rolling deploys, region chosen after Addis Ababa latency measurements. | F0.5 | Deployed; rollback rehearsed. | ⬜ TODO |
| H3.5 | Play listing, IME disclosure, Data safety form, privacy policy, terms. | A2.6 | Play review passed (R8). | ⬜ TODO |
| H3.6 | Admin console v1 (Next.js): provider routing + kill switches, pack publishing, plan/quota editing (versioned), user lookup (no content), audit log views. RBAC from seeded admin roles. | B3.2 | Admin actions audited. | ⬜ TODO |

**Exit M3:** R1–R5 and R8 passed; crash-free ≥ 99.5%; free plans only.

---

## Phase 4 — Monetisation (milestone M4)

Read SD §16 and the billing tags in API. Always fail closed.

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| M4.1 | Billing core: merchant accounts, products/prices (no-overlap guard), orders with snapshot list/discount/tax/amount, attempts, payments, refunds bound to capture, append-only ledgers. | B1.2, B4.1 | Constraint tests from SQL guards. | ⬜ TODO |
| M4.2 | Provider SPI + **Chapa** adapter (hosted checkout, ETB), webhook endpoint (signature verify → `WEBHOOK_SIGNATURE_INVALID` 401, dedupe, out-of-order handling), server-side verification before granting. | M4.1 | Sandbox end-to-end; duplicate/out-of-order tests. | ⬜ TODO |
| M4.3 | Manual renewal + reminders (push/SMS), grace logic, receipts. | M4.2 | Scheduler tests. | ⬜ TODO |
| M4.4 | Upgrade proration, credit packs → credit lots, promo/discount codes. | M4.1, B4.3 | Proration unit tests. | ⬜ TODO |
| M4.5 | Reconciliation job (provider vs ledger), discrepancy report, admin refund flow. | M4.2 | Reconciliation passes on sandbox data. | ⬜ TODO |
| M4.6 | Market/channel policy engine (which methods/currencies per country and platform; default deny). | M4.1 | Unlisted market → denied. | ⬜ TODO |
| M4.7 | **Google Play Billing**: purchases API validation, RTDN webhooks, `store_purchases` tables, states (ACTIVE, IN_GRACE_PERIOD, ON_HOLD, PAUSED, …) feeding entitlements. Only if Play eligibility confirmed (open item). | M4.1 | Sandbox test. | ⬜ TODO |
| M4.8 | Android `direct` flavour (APK distribution, Chapa path, self-update policy) and `billing-play` / `billing-direct` modules. | M4.2, M4.7 | Both flavours build; policy review. | ⬜ TODO |
| M4.9 | ArifPay adapter behind a feature flag. | M4.2 | Flag off by default. | ⬜ TODO |
| M4.10 | Companion paywall/plan screens, order status polling, receipts, subscription management. | M4.2 | Flow tested. | ⬜ TODO |

**Exit M4:** R6 passed; live real-money pilot; zero unverified grants.

---

## Phase 5 — iOS + voice + P1 features (milestone M5)

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| I5.1 | Xcode workspace, UniFFI Swift bindings (XCFramework), App Group, shared Swift package for API client. | C1.1 | Builds on device. | ⬜ TODO |
| I5.2 | Keyboard extension: layouts, composer, candidate strip, **bundled packs only mode (Full Access off)**; memory ceiling profiled on physical low-end device. | I5.1, L1.3 | Types AM/TI/OM/EN with Full Access off. | ⬜ TODO |
| I5.3 | Full Access features: network, App Group shared state, shared keychain; clear in-keyboard prompt when unavailable (N4). | I5.2 | Tested on and off. | ⬜ TODO |
| I5.4 | Companion app (SwiftUI): onboarding to enable keyboard + Full Access explanation, sign-in (Sign in with Apple if Google is offered), settings, privacy. | I5.1 | Flow tested. | ⬜ TODO |
| I5.5 | StoreKit 2 purchases, server notifications V2, `store_purchases` validation. | M4.7 | Sandbox test. | ⬜ TODO |
| I5.6 | iOS AI toolbar (translate/grammar/rewrite) + **pending-insert flow** (companion → keyboard handoff for voice). | I5.3, B5.5 | Tested. | ⬜ TODO |
| V5.1 | **Speech-to-text API**: provider adapters (ASR), per-second metering, language hints, size/duration limits; streaming endpoint for Android. | B5.1, B4.2 | Metering rounds to seconds, not minutes. | ⬜ TODO |
| V5.2 | Android streaming dictation (mic permission UX, foreground-service-free IME recording per policy, partial results). | V5.1, A2.5 | Latency budget met. | ⬜ TODO |
| V5.3 | iOS voice in companion app (keyboard extensions can't use the mic reliably) → pending-insert. | V5.1, I5.6 | Tested. | ⬜ TODO |
| P5.1 | Tone adjust + smart replies endpoints and UI; smart replies only on explicit trigger with clipboard/selection consent. | B5.5 | Tested. | ⬜ TODO |
| P5.2 | Destination profiles + Android app rules in the IME UI; iOS destination picker. | B3.4 | Tested. | ⬜ TODO |
| P5.3 | Opt-in translation history UI on both platforms. | B5.7 | Tested. | ⬜ TODO |
| P5.4 | Pack channels (stable/beta), delta updates. | B3.1 | Delta apply + rollback test. | ⬜ TODO |

**Exit M5:** App Review approved; voice evaluation thresholds met.

---

## Phase 6 — Differentiation (milestone M6)

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| D6.1 | `SCRIPT_CONVERSION` (Latin ↔ Ge'ez, romanised Amharic/Tigrinya → Fidel) server endpoint + on-device fast path. | B5.5, C1.4 | Evaluation passed. | ⬜ TODO |
| D6.2 | Cultural intelligence to PRODUCTION tier: addressee formality/gender handling, honorific registers, Ethiopian calendar/clock/numerals in outputs; native-speaker evaluation. | B5.3 | Honorific/gender evaluation passed. | ⬜ TODO |
| D6.3 | Explain text, summarise, document/email/letter drafting endpoints and UI. | B5.5 | Tested. | ⬜ TODO |
| D6.4 | TTS and pronunciation (`TEXT_TO_SPEECH`, character metering), audio caching without storing user text beyond policy. | B5.1 | Tested. | ⬜ TODO |
| D6.5 | Personal writing style (opt-in `PERSONAL_STYLE` consent): on-device style features or encrypted embeddings; delete-on-withdraw. | B3.5 | Withdrawal purges data. | ⬜ TODO |
| D6.6 | Names/places dictionary expansion programme: dataset ingestion with licences, community suggestions (`COMMUNITY_CONTRIBUTION`) moderation queue in admin, pack rebuild. | B3.2 | Pipeline tested. | ⬜ TODO |
| D6.7 | **Realtime WebSocket**: ticket via `Sec-WebSocket-Protocol`, close codes 4001/4002/4003/4500, session metering, reconnect/resume. | B4.2 | Protocol tests. | ⬜ TODO |
| D6.8 | Voice-to-voice and live conversation (companion apps): ASR → MT → TTS pipeline with latency budget, turn-taking, speaker labels. | D6.4, D6.7, V5.1 | Latency budget met. | ⬜ TODO |

**Exit M6:** native-speaker honorific/gender evaluation passed; realtime latency budget met.

---

## Phase 7 — Expansion and B2B (milestone M7)

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| E7.1 | New-language onboarding playbook: profile + lexicon + eval set + licence + gates R1/R2/R7 per language (candidates: Somali, Afar, Sidama, Wolaytta, Swahili). Add via data/migrations, not code forks. | L1.1 | One language shipped via playbook only. | ⬜ TODO |
| E7.2 | On-device models: language ID/spell first, then MT/ASR; model downloads as signed packs; licence gate (N10); device capability checks; `ON_DEVICE_AI_MODELS` entitlement. | C1.2 | Commercial-licensed model benchmarked on-device. | ⬜ TODO |
| E7.3 | Organisations: seats, invites, roles, org-level billing and usage source `ORGANIZATION`, BUSINESS plan (finalise DRAFT). | M4.1 | Isolation tests. | ⬜ TODO |
| E7.4 | Custom glossaries (`PREFER`/`STRICT` enforcement) applied in translation pipeline. | B5.3 | Tests. | ⬜ TODO |
| E7.5 | Partner API: API keys with scopes `translate, transform, transliterate, detect_language, speech, usage_read`, per-key rate limits, contract metering (`PARTNER_CONTRACT`), docs portal. | B4.2 | Scope + isolation tests; SLA tests. | ⬜ TODO |

**Exit M7:** per-language gates; partner SLA and isolation tests pass.

---

## Cross-cutting backlog (run continuously)

- **Language acceptance:** native-speaker panels each milestone (SD §21.4).
- **Release gates R1–R8:** check before every public release (SD §21.5).
- **Docs:** when behaviour changes, update SD/SQL/API and add an ADR in `docs/adr/` (numbering starts at D33).
- **Accessibility:** TalkBack/VoiceOver, large text, contrast on every UI step.
- **Security reviews:** per phase exit.

## Suggested parallel tracks

After Phase 0, three tracks can run concurrently by separate agents:

1. **Core + Android** (C1.x → A1.x → A2.x)
2. **Backend** (B1.x → B6.x → M4.x)
3. **Language data + admin** (L1.x → H3.6 → D6.6)

Sync points: pack format (C1.2 ↔ B3.2), API contract (B1.3 ↔ A2.1), entitlement token (B4.4 ↔ A2.5).

## Agent working checklist (per step)

1. Read the referenced SD sections, SQL tables and API operations.
2. Write the failing test first where feasible.
3. Implement the minimum to satisfy the step and the rules in §0.1.
4. Run lints and tests; verify no content is logged.
5. Update contracts/docs if anything changed; record open questions in `docs/adr/`.
6. Report: what was done, what was verified, what was skipped, any contract conflicts.
