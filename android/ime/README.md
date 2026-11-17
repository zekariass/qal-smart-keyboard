# android/ime

Android `InputMethodService` and keyboard UI (plan steps A1.x). Design: SD §6.2, ADR 0003 (custom
`View` surface), ADR 0004 (candidate merge policy, composing-text fallback).

## Structure
| File | Role |
|---|---|
| `KeyboardImeService` | IME lifecycle: loads the core + bundled packs off the main thread, `onStartInput` / `onUpdateSelection` / `onFinishInput*`, builds the input view (candidate strip above the key surface). |
| `KeyboardController` | Pure state machine: page, shift, typing mode, candidates. Every key goes through a `Composer`; updates go to a `TextSink`. JVM-tested. |
| `Composer`, `CoreComposer`, `PassthroughComposer` | The engine port. `CoreComposer` wraps the Rust `KeyboardEngine`; `PassthroughComposer` types ABC until the core is loaded. |
| `InputConnectionAdapter` (+ `EditorOps`) | Applies `EditorUpdate`s in one batch edit, grapheme-cluster deletion, own-edit tracking, external cursor moves, direct-commit fallback (ADR 0004). JVM-tested with a fake editor. |
| `CandidateStripView` | Draws the candidates; tap = `select_candidate(i)`. Shows a "DRAFT" tag while the profile is DRAFT (N9). Hidden in password fields and in ABC. |
| `KeyboardSurface`, `KeyboardLayout` | Custom-View ASCII QWERTY surface (A1.2) plus the temporary mode key. |
| `InputProfile` | `EditorInfo` → field kind, learning, Enter action, caps, `allowsTransliteration`. |
| `BundledPacks` | TEMPORARY pack loading from `assets/packs/` (see below). |

## Typing path (A1.3)
key → `KeyboardController` → `Composer.onKey` (core `on_key`) → `EditorUpdate` →
`InputConnectionAdapter.apply`: `beginBatchEdit`; delete `deleteBefore` grapheme clusters (converted
to UTF-16 with `BreakIterator` over `getTextBeforeCursor`); `commitText`; `setComposingText` or clear
it; `endBatchEdit`. The update's `candidates` go to the strip.

- **Modes (TEMPORARY, A1.3):** the key left of `,` cycles `ABC → አማ (Amharic phonetic) → ትግ (Tigrinya
  phonetic)`, only through modes whose `TRANSLITERATION_RULES` pack loaded. A1.4 replaces it with real
  layouts and language switching; A1.5 persists the choice.
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
`./gradlew :ime:testDebugUnitTest` (from `android/`): controller, input profile, adapter (fake editor
modelled on `BaseInputConnection`), and `CoreTypingTest`, which types into the fake editor through the
real Rust core (host build via JNA, `:core-bindings:cargoHostBuild`) with the bundled packs.
