# ADR 0030 — Word Art (decorative text styles)

**Status:** Accepted · **Date:** 2026-11-18 · **Plan steps:** keyboard UI + companion app (owner request: decorative text for birthdays, congratulations, love, wedding, thank-you, holiday and festival greetings, announcements, captions and names, for Amharic, Tigrinya, Afaan Oromoo and English)

## Context
Users want to send visually interesting text. The easy answer, the Unicode "fancy letters" (bold, script, fraktur, circled, ...), is
Latin-only: the Mathematical Alphanumeric Symbols, fullwidth, circled and small-capital blocks exist for A-Z, a-z and 0-9 only, so
applying them to Amharic or Tigrinya (Ge'ez) does nothing, and per-letter combining marks on Ethiopic were never verified to
render. Joining scripts (Arabic) break outright under per-letter marks or spacing. The feature therefore has to be built around
what each script can safely take.

## Decisions

**Engine (`:ime`, package `wordart`, pure Kotlin, no Android classes).** `WordArt.styles` is a data table
(`WordArtCatalog`); a `WordArtStyle` has an id, category, English label, `StyleKind`, optional `Occasion`, `support(ScriptInfo)` and
`apply(text, ScriptInfo)`. `ScriptInfo.of(text)` counts code points per script (ASCII letters and digits, Ethiopic, joining scripts,
complex-shaping scripts, strong right-to-left, other letters). Because `:app` depends on `:ime`, the companion app uses the same
engine. A style's `support` is `FULL`, `PARTIAL` (a letter style on mixed text: only the Latin part is changed) or `NONE`
(unchanged text is returned and the UI shows the style disabled with "Latin letters only").

Four kinds, by what they may touch:
1. **Letter styles** (`LETTER`): replace ASCII letters and digits with Mathematical Alphanumerics (bold, italic, bold italic, script,
   bold script, fraktur, bold fraktur, double-struck, sans, sans bold, sans italic, sans bold italic, monospace), circled, black
   circled, squared, black squared, parenthesized, fullwidth and small capitals. The reserved holes use the Letterlike block
   (script: B E F H I L M R, e g o; fraktur: C H I R Z; double-struck: C H N P Q R Z; italic h = U+210E). Everything that is not
   ASCII (Ethiopic, Arabic, accented Latin, CJK, emoji, punctuation) passes through unchanged. Small capitals use the nearest letters
   for q (ǫ) and x (kept as x), which Unicode has no small capital for.
2. **Combining overlays** (`MARK`): underline, double underline, strikethrough, slash, overline, after each ASCII letter or digit.
   **Latin only.** Combining overlays on Ethiopic were not tested on real devices and the research note treats them as
   unverified, so they are *not* offered for Ge'ez (no "experimental" tier was added: nothing justifies it yet).
3. **Spacing** (`SPACING`): grapheme clusters separated by a space, a thin space (U+2009) or a middle dot. Works for Latin and
   Ethiopic and any other non-joining script; refused for Arabic, Syriac, Mongolian, N'Ko, Indic, Thai, Khmer, Myanmar and Tibetan.
   The cluster splitter is our own (`Graphemes`, base plus Mn/Mc/Me marks, variation selectors, skin tones, tags, ZWJ joins, flag
   pairs), not `java.text.BreakIterator`, so JVM tests and Android agree and emoji ZWJ sequences and Ethiopic with combining marks
   are never torn.
4. **Compositions** (`COMPOSITION`): ornament wraps (`✿ text ✿`, `★彡 text 彡★`, `❀•°• text •°•❀`), brackets (【】『』〔〕), dividers and
   banners (the same line above and below, multi-line), occasion garnish in emoji, and the Ethiopic marks ፠ ፨ ፡፡ as ornaments.
   They never touch the letters, so they work for every script. **No box-drawing frame that needs fixed-width alignment** is used:
   proportional fonts and wide Ge'ez glyphs would misalign it, so frames are side ornaments and fixed top/bottom lines whose
   length does not depend on the text. A right-to-left line is wrapped in a bidi isolate (U+2068 ... U+2069) so Arabic or Hebrew
   neither scrambles nor is scrambled by the ornaments; LTR and Ge'ez text gets no invisible characters. Some compositions also
   apply a letter alphabet to the Latin part (bold banner, script signature); those are the only compositions that alter letters.

Catalogue: 28 letter, mark and spacing styles plus 91 compositions (119 styles) in 11 categories (Popular, Birthday, Love, Wedding,
Congratulations, Thank you, Holidays and festivals, Announcements, Social captions, Names, Letter styles). Popular is a view of the
styles flagged popular. Ethiopian occasions have their own decorative sets, kept to garnish and not religious statements:
Enkutatash 🌼 (Adey Abeba flower), Meskel 🔥✝️, Genna 🎄, Timket 💧✝️, Fasika 🕊️, Eid 🌙, Irreecha 🌿, plus a neutral green, yellow
and red hearts set. Every category has styles that serve Ge'ez text.

**Greetings.** A styled occasion can add a built-in greeting line in the chosen language (am, ti, om, en) above the user's text.
The phrases live in one table (`Greetings`, one entry per occasion and language) so the language panel can correct them without
touching the engine. **Every non-English phrase is DRAFT (rule N9)** and the UI says so (a note in the card and in the app).
The greeting is part of the text the style is applied to, so a letter alphabet also restyles an English phrase and passes a Ge'ez
phrase through. The language is the keyboard's (the app has a picker); other languages fall back to English. While nothing is typed
the previews show a sample name in the language (`Your Name`, `ሰላም`, `Caalaa`).

**Keyboard UI.** A tab labelled `Aa✨` is added at the end of the emoji panel's tab row (the panel is the expressions panel, so no
new key): the row now has the search entry, the categories and this tab. `EmojiMode` gained `WORD_ART` and `WORD_ART_TYPING`.
`WordArtPanelView` replaces the key area at the same height (like the emoji panel): ABC, a text field, the Greeting switch, category
chips and the style list, built from ordinary views (a `ListView` composes only visible rows; the engine is string work, measured
in tests at well under a millisecond per style). Tapping the field enters `WORD_ART_TYPING`: the keys come back and
`KeyboardController.apply` sends the composer's output to `WordArtController.sink` (the same mechanism and the same exclusions as
the emoji search: not learned, no auto-capital, Enter does not reach the host), and a `WordArtBar` (the typed text and Done) takes
the bar's place; Done, Enter, the emoji key and system Back return to the panel with the text kept. The field starts from the host
field's selected text (`getSelectedText`), else empty. Tapping a style opens a card with the large preview, **Insert** (a normal
`commitText`, which replaces the selection) and **Copy** (`ClipboardManager.setPrimaryClip`, a plain-text clip, **not** marked
sensitive; the panel shows "Copied", and Android 13+ adds its own confirmation). Nothing is inserted or copied while only the sample
is shown. Styles that cannot serve the text are listed last and disabled ("Latin letters only"); a style that cannot be selected
cannot be inserted. Word Art does not open in the fields where the emoji panel does not (password, number, ...). The text is held
in memory only, forgotten when Word Art closes, never logged, stored or learned.

**Companion app.** `WordArtScreen` (Compose), reached from a card on the settings screen: text field, greeting switch with a
language picker, category chips and a lazily composed list of previews, each with Copy (clipboard, same non-sensitive clip) and
Share (`ACTION_SEND` chooser).

**Accessibility.** Screen readers read Mathematical Alphanumerics badly, so every preview row, card preview and app card has a
content description of the style name plus the *plain* text (`Bold: Abebe`, `Fraktur style preview: Abebe`), with the decorated
string hidden from accessibility; disabled styles say why; the Greeting switch exposes a state description; "Copied" is a live region.

**Theme.** Only existing `KeyboardTheme` tokens are used (`key`, `special`, `accent`, `onAccent`, `stripBg`, `stripText`, `keyText`,
`hintText`), so `ThemeContrastTest` is unaffected.

## Consequences
- Latin-only styles are shown disabled for Ge'ez text; this is deliberate (the alternative, silently doing nothing, looks broken).
- Emoji garnish and the CJK `彡` depend on the device font; devices without them show the tofu box. The emoji panel has the same
  dependency and already probes glyphs; Word Art does not probe (the styles are short, decorative and user-chosen).
- Style names are English only for now; category titles and all UI strings are resources.

## Open
- Native-speaker review of every am/ti/om phrase (DRAFT), and of Afaan Oromoo `Ayyaana ...` spellings.
- Rendering of the styles on real devices and in the common messengers (some apps strip or substitute decorative letters).
- Whether to offer combining overlays for Ge'ez after a device test.
