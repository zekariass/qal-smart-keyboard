# ADR 0004 — Candidate merge policy and the composing-text fallback

**Status:** Accepted · **Date:** 2026-11-17 · **Plan step:** A1.3 · **Spec:** System Design §6.1, §6.2, §7.2, §7.4, §17.1

## Context
A1.3 connects the phonetic composer (C1.4) to a candidate strip and to real host editors. Two
choices in it are not obvious from the SD:

1. how transliteration alternatives and dictionary candidates (C1.6 lexicon, C1.7 ranker, L1.3
   `DICTIONARY` / `FREQUENCY_LIST` packs) share one strip, and
2. how the keyboard notices that a host app does not support composing text (SD 6.2: "MUST handle
   apps that reject composing text by falling back to commit-only mode") and what it does then.

## Decision 1: candidate merge policy (core, `KeyboardEngine`)
- `load_pack` keeps one lexicon + ranker per language, rebuilt when a `DICTIONARY` or
  `FREQUENCY_LIST` pack is activated. Verification, limits and rollback slots are the unchanged
  C1.2 path. Like `TRANSLITERATION_RULES`, the payloads are opaque to the container check: a verified
  pack whose dictionary does not parse is activated but yields no candidates (the previous model of
  that language is dropped, so nothing comes from a superseded pack); unparsable bigrams leave the
  ranker with unigram frequencies. Rejecting such packs with `InvalidArtifact` was considered, but the
  cross-language pack vectors (`valid-optional-fields-pretty` is a `DICTIONARY` with an opaque
  artifact) and the C1.2 fixtures define that a verified container loads; changing that is a
  contract change for B3.2, not an A1.3 decision.
- In `PhoneticLatin` the strip is: **index 0 = the default composition, then the transliteration
  alternatives in their C1.4 order (kind `Transliteration`), then the ranker's candidates for the
  composition** (`Completion` for exact/variant/prefix completions, `Correction` for one-edit
  corrections), de-duplicated by exact text, capped at `max_candidates`. The C1.4 golden vectors
  therefore hold unchanged with or without dictionaries (tested: the transliteration list is an
  unchanged prefix).
- Ranking context: the core remembers the last committed word (in memory only, cleared by `reset`,
  `set_input_mode`, deletions and sentence punctuation) and passes it to the ranker for bigrams.
- Lexicon lookups run only when the composition is entirely Ge'ez (not for protected Latin tokens
  or pending punctuation).
- **Harm guard:** nothing is applied automatically. Corrections are offered on the strip; the
  composition is committed as typed unless the user taps a candidate. Autocorrect-on-space (C1.7
  `Ranker::autocorrect`) is not wired; it needs a product decision and a native-speaker eval.
- Ranking runs outside the engine's state lock. A generation counter discards results if another
  call changed the state meanwhile, so `select_candidate(i)` always matches the strip the host shows.
  Host-measured key path with a 200k-word dictionary: p95 0.22 ms (`cargo bench --bench engine`).

Trade-off: with many homophone alternatives the dictionary candidates can be pushed off the strip,
and the dictionary does not yet promote the conventional spelling of a homophone to index 0 (SD 7.2
says "dictionary frequency picks the conventional spelling"). Both need native-speaker input and
real frequencies (gate G0) before the order changes; the merge is in one function
(`with_lexicon_candidates`) so it can change without touching hosts.

## Decision 2: detecting hosts that reject composing text (Android `InputConnectionAdapter`)
Each `EditorUpdate` is one batch edit: delete grapheme clusters (converted to UTF-16 with
`BreakIterator` over `getTextBeforeCursor`), `commitText`, then `setComposingText` /
`finishComposingText`. The editor session switches to **direct-commit** (composition committed as
plain text and replaced by delete + recommit of only the changed suffix) when:

1. `EditorInfo.inputType == TYPE_NULL` (no text model; such hosts also get deletions as DEL key
   events, one per code point of our own text), from the start of the session;
2. `setComposingText` returns false;
3. an `onUpdateSelection` report puts the cursor exactly where one of our edits that left
   composing text put it, but reports no composing span (`candidatesStart == -1`), and no other
   pending edit of ours had that cursor position.

To tell our own edits from user cursor moves, the adapter computes the cursor each edit should
produce and matches reports against these expectations in order (reports can arrive late, after
further keys; one report per batch edit; edits that change nothing are not expected to report).
A report that matches none is a change we did not make (tap, selection, app edit): the composing
text stays in the editor as plain text (`finishComposingText`) and the composer is reset, so text is
neither lost nor committed twice. The report of that `finishComposingText` is itself expected:
on the emulator it arrived after the next key and, before this rule, abandoned the new
composition (typing "de" right after a tap gave ድእ instead of ደ). After edits whose effect cannot be predicted (DEL/ENTER key
events, editor actions, an unknown initial selection) the next report that fits the current state
re-synchronises the position and no decision is taken before that. `onFinishInput`,
`onFinishInputView` and mode switches finish the composition the same way.

Backspace: a non-empty selection is deleted with a DEL key event; editors that return no text for
`getTextBeforeCursor` (or an empty field) get DEL key events so apps that implement their own
Backspace (recipient chips, terminals) keep working; otherwise `deleteSurroundingText` removes exactly
the grapheme clusters the core asked for (one Fidel syllable, a whole emoji sequence).

## Consequences and limits
- Detection rule 3 needs a report. If a host inserts composing text as plain text *and* the user
  types faster than the host reports, the first composition may already contain a duplicated
  syllable when the mismatch is seen; the adapter then abandons that composition (no further
  damage) and continues in direct-commit. Hosts that neither report nor answer text queries are only
  covered by rules 1 and 2.
- A host that changes our text (input filters, inline autocomplete) is treated as an external change:
  the composition is finished early, never duplicated.
- Direct-commit mode lasts for the editor session (`onStartInput` re-evaluates).
- Verified on the JVM against a fake editor modelled on `BaseInputConnection` (late reports, rejected
  spans, `setComposingText == false`, TYPE_NULL, emoji/Fidel deletion) and end to end with the real
  core; on the API 35 x86_64 emulator only in the Google Contacts editor (a host that supports
  composing text). The fallback paths have not met a real rejecting host yet; A1.9 should add a
  terminal (TYPE_NULL) and a WebView/Chrome field to the device matrix.
- Revisit if the reference-device QA (A1.9) finds hosts that defeat the heuristic; the policy lives in
  `InputConnectionAdapter` only.
