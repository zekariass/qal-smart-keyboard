# Golden vectors

## Phonetic (C1.4): `am-phonetic-0.2.0.json`, `ti-phonetic-0.2.0.json`
Profile 0.2.0 (coverage audit, `docs/specs/phonetic-coverage-am-ti.md`) retired 0.1.0: its vectors
were carried over unchanged except for alternatives the new rules add (and `x`, now ሸ in Amharic);
0.1.0 was never released outside INTERNAL DRAFT packs, so its files were removed (see git history).
`am-phonetic-coverage.json` / `ti-phonetic-coverage.json` (run by `tests/phonetic_coverage.rs`) list
every required Fidel form with the key sequences that reach it, as default or strip alternative.
Run by `tests/phonetic.rs`: the profile `language-data/profiles/<lang>-phonetic-draft.json` is loaded
through a signed test pack, then `PhoneticLatin` mode is set. The concrete format differs slightly from
the sketch below (kept for reference):
- `keystrokes`: a one-character string is a `Char` key; `Backspace`, `Space`, `Enter`, `Commit`;
  `Select:<n>` calls `select_candidate(n)`.
- `steps` (optional): entries with `after` (number of keystrokes applied) and the expected `composing`,
  `commit`, optional `delete_before` (default 0) and optional `alternatives`.
- Per vector, after the last keystroke: `composing`, `alternatives` (candidates without the default;
  `candidates[0]` is always the composing text) and `committed` (text left after applying all commits
  and deletions) are all checked.
- `alternatives` are bounded by the engine's `max_candidates` (5 in the tests: default plus four).
- The corpora are DRAFT and pin the behaviour of DRAFT profiles; the G0 panel reviews both together.
  The release target of 2,000+ vectors per profile (System Design 18) is not met yet.

## Format sketch

Golden vectors pin the exact behaviour of the composing engine for one profile version.
One file per profile and version, e.g. `am-phonetic-0.1.0.json`:

```json
{
  "profile": "am-phonetic",
  "profile_version": "0.1.0",
  "vectors": [
    {
      "name": "basic-syllable",
      "keystrokes": ["s", "e", "l", "a", "m"],
      "steps": [
        { "composing": "ሰ", "commit": "", "alternatives": ["ሠ"] }
      ],
      "committed": "ሰላም",
      "alternatives": ["ሠላም"]
    }
  ]
}
```

- `keystrokes`: ordered key events (`Char` text, or the names `Backspace`, `Space`, `Enter`, `Commit`).
- `steps`: expected `composing` / `commit` / `alternatives` after each keystroke (optional per-step checks).
- `committed`: the total text committed after all keystrokes.
- `alternatives`: candidates offered at the end.

Rules: a profile change bumps `profile_version` and adds a new file; old files are kept until
that version is retired. The test runner must pass 100% of vectors (plan step C1.4).
Vectors contain only synthetic text, never user content (N2).

## Direct Fidel (C1.3): `fidel-direct-0.1.0.json`
Implemented and run by `tests/fidel_direct.rs`. Each vector names a `language` (`am` or `ti`, whose
draft layout from `language-data/layouts/` is loaded through a signed test pack) and lists
`keystrokes` with notation `C:<seriesId>` (FidelConsonant), `V:<order>` (FidelVowel), `T:<text>`
(Char) or `Backspace`/`Space`/`Enter`/`Commit`. `steps` has exactly one entry per keystroke with the
expected `composing`, `commit` and optional `delete_before` (default 0); `committed` is the text
that remains after applying all commits and deletions (one grapheme per `delete_before`).
