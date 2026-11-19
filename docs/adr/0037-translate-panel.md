# ADR 0037 — Keyboard translate panel (Gboard benchmark)

**Status:** Accepted · **Date:** 2026-11-19 · **Requested by:** product owner · **Benchmark:** Google Gboard's translate
mode (its patterns, not its colours or branding) · **Builds on:** ADR 0007 (D40 cached plan state, D45 one bar), ADR 0012
(destination profiles, app rules), ADR 0031 (§1 translation direction, §4 typing into panel fields), ADR 0032 (UI
strings), ADR 0034 (Arabic), ADR 0035 (toolbar, `ToolHost.openTranslate`), ADR 0036 ("Translate into" page) ·
**Contract:** `/ai/translate` (no change)

## Context
Translation lived in the AI actions row: `[ Auto · Amharic ⇄ English ]  Translate`, a chip row inside the 44 dp bar.
It translated the selection or the sentence before the cursor and showed a review panel above the bar (original,
result, Replace / Insert / Copy / Retry / Cancel). It worked, but it was hard to read on a phone (three tappable parts
squeezed into a scrolling row, language lists as more chips), there was no place to type text to translate, and the
Translate shortcut of the new bar (ADR 0035) only opened that row.

The product owner asked for a clean, easy translation experience with Gboard's translate mode as the benchmark: source
and destination selection with clear indicators, easy swapping, an input area, the output, an explicit Translate
action, loading and error states, clear and reset, easy access to the translation settings, language selection that
fits the keyboard, no explanatory clutter, and every existing translation behaviour and the backend integration kept.

## Decisions

### 1. Patterns taken from Gboard
- **Translate mode:** a panel above the bar while the keys stay below and type into the panel's own box; the bar's
  Translate shortcut shows as active and toggles the mode off again.
- **A language header** `[Detect · Amharic ▾]  ⇄  [English ▾]` with a swap button between the two sides.
- **"Detect language"** as the first source; detected language shown once the server reports it.
- **Language lists in place of the keys**, with a back arrow and "Translate from" / "Translate into" in the bar.
- **A clear (×)** in the box and a close (×) for the mode.

Not taken: Gboard's live translation per keystroke straight into the app's field. Every call is metered (ADR 0027,
plan quotas), so the keyboard translates only on an explicit Translate (the button, or Enter on the keys) and the user
inserts the result. Not taken either: Gboard's language search (five languages and a few profiles fit one screen) and its
colours.

### 2. Where it opens
`ToolHost.openTranslate` (the seam ADR 0035 left) now opens translate mode. It is reached from the bar's **Translate
shortcut**, the tools panel's **Translate tile**, and the AI row's **Translate chip** (the way it was reached before).
Tapping the active shortcut again leaves translate mode. Opening it finishes the composition in the field, closes the AI
row and its panel, AI Assist / Write Email, emoji and voice; opening any of those (or the tools panel) closes translate
mode. A new field, hiding the keyboard, voice typing and Back close it; the emoji key leaves it for the emoji panel.

### 3. The panel (`TranslatePanelView`, above the bar)
- **Header (48 dp):** source chip, ⇄, destination chip, ⚙ (the app's "Translate into" page through the new
  `AiLink.TRANSLATION`, routed like `LANGUAGES`: Back returns to typing), × (leave). The source chip shows a small
  "Detect" caption over the language while the source is automatic, so the two chips fit a 360 dp phone without
  ellipsis; both have a caret and a selected state (accent outline) while their list is open.
- **Box:** "Type to translate"; the end of the text stays visible (three lines) with a cursor mark. A small label says
  where the text came from ("Selected text", "Last sentence") while it is unchanged. × clears it; while empty and the
  field has a sentence before the cursor, a **Use last sentence** chip fills the box with it. The **Translate** button
  (accent, 48 dp) sits next to the box and is disabled while the box is empty, too long, a call is running, or the
  cached state says it cannot run (offline excepted: it may be back).
- **Output (only when there is something):** a spinner, "Translating to English…" and **Cancel**; the translation on a
  card with a quiet direction line ("Amharic → English", the server's detected source when it reported one), the
  alternatives (ADR 0012 formality options) as small chips, up to two server notes, **Copy** and **Insert** (labelled
  **Replace** when the box still holds the selection or sentence it came from); an error with its link (Sign in, See
  plans, Open settings, Update) and **Retry** where it helps; or, before anything is sent, one line for a blocked or
  locked state ("Sign in to use AI  [Sign in]", "Update needed  [Update]", "This feature is not part of your plan.
  [See plans]", "Offline").
- The translation is laid out in the destination's direction: right to left for Arabic (ADR 0034), left to right
  otherwise; it scrolls past 104 dp so the panel stays compact (≈ 110 dp idle, ≤ 330 dp with a long result and
  alternatives on a 360 dp phone).

### 4. Language lists (`TranslateLanguagesView`, in place of the keys)
At the key area's height, so the keyboard does not change size while picking; the panel hides and the bar shows a back
arrow and "Translate from" / "Translate into". Source: **Detect language**, the current source, the keyboard language,
then the rest (am, ti, om, en, ar). Destination: the current destination, the plain languages (not the source), then the
user's destination profiles (ADR 0012). Each row shows the language's own name and, under it, its name in the UI
language ("ትግርኛ / Tigrinya"); profiles show their name and language. The current one is on the key fill with a check;
rows are checkable buttons for TalkBack, named in the UI language. A tap selects and returns; the back arrow, the open
side's chip and Back return without a change.

### 5. One direction, one translate UI
`TranslationDirection` stays the single source of truth and now belongs to `TranslateController` (created with the AI
client; it starts per field with the app rule, ADR 0031). Extensions: `likelySource(detected)`, `sourceLabel(auto,
detected)`, `swap(detected)` (while Detect is on, the language the server detected is the one that swaps), and the two
lists (`sourceChoices`, `destinationChoices`). AI Assist's Translate output defaults to the same destination. The AI
row's direction control and its translate review are **removed**: the row keeps a Translate chip that opens translate
mode, so there are no two translate UIs that could disagree. The toolbar's quick actions (Grammar, Rewrite + tone) and
their review panel are unchanged.

### 6. Input, sending, applying (`TranslateController`, View-free)
- **Typing:** the keys type into the box through the AI panels' redirect (`KeyboardController.fieldTyping`, ADR 0031
  §4); every layout works, candidates show in the bar as usual (a tap picks into the box), nothing is learned and
  auto-capitalisation is off. Unlike the Assist panel, the keys and the bar stay: the box is always the target while the
  panel is open. `FieldTyping.submit()` (new, default = `done()`) lets **Enter translate**; on a result Enter inserts it.
  Enter never reaches the host's Enter or Send.
- **Prefill and today's path:** the selection (read on the tap that opened the panel) prefills the box; "Use last
  sentence" reads the sentence before the cursor (`AiTextTarget`, the old Translate chip's target) on its tap. Nothing
  else in the field is read; the clipboard is not read.
- **Explicit send only:** Translate and Retry are the only calls that send. A new edit, language or swap resets the
  output (a running call is cancelled), so the output always belongs to what is in the box. Translate again after a
  result is a new request with a new key ("another result").
- **Idempotency as before:** Retry after a network error, timeout, 503 or `REQUEST_IN_PROGRESS` reuses the key and text;
  anything else gets a new key; a local or blocked failure reads the box again. Cancel aborts the OkHttp call.
- **Insert:** replaces the selection or sentence when the box still holds it and the field still reads it (else it is
  inserted at the cursor with the existing "inserted instead" notice); typed text is inserted at the cursor (RTL-aware
  collapse of a selection, ADR 0034). Then the box and output clear and **translate mode stays on** for the next
  sentence, like Gboard; × or the shortcut leaves it. **Copy** keeps marking its clip sensitive (as the toolbar always did,
  so translations never enter the clipboard history, ADR 0035 §6) and turns into "✓ Copied".
- **Plan state:** opening the panel or a list refreshes the cached plan state through the toolbar's throttle (D40); the
  panel re-renders when it changes (sign-in in the app). Signed out, update needed and a plan without translation show
  before anything is sent and nothing is sent; `:ime` stays network-free (`AiActions`, `NoNetworkTest`).

### 7. Accessibility, motion, height
- Every control is a Button with a name: "Translate from Detect · Amharic. Change", "Swap languages", "Translate into
  English. Change", "Translation settings", "Close translation", "Clear", "Use last sentence", "Translate to English",
  Copy, Insert/Replace, Cancel, Retry and the links. The box reads "Text to translate: …". The swap announces the new
  direction ("Amharic to English"); the working line, the translation, "Copied" and errors are announced once each.
  The active Translate shortcut is reported selected.
- The panel fades in rising 8 dp (ADR 0035 motion); ⇄ turns 180° in 180 ms; with "Remove animations" both are
  instant. Nothing animates a size.
- The idle keyboard's height never changes (D45): the panel exists only in translate mode, and the language lists take
  exactly the key area.

### 8. Strings
11 keyboard strings (`translate_*`) with DRAFT am/ti/om translations (ADR 0032); existing ones are reused where they
already say it (Translate, Translate to %s, the direction-side and swap descriptions, Selected text, Clear, Copy,
Copied, Insert, Replace, Cancel, Retry, the status lines and failure messages). Removed as unused: `ai_auto`,
`ai_auto_detect`, `ai_translate_from`, `ai_translate_into`, `ai_working_direction`. "Auto" is now "Detect" everywhere
(also in AI Assist's result line).

## What changed from ADR 0031 §1, and why
| ADR 0031 §1 | Now | Why |
|---|---|---|
| Direction control in the AI row: `[Auto · Amharic ⇄ English] Translate` | Header of the translate panel: `[Detect · Amharic ▾] ⇄ [English ▾]`; the row has a Translate chip that opens it | Readable at phone width; one translate UI |
| "Auto" | "Detect" (caption), the detected language shown after a result | Gboard's wording; says what it does |
| Language lists as chip rows above the bar | Lists in place of the keys with self-names, current first, check | Easier to scan and tap; room for profiles |
| Translate = the selection or the sentence before the cursor, at once | The selection prefills the box; "Use last sentence"; or type; then Translate | Text can be typed; the old target is one tap away |
| Review: original + result, Replace / Insert / Copy / Retry / Cancel | Result card: direction, result, alternatives, Copy, Insert (Replace when from the field) | Fewer buttons; Retry is Translate again; Cancel is ×/edit |
| Swap uses the shown source | Swap uses the detected source while Detect is on | Turns the real direction around |
| Destination order, per-source memory, app rules, profiles, preferred target | Unchanged | — |

## Deviations from the brief, and why
- Insert keeps translate mode on and clears the box (instead of closing it): Gboard's mode stays until left, and a
  conversation is usually several sentences. Replace from the selection still works in one tap.
- The language lists take the key area instead of the panel body: five languages plus profiles with two lines each do
  not fit above the keys without making the keyboard very tall, and the keys are not needed while picking.
- The quota hint ("3 of 5 left today") is not repeated in the panel (it stays on the AI row): no explanatory text.
- No auto-translate of a prefilled selection on open: opening translate mode is not a request to spend a call.

## Deferred
- Live translation while typing (would need a non-metered or debounced plan).
- Recent languages across sessions (the lists order by current and keyboard language only).
- Language search (only if the language list grows).

## Consequences
- Tests: `TranslateControllerTest` (open, selection prefill → Replace, last sentence → Replace, fallback insert, typing,
  clear, languages, swap with detection, app rule, translate → working → result, Enter, alternatives, copy, insert,
  cancel, every failure, retry keys, blocked and plan-locked states, a late answer, the keys typing into the box through
  the real `KeyboardController`), `TranslationDirectionTest` (lists, detected source, swap), `TranslatePanelViewTest`
  (Robolectric: header names and roles, box, last sentence, working/result/copy/insert, Replace, RTL, errors, signed out,
  settings link, both lists with roles and self-names, compact height, every theme), the updated
  `AiToolbarControllerTest`, `KeyboardBarTest`, `BarAccessibilityTest`, `DeepLinksTest`; `ToolbarRenderTest` renders the
  panel and the lists for review.
- To verify on a device: typing every layout into the box (Fidel, phonetic with candidates, Qubee, Arabic), Enter,
  the emoji key, Back through the lists, the selection prefill and Replace in real apps (chat, mail, browser), RTL
  output, TalkBack order and announcements, the reveal and swap motion and "Remove animations", the panel's height in
  landscape, and the "Translate into" link returning to the app.
