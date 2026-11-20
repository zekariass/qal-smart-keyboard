# Phonetic (Latin to Ge'ez) coverage, Amharic and Tigrinya

Status: **DRAFT** for the native-speaker panel (gate G0, rule N9). Describes profiles
`am-phonetic` **0.4.0** and `ti-phonetic` **0.4.0** (`language-data/profiles/*-phonetic-draft.json`), engine
`core/src/translit/`. Everything here is provisional: the panel may change any mapping, and every
change is a data edit plus a new golden/coverage version, not code.

Machine-checked sources (this page mirrors them):
- `core/tests/golden/{am,ti}-phonetic-coverage.json`: the required forms and, per form, the key
  sequences that give it as the default composition or as a strip alternative. Checked end to end
  (signed test pack, `KeyboardEngine`, no dictionary, `max_candidates` 5) by
  `core/tests/phonetic_coverage.rs`, which also derives checks from every rule of the profile
  (input alone, input + each vowel, input + `W` + each vowel), so a dropped or broken rule fails CI.
- `core/tests/golden/am-phonetic-0.4.0.json`, `core/tests/golden/ti-phonetic-0.4.0.json`: behaviour vectors (live composition,
  alternatives, commits, backspace).

## 1. The report: "ሽ and ኽ are missing"

Findings (audit 2026-11-18):

1. **Not a composing bug.** `sh` gives ሽ and `kh` gives ኽ, in the core (golden vector
   `digraph-sh-longest-match-and-backspace`, `report-sh-and-kh-sixth-order`) and through the
   Android key path (layout key, `KeyboardController`, `CoreComposer`, `InputConnectionAdapter`;
   JVM test `CoreTypingTest.digraphsReachShAndKhThroughTheRealKeys`, which passes with the 0.1.0
   packs as shipped). Longest match re-tokenises the whole buffer after each key, so `s` then `h`
   always becomes ሽ; no path commits the `s` early, and the shipped pack carried the same profile as
   the JSON source.
2. **The letters were only reachable through undiscoverable digraphs.** The key hints show what
   each key gives alone, so no key showed ሽ, ኽ, ቸ, ኘ, ዠ, ጠ, ጨ, ጰ or ጸ.
   - `x`, the most common Latin key for ሸ (SERA and most Ethiopic phonetic keyboards), had **no
     rule** in Amharic: it was committed as a Latin letter (`xe` gave `xእ`). `c` (ቸ) likewise.
   - Capitals were case-folded, so `K` (ኸ in SERA-style schemes) gave ክ, `T` gave ት, and so on.
3. **Some forms were not reachable at all**, by any sequence: ኈ ኊ ኋ ኌ ኍ (`hWa` fell back to
   ህዋ: the h series had no labiovelar mapping and ኀ was only a homophone alternative; ኋ is common,
   e.g. ኋላ), ዓ (common: ዓመት, ዓለም, ሰዓት) and ኧ. The ሠ, ኀ and ፀ series and most of ዐ were
   reachable only as strip alternatives.
4. **Lowercase `w`** (`gwa`, `kwas`, `hwala`, the way most people spell labialised syllables) never
   offered the labialised letter; only the capital marker (`gWa`) did.

## 2. Coverage before and after

Required forms: the 34 Amharic consonant series (ሀ ለ ሐ መ ሠ ረ ሰ ሸ ቀ በ ቨ ተ ቸ ኀ ነ ኘ አ ከ ኸ ወ ዐ ዘ ዠ የ ደ
ጀ ገ ጠ ጨ ጰ ጸ ፀ ፈ ፐ) in orders 1 to 7, the eighth ("wa") order of every series that has one (ሏ ሗ ሟ
ሧ ሯ ሷ ሿ ቧ ቯ ቷ ቿ ኗ ኟ ኧ ዟ ዧ ዷ ጇ ጧ ጯ ጷ ጿ ፏ ፗ), and orders 1/3/4/5/6 of the labiovelar series ቈ ኈ ኰ ጐ.
Tigrinya adds the ቐ series and the labiovelar series ዀ and ቘ.

| Profile | Required | Default | Alternative only | Unreachable |
|---|---|---|---|---|
| am 0.1.0 | 282 | 246 | 29 | 7 (ዓ ኧ ኈ ኊ ኋ ኌ ኍ) |
| am 0.2.0 | 282 | 280 | 2 (ኣ ኧ) | 0 |
| ti 0.1.0 | 299 | 263 | 29 | 7 (ዓ ኧ ኈ ኊ ኋ ኌ ኍ) |
| ti 0.2.0 | 299 | 297 | 2 (ኣ ኧ) | 0 |
| ti 0.3.0 | 299 | 297 | 2 (አ ኧ) | 0 |
| am 0.3.0 | 282 | 280 | 2 (ኣ ኧ) | 0 |
| ti 0.4.0 | 299 | 297 | 2 (አ ኧ) | 0 |
| am 0.4.0 | 282 (+12 listed ቐ forms) | 280 (+12) | 2 (ኣ ኧ) | 0 |

"Default" = some sequence gives exactly that letter as the composition; "alternative only" = it is
offered in the candidate strip (the lexicon ranks known words first, ADR 0004 U6).

**Fidel grid** (audit 2026-11-19, `core/tests/fidel_coverage.rs`): every required form of both matrices is
reachable on the language's own Fidel grid (`language-data/layouts/{am,ti}-fidel-draft.json`), by two keys
(consonant, then vowel order) or long-press; ዓ, ኧ and ኋ by two keys. The Tigrinya grid has its own ቐ key (ቐ…ቖ,
ቘ ቚ ቛ ቜ ቝ), and ኸ with ዀ…ዅ; the Amharic grid has no ቐ key. Both carry ። ፣ ፤ ፥ ፦ ፧ ፨ ፡ and Ethiopic numerals.

**ti 0.3.0** (language audit 2026-11-19, `docs/specs/language-audit-om-ti.md`): a vowel-initial `a` gives **ኣ**,
the Tigrinya spelling of initial /a/ (ኣብ, ኣነ, ኣደ, ኣስመራ; the ti seed lexicon and the UI glossary use it), with
አ as the first alternative. 0.2.0 typed Amharic's አ (አብ) and offered ኣ only in the strip, so every such
Tigrinya word needed a correction. Amharic is unchanged (`a` አ, alt ኣ). Every other input keeps its output.

**am 0.3.0 / ti 0.4.0** (product owner, 2026-11-19): capitals are their own letters. `N` → ኝ series, `T` → ጥ
series, `K` → ኽ series, `P` → ጵ series as the default (the plain ን/ት/ክ/ፕ is the alternative); Tigrinya `Q` → ቐ
series (ቐ ቑ ቒ ቓ ቔ ቕ ቖ for `Qe Qu Qi Qa Qee Q Qo`, labialised `QWe` ቘ, `QWi` ቚ, `QWa` ቛ, `QWee` ቜ, `QW` ቝ).
Decision recorded: **`K` = ኽ, `Q` = ቐ** (the owner also wrote "K" for ቕ; the common Ge'ez keyboard convention is
followed). (am 0.3.0 had no `Q`; superseded by am 0.4.0 below.) `C`, `S`, `Z` stay folded
(SERA reading as alternative) so names typed with a capital keep their letter; for the panel. Lowercase digraphs
(`ny t' kh x p' qh`) are unchanged, so the coverage counts are the same. The keyboard never auto-capitalises in
Phonetic mode; Shift shows each capital's letter as the key's hint. Golden vectors `capital-*` in both corpora.

**am 0.4.0** (product owner, 2026-11-19, ADR 0044): Amharic Phonetic gets Tigrinya's capital `Q` → ቐ series
(`Qe Qu Qi Qa Qee Q Qo` → ቐ ቑ ቒ ቓ ቔ ቕ ቖ, labialised `QWe` ቘ, `QWi` ቚ, `QWa` ቛ, `QWee` ቜ, `QW` ቝ), with the
plain ቀ series as the alternative; the shifted `q` key shows ቕ. Lowercase `q` and `k'` stay ቀ (no new
alternative, so no existing Amharic output or strip changes). The ቐ forms are not Amharic orthography, so they are
listed in the am matrix beyond `required` (checked the same way) and the Amharic Fidel grid still has no ቐ key.
Golden vector `capital-q-is-qh-in-every-order`.

Out of scope (not required, not mapped): the ninth-order "oa" forms (ሇ, ⶀ...), ፘ ፙ ፚ, the
Sebatbeit/Gurage/Bench letters (ᎀ..., ⶠ..., ꬁ..., 𞟠...), ዸ and ጘ series.

## 3. Scheme (0.2.0)

All 0.1.0 inputs keep their default output; 0.2.0 only adds inputs and alternatives.

| What | Keys | Example |
|---|---|---|
| Vowel orders after a consonant | `e` 1st, `u` 2nd, `i` 3rd, `a` 4th, `ee`/`ie` 5th, none or `I` 6th, `o` 7th | `selam` ሰላም |
| Digraphs | `sh` ሸ, `ch` ቸ, `ny` ኘ, `zh` ዠ, `kh` ኸ, `hh` ሐ, `ts` ጸ (ti: `qh` ቐ) | `shiro` ሺሮ |
| **New single keys** | `x` ሸ (am; ti keeps `x` = ኸ), `c` ቸ | `xiro` ሺሮ, `caw` ቻው |
| Ejectives (apostrophe) | `t'` ጠ, `ch'`/**`c'`** ጨ, `p'` ጰ, `ts'`/**`s'`** ጸ, **`k'`** ቀ | `t'ena` ጠና |
| **Homophone letters, deterministic** (backtick, SERA style) | `` `s `` ሠ, `` `h `` ኀ, `` `ts `` ፀ, `` ` `` + vowel ዐ series (`` `a `` ዓ, `` ` `` alone ዕ) | `` `amet `` ዓመት |
| Labialised (capital `W`) | consonant + `W` + vowel: labiovelar series or the "wa" form | `gWa` ጓ, `lWa` ሏ, **`hWa` ኋ** |
| **Labialised (lowercase `w`)** | default unchanged (`gwa` ግዋ); the labialised letter is the **first alternative** | `kwas` ክዋስ, alt ኳስ |
| **Capitals** | default unchanged (ት ቸ ፕ ስ ክ ን ዝ); SERA reading as **alternative**: `T` ጥ, `C` ጭ, `P` ጵ, `S` ሥ/ጽ, `K` ኽ, `N` ኝ, `Z` ዥ | `Tena` ተና, alt ጠና |
| Vowel without consonant | glottal series: `a` አ (alt ኣ, ዐ, **ዓ**; **ti 0.3.0: `a` ኣ**, alt አ, ዓ, ዐ), `e` እ (alt አ, ዕ, ዐ, **ኧ**), `u` ኡ, `i` ኢ, `ee` ኤ, `o` ኦ | `amet` አመት, alt ዓመት; ti `ab` ኣብ |
| Gemination | a doubled consonant **before a vowel** collapses (Fidel does not mark it; the two-letter reading is the last alternative); a doubled consonant **with no vowel after it** types two letters; `_` separates | `messeret` መሰረት (alt. መስሰረት), `ggr` ግግር, `gg` ግግ, `s_h` ስህ |
| Punctuation | `..` ። `,,` ፣ `;;` ፤ `::` ፥ `??` ፧; all eight marks (፡ ። ፣ ፤ ፥ ፦ ፧ ፨) are also keys on the symbols page in Phonetic mode | |
| Numerals | not in the profile: typed digits stay ASCII; Ethiopic numerals ፩–፼ are on the digit keys' long-press (C1.9 data) | |

Engine capabilities added (generic, data-driven, tested): the optional profile field
`labialAltMarker` (a consonant input that, after a bare consonant, also offers the labialised
form as an alternative), and alternative series of the initial (vowel-only) syllable are now offered
in the alternative orders too (ዓ next to ኣ). The per-key rule scan got a first-byte pre-check so the
larger profile does not slow the key path (bench `on_key, phonetic + lexicon` p50 unchanged within
noise on the dev box).

## 4. Ambiguities and decisions

| Ambiguity | Decision (0.2.0) | Why |
|---|---|---|
| `x`: ሸ (am convention) or ኸ (ti profile 0.1.0) | am `x` = ሸ (was unmapped); ti keeps `x` = ኸ | No existing am output changes; ti ኸ is frequent and `x` was a deliberate ti choice. Panel to confirm the split. |
| `c`: ቸ or ክ (English "cat") | `c` = ቸ | SERA and common keyboards; `c` was unmapped. |
| Capitals: SERA meanings vs names typed with a capital (`Tesfaye`, `Selam`) | 0.2.0: default stayed the folded lowercase. **am 0.3.0 / ti 0.4.0: `N T K P` (ti also `Q`; am `Q` from 0.4.0) are their own letters by default**; `C S Z` still folded | Owner's request; no auto-capitalisation in Phonetic, so only a deliberate Shift gives the capital letter. |
| Lowercase `w` after a consonant: ወ or labialisation | Default stays ወ (`gwa` ግዋ); labialised letter first alternative | `w` is also the consonant ወ and 0.1.0 output must not change; the lexicon promotes known words such as ኳስ, ኋላ. |
| Homophones ሰ/ሠ, ሀ/ሐ/ኀ, ጸ/ፀ, አ/ዐ | Common letter by default, others as alternatives, plus deterministic backtick sequences | Backtick is rare in text; on the phone it is on the symbols page (slow but certain). |
| ኋ: labialised ሀ or ኀ | `hWa` gives ኋ (the ኀ-series labiovelar; `h` now maps to it), `hwa` offers it first; ሗ as alternative | ኋ is the standard spelling (ኋላ). |
| ኣ vs አ word-initially | am: አ default, ኣ alternative. **ti 0.3.0: ኣ default, አ alternative** | Amharic writes initial /a/ አ; Tigrinya writes it ኣ (its lexicon and glossary do). Panel to confirm the ti default. |
| ኧ | Alternative of standalone `e` (after አ ዕ ዐ) | Rare letter (ኧረ); no deterministic key without breaking `W`/apostrophe uses. |
| Apostrophe as a glottal-stop marker (SERA `ma'ed`) | Not added | Would eat quote marks (`'selam'`). |
| `shsh`, `chch` | Before a vowel: one letter like any geminate; with no vowel after: two letters | `shshit` ሺት (alt. ሽሺት), `shsh` ሽሽ; `sh_shi` forces ሽሺ. |
| Repeated key (`gg`, `ll`) — owner report: ግግር needed ግ twice but the second press "disappeared" | **Changed (repeated-key fix):** a doubled consonant only collapses when a vowel follows it. `ggr` ግግር, `gg` ግግ, `llb` ልልብ; `gge` still ገ (gemination), with ግገ as an alternative | The old rule collapsed every doubled consonant, so the second press of a key never showed. Typing a letter twice is the common case; gemination is only spelled with a following vowel. No separator is needed for it any more; `_` stays for the rare ግገ-type split. |
| `-:` / `:-` for ፦ | Not added | `:-)` would turn into ፦); the mark is on the symbols page. |

## 5. Open questions for the G0 panel

1. The `x` split (am ሸ, ti ኸ), and whether ti `x` should offer ሸ as an alternative.
2. Capitals as SERA letters by default (fast for SERA typists) vs as alternatives (safe for names).
3. Word-initial ኣ vs አ: confirm the ti 0.3.0 default ኣ (and whether Amharic should offer ኣ at all).
4. Backtick sequences for ሠ ኀ ፀ ዐ: acceptable, or a different marker?
5. A deterministic key for ኧ (both languages), for ኣ in Amharic and for አ in Tigrinya, if wanted (no
   conventional Latin spelling exists that does not collide with `e`, `a`, `W` or the apostrophe).
6. Earlier items still open: `ie` vs i + e, `e` as 1st order, punctuation by doubling, the `_`
   separator (see `language-data/profiles/README.md`).

## 6. Matrix

Each cell: the letter, the sequences that give it as the default composition, then (`alt`) sequences
that offer it in the strip. At most three default and two alternative sequences per cell are shown; the JSON files are complete
for what the test checks. Generated from `core/tests/golden/{am,ti}-phonetic-coverage.json`.

### Amharic (`am-phonetic` 0.2.0)

282 required forms: 280 reachable as the default composition, 2 only as a strip alternative, 0 unreachable.
am 0.4.0 also lists (beyond the required forms) `QH` ቐ `Qe` · ቑ `Qu` · ቒ `Qi` · ቓ `Qa` · ቔ `Qee` · ቕ `Q` · ቖ `Qo` and
`QHW` ቘ `QWe` · ቚ `QWi` · ቛ `QWa` · ቜ `QWee` · ቝ `QW`, all as the default composition.

| Series | 1st (e) | 2nd (u) | 3rd (i) | 4th (a) | 5th (ee) | 6th (bare / I) | 7th (o) |
|---|---|---|---|---|---|---|---|
| `H` | ሀ `he` | ሁ `hu` | ሂ `hi` | ሃ `ha` | ሄ `hee` | ህ `h` · alt `` `h `` | ሆ `ho` |
| `L` | ለ `le` | ሉ `lu` | ሊ `li` | ላ `la` | ሌ `lee` | ል `l` | ሎ `lo` |
| `HH` | ሐ `hhe` · alt `he` | ሑ `hhu` · alt `hu` | ሒ `hhi` · alt `hi` | ሓ `hha` · alt `ha` | ሔ `hhee` | ሕ `hh` · alt `h` `` `h `` | ሖ `hho` · alt `ho` |
| `M` | መ `me` | ሙ `mu` | ሚ `mi` | ማ `ma` | ሜ `mee` | ም `m` | ሞ `mo` |
| `SZ` | ሠ `` `se `` · alt `se` `Se` | ሡ `` `su `` · alt `su` `Su` | ሢ `` `si `` · alt `si` `Si` | ሣ `` `sa `` · alt `sa` `Sa` | ሤ `` `see `` · alt `See` | ሥ `` `s `` · alt `s` `S` | ሦ `` `so `` · alt `so` `So` |
| `R` | ረ `re` | ሩ `ru` | ሪ `ri` | ራ `ra` | ሬ `ree` | ር `r` | ሮ `ro` |
| `S` | ሰ `se` | ሱ `su` | ሲ `si` | ሳ `sa` | ሴ `see` | ስ `s` · alt `` `s `` | ሶ `so` |
| `SH` | ሸ `she` `xe` | ሹ `shu` `xu` | ሺ `shi` `xi` | ሻ `sha` `xa` | ሼ `shee` `xee` | ሽ `sh` `x` | ሾ `sho` `xo` |
| `Q` | ቀ `qe` `k'e` | ቁ `qu` `k'u` | ቂ `qi` `k'i` | ቃ `qa` `k'a` | ቄ `qee` `k'ee` | ቅ `q` `k'` | ቆ `qo` `k'o` |
| `B` | በ `be` | ቡ `bu` | ቢ `bi` | ባ `ba` | ቤ `bee` | ብ `b` | ቦ `bo` |
| `V` | ቨ `ve` | ቩ `vu` | ቪ `vi` | ቫ `va` | ቬ `vee` | ቭ `v` | ቮ `vo` |
| `T` | ተ `te` | ቱ `tu` | ቲ `ti` | ታ `ta` | ቴ `tee` | ት `t` | ቶ `to` |
| `C` | ቸ `che` `ce` | ቹ `chu` `cu` | ቺ `chi` `ci` | ቻ `cha` `ca` | ቼ `chee` `cee` | ች `ch` `c` | ቾ `cho` `co` |
| `X` | ኀ `` `he `` · alt `he` | ኁ `` `hu `` · alt `hu` | ኂ `` `hi `` · alt `hi` | ኃ `` `ha `` · alt `ha` | ኄ `` `hee `` | ኅ `` `h `` · alt `h` | ኆ `` `ho `` · alt `ho` |
| `N` | ነ `ne` | ኑ `nu` | ኒ `ni` | ና `na` | ኔ `nee` | ን `n` | ኖ `no` |
| `NY` | ኘ `nye` · alt `Ne` | ኙ `nyu` · alt `Nu` | ኚ `nyi` · alt `Ni` | ኛ `nya` · alt `Na` | ኜ `nyee` · alt `Nee` | ኝ `ny` · alt `N` | ኞ `nyo` · alt `No` |
| `GLOTTAL` | አ `a` · alt `e` | ኡ `u` | ኢ `i` | ኣ alt `a` | ኤ `ee` | እ `e` `I` | ኦ `o` |
| `K` | ከ `ke` | ኩ `ku` | ኪ `ki` | ካ `ka` | ኬ `kee` | ክ `k` | ኮ `ko` |
| `KX` | ኸ `khe` · alt `Ke` | ኹ `khu` · alt `Ku` | ኺ `khi` · alt `Ki` | ኻ `kha` · alt `Ka` | ኼ `khee` · alt `Kee` | ኽ `kh` · alt `K` | ኾ `kho` · alt `Ko` |
| `W` | ወ `we` | ዉ `wu` | ዊ `wi` | ዋ `wa` | ዌ `wee` | ው `w` | ዎ `wo` |
| `PHARYNGEAL` | ዐ `` `e `` · alt `e` `a` | ዑ `` `u `` · alt `u` | ዒ `` `i `` · alt `i` | ዓ `` `a `` · alt `a` | ዔ `` `ee `` · alt `ee` `ie` | ዕ `` ` `` · alt `e` `I` | ዖ `` `o `` · alt `o` |
| `Z` | ዘ `ze` | ዙ `zu` | ዚ `zi` | ዛ `za` | ዜ `zee` | ዝ `z` | ዞ `zo` |
| `ZH` | ዠ `zhe` · alt `Ze` | ዡ `zhu` · alt `Zu` | ዢ `zhi` · alt `Zi` | ዣ `zha` · alt `Za` | ዤ `zhee` · alt `Zee` | ዥ `zh` · alt `Z` | ዦ `zho` · alt `Zo` |
| `Y` | የ `ye` | ዩ `yu` | ዪ `yi` | ያ `ya` | ዬ `yee` | ይ `y` | ዮ `yo` |
| `D` | ደ `de` | ዱ `du` | ዲ `di` | ዳ `da` | ዴ `dee` | ድ `d` | ዶ `do` |
| `J` | ጀ `je` | ጁ `ju` | ጂ `ji` | ጃ `ja` | ጄ `jee` | ጅ `j` | ጆ `jo` |
| `G` | ገ `ge` | ጉ `gu` | ጊ `gi` | ጋ `ga` | ጌ `gee` | ግ `g` | ጎ `go` |
| `TH` | ጠ `t'e` · alt `Te` | ጡ `t'u` · alt `Tu` | ጢ `t'i` · alt `Ti` | ጣ `t'a` · alt `Ta` | ጤ `t'ee` · alt `Tee` | ጥ `t'` · alt `T` | ጦ `t'o` · alt `To` |
| `CH` | ጨ `ch'e` `c'e` · alt `Ce` | ጩ `ch'u` `c'u` · alt `Cu` | ጪ `ch'i` `c'i` · alt `Ci` | ጫ `ch'a` `c'a` · alt `Ca` | ጬ `ch'ee` `c'ee` · alt `Cee` | ጭ `ch'` `c'` · alt `C` | ጮ `ch'o` `c'o` · alt `Co` |
| `PH` | ጰ `p'e` · alt `Pe` | ጱ `p'u` · alt `Pu` | ጲ `p'i` · alt `Pi` | ጳ `p'a` · alt `Pa` | ጴ `p'ee` · alt `Pee` | ጵ `p'` · alt `P` | ጶ `p'o` · alt `Po` |
| `TS` | ጸ `tse` `ts'e` `s'e` · alt `Se` | ጹ `tsu` `ts'u` `s'u` · alt `Su` | ጺ `tsi` `ts'i` `s'i` · alt `Si` | ጻ `tsa` `ts'a` `s'a` · alt `Sa` | ጼ `tsee` `ts'ee` `s'ee` · alt `See` | ጽ `ts` `ts'` `s'` · alt `S` | ጾ `tso` `ts'o` `s'o` · alt `So` |
| `TZ` | ፀ `` `tse `` | ፁ `` `tsu `` | ፂ `` `tsi `` | ፃ `` `tsa `` | ፄ `` `tsee `` | ፅ `` `ts `` · alt `ts` `s'` | ፆ `` `tso `` |
| `F` | ፈ `fe` | ፉ `fu` | ፊ `fi` | ፋ `fa` | ፌ `fee` | ፍ `f` | ፎ `fo` |
| `P` | ፐ `pe` | ፑ `pu` | ፒ `pi` | ፓ `pa` | ፔ `pee` | ፕ `p` | ፖ `po` |

Labialised "wa" forms (8th order): ሏ `lWa` · alt `lwa`; ሗ `hhWa` · alt `hwa` `hhwa`; ሟ `mWa` · alt `mwa`; ሧ `` `sWa `` · alt `swa` `` `swa ``; ሯ `rWa` · alt `rwa`; ሷ `sWa` · alt `swa` `` `swa ``; ሿ `shWa` `xWa` · alt `shwa` `xwa`; ቧ `bWa` · alt `bwa`; ቯ `vWa` · alt `vwa`; ቷ `tWa` · alt `twa` `Twa`; ቿ `chWa` `cWa` · alt `chwa` `cwa`; ኗ `nWa` · alt `nwa` `Nwa`; ኟ `nyWa` · alt `nywa` `NWa`; ኧ alt `e`; ዟ `zWa` · alt `zwa` `Zwa`; ዧ `zhWa` · alt `zhwa` `ZWa`; ዷ `dWa` · alt `dwa`; ጇ `jWa` · alt `jwa`; ጧ `t'Wa` · alt `t'wa` `TWa`; ጯ `ch'Wa` `c'Wa` · alt `ch'wa` `c'wa`; ጷ `p'Wa` · alt `p'wa` `PWa`; ጿ `tsWa` `ts'Wa` `s'Wa` · alt `tswa` `ts'wa`; ፏ `fWa` · alt `fwa`; ፗ `pWa` · alt `pwa` `Pwa`.

| Labiovelar | 1st (We) | 3rd (Wi) | 4th (Wa) | 5th (Wee) | 6th (W) |
|---|---|---|---|---|---|
| `QW` | ቈ `qWe` `k'We` · alt `qwe` `k'we` | ቊ `qWi` `k'Wi` · alt `qwi` `k'wi` | ቋ `qWa` `k'Wa` · alt `qwa` `k'wa` | ቌ `qWee` `k'Wee` · alt `qwee` `qwie` | ቍ `qW` `k'W` · alt `qw` `k'w` |
| `XW` | ኈ `hWe` `` `hWe `` · alt `hwe` `` `hwe `` | ኊ `hWi` `` `hWi `` · alt `hwi` `` `hwi `` | ኋ `hWa` `` `hWa `` · alt `hwa` `` `hwa `` | ኌ `hWee` `` `hWee `` · alt `hwee` `hwie` | ኍ `hW` `` `hW `` · alt `hw` `` `hw `` |
| `KW` | ኰ `kWe` · alt `kwe` `Kwe` | ኲ `kWi` · alt `kwi` `Kwi` | ኳ `kWa` · alt `kwa` `Kwa` | ኴ `kWee` · alt `kwee` `kwie` | ኵ `kW` · alt `kw` `Kw` |
| `GW` | ጐ `gWe` · alt `gwe` | ጒ `gWi` · alt `gwi` | ጓ `gWa` · alt `gwa` | ጔ `gWee` · alt `gwee` `gwie` | ጕ `gW` · alt `gw` |

### Tigrinya (`ti-phonetic` 0.3.0)

299 required forms: 297 reachable as the default composition, 2 only as a strip alternative (አ ኧ), 0 unreachable.

| Series | 1st (e) | 2nd (u) | 3rd (i) | 4th (a) | 5th (ee) | 6th (bare / I) | 7th (o) |
|---|---|---|---|---|---|---|---|
| `H` | ሀ `he` | ሁ `hu` | ሂ `hi` | ሃ `ha` | ሄ `hee` | ህ `h` · alt `` `h `` | ሆ `ho` |
| `L` | ለ `le` | ሉ `lu` | ሊ `li` | ላ `la` | ሌ `lee` | ል `l` | ሎ `lo` |
| `HH` | ሐ `hhe` · alt `he` | ሑ `hhu` · alt `hu` | ሒ `hhi` · alt `hi` | ሓ `hha` · alt `ha` | ሔ `hhee` | ሕ `hh` · alt `h` `` `h `` | ሖ `hho` · alt `ho` |
| `M` | መ `me` | ሙ `mu` | ሚ `mi` | ማ `ma` | ሜ `mee` | ም `m` | ሞ `mo` |
| `SZ` | ሠ `` `se `` · alt `se` `Se` | ሡ `` `su `` · alt `su` `Su` | ሢ `` `si `` · alt `si` `Si` | ሣ `` `sa `` · alt `sa` `Sa` | ሤ `` `see `` · alt `See` | ሥ `` `s `` · alt `s` `S` | ሦ `` `so `` · alt `so` `So` |
| `R` | ረ `re` | ሩ `ru` | ሪ `ri` | ራ `ra` | ሬ `ree` | ር `r` | ሮ `ro` |
| `S` | ሰ `se` | ሱ `su` | ሲ `si` | ሳ `sa` | ሴ `see` | ስ `s` · alt `` `s `` | ሶ `so` |
| `SH` | ሸ `she` | ሹ `shu` | ሺ `shi` | ሻ `sha` | ሼ `shee` | ሽ `sh` | ሾ `sho` |
| `Q` | ቀ `qe` `k'e` | ቁ `qu` `k'u` | ቂ `qi` `k'i` | ቃ `qa` `k'a` | ቄ `qee` `k'ee` | ቅ `q` `k'` · alt `qh` | ቆ `qo` `k'o` |
| `B` | በ `be` | ቡ `bu` | ቢ `bi` | ባ `ba` | ቤ `bee` | ብ `b` | ቦ `bo` |
| `V` | ቨ `ve` | ቩ `vu` | ቪ `vi` | ቫ `va` | ቬ `vee` | ቭ `v` | ቮ `vo` |
| `T` | ተ `te` | ቱ `tu` | ቲ `ti` | ታ `ta` | ቴ `tee` | ት `t` | ቶ `to` |
| `C` | ቸ `che` `ce` | ቹ `chu` `cu` | ቺ `chi` `ci` | ቻ `cha` `ca` | ቼ `chee` `cee` | ች `ch` `c` | ቾ `cho` `co` |
| `X` | ኀ `` `he `` · alt `he` | ኁ `` `hu `` · alt `hu` | ኂ `` `hi `` · alt `hi` | ኃ `` `ha `` · alt `ha` | ኄ `` `hee `` | ኅ `` `h `` · alt `h` | ኆ `` `ho `` · alt `ho` |
| `N` | ነ `ne` | ኑ `nu` | ኒ `ni` | ና `na` | ኔ `nee` | ን `n` | ኖ `no` |
| `NY` | ኘ `nye` · alt `Ne` | ኙ `nyu` · alt `Nu` | ኚ `nyi` · alt `Ni` | ኛ `nya` · alt `Na` | ኜ `nyee` · alt `Nee` | ኝ `ny` · alt `N` | ኞ `nyo` · alt `No` |
| `GLOTTAL` | አ alt `a` `e` | ኡ `u` | ኢ `i` | ኣ `a` | ኤ `ee` | እ `e` `I` | ኦ `o` |
| `K` | ከ `ke` | ኩ `ku` | ኪ `ki` | ካ `ka` | ኬ `kee` | ክ `k` | ኮ `ko` |
| `KX` | ኸ `khe` `xe` · alt `Ke` | ኹ `khu` `xu` · alt `Ku` | ኺ `khi` `xi` · alt `Ki` | ኻ `kha` `xa` · alt `Ka` | ኼ `khee` `xee` · alt `Kee` | ኽ `kh` `x` · alt `K` | ኾ `kho` `xo` · alt `Ko` |
| `W` | ወ `we` | ዉ `wu` | ዊ `wi` | ዋ `wa` | ዌ `wee` | ው `w` | ዎ `wo` |
| `PHARYNGEAL` | ዐ `` `e `` · alt `e` `a` | ዑ `` `u `` · alt `u` | ዒ `` `i `` · alt `i` | ዓ `` `a `` · alt `a` | ዔ `` `ee `` · alt `ee` `ie` | ዕ `` ` `` · alt `e` `I` | ዖ `` `o `` · alt `o` |
| `Z` | ዘ `ze` | ዙ `zu` | ዚ `zi` | ዛ `za` | ዜ `zee` | ዝ `z` | ዞ `zo` |
| `ZH` | ዠ `zhe` · alt `Ze` | ዡ `zhu` · alt `Zu` | ዢ `zhi` · alt `Zi` | ዣ `zha` · alt `Za` | ዤ `zhee` · alt `Zee` | ዥ `zh` · alt `Z` | ዦ `zho` · alt `Zo` |
| `Y` | የ `ye` | ዩ `yu` | ዪ `yi` | ያ `ya` | ዬ `yee` | ይ `y` | ዮ `yo` |
| `D` | ደ `de` | ዱ `du` | ዲ `di` | ዳ `da` | ዴ `dee` | ድ `d` | ዶ `do` |
| `J` | ጀ `je` | ጁ `ju` | ጂ `ji` | ጃ `ja` | ጄ `jee` | ጅ `j` | ጆ `jo` |
| `G` | ገ `ge` | ጉ `gu` | ጊ `gi` | ጋ `ga` | ጌ `gee` | ግ `g` | ጎ `go` |
| `TH` | ጠ `t'e` · alt `Te` | ጡ `t'u` · alt `Tu` | ጢ `t'i` · alt `Ti` | ጣ `t'a` · alt `Ta` | ጤ `t'ee` · alt `Tee` | ጥ `t'` · alt `T` | ጦ `t'o` · alt `To` |
| `CH` | ጨ `ch'e` `c'e` · alt `Ce` | ጩ `ch'u` `c'u` · alt `Cu` | ጪ `ch'i` `c'i` · alt `Ci` | ጫ `ch'a` `c'a` · alt `Ca` | ጬ `ch'ee` `c'ee` · alt `Cee` | ጭ `ch'` `c'` · alt `C` | ጮ `ch'o` `c'o` · alt `Co` |
| `PH` | ጰ `p'e` · alt `Pe` | ጱ `p'u` · alt `Pu` | ጲ `p'i` · alt `Pi` | ጳ `p'a` · alt `Pa` | ጴ `p'ee` · alt `Pee` | ጵ `p'` · alt `P` | ጶ `p'o` · alt `Po` |
| `TS` | ጸ `tse` `ts'e` `s'e` · alt `Se` | ጹ `tsu` `ts'u` `s'u` · alt `Su` | ጺ `tsi` `ts'i` `s'i` · alt `Si` | ጻ `tsa` `ts'a` `s'a` · alt `Sa` | ጼ `tsee` `ts'ee` `s'ee` · alt `See` | ጽ `ts` `ts'` `s'` · alt `S` | ጾ `tso` `ts'o` `s'o` · alt `So` |
| `TZ` | ፀ `` `tse `` | ፁ `` `tsu `` | ፂ `` `tsi `` | ፃ `` `tsa `` | ፄ `` `tsee `` | ፅ `` `ts `` · alt `ts` `s'` | ፆ `` `tso `` |
| `F` | ፈ `fe` | ፉ `fu` | ፊ `fi` | ፋ `fa` | ፌ `fee` | ፍ `f` | ፎ `fo` |
| `P` | ፐ `pe` | ፑ `pu` | ፒ `pi` | ፓ `pa` | ፔ `pee` | ፕ `p` | ፖ `po` |
| `QH` | ቐ `qhe` · alt `qe` | ቑ `qhu` · alt `qu` | ቒ `qhi` · alt `qi` | ቓ `qha` · alt `qa` | ቔ `qhee` | ቕ `qh` · alt `q` `k'` | ቖ `qho` · alt `qo` |

Labialised "wa" forms (8th order): ሏ `lWa` · alt `lwa`; ሗ `hhWa` · alt `hwa` `hhwa`; ሟ `mWa` · alt `mwa`; ሧ `` `sWa `` · alt `swa` `` `swa ``; ሯ `rWa` · alt `rwa`; ሷ `sWa` · alt `swa` `` `swa ``; ሿ `shWa` · alt `shwa`; ቧ `bWa` · alt `bwa`; ቯ `vWa` · alt `vwa`; ቷ `tWa` · alt `twa` `Twa`; ቿ `chWa` `cWa` · alt `chwa` `cwa`; ኗ `nWa` · alt `nwa` `Nwa`; ኟ `nyWa` · alt `nywa` `NWa`; ኧ alt `e`; ዟ `zWa` · alt `zwa` `Zwa`; ዧ `zhWa` · alt `zhwa` `ZWa`; ዷ `dWa` · alt `dwa`; ጇ `jWa` · alt `jwa`; ጧ `t'Wa` · alt `t'wa` `TWa`; ጯ `ch'Wa` `c'Wa` · alt `ch'wa` `c'wa`; ጷ `p'Wa` · alt `p'wa` `PWa`; ጿ `tsWa` `ts'Wa` `s'Wa` · alt `tswa` `ts'wa`; ፏ `fWa` · alt `fwa`; ፗ `pWa` · alt `pwa` `Pwa`.

| Labiovelar | 1st (We) | 3rd (Wi) | 4th (Wa) | 5th (Wee) | 6th (W) |
|---|---|---|---|---|---|
| `QW` | ቈ `qWe` `k'We` · alt `qwe` `qhwe` | ቊ `qWi` `k'Wi` · alt `qwi` `qhwi` | ቋ `qWa` `k'Wa` · alt `qwa` `qhwa` | ቌ `qWee` `k'Wee` · alt `qwee` `qwie` | ቍ `qW` `k'W` · alt `qw` `qhw` |
| `XW` | ኈ `hWe` `` `hWe `` · alt `hwe` `` `hwe `` | ኊ `hWi` `` `hWi `` · alt `hwi` `` `hwi `` | ኋ `hWa` `` `hWa `` · alt `hwa` `` `hwa `` | ኌ `hWee` `` `hWee `` · alt `hwee` `hwie` | ኍ `hW` `` `hW `` · alt `hw` `` `hw `` |
| `KW` | ኰ `kWe` · alt `kwe` `Kwe` | ኲ `kWi` · alt `kwi` `Kwi` | ኳ `kWa` · alt `kwa` `Kwa` | ኴ `kWee` · alt `kwee` `kwie` | ኵ `kW` · alt `kw` `Kw` |
| `GW` | ጐ `gWe` · alt `gwe` | ጒ `gWi` · alt `gwi` | ጓ `gWa` · alt `gwa` | ጔ `gWee` · alt `gwee` `gwie` | ጕ `gW` · alt `gw` |
| `KXW` | ዀ `khWe` `xWe` · alt `khwe` `xwe` | ዂ `khWi` `xWi` · alt `khwi` `xwi` | ዃ `khWa` `xWa` · alt `khwa` `xwa` | ዄ `khWee` `xWee` · alt `khwee` `khwie` | ዅ `khW` `xW` · alt `khw` `xw` |
| `QHW` | ቘ `qhWe` · alt `qwe` `qhwe` | ቚ `qhWi` · alt `qwi` `qhwi` | ቛ `qhWa` · alt `qwa` `qhwa` | ቜ `qhWee` · alt `qwee` `qwie` | ቝ `qhW` · alt `qW` `qw` |
