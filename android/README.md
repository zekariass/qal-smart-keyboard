# android/

Gradle project for the Qal Smart Keyboard Android app (one APK: IME service + companion UI).

Modules: `:app` (companion UI, flavours `play` / `direct`, ABI splits), `:core-bindings` (Rust core +
UniFFI Kotlin bindings, SD 6.2), `:ime`, `:data`, `:api` (generated API client, interceptors, refresh authenticator; plain JVM, see
`api/README.md`), `:packs-sync` (language-pack updater, A2.3; `packs-sync/README.md`), `:sync` (settings, destination-profile, app-rule and dictionary sync, A2.4; `sync/README.md`), `:ai-client` (the keyboard's AI toolbar network side, A2.5; `ai-client/README.md`), `:billing-play`, `:billing-direct`.
Versions live in `gradle/libs.versions.toml`; `qal.minSdk` and `qal.abis` in `gradle.properties`.

## Pointing a build at the server: emulator vs physical phone

The debug API base URL defaults to `http://10.0.2.2:8080`, the **emulator's** alias for the development machine. A
physical phone cannot reach `10.0.2.2`: with that build every AI call fails ("Can't reach the Qal server"; before ADR
0040 they hung until "That took too long"). A debug build logs a warning (`QalApi`, no user content) when it runs on a
phone with that address (`app/.../ai/BaseUrlGuard.kt`).

For a phone on USB, build with `localhost` and forward the phone's port 8080 to the server's port on the computer:

```sh
cd android
./gradlew :app:assembleDirectDebug -Pqal.apiBaseUrl=http://localhost:8080
adb install -r app/build/outputs/apk/direct/debug/<the universal debug APK>
adb reverse tcp:8080 tcp:8081   # phone 8080 -> computer 8081 (the local dev server's port; use 8080 if it listens there)
```

`adb reverse` lasts until the phone disconnects; run it again after replugging. Plain `http` is allowed only for
`localhost`, `127.0.0.1` and `10.0.2.2` (`TransportConfig.LOCAL_HOSTS`). Never put API keys in the app: provider keys
live on the server only.

AI calls connect with a short timeout (5 s, `AiTimeouts.CONNECT_SECONDS` / `TransportConfig.DEFAULT_CONNECT_TIMEOUT_MS`)
separate from the per-task call timeouts (`AiTimeouts`): an unreachable server is reported at once as "Can't reach the
Qal server" (`AiFailure.Unreachable`), and "That took too long" is kept for a server that accepted the request but
answered too slowly.

## Crash reporting: Sentry (ADR 0024, ADR 0007 D47)

`:app` depends on `io.sentry:sentry-android-core` 8.62.0 (no Sentry Gradle plugin: nothing is uploaded at build time).
Sentry starts only when the build has a DSN **and** the privacy dashboard switch "Send crash reports — no typed text, ever"
is on (`KeyboardSettings.crashReports`, default on). Without a DSN (the default: local, CI, tests) it never starts.

```sh
./gradlew :app:assemblePlayRelease -Pqal.sentryDsn=https://<key>@<org>.ingest.sentry.io/<project>
# or qal.sentryDsn=... in android/local.properties, or env QAL_SENTRY_DSN_ANDROID
```

One init in `QalApplication` covers the companion app and the keyboard (same process); `:ime` stays network-free. No
screenshots, view hierarchy, replay, tap/system/network breadcrumbs, tracing or logcat; exception messages are replaced by
`[redacted]` (`app/.../crash/CrashScrubber.kt`). Details and the full list: `docs/adr/0024-sentry-crash-reporting.md`.

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
