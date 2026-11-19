# Prediction engine: audit and design (ADR 0039)

Status: implemented on branch `r2-predict` (2026-11-19). DRAFT data everywhere (N9): every shipped word list is a
hand-written seed, not native-speaker reviewed (gate G0).

## 1. Audit before ADR 0039 (what existed)

| Capability | am / ti Phonetic | am / ti Fidel grid | om Qubee | en | ar |
|---|---|---|---|---|---|
| Candidates while typing | **Yes**: composition + transliteration alternatives (homophones, labialised, initial vowels), then dictionary completions and one-edit corrections (ADR 0004 U6, ≥2 reserved slots) | No strip | **Yes**: word composes once the om dictionary is loaded; word as typed + completions / corrections | **No**: plain passthrough, although the en seed dictionary and bigrams were bundled and loaded | No (passthrough, no licensed data) |
| Ranking | lexicon frequency + previous-word bigram (core `Ranker::rank`) | — | same | — | — |
| Completions of a partial word | Yes, but placed after *every* homophone, so mostly hidden behind non-words | — | Yes | — | — |
| Autocorrection | Never (by design) | Never | Never applied, corrections only offered | None | None |
| Next-word prediction | No (`Ranker::next_words` existed, unused) | No | No | No | No |
| Undo of a correction | — | — | — | — | — |
| Learning | Yes: `WordLearner` (2nd sighting, D18) → `UserDictionary` (encrypted, per language) → core overlay; learned pairs feed bigrams | Yes (same) | Yes | Yes (overlay exists, never suggested back) | No (`learnsWords` excluded Arabic) |
| Learning controls | No setting to stop learning; Dictionary page lists words, deletes one, "Clear all"; consent-gated sync (ADR 0018) | | | | |
| Strip UI | Flowing left-to-right list, index 0 bold, DRAFT tag, long-press adds to dictionary; no slots, no autocorrect mark, no RTL | | | | |

Core pieces already implemented and benchmarked but not wired: `Ranker::autocorrect` (harm guard), `next_words`,
`frequent`, guard lexicons for code-switching, keyboard-adjacency edit costs. Repeated-key bug: see
[`phonetic-coverage-am-ti.md`](phonetic-coverage-am-ti.md) §4 (fixed separately before this work).

Data (all `LicenseRef-Qal-Original`, hand-written seeds, frequencies are placeholders): word lists am 96, ti 92, om 88,
en 100 words; bigrams am 8, ti 6, om 6, en 9 pairs. No Arabic data (no licensed source, N10).

## 2. What is implemented now (per language)

| | Suggestions while typing | Autocorrection | Next word | Learning |
|---|---|---|---|---|
| **en** (English layout) | word composes; typed word + completions + one-edit corrections, frequency + previous word | **On by default**; Space / Enter / `. , ! ? ; :` apply a confident correction; Backspace undoes | bigrams + user pairs, filled with frequent words | yes |
| **om** (Qubee) | as before | available, **off by default** (§4 of the ADR) | yes | yes |
| **am / ti Phonetic** | composition, then dictionary-word homophones, completions, other homophones | never | yes (after Space or a pick) | yes |
| **am / ti Fidel grid** | completions of the letters typed since the last boundary (+ the pending syllable); a pick replaces them | never | yes | yes |
| **ar** | user-learned words only (no licensed list): completions once the user has Arabic words | never | from the user's own word pairs only | **yes (new)** |
| ABC / English writing mode of am/ti | none (Latin text in an Ethiopic language) | never | no | no |

Everywhere: no candidates in password fields; no autocorrection in e-mail, URL, person-name, postal-address and filter
fields or where the app sets `TYPE_TEXT_FLAG_NO_SUGGESTIONS` (no strip at all there); nothing learned in incognito
(`IME_FLAG_NO_PERSONALIZED_LEARNING`) fields or when "Learn from my typing" is off.

### Strip (Gboard-like)
Three slots: centre = what Space commits (bold); left / right = the next two candidates; thin separators; Arabic
mirrors left and right. An autocorrection is the centre candidate with an accent underline, and the typed word is on
the left in quotes. More than three candidates: a chevron expands to the full list. Tapping inserts the word with one
space (none if a space follows the cursor); Space right after adds no second space; attaching punctuation replaces it.

### Settings
Preferences → *Text correction*: "Next-word suggestions" (default on), "Auto-correction: English" (on), "Auto-correction:
Afaan Oromoo" (off, DRAFT), "Learn from my typing" (on). Per-language "Show suggestions" switches stay on the language
pages; "Clear all" learned words stays on the Dictionary page.

## 3. Engine design (core)
- `PredictionOptions { suggestions, next_word, autocorrect }`, set per field by the host.
- `WordContext` (`core/src/predict.rs`): previous word, letters of the current word, sentence start, last character;
  follows commits and deletions; rebuilt from host text by `set_cursor_context`.
- Strip ownership: phonetic compositions keep `PhoneticSession`'s strip; everything else (`Strip::Word`,
  `Predictions`, `Fidel`) is held by the engine so `select_candidate` resolves exactly what the host shows. Ranking
  runs outside the state lock with a generation check.
- Autocorrection: `Ranker::autocorrect` (harm guard) at the boundary, plus the engine's own rules (word must start at a
  boundary, not restored by the user). `Undo { typed, remove, context }` lives until the next event.
- Arabic: `LangModel { dictionary: None }` created on the first learned / added / imported word.

## 4. Latency
Key-path work is bounded: the word context holds ≤ 48 characters of the current word and one previous word; the
ranker caps typed words at 48 characters, spelling variants at 72 and results at 50; ranking runs outside the state
lock. Measured numbers in §6.

## 5. Data and licences
Only the bundled seed packs are used (all `LicenseRef-Qal-Original`, recorded in the pack manifests and
`language-data/licenses.json`). No new data was added. **Limitation:** English with a 100-word list predicts little and
autocorrects only into those words. `language-data/licenses.json` and `language-data/sources/` record no licensed
English frequency list, so none was added. Recommended for legal review (OI-R7), in order of preference:
1. **SCOWL / 12dicts word lists** (Kevin Atkinson; permissive copyright notice similar to MIT/BSD) for the word set,
   with frequencies from
2. **Google Books Ngram Viewer exports** (CC BY 3.0) or **wordfreq** data (CC BY-SA 4.0; ShareAlike needs the legal
   review the allow-list already notes), or
3. a Qal-collected, consented corpus (`OWN_CREATION`) once the data programme exists.
Arabic needs the same (e.g. a CC BY-SA Wikipedia-derived frequency list after legal review); until then Arabic
suggestions are the user's own words only.

## 6. Measurements
`cargo bench --bench engine` (release build, Windows dev box, Intel 64-bit; 200k-word synthetic lexicons with
corpus-like counts and 20k bigram pairs; 3,000 timed words after 200 warm-up words). The SD 17.1 budget is p95 ≤ 30 ms
on a mid-range phone; the bench gates on 10 ms p95 on the host as a proxy (expect a phone to be 3–5× slower).

| Scenario | p50 | p95 | p99 |
|---|---|---|---|
| Phonetic + lexicon, letters (pre-0039 options) | 0.20 ms | 0.58 ms | 2.0 ms |
| Phonetic, all predictions on, letters | 0.20 ms | 0.53 ms | 1.1 ms |
| Phonetic, all predictions on, Space (commit + next-word) | 0.48 ms | 0.79 ms | 1.8 ms |
| English, all on, letters (completions + autocorrect mark; 30 % typos) | 0.82 ms | 6.0 ms | 8.4 ms |
| English, all on, Space (autocorrect + next-word) | 2.1 ms | 4.3 ms | 6.1 ms |

The English letter p95 is dominated by one-edit corrections of unknown 6–12-letter words (≈ 60·n neighbours, each
looked up for the ranked strip and again for the autocorrect decision). It is within the host budget; on a slow
phone it should be re-measured (A1.9). If needed the two passes can share one neighbour set.
