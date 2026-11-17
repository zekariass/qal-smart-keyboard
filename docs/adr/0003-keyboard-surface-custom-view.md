# ADR 0003 — Keyboard surface: custom `View`, not Compose

**Status:** Accepted · **Date:** 2026-11-17 · **Plan step:** A1.2 · **Spec:** System Design §6.2

## Context
Plan step A1.2 asks us to pick Compose or a custom `View` for the keyboard surface. SD §6.2 already
names "a custom `View` for the key surface (for frame-time control)" and Jetpack Compose for the
companion and settings UI. This ADR records the reasoning so later steps (A1.3 candidate strip,
A1.4 layouts and long-press popups, A1.8 theming) do not reopen it.

Compose inside an `InputMethodService` is possible but not free: the IME window has no
`ViewTreeLifecycleOwner`, `ViewTreeViewModelStoreOwner` or `SavedStateRegistryOwner`, so the service
must implement and attach them by hand to the `ComposeView`; recomposition, the first-frame cost of
inflating Compose when the keyboard is shown, and allocations during recomposition all sit on the
key-press path. The keyboard also has to run on entry-level 32-bit ARM devices (SD §17.1) where a
dropped frame while typing is the most visible defect we can ship.

## Decision
- The key surface (`KeyboardSurface`) is a single custom `View` that draws every key on one `Canvas`
  and resolves touches itself (multi-pointer, key fires on touch-down, backspace auto-repeat).
- Layouts are plain data (`KeyboardLayout`, `Key`), and all behaviour lives in `KeyboardController`
  behind a small `TextSink` interface, so it is unit-tested on the JVM without Android or a View.
- Field handling (`InputProfile`) is a pure function of the `EditorInfo` ints.
- Compose stays for the companion app (`:app`) and settings. Compose may still be used for
  non-hot-path IME overlays later (for example the AI review sheet) if hosted in a separate window
  with its own owners; that does not change this decision.
- The surface draws **no key-preview popups**, so nothing typed into a password field is echoed in
  our own UI. Typed text is only passed to `InputConnection`; it is never logged or stored (N2).

## Consequences
- Frame time and memory are in our hands: no recomposition, no per-press allocations beyond the
  commit. The `:ime` module needs no Compose dependency, which keeps it small.
- We write our own drawing, hit-testing and accessibility support; Compose would have given some of
  this for free. A1.4 (long-press popups) and accessibility work (TalkBack virtual nodes) must be
  built by hand on `View`.
- Theming and Fidel glyph rendering must be checked on the custom canvas (font fallback for
  Ethiopic) in A1.4/A1.8.
- Revisit if profiling on the reference low-end device (plan gate) shows the custom path is not
  needed and Compose's cost is acceptable; the `KeyboardController`/`TextSink` split means only the
  surface would change.

## Input-type rules implemented in A1.2
- Password variants (text, visible, web, number password): letters page, learning disabled
  (`InputProfile.learningAllowed = false`), no previews. `IME_FLAG_NO_PERSONALIZED_LEARNING` also
  disables learning.
- Email: `@` and `.` on the bottom row. URI: `/`, `.` and `.com`. Number/date: digit pad. Phone:
  dial pad with `+ * # ( )`. All of these use Latin/direct input; transliteration is never forced.
- Enter: a custom `actionLabel`/`actionId` wins; otherwise `IME_FLAG_NO_ENTER_ACTION` or a multi-line
  field with no explicit action inserts a line break; otherwise the field's `IME_ACTION_*` is
  performed; with no action a plain ENTER key press is sent.
- Backspace and Enter-as-key use key events (`KEYCODE_DEL`/`KEYCODE_ENTER`) so selections are
  deleted and hosts that ignore `deleteSurroundingText` still work.
