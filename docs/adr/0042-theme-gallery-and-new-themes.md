# ADR 0042 — Theme gallery and 20 new themes

**Status:** Accepted · **Date:** 2026-11-19 · **Requested by:** product owner · **Builds on:** ADR 0032 (UI
localisation), ADR 0036 (settings redesign, theme strip) · **Contract:** none

## Context
The catalogue had 20 themes (8 free, 12 premium) and the settings home showed only 8 of them. The owner asked for a
Gboard-style theme gallery with about 20 more themes.

## Decisions
1. **20 new themes**, appended to `ThemeCatalog.all` (existing entries, ids, order and tiers are untouched; a test
   snapshots them). Light: `soft_grey`, `cream`, `warm_beige`, `sky`, `peach`, `rose`, `ice_blue`, `sage`, `coral`,
   `tibeb`. Dark: `deep_navy`, `slate`, `charcoal`, `teal_night`, `plum`, `cocoa`, `meskel`. Subtle gradients (three
   at most): `night_gradient`, `sunrise_gradient`, `pastel_rainbow`. Mostly flat, Gboard style; `tibeb`, `meskel`,
   `charcoal`, `ice_blue` and `sage` carry a key border. `tibeb` (cream, deep green accent, gold border) and `meskel`
   (dark green, golden accent) are the Ethiopian touches: muted, no flag, no ornament.
2. **All new themes are free.** This is a product choice, recorded here and easy to change: set `tier =
   ThemeTier.PREMIUM` on an entry. `ThemeAccess.kt` is unchanged.
3. **Tokens.** Themes use the catalogue's `theme(...)` builder, so the strip, divider, popup, warn colour and the
   candidate/tools/AI/editor surfaces derive from the same tokens as before; nothing new to define.
4. **Contrast.** `ThemeContrastTest` (WCAG 2.x) already held every theme to: key and special labels 4.5:1 on their
   fill and on the pressed fill, Enter text on accent 4.5:1, hints and accent labels 3:1, strip, popup and the DRAFT /
   caution colour 4.5:1, at every gradient stop. It runs over the whole catalogue, new and old; no existing theme is a
   known exception.
5. **Strings.** Each name has a `theme_name_<id>` resource and an entry in `ThemeNames` (en, and DRAFT am/ti/om as all
   UI strings, rule N9). Gallery section titles Light, Dark, Gradient are new app strings.
6. **Settings strip** is a `LazyRow` of every theme (applied first and checked, then System, free, premium), tap
   applies, a locked premium theme still opens the picker. "View more" stays.
7. **Gallery** (`ThemesScreen`): three cards per row grouped Light (with System), Dark, Gradient, Premium; the applied
   card has a primary outline and a check; names wrap to two lines. Previews are drawn from the same tokens as the
   keyboard (`KeyboardThemePreview`), so the new themes show their keys, borders, shadows and accent as they will look.
8. **Persistence** is unchanged (`SettingsRepository` stores any well-formed id); a test round-trips the new ids.

## Verification
Catalogue, contrast, gallery and persistence tests; `ToolbarRenderTest` renders keys, tools and clipboard per new
theme with `QAL_RENDER_DIR=<dir> QAL_RENDER_NEW=1`.
