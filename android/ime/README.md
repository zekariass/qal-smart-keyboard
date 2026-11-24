# android/ime

Android `InputMethodService` and keyboard UI (plan steps A1.x). Design: SD §6.2, ADR 0003 (custom
`View` surface), ADR 0004 (candidate merge policy, composing-text fallback).

## Structure
| File | Role |
|---|---|
| `KeyboardImeService` | IME lifecycle: loads the core + bundled packs off the main thread, `onStartInput` / `onUpdateSelection` / `onFinishInput*`, builds the input view (candidate strip above the key surface). |
| `KeyboardController` | Pure state machine: page, shift, language, layout, candidates, switching. Every key goes through a `Composer`; updates go to a `TextSink`. JVM-tested. |
| `Composer`, `CoreComposer`, `PassthroughComposer` | The engine port. `CoreComposer` wraps the Rust `KeyboardEngine`; `PassthroughComposer` types ABC until the core is loaded. |
| `InputConnectionAdapter` (+ `EditorOps`) | Applies `EditorUpdate`s in one batch edit, grapheme-cluster deletion, own-edit tracking, external cursor moves, direct-commit fallback (ADR 0004). JVM-tested with a fake editor. |
| `KeyboardBar`, `CandidateStripView` | The **one bar** above the keys (44 dp, shown in every field so the keyboard keeps one height; ADR 0035). `CandidateStripView` draws, left to right: the tools button, the writing-mode pill (am/ti), the middle, the microphone. The middle holds the pinned shortcuts (Translate, the "AI" mark, Clipboard, ...; the "AI" mark opens the AI tools grid `AiToolsPanelView` in place of the keys, ADR 0041; when the icons do not all fit the middle scrolls sideways on a drag, nothing drops off). While composing, the bar becomes the suggestion bar: a back chevron at the left end returns to the tools view, then the candidates — every word whole in a natural-width cell, scrolling sideways on a drag when they overflow — with the DRAFT tag while the profile is DRAFT (N9; never in password fields). While a tool panel is open the bar shows a back arrow and its title. All drawn elements are `ExploreByTouchHelper` virtual views. |
| `KeyboardInsets`, `KeyboardMetrics`, `KeyboardColumn` | Navigation-bar / cutout padding of the input view and responsive row heights (pure, JVM-tested); the column paints the keyboard background under the padding. |
| `PhoneticHints` | The native character each QWERTY key produces alone in Phonetic mode (secondary key labels), derived from the engine. |
| `KeyboardSurface` | Custom-View key surface: draws keys, hit-tests, long-press popups (drawn by `PopupOverlay`, selection by slide-and-release), globe long-press. Keys with a popup or long action act on release. |
| `PopupOverlay`, `OverlayStack` | The popup is drawn inside the input view, in a transparent non-touchable 190dp headroom above the strip (`onComputeInsets` keeps it out of the app layout and touch region). A `PopupWindow` was tried first: it is clipped to the IME window on API 35 and never appeared. |
| `KeyboardLayout` (`Layouts`) | Layout model + builders: Fidel grid (from core data), phonetic/English/ABC QWERTY, Qubee (hudhaa key on the main page), symbol pages, number/phone pads. |
| `TypingMode` (`LayoutKind`, `LanguageCatalog`, `EnabledLanguages`, `FidelGrid`) | Language + layout model, UI names, the enabled-language interface and the Kotlin copy of the core's `FidelLayout`. |
| `InputProfile` | `EditorInfo` → field kind, learning, Enter action, caps, `allowsTransliteration`, `learnsWords`. |
| `packs/` (`PackManager`, `PackStorage`, `VersionStore`, `PackTrust`, `SemVer`) | Pack manager (A1.6, see below). Replaces the temporary `BundledPacks`. |
| `SettingsPreferences` | Adapter from `:data`'s cached settings snapshot to `EnabledLanguages` + `ModePreferences` (preferred/last layout, candidates, numerals, haptics, key height). |

## Typing path (A1.3)
key → `KeyboardController` → `Composer.onKey` (core `on_key`) → `EditorUpdate` →
`InputConnectionAdapter.apply`: `beginBatchEdit`; delete `deleteBefore` grapheme clusters (converted
to UTF-16 with `BreakIterator` over `getTextBeforeCursor`); `commitText`; `setComposingText` or clear
it; `endBatchEdit`. The update's `candidates` go to the strip.

- **Languages and layouts (A1.4):** `TypingMode(language, LayoutKind)`. am/ti: `FIDEL` grid and `PHONETIC` Latin keys (strip); om: `QUBEE`; en: `ENGLISH`; `ABC` Latin passthrough in any language. A layout is offered only when the core accepted its data (`Composer.supports`); until the core is loaded the keyboard types ABC. First run (ADR 0007) starts on the first enabled language (am) in phonetic QWERTY; Fidel is one globe long-press away. The layout order is remembered per language.
- **Fidel grid:** rows, labels, long-press orders (incl. labialised forms), vowel-order keys, punctuation and numerals all come from `KeyboardEngine.fidel_layout(lang)` (copied into `FidelGrid`); no series list is in the UI. Tap a consonant (TWO_KEY, `FidelConsonant`, composing shows the sixth order), then a vowel key (`FidelVowel`; order 8 = labialised); or long-press a consonant and slide to a form (`Char`). Layouts without TWO_KEY tap `tap_text`.
- **Switching (SD 7.1):** globe tap = next enabled language (lands on that language's last native layout); globe long-press = cycle that language's layouts, ending on ABC (am: Fidel, QWERTY, ABC); long-press space = language picker popup; the ABC key toggles ABC and the language's last native layout (hidden in English: ABC == English). Every switch first sends `Commit`, so the open composition is kept as plain text. The last native layout per language and the enabled languages come from the settings store (A1.5, `:data`) through `SettingsPreferences`; a layout is picked as: remembered, else the language's input-mode setting, else its first native layout. Password/email/URI/number/phone fields stay ABC with no globe/toggle and refuse programmatic switches.
- **Mode indicator** is drawn on the space bar: `አማ · ፊደል`, `አማ · Phonetic`, `አማ · English`, `OM · Qubee`, `OM · ABC→Qu`, `EN · QWERTY`.
- **Writing modes (am/ti).** Language and writing mode are separate: am and ti each have **Fidel** (the grid), **Phonetic** (QWERTY into the phonetic engine, with candidates) and **English** (`LayoutKind.ABC`: Latin, no transliteration, auto-capitalised like ABC). The pill at the left of the bar names the active mode (ፊደል / Phonetic / English) and a tap cycles to the next one (Phonetic → Fidel → English → Phonetic); the tools panel's Writing mode tile does the same. The globe long-press still cycles Phonetic → Fidel → English, and the ABC key is the one-tap English switch (`ABC` → English, `አ` → back to the last of Fidel/Phonetic). The selected mode is saved per language (`KeyboardSettings.writingModes`, key `writing_modes`, device-local) and restored on the next keyboard session, app restart and language switch; am and ti are independent. A missing entry (settings saved before this field) falls back to the last native layout, then the language's input-mode setting, so nothing is lost. om (Qubee + temporary ABC) and en have a single mode and no pill.
- **English suggestions:** none yet. The core's `English` input mode is plain passthrough (no candidates; `engine.rs`: "Abc, English and Qubee are plain passthrough") and is only accepted for language `en`, and `select_candidate` only works in `PhoneticLatin`. The `en` dictionary pack feeds candidates only through the phonetic session. Offering English completions/autocorrection needs a core change (a Latin completion session over the `en` lexicon), so English mode is passthrough on purpose.
- **Phonetic key hints:** in Phonetic mode each letter key shows, top right in the dim label colour, the native character that key produces on its own (the Latin letter stays the main label; the shifted hint is shown while shift is on). They come from the engine: on the pack-loader thread, before the new engine is handed to the main thread, `CoreComposer.deriveHints` types each letter alone (lower and upper case) into a fresh phonetic session per language (am and ti differ) and caches the result in the composer. Keys whose single press produces no Ethiopic text show no hint. No core call happens on the draw or key path.
- **Punctuation and numerals (SD 7.2):** while an Ethiopic language is typed natively (Fidel or Phonetic, not English), long-press `.` offers ። ፣ ፤ ፥ ፦ ፧ ፨ ፡ and long-press `,` offers ፣ ፤ (from the layout's `punctuation`); long-press a digit offers Ethiopic numerals (`digits`); the `?123` page has the marks as a key row next to the ASCII punctuation. The bundled am/ti layout packs contain all of ። ፣ ፤ ፥ ፦ ፧ ፨ (plus ፡); any of these a pack lacks (or all, when no layout pack is loaded) is appended from `Layouts.GEEZ_MARKS`, without touching the signed packs. ADR 0043: with `numeralStyle` ETHIOPIC (the default until the user picks one) the digit keys of am/ti typed natively (Phonetic and Fidel, letters and `?123` pages) type ፩…፱ and ፲ (on 0), the Western digit first on long-press, then the pack's tens (and ፻ ፼ on ፲); WESTERN types 1…0 with no popup. Number, phone and date fields, the English (ABC) writing mode, om, en and ar always type Western digits. On the Fidel grid only (ADR 0007), tapping `.` / `,` commits ። / ፣ and the ASCII marks lead the long-press popup; phonetic, Qubee, English and ABC keep ASCII on tap.
- **Settings from the keyboard:** the gear at the right of the bar opens the companion app's keyboard settings (`AiLink.SETTINGS` through `EXTRA_OPEN_LINK`); there "Back to typing" or Back finishes the screen and returns to the app being typed in.
- **Navigation bar (targetSdk 36).** Since Android 15 the IME window is edge to edge and nothing pads the input view, so the bottom row used to sit under the navigation bar. The input view reads the window's root `WindowInsetsCompat` (the IME decor does not forward them to the input view; re-read on every insets dispatch and global layout): navigation-bar insets (bottom with gesture or 3-button navigation, a side with 3-button navigation in landscape), display-cutout sides, and the height of the navigation strip the system draws inside the IME window itself (Android 13+ `NavigationBarFrame` with the IME back / switcher buttons; its insets are provided to other windows, so the IME never receives them, and on a landscape phone with 3-button navigation it sits at the bottom while the bar inset is 0). The largest bottom value plus a fixed 8 dp gap (`KeyboardInsets.BOTTOM_GAP_DP`, so the bottom row never touches the switcher icons or the screen edge) pads `KeyboardColumn`, which paints the keyboard background (`KeyboardSurface.bg`) under that padding so the keyboard runs continuously under the transparent bar. Navigation-bar icons are light or dark from the surface's `dark` flag (`WindowInsetsControllerCompat`); below API 35 the bar is given the keyboard background (API 26+, or dark keys on 24-25, which have no light navigation icons), and contrast enforcement is off (API 29+). Where the system already fits the window the dispatched insets are zero, so nothing is padded twice. `onComputeInsets` is unchanged in effect: content and visible insets start below the transparent popup headroom, which never takes touches. The headroom (190 dp) shrinks when the window is too short for it plus the keyboard (landscape phones), so key rows are never cut off; popups of the top row may then overlap the top bar. Verified on an API 37 emulator (gesture and 3-button, portrait and landscape, densities 320/420/560, 720x1600), not yet on a Samsung device.
- **Key sizes:** rows keep the layout's height (with the key-height setting) in portrait; in landscape they are 75 % as tall, and the key rows never take more than 48 % (portrait) / 42 % (landscape) of the screen height, never below 30 dp; the key-height setting is applied after that fit, so it changes the keyboard in both orientations (`KeyboardMetrics`). Keys are 4 dp apart in a row and 7 dp between rows, with 6 dp corners; the globe is drawn as an icon in the label colour.
- **Accessibility:** keys carry `description`; the surface content description and an announcement name language and layout on a switch. Every key is a TalkBack virtual node (`ExploreByTouchHelper` in `KeyboardSurface`: spoken name incl. shift state, Enter action and DRAFT, Button role, accessibility click = tap, the globe's long-press as a long click "Change layout"); long-press popups (Fidel forms, Ethiopic punctuation and numerals) stay touch-only. On the bar, the pill (Button, "Writing mode: Phonetic", click "Change writing mode"), the AI entry ("AI tools" / "Close AI tools"), the DRAFT tag (text), the back chevron while composing ("Back to tools"), the candidates (Button with the candidate text, long click "Add to your dictionary") and the gear ("Keyboard settings") are virtual nodes too; the AI chips are real views with the Button role.
- **Fields:** only plain text fields use the chosen mode. Password, email, URI, number and phone fields
  always type ABC with no transliteration and no strip (SD 6.2/7: transliteration is never forced).
  ABC auto-capitalises; phonetic modes do not (capitals mean other letters in the scheme).
- **Composition ends** on Space/Enter/punctuation, a candidate tap, a mode switch (committed), and on
  `onFinishInput`, `onFinishInputView` or a cursor/selection change we did not make (the editor keeps
  the text as plain text, the core is reset: no lost or duplicated text).
- **Direct-commit fallback** for hosts that reject composing text: `TYPE_NULL`, `setComposingText`
  returning false, or a report with no composing span where our composition should be. Heuristic and
  limits: ADR 0004.

## Pack manager (A1.6, package `ime.packs`)
`KeyboardImeService` starts a background thread: `PackStorage.create(context)` then `PackManager.load()`
builds one core engine; until it is ready the keyboard types ABC (A1.3/A1.4 behaviour).

- **Profile catalog (A1.5 seam):** after `load()` the service calls `LoadResult.installedProfiles()` (one
  `ProfileRef` per language with an active `TRANSLITERATION_RULES` pack, from the core's `translit_profile`:
  code = profile id upper-cased with `_`, version, approved flag) and
  `SettingsRepository.applyInstalledProfiles(...)` on the loader thread. That publishes the catalog, runs
  `applyProfileFallback` (F14) and raises `SettingsRepository.profileChanges`. If loading fails nothing is
  published and the settings stay as they are. Per language, a saved profile code is replaced only when that
  language has a loaded profile and the code differs from it; a language whose rules pack is missing or was
  rejected keeps its saved code unflagged (a transient pack failure never erases a user choice). The
  settings-screen banner is an `android/app` follow-up.
- **Sources:** bundled `assets/packs/*.pack` (the file list is the asset directory, `seed-packs.json` is
  no longer read at run time) and the app-private `noBackupFilesDir/packs/installed/*.pack`, where the A2.3
  updater (`:packs-sync`, below) places downloaded packs (temp file, verify, rename to `.pack`). Per (language, resource type,
  platform) the newest valid pack wins; on a tie the bundled one does. `no_backup` keeps a restored
  backup from bringing back packs without their version floor.
- **Verification:** every candidate is first loaded on a throw-away engine (core: signature, key, size,
  SHA-256, layout parse, payload parse flag `PackInfo.payloadUsable`), then the winners are loaded into
  the final engine. A failing pack is skipped: an installed file is deleted, a bundled one cannot be.
  Logged as `pack <file> rejected: <REASON>` only (reason enum: core `PackRejectReason` plus
  `UNREADABLE`, `PAYLOAD_UNUSABLE`, `DOWNGRADE`, `SUPERSEDED`). A signed but unparsable dictionary or
  bigram payload is `PAYLOAD_UNUSABLE` and treated as corrupt. `minAppVersion` is enforced by the updater before it installs a pack (A2.3), not by the manager.
- **Trust keys** are generated at build time into `PackTrustConfig` by the `generate<Variant>PackTrustConfig`
  task in `build.gradle.kts`, never read from a file next to the packs. Debug: the DEV key of
  `seed-packs.json`. Release: `-Pqal.packTrustKeysFile=<keys.json>`
  (`{"keys":[{"id","publicKeyHex","status","devOnly"}]}`); with only DEV keys (`signingKeyIsDevOnly`)
  the release build fails unless `-Pqal.allowDevPackKeys=true` (internal builds). `PackTrust.check`
  repeats the rule at run time: a non-debuggable app with DEV keys and no explicit allow creates no
  manager and stays on ABC. The bundled seed packs are signed by the DEV key, so a real release also needs
  production-signed bundled packs.
- **Downgrade protection:** `VersionStore` (`packs/versions.txt`, `slot<TAB>version`, atomic write)
  keeps the highest version accepted per slot. An installed pack below it is refused (`DOWNGRADE`,
  deleted). Only a valid winner raises it, so a corrupt pack cannot lock out a later good one.
  Bundled packs are always allowed as a fallback even below the floor: they ship in the signed APK
  (not a replay vector), so a corrupt newer installed pack leaves the user on the bundled data, and a
  newer bundled pack (app update) raises the floor itself. A missing or corrupt store means no floor
  for that run (logged).
- **Last good model:** the core never drops a language's working lexicon or bigrams because of an
  unparsable payload (core README), and the manager additionally rejects such packs before loading.
- **Debugging on a device:** with a debug build, `adb shell run-as com.qalsmartkeyboard.app ls no_backup/packs`.

## Pack updates (A2.3, `:packs-sync` + `ime.packs.PackJournal`)
`:ime` has no network code (`NoNetworkTest`). Updates are downloaded by `android/packs-sync` (used by `:app`'s
WorkManager job), which only writes files that `:ime` reads. The two share the `PackJournal` (files under
`noBackupFilesDir/packs/update/`):

- **Reload signal:** after a swap the updater rewrites `update/reload.flag`. `KeyboardImeService.onStartInputView`
  compares it with the token it last loaded under and, if it differs, reloads the packs on the loader thread
  (`PackRuntime.load()`), swaps the engine on the main thread, re-attaches the personal dictionary and closes the old engine.
- **`.prev` and the version floor:** the updater renames the current `upd-<lang>-<type>-<platform>.pack` to `<name>.pack.prev`
  (not loaded: the manager only reads `*.pack`), then the verified temp file over `<name>.pack`, and records the swap in
  `update/pending.txt` with the floor it had. It never changes `versions.txt`: the floor rises when the IME loads the pack (A1.6 rule).
- **Quarantine (D34):** `PackJournal.quarantine` moves an installed pack signed by a server-revoked key aside, drops its `.prev` and pending entry, blacklists it, removes the slot's version floor and requests a reload, so the slot falls back to the bundled pack (details in `android/packs-sync/README.md`).
- **Health signal and rollback:** a pending swap is healthy when the pack loaded and won its slot (`PackJournal.afterLoad`) and the
  engine built from it survived until the keyboard was first shown (`confirm`: `.prev` and the journal entry are dropped). If the
  manager rejects the pack (it deletes the file), `.prev` is restored and `resourceId<TAB>version` goes to `update/blacklist.txt`
  so it is never downloaded again. If a swap saw `maxUnconfirmedStarts` (2) loads without a confirmation (the process died: a native
  crash on first use), the next load rolls it back before loading: `.prev` restored, version floor set back
  (`VersionStore.restore`), blacklisted. Bundled packs remain the final fallback. Swaps the updater began but did not finish
  are marked staged (ignored by the IME) and undone by the next update check.
- Tests: `:packs-sync` `TamperTest` drives `PackJournal` and `PackRuntime` on the real core (`android/packs-sync/README.md`).

## Privacy
Typed text goes only to the InputConnection and the strip; it is never logged, stored or sent (N1,
N2). The core keeps the previous word in memory for bigram ranking only. Words are learned only into the local,
encrypted personal dictionary (below).

## Personal dictionary (A1.8, package `userdict`)
Local only: no network API or permission is used by `:ime` or `:data` (`NoNetworkTest` guards the sources and manifests;
a unit test cannot prove the absence of network use in dependencies, so that stays a review item). Sync is a later,
consented feature (`DICTIONARY_SYNC`).

| File | Role |
|---|---|
| `WordLearner.kt` | Pure rules that turn committed text into learned words (`WordLearningSink`). |
| `UserDictionary.kt` | Per-language overlay (core `Lexicon` + `Ranker`, user data only), encrypted persistence, engine sync. One instance per process (`UserDictionary.get`), shared with the settings screen. |

**Learning rules** (SD 7.4, ADR 0007 D18): a typed word is finished by a space (or the Ethiopic wordspace) after it, and is
learned on its **second** sighting, not its first, so a one-off typo is never learned. Until then it is a pending count (at most
2,000 words per language; the oldest is evicted), kept in the same encrypted file so a sighting before a restart still counts.
A word already in the personal dictionary is learned at once. Counted per language, with the previous word as a pair (recorded
only when the word itself is learned) when a plain space separates them. Never learned: anything in password, email, URI, number and
phone fields, or in a field with `IME_FLAG_NO_PERSONALIZED_LEARNING` (incognito) (`InputProfile.learnsWords`); nothing is even
buffered there. A Latin word typed in ABC for am/ti; tokens with digits or symbols inside (addresses, handles), all-capital
acronyms, single letters, words over 48 characters; the word being typed when the field is left or the mode switches; text the
user is deleting (a backspace into committed text drops the open word and the context). Words must be in the language's script
(Ethiopic for am/ti, Latin for om/en). **Explicit add:** long-press a candidate on the strip (same field rules); it is learned at
once, not on a second sighting. **Remove:** the settings screen ("Personal dictionary": per-word delete and "Clear all"). Deleting
forgets the user entry and any pending sightings of that word; shipped words stay. "Clear all" also clears pending sightings.

**Storage:** one file per language under `noBackupFilesDir/userdict/` (a backup could not restore the Keystore key, so
it is excluded). Content = `"QUF2"` + the core's user-dictionary bytes + the core's user-bigram bytes + the pending sightings (a count, then per word
its UTF-8 length, bytes and sighting count), encrypted by
`:data`'s `EncryptedFileStore` with AES-256-GCM (fresh 96-bit IV per write, file name bound as AAD) under a non-exportable
Android Keystore key (`qal.userdict.v1`, no user authentication). Writes are atomic (temp file, `fsync`, rename). A missing
file starts empty (a `"QUF1"` file from before pending sightings still loads); an unreadable, undecryptable (wrong or lost key) or malformed file also starts empty, is overwritten by the
next save, and only a reason code is logged, never a word. If the Keystore cannot be used the dictionary works in memory only;
nothing is ever written unencrypted. **Lifecycle:** `load` (and `attach` to the engine) run on the pack-loader thread after the
packs are loaded; changes made before that are ignored. Saves are debounced (5 s) on a background thread, requested at once on
`onFinishInput` and run synchronously on `onDestroy`.

On JVM tests the key comes from an injected `AesGcmCipher { key }`; "restart" is a new `UserDictionary` and engine over the same
directory.

## Tests
`./gradlew :ime:testDebugUnitTest` (from `android/`): `KeyboardInsetsTest` (navigation-bar/cutout padding, row heights), `WritingModeTest` (selector, per-language persistence, ABC = English, hints in the layout; fake composer), `CoreWritingModesTest` (hints equal what each key types on the real core, Ge'ez marks on `?123` in Fidel and Phonetic), controller (fake composer), input profile, adapter (fake editor
modelled on `BaseInputConnection`), `PackManagerTest` (real core; packs signed in the test with a JCA Ed25519 key:
tamper, unknown key, unparsable payload, downgrade, persisted versions, trust policy), and `CoreTypingTest` /
`CoreLayoutsTest` (shared `CoreTestEnv`), which build the layouts from core data and type a word in each of the four
languages into the fake editor through the real Rust core (host build via JNA, `:core-bindings:cargoHostBuild`) with the
bundled packs loaded by the `PackManager`.

## Settings (A1.5)
`KeyboardImeService` owns a `SettingsSnapshot` (from `:data`): `current` is a plain field read (defaults until the first load, so nothing blocks the main thread at IME start), refreshed by a background collector; each change posts `KeyboardController.onPreferencesChanged()` to the main thread, which drops cached layouts, re-reads languages, numerals and key height, and moves off a language that was disabled. Haptics are read per key press. The first load also selects the starting language (first enabled). `conversionTrigger` and `unknownTokenBehavior` are stored and synced but not applied by the engine yet; `showTransliterationCandidates` hides the strip. Settings values are never logged.

## AI toolbar (A2.5)

`com.qalsmartkeyboard.ime.ai`: the AI actions (Translate + destination chip, Grammar, Rewrite + tone chip, plan
state) and a review panel. The actions are not a row of their own: the "✦ AI" entry on the one bar opens them in the
bar's middle (they scroll sideways, nothing is truncated; a × closes them, and so do candidates, a mode switch,
a field change and hiding the keyboard). When blocked, the plan state comes first: "Sign in to use AI" and "Update
needed" are links (`AiLink.SIGN_IN` / `UPDATE_APP`), "Offline" is text; otherwise the quota hint is last. The panel
(picker, working, review, errors with their links) appears above the bar only while it has content, so the idle
keyboard shows only the bar. `:ime` stays network-free: it only defines the `AiActions`
port (pure Kotlin); `:ai-client` implements it and `:app` registers the factory (`QalApplication` ->
`AiActionsHolder`). The service builds the actions on a background thread.

- **Explicit action only.** `AiActions.submit` is called only from a tap (`AiToolbarController.onTask` / `retry`).
  The editor is read only then. Typing, selection changes and focus send and read nothing. The keyboard never
  refreshes plan state by itself: `refresh()` (no text) runs on the first toolbar interaction (opening the AI entry,
  a picker or action tap, `AiToolbarController.onToolbarOpened`, at most once per 5 minutes) and from the app's background worker; showing
  or hiding the keyboard makes no network call (ADR 0007 D40). The result enters the editor only via Replace or Insert; nothing presses Send.
- **Target text.** The selection if there is one, else the sentence before the cursor (after the previous
  `. ! ? ... ። ፧ ፨` or line break; a sentence that just ended counts; trailing whitespace is kept). Text after the
  cursor is never read. Longer than 5000 chars: refused, not cut.
- **Hidden** in password, email, URI, number, phone, date fields, TYPE_NULL hosts and
  `IME_FLAG_NO_PERSONALIZED_LEARNING` (`InputProfile.allowsAi`).
- **One request at a time**: a tap while one is in flight is ignored; Cancel aborts the call. Typing keeps working.
  Retry after a network error/timeout/503/`REQUEST_IN_PROGRESS` reuses the `Idempotency-Key`; any other retry or
  "another result" uses a new one. A field change or keyboard hide cancels and drops the answer.
- **Destination chip default:** `AiActions.defaultDestination(package)`: the app's A2.4 rule, else the last destination
  picked in that app (`rememberDestination`, device-local), else the first profile, else English. Choosing a
  destination in the picker calls `rememberDestination`.
- **Links:** a failure's link button starts the companion app's launcher activity with
  `EXTRA_OPEN_LINK` = `AiLink.name`; `:app`'s `DeepLinks` routes it (sign-in, settings, plans, update).
- Replace re-checks that the original text is still there; if not, the result is inserted at the cursor instead.
- **Translate panel** (`TranslatePanelView` + `TranslateController`, opened from the bar's Translate shortcut): a
  language header (source/destination chips, swap, settings, close), an editable input box (`KeyboardTextField`;
  the keys type into it while it's open) and the Translate button — the only thing that sends, Enter included.
  While a result shows, the keyboard surface hides so the window is just the panel; tapping the box brings the
  keys back (and folds the output card on landscape screens so nothing clips). Editing the box drops the shown
  output. Three result actions: Copy (clipboard, "Copied" feedback), Replace (replaces the captured selection or
  last sentence in the host field — re-checks it is still there and is disabled when nothing was captured) and
  Insert (always inserts at the host cursor, preserving the text around it).
- **AI tools grid** (`AiTool` / `AiQuickAction`, ADR 0041): every served feature — Translate; the quick actions
  (Grammar, Rewrite, Simplify, Shorter / Expand / Clarify — `REWRITE` `options.mode`; the ten `TONE_ADJUST` tones);
  the Assist-opening tiles (Reply, Explain, Summarise, Format — `FORMAT_TEXT`); Write Email (`DRAFT_DOCUMENT` with the
  document-type row: e-mail, job application, CV, cover letter, business message); AI Assist. Inside Assist, Reply
  offers the six preset intents (`RepliesRequest.intent`) as optional chips and Format the five output shapes
  (`TransformRequest.options.format`). Tiles a plan does not include show a lock and still open to explain.
- Tests: `ai/` (controller, targets, `AiTypingLatencyTest` benchmark + no-call-on-keystroke test); Robolectric
  `KeyboardBarTest` (bar states, fixed height, sensitive fields, signed-out link) and `BarAccessibilityTest`
  (virtual nodes of the bar and the keys).

## Themes (package `ime.theme`)
`KeyboardTheme` is a flat set of colour and shape tokens: background (`bgTop`, `bgBottom`; different = vertical gradient), `key`, `special`, `pressed`, `accent` + `onAccent` (Enter, highlighted popup item), `accentText`, `keyText`, `specialText`, `hintText`, `border` + `borderWidthDp`, `shadow` + `shadowDyDp`, `cornerRadiusDp`, the strip (`stripBg`, `stripText`, `stripDivider`, `warn`), the popup (`popupBg`, `popupText`, `popupBorder`) and `isLight`. `KeyboardSurface`, `CandidateStripView`, `PopupOverlay` and `AiToolbarView` each have a `theme` property and read tokens through getters (`textColor get() = theme.keyText`); the gradient shader is built once per theme and size, borders and shadows reuse preallocated paints, so nothing is allocated while drawing. The service resolves the stored id and calls `applyTheme()` when the settings snapshot changes, the entitlement changes, the device mode changes and when the keyboard is shown; the next draw uses the new theme (no restart).

- **Catalogue:** `ThemeCatalog.all` (ids are stable, lower-case snake case, at most 40 characters; `system` is not an entry: it means Classic Light/Dark by the device's dark mode and is the default). **Add a theme:** append a `theme(...)` entry to `ThemeCatalog.all` under `FREE` or `PREMIUM`; no layout or view change. `ThemeContrastTest` then checks it (key/special/Enter/candidate/popup text 4.5:1, hints and accent labels 3:1, every gradient stop), `ThemeCatalogTest` checks id rules. Never rename or reuse a released id: users store it and it syncs as `keyboardTheme`.
- **Premium gating:** `PREMIUM_THEMES` (backend feature, V18) reaches the keyboard through the `ThemeAccess` port, injected by `:app` with `ThemeAccessHolder` (like `AiActionsHolder`; `:ime` has no network or `:ai-client` dependency). It answers from the cached, verified entitlement token, including its offline grace. `ThemeResolver.resolve(storedId, systemDark, access)`: unknown id or `system` -> Classic Light/Dark by device mode; a premium theme without access -> Classic Light/Dark by the theme's `isLight` (the stored choice is kept, so it comes back by itself once access returns). Until the port is built (a background thread at service start) a premium choice is drawn as its fallback.

## Emoji panel (package `ime.emoji`, ADR 0028)
An emoji key beside the space bar (plain text fields only: `InputProfile.allowsEmoji`) opens a panel in place of the keys (same height): category tabs, one scrolling grid, ABC / space / backspace / enter. The magnifier opens a search: the keys come back and type into `EmojiController.querySink` (the controller routes the composer's output there while `emoji.searching`, so every layout works and nothing is learned), results show in `EmojiSearchBar`. Recents (32) and per-emoji skin tones are device-local settings (`KeyboardSettings.recentEmoji`, `emojiTones`). Nothing loads until the panel is first opened: `EmojiLoader` parses `assets/emoji/*.tsv` on a worker thread and filters unsupported glyphs with `Paint.hasGlyph` (cached per system build in `FileGlyphCache`).

- **Regenerate the data** (new Unicode or CLDR release): `python -X utf8 android/ime/scripts/build_emoji_data.py --download` (needs network; the keyboard itself never does), then run `:ime:testDebugUnitTest`. Commit the script and the generated `emoji*.tsv`; keep `UNICODE-LICENSE.txt` beside them (Unicode License v3).
- **Tests:** `emoji/` (data parsing, search ranking in en/am/ti/om, recents and tones, controller: laziness, search routing, insertion, backspace over ZWJ/flags/tones; `EmojiPanelViewTest` Robolectric nodes and touch), `EmojiKeyLayoutTest` (key visibility per field type, space-bar share).
- **Layout options (ADR 0043):** a per-language Latin key arrangement (`LatinLayout`: QWERTY, AZERTY, QWERTZ, Dvorak, Colemak) for every Latin layout of the language (Phonetic, Qubee, English, ABC; hints and the Qubee hudhaa follow the arrangement), the Fidel grid typed two-key or long-press only (`FidelVariant`, from the same layout pack), and the number-row switch (off: digits on long-press of the top letter row). The tools grid's "Layout" tile opens `LayoutPanelView` (`ToolPanel.LAYOUT`) with the current language's choices; a choice applies at once (the open composition is committed first) and is saved per language.
