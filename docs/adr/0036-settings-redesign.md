# ADR 0036 — Settings redesign: Gboard-style home, theme strip, language pages

**Status:** Accepted · **Date:** 2026-11-19 · **Requested by:** product owner · **Builds on:** ADR 0007 (typing
languages, layouts), ADR 0029 (voice typing), ADR 0030 (Word Art), ADR 0031 (translation, e-mail writer), ADR 0032 (UI
localisation) · **Contract:** none

## Context
The companion app's settings were one long column: a setup card, the App language card, account and privacy cards, a
card per typing language with mode chips and Up/Down buttons, numeral chips, a theme button, e-mail writer, translation
and Word Art cards with a paragraph each, four switches, the key-height slider, Reset, and the personal dictionary, with
a debug "Hello core 0.1.0!" line on top. The product owner found it hard to scan: too much explanatory text, too many
cards, borders and buttons. They asked for a clean, professional settings experience benchmarked on Google Gboard's
settings (its patterns, not its colours or branding), with the theme choice at the very top and a polished language
selection that tells Amharic, Tigrinya and Afaan Oromoo apart.

## Decisions

### 1. Patterns taken from Gboard
- A **home list of short rows** (leading icon, title, an optional one-line value) that open pages; no cards, no
  dividers, no paragraphs. Small coloured **section headers** group the rows.
- **One page scaffold** for every page: a top app bar with the page title and a Back arrow, then list rows
  (`SettingsComponents.kt`): switch rows and checkbox rows toggle when the whole row is tapped, radio rows select when
  the whole row is tapped, each row is one focusable target with a role, headers are headings for TalkBack.
- **At most one quiet supporting line** per page where it carries a fact the user needs (where voice audio goes, the
  globe-key order, that App language does not change typing languages, how the dictionary is stored).
- Material 3 colour roles only (light and dark), the app's existing theme; icons are vector drawables of Material
  icons (no icon library, no new dependency). No chevrons on rows (as Gboard and Android Settings).

### 2. Information architecture

Home (title: the app name):

| Group | Rows |
|---|---|
| (top) | **Theme**: heading, "View more", a horizontally scrolling strip of miniature keyboards (`KeyboardThemePreview`, drawn from each theme's tokens). The applied theme comes first with an outline and a check; tapping a theme applies it; a locked premium theme opens the picker on its preview with "Unlock with Premium" instead. Up to 8: applied, System, free, premium. |
| (only when not ready) | A compact setup prompt ("Finish setting up the keyboard · Set up") while Qal is not turned on or not selected. |
| Keyboard | Languages (summary: enabled languages), Preferences, Voice typing (On/Off), Dictionary |
| AI and tools | Translate into (summary: Automatic or the language), Write Email with AI, Word Art |
| Account and privacy | Account (Signed in / Guest / Not signed in), Privacy |
| App | App language (summary), Keyboard setup (when ready), Advanced, About |

Pages:
- **Languages**: the enabled languages in switching order, each named in itself (አማርኛ, ትግርኛ, Afaan Oromoo, English)
  with its UI-language name and current layout underneath, Move up / Move down icon buttons (moves skip disabled
  languages, which keep their stored place), each row opening the language's page; then "Add or remove languages".
- **Language** (one per language): Layout as radio rows with a one-line description each (Phonetic QWERTY, Fidel grid,
  Latin — whatever `LanguageRules.allowedModes` allows); for phonetic typing, "Show suggestions" and the transliteration
  profile as a quiet line.
- **All languages**: a search field (matches the self-name, the UI-language name and the code) and a checkbox row per
  language; the last enabled language cannot be turned off (row disabled, "At least one language stays on").
- **Preferences**: Keys (haptic feedback, key press preview, key height — saved when the slider is let go), Numerals
  (Western 1 2 3 / Ethiopic ፩ ፪ ፫).
- **Voice typing**: the switch and one line on where the audio goes.
- **Translate into**: Automatic (English, or Amharic for English text) or a language, as radio rows.
- **Dictionary**: the dictionary-sync switch (A2.4) and the personal dictionary (a row per language with its word
  count, opening the words with delete buttons; "Clear all" after a confirmation).
- **Account**: the existing account section (sign in / sign out), unchanged.
- **App language** (ADR 0032), **Advanced** (language packs on mobile data; Reset to defaults, now after a
  confirmation, still keeping the crash-report choice), **About** (app version, core version — the A1.1 binding proof
  that used to be the "Hello core" line — and the privacy notice).

Language lists are never hard-coded: they come from `KeyboardSettings.languages` (always every language of
`LanguageRules.KNOWN`) and `LanguageRules.KNOWN`. Self-names come from `AppLanguage.selfName`, else the platform's name
of the language in itself (`Locale.getDisplayLanguage`), so a language added to `KNOWN` (Arabic, in a parallel branch)
appears on every page with a proper self-name; its UI-language name falls back to the code until a `language_name_*`
resource and a `languageName()` entry exist.

### 3. What moved where

| Before | Now |
|---|---|
| "Hello core x.y.z!" line | About → Core engine |
| Setup card | Home prompt while not ready, else App → Keyboard setup |
| App language card | App → App language page |
| Account card | Account page |
| Privacy card | Privacy row (opens the privacy area as before) |
| Language cards (switch, Up/Down, mode chips, candidates) | Languages, Language and All languages pages |
| Numerals chips, haptics, key preview, key height | Preferences |
| Theme "Current: …" + button | Theme strip on top + View more |
| E-mail writer and Word Art cards | Rows under AI and tools |
| Translate-into chips | Translate into page |
| Voice typing switch | Voice typing page |
| Packs on mobile data, Reset | Advanced |
| Personal dictionary (cards) | Dictionary page (rows) |
| "Back to typing" bar (keyboard gear) | The home page's arrow ("Back to typing") and the system Back |

The theme picker (`ThemesScreen`) moved onto the same scaffold (top bar with Back, previews without card containers,
no intro paragraph); its Free / Premium sections, badges, locked banner, preview, Apply and Unlock are unchanged.

### 4. Navigation without a library
navigation-compose is not a dependency and is not added. `SettingsNavigator` is a small saveable back stack (page
plus optional argument) owned by `MainActivity` so deep links can reset it; `SettingsScreen` pops it with
`BackHandler`. The activity keeps its own top-level switches (themes, Word Art, e-mail writer, privacy, sign-in, plans,
setup). `SHOW_SETTINGS` and `SHOW_KEYBOARD_SETTINGS` reset the stack to the home page (and the latter now also closes
the theme picker, Word Art and the e-mail writer). The system Back now also closes the theme picker (preview first),
Word Art, the e-mail writer and the plans screen instead of leaving the app. Settings pages and the theme picker draw
their own top app bar and take the system-bar insets themselves (edge to edge).

### 5. Strings
Labels are short; 34 strings and one plural (`pd_word_count`) were added, 7 shortened, 17 removed (`settings_title`,
`settings_setup_body`, `settings_setup_button`, `settings_opened_from_keyboard`, `settings_theme_current`,
`settings_theme_button`, `themes_intro`, `themes_premium_note`, `themes_back_to_list`, `pv_entry_body`,
`pv_entry_button`, `settings_wordart_body`, `settings_wordart_button`, `settings_email_body`, `settings_email_button`,
`pd_language_count`, `pd_delete`). am, ti and om are DRAFT (ADR 0032, N9) and follow the glossary.

## Consequences
- The settings home fits on about two screens; each page has one job. Adding a setting means choosing a page, not
  adding a card.
- Tests: `SettingsScreenTest` (Robolectric, real `SettingsRepository`): the theme strip at the top, applying a theme,
  locked premium themes, setup prompt, navigation and Back, keyboard-gear return, reset confirmation, language
  enable/disable with the last-language rule, reorder, layout choice, search. `SettingsLogicTest`: back stack, strip
  order, self-names (incl. a future `ar`), search matching. `SettingsLocalisationTest` walks the pages in am, ti, om.
- Not changed: the privacy screens, the sign-in screens, the account section, the e-mail writer and Word Art screens.
- On-device QA (light/dark, 360 dp, font scale 1.3, TalkBack) is a later step; `@Preview`s cover those cases in the IDE.
