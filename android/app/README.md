# android/app

Application module (Compose companion UI). Assembles ime, data, billing-* libraries; play/direct flavours. Plan steps A1.1, A1.7, A2.x. Depends on `:sync` (A2.4).

## Onboarding (A1.7, package `onboarding`)
First launch shows a six-step flow (the account step is optional, A2.2); the result ("completed or skipped") is stored by `:data`
(`SettingsRepository.onboardingCompleted`, key `onboarding_completed`, not part of `KeyboardSettings`, so "Reset to defaults"
does not bring it back). It can be skipped at any step ("Skip setup" marks it done without saving the language draft) and
re-run from the settings screen ("Run setup again").

| Step | What it does |
|---|---|
| Privacy | Welcome and short notice (offline typing, no account, typed text stays on the device, no AI features in this version, settings stored locally) plus a link to the full notice. The URL is the placeholder `privacy_notice_url` in `strings.xml`; replace it before release. |
| Enable | Opens `Settings.ACTION_INPUT_METHOD_SETTINGS`. "Continue" is enabled once `InputMethodManager.enabledInputMethodList` contains our package. |
| Select | `showInputMethodPicker()`. Selected = `Settings.Secure.DEFAULT_INPUT_METHOD` starts with our package. |
| Languages | Enable/disable, reorder and "make first" for am, ti, om, en; am and ti choose Phonetic QWERTY (default, ADR 0007 U3) or Fidel grid. Saved through `SettingsRepository.update` when "Continue" is pressed; a language whose mode changed forgets its remembered layout so the keyboard opens in the chosen mode. |
| Account | Optional (typing needs no account, R-2): phone, Google or guest sign-in, or "Continue" to skip. See "Sign-in (A2.2)" below. |
| Tutorial | A text field to try the keyboard, plus tips that match `android/ime/README.md` (phonetic `selam`, Fidel two-key entry, globe tap/long-press, ABC key, long-press space, punctuation long-press, DRAFT tag). |

System state is re-read in `MainActivity.onResume` and `onWindowFocusChanged` (the picker is a dialog window), so the flow moves on by itself when the user comes back from system settings, and falls back if the keyboard was turned off again.

| File | Role |
|---|---|
| `OnboardingModel.kt` | Android-free state machine (`OnboardingFlow` step rules, `LanguageDraft`, `OnboardingModel` over `SettingsRepository`). JVM-tested. |
| `OnboardingViewModel.kt` | Thin `ViewModel` (survives rotation) and `ImeStatusReader` (system facts). |
| `OnboardingSaver.kt` | Keeps the step and the language draft in the `SavedStateHandle` (ADR 0007 D12), so the flow resumes the same step after process death (for example after enabling the keyboard in system settings). The IME status is re-read on resume; restoring is lenient (garbage falls back to defaults). |
| `OnboardingScreen.kt` | Compose UI: scrollable content with pinned Back/Continue, content descriptions, status as text (not colour only); works in portrait, landscape and large text. |

## Sign-in (A2.2, package `auth`)
Phone OTP, Google and "Continue as guest", plus "Sign in / Sign out" in settings (`AccountSection`) and the optional onboarding
step. Typing never needs any of it. All screens are Compose (`SignInScreen.kt`); the logic is Android-free and JVM-tested.

| File | Role |
|---|---|
| `SignInModel.kt` | State machine (`OPTIONS -> CODE -> DONE`): phone validation, OTP start/verify/resend, Google, guest; maps every `ApiError` to a `SignInMessage`. Suspend functions, so tests run it directly. |
| `AuthRepository.kt` | Calls the `:api` client: `startOtp`, `verifyOtp`, `signInWithGoogle`, `continueAsGuest`, `signOut`; stores the account kind (guest/registered) and the per-install id next to the tokens; `status()` flow for the UI. |
| `EncryptedTokenStore.kt` | The Keystore-backed `TokenStore` (below). |
| `PhoneInput.kt` | Normalises and validates numbers with the server's rules. |
| `AttestationProvider.kt`, `GoogleIdTokenSource.kt` | Seams for the two device-only parts (below). |
| `AuthGraph.kt`, `SignInViewModel.kt`, `SignInScreen.kt` | Wiring (process singleton), view model, Compose UI (`SignInPane`, `AccountSection`, `UpdateRequiredScreen`). |

**Token storage.** `EncryptedTokenStore` writes the access+refresh pair as one blob through `:data`'s `EncryptedFileStore`
(AES-256-GCM, fresh IV per write, file name as AAD, temp file + sync + rename) under `noBackupFilesDir/auth/` (excluded from
backups and device transfer). The AES key is a non-exportable Android Keystore key with its own alias `qal.auth.tokens.v1`
(separate from the dictionary key). A copy is cached in memory for OkHttp threads. A missing, tampered or undecryptable file
(key lost) is "signed out" and is deleted; a failed write keeps the pair in memory only and deletes the stale file (a restart
signs out rather than replaying a rotated refresh token); sign-out and ended sessions wipe memory and disk. Tokens are never
logged (`TokenPair.toString()` is redacted, `ApiError` carries status and code only). It lives in `:app` rather than `:data`
because `TokenStore` belongs to `:api` and `:data` must stay network-free (`NoNetworkTest`).

**Flows**
- *Phone OTP.* Input such as `0911 234 567`, `911234567` or `+251...` becomes E.164; the server rule is applied before any call
  (`^\+[1-9][0-9]{6,14}$`, and for +251 `^\+251[79][0-9]{8}$`, so landlines fail locally). `otp/start` sends the install id (device
  rate limit). The code screen shows the code length from the server, "attempts left" (5 per challenge, counted locally: the
  server does not return it), and a resend countdown from `resendAvailableAt` (device clock, clamped to 0-120 s so a skewed clock
  cannot lock the user out; 60 s when absent). `OTP_INVALID` clears the field and counts down; `OTP_EXPIRED` and
  `OTP_TOO_MANY_ATTEMPTS` (which also arrives as 429, the code wins) lock the challenge and enable "Send a new code" at once;
  `RATE_LIMITED` shows `Retry-After`; a 400 on `ageConfirmed` asks for the age checkbox (the code was not consumed);
  `ACCOUNT_SUSPENDED`, offline and timeout have their own text. No server `title` is ever shown.
- *Google.* Credential Manager (`androidx.credentials` 1.5.0 + `googleid` 1.1.1, `GetGoogleIdOption`, any account) with a fresh
  256-bit hex `nonce` per attempt, sent as `nonce` in `oauth/GOOGLE`. The web client id comes from `qal.googleWebClientId`
  (`local.properties` or `-P`); blank hides the button. Cancel shows nothing; 401 -> "not accepted"; 503 `PROVIDER_UNAVAILABLE` ->
  "temporarily unavailable".
- *Guest.* `POST /auth/attestation-nonces` -> `AttestationProvider.attest(nonce)` -> `POST /auth/guest`. The server answers every
  failure with 403 `GUEST_NOT_ALLOWED` and never says why, so there is one message. If the provider cannot attest, no
  `/auth/guest` call is made. See "Attestation providers".
- *Guest linking (B2.6).* While a guest session exists the bearer interceptor already sends the guest token on `otp/start`,
  `otp/verify` and `oauth/GOOGLE`, so the server links it. `mergedGuestUserId` == `user.id` is shown as "your guest data was kept:
  this guest session is now your account", a different id as "...added to your existing account". If the server answers 401
  `GUEST_SESSION_INVALID` the stale guest token is discarded (the authenticator clears it) and the same call is retried once
  without it, as the contract prescribes. "Continue as guest" is hidden while a guest session exists.
- *Sign-out and ended sessions.* "Sign out" calls `/auth/logout` (best effort) and always wipes the tokens locally.
  `SessionManager.state` drives the UI: after `REFRESH_TOKEN_REUSED` and the like the account card says the session ended and
  offers "Sign in". 426 (seen by any call, through the transport's `CallLogger`) shows the update-required screen
  (`AuthGraph.updateRequired`; "Not now" returns to the app).

**Attestation providers.** `AttestationProvider` is chosen per flavour in `AttestationProviders.create` (flavour source sets):
`MockAttestationProvider` (debug builds with `qal.mockAttestation` true, the default for debug) returns
`mock-play-integrity.v1.<nonce>.valid`, which only a backend running the mock verifier accepts; `PlayIntegrityAttestationProvider`
(flavour `play`, `com.google.android.play:integrity` 1.4.0, standard request over the server nonce, needs the Google Cloud project
number in `qal.playIntegrityProjectNumber`) is **not testable on the JVM**; flavour `direct` has no Play Store, so it offers no
guest sessions unless the mock is on.

**Build configuration** (`local.properties` or `-P`, never committed): `qal.apiBaseUrl` (debug default `http://10.0.2.2:8080`, the
emulator's view of the host; release default is a placeholder `https://` host), `qal.googleWebClientId`,
`qal.playIntegrityProjectNumber`, `qal.mockAttestation`. `INTERNET` is declared in this module only (`:ime` stays network-free).
Core library desugaring is on (D28) for the generated client's `java.time` types.

## Personal dictionary (A1.8)
`PersonalDictionarySection` (bottom of the settings screen): per language the number of words, "Show words" (paged, 50 at a time) with a Delete button per word, and "Clear all" behind a confirmation. It uses the same process-wide `UserDictionary` as the keyboard (`android/ime/README.md`), so a deletion also reaches a running keyboard and is saved encrypted. Words appear on screen only; nothing is logged or uploaded.

All user-visible text is in `res/values/strings.xml` (English; add `values-am`, `values-ti`, `values-om` later).

## Language-pack updates (A2.3, package `packs`)
`PackUpdateWorker` (WorkManager `CoroutineWorker`) runs `:packs-sync`'s `PackUpdater` for the enabled languages, the channel from
the settings (`packChannel`, default STABLE) and this app version, over the `AuthGraph` API client (signed-in callers see staged
rollouts). `PackUpdateScheduler` queues it every 12 h and once at app start (`MainActivity`): network UNMETERED (Wi-Fi) by default,
CONNECTED when the settings switch "Update language packs on mobile data" is on (`packUpdatesOnMobileData`, default off), battery
not low, storage not low. Changing the switch re-queues the check. Transient failures retry with exponential backoff (max 3
attempts per run). `:ime` has no network code; the updater and the IME talk only through files (`android/packs-sync/README.md`,
`android/ime/README.md`). Nothing but file names, resource ids and reason codes is logged. Needs a device: the real WorkManager
scheduling and constraints.

## Tests
`./gradlew :app:testPlayDebugUnitTest :app:testDirectDebugUnitTest` (from `android/`; `testDebugUnitTest` skips the flavoured app tests): step rules, IME status parsing, language draft rules and the whole new-user
flow over a real DataStore on a temp file (including restart persistence of the completed flag and the language choice).

A2.2 tests (`auth/`): `SignInModelTest` drives the real `:api` client, the encrypted token store (injected key) and OkHttp
`MockWebServer` through the OTP flow (cooldown, attempts, expired/locked/rate-limited, age confirmation, offline, 426), Google
(nonce, cancel, rejected, provider down), guest (server nonce, mock token, `GUEST_NOT_ALLOWED`, unavailable attestation), guest ->
OTP/Google upgrade and merge, stale guest token retry, sign-out on `REFRESH_TOKEN_REUSED`, and logout; `EncryptedTokenStoreTest`
(round trip across restarts, no plaintext, tamper, wrong key, failed write, wipe); `PhoneInputTest`; `SignInPaneTest` (Compose on
Robolectric). **Needs a device or emulator later:** the Credential Manager account chooser and a real Google ID token, a real Play
Integrity token, the Android Keystore itself on real hardware (StrongBox/TEE behaviour, key loss after restore), SMS delivery, and
a visual check of the screens.

## Sync (A2.4, package `sync`; the engine is `android/sync/README.md`)
| File | Role |
|---|---|
| `SyncGraph.kt` | Process singleton: `SyncEngine` over the shared API client, `SettingsRepository` and the process-wide `UserDictionary` (through `UserDictionaryPort`; remote words are explicit adds, ADR 0007 D18). State and destination profiles are encrypted under `noBackupFilesDir/sync` with their own Keystore alias `qal.sync.v1`. |
| `SyncScheduler.kt`, `SyncWorker.kt` | WorkManager: periodic every 12 h on an unmetered network; `requestSoon` (unique, REPLACE with a 5 s initial delay = debounce) on any connected network for the event-driven runs. The worker loads the dictionary off the main thread, tells the engine whether the account is a guest and retries (at most 3) only transient failures. |
| `DictionarySyncSection.kt` | Settings card with the `DICTIONARY_SYNC` switch (`PUT /me/consents/DICTIONARY_SYNC`, surface `settings`); registered accounts only (guests see why it is off); adopts the server's decision on a new device. |

Triggers (`MainActivity`): app start and every session change to signed-in (first emission), a sign-in finishing (also guest -> account: the rule key may change, `SyncEngine.signedIn()`), a change of
languages/numerals/theme/haptics (2 s debounce, then the worker's 5 s), and the periodic job. A transition from signed-in to signed-out wipes the account's sync state and local profiles/rules (`accountChanged()`).
The destination-profile/app-rule editor is deferred (see `android/sync/README.md`).

## Toolbar deep links and background refresh (A2.5, ADR 0007 D40; packages `link`, `ai`)
The keyboard's toolbar link buttons start the launcher activity with `EXTRA_OPEN_LINK` = `AiLink.name` (`CLEAR_TOP`;
`MainActivity` reads it in `onCreate` and `onNewIntent`). `link/DeepLinks` (pure, unit-tested) maps it to an action:

| `AiLink` | Action |
|---|---|
| `SIGN_IN` | the sign-in pane (settings when already signed in with an account) |
| `AI_SETTINGS` | the settings screen |
| `UPGRADE` | `PlansScreen`, a **placeholder** (text and Back) until the billing/plans UI exists |
| `UPDATE_APP` | the store page (`openStorePage`), also during first-run setup |

During first-run setup every link except `UPDATE_APP` is ignored (the setup has its own account step). An unknown
extra does nothing. The link is applied once the stored onboarding flag has loaded.

Plan state and the entitlement token also refresh from `SyncWorker` (`AiGraph.actions.refreshNow()` after the sync
run; throttled and a no-op when signed out), so the keyboard never needs to fetch them when it appears. `AiGraph`
passes a `LastDestinationStore` (device-local last destination per app, see `android/ai-client/README.md`).
Tests: `link/DeepLinksTest`.
