# android/data

Local data for the keyboard: settings (A1.5). The API client, pack manager and sync land here in A1.6 and A2.x.

## Settings store (A1.5)
Preferences DataStore (`keyboard_settings`), wrapped by `SettingsRepository`. Preferences rather than Proto DataStore: the model is small and flat, Proto needs the protobuf Gradle plugin and generated code, and a hand-written codec lets us validate and migrate in plain Kotlin. Every read is decoded leniently (unknown or missing values take their default) and every write goes through `KeyboardSettings.sanitized()`.

| File | Role |
|---|---|
| `KeyboardSettings.kt` | Typed model (`KeyboardSettings`, `TypingLanguageSetting`, enums), `LanguageRules`, validation (`sanitized`) and `toSyncItems()`. |
| `ProfileResolver.kt` | F14 fallback (`ProfileCatalog`, `ProfileRef`, `ResolvedSettings`). |
| `SettingsRepository.kt` | DataStore codec, `settings` flow, `update`, reorder/enable helpers, `applyProfileFallback`; `SettingsSnapshot` = cached non-blocking view for the IME. |

### Model and the contract
`KeyboardSettings.languages` is the user's ordered list of all known languages (am, ti, om, en), enabled or not. Each `TypingLanguageSetting` maps to the contract's `TypingLanguage` (`/me/typing-languages`, SD 10.2): `languageCode`, `enabled`, `inputMode` (`DIRECT_SCRIPT_LAYOUT | PHONETIC_LATIN_TO_NATIVE | LATIN_PASSTHROUGH`), `transliterationProfileCode` (null = newest active profile), `conversionTrigger`, `showTransliterationCandidates`, `unknownTokenBehavior`. `sortOrder` is the list position (10, 20, ...) and `isPrimary` the first enabled entry; `toSyncItems()` produces exactly that shape. `numeralStyle` and `hapticsEnabled` map to the preferences document; `theme` to `keyboardTheme`. Local only: `keyHeightPercent` (80..130) and `lastModes` (last native layout per language). The sync `revision` is not stored yet (A2.x).

Defaults (ADR 0007): am, ti, om, en all enabled in that order; am and ti start on phonetic QWERTY (Fidel is one globe long-press away); numerals WESTERN; theme SYSTEM; haptics on; key height 100%.

Validation: at least one language enabled; unknown or duplicate languages dropped; missing known languages appended disabled; an input mode the language cannot use resets to its default; profile codes kept only in phonetic mode and only when well formed; key height clamped. Schema versioned by `schema_version` (1).

### Onboarding flag (A1.7)
`SettingsRepository.onboardingCompleted` / `setOnboardingCompleted` store whether the companion onboarding was completed or skipped (preferences key `onboarding_completed`). It is deliberately not part of `KeyboardSettings`: it is not a preference, is never synced, and "Reset to defaults" must not restart the welcome flow. It is an additive key, so `schema_version` stays 1; a missing key reads as `false`.

### F14
`ProfileResolver.resolve` replaces a profile code that is not among the active profiles of its language with the newest active one (or null if none) and reports a `ProfileChange`; `SettingsRepository.applyProfileFallback` persists it. Order and enabled flags are never touched and reordering never looks at profiles, so a retired profile cannot block the user. If the catalog does not know the language yet (packs loading) nothing changes. The catalog comes from the loaded packs (A1.6 seam): `SettingsRepository.applyInstalledProfiles(profiles)` publishes them to `InstalledProfileCatalog` (unknown, so no change, until then), applies the fallback and reports the changes to `SettingsRepository.profileChanges` (`ProfileChangeNotice`: a `StateFlow` of unacknowledged `ProfileChange`s plus `profileChanged` and `acknowledge()`). The IME calls it after the pack manager loads (off the main thread). A language with loaded packs but no profile of its own stays unknown to the catalog: its saved code is left unchanged and not flagged (a transient pack failure must not erase a user choice); the normal fallback applies once a later load has its profile. The "profile changed" banner on the settings screen is a follow-up in `android/app`.

## Encrypted local files (A1.8, package `secure`)
`EncryptedFileStore` keeps named blobs encrypted at rest (`BlobCipher`: `AesGcmCipher`, AES-256-GCM, random IV per write, file name as AAD) and writes them atomically; a missing, unreadable, tampered or wrong-key file reads as `Missing` / `Corrupt(reason)` and never throws. `KeystoreKeys.aesGcm(alias)` supplies the Android Keystore key (non-exportable, created on first use); JVM tests inject a plain `SecretKey`. First user: the personal dictionary in `:ime`. No network API is used here (`NoNetworkTest`).

## Tests
`./gradlew :data:testDebugUnitTest`. DataStore runs on the JVM over a temp file; "restart" = a new DataStore instance on the same file. The tests use `OkioStorage` because DataStore's File-based storage cannot replace an existing file on Windows (CI is Linux; production is Android).
