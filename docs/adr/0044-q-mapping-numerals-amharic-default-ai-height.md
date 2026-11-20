# ADR 0044 — Capital Q in Amharic, Western digits first, Amharic opens in Phonetic, taller AI panels

**Status:** Accepted (profile and strings DRAFT until the G0 panel) · **Date:** 2026-11-20 · **Requested by:** product
owner · **Benchmark:** Google Gboard (patterns, not branding) · **Amends:** ADR 0043 (numerals default) ·
**Builds on:** ADR 0007 (Phonetic first), ADR 0032 (UI strings), ADR 0035 / 0041 (tools grid, AI panels), ADR 0038,
`docs/specs/phonetic-coverage-am-ti.md`

Four refinements after device QA of round 2. Each section records the cause found in the code and the decision.

## 1. Capital `Q` is the ቐ series in Amharic Phonetic too (am 0.4.0)
**Cause.** Tigrinya mapped `Q` to the ቐ series in ti 0.4.0; the Amharic profile (0.3.0) had no `Q` rule, so case folding
turned `Q` into `q` (ቅ). The core's Fidel table already has the `QH` / `QHW` series for every language.

**Decision.** `am-phonetic` **0.4.0** (DRAFT): `{"in": "Q", "series": "QH", "alt": ["Q"]}` and `"QH": "QHW"` in
`labializedSeries`, exactly as Tigrinya. `Qe Qu Qi Qa Qee Q Qo` give ቐ ቑ ቒ ቓ ቔ ቕ ቖ (ቀ… as the strip alternative),
`QWe QWi QWa QWee QW` give ቘ ቚ ቛ ቜ ቝ. Lowercase `q` and `k'` keep ቀ with no new alternative, so no existing Amharic
output or strip changes; the shifted `q` key shows ቕ (hints are derived from the engine). The ቐ forms are not Amharic
orthography: they are listed in the am coverage matrix beyond the required forms (checked the same way) and the Amharic
Fidel grid keeps no ቐ key. The am rule pack was rebuilt with the dev key (`build_seed_packs.py`, `--check` green).
Golden vector `capital-q-is-qh-in-every-order` (`core/tests/golden/am-phonetic-0.4.0.json`, which retires 0.3.0).

## 2. Western digits first, Ethiopic numerals on long-press (amends ADR 0043)
**Cause.** ADR 0043 made ETHIOPIC the effective default (`DEFAULT_NUMERAL_STYLE`) and treated every unchosen device as
Ethiopic-first; WESTERN meant plain digits with no Ethiopic at all on the keys.

**Decision.** One setting, two meanings on the am/ti native layouts (Phonetic and Fidel, digit row, `?123` pages, and
the number-row-off long-press of the top letter row): **WESTERN (new default)** = 1…0 on the keys, the Ethiopic numeral
(፩…፱, ፲ on 0) first on long-press and as the corner hint, then the tens ፲…፺ and ፻ ፼ as before; **ETHIOPIC** = the
ADR 0043 behaviour. `LayoutContext.ethiopicNumeralKeys` (native am/ti) decides that numerals are offered,
`ethiopicDigits` (ETHIOPIC chosen) which one types. English (ABC), om, en, ar and the dial pads are unchanged; Ethiopic
numeral support is never removed. Migration: `effectiveNumeralStyle` is ETHIOPIC only when `numeral_style_chosen` is
true and the stored style is ETHIOPIC; everyone else (never chose, or a pre-ADR-0043 file without the flag, whose stored
ETHIOPIC meant "on long-press") is Western first. Details in the ADR 0043 amendment. Settings labels: "Western first" /
"Ethiopic first" with samples `1 2 3 · ፩ ፪ ፫` / `፩ ፪ ፫ · 1 2 3`.

## 3. Amharic opens in Phonetic, and a deliberate choice sticks
**What was checked.** Every first-run path already gives Phonetic and is now pinned by tests: fresh install (settings
defaults, before and after the core loads, nothing remembered on the user's behalf), onboarding (its default draft, a
state restored after process death, the saved result), Amharic enabled later in Languages or made primary, the first
switch to it by the space-bar picker or the globe, the typing-language sync's first-run base (Phonetic). `LanguageCatalog`
lists Fidel first only for the writing-mode chooser; nothing picks its `first()`. Latin → Ge'ez works in the resulting
default (`selam` → ሰላም, core and controller tests).

**Cause found.** The keyboard remembers the layout picked on it (`writingModes`, `lastModes`, ADR 0007) and that wins over
the language's setting (`inputMode`). The app showed and edited only the setting: once Fidel or English (the ABC toggle)
had been picked on the keyboard, the language page and an onboarding re-run still showed "Phonetic", picking Phonetic
there changed nothing (already selected, or `inputMode` only), and a running keyboard ignored setting changes after its
first load anyway. So Amharic kept opening in Fidel / English with no way back from the app.

**Decision.**
- `KeyboardSettings.modeInForce(code)`: the mode the keyboard opens in, resolved in the IME's order (writing mode, last
  native layout, setting). The language list, the language page and the onboarding draft show it.
- `KeyboardSettings.withLanguageMode(code, mode)`: a pick in the app (language page, onboarding) sets the setting and
  replaces the keyboard's memory for that language (English/Latin in am/ti = the ABC writing mode). Onboarding applies it
  only to languages whose mode the user changed there; everything else keeps its memory.
- `KeyboardController.onPreferencesChanged`: when the settings give the current language another layout than at the last
  change, and it is neither the layout in force nor the store's echo of a switch made on this keyboard, it was picked in
  the app: the keyboard commits any composition and follows at once (one switch, no loop). Its own switches are never
  undone by late echoes, unrelated setting changes leave the layout alone.
- A layout picked deliberately on the keyboard (writing-mode chooser, globe long-press, ABC toggle) still persists across
  restarts; the latest deliberate choice wins wherever it was made. ti, om, en, ar defaults are unchanged.

## 4. AI panels a little taller than the keyboard; quick-action buttons always fit
**Cause.** The AI tools grid and the AI Assist / Write Email panels measured exactly `KeyboardSurface.preferredHeightPx()`
(the key area). The quick actions' review put its five buttons (Replace, Insert, Copy, Retry, Cancel) in a sideways
`HorizontalScrollView` with no scroll hint, so at 360 dp the last one ("Cancel") was cut at the right edge.

**Decision.**
- `KeyboardMetrics.aiPanelHeightPx`: key area + 18 % of it, kept within **56–72 dp** in portrait; landscape 10 % (at
  most 32 dp). The whole keyboard (bar + panel + inset padding: navigation bar, cutout, bottom gap) stays within **60 %**
  of the window height in portrait and **50 %** in landscape; the panel is never shorter than the key area. Typical
  phones: +56 dp at 411 × 891 dp; a 360 × 640 dp phone gets what the cap leaves (≈ +24 dp with 3-button navigation, +48 dp with gestures); a phone on its side gets
  nothing extra. `aiPanelHeightInWindowPx` adds the bar and the column's inset padding.
- Applied to the AI tools grid (ADR 0041) and to AI Assist and Write Email in every step (compose, working, result): they
  share one height, so moving between them never resizes the keyboard; keys ↔ AI is one height change. The height is a
  pure function of stable values (key layout, fixed bar height, padding already applied by `KeyboardInsets`, display
  size), never of the panel's own size: no layout loop, insets applied once as before. The typing keyboard, the tools,
  clipboard, layout, emoji and voice panels keep the key area's height.
- Content scrolls: the grid's scroll view and the panels' body (header and footer fixed). The AI panels' footer keeps its
  sideways scroll and now shows a fading edge where buttons continue.
- The quick actions' review panel sits **above** the bar with the keys still below it (it is not in place of the keys),
  and with it the keyboard already exceeds the 60 % cap on 360–411 dp phones, so its height is unchanged. Its button rows
  now **wrap** (`WrapRow`) instead of scrolling: every button is visible at 360 dp in English, Amharic, Tigrinya and
  Afaan Oromoo (tested), a chip wider than the whole row would ellipsize.
- The translate panel (above the keys, keys visible) is unchanged, like the quick actions.
- No new animation: the height changes at once; the panels' existing reveal (`Motion`) already honours "Remove
  animations".
