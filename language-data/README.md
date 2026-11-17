# language-data

Source rules, licensed wordlists, eval sets and build scripts that produce signed packs. Every source needs a commercial-use licence entry. Plan steps L1.x, F0.6.

See docs/Ethiopian_AI_Keyboard_Implementation_Plan_v2_0.md for the steps that populate this folder.

## Fidel (Ethiopic) data (C1.3)
- `scripts/gen_fidel_table.py` generates `generated/fidel-table-v1.json` and
  `core/src/composer/fidel_table.rs` from Unicode character names (`unicodedata.name()`), never from
  code point arithmetic. `--check` exits 1 when a file is stale; `scripts/test_gen_fidel_table.py`
  runs that check. Needs Python with Unicode 14+ data (3.11 has it).
- `layouts/am-fidel-draft.json`, `layouts/ti-fidel-draft.json`: source `KEYBOARD_LAYOUT` artifacts
  (format: `docs/specs/keyboard-layout-v1.md`), status DRAFT pending native-speaker review (G0).
  The L1.1 pack build tool wraps them into signed packs.
