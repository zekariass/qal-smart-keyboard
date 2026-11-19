# ADR 0038 — In-keyboard text editor, Word Art results and voice in text fields

**Status:** Accepted · **Date:** 2026-11-19 · **Requested by:** product owner (real-phone test) · **Benchmark:** Gboard
(its translate box, its selection toolbar and handles) · **Builds on:** ADR 0003 (custom Views), ADR 0029 (voice typing),
ADR 0030 (Word Art), ADR 0031 (§4 typing into panel fields, §7 privacy), ADR 0032 (UI strings), ADR 0034 (Arabic), ADR
0035 (§6 clipboard rules), ADR 0037 (translate panel) · **Contract:** none

## Context
On a real phone the owner found that the keyboard's own text inputs did not behave like text fields: the translate box
could not be tapped reliably, the cursor could not be moved, nothing could be edited in the middle; AI Assist's and Write
Email's "Type it" and Word Art's "Tap to type your text" had the same problem; Word Art results could not be tapped; and
none of these inputs (nor the companion app's Word Art and e-mail fields) offered the microphone.

## Root causes
1. **No cursor model.** Every in-keyboard field was a `StringBuilder` + composition appended at the end (`AiTextField`,
   `WordArtController`; ADR 0031 §4 said so: "Fields append at the end and backspace removes the last grapheme"). The
   keyboard's redirect (`KeyboardController.fieldTyping`, `EmojiController.redirect`) could only append.
2. **No touchable editor View.** The translate box was a `TextView` showing the *tail* of the text plus a "▏" glyph inside a
   column that was focusable but not clickable; the AI and Word Art strips likewise drew `tail + "▏"`. A tap had nothing to
   land on (it fell through to the box, which ignored it), and there was no mapping from a touch to a text offset.
3. **Word Art rows were focusable.** `WordArtPanelView.Row` set `isFocusable = true` (for TalkBack). `AbsListView` does not
   perform the item click for a touch on a child that has (explicit) focusables, so `setOnItemClickListener` never fired on
   a phone; the Robolectric tests called `performItemClick` directly and missed it. In the app, the style cards had no click
   at all (only the small Copy / Share buttons).
4. **Voice only knew the app's field.** `VoiceHost.commitVoiceText` always went to `KeyboardController.commitVoiceText`
   (the InputConnection), and starting voice closed every panel (`beforeVoice`), so a panel's field could never receive it.
   The app's screens never used the recogniser.
5. **App fields under the keyboard.** `MainActivity` is edge-to-edge without `adjustResize`, and the Word Art and e-mail
   screens had no `imePadding()`, so the focused field could sit behind the keyboard.

## Decisions

### 1. One editor component (`ime/editor`)
- **`EditorBuffer`** (pure Kotlin, JVM-tested): text, selection (`selectionStart ≤ selectionEnd`; collapsed = cursor) and the
  composition range, in **logical UTF-16 offsets** (bidi never changes them). It applies a `ComposerUpdate` exactly as
  `InputConnectionAdapter` does for the host: delete `deleteBefore` **grapheme clusters** before the cursor (a selection
  counts as one and is deleted), commit at the cursor (replacing the selection or the composition), then show the
  composition at the cursor. Every reachable position is a cluster boundary (`Graphemes.clusterEnd`, now also treating
  line breaks/controls as their own cluster): an Ethiopic letter with U+135D-135F, an Arabic letter with its harakat, emoji
  ZWJ sequences and flags are never split by a tap, a move or Backspace. Also: `insert` (paste, voice), `deleteSelection`,
  `setSelection` (snaps, keeps the composition as text), `moveCursor` by clusters, `selectWordAt` (letters, digits and marks;
  ZWJ/ZWNJ; an inner apostrophe for Afaan Oromoo's hudhaa "ba'aa"; Ethiopic punctuation ends a word), `selectAll`,
  capacity (cut, never inside a surrogate pair). `toString` prints no text.
- **`EditorView`** draws the buffer with a `StaticLayout` (first-strong direction: an Arabic paragraph is right to left),
  wraps and scrolls vertically between `minLines` and `maxLines` with the caret kept in view; caret blinks (steady with
  "Remove animations"), composition underlined, selection highlighted with two draggable handles. **Tap** anywhere →
  `Layout.getOffsetForHorizontal` (bidi-aware) → caret; **tap on the caret** → the action row; **double tap / long press**
  → select the word + action row; dragging after a long press extends the selection. `beforeEdit` runs first (the service
  commits the open composition into the buffer at its old place). Accessibility: an EditText node with text, hint and
  selection; set-selection, character/word movement, cut/copy/paste/select.
- **`KeyboardTextField`**: the view plus one slot above it that shows the field's label or, while selecting, the **action
  row** (Cut, Copy, Paste, Select all: Gboard's selection toolbar, placed inside the keyboard so it never covers the keys or
  leaves the IME window). Paste is offered only when the clip's *description* says text that is not marked sensitive and the
  content is read only on the tap (`EditorClipboard`, over `SystemAiClipboard`: ADR 0031 §7 / 0035 §6); Copy and Cut put
  the user's own text as a plain clip.
- **`EditorStrip`**: the bar's replacement while typing into a panel field: the field (name + count, two to four lines),
  a **microphone** and Done, fixed at 104 dp (was 64 dp; the name line doubles as the action row, so selecting never
  resizes the keyboard). `AiFieldBar` and `WordArtBar` are now this strip.

### 2. Surfaces
- **Translate box** (`TranslatePanelView`): the `KeyboardTextField` edits `TranslateController.input.buffer` in place, so
  the cursor and selection survive re-renders, language changes, the swap, the language lists and back. The keys type at
  the cursor through the unchanged redirect; candidates (a tap picks into the box) and phonetic composition work mid-text.
  The editor spans the box (its padding is the editor's), so the whole box is the tap target.
- **AI Assist "Type it" / Write Email fields** (`AiTextField` is now an `EditorBuffer` wrapper with the same API): the
  typing strip edits the field at its cursor; switching actions, tones or steps keeps the text (it lives in the
  controller). The strip sits above the keys, never behind them.
- **Word Art** (keyboard): the panel's field shows the text and a tap puts the cursor there and opens typing; the strip is
  the editor. **A tap on a style inserts its complete styled text** into the app's field (`commitText` through the
  InputConnection, replacing a selection) and the panel stays with a short "Inserted" over the list (Gboard keeps its emoji
  panel open too); a long press opens the large preview (Insert, Copy). Rows handle their own click and long click (the
  `ListView` item click is gone), are Buttons for TalkBack with "Insert" / "Open large preview" action labels, and are not
  clickable while unavailable for the script. Word Art never opens where emoji are not allowed (passwords etc.).
- **Companion app**: style cards are clickable (copy the complete styled text, "Copied" snackbar; a hint "Tap a style to
  copy it."); `MainActivity` gets `adjustResize`, both screens `imePadding()` (and `consumeWindowInsets(innerPadding)` so the
  bars are not counted twice); fields that dictate keep a `TextFieldValue` so text goes in at the cursor. The app's Word Art
  is not opened from the keyboard (no link exists), so there is no "insert and return" action; Copy is the way back.

### 3. Voice in these fields
- Keyboard: `RoutedVoiceHost` wraps the keyboard's `VoiceHost` with an optional `VoiceTarget` (an `EditorBuffer`). The
  strips' microphones (AI fields, Word Art) and **the bar's microphone while translate mode is on** dictate into the field at
  its cursor (replacing a selection), with the same `VoiceController`, recogniser, language checks, spacing and English
  capitalisation as dictation into the app; before listening only the composition is finished into the field (the panel
  stays open). The voice panel takes the key area as before (partial text shown there as progress, never inserted until
  final); stop inserts, cancel/Back inserts nothing; if the field leaves the screen (Done, Back, panel closed, a language
  list) dictation is cancelled. Without RECORD_AUDIO the existing `VoicePermissionActivity` opens and nothing stays routed.
  Back now handles voice first.
- App: `DictationModel` is the same `VoiceController` over `PlatformVoiceProvider` (services read off the main thread), with
  the keyboard's voice settings (on/off, per-language choice) and a target per field. The microphone is a trailing icon of
  `VoiceTextField`; a status line under the field shows Starting / Listening (+ the words heard so far) / Processing, a
  failure (`VoicePanelView.errorText`: no speech, no match, network, busy, server, language not supported or needing a
  download, permission) with Try again, a language menu that lists only languages the installed service declares (or
  cannot rule out before Android 13, marked "may not be available"), Stop and Cancel, and the existing privacy line.
  Permission: the system dialog from the screen; on denial, the explanation and "Allow microphone" (the existing
  permission screen, which also handles "don't ask again").
- Privacy: no recordings (the platform recogniser streams audio itself), transcripts are only held until inserted, never
  logged, stored or uploaded by us; `:ime` stays network-free (`NoNetworkTest`).

### 4. Strings
Seven keyboard strings (`editor_cut`, `editor_paste`, `editor_select_all`, `editor_dictate`, `editor_dictate_stop`,
`wordart_open_preview`, `wordart_typing_label`) and one app string (`wordart_app_tap_hint`), each with DRAFT am/ti/om
translations (ADR 0032); Copy, the voice states and errors reuse existing strings.

## Consequences
- What ADR 0031 §4 said ("an in-keyboard field has no cursor of its own") and ADR 0037 §3 ("the end of the text stays
  visible with a cursor mark") are superseded.
- `KeyboardController` is unchanged (the redirect already passed every update to the field's sink).
- Tests: `EditorBufferTest` (insert/delete/replace at the cursor, Ethiopic and Arabic clusters, emoji, line breaks,
  composition mid-text, word selection in en/am/ti/om/ar, capacity, notifications), `EditorActionsTest` (sensitive clips
  never offered or read), `EditorViewTest` (native graphics: taps at both ends, RTL Arabic, never inside a cluster, long
  press, double tap, caret tap, TalkBack node), `TranslateBoxEditingTest` (tap → keys through the real controller at the
  cursor, Backspace by clusters, selection replaced, action row, cursor kept across languages/swap/lists, phonetic
  composition and candidates mid-text with the real core), `WordArtKeyboardTest` additions (a real touch on a row inserts,
  tap → commit through the adapter, long press → card, sample → no insert, unavailable rows, en/am/ti/om/ar marks kept),
  `VoiceTargetTest` (routing, partials, errors, stop, permission, unsupported language), app `DictationModelTest` and
  `WordArtScreenTest` (card tap copies, snackbar).
- To verify on a device: tapping and dragging in the translate box with Ge'ez, Arabic and mixed text (caret placement,
  handles), long press timing next to the keys, the action row's Paste with a sensitive clip (a password manager), the
  strip's height on small phones and in landscape, Word Art taps in chat apps (and that the field receives the whole
  styled string), dictation into each field in am and en with Google's service, the permission flow from a field, and the
  app's screens with the keyboard open (fields scroll above it).

## Not done (limitations)
- No floating selection toolbar or magnifier (the row lives in the field's label slot); no drag-and-drop of text.
- No horizontal scrolling in one-line fields (they wrap inside their lines and scroll vertically).
- Partial voice results are shown in the voice panel / status line, not live in the field.
- The app's Word Art has no "insert into the field I came from" (it is not opened from the keyboard).
