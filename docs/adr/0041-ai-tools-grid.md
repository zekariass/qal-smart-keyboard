# ADR 0041 — The "AI" entry and the AI tools grid

**Status:** Accepted · **Date:** 2026-11-19 · **Requested by:** product owner · **Benchmark:** Google Gboard's tools
grid (its patterns, not its colours or branding) · **Builds on:** ADR 0027 (text actions), ADR 0031 (AI panels, plan
locks), ADR 0035 (toolbar, tools and clipboard panels), ADR 0037 (translate panel), ADR 0038 (keyboard text fields)

## Context
The AI entry was a sparkle that opened a narrow chip row over the bar's middle (Translate, Assist, Email, Grammar,
Rewrite and a tone chip). The row scrolled sideways, hid most actions, and the sparkle did not say "AI" to users. The
tools panel had a second AI tile (AI Assist, also a sparkle) and a Write Email tile. The product owner asked for an "AI"
mark like the other tools and for the AI actions as a well-organised grid in the key area, like the tools and clipboard
panels. QA also found the text field's action row ("Select all") cut by the translate box's clear (×) at 360 dp.

## Decisions

### 1. One AI entry, drawn as an "AI" mark
`ToolIcon.AI` is a bold "A" and a serifed "I" inside a subtle rounded outline, drawn as paths of the icon family
(`ToolIcons`; outline 1.6 units, letters 2.2 units on the 24-unit grid). It is drawn at the 22 dp icon size in the bar and
24 dp in tiles, so it never grows with the font scale, and takes the same active state (accent circle, on-accent mark)
and lock badge as every tool. It is a mark, not text: TalkBack reads its name, "AI tools" (`top_bar_ai_open`, localised).
`Tool.AI` replaces `AI_ACTIONS`, `AI_ASSIST` and `EMAIL` (the bar shortcut and one tools-panel tile); it is locked when
the plan state blocks AI (signed out, update, offline) or includes no AI feature. The sparkle remains only inside other
marks (Word Art, the Assist wand).

### 2. The AI tools grid replaces the AI row
`ToolPanel.AI` / `AiToolsPanelView`: the same pattern as the tools panel (ADR 0035): in place of the keys at their
height, the bar showing a back arrow and "AI tools", 4 columns of icon + label tiles (two lines at most), vertical scroll
with a fading edge when they do not fit, back arrow and system Back return to the keys, the 180 ms rise. The tools and AI
grids share one implementation (`TileGridView`); a new tool is one enum value, a label, a place in `ToolSet` and a branch
in `ToolsController.activate`.

Tiles are exactly the AI features that work today, in this order:

| Tiles | Flow | Backend |
|---|---|---|
| Translate | translate mode (ADR 0037) | `/ai/translate` |
| Fix grammar, Rewrite, Simplify | quick action on the selection or the sentence before the cursor; review panel above the bar (Replace / Insert / Copy / Retry) | `/ai/transform` `GRAMMAR_CORRECTION`, `REWRITE`, `REWRITE`+`SIMPLIFY` |
| Reply, Explain, Summarise | AI Assist opened on that action (they read a received or selected text; the result is inserted) | `/ai/replies`, `/ai/transform` `EXPLAIN_TEXT`, `SUMMARIZE` |
| Write Email | the Write Email panel | `/ai/drafts` |
| Professional, Friendly, Polite, Concise, Apologetic, Honorific | quick action, `TONE_ADJUST` with that tone | `/ai/transform` |
| AI Assist | the Assist panel | all of the above |

Write Email comes first in fields that look like an e-mail body (as the Email chip did). The quick actions are
`AiQuickAction` on `AiToolbarController.run`; idempotency keys, same-key retries, one request at a time, cancel on field
change and keyboard hide, and never auto-inserting are unchanged. Simplify and the five other tones were reachable only
in Assist or through the tone chip before; they are the same tasks.

**Plan and states.** Tiles read only the cached plan state (`AiAvailability`); a tile the plan excludes carries the lock
(and ", locked" for TalkBack) and still opens to say why (upgrade link), sending nothing. Opening the grid triggers the
same throttled background plan refresh the AI row did (D40); it never reads or sends text. Signed out, update needed and
offline are one line above the tiles with its action (Sign in, Update; offline has none), not locks on every tile;
otherwise a quiet line gives the remaining translation allowance when known (as the row did). No AI tools in fields that
do not allow AI (passwords, incognito, email/URL/number fields): no entry, no tiles.

### 3. The old AI row is retired
`AiToolbarView.actionsRow`, the tone chip and picker (`AiPicker`), the bar's AI close (×) target (`AiEntry`,
`Target.AiClose`) and the row layout in `KeyboardBar` are removed. `AiToolbarView` remains as the quick actions' result
panel above the bar; the AI entry is drawn active while that panel or an AI panel is open. There is one AI UI.

### 4. The text field's action row always fits
`KeyboardTextField` measures its action row against the field's width: labelled pills, then the same with tighter
padding, then 40 dp icon pills (cut, copy, paste, select all; each keeps its name for TalkBack and a tooltip). At 360 dp
the translate box (beside its clear × and Translate button) and the typing strips fit in en/am/ti/om; only a field
narrower than four icons would still scroll (with a fading edge).

### 5. Strings
New: `ai_tile_tone_description` ("Rewrite, tone: %1$s"), `ai_grid_offline`, `ai_grid_quota`, with DRAFT am/ti/om
(ADR 0032). Removed (unused): `top_bar_ai_label`, `top_bar_ai_close`, `ai_grammar`, `ai_rewrite`, `ai_tone_natural`,
`ai_assist_chip`, `ai_email_chip`. Tile labels reuse the Assist action and tone names.

## Consequences
- Every AI feature is visible in one grid, two taps from the keys at most; the row's sideways scroll is gone.
- Users who opened Assist or Email from the tools panel now go through the AI tile (still two taps).
- Tests: `AiBarLabelTest` (the mark drawn, size under font scale, active, locked, TalkBack in four languages),
  `AiToolsGridTest` (opening at the keys' height and Back, tile set, order, email first, no AI in password/incognito,
  locks, signed-out/offline/update/quota line, a tile on the same controller path with fake `AiActions`, Assist actions,
  labels at 360 dp in en/am/ti/om, every theme), `EditorActionRowFitTest` (360 dp, four languages, translate box and
  strip, label → icon fallback), and the updated `ToolsTest`, `KeyboardBarTest`, `BarAccessibilityTest`, `ToolPanelsTest`,
  `AiToolbarControllerTest`. `ToolbarRenderTest` renders the bar (idle, active, locked) and the AI grid (light, dark,
  bordered and gradient themes, signed out, plan locks, am/ti/om at 360 dp) and the action row.
- To verify on a device: the mark's legibility next to the other icons on a real screen at 360 dp and with the largest
  font and display size, TalkBack order in the grid (line, then tiles), the grid's scroll in landscape, the tone results'
  quality per language, and the icon fallback of the action row.

## Open
- The tone tiles' labels are adjectives ("Friendly"); if users read them as states rather than actions, "Make friendly"
  style labels need a second line in Oromo.
