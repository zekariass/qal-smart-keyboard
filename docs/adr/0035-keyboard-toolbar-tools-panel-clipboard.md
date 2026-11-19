# ADR 0035 — Keyboard toolbar, tools panel and clipboard (Gboard benchmark)

**Status:** Accepted · **Date:** 2026-11-19 · **Requested by:** product owner · **Benchmark:** Google Gboard (its
patterns, not its colours or branding) · **Builds on:** ADR 0003 (custom-View surface), ADR 0007 (D40 plan state, D45
one bar), ADR 0028 (emoji panel), ADR 0029 (voice typing), ADR 0030 (Word Art), ADR 0031 (AI panels, translation
direction), ADR 0032 (UI localisation), ADR 0034 (Arabic), ADR 0036 (settings) · **Contract:** none

> **Amended by ADR 0041:** the AI entry is an "AI" mark (not the sparkle) that opens the AI tools grid in place of the
> keys; the AI actions row in the bar's middle, its close (×) button and the tone chip are retired. The tools panel has
> one AI tile ("AI tools") instead of AI Assist and Write Email, which are tiles of the AI grid. The tables below that
> mention the AI row describe the state before ADR 0041.

## Context
The bar above the keys (D45) had grown one feature at a time: the writing-mode pill, a text "✦ AI" chip, the middle,
the microphone and a gear. Every other tool lived somewhere else: Word Art behind the emoji panel, the e-mail writer and
AI Assist behind the AI chip, themes and languages only in the app. The clipboard was not reachable at all outside the
AI panels. The bottom row had eight keys (`?123`, globe, ABC, comma, emoji, space, period, Enter) and text glyphs for
shift, delete and Enter that looked different from the drawn globe and emoji.

The product owner asked for a Gboard-quality toolbar: clean modern icons at proper sizes and spacing, a clear
hierarchy, intuitive navigation, smooth expansion and collapse, consistent active and inactive states, easy access to
frequent tools, no clutter, every tool easy to discover, and no feature removed to simplify.

## Decisions

### 1. Patterns taken from Gboard
- A **tools button** (2×2 grid) at the bar's left opens a **tools panel** in place of the keys: every tool is a
  labelled tile, so nothing is hidden behind another feature.
- A few **pinned shortcuts** on the idle bar, evenly spaced, one outline icon each; the **microphone at the right end**.
- **Candidates take the bar while composing**; the tools button stays at the left, so tools are one tap away.
- While a tool panel is open the bar shows a **back arrow and the panel's title** only.
- A **clipboard panel** with recent copies.
- A **bottom row of seven keys** and **icon-drawn function keys**.

Not taken: Gboard's toolbar customisation (drag to reorder pinned tools) — the set is fixed for now (deferred, §9);
Gboard's colours, its G-logo button and sticker/GIF tools.

### 2. One icon family
`ToolIcons` draws every icon of the keyboard on a 24-unit grid with a 2-unit round-capped stroke (Material-outline
proportions; the translate mark is Material's, Apache 2.0): tools, back, close, translate, AI sparkle, clipboard,
emoji, mic, settings gear, theme palette, languages globe, e-mail, Word Art, writing mode (keyboard), lock, check,
grammar, rewrite, and the special keys (shift off / on / caps lock, delete, Enter's return, search, send, go, next,
previous, done). Paths are built once and scaled on the canvas: no image resources, no icon library, no allocation per
draw. Colours are always the caller's theme tokens. Sizes: bar icons 22 dp in 48 dp targets, tool tiles 24 dp on a
52 dp rounded square, chip icons 18 dp, key icons half the key's short side up to 24 dp.

### 3. The bar (`CandidateStripView`, `KeyboardBar`): 44 dp, one height in every state
The bar grows from 40 to **44 dp** so 22 dp icons and 34 dp chips get Gboard-like room; it is still one fixed height in
every state, field and theme, so the keyboard never changes height when tools, modes or fields change (D45 holds; the
keyboard as a whole is 4 dp taller than before).

| State | Left | Middle | Right |
|---|---|---|---|
| idle | tools button, writing-mode pill (am/ti) | pinned shortcuts | mic |
| composing (phonetic) | tools button, pill | DRAFT tag, candidates | mic |
| writing-mode chooser | tools button, pill | Fidel / Phonetic / English | mic |
| AI actions open | tools button, pill, close (×) on the accent | the AI actions row (scrolls) | mic |
| tools or clipboard panel open | back arrow | the panel's title | — |
| sensitive field | tools button | only the shortcuts the field allows (§5) | mic where allowed |

- **Shortcuts**, in priority order: Translate, AI, Clipboard, then Settings, Theme, Emoji. As many as fit at 48 dp are
  shown, evenly spread (never more than 72 dp apart, the group centred): at 411 dp with the pill and the mic that is
  Translate, AI, Clipboard, Settings; at 360 dp Translate, AI, Clipboard. Every one is also a tile (§4). Voice is never
  a shortcut: the mic has its own place.
- **States.** Active (its panel or row is open: emoji panel, AI panels, voice typing listening) = the icon in the
  on-accent colour on a 36 dp accent circle (the treatment the mic already had). Pressed = a subtle circle in the strip
  divider colour. Locked (AI tools while signed out, an update is needed, offline or the cached plan excludes them;
  ADR 0031's plan state, no new network call) = a small lock at the icon's corner, and TalkBack says "Translate,
  locked". Locked tools still open: their row or panel says why and links to the fix.
- **AI**: the AI and Translate shortcuts both open today's AI actions row with the direction control and Translate first
  (scrolled to the start). The row's chips use the new icons, 34 dp tonal chips on the key fill, an outline only in
  themes that draw key borders or on the selected chip. The direction control and the translate review flow are
  **unchanged** (a later step redesigns translation); `ToolHost.openTranslate` is the one seam it will swap for a
  dedicated translate panel.
- The **gear** is no longer a fixed bar element: Settings is a shortcut when it fits and always a tile.
- **Accessibility**: every element is an `ExploreByTouchHelper` virtual view with a role and label (tools button
  "Tools" / "Back to keyboard", shortcuts by name, locked suffix, the panel title as a heading, the AI close "Close AI
  tools", the mic "Voice typing" / "Stop voice typing…", candidates with "Add to your dictionary"). Haptics follow the
  keys' setting.

### 4. Tools panel (`ToolsPanelView`, `ToolsController`, `ToolSet`)
Replaces the key area **at the keyboard's current height** (like the emoji panel), 4 tiles per row, scrolling when the
keyboard is short (landscape); the bar's back arrow and the system Back return to the keys.
Tiles in order: **Translate, AI Assist, Write Email, Clipboard, Emoji, Voice typing, Word Art, Writing mode, Languages,
Theme, Settings**. A tap closes the panel and opens the tool where it opens today: Translate → the AI row; Assist and
Write Email → their panels (ADR 0031); Clipboard → §6; Emoji → the emoji panel; Voice → voice typing; Word Art → the
emoji panel's Word Art screen (Back returns to the emoji panel, as before); Writing mode → the pill's chooser in the bar;
Languages → the app's Languages page; Theme → the app's theme picker; Settings → the app's settings. Opening the panel
finishes the composition as typed and closes voice, emoji and the AI row and panels first.

`ToolsController` is View-free (JVM-tested): one panel at a time, a stale tap on a tool the field does not offer does
nothing.

### 5. Which tools each field offers (`ToolContext`)
| Field | AI (Translate, AI, Assist, Email) | Clipboard | Emoji, Word Art | Voice | Writing mode | Languages, Theme, Settings |
|---|---|---|---|---|---|---|
| text | yes (AI client attached) | yes | yes | where offered (ADR 0029) | am/ti with ≥ 2 modes | yes |
| e-mail, URL, number, phone | no | yes | no | where offered | no | yes |
| password | no | **no** | no | no | no | yes |
| incognito (`IME_FLAG_NO_PERSONALIZED_LEARNING`) | no | **no** | yes | where offered | yes | yes |
| raw key events (`TYPE_NULL`) | no | no | no | where offered | yes | yes |

AI follows `InputProfile.allowsAi`, emoji `allowsEmoji`, voice `VoiceController.micVisible` — today's rules, unchanged.

### 6. Clipboard panel and its privacy rules
`ClipboardPanelView` replaces the key area: recent text clips as two-column cards (up to three lines each), tap = paste
into the field (the open composition is committed first; pasted words are not learned), × on a card removes it, "Clear
all" removes every one, one short line when empty ("Text you copy will appear here", or "Clipboard history is off").
After a paste the keys come back.

- **What is kept:** text copied while the keyboard process is alive (`OnPrimaryClipChangedListener`) and the current
  clip when the user opens the panel. **In memory only** (`ClipboardHistory`): never written to disk, synced, logged or
  sent; it dies with the process. At most **20** clips, each dropped **1 hour** after it was copied (pruned on every
  read and when the keyboard is shown or hidden); a clip copied again moves to the top; clips over 20 000 characters
  are not kept.
- **Never kept:** clips marked `ClipDescription.EXTRA_IS_SENSITIVE` (password managers, one-time codes; honoured as
  the plain extra name below API 33); anything copied while a password, incognito or raw key-event field has focus;
  non-text clips. A sensitive current clip is not offered either.
- **Setting:** "Clipboard history" in the app's Preferences (on by default, `KeyboardSettings.clipboardHistoryEnabled`,
  device-local). Off: the listener is removed, the history is cleared at once, and the panel offers only the current
  clip (read on open, without delete buttons).
- **Android access rules:** from Android 10 only the default input method and the focused app may read the clipboard;
  Qal reads it as the default keyboard. Android 12+ shows its "pasted from your clipboard" notice for other apps, not
  for the default keyboard. Every read is wrapped: a refused read (`SecurityException`, a missing service) is "no clip".
  The panel reads clip text only on a copy event (to keep it) and when the user opens the panel.
- AI results copied by the AI panels stay marked sensitive (ADR 0031) and so never enter this history.

### 7. Bottom row and keys
- **Text fields:** `?123`, comma, globe, emoji, space, period, Enter (Gboard's order). Comma, period, globe and emoji
  are one key wide, `?123` 1.25, Enter 1.5; the space bar takes what the letter rows' ten units leave (3.25, or 4.25
  without emoji) and never less than three. Arabic keeps its marks key, after the globe (space bar 3). E-mail, URL,
  number and phone rows are unchanged.
- **The ABC key is removed** for am/ti and om. am/ti: the bar's pill is always visible in text fields (idle, composing,
  AI open) and switches Fidel / Phonetic / English — writing modes stay one tap away; the globe's long press still
  cycles layouts. om: ABC (Latin without the hudhaa key) is the last stop of the globe's long press, as for every
  language. `KeyKind.ABC_TOGGLE` and `toggleAbc` stay in the controller; no layout places the key. The key's descriptions
  (`kb_key_switch_to_abc`, `kb_key_switch_to_english_mode`, `kb_key_switch_to_language_keys`) stay in the resources
  and `KeyboardLabels`, unused, until the native-speaker review.
- **Icons:** shift (outline; filled once; filled with a bar for caps lock), delete, Enter (return, search, send, go,
  next, previous, done — the key's spoken name is unchanged), globe and emoji are drawn from §2 in the special-key text
  colour (on-accent on Enter); the emoji panel's delete and Enter keys match. Key gaps (4 dp, rows 7 dp), corner radii
  (the theme's), label sizes (21 dp letters) were reviewed and kept: they are already within Gboard's proportions.

### 8. Motion and feedback
The AI row fades in while sliding 12 dp out of the close button; the tools and clipboard panels fade in rising 8 dp;
180 ms, decelerate. With the system's "Remove animations" (animator duration scale 0) everything appears at once
(`Motion.enabled`). Nothing animates a size: the keyboard's height never moves. Pressed states on every bar button,
tile and card; key-tap haptics on bar buttons, tiles and cards follow the keys' haptics setting. The bar's sync is
called on every keyboard refresh but only requests a layout pass when something it lays out changed (never per key).

### 9. App side (kept small)
- `AiLink.LANGUAGES` and `AiLink.THEMES` open the settings' Languages page and the theme picker from the keyboard
  (`DeepLinks` → `SHOW_KEYBOARD_LANGUAGES` / `SHOW_KEYBOARD_THEMES`); like the gear's settings, Back returns to the app
  the user was typing in. During first-run setup they are ignored like the other links.
- Preferences: the "Clipboard history" switch with one quiet summary line.

### 10. Strings
16 keyboard strings (`tools_title`, `tool_*`, `clip_*`) and 2 app strings, with DRAFT am/ti/om translations (ADR 0032,
glossary: tools = መሣሪያዎች / መሳርሒታት / meeshaalee, clipboard, paste, delete, clear). Existing strings are reused where
they already say it (Translate, Voice typing, Word Art, Emoji, "Back to keyboard", "Close AI tools").

### Deviations from the brief, and why
- Bar height 44 dp instead of 40: 48 dp-wide targets with 22 dp icons and 34 dp chips need it; still one height.
- The pill stays visible while the AI row is open (the row is narrower) so writing modes are always one tap away, which
  is what allows dropping the ABC key.
- Settings and Theme are shortcuts only when they fit; the gear lost its fixed place (it is a tile in every field).
- Incognito fields get no clipboard panel (the brief named only passwords): the history is the user's own text and
  incognito fields ask the keyboard not to remember or show such things.
- After a paste the panel closes (Gboard keeps it open): typing usually continues right after the paste.
- No ABC button in the tools and clipboard panels (the emoji panel has one): the bar's back arrow sits right above, as
  in Gboard, and without the extra row all eleven tiles fit a portrait key area in three rows without scrolling.

### Deferred
- A dedicated translate panel (the next step; it replaces `ToolHost.openTranslate`).
- Pinning and reordering shortcuts by the user; pinned clips; image clips; clipboard suggestions in the bar.
- Collapse animations (panels and the row close at once); shared-element transitions.
- On-device QA (below).

## Consequences
- Every tool is reachable in at most two taps from the keys (tools button, tile), the frequent ones in one.
- Tests: `KeyboardBarTest` (bar states, 48 dp targets, spacing, narrow screens, sensitive fields, locks, panel title,
  one height), `BarAccessibilityTest` (nodes, labels, locked suffix, heading), `ToolsTest` (tile set per field kind and
  language, shortcut order, controller), `ClipboardHistoryTest` (TTL, max 20, dedupe, clear, refused texts, never
  persisted, sensitive / password / incognito policy), `ToolPanelsTest` (Robolectric: tiles, height, a11y, paste,
  delete, clear all, empty and off states, the system clipboard listener with sensitive and non-text clips, every theme
  draws), `BottomRowTest` and the updated layout tests, `SettingsRepositoryTest` and `SettingsScreenTest` (the switch),
  `DeepLinksTest`. `ToolbarRenderTest` renders PNGs for a visual review when `QAL_RENDER_DIR` is set.
- To verify on a device: clipboard reads as the default keyboard on Android 10–15 (no "pasted" notice, history fills
  while another app copies), sensitive clips from a password manager, the 180 ms transitions and "Remove animations",
  haptics, TalkBack order through the bar and the tools grid, every theme in light and dark, 360 dp and landscape
  (tools grid scrolls), the navigation-bar inset under the new bottom row, and the Languages / Theme links returning to
  the app after Back.
