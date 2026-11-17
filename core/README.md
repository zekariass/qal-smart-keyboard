# core (qal-keyboard-core)

Offline Rust language engine for Qal Smart Keyboard, shared by Android and iOS through UniFFI
(proc-macro style) Kotlin/Swift bindings. Crate: `qal-keyboard-core`, lib `qal_keyboard_core`
(`lib`, `cdylib`, `staticlib`). Toolchain pinned in `rust-toolchain.toml` (Rust 1.91.0).

## Rule: never log or persist typed content
Plan rules N1/N2: the core is offline, performs no I/O, and must never log, print or persist typed
content, nor put it in error messages. Tests use synthetic text only.

## Module map
| Module | Responsibility | Plan step |
|---|---|---|
| `composer` | direct-Fidel key handling and layout (C1.3); phonetic composing buffer (C1.4) | C1.3, C1.4 |
| `translit` | data-driven Latin to Geez transliteration | C1.4, C1.5 |
| `segmenter` | grapheme-safe segmentation | C1.4, C1.10 |
| `lexicon` | trie/FST lexicon, user dictionary overlay | C1.6 |
| `ranker` | ranking, suggestions, autocorrect harm guard | C1.7 |
| `langid` | code-switching language ID | C1.8 |
| `calendar` | Ethiopian calendar, clock, numerals | C1.9 |
| `pack` | pack format, Ed25519 verification (N11) | C1.2 |
| `error` | `CoreError` | C1.1 |

## FFI API summary
- `KeyboardEngine` (object): `new(EngineConfig) -> Result<KeyboardEngine, CoreError>` (fails with
  `InvalidConfig` on a bad trusted key or limits), `load_pack(Vec<u8>) -> Result<PackInfo, CoreError>`,
  `loaded_packs() -> Vec<PackInfo>`, `set_input_mode(language_code, InputMode)`,
  `fidel_layout(language_code) -> Result<FidelLayout, CoreError>`,
  `translit_profile(language_code) -> Result<TranslitProfileInfo, CoreError>` (profile id, version,
  DRAFT or APPROVED, so hosts can honour N9),
  `on_key(KeyEvent) -> EditorUpdate`, `select_candidate(u32) -> EditorUpdate`,
  `reset() -> EditorUpdate`, `version() -> String`.
- Phonetic input (C1.4): load a signed `TRANSLITERATION_RULES` pack (artifact =
  `language-data/profiles/*-phonetic-draft.json`), then `set_input_mode(lang, PhoneticLatin)`. Every
  `on_key` returns the composing text and `candidates` (index 0 is the composing text, the rest are
  homophone alternatives, at most `max_candidates`); `select_candidate(i)` commits candidate `i`.
- `EngineConfig { max_candidates, trusted_keys: Vec<TrustedKey>, pack_limits: Option<PackLimits> }`.
- `TrustedKey { key_id, public_key (32 raw bytes), status: KeyStatus }`,
  `KeyStatus { Active, Retiring, Revoked }`,
  `PackLimits { max_container_bytes, max_manifest_bytes, max_artifact_bytes }` (`None` = defaults).
- `PackInfo { pack_id, language_code, resource_type: ResourceType, version, platform: PackPlatform,
  release_channel: ReleaseChannel, size_bytes, sha256, is_delta, key_id, min_app_version }`.
- `KeyEvent { Char { text }, FidelConsonant { series_id }, FidelVowel { order }, Backspace, Space,
  Enter, Commit }`.
- Direct Fidel records: `FidelLayout { language_code, layout_id, layout_version, status,
  long_press, two_key, default_order, rows: Vec<Vec<FidelKey>>, vowel_keys: Vec<FidelVowelKey>,
  punctuation: Vec<String>, digits: Vec<FidelDigit> }`, `FidelKey { series_id, label, tap_text,
  forms: Vec<FidelForm> }`, `FidelForm { order, label, text }`, `FidelVowelKey { order, label }`,
  `FidelDigit { digit, alternatives }`.
- Other types: `InputMode`, `EditorUpdate`, `Candidate`, `CandidateKind`,
  `ResourceType`, `PackPlatform`, `ReleaseChannel`, `PackRejectReason`.
- `CoreError`: `PackRejected { reason: PackRejectReason, detail }`, `UnsupportedLanguage`,
  `InvalidState`, `InvalidConfig`.

Current behaviour: Abc/English pass characters through. `set_input_mode` returns `InvalidState` for a
mode that does not fit the language (e.g. Qubee is only for `om`), that is not implemented yet
(PhoneticLatin, Qubee until C1.4/C1.5), or, for DirectFidel, when no `KEYBOARD_LAYOUT` pack is
loaded for the language ("no keyboard layout is loaded for am"). The host therefore keeps a
working mode instead of an engine that swallows keystrokes.

## Direct Fidel (C1.3)
Data-driven by a verified `KEYBOARD_LAYOUT` pack; format in
[`docs/specs/keyboard-layout-v1.md`](../docs/specs/keyboard-layout-v1.md). `load_pack` parses and
validates the layout after signature verification and rejects bad ones with
`PackRejectReason::InvalidArtifact`. The host draws `fidel_layout(lang)` and, in DirectFidel mode:
- **LONG_PRESS**: sends the chosen popup form (or punctuation / Ethiopic numeral) as
  `Char { text }`; the engine commits it as NFC. A tap on a key in a LONG_PRESS-only layout sends
  `Char { text: key.tap_text }`.
- **TWO_KEY**: `FidelConsonant { series_id }` starts a composition showing the default order
  (sixth by default): composing is the sixth-order form. `FidelVowel { order }` replaces it with
  that order and commits (composing empty). Order 8 is the labialised form. Another consonant
  commits the pending syllable and starts a new one; Space/Enter/Commit commit it (plus the space
  or newline); a `Char` commits it first, then the text. An order the series lacks keeps the
  composition so the user can retry.
- **Backspace** inside a composition cancels it (`composing` empty, nothing committed). Outside one
  it returns `delete_before: 1`: one grapheme cluster, so hosts must delete a whole cluster, never
  a UTF-16 code unit.
- `Char` text longer than 64 characters or containing control characters (other than newline and
  tab) is dropped (a pending composition is still committed). Nothing panics on any input.

The syllable table is generated from Unicode character names by
`language-data/scripts/gen_fidel_table.py` (see below); do not edit `src/composer/fidel_table.rs`.

## Language packs (C1.2)
Normative format: [`docs/specs/pack-format-v1.md`](../docs/specs/pack-format-v1.md). A pack is one
container: header (magic, format version, key id, Ed25519 signature), the manifest JSON and the
artifact. The signature covers `"QAL-PACK-MANIFEST-v1\0" || exact manifest bytes`; the manifest
commits to the artifact by SHA-256 and size. Bundled and CDN packs use the same file.

`load_pack` activates a pack only if it is authentic and intact:
- size limits first (defaults: 32 MiB artifact, 64 KiB manifest), bounds-checked framing;
- key id must be trusted and not revoked (`UnknownKey`, `RevokedKey`; `Retiring` keys still verify);
- strict Ed25519 (`verify_strict`: no malleable or small-order signatures) before any manifest
  field is read;
- manifest validation (resource type from the DB list, language tag, SemVer without build
  metadata, platform, channel, lowercase UUIDs, licence code), `signatureKeyId` = header key id,
  then artifact size and SHA-256.

Engine policy after verification:
- **Rollback protection** per (language, resource type, platform): a lower version, or the same
  version with a different artifact, is rejected with `Downgrade`; the identical pack again is an
  idempotent success. SemVer precedence (`1.10.0 > 1.9.0`, `1.0.0-rc.1 < 1.0.0`). State is per
  engine instance (the core does no I/O), so the host must persist installed versions; a
  last-known-good rollback means a new engine loading the previous file.
- **Delta packs** (`baseResourceId` set) are verified, then rejected with `DeltaNotSupported`: the
  host pack manager applies deltas and loads the resulting full, signed pack.
- The host enforces `minAppVersion`, release channel, platform and device requirements.

Errors carry a machine-readable `PackRejectReason` and a fixed `detail` string that never contains
pack content (N2). The `pack` module forbids `unsafe` and denies `unwrap`/`expect`/indexing/
unchecked arithmetic in non-test code.

Signing lives in `pack::builder` behind the non-default cargo feature `builder` (tests, fuzzing and
the L1.1 pack build tool). App builds never enable it. Tests get it through a dev-dependency on the
crate itself.

## Build and test
Docker (no local Rust needed), from the repo root:

```sh
docker run --rm -v "$PWD:/work" -v qal-cargo-registry:/usr/local/cargo/registry \
  -e CARGO_TARGET_DIR=/target -v qal-core-target:/target -w /work/core rust:1.91 bash -c \
  "rustup component add rustfmt clippy && cargo fmt --check && \
   cargo clippy --all-targets -- -D warnings && cargo test"
```

The repo root is mounted because the Fidel tests read `language-data/layouts/*.json` and
`language-data/generated/fidel-table-v1.json` (`include_str!`).

Local toolchain: `cd core && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test`.

## Generate bindings
See `bindings/README.md`. Generated output is not committed.

## Tests, vectors and fuzzing
- `tests/pack_verify.rs`: every rejection reason, every single-bit flip and every truncation of a
  pack, oversize inputs, wrong/revoked/unknown keys, manifest/artifact swaps, malleable signatures,
  rollback and delta policy.
- `tests/pack_proptest.rs`: `proptest` on arbitrary and mutated inputs (no panic, never accepted).
- `tests/vectors/pack-v1/*.json`: cross-language vectors for the backend signer (B3.2); checked by
  `tests/pack_vectors.rs`, regenerated with `QAL_REGEN_PACK_VECTORS=1 cargo test --test pack_vectors`.
- `tests/fidel_direct.rs`: layout packs signed with the test `builder`, mode switching without a
  layout, `fidel_layout`, malformed-layout rejection matrix, and the golden runner for
  `tests/golden/fidel-direct-0.1.0.json`. Unit tests in `src/composer/` cover table lookup and the
  TWO_KEY / LONG_PRESS state machine.
- Python (stdlib): `python -m unittest discover -s language-data/scripts -p "test_*.py"` fails if the
  generated Fidel table files are stale. Regenerate with `python language-data/scripts/gen_fidel_table.py`.
- `fuzz/`: `cargo-fuzz` targets (nightly, not part of the build or CI); see `fuzz/README.md`.

## Golden vectors
Format described in `tests/golden/README.md`. `fidel-direct-0.1.0.json` (C1.3) is the first file; the
phonetic profiles follow in C1.4.

## Property tests and benchmark gate (C1.10)
- `tests/properties.rs`: proptest for grapheme-safe segmentation (`src/segmenter.rs`, UAX #29 via
  `unicode-segmentation`), NFC idempotence of the lexicon, ranker and composers, and phonetic
  Backspace undoing the last key. (Ethiopic numeral round-trips: `src/calendar.rs`.)
- Benchmark gate: `python core/scripts/bench_gate.py` runs `cargo bench --bench lexicon|ranker`
  5 times, takes the minimum over runs of each label's **p50** (median latency per run; the minimum discards runs disturbed by other load), and fails if any is more than 10 %
  above `benches/baseline.json` AND more than 0.020 ms slower (noise floor), or if a bench exceeds
  its own absolute p95 budget. p50 is used because tail percentiles are too noisy on shared
  runners; the cost is that pure tail regressions are only caught by the absolute budgets, and
  labels with sub-0.02 ms medians can never trip the relative rule.
- Updating the baseline is deliberate: `python core/scripts/bench_gate.py --update`, review the diff
  of `benches/baseline.json`, and commit it with the reason in the message.
- The committed baseline was recorded on a Windows developer machine. Ubuntu CI runners differ, so
  until it is re-recorded the `core-bench` CI job may fail on a slower (or pass on a faster) runner.
  Re-record on the runner class: run the CI workflow via `workflow_dispatch` with
  `record_baseline` = true, download the `bench-baseline` artifact, commit it as
  `core/benches/baseline.json`. Do this once before relying on the gate, and again whenever the
  runner image changes.
