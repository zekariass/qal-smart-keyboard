# Phonetic transliteration profiles (C1.4)

`am-phonetic-draft.json` and `ti-phonetic-draft.json` are source `TRANSLITERATION_RULES` artifacts
(`qal-transliteration-profile` v1, parsed and validated by `core/src/translit/profile.rs`). Status is
**DRAFT** until the native-speaker panel (gate G0) approves them; they must never be shown to users as
verified (rule N9; hosts read the status through `KeyboardEngine::translit_profile`). The L1.1 pack build
tool wraps them into signed packs. Golden corpora: `core/tests/golden/am-phonetic-0.4.0.json`, `core/tests/golden/ti-phonetic-0.4.0.json`; coverage matrices (every required
Fidel form and the sequences that reach it, checked end to end by `core/tests/phonetic_coverage.rs`):
`core/tests/golden/{am,ti}-phonetic-coverage.json`, explained in `docs/specs/phonetic-coverage-am-ti.md`.
Syllable forms come from the generated Fidel table (`../generated/fidel-table-v1.json`); profiles only
name series ids and order numbers.

## Scheme (all DRAFT, needs native-speaker review)
- Syllable = consonant + vowel. Vowels: `e` 1st order, `u` 2nd, `i` 3rd, `a` 4th, `ee` or `ie` 5th,
  `I` (capital) explicit 6th, `o` 7th. A consonant with no vowel shows the 6th order (`s` gives ስ).
- Digraphs: `sh ch ny zh kh hh ts`; Tigrinya also `qh` (ቐ). Single keys (0.2.0): `c` ቸ; Amharic `x` ሸ,
  Tigrinya `x` ኸ. Ejectives with an apostrophe: `t' ch' c' p' ts' s' k'` (ጠ ጨ ጨ ጰ ጸ ጸ ቀ).
- Homophone letters, deterministic (0.2.0, SERA-style backtick): `` `s `` ሠ, `` `h `` ኀ, `` `ts `` ፀ, and
  `` ` `` + vowel for the ዐ series (`` `a `` ዓ, `` ` `` alone ዕ).
- Labialised forms: capital `W` after the consonant (`gWa` gives ጓ, `qWe` gives ቈ, `lWa` gives ሏ, `hWa`
  gives ኋ: `h` maps to the ኀ labiovelar series). If no such form exists the `W` is read as the consonant.
  Lowercase `w` (`labialAltMarker`) stays the consonant ወ by default (`gwa` gives ግዋ) but the labialised
  form is offered as the first alternative (ጓ).
- Doubled consonants **before a vowel** collapse (`messeret` gives መሰረት, Fidel does not mark gemination); the
  two-letter reading is offered as the last alternative (`messeret`: መስሰረት). A doubled consonant with **no vowel
  after it** types two letters: pressing a key twice gives the letter twice (`ggr` ግግር, `gg` ግግ; engine rule since
  the r2 repeated-key fix, no profile change). `_` separates letters that would otherwise combine (`s_h` gives ስህ,
  `s_se` gives ስሰ).
- A vowel typed without a consonant uses the glottal series: `a` አ, `e` እ, `u` ኡ, `i` ኢ, `ee` ኤ, `I` እ, `o` ኦ.
  Tigrinya (0.3.0) writes initial /a/ with its own letter: `a` gives ኣ (ኣብ, ኣነ), with አ as the first alternative.
- Case is folded, except the explicit `I`, the labial marker `W` and the **capital letters** below.
- **Capitals are their own letters** (am 0.3.0, ti 0.4.0, product owner's request): `N` ኝ series (`Na` ኛ), `T` ጥ
  series (`Te` ጠ), `K` ኽ series (`Ke` ኸ), `P` ጵ series (`Pa` ጳ) by default, with the plain letter (ን ት ክ ፕ) as the
  alternative; `Q` ቐ series (`Qe` ቐ … `Qo` ቖ, `QWa` ቛ, alternative ቀ) in Tigrinya (ti 0.4.0) and Amharic (am
  0.4.0, ADR 0044; the Amharic Fidel grid still has no ቐ key). The lowercase digraphs keep working (`ny`,
  `t'`, `kh` / ti `x`, `p'`, ti `qh`); Amharic lowercase `q` and `k'` stay ቀ. `K` is ኽ and `Q` is ቐ (the common
  Ge'ez keyboard convention; the owner also wrote "K" for ቕ, but one key cannot do both).
  Still folded with the SERA reading as an alternative: `C` (ቸ, alt ጭ), `S` (ስ, alt ሥ/ጽ), `Z` (ዝ, alt ዥ), so names
  such as Selam or Zewdu keep their letter; the panel decides whether these follow. Because capitals now change the
  letter, the keyboard never auto-capitalises in Phonetic mode (only a manual Shift gives a capital).
- Alternatives (one substitution at a time, left to right, capped by `max_candidates`): homophone series
  (ሰ/ሠ, ሀ/ሐ/ኀ, ጸ/ፀ; Tigrinya ቀ/ቐ), and for a standalone vowel the other glottal orders and the
  pharyngeal series in the same and the other orders (`a`: ኣ ዐ ዓ, Tigrinya አ ዓ ዐ; `e`: አ ዕ ዐ ኧ). A bare consonant
  followed by a lowercase-`w` syllable also offers the labialised letter (counts as one substitution).
- Punctuation by doubling: `..` ። `,,` ፣ `;;` ፤ `::` ፥ `??` ፧. A single `.` `,` `;` `:` `?` stays ASCII (it is
  held as composing text until the next key decides). On the phone the comma and period keys of the Phonetic and Fidel
  layouts type ፣ and ። directly (ASCII `,` / `.` first in their long-press popups), so the doubling is only needed
  from a hardware keyboard or the symbols page.
- Protected tokens stay Latin: a buffer starting with `http` or `www`, anything containing `@`, and the
  listed product names (`whatsapp`, `telegram`, ...; transliteration is offered as the alternative).
  The real list will come from the `PROTECTED_TERMS` pack and lexicon (C1.6).
- Digits and other non-letter keys end the word and are committed as typed.

## Open questions for the G0 panel
1. `e` as 1st order (ሰ) but a standalone `e` as እ (6th); `a` standalone as አ in Amharic, ኣ in Tigrinya (0.3.0).
2. `ie` as 5th order conflicts with a genuine i + e sequence (use `_`).
3. Ejective apostrophes (`t'`) vs capital letters (SERA style `T`); 0.2.0 offers the capital readings
   only as alternatives so that names typed with a capital keep their default.
4. Which homophone (ሰ/ሠ, ሀ/ሐ/ኀ, ጸ/ፀ) is the default, and `ts` giving ጸ with ፀ as alternative.
5. Tigrinya: `x` for ኸ (Amharic `x` is ሸ), `qh` for ቐ, whether ቀ should offer ቐ and vice versa.
6. Doubling for punctuation (`..` ።), the `_` separator and the `W` labial marker.
7. Missing: a deterministic key for ኧ, Amharic ኣ and Tigrinya አ (alternatives only), ጐ-like forms for non-labiovelar series, gemination marking, Ethiopic numerals as
   alternatives for typed digits (C1.9 provides the conversion).
