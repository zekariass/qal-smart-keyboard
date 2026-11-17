# Fuzzing the pack parser

`cargo-fuzz` (libFuzzer) targets for `qal_keyboard_core::pack` (plan step C1.2). This is a
standalone crate with its own `[workspace]`, so the main crate, app builds and stable CI never
build it. It needs a nightly toolchain; the crate's `rust-toolchain.toml` (1.91.0) is overridden
with `+nightly`.

| Target | Input | Covers |
|---|---|---|
| `pack_container` | Arbitrary bytes as a container | Size limits, framing, key lookup, strict signature check. Must never panic or accept a forged pack. |
| `pack_signed_manifest` | `[key selector u8][manifest_len u16 BE][manifest][artifact]`, signed on the fly with a trusted test key | Everything after the signature: UTF-8, JSON, field validation, key binding, size, SHA-256. |

Keys are the test keys from fixed public seeds used by `core/tests` (never trusted in production).

## Run (Docker, no local Rust)

From the repository root:

```sh
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/core:/work" \
  -v qal-cargo-registry:/usr/local/cargo/registry -v qal-fuzz-target:/ft -w /work/fuzz rust:1.91 bash -c '
  rustup toolchain install nightly --profile minimal &&
  cargo install cargo-fuzz --locked &&
  python3 -I seed_corpus.py &&
  cargo +nightly fuzz run pack_container       --target-dir /ft -- -max_total_time=60 -max_len=4096 &&
  cargo +nightly fuzz run pack_signed_manifest --target-dir /ft -- -max_total_time=60 -max_len=4096'
```

`seed_corpus.py` seeds `corpus/<target>/` from `../tests/vectors/pack-v1/*.json`. `corpus/`,
`artifacts/` and `target/` are git-ignored. A crash leaves a reproducer in
`artifacts/<target>/`; replay it with `cargo +nightly fuzz run <target> <file>` and add it as a
regression test in `core/tests/pack_verify.rs`.
