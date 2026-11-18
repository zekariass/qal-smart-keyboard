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
| Phase 1 | keyboard-core + Android offline alpha (M1) | 22 | 21 | 0 | 0 | 1 | 95% |
| Phase 2 | Backend + P0 AI (M2) | 39 | 12 | 0 | 0 | 27 | 31% |
| Phase 3 | Hardening + launch (M3) | 6 | 0 | 0 | 0 | 6 | 0% |
| Phase 4 | Monetisation (M4) | 10 | 0 | 0 | 0 | 10 | 0% |
| Phase 5 | iOS + voice + P1 (M5) | 13 | 0 | 0 | 0 | 13 | 0% |
| Phase 6 | Differentiation (M6) | 8 | 0 | 0 | 0 | 8 | 0% |
| Phase 7 | Expansion + B2B (M7) | 5 | 0 | 0 | 0 | 5 | 0% |
| **Total** | | **109** | 39 | 0 | 0 | 70 | 36% |

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
| 2026-11-17 | C1.9 | 🟩 DONE | 15b4c9f, 6ae9369, 82cb96a | `core/src/calendar.rs`: Gregorian<->Ethiopian conversion (JDN, 13 months, Pagume 5/6 days), Ethiopian 6:00-offset clock, Ethiopic<->Arabic numerals (1..=99,999,999), all UniFFI-exported. 32 unit/property tests; `cargo fmt --check`, `clippy -D warnings` and `cargo test` pass on Rust 1.91.0. First real test run caught two bugs the Python cross-check missed (clock inverse for hours 6-11; wrong 20,000 numeral expectation), both fixed. |
| 2026-11-17 | B1.1 | 🟩 DONE | 2d83e78, 3edbd45 | Spring Modulith 2.1.2 (BOM; Boot 4.1.2 does not manage it); 21 SD §24 module packages (package-info only); app class moved to `com.qalsmartkeyboard` root so Modulith sees exactly those modules; `ModularityTests` (`ApplicationModules.verify()`) runs in surefire in the existing CI backend job. `mvnw verify` green on JDK 25 against PG18: 3 unit + 10 IT, none skipped. |
| 2026-11-17 | B1.2 | 🟩 DONE | 061439e | Flyway and `V1__baseline.sql` were already wired by F0.3 (file byte-identical to docs; CI `sync.sh --check` covers drift). Added `FlywayBaselineIT`: Spring Boot app boots on Testcontainers PG18, Flyway applies V1 (one migration, success, `validate()` OK), 86 tables. `mvnw verify` green: 3 unit + 12 IT. |
| 2026-11-17 | L1.1 | 🟩 DONE | 13537b2, merge | Pack build: stdlib-only `language-data/scripts/build_packs.py` (`sources/*.source.json` -> signed `.pack` + exact signed manifest; pure-Python Ed25519 in `ed25519_ref.py`, checked against RFC 8032 and the core's test vector). Reproducible (identical SHA-256 across builds); verified in core via new `qal-pack-verify` bin (am+ti layout packs load, tampered pack rejected). Dev-only random keystore in gitignored `language-data/keys/` (no key committed; refuses STABLE and non-devOnly keys). Sources carry per-input `licenseCode` for L1.2. Only artifact kind `file` so far; lexicon (C1.6) and rule (C1.4) kinds follow with those steps. Re-verified after merge: cargo fmt/clippy/test and 7 Python tests green. |
| 2026-11-17 | C1.6 | 🟩 DONE | d861bab, merge | Lexicon: immutable radix trie in a flat byte buffer ("QLX1", the `DICTIONARY` pack payload; `from_bytes` fully validates, byte-flip and truncation tested), exact top-k prefix search via per-node max-frequency best-first walk, NFC-normalised. User overlay (add/learn/remove, removal hides base words, 50k cap) exports/imports opaque bytes for host-side encrypted storage (A1.8). FFI `Lexicon` + `LexiconError`. Bench (`cargo bench --bench lexicon`, asserts p95 <= 5 ms, an allotment within the SD §17.1 30 ms strip budget; SD sets no lexicon-specific number): 179k words = 2.34 MiB, load ~13 ms, worst p95 0.33 ms. Host numbers only; re-measure on devices in A1.9. Open: case/diacritic folding left to C1.7 and profiles; engine not yet wired to Lexicon or validating DICTIONARY artifacts. Re-verified after merge: fmt/clippy/test (61 unit) green. |
| 2026-11-17 | C1.4 | 🟩 DONE | a2fb3ea, merge | Phonetic Latin->Ge'ez engine: data-driven `qal-transliteration-profile` v1 (`language-data/profiles/{am,ti}-phonetic-draft.json`, DRAFT per N9; parser `core/src/translit/profile.rs`); `PhoneticSession` composing buffer (longest match, backspace, homophone alternatives, labialised forms, Ethiopic punctuation by doubling, protected URL/e-mail/term tokens). FFI: `InputMode::PhoneticLatin` (needs a TRANSLITERATION_RULES pack), `select_candidate`, `translit_profile` (id/version/status). Golden corpora `core/tests/golden/{am,ti}-phonetic-0.1.0.json` (47 + 14 vectors) pass 100%. Re-verified after merge: fmt/clippy/test green. OPEN: scheme not native-speaker reviewed (G0; choices listed in `language-data/profiles/README.md`); SD release target of 2,000+ vectors per profile not met; profile validated lazily (bad profile fails at `set_input_mode`/`translit_profile` with InvalidState, not at `load_pack`) because C1.2/C1.3 test fixtures use a garbage TRANSLITERATION_RULES artifact; Kotlin/Swift bindings and fuzz crate not regenerated. |
| 2026-11-17 | C1.5 | 🟩 DONE | 6e33439, merge | Qubee (om), English and ABC are plain passthrough per SD 7.1 (text committed exactly as typed; no auto-capitalisation, no apostrophe rewriting; Backspace after commit = one grapheme cluster, so a digraph takes two presses). New data-driven `qal-latin-orthography` v1 (`language-data/orthography/{om-qubee,en-latin}-draft.json`, DRAFT per N9; `core/src/latin.rs`): multi-letter units, hudhaa (U+0027/U+2019/U+02BC) word-internal only between letters, word_spans/units/fold_apostrophes/capitalize helpers. `InputMode::Qubee` enabled for om; refused switches keep the previous mode. 9 integration + 7 unit tests. Re-verified after merge: fmt/clippy/test green. OPEN: unit list (ts, zy unconfirmed) and hudhaa edge rule (word-final hudhaa is split off as punctuation) not native-speaker reviewed (G0); orthography is compiled into the crate (no pack kind yet); no FFI word-segmentation API yet. |
| 2026-11-17 | C1.8 | 🟩 DONE | f3f9014, merge | Code-switching / language ID (`core/src/langid.rs`, `langid/model.rs`): script detection + char 1-3-gram naive-Bayes (QLID v1 bytes, validated) + optional lexicon hits + 3-word context prior + enabled-language mask. Tags am/ti/om/en plus romanised am-latn/ti-latn. Returns ranked candidates with confidences and an `ambiguous` flag (top < 0.8), never a single forced answer; ~4 us/word release. Eval on a SYNTHETIC hand-written, non-native-reviewed set of 226 tokens (`language-data/eval/langid/`, recorded in `results.tsv`): top-1 80.1% overall (am 91.7, ti 77.8, om 85.7, en 66.0, am-latn 82.8, ti-latn 83.3), gold-in-candidates 93.4%; regression floors in `core/tests/langid_eval.rs`. Re-verified after merge: fmt/clippy/test green. OPEN: seeds (~100 words/class) and eval are synthetic, so real accuracy needs the G0 native-speaker corpus; models DRAFT, not user-exposed (N9); English weakest (66%); lexicon and context signals unit-tested but not in the eval; C1.7/engine must decide whether to keep AmLatn/TiLatn as separate tags. |
| 2026-11-17 | C1.7 | 🟩 DONE | 9e00763, merge | Ranker (`core/src/ranker.rs` + `ranker/{fold,bigram,tests}.rs`, FFI object `Ranker`): log-scale scoring (lexicon incl. user overlay, bigram, exact bonus, edit and completion penalties); one-edit correction neighbourhood (Latin with QWERTY adjacency; Fidel delete/transpose/vowel-order slip); next-word model from a "QBG1" bytes payload plus local user bigram overlay. Matching equivalence for case, Latin diacritics and Ge'ez homophone families (ሀ/ሐ/ኀ ሰ/ሠ አ/ዐ ጸ/ፀ) while candidates keep the lexicon spelling and a valid typed word is never rewritten. Harm guard: Apply only if the typed word is not valid (nor equivalent to one, nor valid in a guard lexicon), >= 3 letters, no digits/symbols/stray capitals, best candidate within one edit keeping first letter and script, posterior >= 0.85 vs rivals and a typed-word-intended prior; else SuggestOnly/None; property test: Apply implies all guards. Bench (`cargo bench --bench ranker`, asserts p95 <= 10 ms): worst p95 2.1 ms on a 200k-word synthetic lexicon, host numbers only (A1.9 re-measures). Additive `Lexicon::word_score`. Re-verified after merge: fmt/clippy/test (109 unit) and both benches green. OPEN: thresholds (all named `pub const`) are provisional until real frequencies and an eval set exist (G0); QBG1 bigram format is the agent's own, to be confirmed with the FREQUENCY_LIST artifact in L1.x; no Fidel insertions, one-edit limit; engine not yet wired to Ranker/Lexicon/LangId. |
| 2026-11-17 | L1.2 | 🟩 DONE | cde3471, merge | Licence gate (N10): allow-list `language-data/licenses.json` (commercial-use SPDX/LicenseRef codes; unknown codes fail closed; any -NC code refused even if marked commercial); `build_packs.py` checks the pack-level and every input `licenseCode`, requires non-empty `inputs[]` and aborts before writing output. Each build emits a deterministic `datasets.manifest.json` shaped to `language_datasets` (datasetCode, licenseCode, permittedUses, consentBasis, reviewStatus=PENDING_REVIEW, sha256, path, packs). 12 new tests (missing/unknown/NC at pack and input level, passing case, reproducibility, CLI exit code); 28 Python tests green after merge. OPEN: CC-BY-SA-4.0 and MPL-2.0 are allow-listed as commercial pending legal review (OI-R7); `PROPRIETARY`/`UNKNOWN` (named in the DB comment) are rejected, a signed commercial licence needs its own LicenseRef entry; permittedUses always ["COMMERCIAL"]. |
| 2026-11-17 | A1.1 | 🟩 DONE | ef403cd, merge | Gradle `:core-bindings` module (SD 6.2) builds the Rust core for arm64-v8a/armeabi-v7a/x86_64 with cargo-ndk, generates Kotlin via uniffi-bindgen (library mode) and packages .so + bindings (nothing generated committed). `:app` flavours play/direct with per-ABI + universal APK splits; minSdk 24 and ABI list in `gradle.properties`, NDK/JNA in the version catalog. `assemblePlayDebug` + `assembleDirectDebug` build 8 APKs; JVM test calls core `version()` via JNA on host. VERIFIED ON DEVICE: the `play` x86_64 debug APK installed on the Android 35 x86_64 emulator (AVD Tablet-10-inch) and `MainActivity` rendered "Hello core 0.1.0!" (version from the Rust core via UniFFI). NOT verified: arm64-v8a and armeabi-v7a at runtime, the androidTest, assembleRelease/R8 (keep rules for JNA/UniFFI needed), and the CI Android job (updated with Rust, targets, cargo-ndk, NDK but never run in CI; `sdkmanager` line untested). OPEN: minSdk 24 is the skeleton's value, SD gives none (reference low-end device = API 29); skeleton has `:app` where SD 6.2 lists `:companion`; Hilt (SD 6.2) not added; `coreKtx` 1.10.1 is old. Tools installed: 3 Rust Android targets and cargo-ndk 4.1.2. |
| 2026-11-17 | C1.10 | 🟩 DONE | d4a48c3, 6232969, merge | Code complete and verified locally; NOT marked DONE because the plan's check is "CI gate active" and the `core-bench` job has never run in GitHub Actions. Built: segmenter implemented (it was a stub): UAX #29 clusters via `unicode-segmentation`; `core/tests/properties.rs` (11 proptests: segmenter tiles text and never splits marks/ZWJ, NFC idempotence of lexicon, ranker and both composers, phonetic Backspace undoes the last key); bench gate `core/scripts/bench_gate.py` (5 runs, min of per-label p50; fails at >10% above `core/benches/baseline.json` and >0.02 ms) + `core-bench` CI job + `record_baseline` dispatch input. The new NFC property test found a REAL BUG after merge (pending "c" + lone U+0341 committed "c"+U+0301, not NFC): both composers now normalise assembled commits (`nfc_commit`), regression case checked in, all 11 properties pass at 5000 cases each. Gate demonstrated: passes on baseline, fails on a 0.8x baseline and on an injected slowdown. LIMITS: baseline is from a Windows dev machine and must be re-recorded on the CI runner (dispatch `record_baseline`); on a re-run on this idle machine the gate gave one false FAIL (autocorrect +15%) in 2 runs, then passed, so expect some noise; it cannot catch p95 tail regressions or sub-0.02 ms labels; engine does not use the segmenter yet. To finish: push, run the job, re-record the baseline on the runner, mark DONE. CLOSED: pushed `047beeb`; CI run 44152636782 green on all 8 jobs, including `core-bench` (gate passed against the Windows baseline on ubuntu-latest) and the Android job (first CI run since A1.1). FOLLOW-UP (human, needs GitHub UI): run CI via workflow_dispatch with `record_baseline`, download the `bench-baseline` artifact and commit it as `core/benches/baseline.json`. |
| 2026-11-17 | L1.3 | 🟩 DONE | 1b2dd6f | Seed packs AM/TI/OM/EN: `build_seed_packs.py` plus new core bin `qal-artifact-build` (public API) build per-language DICTIONARY (QLX1) and FREQUENCY_LIST (QBG1) packs, one shared LANGUAGE_ID_MODEL (`mul`), and re-pack the existing AM/TI layouts and phonetic profiles. Shipped in `android/ime/src/main/assets/packs/` and `ios/KeyboardExtension/Resources/packs/` with `seed-packs.json`. Verified by me: 38 Python tests, fmt, clippy -D warnings, cargo test all green; `build_seed_packs.py --check` within budgets (about 44-50 KB per language vs 15 MB Ethiopic / 25 MB English); `qal-pack-verify` OK on all 13 Android packs; Android and iOS copies identical; no private key tracked. OPEN: all DRAFT/UNVERIFIED (N9, INTERNAL, `userExposable` false): about 90 hand-written words per language, placeholder frequencies, `LicenseRef-Qal-Original`, not native-reviewed (G0), no licensed corpus yet. No OM/EN layout pack. Engine does not yet consume dictionary/frequency/langid packs (wiring is A1.3). Packs signed with a per-checkout dev key, so a rebuild changes the bytes. |
| 2026-11-17 | A1.2 | 🟩 DONE | d0b7932 | `KeyboardImeService` plus custom-View ASCII QWERTY surface (letters, shift, backspace, space, enter, symbols, number/phone pads) with `EditorInfo` handling (password: no learning, no previews; email, URI, number, multiline, IME actions); ADR 0003 records custom View over Compose. Verified by me: `:ime:testDebugUnitTest` 16 tests, 0 failures; screenshot of the agent's emulator run shows the keyboard and "Hi qa9" in a Contacts text field (API 35 x86_64). OPEN: `:app:assemblePlayDebug` built for x86_64 only (not other ABIs or `direct` flavour); only the plain-text field was exercised on a device, other input types covered by unit tests only; bottom row needs window-inset padding; no key previews, long-press, accessibility or enable-IME settings screen yet. Composer and candidate strip are A1.3. |
| 2026-11-17 | B1.3 | 🟩 DONE | 207cec9, 2de05c3, merge 3e6fc46 | Platform module: `ApiErrorCode` (all contract `Problem.code` values), `ApiException`, global `ProblemExceptionHandler` (RFC 9457, static titles/messages, no rejected values or exception messages echoed; 500 has no detail), `CorrelationIdFilter` (highest precedence; keeps inbound `X-Correlation-Id` only if `^[A-Za-z0-9._:-]{1,100}$`, else UUID; MDC + header on every response). Contract tests: test-scope `com.atlassian.oai:openapi-request-validator-mockmvc` 3.0.0 (OpenAPI 3.1); `@ContractValidated` validates every MockMvc response against `contracts/openapi.yaml`; negative tests prove the validator fails on a non-conforming Problem, an undeclared status and an unknown path. CI contracts job generates TypeScript types (`openapi-typescript` 7.13.0) and type-checks them (`tsc` 5.9.3 --strict). REVIEW FIX (me): 405/406/415 were reported as `VALIDATION_FAILED`; added `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE` to the contract and mapped them; SD 14.4 now names the contract as the canonical code list (it listed stale names). Verified by me: `mvnw -Djava.version=21 verify` 78 unit + 12 IT, 0 failures, 0 skipped. OPEN: `ApiException` cannot set headers yet (`Retry-After`/`RateLimit-*` come with rate limiting B4.x and idempotency); no `SecurityFilterChain` yet, so unauthenticated requests get Spring's default 401 instead of a Problem until B2.4; 5xx handler logs the full throwable server-side (exception messages could contain request fragments; revisit with logging redaction in Phase 3); Problem `type` base URI is the contract's placeholder host (`qal.api.problem-base-uri`). |
| 2026-11-17 | B1.4 | 🟩 DONE | 93ddb1c, review fix, merge 82c4e12 | `platform.outbox` (Modulith named interface `outbox`): `OutboxPublisher.publish(...)` requires an active transaction (same-transaction write with the domain change), UUIDv7 ids, payloads carry ids/metadata only (no user text). `OutboxDispatcher` claims due PENDING/FAILED and expired-lease PROCESSING rows with `FOR UPDATE SKIP LOCKED`, leases them, runs handlers outside the claim transaction, PUBLISHED on success, FAILED with exponential backoff + equal jitter (5 s base, 1 h cap), DEAD at `max_attempts`; `last_error` is the exception class only. Idempotent consumers: `processed_events (consumer, event_id)` row inserted in the handler's own transaction, so a redelivery skips the body. db-scheduler 17.1.0 core (its Boot starter is not Boot 4 compatible) runs recurring task `outbox-dispatcher`; Micrometer counters/gauges. `V2__outbox_scheduler.sql` adds `scheduled_tasks`, `processed_events` and a DEAD partial index (88 tables). REVIEW FIX (me): outcome updates fenced by the claimed `attempts`, so a stale worker whose lease expired cannot overwrite the reclaiming worker's state (new regression test). Verified by me: `mvnw -Djava.version=21 verify` 78 unit + 30 IT (OutboxIT 17 incl. crash-after-claim and crash-after-effect: delivered twice, applied once), 0 failures, 0 skipped. OPEN: docs DB design and `contracts/sync.sh` cover V1 only; V2+ migrations live only in `backend/` (decide whether docs should list them); `processed_events` retention is B6.3; no per-aggregate ordering; lease must exceed the longest handler run. |
| 2026-11-17 | B1.5 | 🟩 DONE | c8e9a93, merge 4d3bc87 | `platform.crypto` (Modulith named interface `crypto`): `UserDataEncryption` encrypt/decrypt/rotateKey/cryptoShred. AES-256-GCM (JDK) under a random 256-bit per-user DEK, random 96-bit nonce per value; ciphertext v1 = `0x01 || key_version(4) || nonce(12) || ct || tag(16)`; associated data binds user id, key version, table, column and row id (length-prefixed). DEK stored only KMS-wrapped in `user_data_keys`, wrap bound to {purpose, userId, keyVersion}. Per-user advisory lock serialises create/rotate/shred; unique-index race re-read. KMS port with local dev adapter (KEK from `QAL_LOCAL_KMS_KEK`; refuses prod/production/staging; ephemeral KEK only with an explicit dev/test switch) and a cloud adapter over a `CloudKmsClient` interface (no SDK yet). `qal.crypto.kms.provider` has NO default: `spring-boot:run` needs `QAL_KMS_PROVIDER=local` + `QAL_LOCAL_KMS_KEK` (README); tests use `src/test/resources/config/application.yaml`. Shred: all keys DESTROYED + wrapped DEK NULL (constraint satisfied), idempotent, joins caller transaction; a shredded user can never get a new key. LRU DEK cache (copies in/out, wiped on eviction); key row re-read on every call so shred is immediate cluster-wide. ADR 0005. Reviewed by me (AEAD use, AAD encoding, wrap context, cache aliasing, shred path). Verified by me after merging main into the branch: `mvnw -Djava.version=21 verify` 133 unit + 42 IT, 0 failures, 0 skipped. OPEN: OI-P6 (backups vs shred), OI-P7 (dictionary HMAC key), OI-P8 (table ownership/metadata); guest→account merge (B2.6) must re-encrypt, never copy bytes; re-encryption after rotation and KEK re-wrap not implemented. |
| 2026-11-17 | B1.6 | 🟩 DONE | f6b861e, merge e215101 | `configuration` module: `GET /api/v1/config` per contract from `feature_flags` + `app_configurations` (ALL scope overridden per key by the caller's platform; `BACKEND` rows never served; `minSupportedAppVersion`/`recommendedAppVersion` are reserved keys; flags true only if enabled, platform matches, version >= `min_app_version`, no `targeting`, and the deterministic rollout bucket `SHA-256(flagCode:subjectKey)` mod 100 admits). In-memory snapshot cache (`qal.config.cache-ttl`, 30 s; stale snapshot served if reload fails). Version gate: MVC interceptor on `/api/v1/**` throws `APP_VERSION_UNSUPPORTED` 426 Problem (with `minSupportedAppVersion` extension) via B1.3's handler; SemVer precedence. Exempt: `/api/v1/config`, billing webhooks and store notifications; `/partner/v1` and actuator not covered. Missing headers are not errors (contract says optional; client-asserted, so a compatibility gate not a security control); malformed version/platform gives 400 `VALIDATION_FAILED` without echoing the value; config outage fails open. Verified by me after merging main into the branch: `mvnw -Djava.version=21 verify` 191 unit + 45 IT, 0 failures, 0 skipped (explicit tests: old version gets 426 Problem, web and IT against DB rows). OPEN: OI-P10 (426 not declared in the contract), OI-P11 (no security chain until B2.4); no subject key yet, so partial rollouts and targeted flags evaluate false and `user_feature_overrides` is ignored until B2.4; no cache invalidation on change. |
| 2026-11-17 | A1.3 | 🟩 DONE | ffb6759, merge b6b11e8 | Core: engine builds a lexicon + ranker per language from DICTIONARY/FREQUENCY_LIST packs; phonetic candidates = default composition, transliteration alternatives (C1.4 order), then ranker completions/one-edit corrections (kind `Correction`), de-duplicated, capped; nothing auto-applied; lookups only for all-Ge'ez compositions; last committed word kept in memory as bigram context; ranking outside the state lock with a generation check so `select_candidate` always matches the strip; FFI unchanged. Android: temporary `BundledPacks` loader (13 packs, DEV key from `seed-packs.json`, replaced by A1.6), temporary mode key ABC/አማ/ትግ (A1.4 replaces), `InputConnectionAdapter` (batch edit; grapheme-to-UTF-16 deletion via BreakIterator; direct-commit fallback on TYPE_NULL, rejected `setComposingText` or a missing composing span; foreign cursor moves finish the composition), candidate strip with DRAFT tag (N9), hidden in password/ABC; password/email/URI/number/phone fields forced to ABC; no text logged. ADR 0004. Agent verified on the API 35 x86_64 emulator: "selam" -> ሰላም with composing underline, strip, bigram ranking (ነው before ነህ), cursor-move handling, one-cluster Backspace; it found and fixed a device-only race (late `onUpdateSelection` after our own `finishComposingText`). Verified by me after merging main into the branch: core fmt, clippy x2, 229 tests; Android play+direct assemble and 56 JVM tests, 0 failures. NOT verified: fallback against a real composing-rejecting app, password/email/number fields and Tigrinya on device, ARM APKs, real-device latency; one unreproduced double-delete in an early emulator run. FOLLOW-UP: an unparsable (but correctly signed) dictionary pack is accepted and drops the language's previous model; A1.6 should keep the last good model. OPEN for panel/product: homophone alternatives can push dictionary words off the strip and dictionary frequency does not yet reorder homophones (SD 7.2); C1.7 autocorrect-on-space not wired; DRAFT wording; add TYPE_NULL terminal and WebView fields to the A1.9 device matrix. |
| 2026-11-17 | A1.4 | 🟩 DONE | 6d05b54, merge 1ed95b8 | Layouts: Fidel grid for am/ti built from core `fidel_layout` data (two-key consonant then vowel order, long-press popup with all forms), phonetic QWERTY am/ti with the A1.3 strip, Qubee (hudhaa key on main page), English, ABC; mode indicator on the space bar (e.g. `አማ · ፊደል`, `አማ · ABC→አ`) with DRAFT tag. Switching: globe tap = next enabled language (its last native layout), globe long-press cycles that language's layouts ending on ABC (SD 7.1), space long-press = language picker, one-tap ABC toggle; every switch commits the composition first; password/email/URI/number/phone stay ABC with no switching. `EnabledLanguages` interface (default am, ti, om, en) for A1.5. Punctuation: tap `.`/`,` stay ASCII, long-press gives ። ፣ ፤ ፥ ፦ ፧ ፨ ፡ (also a row on `?123`); Ethiopic numerals as long-press alternatives on digits. Popups drawn in-view (`PopupOverlay` in a 190dp transparent headroom excluded from layout and touch insets) because `PopupWindow` was clipped on API 35. Agent verified on the API 35 emulator: Fidel two-key ሰላም, long-press popup, globe am->ti->om, Qubee "ba'e", English, globe long-press cycling, phonetic strip. Verified by me: Android play+direct assemble and 77 JVM tests (incl. one word typed end to end per language through the real core), 0 failures; insets reviewed. NOT verified on device: Tigrinya, ABC toggle, numeral/punctuation selection, symbols page, password/email/number fields. FOLLOW-UP: popups are press-slide-release only (headroom not touchable); per-key TalkBack virtual nodes missing (before A1.9). OPEN for panel: tap `.`/`,` as ። ፣ in Fidel; grid density and key order; labels; default two-key order (6th vs 1st). OPEN for product: switch lands in native layout vs keeps ABC; space long-press picker vs cycle; first-run default (am Fidel vs ABC). |
| 2026-11-17 | B2.1 | 🟩 DONE | a5f1618, merge b4f5b9e | Phone OTP: `otp/start` + `otp/verify` per contract; 6-digit `SecureRandom` codes stored as `v1:`HMAC(challengeId||code), constant-time compare; 5-min TTL, 60 s cooldown, 5 attempts then lockout; replayed/superseded/unknown challenges all `OTP_INVALID`; per-number advisory lock. One secret `QAL_OTP_SECRET_KEY` (fail closed, ephemeral only in dev/test) derives the phone-hash, code-hash and phone-sealing keys. **V3 migration** adds `otp_challenges.phone_ciphertext` (AES-GCM, challenge id as AAD) because verify carries only challengeId+code. E.164 + `+251[79]` rule (no libphonenumber; ADR 0006). Rate limits via new `platform.ratelimit` port, in-memory Bucket4j 8.21.0 **per instance only** (must become the distributed store before running >1 instance); 429 `RATE_LIMITED` with `Retry-After`/`RateLimit-*` (`ApiException` can now carry headers). `notifications` SMS port with a masked-log mock only (OI-11 aggregator open). Micrometer anomaly counters. Verified by me (after merging main): see B2.4. |
| 2026-11-17 | B2.4 | 🟩 DONE | b4097c8, merge b4f5b9e | ES256 access JWT (Nimbus; `typ at+jwt`, `kid`, claims iss/aud/sub/sid/accountType/iat/exp/jti, no PII, 15 min max; P-256 keys from `QAL_JWT_*`, checked at startup, `previous-keys` for rotation, fail closed); only an otherwise-valid expired token gets `TOKEN_EXPIRED`. Refresh: 256-bit opaque, SHA-256 hash in `user_sessions`, 60-day sliding, row lock + successor + CAS to ROTATED; reuse revokes every ACTIVE row of the family (`TOKEN_REUSE`), sets `reuse_detected_at`, one outbox event per incident; concurrent refresh losers count as reuse (SD 9.3). Logout (family or all). Baseline `SecurityFilterChain` (closes OI-P11): stateless, allow-list from each operation's contract `security`, optional-bearer endpoints reject an invalid token, `/partner/v1` closed (`API_KEY_INVALID`), 401/403 contract Problems via `platform.ProblemResponseWriter`. ADR 0006. Reviewed by me (rotation transaction, reuse path, algorithm pinning). Verified by me after merging main into the branch: `mvnw -Djava.version=21 verify` 262 unit + 69 IT, 0 failures, 0 skipped (replay test revokes family; 8-thread refresh race yields exactly one successor). FOLLOW-UP (decided, ADR 0007 D8/D9): refresh grace window for lost responses; contract gaps (installationId on otp/start, 403/401/400 declarations, marketSource enum). OPEN: access tokens stay valid up to 15 min after revocation; reuse event has no consumer yet (notifications); `/config` can now use the user id as rollout subject (B1.6 follow-up); challenge purge is B6.3; `VOICE_CALL` sent as SMS; OTP text English only. |
| 2026-11-17 | A1.5 | 🟩 DONE | dd14cac, merge 1190171 | `:data`: Preferences DataStore (hand-written lenient codec, every write sanitised, `schema_version` 1) holding ordered typing languages with the contract `TypingLanguage` fields (`toSyncItems()` gives the `/me/typing-languages` shape), numeralStyle, theme, haptics, key height (80-130%), last layout per language. F14: `ProfileResolver` replaces a retired profile with the newest active one of the language and flags the change; never touches order or enabled flags; reorder never consults profiles. IME: `SettingsSnapshot` cached off the key path; A1.4's `EnabledLanguages` and last-mode state now persisted; Ethiopic numerals offered only when numeralStyle=ETHIOPIC; candidate strip toggle. ADR 0007 applied: am/ti first-run default = phonetic QWERTY (globe cycle PHONETIC, FIDEL, ABC); Fidel grid tap `.`->። `,`->፣ with ASCII first on long-press. `:app` Compose settings screen. Verified by me after merging main: play+direct assemble and 100 JVM tests (`:data` 11 incl. persistence across a new DataStore instance on the same file, the done-when), 0 failures. NOT verified on device. OPEN: no `ProfileCatalog` wired yet (A1.6 supplies it from installed packs and calls `applyProfileFallback`), so the profile picker is automatic only and no "profile changed" banner; `conversionTrigger` and `unknownTokenBehavior` stored but not applied by the engine; sync revision comes with A2.x. |
| 2026-11-17 | A1.6 | 🟩 DONE | 701c32f, merge 22556e4 | `ime.packs` pack manager replaces `BundledPacks`: bundled `assets/packs` plus `noBackupFilesDir/packs/installed` (A2.3 writes there); newest valid pack per (language, type, platform) wins, bundled wins ties; each candidate verified on a throw-away engine first; rejected installed files deleted; logs = file name + reason only. Trust keys generated at build time (`generate<Variant>PackTrustConfig`): debug uses the DEV key, release needs `-Pqal.packTrustKeysFile` and refuses DEV-only keys unless `-Pqal.allowDevPackKeys=true`; runtime `PackTrust.check` repeats the rule; `seed-packs.json` no longer read at runtime. Version floor in `versions.txt` (atomic writes), raised only by valid winners; older installed packs refused (`DOWNGRADE`); bundled packs exempt from the floor (they ship inside the signed APK). Core: unparsable but verified DICTIONARY/FREQUENCY_LIST packs still count for rollback but never replace the working model (A1.3 follow-up closed); new FFI field `PackInfo.payload_usable`; manager rejects such packs as `PAYLOAD_UNUSABLE`. Agent verified on the emulator: three corrupt installed packs rejected (CHECKSUM_MISMATCH, BAD_SIGNATURE, MALFORMED) and deleted, phonetic ሰላም with lexicon candidates and the Fidel grid still work from bundled packs. Verified by me after merging main (resolved conflicts with A1.5 in the IME service, test env and README): play+direct assemble and 115 JVM tests (15 `PackManagerTest` on the real core), core fmt/clippy x2 and 231 tests, 0 failures. Decisions: ADR 0007 D10 (production public keys committed; private keys in KMS; re-sign bundled packs before release), D11 (missing `versions.txt` fails open). OPEN: `minAppVersion` enforcement left to A2.3; valid newer installed packs verified only in JVM tests; `ProfileCatalog` from installed packs not yet wired into A1.5's `applyProfileFallback`. |
| 2026-11-17 | B2.4 follow-up | 🟩 DONE | c445349, merge caa16bd | ADR 0007 D8: refresh grace window (`qal.identity.tokens.refresh-reuse-grace`, default 30 s, 0 disables, max 2 min): a ROTATED token whose direct successor is ACTIVE, unexpired and never presented (`last_used_at IS NULL`), within the window measured from the FIRST rotation, rotates again (old successor REVOKED `SUPERSEDED_RETRY`); every other reuse revokes the family as before; presenting a superseded successor counts as reuse. Per-family advisory lock, then presented row, then successor, then family rows (one order everywhere); this also fixes a B2.4 race where a concurrent revocation/logout could miss a just-inserted successor. **V4 migration** widens the `revoked_reason` CHECK for `SUPERSEDED_RETRY`. SD §9.3 amended; ADR 0006 updated. D9 contract fixes (docs copy, synced): optional `installationId` on `OtpStartRequest` (+ `start-per-device` limit 10/h), 403 on otp/verify and auth/refresh, 401 on all 10 optional-bearer operations (invalid bearer is never ignored), 400 on auth/refresh, `UserProfile.marketSource` enum aligned to the V1 CHECK list; `IdentityContractDeclarationsTest` guards these. Verified by me after merging main: 271 unit tests + 80 IT (SessionsIT rerun alone after a Testcontainers start failure under host memory pressure), 0 failures; `sync.sh --check` and the structural OpenAPI check (0 errors). OPEN: device/user-agent binding for the grace window not added (mobile IPs change); clients must keep only the newest refresh token (stated in the contract). |
| 2026-11-17 | A1.x follow-ups | 🟩 DONE | af3d391, cc7fd87, da04856, merge 242c5ca | Core (ADR 0007 U6): phonetic candidates keep index 0 = literal rule output; homophone alternatives sorted by `Lexicon::word_score` (unknown ones after, C1.4 order, stable); session keeps the full homophone list; at least 2 slots reserved for dictionary completions when the ranker has any; session list replaced with the shown list so `select_candidate` matches; no dictionary = exactly C1.4. One expectation changed (`max_candidates=2` now shows literal + one completion); ADR 0004 U6 amendment. Android: installed TRANSLITERATION_RULES profiles published to `:data` after pack load; `applyProfileFallback` runs per language only when that language has a loaded profile (a missing/rejected pack keeps the user's saved code, unflagged); `profileChanges` StateFlow for a settings banner. Verified by me after merging main: core fmt/clippy x2 and 237 tests; Android play+direct assemble and 121 JVM tests, 0 failures. OPEN: settings banner in `android/app`; local profile codes (`AM_PHONETIC`) to be aligned with backend `transliteration_profiles` codes in A2.x. |
| 2026-11-17 | A1.7 | 🟩 DONE | 4e8e183, CI fix, merge eb0ab14 | `:app` onboarding: Android-free `OnboardingFlow` state machine (PRIVACY, ENABLE, SELECT, LANGUAGES, TUTORIAL; Continue blocked until the IME is enabled/selected; satisfied steps skipped; falls back if disabled), `LanguageDraft` (toggle with at least one enabled, reorder, first language, am/ti phonetic or Fidel per ADR 0007 U3), Compose screens with strings in resources and a placeholder privacy URL, `ImeStatusReader` (enabled list + `DEFAULT_INPUT_METHOD`), Skip on every step, "Run setup again" in settings. `:data` gains `onboarding_completed` (outside `KeyboardSettings`, never synced, not reset by Reset to defaults). Agent walked the full flow on an API 35 emulator after `pm clear`: privacy, enable in system settings, select via picker, languages edited, tutorial field typed ሰላም in `አማ · QWERTY`, settings persisted across force-stop and relaunch (15 screenshots). CI FIX (me): the Android job now also runs `:app:testPlayDebugUnitTest :app:testDirectDebugUnitTest` (plain `testDebugUnitTest` skipped the flavoured app module). Verified by me after merging main: play+direct assemble and 141 JVM tests incl. app flavours, 0 failures. NOT verified on device: Run setup again, Fidel option, first-language change, landscape/large font/TalkBack, privacy link, candidate tap. FOLLOW-UP: ADR 0007 D12 (step survives process death). OPEN: full privacy notice text and am/ti/om translations; tutorial tip wording. ENV NOTE: the earlier AVDs (`Tablet-10-inch`, `Pixel_Tablet_10.95_inch`) were found deleted during this step (cause unknown); agent created `A17Tablet`. An early `pm clear` reached the user's physical phone over wireless adb and failed harmlessly; ADR 0007 D13 now requires `adb -s emulator-NNNN`. |
| 2026-11-17 | B2.5 | 🟩 DONE | 18fd71f, merge 8391af6 | `devices` module (depends on identity, never the reverse): identity ports `DeviceRegistrar` (devices registers during OTP sign-in, so session row, `AuthSession.deviceId` and the access token `did` carry the device) and `DeviceSessions` (devices revokes families). `POST /devices` idempotent per user+installation (safe under concurrency), binds the caller's device-less family; `DELETE` revokes every bound family (`DEVICE_REVOKED`), drops the push token, outbox `devices.device.revoked`; foreign/unknown = 404; platform contradictions and `WEB` = 400; `X-App-Version` captured. Push token write-only, never logged, stored in V1 `push_token`. Verified: see B2.3. OPEN: no `GET /devices` in the contract (ADR 0007 D15 follow-up); event consumer with notifications (D16). |
| 2026-11-17 | B2.3 | 🟩 DONE | 280449c, merge 8391af6 | Attestation nonces: 256-bit, 5 min, SHA-256 only in new `attestation_nonces` (**V5 migration**, plus an installation-id index), optionally bound to caller and platform, consumed by one atomic UPDATE in its own transaction (replays and failures burn it), 120/h per IP. `PlayIntegrityVerifier`/`AppAttestVerifier` ports (VALID/REJECTED/UNAVAILABLE) with mock adapters only behind `QAL_ATTESTATION_MOCK` (refused under prod-like profiles); no adapter = every verdict UNAVAILABLE, so guests are refused (fail closed, `GuestFailClosedIT`). `/auth/guest`: platform check, per-IP 10/h and per-installation 5/h, attestation, then one transaction creating the GUEST user, attested device and session; every attestation failure is one 403 `GUEST_NOT_ALLOWED` (the done-when, tested explicitly). GUEST plan is the V1 seed and the effective plan without a subscription (SD 15.5), so no subscription row. `POST /devices/{id}/attestations` stores verdicts (UNSUPPORTED never overwrites VALID/FAILED). ADR 0008. Verified by me after merging main: 305 unit + 112 IT on JDK 21, 0 failures, 0 skipped; backend compiles on JDK 25 (Docker). Decisions: ADR 0007 D14 (create-only guest until attestation binds the installation). OPEN: rate limits in-memory per instance; nonce purge (B6.3); verdict `validUntil` not stored; real Play Integrity / App Attest adapters. |
| 2026-11-17 | B2.2 | 🟩 DONE | 7c2553b, 87d95ef, merge f268d76 | `identity.oauth`: provider-neutral `JwksIdTokenVerifier` (RS256 only; none/HS256/ES256 refused; `kid`-selected RSA keys), Google rules: `iss` accounts.google.com (both forms), `aud` in `QAL_GOOGLE_CLIENT_IDS` (none configured = sign-in off; prod-like profiles refuse to start unless explicitly disabled), exp/iat 60 s skew, `nonce` must match the request, `email_verified` before using the email. Nimbus JWKS cache 1 h, unknown-kid refresh (max 1 per 30 s), 6 h outage tolerance, 3 s timeout, 256 KiB, https only (loopback for tests). Every rejected token = generic 401; no keys at all = 503 `PROVIDER_UNAVAILABLE`. Accounts found/created by (GOOGLE, sub) under an advisory lock, **never linked by email** (SD silent; a matching email makes a separate account); stores sub, verified email on the identity row, display name for new accounts; no tokens stored or logged. Same status rules and device registration as OTP; 60/h per IP, 20/h per device. Unsupported providers (APPLE etc.) = 400 `PROVIDER_NOT_SUPPORTED`. ADR 0009. D15: `GET /devices` added to the contract (cursor pagination, revoked devices included with status, push token never returned). Tests use a fake JWKS on a loopback server. REVIEW FIX (me): `ProblemDetailsContractTest` was flaky (rejected value `abc` can appear inside a random correlation UUID). Verified by me: JDK 21 verify 332 unit + 128 IT, 0 failures after the fix (the one failure in my first run was that flake); agent's JDK 25 compile passed. Decision: ADR 0007 D17 (server-issued OAuth nonce before launch). OPEN: real Google client ids; Apple sign-in rules; guest merge on sign-in is B2.6. |
| 2026-11-18 | A1.8 | 🟩 DONE | 4d00377, 763fdfb (D12), merge c3eea0a | Personal dictionary: `WordLearner` learns a finished word (space/wordspace) in the language's script, plus the previous word as a bigram; never in password/email/URI/number/phone or `IME_FLAG_NO_PERSONALIZED_LEARNING` fields, never Latin in am/ti ABC, never digits/symbols/acronyms/single letters/>48 chars, never words being deleted. Explicit add = long-press a strip candidate; settings "Personal dictionary" lists (paged) with delete and confirmed clear-all. Storage: one file per language in `noBackupFilesDir/userdict/`, AES-256-GCM with a non-exportable Android Keystore key (`qal.userdict.v1`), fresh IV per write, file name as AAD, atomic replace; unreadable/tampered file starts empty (reason-only log); Keystore unusable = memory only, never plaintext on disk. Process-wide `UserDictionary` holds the canonical user lexicon/ranker per language and mirrors changes into the live engine. FFI: `import_user_data`, `add_user_word`, `learn_user_word`, `forget_user_word`, `clear_user_data` on the engine (user overlay kept across pack reloads), `user_words`/`forget_user_word` on `Lexicon`. `NoNetworkTest` scans `:data`/`:ime` sources and manifests. D12: onboarding step + language draft in `SavedStateHandle`. Verified by me after merging main: core fmt/clippy x2, 243 tests, CI bindgen smoke (all new functions generated); Android play+direct assemble and 190 JVM tests incl. a simulated restart (new `UserDictionary` + new engine over the same encrypted files, real core), 0 failures. NOT verified on device (the agent's AVD was gone; real Keystore path untested). Decision: ADR 0007 D18 (learn on second sighting). OPEN: picked candidates learned only after a following space; am/ti string translations. |
| 2026-11-18 | B3.1 | 🟩 DONE | 04926c7, V6 renumber, merge 836a313 | `languages` module: `GET /languages`, `/languages/{code}/transliteration-profiles` (ACTIVE+RETIRED with status, never DRAFT), `/language-resources` (+ detail), `/resource-signing-keys` (ACTIVE/RETIRING/REVOKED, public only). Served: PUBLISHED rows of active languages, signing key not REVOKED; platform SHARED+own; hidden when `min_app_version` > caller; channel STABLE default, BETA sees BETA+STABLE, INTERNAL needs a token AND `qal.languages.internal-channel-enabled`; partial rollouts only for authenticated callers by SHA-256(device or user + resource) bucket; keyset paging. `ArtifactUrlSigner` port: hand-written SigV4 presign (JDK crypto, checked byte-for-byte against the AWS example), path/virtual-host styles, bucket and key guards, 15 min TTL; `https://` CDN URIs pass through; fails closed in prod-like profiles. **V6 migration** (renumbered from V7 by me): `language_resources.container_size_bytes`, signing-key endorsement columns. No caching headers (contract defines none). Verified by me: 366 unit + 144 IT on JDK 21, 0 failures; migration ITs rerun after the renumber (clean build); agent's JDK 25 compile passed. Decisions: ADR 0007 D19 (BETA open), D20 (contract cleanup batch). OPEN: B3.2 must write `s3://` URIs, `container_size_bytes` and endorsements; not run against a real MinIO. |

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
| C1.4 | **Phonetic Latin→Ge'ez transliteration engine** driven by profile data (rules, longest-match, composing buffer, alternatives, backspace, protected tokens). Load AM and TI DRAFT profiles from `language-data/`. | C1.2 | Golden corpus per profile version: keystrokes → composing → committed → alternatives, 100% pass. | 🟩 DONE |
| C1.5 | Qubee (Afaan Oromoo Latin) and English handling; ABC passthrough mode. | C1.1 | Unit tests. | 🟩 DONE |
| C1.6 | **Lexicon**: compact trie/FST from wordlists, prefix lookup, user dictionary overlay. | C1.2 | Lookup meets §17.1 latency budget in benchmark. | 🟩 DONE |
| C1.7 | **Ranker**: candidate ranking, next-word suggestions, autocorrect with a "harm guard" (never auto-apply a correction that is low-confidence; suggestions only). | C1.6 | Tests incl. homophone families. | 🟩 DONE |
| C1.8 | **Code-switching / language ID** (cheap on-device detector) to pick candidate languages per word. | C1.6 | Eval set accuracy recorded. | 🟩 DONE |
| C1.9 | **Calendar utilities**: Ethiopian calendar, Ethiopian clock (6:00 offset), Ethiopic numerals ↔ Arabic numerals. | C1.1 | Property tests incl. leap years, Pagume. | 🟩 DONE |
| C1.10 | Property tests (segmenter never splits grapheme clusters; NFC idempotence; reversibility where declared) and CI benchmarks (>10% regression fails). | C1.3–C1.9 | CI gate active. | 🟩 DONE |

### 1B. Language data pipeline

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| L1.1 | `language-data/` build script: source rules + licensed wordlists → signed packs (`.pack` + manifest), keys from a dev keystore. | C1.2 | Pack builds reproducibly; signature verifies in core. | 🟩 DONE |
| L1.2 | Record licence per source in a data manifest (feeds `language_datasets`). Block build if licence field is missing or non-commercial (N10). | L1.1 | Build fails on an unlicensed source (test). | 🟩 DONE |
| L1.3 | Seed packs: AM, TI, OM, EN baseline (small), meeting size budgets from SD §17.1. | L1.1 | Packs in `android/ime/src/main/assets` and `ios/.../Resources`. | 🟩 DONE |

### 1C. Android IME + companion

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| A1.1 | Gradle project, flavours `play`/`direct`, UniFFI binding integration (`cargo-ndk`), ABI splits, min SDK per SD. | C1.1 | App installs; calls a core function. | 🟩 DONE |
| A1.2 | `InputMethodService` skeleton, keyboard surface (Compose or custom View — pick per SD §6 and record ADR), input-type handling (password, email, URI, number, multiline). | A1.1 | Types ASCII into any app. | 🟩 DONE |
| A1.3 | Wire composer + candidate strip; composing text with commit/replace; handle apps that reject composing text (fallback to direct commit). | A1.2, C1.4 | Phonetic AM typing works end to end in a test app. | 🟩 DONE |
| A1.4 | Layouts: Fidel grid, phonetic QWERTY, Qubee, English, ABC; language switching (long-press space / globe); number/symbol pages; Ethiopian punctuation. | A1.3 | All four languages typeable. | 🟩 DONE |
| A1.5 | Local settings store (DataStore): enabled languages, order, active transliteration profile, themes, haptics, key height. Typing language triggers per SD §7 (do **not** block reorder when a profile is retired — Review F14). | A1.4 | Persisted across restarts. | 🟩 DONE |
| A1.6 | Pack manager (bundled-only for now): load, verify signature, version-check. | C1.2, A1.1 | Corrupt pack ignored; falls back to bundled. | 🟩 DONE |
| A1.7 | Companion onboarding: enable IME, select IME, language selection, tutorial, privacy notice. | A1.5 | New-user flow tested on device. | 🟩 DONE |
| A1.8 | Offline user dictionary (add/learn/remove), local only for now. Encrypted at rest. | C1.6 | Learned words survive restart; no network. | 🟩 DONE |
| A1.9 | Performance + QA: reference-device budgets (§17.1: key latency, cold start, memory); lifecycle tests (rotation, split screen, foldables, low-memory kill). | A1.1–A1.8 | Budgets met on low-end device; 30+ testers sign-off (human gate G1). | ⬜ TODO |

**Exit M1:** golden corpus 100% pass; budgets met; native testers.

---

## Phase 2 — Backend foundation + P0 AI (milestone M2)

Goal: identity, devices, preferences, packs, AI gateway, translate/grammar/rewrite, metering for GUEST/FREE. Read SD §8–11, §14–15 and walk the API tags in order.

### 2A. Backend skeleton

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| B1.1 | Spring Boot 4 project with Spring Modulith packages from SD §24; `ApplicationModules.verify()` test in CI. | F0.2 | Module boundary test passes. | 🟩 DONE |
| B1.2 | Flyway with `V1__baseline.sql` copied unchanged from docs; never edit applied migrations — later changes are `V2__…`. | F0.3 | App boots; schema matches. | 🟩 DONE |
| B1.3 | Platform module: Problem Details error handling with API error codes, correlation ID filter, request validation, OpenAPI contract tests (every controller response validated against `contracts/openapi.yaml`), generated client types in CI. | B1.1 | Contract test suite green. | 🟩 DONE |
| B1.4 | Outbox + db-scheduler infrastructure (outbox table dispatcher, retry/backoff, idempotent consumers). | B1.2 | Test: event survives crash and is delivered once-effectively. | 🟩 DONE |
| B1.5 | Per-user envelope encryption service (KMS adapter: local dev KMS + cloud KMS interface), key-per-user, crypto-shred operation. | B1.2 | Round-trip + shred test. | 🟩 DONE |
| B1.6 | Configuration module: `/config` (feature flags, `minSupportedAppVersion`, remote config), `APP_VERSION_UNSUPPORTED` (426) enforcement. | B1.3 | Old version gets 426 Problem. | 🟩 DONE |

### 2B. Identity and devices

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| B2.1 | Phone OTP (SMS provider interface + mock, rate limits per phone/IP/device, anomaly counters). | B1.3 | Flow tested incl. lockout and replay. | 🟩 DONE |
| B2.2 | Google sign-in (ID token verification). | B1.3 | Test with fake JWKS. | 🟩 DONE |
| B2.3 | Attestation nonce endpoint + **guest accounts** gated by Play Integrity / App Attest verifier interfaces (mock now, real later). Guests get GUEST plan. | B2.1 | Guest without valid attestation gets `GUEST_NOT_ALLOWED`. | 🟩 DONE |
| B2.4 | Access JWT + **refresh-token rotation with families and reuse detection** (reuse revokes the family). | B2.1 | Replay test revokes family. | 🟩 DONE |
| B2.5 | Devices: register, list, revoke, push-token, platform/app-version capture. | B2.4 | API tests. | 🟩 DONE |
| B2.6 | Account linking (guest → phone/Google) preserving data and entitlements. | B2.3 | Test. | ⬜ TODO |

### 2C. Languages, packs, preferences

| ID | Step | Depends on | Done when | Status |
|---|---|---|---|---|
| B3.1 | Languages/profiles/packs read APIs and `/resource-signing-keys`; pack manifest + delta metadata; release channels. | B1.2 | Client can discover and download pack metadata. | 🟩 DONE |
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
