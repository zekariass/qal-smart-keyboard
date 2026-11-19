# ADR 0031 — Keyboard AI panels: translation direction, AI Assist and Write Email

**Status:** Accepted · **Date:** 2026-11-19 · **Builds on:** ADR 0007 (D40 toolbar/plan state, D43 privacy, D45 one bar,
D47 crash reports, D50 AI actions), ADR 0012 (destination profiles, app rules), ADR 0021 (history), ADR 0027 (the text
actions and e-mail backend this integrates with), ADR 0028 (emoji panel), ADR 0029 (voice), ADR 0030 (Word Art) ·
**Contract:** `/ai/translate`, `/ai/transform`, `/ai/replies`, `/ai/drafts` (no change)

> **Amended by ADR 0041:** the panels are opened from the AI tools grid (AI Assist, Write Email, and Reply / Explain /
> Summarise opening Assist on that action) instead of the AI row's chips, which are retired; the grid's tiles follow the
> same plan locks (cached state, a locked tile still opens and says why).

## Context
The keyboard's AI row (D45) had a Translate chip and a separate destination chip that users did not connect, and no way
to run the eight actions of ADR 0027 on text copied from another app or to write an e-mail. The backend for all of it is
merged (ADR 0027). This ADR is the Android user experience: a translation direction control, an AI Assist panel for
selected or copied text, and "Write Email with AI" in the keyboard and in the companion app. `:ime` stays network-free
(`NoNetworkTest`): everything goes through the `AiActions` port, implemented by `:ai-client`.

## Decisions

### 1. Translation direction control (keyboard AI row)
`[ Auto · Amharic ⇄ English ]  Translate` replaces "Translate" + destination chip. The group's source side, the ⇄ swap
icon and the destination side are each a button; the primary Translate chip (accent fill) sits right after it.

- **Source:** "Auto" by default: the server detects the language (no `sourceLanguage` sent), and the keyboard language is
  shown as the likely one ("Auto · Amharic"; it follows the globe key). Tapping it opens the list above the bar
  (Auto-detect, Amharic, Tigrinya, Afaan Oromoo, English; the current one checked); picking a language makes it explicit
  and it is sent as `sourceLanguage`. A new field starts at Auto again.
- **Destination**, in order (`TranslationDirection`): the one picked in this field; the user's enabled A2.4 app rule
  (`AiActions.appRuleDestination`, explicit configuration wins); the destination **last picked for this source language**
  (device-local, `KeyboardSettings.aiTranslatePairs`); the **preferred destination** from settings (new
  `aiPreferredTarget`, "Translate into" in the app's settings; null = automatic); else English for am/ti/om text and
  Amharic for English text. A candidate in the source language itself is skipped. The destination list shows the four
  plain languages (minus the source) then the user's destination profiles.
- **Swap** makes the destination's language the explicit source and the old source the destination, and remembers that
  pair. The per-app "last used destination" (A2.5) is no longer consulted: per-source memory answers the question users
  actually have ("I translate Amharic into English"); app rules still give per-app behaviour. `LastDestinationStore`
  stays (no migration) but the keyboard no longer writes it.
- **Label:** the chip reads "Translate" on phones and "Translate to English" from 600 dp (tablets, wide landscape). On a
  360-411 dp bar the middle is ~230 dp: the direction group already shows "→ English" right before the chip, and the
  chip's accessibility label is always "Translate to English". The working and review panels state the direction
  ("Translating Auto · Amharic → English", review "Amharic → English" using the server's `detectedSourceLanguage`).
- The review/Replace/Insert/Copy/Retry flow of the toolbar panel is unchanged; idle height never changes (D45).

### 2. AI Assist panel (selected or copied text)
"Assist" in the AI row opens a panel **in place of the keys at the keyboard's height** (like emoji and voice): fixed header
(back, title, back to keyboard), scrolling body, fixed footer with the step's buttons. Steps: **Compose → Working →
Result** (or an error). Created on first open only; nothing new on the keyboard's start-up path.

- **Source priority:** (a) the host field's selection (`getSelectedText`, read on the tap that opened the panel);
  otherwise (b) a "Paste from clipboard" chip, shown only when `getPrimaryClipDescription()` reports text and the clip is
  not marked sensitive; the content is read **only in that tap's handler**; (c) "Type it": the keys type into the panel's
  text box. A preview card shows the label (Selected text / From clipboard / Your text), the text (3 lines, "Show more"
  up to 14) and "1,234 / 5,000"; over the limit the count turns bold, a caution line explains, and Run is disabled.
- **Actions** (chips with icons, sideways scroll): Translate, Explain, Fix grammar, Rewrite, Make professional, Simplify,
  Reply, Summarise → `/translate`, `EXPLAIN_TEXT`, `GRAMMAR_CORRECTION`, `REWRITE`, `TONE_ADJUST` PROFESSIONAL, `REWRITE`
  `SIMPLIFY`, `/replies` (count 3), `SUMMARIZE` (MEDIUM). Selecting a chip configures; the footer's Run button names the
  result ("Explain in Amharic", "Suggest replies in English") and is the only thing that sends.
- **Output language:** Translate = the direction control's destination; Explain, Summarise, Reply = the keyboard language;
  Rewrite, Professional, Simplify = "Same as text" (no `outputLanguage` sent); all changeable per action (kept while the
  panel is open) except Fix grammar, whose chip is disabled with "Grammar keeps the language of the text." (the server
  refuses another one). Reply adds "What do you want to say? (optional)" (any language, `instructions`).
- **Result:** the action and direction ("Translate · Amharic → English"), the text (Reply: three cards, tap to pick), notes,
  then **Replace selection** (only when the source is the host's selection and unchanged) and **Insert after**, or
  **Insert**; **Copy** (a plain clip: the user asked for it); **Regenerate** (Rewrite, Professional, Simplify, Reply);
  **Edit** (back to the inputs). Insert closes the panel; if the selection changed meanwhile the result is inserted at the
  cursor with the existing "inserted instead" notice.
- **States:** Working shows a small spinner, "Summarising…", "This can take up to 20 seconds." for the long-form actions,
  and **Cancel** (cancels the OkHttp call; the server handles client cancellation, ADR 0017); every failure has a message
  (`AiMessages`), its link (Sign in, See plans, Open settings, Update), Retry when it can help (same `Idempotency-Key` after
  network errors, timeouts, 503 and `REQUEST_IN_PROGRESS`; a new one otherwise) and Edit. Offline / signed out / update
  needed show a banner and no call is made. State changes are announced (live regions + `announceForAccessibility`).
- **Plan locks:** the cached plan state (D40: refreshed when the AI row or a panel opens) now carries the features with
  `accessEnabled` (`AiAvailability.entitled`, null = unknown = nothing locked). A locked action keeps its chip with a lock
  icon and "…, Premium" for TalkBack; Run reads "Premium: unlock Reply" and shows **See plans** (`AiLink.UPGRADE`, the
  existing `ai_link_upgrade` handling) instead of sending. A `FEATURE_NOT_ENTITLED` answer locks that action for the rest
  of the panel session. FREE today: Explain yes (3/day), Reply, Summarise, Make professional (`TONE_ADJUST`) and Email no.
- The toolbar's own quick Rewrite with a tone is now metered as `TONE_ADJUST` (`AiTaskKind.TONE`), which is what the
  server charges; a locked tone shows the upgrade panel instead of a server round trip.

### 3. Write Email with AI
**Keyboard:** "Email" in the AI row (first and highlighted when the field looks like an e-mail body:
`AiEmailHints`, a multi-line text field of a known mail app that is not its subject/address field, or a multi-line field
whose hint says "compose email"; content-free). A step flow inside the panel:
- **Compose:** mode New · Reply · Improve; "What do you want to say?" (any language, ≤ 2000); Reply: "Email you received"
  from **Use selected text** (the selection when the panel opened), **Paste from clipboard** (same rules as Assist) or
  Type it; Improve: "Your draft" from **Use text from this field** (`getExtractedText`, else text before the cursor; read
  only on that tap), Paste or Type; the eight `EmailTone` chips with a one-line explanation of the chosen tone (also in each
  chip's TalkBack label); Short/Medium/Long; Write in am/ti/om/en (default English); optional recipient name and your name
  (remembered on the device, `emailSenderName`). Validation on the device (`AiEmailRules`): NEW needs instructions, REPLY
  the e-mail and instructions, IMPROVE the draft; limits 2000 / 8000 / 8000 / 120 counted in code points like the server.
- **Preview:** subject card (tap to edit) with **Copy subject**; body with `[placeholders]` highlighted (bold on the pressed
  fill: a contrast-tested pair) and "Fill in before sending: [date], [time]" (the server's list plus anything bracketed
  left after edits); Insert, Copy, Regenerate (`variation` + 1, new key), Edit text (the body in the field strip),
  Edit (back to the inputs).
- **Subject on insert:** the keyboard cannot reach a mail app's separate subject field, so **Insert puts the body only** by
  default and the subject has its own Copy; the toggle "Also insert the subject line" prepends `Subject: …` + blank line
  for apps with a single text box. When the draft came from the field (Improve) and the field still holds it, Insert
  replaces it (select all + commit); otherwise it inserts at the cursor.
- **Open full editor** (header icon) hands the current inputs to the companion app (below).

**Companion app:** "Write Email with AI" (settings card, and the keyboard's Open full editor). Same modes, rules and request
through the same `ServerAiActions` (timeouts, history flag, error mapping), comfortable multi-line fields with Paste buttons
(sensitive clips refused), tone/length/language chips, recipient and your name, Generate with progress and Cancel, then an
editable subject and body (placeholders highlighted by a `VisualTransformation`), "Fill in" list, Copy subject, Copy body,
Copy all, Share (`ACTION_SEND` with `EXTRA_SUBJECT`), Regenerate.

**Keyboard → app handoff:** `AiEmailHandoff` is an in-memory, single-slot, 10-minute holder in `:ime` (the keyboard and the
app share one process). The keyboard puts the spec and sends only `EXTRA_OPEN_LINK = EMAIL_WRITER` on the existing launch
intent (new task, clear top); the app takes the spec once. No user text in Intent extras. Back from that screen finishes
the task so the user is back in the mail app to paste. A pending "insert into the field when you come back" handoff is
**out of scope**: the user copies in the app and pastes in the field.

### 4. Typing into panel fields
The keys type into a panel field with the redirect the emoji search and Word Art use: `KeyboardController.fieldTyping`
(a `FieldTyping`: sink, Enter behaviour, done) is consulted after `EmojiController.redirect`. While typing, the keys are
back, the bar is replaced by `AiFieldBar` (fixed 64 dp: field name and "count / limit", the end of the text on two lines,
Done), candidates are not shown, nothing is learned and auto-capitalisation is off. Enter adds a line break in multi-line
fields and ends typing in one-line fields (names, subject); the emoji key, Done and Back end typing. Fields append at the
end and backspace removes the last grapheme: an in-keyboard field has no cursor of its own (small edits; larger ones in the
app's full editor). `AiTextField` keeps up to 20 000 characters so a too-long paste is visible as too long, never silently
cut.

### 5. Layout, height, theming, accessibility
- The AI Assist and Email panels take the key area at `KeyboardSurface.preferredHeightPx()`; the 40 dp bar stays. Idle
  height never changes; the toolbar's own panel above the bar keeps D45's rule. Back: typing → panel, result → inputs,
  inputs → keyboard. Closing the AI entry (×), a field change, hiding the keyboard and voice typing close the panels and
  cancel their calls; opening a panel closes the emoji panel, cancels voice and closes the toolbar's panel.
- Views are plain Views built on first open; re-rendered on state changes, never per key (the field strip re-renders per
  key, cheaply). Scroll positions survive re-renders within a step. No animations beyond the platform spinner.
- Colours only from `KeyboardTheme` pairs already in `ThemeContrastTest` (key text on key, key text on pressed, strip text
  and warn on the background, on-accent on accent, hint on key); icons are 24×24 path drawables tinted with those colours.
- Buttons are 48 dp, chips 40 dp; every clickable has the Button role, chips are checkable with their selected state,
  headings are marked, Working/result/error lines are polite live regions; TalkBack order is header → body → footer.

### 6. Client timeouts per operation
`AiTimeouts` (`:ai-client`): Translate, Grammar, Rewrite, Tone **8 s** (unchanged, SD 17.1); Explain, Summarise, Reply
**25 s** (server deadline 20 s + 5 s margin); Email **35 s** (30 s + 5 s). Long-form requests use `newBuilder()` copies of the
keyboard client (same pool and dispatcher); the read timeout is raised with the call timeout so a slow answer is not cut.

### 7. Privacy (N2, D43, D47)
- Nothing reads the clipboard's content except the Paste tap; sensitive clips (`EXTRA_IS_SENSITIVE`, also checked by its
  string name below API 33) are never offered or read. The selection is read only on the Assist/Email tap; the field's
  whole text only on "Use text from this field". The toolbar's own Copy keeps marking its clip sensitive (unchanged); the
  panels' Copy is a plain clip because the user asked to paste the output elsewhere.
- No text in logs, analytics or exception messages; `AiTask.Reply`, `AiTask.Email`, `AiEmailSpec`, `AiTextField` print no
  content in `toString`. Panel texts live in memory while the panel is open; `emailSenderName` is the only stored text
  (the user's own name, device-local, never synced, sanitised to one line of ≤ 120 characters). `saveToHistory` follows
  the existing opt-in history flag (ADR 0021/D43) for every new call, including the app's e-mail screen.

### 8. iOS mapping notes
- A custom keyboard extension needs **Full Access** (`RequestsOpenAccess`) for network calls and for `UIPasteboard`;
  without it the AI row should show a "Turn on Full Access" explanation instead of the actions. Use
  `UIPasteboard.hasStrings` / `detectPatterns` (no "Allow Paste" prompt) to decide whether to show "Paste from clipboard",
  and read `string` only on the tap (iOS 16+ may still prompt; `UIPasteControl` is the system-sanctioned button where it is
  available in extensions; to be verified). `textDocumentProxy.selectedText` replaces `getSelectedText`.
- Extensions have tight memory limits and no long-running work: keep Assist to the compact step flow and give the e-mail
  writer to the **companion app** (open it with a URL scheme; the handoff goes through an App Group container or a
  keychain item with a short expiry instead of process memory, since extension and app are separate processes).
- The direction control, the output-language rules, `AiEmailRules` and the timeouts are platform-neutral and should be
  mirrored as is.

## Consequences
- New in `:ime` (`ai/`): `TranslationDirection`, `AiAssistController`, `AiEmailController`, `AiEmailRules`, `AiWorkspace`,
  `AiTextField`, `AiClipboard`/`SystemAiClipboard`, `AiEmailHandoff`, `AiEmailHints`, `AiMessages`, `AiUi`, `AiPanelView`,
  `AiFieldBar`; the `AiActions` port gains tasks (`Explain`, `Summarize`, `Simplify`, `Reply`, `Email`, output language on
  `Rewrite`, source on `Translate`), result fields (language, detected source, suggestions, subject, placeholders),
  failures (`InputTooLong(field)`, `Invalid(field)`), kinds (`TONE`, `EXPLAIN`, `SUMMARIZE`, `REPLY`, `EMAIL`),
  `AiAvailability.entitled` and `appRuleDestination`. `AiLink.EMAIL_WRITER` routes to the app's e-mail screen.
- `:data` settings: `aiPreferredTarget`, `aiTranslatePairs`, `emailSenderName` (additive keys, schema stays 1, not synced).
- Tests: `TranslationDirectionTest`, `AiAssistControllerTest`, `AiEmailControllerTest`, `AiWorkspaceTest`,
  `AiPanelViewTest` (Robolectric), `AiTextActionsWireTest` (contract shapes, timeouts, plan hint, app rule),
  `EmailWriterScreenTest` (Compose), additions to the toolbar, bar, accessibility, settings and deep-link tests.

## Open
- Contract gap: `TransformRequest` has no `variation` (only replies and drafts do), so Regenerate on Rewrite / Make
  professional / Simplify sends the same body with a new key: a fresh provider call that may (OpenAI) or may not (mock,
  deterministic) differ. Adding `variation` to `TransformRequest` (fingerprinted) would make it reliable.
- ADR 0027 says "400 `NOT_ALLOWED`"; the server answers `VALIDATION_FAILED` with a field error code `NOT_ALLOWED`. The client
  maps by the problem `code` and the field (`AiFailure.Invalid(field)`); the ADR wording should say so.
- In-keyboard fields have no cursor (append/backspace at the end only); a pending insert from the companion app back into
  the field; streaming (SSE) progress for long answers; am/ti/om UI translations of the new strings; the companion app
  calls with `X-Client-Surface: KEYBOARD` (it shares the keyboard's AI client).
- Real-device checks: the clipboard notice when an IME reads on tap (Android 12+), `getExtractedText` in Gmail/Outlook,
  and `performContextMenuAction(selectAll)` in hosts that ignore it (then Insert falls back to inserting at the cursor).
