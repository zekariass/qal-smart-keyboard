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
| `CandidateStripView` | Draws the candidates; tap = `select_candidate(i)`. Shows a "DRAFT" tag while the profile is DRAFT (N9). Hidden in password fields and in ABC. |
| `KeyboardSurface` | Custom-View key surface: draws keys, hit-tests, long-press popups (drawn by `PopupOverlay`, selection by slide-and-release), globe long-press. Keys with a popup or long action act on release. |
| `PopupOverlay`, `OverlayStack` | The popup is drawn inside the input view, in a transparent non-touchable 190dp headroom above the strip (`onComputeInsets` keeps it out of the app layout and touch region). A `PopupWindow` was tried first: it is clipped to the IME window on API 35 and never appeared. |
| `KeyboardLayout` (`Layouts`) | Layout model + builders: Fidel grid (from core data), phonetic/English/ABC QWERTY, Qubee (hudhaa key on the main page), symbol pages, number/phone pads. |
| `TypingMode` (`LayoutKind`, `LanguageCatalog`, `EnabledLanguages`, `FidelGrid`) | Language + layout model, UI names, the enabled-language interface and the Kotlin copy of the core's `FidelLayout`. |
| `InputProfile` | `EditorInfo` → field kind, learning, Enter action, caps, `allowsTransliteration`. |
| `BundledPacks` | TEMPORARY pack loading from `assets/packs/` (see below). |

## Typing path (A1.3)
key → `KeyboardController` → `Composer.onKey` (core `on_key`) → `EditorUpdate` →
`InputConnectionAdapter.apply`: `beginBatchEdit`; delete `deleteBefore` grapheme clusters (converted
to UTF-16 with `BreakIterator` over `getTextBeforeCursor`); `commitText`; `setComposingText` or clear
it; `endBatchEdit`. The update's `candidates` go to the strip.

- **Languages and layouts (A1.4):** `TypingMode(language, LayoutKind)`. am/ti: `FIDEL` grid and `PHONETIC` Latin keys (strip); om: `QUBEE`; en: `ENGLISH`; `ABC` Latin passthrough in any language. A layout is offered only when the core accepted its data (`Composer.supports`); until the core is loaded the keyboard types ABC. First run starts on the first enabled language's first native layout (am, Fidel grid).
- **Fidel grid:** rows, labels, long-press orders (incl. labialised forms), vowel-order keys, punctuation and numerals all come from `KeyboardEngine.fidel_layout(lang)` (copied into `FidelGrid`); no series list is in the UI. Tap a consonant (TWO_KEY, `FidelConsonant`, composing shows the sixth order), then a vowel key (`FidelVowel`; order 8 = labialised); or long-press a consonant and slide to a form (`Char`). Layouts without TWO_KEY tap `tap_text`.
- **Switching (SD 7.1):** globe tap = next enabled language (lands on that language's last native layout); globe long-press = cycle that language's layouts, ending on ABC (am: Fidel, QWERTY, ABC); long-press space = language picker popup; the ABC key toggles ABC and the language's last native layout (hidden in English: ABC == English). Every switch first sends `Commit`, so the open composition is kept as plain text. Last layout per language is kept in memory (A1.5 persists it). The enabled languages come from `EnabledLanguages` (default am, ti, om, en; A1.5 supplies the setting). Password/email/URI/number/phone fields stay ABC with no globe/toggle and refuse programmatic switches.
- **Mode indicator** is drawn on the space bar: `አማ · ፊደል`, `አማ · QWERTY`, `አማ · ABC→አ`, `OM · Qubee`, `EN · QWERTY`; DRAFT layouts/profiles carry a small DRAFT tag (N9).
- **Punctuation and numerals (SD 7.2):** while an Ethiopic language is typed natively, long-press `.` offers ። ፣ ፤ ፥ ፦ ፧ ፨ ፡ and long-press `,` offers ፣ ፤ (both from the layout's `punctuation`); long-press a digit offers Ethiopic numerals (`digits`); the `?123` page has the marks as a key row. Numerals are long-press alternatives only until A1.5's `numeralStyle`. ASCII `.` and `,` stay the tap output (not silently replaced).
- **Accessibility:** keys carry `description`; the surface content description and an announcement name language and layout on a switch. Per-key TalkBack virtual nodes are NOT implemented yet (ADR 0003 follow-up).
- **Fields:** only plain text fields use the chosen mode. Password, email, URI, number and phone fields
  always type ABC with no transliteration and no strip (SD 6.2/7: transliteration is never forced).
  ABC auto-capitalises; phonetic modes do not (capitals mean other letters in the scheme).
- **Composition ends** on Space/Enter/punctuation, a candidate tap, a mode switch (committed), and on
  `onFinishInput`, `onFinishInputView` or a cursor/selection change we did not make (the editor keeps
  the text as plain text, the core is reset: no lost or duplicated text).
- **Direct-commit fallback** for hosts that reject composing text: `TYPE_NULL`, `setComposingText`
  returning false, or a report with no composing span where our composition should be. Heuristic and
  limits: ADR 0004.

## Bundled packs (TEMPORARY until A1.6)
At service start a background thread creates the engine and loads every pack listed in
`assets/packs/seed-packs.json`, trusting the key in that file (`signingKeyId`,
`signingPublicKeyHex`). That key is a **DEV key** (`signingKeyIsDevOnly: true`) and the packs are
DRAFT/UNVERIFIED seeds (N9): fine for development, never a release trust anchor. Each pack still
passes the core's full verification; a pack that fails is skipped and logged as file name + error
type only. If the core cannot load at all, the keyboard keeps typing ABC. A1.6 (pack manager) replaces
`BundledPacks` with version bookkeeping and corrupt-pack fallback.

## Privacy
Typed text goes only to the InputConnection and the strip; it is never logged, stored or sent (N1,
N2). The core keeps the previous word in memory for bigram ranking only. No learning yet (A1.8 honours
`InputProfile.learningAllowed`).

## Tests
`./gradlew :ime:testDebugUnitTest` (from `android/`): controller (fake composer), input profile, adapter (fake editor
modelled on `BaseInputConnection`), and `CoreTypingTest` / `CoreLayoutsTest` (shared `CoreTestEnv`), which build the
layouts from core data and type a word in each of the four languages into the fake editor through the real Rust core
(host build via JNA, `:core-bindings:cargoHostBuild`) with the bundled packs.
