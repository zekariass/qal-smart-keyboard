# android/

Gradle project for the Qal Smart Keyboard Android app (one APK: IME service + companion UI).

Modules: `:app` (companion UI, flavours `play` / `direct`, ABI splits), `:core-bindings` (Rust core +
UniFFI Kotlin bindings, SD 6.2), `:ime`, `:data`, `:billing-play`, `:billing-direct`.
Versions live in `gradle/libs.versions.toml`; `qal.minSdk` and `qal.abis` in `gradle.properties`.

## Building the Rust core (A1.1)

`:core-bindings` builds `../core` for `arm64-v8a`, `armeabi-v7a` and `x86_64` with `cargo-ndk`
(task `cargoNdkBuild`), generates Kotlin with `uniffi-bindgen` in library mode from the compiled
`.so` (task `generateUniffiKotlin`), and packages both into the module. Nothing generated is
committed (`build/` is git-ignored).

One-time developer setup (Windows, macOS or Linux):

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
# Android SDK + NDK 27.1.12297006 (sdkmanager "ndk;27.1.12297006"); ANDROID_HOME must be set
# JDK 21 for Gradle (not JDK 25)
```

Then:

```sh
cd android
./gradlew :app:assemblePlayDebug :app:assembleDirectDebug   # per-ABI APKs + universal APK
./gradlew :core-bindings:testDebugUnitTest                  # JVM test: calls the host-built core via JNA
./gradlew :core-bindings:connectedDebugAndroidTest          # on a device/emulator
```

The first build compiles the core three times in release mode (LTO); allow several minutes.
The JVM unit test builds the core for the host (`cargoHostBuild`) and loads it with JNA.
