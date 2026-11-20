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
