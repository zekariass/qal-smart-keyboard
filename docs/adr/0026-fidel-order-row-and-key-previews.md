# ADR 0026 — Fidel order row follows the consonant; key press previews

**Status:** Accepted · **Date:** 2026-11-18 · **Plan steps:** A1.2 (key previews, "password: no previews"), A1.4 (Fidel grid from core `fidel_layout` data)

## Context
1. In the two-key Fidel layout the bottom row showed `ሀ ሁ ሂ ሃ ሄ ህ ሆ ዋ` whatever consonant was pending. The labels were
   taken once, when the layout was built, from the core's `vowelKeys` (the neutral ሀ family); the only state the row
   followed was a dim/live flag. The keys already typed the right syllable (the core resolves the order for the pending
   family), so the label simply lied about it.
2. Keys showed no feedback of what was pressed under the finger.

## Decision
**Order row.** The layout model stays static (one `FIDEL_VOWEL` key per core vowel key, unchanged identity, so no
re-placement and no accessibility-tree rebuild). The state lives in the controller: `pendingSeries` is the consonant family
whose default form is the open composition; it is set by the consonant key and cleared whenever an update leaves no
composition (space, backspace, order key, another commit, mode switch, field change, abandoned composition). `FidelOrderRow`
maps `(pendingSeries, order)` to an `OrderFace` using only core data: the label is the form with that order in the key's
`forms` (the core's popup forms). Order 8 is the core's `ዋ`-type key: the series' own eighth form (ሏ), else the labialised
series' "waa" form (form 14: ጓ ቋ ኋ ኳ), exactly what `FidelVowel(8)` resolves to. A family without an order shows a blank,
inactive key and the press is ignored (the composition stays so the user can retry). The other labiovelar forms (ቈ ቊ ቌ ቍ)
stay reachable through the consonant key's long-press popup, which the core already lists, so nothing is duplicated.
Faces are precomputed per grid; asking for one allocates nothing.

*Neutral state:* the core's own ሀ-family reference labels, dimmed and inert. Keeping the last family would look like live
keys that do nothing and would show a stale selection; the dimmed reference row keeps the order pattern visible, needs no
extra state and is what the row showed before. The selected consonant is highlighted with the accent fill (`accent` /
`onAccent`, a pair `ThemeContrastTest` already checks).

*Redraw:* `KeyboardSurface.onLayoutChanged` used to re-place every key, rebuild the accessibility root and relayout on every
change. When only `pendingSeries` changed it now invalidates the union rectangle of the vowel row and the old/new
selected consonant, and sends a content-description change for just those virtual views. TalkBack names follow the face
("ላ, vowel order 4"; "ለ, selected"; "Vowel order 7, not available").

**Key previews.** A bubble above the pressed character key (CHAR, Fidel consonant and order keys), never for space, enter,
shift, backspace, globe or the mode/page keys. It shows the exact output (shift upper-cases letters on the letters page; an
order key shows its current face), taken before the key is handled. It is drawn by the existing `PopupOverlay`, which covers
the whole input view including the transparent headroom the long-press popups use, so a top-row bubble is not clipped by
the IME window. `KeyPreview.place` centres it over the key, clamps it into the overlay width and not above the overlay top.
It reuses the popup theme tokens (`popupBg`/`popupText`/`popupBorder`; text vs panel is already tested at 4.5:1), so no new
colour pair. It is shown on touch-down (it adds no work before the key or the long-press timer), hidden on release, cancel,
when a long-press popup or action opens, when a release-acting key is slid off, and when the window is hidden. The overlay
ignores touches and is not important for accessibility, so previews never take focus. No allocation per frame, no layout
pass: only the old and new bubble rectangles are invalidated. Keys fire on touch-down and the surface has no slide-to-retarget
semantics, so there is no key-to-key bubble movement.
`keyPreviewEnabled` (default on, device-local) is a `KeyboardSettings` DataStore key with a switch in the app's keyboard settings;
previews are always off in password fields (`FieldKind.PASSWORD`).

## Consequences
- The order row is correct for every family in the core data, including irregular and Tigrinya-only series, with no Fidel
  table in Kotlin. If a layout pack's `popupOrders` omit an order its `vowelKeys` list, that key shows inactive.
- Previews are a settings-gated, password-safe overlay with unit-tested geometry and rules.
