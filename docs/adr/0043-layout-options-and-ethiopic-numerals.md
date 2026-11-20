# ADR 0043 — Ethiopic numerals by default, key layout options and the Layout tile

**Status:** Accepted (layout choices DRAFT until the G0 panel) · **Date:** 2026-11-19 · **Requested by:** product owner ·
**Benchmark:** Google Gboard (its layout choices, number-row switch and tools grid; patterns, not branding) ·
**Builds on:** ADR 0007 (Phonetic first, Fidel grid), ADR 0032 (UI strings), ADR 0034 (Arabic), ADR 0035 / 0041 (tools
grid), ADR 0039 (prediction engine), `docs/specs/keyboard-layout-v1.md`

## Context
The digit keys of Amharic and Tigrinya typed Western digits; `numeralStyle` ETHIOPIC only added Ethiopic numerals to
their long-press popup, and the default was WESTERN. The product owner asked for Ethiopic numerals as the default
output of the am/ti Phonetic keyboard, Western digits still reachable. The owner also asked for more layout choices for
Ge'ez and English, with a practical typing benefit, and a "Layout" tile in the tools grid that switches the current
language's layout in place, like Gboard.

## Decisions

### 1. Ethiopic numerals: what the keys do
- While Amharic or Tigrinya is typed natively (**Phonetic and Fidel**) and the numeral style is ETHIOPIC, the digit row of
  the letters page and of both symbols pages types **፩ ፪ ፫ ፬ ፭ ፮ ፯ ፰ ፱** (U+1369–U+1371) on 1–9 and **፲** (U+1372) on
  the 0 position (`Layouts.digitKey`, `EthiopicNumerals`).
- Ethiopic has no zero and no place value (2017 = ፳፻፲፯). The 0 position takes ፲: it is the numeral that follows ፱, and
  with it every numeral is one tap or one long-press away. Long-press: the **Western digit first** (also the corner hint),
  then the layout pack's alternatives: the ten of each digit on 1–9 (፲ on 1, ፳ on 2 … ፺ on 9) and **፻ ፼** on the ፲ key.
  Without a layout pack the same table comes from `EthiopicNumerals.alternatives` (no pack data changed).
- WESTERN keeps the old behaviour exactly (1…0, no popup). The old "ETHIOPIC offered on long-press" mode is gone: ETHIOPIC
  now means "Ethiopic primary, Western on long-press".
- Unchanged: the number / phone / date dial pad, the English (ABC) writing mode of am/ti, om, en, and ar (Western digits
  with the Arabic-Indic digit on long-press). Nothing outside the keyboard changes.
- **Fidel follows the same setting.** The setting has always applied to both native modes (it never distinguished them),
  the Fidel grid's `?123` page is where its users find digits (the grid has no digit row), and a Fidel user is the one most
  likely to want Ge'ez numerals; one switch is simpler to explain than two.
- Prediction (ADR 0039): a numeral is not a letter (`Char.isLetter` false, so Shift ignores it). In Phonetic it is not a
  letter key of the profile, so it ends the word: the word is converted and committed, then the numeral; it never enters
  a composition or a candidate. The core's word context treats it like a Western digit (alphanumeric: a word glued to a
  number is never autocorrected), and the word learner never learns a token with a numeral. No core change was needed.

### 2. Ethiopic numerals: the default and the migration
`numeralStyle` is one global, synced preference (`/me/preferences`), stored by `SettingsRepository` under `numeral_style`.
Every settings write stores every field, so for an existing install a stored WESTERN cannot be told apart from "never
chose" (the old default was written on the first save, e.g. the first layout switch). Decision:
- New device-local flag `numeralStyleChosen` (`numeral_style_chosen`, additive, schema stays 1).
  `effectiveNumeralStyle = if (chosen) numeralStyle else ETHIOPIC`. The keyboard and the settings page use the effective
  value; picking a style on the settings page sets it and the flag.
- Files written before the flag: a stored **ETHIOPIC** counts as chosen (it was never a default, so the user picked it);
  a stored **WESTERN** counts as not chosen and moves to the new default. A user who had deliberately picked Western on
  the old page is indistinguishable and gets Ethiopic once; the settings page ("Numerals") and its note bring Western
  back in one tap. Choices made from now on are kept.
- Sync: unchanged contract (WESTERN | ETHIOPIC). A value adopted from the server that differs from this device's was
  picked on another device and counts as chosen here. A device that never chose still sends nothing new (its stored
  value is the old default), so it does not overwrite another device's choice. Known gap: picking WESTERN explicitly
  on a device whose stored value was already WESTERN is not a change the three-way merge sends.
- `ModePreferences.ethiopicNumerals` stays false by default (the plain in-memory test preferences); the product default
  is the settings' `effectiveNumeralStyle` read by `SettingsPreferences`.

### 3. Latin key arrangements (per language)
`LatinLayout`: **QWERTY** (default), **AZERTY**, **QWERTZ**, **Dvorak**, **Colemak**. One choice per language
(`KeyboardSettings.latinLayouts`, device-local), applied to all its Latin layouts: English, Afaan Oromoo (Qubee), the
Phonetic and English writing modes of Amharic and Tigrinya, and the ABC layout any field forces (e-mail, password).
Each one has a real benefit: AZERTY and QWERTZ are the muscle memory of diaspora users in France / Belgium and in
Germany / Austria / Switzerland / Central Europe (large Ethiopian and Eritrean communities), Dvorak and Colemak are the
two established ergonomic layouts their typists will not give up. Names are proper names and are not translated.
- English names its arrangement on the space bar and to TalkBack (`EN · Dvorak`), and the app's language list too.
- Rows: the arrangement's three letter rows; a row shorter than ten keys is centred between spacers (QWERTY's middle row
  as before); Shift and Delete share what the bottom letters leave, never narrower than 1.25 keys. QWERTY builds exactly
  the previous rows (tested).
- Phonetic hints follow the letter (each key looks its own character up), Shift hints too. Transliteration works by
  character, so typing `selam` gives the same text on every arrangement (tested with the real core).
- Qubee's hudhaa `'` stays on the letters page: after the bottom letters, unless the arrangement already has an
  apostrophe key (Dvorak's top row). Dvorak's `'` `,` `.` keys type plain ASCII, as on a Dvorak keyboard; the bottom
  row's Ethiopic `።` / `፣` keys are unchanged.
- Rejected: Arabic arrangements (ADR 0034: one standard Arabic layout), language-specific Latin layouts with extra letters
  (ä ö ü …: none of our typing languages needs them; long-press can be added later), and a Workman / Neo / BÉPO list
  (too few users to justify the choice list).

### 4. Number row
`KeyboardSettings.numberRow` (device-local, default **on** = the existing layout). Off: the letters page has four rows
(the keyboard gets shorter, as in Gboard) and the top letter row's first ten keys offer the digit-row key's choices
first in their long-press popup (Ethiopic numeral then Western digit in am/ti, the Arabic-Indic digit in Arabic); the
digit is the corner hint unless the key shows a phonetic hint. The symbols pages keep their digit row. Colemak's top
row has nine letters: 0 stays on `?123`. Switch: Settings → Preferences → "Number row".

### 5. Symbols / punctuation
No new symbols page: am/ti native modes already have the Ethiopic-punctuation-first behaviour (`.` / `,` tap ። / ፣, the
marks row on `?123`) and Arabic its own; no further variant had a clear benefit.

### 6. Fidel grid variants
Candidates checked: (a) the current two-key grid, (b) a **long-press-only** grid, (c) a compact / frequency-ordered grid,
(d) homophone series behind long-press (ADR 0007 L3).
- Implemented: **(b) `FidelVariant.LONG_PRESS`** ("Long-press grid"). It is derived from the existing packs, which
  already declare both `LONG_PRESS` and `TWO_KEY` interactions (spec §4: "in a LONG_PRESS-only layout a tap sends
  `Char { tap_text }`"), so no layout data or signed pack changes. Tap types the sixth-order form (ስ: the consonant on its
  own, the most frequent order in running text) and the key shows it; long-press offers the other orders and the
  labialised forms; no vowel-order row, so the five grid rows are taller (49 dp instead of 42 dp: the keyboard keeps its
  height). Benefit: one tap instead of two for the commonest syllables, and bigger keys. The two-key grid stays the
  default (ADR 0007). `KeyLayouts.apply` drops TWO_KEY only when the pack offers LONG_PRESS.
- Not implemented, left for the **G0 panel** (they need native-speaker and frequency decisions, not code): (c) a
  frequency-ordered key order (needs corpus counts per series and the panel's judgement of findability vs speed), (d)
  homophones behind long-press, and a first-order (ሀ-style) default for the two-key grid. Each is layout data in a new
  pack version; the variant mechanism (`fidelLayouts` per language, the Layout panel) is in place for them.

### 7. The Layout tile and panel
`Tool.LAYOUT` (icon: two offset rows of filled keys over a space bar), after Writing mode in the tools grid, offered when the current language
has at least two layouts in this field (`ToolContext.layouts`; not Arabic). It opens `ToolPanel.LAYOUT` /
`LayoutPanelView` in place of the keys at their height, the bar showing the back arrow and "Layout · <language>" (the
language's own name). One row per layout: name with a small preview beside it (the top letter row; the Fidel variant's way
to type ሱ), a one-line description (UI language), the one in force checked; TalkBack reads each row as a radio button. Fidel mode lists
the Fidel variants, every Latin mode the arrangements.
- Choosing applies at once (`KeyboardController.selectLayout`), is saved per language (`SettingsPreferences` keeps the
  choice in memory until the store answers, so the keys never flash the old layout) and returns to the letters page.
- Composition: opening the tools grid already commits the open composition as it stands (ADR 0035
  `closeOtherPanels`), and `selectLayout` commits again if one is open (a direct call): the composed text stays in the
  field exactly once, nothing is lost or retyped (tested with the real core). The current layout or a stale tap changes
  nothing; Back (arrow or system) returns to the same keyboard.
- The companion app's language page shows the same choices ("Key layout", and "Fidel keys" for am/ti) with the same
  names and descriptions (`layoutName` / `layoutDescription` from the IME).

## Consequences
- New device-local settings keys: `numeral_style_chosen`, `number_row`, `latin_layouts`, `fidel_layouts` (additive,
  schema 1, sanitised like `writing_modes`). Nothing new is synced.
- Strings: the panel, descriptions and settings have DRAFT am/ti/om translations (ADR 0032, rule N9).
- Device checks still needed: Ethiopic numeral glyphs at key size on low-end devices (Noto Sans Ethiopic), long-press
  popups on the top row with the number row off, the Layout panel's height and scrolling at 360 dp, and TalkBack on it.

## Amendment (2026-11-20, ADR 0044): Western digits first by default
The product owner reversed the default of §1–§2 after device QA: **Western digits are the primary output everywhere**,
Ethiopic numerals secondary. What changed, and what did not:
- `numeralStyle` keeps its two values with these meanings on the am/ti native layouts (Phonetic and Fidel; letters page,
  both symbols pages, and the top letter row's long-press with the number row off):
  - **WESTERN (the default)**: the keys type 1…9, 0; long-press offers the Ethiopic numeral first (፩…፱, ፲ on 0; also the
    corner hint), then the pack's tens (፲…፺) and ፻ ፼ on 0, as before.
  - **ETHIOPIC**: exactly §1 (the numeral types, the Western digit first on long-press and as the hint).
  The old "WESTERN = plain digits, no popup" mode is gone on am/ti native layouts: Ethiopic numerals are always one
  long-press away there. English (ABC) of am/ti, om, en, ar and the number / phone / date pads are unchanged.
- `KeyboardSettings.DEFAULT_NUMERAL_STYLE` is WESTERN; `effectiveNumeralStyle = if (numeralStyleChosen) numeralStyle else
  WESTERN`. Migration: only a recorded choice of ETHIOPIC (`numeral_style_chosen` = true and `numeral_style` = ETHIOPIC)
  keeps Ethiopic first. Everyone who never picked a style (the ADR 0043 default users) is back on Western first. Files
  without the flag (written before ADR 0043) now count as **not chosen**, whatever they store: their stored ETHIOPIC
  meant "Ethiopic offered on long-press", which is exactly today's WESTERN. A file written by an ADR 0043 build that
  inferred the flag from a stored ETHIOPIC and saved it is indistinguishable from an explicit pick and keeps Ethiopic first
  (one tap on the settings page changes it).
- Sync is unchanged (WESTERN | ETHIOPIC; a different value adopted from the server counts as a choice).
- Settings → Preferences → Numerals: "Western first" (1 2 3 · ፩ ፪ ፫) and "Ethiopic first" (፩ ፪ ፫ · 1 2 3), with a note
  that the other style is on long-press and in the key's corner (DRAFT am/ti/om strings).
