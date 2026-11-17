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
- `KeyboardEngine` (object): `new(EngineConfig)`, `load_pack(Vec<u8>) -> PackInfo`,
  `set_input_mode(language_code, InputMode)`, `on_key(KeyEvent) -> EditorUpdate`,
  `select_candidate(u32) -> EditorUpdate`, `reset() -> EditorUpdate`, `version() -> String`.
- Types: `EngineConfig`, `InputMode`, `KeyEvent`, `EditorUpdate`, `Candidate`, `CandidateKind`,
  `PackInfo`, `CoreError` (`PackRejected`, `UnsupportedLanguage`, `InvalidState`).

Current behaviour: Abc/English pass characters through. `set_input_mode` returns `InvalidState` for a
mode that does not fit the language (e.g. Qubee is only for `om`) or that is not implemented yet
(DirectFidel, PhoneticLatin, Qubee until C1.3–C1.5), so the host keeps a working mode instead of an
engine that swallows keystrokes. `load_pack` rejects everything until C1.2.

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

## Golden vectors
Format described in `tests/golden/README.md` (C1.4).
