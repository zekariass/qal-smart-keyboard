# ADR 0034 — Arabic as a typing language

**Status:** Accepted · **Date:** 2026-11-19 · **Requested by:** product owner (2026-11-19: "Add Arabic as an additional
keyboard language … proper Arabic keyboard layout … right-to-left text entry … punctuation, spacing and cursor
behaviour … represented correctly in settings and language selectors … in translation language options if the provider
supports it") · **Builds on:** ADR 0007 (typing languages, N9), ADR 0026 (key previews), ADR 0029 (voice typing),
ADR 0031 (AI panels), ADR 0032 (UI localisation) · **Contract:** none changed (backend activation is a follow-up)

## Context
The keyboard types the four launch languages (am, ti, om, en). Many users also write Arabic (family, religion, work in
the Gulf), and today they must switch to another keyboard app for it. The backend's `supported_languages` already lists
`ar` (Arabic, العربية, Arab script, RTL) as PLANNED and inactive, so the server refuses it in `/me/typing-languages` and
offers no `en↔ar` translation route yet. This step makes Arabic a real typing language on the device; activating it on
the server (and adding translation routes, migration V23) is a separate step.

## Decisions

### 1. Engine: a dedicated `Arabic` passthrough mode in the core
The Rust core gets `InputMode::Arabic`, allowed only for `ar` (`mode_fits_language`), and `ar` joins
`SUPPORTED_LANGUAGES`. It is a passthrough like `English`/`Qubee`: no composition, no candidates, Backspace returns
`delete_before = 1`. Unlike the Latin passthrough modes it runs each `Char` through the same `sanitize` as the Fidel
popups: the text is committed as **NFC** (alef + combining hamza becomes أ) and control characters are never
committed. Alternatives considered:

- **Reuse `Abc`** (no core change). Rejected: `Abc` is the Latin passthrough "for every language" and commits exactly
  what it receives (no NFC), and a separate mode lets the host and the core agree on what an "Arabic layout" is.
- **A data-driven layout pack like `DirectFidel`.** Rejected for now: Arabic needs no composition (every key is a
  finished character), so a pack would add signing, versioning and a review gate for a fixed, standard arrangement.

The variant is added last, so the FFI ordinals of the existing modes do not change.

### 2. No word predictions for Arabic (N10)
There is no Arabic dictionary or frequency list with a recorded commercial-use licence, so no `DICTIONARY` /
`FREQUENCY_LIST` pack exists for `ar` and the candidate strip stays hidden. For the same reason Arabic words are **not
learned** into the personal dictionary (`LanguageCatalog.learnsWords`): nothing would ever be suggested back, so keeping
them would only store typed text for no benefit. `UserDictionary.LANGUAGES` and `DictionarySync.LANGUAGES` stay am, ti,
om, en; dictionary sync is unaffected.

### 3. Layout (Android `:ime`, `ArabicKeys` + `Layouts.arabic`)
The standard Arabic arrangement (Windows Arabic 101, as on Android keyboards), visual left to right:

```
1 2 3 4 5 6 7 8 9 0                     (long-press: ١ ٢ ٣ ٤ ٥ ٦ ٧ ٨ ٩ ٠)
ض ص ث ق ف غ ع ه خ ح ج
ش س ي ب ل ا ت ن م ك ط
ذ ء ؤ ر لا ى ة و ز ظ د ⌫
?123  🌐  ◌َ  ،  ☺  [ ع · العربية ]  .  ⏎
```

- `ذ` (Windows: the backtick key) and `د` (Windows: `]`) complete the third row, as on Android; `لا` (Windows: `B`) is
  one key that commits ل + ا, drawn as one string so the font shapes the ligature. Row 3 has 12 keys (11 letters +
  Delete), so its keys are about 8 % narrower than rows 1–2; we keep the lam-alef key because it is one of the most
  frequent letter pairs and part of the standard.
- **No shift and no auto-capitalisation** (Arabic has no case; `LayoutKind.ARABIC` is neither Latin nor phonetic).
- **No ABC layout inside Arabic**: no ABC key and the globe long-press does not cycle to Latin, as on Gboard. Latin is a
  globe tap away (English). Fields that must stay Latin (password, e-mail, URL, number) still get QWERTY, as for every
  language.
- The bottom row has the same keys and widths as the other languages; the slot the others use for the ABC key holds the
  **marks key** `◌َ`: tap types a fatha, long-press offers fatha, damma, kasra, sukun, shadda, fathatan, dammatan,
  kasratan, superscript alef and tatweel. The symbols page has the same ten marks as a row. Marks are drawn on a dotted
  circle (U+25CC) on keys, popups and the key preview, never floating on nothing; TalkBack says "Arabic mark Shadda".
- Long-press alternates: ا → أ إ آ ٱ ء; ي → ئ ى; و → ؤ; ه → ة.
- Punctuation: the comma key types the Arabic comma `،` (long-press `؛` then `,`); the period key types `.` (Arabic uses
  the Latin full stop) with `؟ ! ؛ :` on long-press, `؟` shown as its hint. On the symbols pages `, ; ? %` type `، ؛ ؟ ٪`
  with the ASCII character on long-press.
- Digits: Western digits stay primary (what most Arabic-script phones and forms send); Arabic-Indic digits `٠–٩` are the
  long-press alternatives and the key hints. A numeral-style setting for Arabic-Indic-first is left for later (the
  existing `numeralStyle` is Ethiopic-specific). Number and phone fields keep the ASCII dial pad.
- Space-bar indicator `ع · العربية` (`LanguageCatalog.shortLabel` `ع`, `kindLabel(ARABIC)` `العربية`); the picker and
  the AI panels show the self-name العربية; TalkBack and settings use the UI-language name (`language_name_ar`).

### 4. Right-to-left behaviour
- **Text is committed in logical order**; the editor's bidi algorithm lays it out right to left. The keyboard view keeps
  its own LTR layout direction (keys, bottom row, popups and previews are not mirrored, as on Gboard); the UI languages
  are all LTR, so nothing in the keyboard window flips.
- **Backspace**: deletion is by grapheme cluster, except that a cluster ending in an Arabic combining mark loses that mark
  alone (`InputConnectionAdapter.utf16LengthOfLastGraphemes`): `بَّ` → `بَ` → `ب` → nothing, as Arabic keyboards do. ل
  and ا are separate letters, so Backspace after `لا` leaves `ل`. Latin accents and Fidel syllables are unchanged.
- **Cursor movement**: the keyboard has no space-bar slide or arrow keys. The one place it moves the cursor with a key
  event, the AI toolbar's "Insert" when the editor does not report the selection, collapses the selection to its logical
  end with `DPAD_LEFT` when the selected text starts right to left (arrow keys move visually; `DPAD_RIGHT` would land on
  the logical start). `؟` ends a sentence for the AI toolbar's "sentence before the cursor".

### 5. Configuration everywhere
- `LanguageRules.KNOWN` = am, ti, om, en, **ar**; `LanguageRules.OPT_IN` = {ar}. **New users** get Arabic in the list,
  switched off; **existing users** get it appended switched off by `KeyboardSettings.sanitized()` (the same migration
  that appends any missing known language). Their order and choices are untouched.
- Allowed mode for `ar`: `LATIN_PASSTHROUGH` only. The contract's enum names "keys commit what they show" that way, and
  the backend accepts only that mode for a non-Ge'ez language; the keyboard picks the Arabic layout from the language,
  not from this value. Settings shows no mode choice for Arabic.
- `LanguageCatalog`, `KeyboardLabels` (`languageAr`, `spokenArabicKeys`, `keyArabic*`), the settings screen's
  `languageName()`, onboarding (`lang_ar` = "Arabic (العربية)", unchecked by default), the AI language lists
  (`AiLanguages.ALL`, `ServerAiActions.PLAIN_LANGUAGES`) and the settings' "translate into" chips. A translation the
  server cannot do yet fails with the existing "language not supported" message; nothing is faked.
- Voice typing: Arabic recognition tag `ar-<region>` when the device is set to Arabic, then `ar-SA`. The voice picker
  lists Arabic only when a speech service declares it, or while Arabic is the keyboard language (so the user sees why
  voice fails); support is checked at run time like the Ethiopian languages.
- Strings: en plus DRAFT am/ti/om (N9, ADR 0032), with the naming choices recorded in the l10n glossary.

### 6. Sync while the server refuses `ar`
Without a change, every typing-language sync would PUT a list containing `ar` and get 400 `LANGUAGE_NOT_SUPPORTED`,
so the whole list would stop syncing. `TypingLanguagesSync` now reads the field errors (`languages[i].languageCode`),
remembers the refused codes for a day in the sync state (`RefusedLanguages`), and repeats the run once without them.
A held-back language stays exactly as it is on the device (enabled, position), takes no part in the merge or the
order comparison, and is not an error or a reason to retry with backoff. After a day, or as soon as the server lists
the language, it is sent again, so activation on the server needs no app update. Preferences sync carries no typing
language; dictionary sync does not include Arabic.

## Out of scope
- Word predictions, autocorrect and learning for Arabic (no licensed data, N10).
- Arabic as a **UI** language (no `values-ar`, no RTL app UI).
- Backend activation of `ar`, `en↔ar` translation routes (migration V23), Arabic emoji search keywords and Word Art
  greetings (emoji search and Word Art fall back to English), Persian/Urdu letters and an Arabic-Indic-first numeral
  setting.

## Consequences
- Five typing languages: lists and tests that enumerated four now include `ar`.
- On-device QA is still needed for glyph rendering (lam-alef ligature, marks on the dotted circle, key-label sizes),
  the editor's handling of harakat deletion and bidi in real apps; JVM and core tests cover the logic.
