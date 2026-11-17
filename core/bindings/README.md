# Bindings

Generated Kotlin and Swift bindings are NOT committed. Generate them from the built library:

```sh
cd core
cargo build --release
# Linux library name; use libqal_keyboard_core.dylib on macOS, qal_keyboard_core.dll on Windows
cargo run --features cli --bin uniffi-bindgen -- generate \
  --library target/release/libqal_keyboard_core.so --language kotlin --out-dir bindings/out/kotlin
cargo run --features cli --bin uniffi-bindgen -- generate \
  --library target/release/libqal_keyboard_core.so --language swift --out-dir bindings/out/swift
```

`bindings/out/` is git-ignored. Android (step A1.1, via `cargo-ndk`) and iOS (step I5.1,
XCFramework) will wire generation into their own builds.
