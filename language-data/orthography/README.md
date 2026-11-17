# Latin orthography data (C1.5)

`om-qubee-draft.json` and `en-latin-draft.json` (`qal-latin-orthography` v1, parsed by
`core/src/latin.rs`) describe how Latin-script languages are segmented: which letters exist, which
multi-letter units (digraphs, long vowels) are single letters, and which apostrophe characters are
word-internal (Qubee hudhaa, English contractions). Typing itself is plain passthrough (System Design
7.1: Qubee is `LATIN_PASSTHROUGH` plus an Oromo lexicon); this data only drives segmentation and
lexicon-key folding. Status is **DRAFT** (rule N9), not reviewed by native speakers.

Assumptions for native-speaker review:
1. Unit list for Afaan Oromoo: `aa ee ii oo uu` (long vowels), `ch dh ny ph sh` (SD 7.3) and the
   "rarer" `ts zy` (SD says rarer, spelling in use unconfirmed). `ng`, `kh`, `ts` variants not listed.
2. The apostrophe (hudhaa) U+0027, U+2019 and U+02BC are all the same letter and are word-internal only
   between two letters; at word start or end it is punctuation (a closing quote). A word-final hudhaa
   (e.g. in some loan or dialect spellings) would therefore be split off.
3. Backspace after commit removes one grapheme cluster (the host's view), so `dh` takes two presses;
   digraph-aware backspace is not required by the SD.
4. The engine never auto-capitalises or rewrites typed text in Qubee, English or ABC.
