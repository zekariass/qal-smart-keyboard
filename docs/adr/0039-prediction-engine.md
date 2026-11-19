# ADR 0039 — Prediction engine: suggestions, autocorrection, next word, personalisation

**Status:** Accepted · **Date:** 2026-11-19 · **Requested by:** product owner · **Benchmark:** Google Gboard's
suggestion strip (its patterns, not its colours or branding) · **Builds on:** ADR 0004 (composer candidates, U6 merge),
ADR 0007 (D18 learn on the second sighting), ADR 0018 (dictionary sync), ADR 0034 (Arabic), ADR 0035 (one bar) ·
**Spec / audit:** [`docs/specs/prediction-engine.md`](../specs/prediction-engine.md)

## Context
The core already had a ranker (C1.7: completions, one-edit corrections with keyboard adjacency, bigram context,
next-word predictions, an autocorrect decision with a harm guard) and a per-language user overlay (A1.8). Only part of
it reached the keyboard: am/ti Phonetic showed transliteration alternatives plus dictionary completions, Afaan Oromoo
Qubee showed dictionary words for the word being typed, and nothing else did. English typed with no suggestions at all
although its seed dictionary and bigrams are bundled; the Fidel grid and Arabic had no strip; nothing predicted the
next word; nothing was ever corrected automatically; learning could not be switched off. The product owner asked for a
Gboard-like prediction experience in every language, with the logic in the shared Rust core.

## Decisions

### 1. Everything language-related lives in the core
`KeyboardEngine` owns tokenisation (the word being composed, the word context that follows committed text), ranking,
completions, next-word prediction, autocorrection and its undo, and the spacing around picked suggestions. Kotlin only
renders the strip, inserts text through the `InputConnection` adapter and decides per field which options apply. New
FFI: `set_prediction_options(PredictionOptions { suggestions, next_word, autocorrect })`, `set_cursor_context(before,
after) -> EditorUpdate`, and `EditorUpdate.autocorrect_index`.

### 2. Word modes compose the current word
English (with its dictionary loaded), Afaan Oromoo Qubee (as before) and Arabic (once the user has Arabic words, §6)
hold the word being typed as composing text so a suggestion can replace it. Text is still committed exactly as typed
unless an autocorrection applies (§4). Direct Fidel does not compose words (each syllable is committed); its strip
completes the letters committed since the last word boundary plus the pending syllable, and a pick deletes those letters
and inserts the word. Phonetic am/ti keep their composition (ADR 0004).

### 3. One strip order: what Space commits is in the middle
Candidates keep the core's indices (index 0 = the composition / the word as typed), so `select_candidate` is unchanged.
The bar shows three slots, Gboard-like: **centre = what Space commits** (the composition, or the correction Space will
apply), bold; left and right the next two candidates; thin separators; RTL languages (Arabic) mirror left and right.
When autocorrection will apply, the correction is in the centre with an accent underline and the typed word moves to
the left slot in quotes ("keep what I typed"). More than three candidates add a chevron that expands the strip into
the full flowing list (the pre-0039 layout) and back. The strip owns the middle of the ADR 0035 bar only while it has
candidates; the tools button, writing-mode pill and microphone keep their places. The DRAFT tag (N9) stays.

Phonetic merge (amends ADR 0004 U6): composition, then the transliteration alternatives that are dictionary words,
then completions and corrections (frequency + previous word, two reserved slots), then the alternatives that are not
words. The previous order put every homophone before any completion, which hid completions behind non-words once the
strip shows three slots.

### 4. Autocorrection: English on, Afaan Oromoo off by default
Applied only at a word boundary (Space, Enter, `. , ! ? ; :`) in English and Afaan Oromoo, and only when the ranker's
harm guard says `Apply`: the word is not a known or user word (in either Latin language), at least three letters, all
letters, no capital mid-sentence (a name), within one weighted edit (adjacent keys and transpositions are cheaper), same
first letter, posterior ≥ 0.85 against every rival and against "the typed word is meant". The engine also refuses
words glued to `@`, `/`, `.` or digits (addresses, URLs, numbers) and words after a cursor move into a word. A
Backspace right after a correction restores the typed word (composing again, not corrected a second time); any other
key ends the undo window. The host never enables it in password, e-mail, URL, name, address or filter fields, nor
where the app asked for no suggestions.

Defaults: **on for English**, **off for Afaan Oromoo**. The om lexicon is a ~90-word DRAFT seed that no Oromo speaker
has reviewed (gate G0); with so few words almost every real word is "unknown" and a one-edit neighbour of some seed
word, so automatic corrections would mostly be wrong. Suggestions are still shown; the user can turn om autocorrection
on. am/ti are never auto-corrected (the transliteration is the user's spelling; Fidel words are not typed by edit
distance) and Arabic has no shipped lexicon.

### 5. Next-word prediction and spacing
After a word boundary the strip predicts the next word from the bigram pack plus the user's own word pairs (weighted
×20), filled with frequent words; never the previous word itself. Shown only when a previous word is known (not at
the start of a sentence). Picking any suggestion inserts it followed by a space unless the text after the cursor
already starts with whitespace; a Space typed right after adds no second space, and punctuation that attaches to a
word (`. , ! ? ; : ) ። ፣ ፤ ፥ ፧ ، ؛ ؟`) replaces the added space. After a cursor move the host passes the text around
the cursor (`set_cursor_context`, at most 96 characters before and 1 after, in memory only), so predictions, the
sentence-start rule and Fidel completions work mid-text.

### 6. Personalisation
Learning stays as A1.8/D18 (second sighting, per language, encrypted on the device, never in password, incognito,
e-mail, URL, number or phone fields; sync only through the consent-gated dictionary sync, ADR 0018). New: a **"Learn
from my typing"** setting (default on) stops new learning; "Clear all" on the Dictionary page stays the one place to
clear learned words. Arabic now learns too: with no licensed Arabic word list (N10) its model is the user's own words
and word pairs only, created on first use, so Arabic suggestions and next-word predictions come only from what the user
typed.

### 7. Privacy and performance
No network on the key path; nothing typed is logged or persisted by the core; the word context is a few dozen
characters in memory, cleared on reset, mode switch, cursor move and deletions it cannot follow. Ranking runs outside
the state lock; a stale result is dropped (generation check), so the strip always matches `select_candidate`.
Latency (release build, 200k-word synthetic lexicons, `cargo bench --bench engine`): phonetic letters p95 0.5 ms,
English letters p95 6.0 ms (completions + autocorrect decision on typos), English Space p95 4.3 ms; host budget 10 ms
(spec §6).

## Consequences
- Picking a phonetic or Qubee candidate now adds a space (Gboard behaviour); tests and docs updated.
- English needs a real licensed frequency list before release: the seed has ~100 words. No such source is recorded in
  `language-data/licenses.json`/`sources`, so none was added (spec §5 recommends candidates for legal review).
- Not done: re-composing a committed word when the cursor is placed inside it (Gboard does), gesture typing, emoji
  predictions, cross-language candidates in one strip, learned-word blocklisting from the strip.
