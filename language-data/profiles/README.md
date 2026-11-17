# Phonetic transliteration profiles (C1.4)

`am-phonetic-draft.json` and `ti-phonetic-draft.json` are source `TRANSLITERATION_RULES` artifacts
(`qal-transliteration-profile` v1, parsed and validated by `core/src/translit/profile.rs`). Status is
**DRAFT** until the native-speaker panel (gate G0) approves them; they must never be shown to users as
verified (rule N9; hosts read the status through `KeyboardEngine::translit_profile`). The L1.1 pack build
tool wraps them into signed packs. Golden corpora: `core/tests/golden/{am,ti}-phonetic-0.1.0.json`.
Syllable forms come from the generated Fidel table (`../generated/fidel-table-v1.json`); profiles only
name series ids and order numbers.

## Scheme (all DRAFT, needs native-speaker review)
- Syllable = consonant + vowel. Vowels: `e` 1st order, `u` 2nd, `i` 3rd, `a` 4th, `ee` or `ie` 5th,
  `I` (capital) explicit 6th, `o` 7th. A consonant with no vowel shows the 6th order (`s` gives ስ).
- Digraphs: `sh ch ny zh kh hh ts`; Tigrinya also `qh` (ቐ) and `x` (ኸ). Ejectives with an apostrophe:
  `t' ch' p' ts'`.
- Labialised forms: capital `W` after the consonant (`gWa` gives ጓ, `qWe` gives ቈ, `lWa` gives ሏ); lowercase
  `w` is always the consonant ወ. If no such form exists the `W` is read as the consonant.
- Doubled consonants collapse (`messeret` gives መሰረት, Fidel does not mark gemination); `_` separates
  letters that would otherwise combine (`s_h` gives ስህ, `s_s` gives ስስ).
- A vowel typed without a consonant uses the glottal series: `a` አ, `e` እ, `u` ኡ, `i` ኢ, `ee` ኤ, `I` እ, `o` ኦ.
- Case is folded (a sentence-initial capital is safe), except the explicit `I` and the labial marker `W`.
- Alternatives (one substitution at a time, left to right, capped by `max_candidates`): homophone series
  (ሰ/ሠ, ሀ/ሐ/ኀ, ጸ/ፀ; Tigrinya ቀ/ቐ), and for a standalone vowel the other glottal orders and the
  pharyngeal series (አ/ኣ/ዐ).
- Punctuation by doubling: `..` ። `,,` ፣ `;;` ፤ `::` ፥ `??` ፧. A single `.` `,` `;` `:` `?` stays ASCII (it is
  held as composing text until the next key decides).
- Protected tokens stay Latin: a buffer starting with `http` or `www`, anything containing `@`, and the
  listed product names (`whatsapp`, `telegram`, ...; transliteration is offered as the alternative).
  The real list will come from the `PROTECTED_TERMS` pack and lexicon (C1.6).
- Digits and other non-letter keys end the word and are committed as typed.

## Open questions for the G0 panel
1. `e` as 1st order (ሰ) but a standalone `e` as እ (6th); `a` standalone as አ (not ኣ).
2. `ie` as 5th order conflicts with a genuine i + e sequence (use `_`).
3. Ejective apostrophes (`t'`) vs capital letters (SERA style `T`); capitals are avoided because of
   automatic sentence capitalisation.
4. Which homophone (ሰ/ሠ, ሀ/ሐ/ኀ, ጸ/ፀ) is the default, and `ts` giving ጸ with ፀ as alternative.
5. Tigrinya: `x` for ኸ, `qh` for ቐ, whether ቀ should offer ቐ and vice versa.
6. Doubling for punctuation (`..` ።), the `_` separator and the `W` labial marker.
7. Missing: ኧ, ጐ-like forms for non-labiovelar series, gemination marking, Ethiopic numerals as
   alternatives for typed digits (C1.9 provides the conversion).
