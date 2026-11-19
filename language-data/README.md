# language-data

Source rules, licensed wordlists, eval sets and build scripts that produce signed packs. Every source needs a commercial-use licence entry. Plan steps L1.x, F0.6.

See docs/Ethiopian_AI_Keyboard_Implementation_Plan_v2_0.md for the steps that populate this folder.

## Fidel (Ethiopic) data (C1.3)
- `scripts/gen_fidel_table.py` generates `generated/fidel-table-v1.json` and
  `core/src/composer/fidel_table.rs` from Unicode character names (`unicodedata.name()`), never from
  code point arithmetic. `--check` exits 1 when a file is stale; `scripts/test_gen_fidel_table.py`
  runs that check. Needs Python with Unicode 14+ data (3.11 has it).
- `layouts/am-fidel-draft.json`, `layouts/ti-fidel-draft.json`: source `KEYBOARD_LAYOUT` artifacts
  (format: `docs/specs/keyboard-layout-v1.md`), status DRAFT pending native-speaker review (G0).
  The L1.1 pack build tool wraps them into signed packs.

## Pack build (L1.1)
- `scripts/build_packs.py`: `sources/*.source.json` -> signed `.pack` + `.manifest.json` (the exact
  signed manifest bytes) in `build/packs/` (gitignored). Stdlib only. Container format:
  `docs/specs/pack-format-v1.md`. Reproducible: same sources + same key give byte-identical packs
  (`--check-reproducible`). `scripts/ed25519_ref.py` is a pure-Python RFC 8032 signer, independent
  of the Rust one; `scripts/test_build_packs.py` checks it against RFC 8032 and the core's test
  vector byte for byte.
- Verify in core: `cd core && cargo run --bin qal-pack-verify -- <key_id> <public_key_hex> <packs>`
  loads each pack into a fresh engine (signature, manifest, SHA-256, layout validation).
- **Keys are dev only.** `build_packs.py --init-dev-key` creates a random Ed25519 key in
  `keys/dev/` (gitignored, never committed; there is deliberately no committed key, ADR 0002 only
  allows fixed public seeds in core tests). Key ids are `qal-dev-<8 hex>`; the script refuses a key
  not marked `devOnly`, and refuses to sign `STABLE` packs. Production keys live in KMS/HSM and are
  used only by the backend publisher (B3.2). Pin the dev public key in a debug build only.
  Reproducibility is per key: a fresh checkout has a different dev key, hence different bytes.
- Source manifest: see the docstring of `build_packs.py`. Each source lists `inputs[]` with a
  `licenseCode` per raw input.
- **Licence gate (L1.2, N10):** the pack and every input licence must be listed with
  `commercialUse: true` in `licenses.json` (the allow-list; unknown codes fail closed, `-NC` codes
  and missing fields fail, `inputs[]` is mandatory); the build aborts before writing anything.
  Each build also emits `<out>/datasets.manifest.json` (sorted, no timestamps, deterministic), one
  record per input shaped to `language_datasets` (`datasetCode`, `licenseCode`, `permittedUses`,
  `consentBasis`, `reviewStatus: PENDING_REVIEW`, ...) plus `sha256`, `path`, `attribution`, `packs`.
  Adding a licence to the allow-list needs legal review (OI-R7). Only `artifact.kind: "file"`
  exists; wordlist -> lexicon artifacts arrive with C1.6, rule artifacts with C1.4.

## Seed packs (L1.3)
- `scripts/build_seed_packs.py` builds the AM/TI/OM/EN baseline packs (DICTIONARY "QLX1", FREQUENCY_LIST
  "QBG1" bigrams, one shared LANGUAGE_ID_MODEL "QLID" v1, plus the existing AM/TI layouts and phonetic
  profiles) from `seed/` using the core's own builders (`cargo run --bin qal-artifact-build`), signs them
  with the dev key and copies them with an index (`seed-packs.json`) to `android/ime/src/main/assets/packs/`
  and `ios/KeyboardExtension/Resources/packs/`. `--check` needs no key (payloads fresh, shipped files match
  the index, budgets met). Budgets: `seed/budgets.json` (SD 17.1: 15 MB Ethiopic, 25 MB English per language).
- **Everything is DRAFT / UNVERIFIED (N9, `releaseChannel` INTERNAL, index `userExposable: false`).** Word lists,
  bigrams and language-ID seeds are tiny, hand-written by the engineering team (`LicenseRef-Qal-Original`),
  derived from `eval/langid/train_*.txt`, with placeholder frequencies. Amharic, Tigrinya and Oromo content
  needs native-speaker review (human gate G0) and a real licensed corpus before any release.
- Layouts and phonetic profiles are packed as their JSON text with LF line ends, whatever the checkout uses
  (a Windows checkout with `core.autocrlf` has CRLF files), and `--check` fails when a shipped layout or profile
  pack differs from its current source (a rule change without a pack rebuild).
- The shipped packs are signed with a dev key (public key recorded in the index); a fresh checkout has a
  different dev key, so rebuilding changes the pack bytes. Release packs are signed by the backend (B3.2).
- OM and EN have no layout pack yet: only `am`/`ti` KEYBOARD_LAYOUT artifacts exist (the `orthography/`
  files describe Latin letter sets, not layouts).
