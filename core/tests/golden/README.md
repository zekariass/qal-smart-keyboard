# Golden vectors (phonetic format planned for C1.4)

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
