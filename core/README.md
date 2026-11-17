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
| `composer` | composing buffer state machine | C1.3, C1.4 |
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
  `on_key(KeyEvent) -> EditorUpdate`, `select_candidate(u32) -> EditorUpdate`,
  `reset() -> EditorUpdate`, `version() -> String`.
- `EngineConfig { max_candidates, trusted_keys: Vec<TrustedKey>, pack_limits: Option<PackLimits> }`.
- `TrustedKey { key_id, public_key (32 raw bytes), status: KeyStatus }`,
  `KeyStatus { Active, Retiring, Revoked }`,
  `PackLimits { max_container_bytes, max_manifest_bytes, max_artifact_bytes }` (`None` = defaults).
- `PackInfo { pack_id, language_code, resource_type: ResourceType, version, platform: PackPlatform,
  release_channel: ReleaseChannel, size_bytes, sha256, is_delta, key_id, min_app_version }`.
- Other types: `InputMode`, `KeyEvent`, `EditorUpdate`, `Candidate`, `CandidateKind`,
  `ResourceType`, `PackPlatform`, `ReleaseChannel`, `PackRejectReason`.
- `CoreError`: `PackRejected { reason: PackRejectReason, detail }`, `UnsupportedLanguage`,
  `InvalidState`, `InvalidConfig`.

Current behaviour: Abc/English pass characters through. `set_input_mode` returns `InvalidState` for a
mode that does not fit the language (e.g. Qubee is only for `om`) or that is not implemented yet
(DirectFidel, PhoneticLatin, Qubee until C1.3–C1.5), so the host keeps a working mode instead of an
engine that swallows keystrokes.

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
docker run --rm -v "$PWD/core:/work" -v qal-cargo-registry:/usr/local/cargo/registry \
  -e CARGO_TARGET_DIR=/target -v qal-core-target:/target -w /work rust:1.91 bash -c \
  "rustup component add rustfmt clippy && cargo fmt --check && \
   cargo clippy --all-targets -- -D warnings && cargo test"
```

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
- `fuzz/`: `cargo-fuzz` targets (nightly, not part of the build or CI); see `fuzz/README.md`.

## Golden vectors
Format described in `tests/golden/README.md` (C1.4).
