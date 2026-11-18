# ADR 0028 — Emoji panel

**Status:** Accepted · **Date:** 2026-11-18 · **Plan steps:** keyboard UI (owner request: emoji with categories, recents, search, skin tones, all layouts)

## Context
The keyboard had no emoji entry. The owner asked for an emoji panel that follows Gboard / Samsung conventions: categories,
recently used, search, insertion into the active field, working in every layout (am/ti Fidel and phonetic, om Qubee, en,
numbers/symbols), styled by the keyboard themes, and no cost to the time the keyboard takes to appear.

## Decisions

**Entry point.** An emoji key (a drawn smiley, like the drawn globe) sits **directly left of the space bar** on the letters
bottom row of plain text fields, and on both symbols pages. It is shown only where `InputProfile.allowsEmoji` (plain text
field with a text model): never in password, number, phone, date, email or URI fields, and never for `TYPE_NULL` hosts. Those
fields keep their current bottom row exactly. Making room: the key weighs 0.9 and the space bar goes from 3 to 2.75, so the
space bar keeps at least 27 % of the row in every letter layout (a test holds it at 25 %). A comma/period long-press shortcut
was not added (the period key's long-press already carries the Ethiopic marks).

**Panel.** It replaces the key area at the **same height** (`KeyboardSurface.preferredHeightPx`, so the keyboard does not
jump); the bar (mode pill, AI, candidates, gear) stays. A tab row on top (search entry, then Recent, Smileys & Emotion,
People & Body, Animals & Nature, Food & Drink, Travel & Places, Activities, Objects, Symbols, Flags: a drawn magnifier and
clock, the other tabs show a representative emoji, all with spoken names), one continuous scrolling grid with a header per
category (a tab jumps to its section and the highlighted tab follows the scroll, as in Gboard), and a bottom row: ABC (back to
the keys), space, backspace (repeats on hold), enter (same action as the keys). The grid is **custom-drawn** on one Canvas
(`EmojiPanelView`) rather than a RecyclerView: no new dependency, no view per cell, one `drawText` per visible cell, a fling
scroller, and the same accessibility approach as the key surface (`ExploreByTouchHelper` virtual views). Backspace goes
through the composer and `InputConnectionAdapter`, which already deletes whole extended grapheme clusters (ZWJ sequences,
flags, skin-tone forms) through `BreakIterator`, so one press removes one emoji.

**Search.** The magnifier opens a search state in which **the normal keys come back** and a strip replaces the bar: back
arrow, the query box, then the matching emoji in one horizontally scrolling row (the recents while the query is empty).
`KeyboardController.apply` sends the composer's output to `EmojiController.querySink` instead of the host while the search is
open, so Fidel, phonetic, Qubee and English all type into it (the composer commits Ge'ez text; the sink appends it, deletes by
grapheme cluster, and shows a composition in progress). In that state Enter only commits the composition, auto-capitalisation
is off, and **nothing is learned** into the personal dictionary. Tapping a result inserts it into the host field (the query
stays); the arrow, the emoji key and system Back return to the panel (the query and any composition are dropped, never sent
to the host). Phonetic candidates are not shown during the search (the bar is replaced); the composition commits on space.
Matching: every query word must match; a term scores exact, prefix, word-prefix, then substring (three letters or more), so
prefix matches always come first; ties keep the panel order. Terms are the CLDR English name and keywords always, plus the
keyboard language's annotations. CLDR has annotations for **am, ti and om** as well as en (the research notes listed ti/om as
unconfirmed: they exist, coverage am 99 %, ti 99 %, om 84 % of the emoji), so all three languages search natively.

**Recents and tones.** The last 32 emoji inserted, newest first, and the skin tone last chosen per base emoji, are stored in
the settings store (`KeyboardSettings.recentEmoji`, `emojiTones`; additive DataStore keys, schema stays 1, sanitised on every
write). They are device-local: `PreferencesSync` copies named fields only, so they are never synced, and nothing is logged.
They are read on first use (the settings snapshot is still the defaults when the keyboard starts). Long-pressing an emoji that
has tones opens the six forms in the shared `PopupOverlay`; the choice is inserted and remembered, and later taps and search
results use it. For TalkBack each tone is a custom action on the cell. The Recent section is rebuilt when the panel opens, not
while it is open, so it does not reshuffle under the finger.

**Data.** `android/ime/scripts/build_emoji_data.py` builds the assets in `android/ime/src/main/assets/emoji/` from Unicode
`emoji-test.txt` (v18.0) and the CLDR annotations (cldr-json, en/am/ti/om). Run it with `--download` or with local files
(usage in the script header); the generated files and the script are committed. Only fully-qualified emoji are kept (1,923);
the Component group is dropped; an emoji whose five uniform skin-tone forms exist is one entry with those variants (sequences
mixing two tones are dropped); entries follow CLDR order within their group. `emoji.tsv` (English: text, name, keywords,
tone variants) is 133 KB; `emoji-am.tsv` 175 KB, `emoji-ti.tsv` 160 KB, `emoji-om.tsv` 106 KB are index-aligned with it
(only the keyboard language's file is loaded, so at most about 310 KB is parsed). The APK compresses assets. The Unicode
License v3 text is shipped next to the data (`UNICODE-LICENSE.txt`).

**Rendering and loading.** No emoji font is bundled. `Paint.hasGlyph` filters out what the device cannot draw, on the loading
thread; the unsupported set is cached in `cacheDir` keyed by `Build.FINGERPRINT` plus the data version, so the check runs once
per system build. Nothing loads on the keyboard's start-up path: the first open of the panel parses the English data on a
worker thread (`EmojiExecutor`) and shows an empty grid until it arrives; the language file loads the same way when the
panel or the search needs it. `EmojiController`, `RecentEmoji` and the store are constructed with the keyboard but touch
neither assets nor settings until then.

**Insertion.** `EmojiController.insert` commits any open composition first (in the panel), then commits the emoji to the host
through the same `TextSink` / `InputConnection` path as every other commit, bypassing the composer, so an emoji never reaches
the word learner or the personal dictionary and ends the current word. While searching, the composition belongs to the query
and is left alone.

**Back.** `KeyboardImeService.onKeyDown(BACK)` steps out of the search, then out of the panel, before the framework dismisses
the keyboard. On Android versions where back is delivered as a predictive-back gesture instead of a key event the keyboard is
dismissed as usual; the ABC key and the arrow are the dependable exits.

**Themes and accessibility.** Only existing tokens are used (background and gradient, `stripBg`/`stripText`/`stripDivider` for
the tab row and the search strip, `key`, `special`, `pressed`, `accent`/`onAccent`/`accentText`, `hintText`, `popupBg`
through the overlay), so no new colour pair needs a contrast rule. Every tab, key and emoji is an accessibility node with a
role and a label (an emoji reads its CLDR name, with its tone), the grid exposes scroll actions, and the search query is
announced as the strip's description.

**Network.** The `ime` module stays network-free (`NoNetworkTest`); the data is bundled.

## Consequences
- A weight change on the TEXT bottom row (space 3 to 2.75) and one more key; other field types are untouched.
- Emoji data is regenerated by script when Unicode or CLDR releases; the loader tolerates new groups it does not know
  (they are skipped) and a missing language file (English search only).
- The grid is not virtualised by a RecyclerView: with about 1,900 cells the model is cheap, and only visible rows are drawn.
- Search sorts about 1,900 entries per key press on the main thread (a few milliseconds); an index structure is a follow-up
  only if profiling asks for it.
- Not done: Gboard-style emoji suggestions in the candidate strip, emoji kitchen/stickers, GIFs, a comma/period shortcut,
  per-person (mixed-tone) couple emoji, and search-time phonetic candidates.
