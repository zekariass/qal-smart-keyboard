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
