# Language audit: Afaan Oromoo (om) and Tigrinya (ti)

Status: **DRAFT** record of the audit of 2026-11-19 (branch `lang-om-ti`). Product owner's requirement: "Our keyboard
must represent Ethiopian languages properly." Afaan Oromoo must have its own language configuration and typing
experience, never an alternative label for Amharic; Tigrinya must be clearly identifiable and fully typeable in Ge'ez.
Every language datum below stays DRAFT for the native-speaker panel (gate G0, rule N9).

Legend: **OK** = was already correct (with the evidence); **Fixed** = defect found and fixed (commit); **Open** = left
for a human decision or a device check, with the reason.

## Afaan Oromoo

| # | Item | Status | Evidence / change |
|---|---|---|---|
| om-1 | Name in every UI language | **Fixed** f700542 | en/om said "Afaan Oromoo" and am prose said አፋን ኦሮሞ, but the keyboard's language lists (space-bar picker speech, AI source/destination lists, voice messages) used the exonym **ኦሮምኛ** in the am and ti UIs, and the ti app strings (welcome, language list, Word Art) did too. Now the self-name everywhere: am አፋን ኦሮሞ, ti ኣፋን ኦሮሞ, en/om Afaan Oromoo. `LanguageNamesTest` fails on ኦሮምኛ / Oromiffa / Oromigna and checks every om name starts with the self-name. Glossary updated. |
| om-2 | Own typing configuration in core | **OK** | `InputMode::Qubee` only fits `om` (and `om` fits no Fidel/Phonetic mode): `mode_fits_language`, `tests/latin_modes.rs::mode_validity_per_language`. No Amharic profile, grid or candidates can be selected for om (`qubee_never_suggests_from_another_language`). |
| om-3 | Own layout | **OK** | `Layouts.letters` QUBEE: full Latin QWERTY (all 26 letters incl. loan letters p v z), hudhaa `'` on the primary layer (shift row), digits row, Latin `.` `,` with no Ethiopic marks or numerals (`LayoutContext.ethiopic` false; new assertion in `CoreLayoutsTest.oromoQubeeTypesWithHudhaaOnTheMainPage`). |
| om-4 | Own settings | **OK** + **Fixed** 31a3a72 | `LanguageRules.allowedModes("om")` = one layout, shown as "Qubee" (not "Latin"). New: a Suggestions section with the per-language switch (see om-8), strings in en/am/ti/om (DRAFT). |
| om-5 | Digraphs, doubled letters, long vowels | **OK** | Typing is letter by letter, so `ch dh ny ph sh`, geminates (`dd`, `dhh`) and long vowels (`aa`) type naturally; segmentation units in `language-data/orthography/om-qubee-draft.json`; `latin::Orthography::units`/`capitalize` (`Dhugaa`, not `DHugaa`). |
| om-6 | Hudhaa part of words | **OK** + **Fixed** 7c79b33, d774325 | Primary-layer key; word segmentation and learning keep `ba'a` whole (`latin.rs`, `WordLearner.isLearnable`); Android auto-capitalisation comes from the editor's caps mode, which does not capitalise after a word-internal `'`; no smart-quote substitution exists anywhere (searched). **Defects:** (a) the engine's ranking context split `har'a` into `har` + `a` (the hudhaa reset the previous word), fixed in `WordContext::observe` (unit test `word_context_keeps_the_hudhaa_inside_a_word`); (b) the om emoji keywords (CLDR) spell the hudhaa `’` while the key types `'`, so `beela'uu`, `boo'uu`, `mi'aawaa` found nothing; terms and queries are now folded (`EmojiSearch.fold`, test `oromoHudhaaTypedWithTheApostropheKeyMatchesTheCldrSpelling`). |
| om-7 | Sentence auto-capitalisation | **OK** | `KeyboardController.refreshAutoCapital`: Latin passthrough layouts (Qubee included) follow the field's caps mode; phonetic modes never (capitals have SERA meanings). Still correct with the new composition (the composing word is in the editor). `KeyboardControllerTest.autoCapitalAppliesToQubeeAndEnglishButNotPhonetic`. |
| om-8 | Word suggestions from the om pack | **Fixed** 7c79b33, 31a3a72 | The om seed DICTIONARY (90 DRAFT words incl. `har'a`, `baay'ee`, `waa'ee`, `ji'a`) and FREQUENCY_LIST packs shipped and the personal dictionary learned om words, but **Qubee never showed a strip**: the core offered candidates in Phonetic mode only, and the controller showed the strip for phonetic modes only. Learned Oromo words were never suggested back. Now, with the om dictionary loaded, Qubee composes the current word (letters, and the hudhaa after a letter) and the strip shows it as typed (index 0) plus completions/corrections from the om lexicon and the user's words, ranked with the previous word (bigrams). Nothing is applied automatically: space and punctuation commit exactly what was typed. Capitals are kept (`Akk` offers `Akkam`); apostrophe variants are folded for the lookup only; a word ending in `'` is not looked up. The strip carries the DRAFT tag unless the om dictionary pack is STABLE. Without a dictionary Qubee stays plain passthrough; English and ABC are unchanged. Tests: `core/tests/qubee_suggestions.rs` (10), `CoreTypingTest.afaanOromoo*`, `SettingsPreferencesTest.afaanOromooSuggestionsFollowTheirOwnSwitch`. |
| om-9 | Space-bar indicator and chips | **OK** | `OM · Qubee`; ABC toggle shows `OM · ABC→Qu` and the key `Qu` (`CoreLayoutsTest`, `LanguageCatalog.indicator`). |
| om-10 | Voice typing locale | **OK** / **Open (device)** | `om-ET`, asked of the recogniser at run time (API 33+ `checkRecognitionSupport`, older: learnt from `ERROR_LANGUAGE_NOT_SUPPORTED`); unsupported is said plainly with a pointer to supported languages, never faked (ADR 0029). Whether any Google/Samsung service accepts `om-ET` needs a device check. |
| om-11 | Translation lists and default | **OK** | `AiLanguages.ALL` has om as source and destination; default destination for om text is English; backend V19 routes en↔om on Google and OpenAI. Pairs om↔am/ti are reported as unsupported by the server, not faked. |
| om-12 | Emoji keyword search in om | **OK** + **Fixed** | `emoji-om.tsv` (CLDR) is searched while typing om; hudhaa fix in om-6(b). |
| om-13 | Onboarding and om UI strings for the language list | **OK** | Onboarding offers om with its own label (`lang_om`), never a mode chooser (Qubee only); `values-om` names every language in Oromo (Afaan Amaaraa, Afaan Tigiraay, Afaan Oromoo, Afaan Ingiliffaa). |

## Tigrinya

| # | Item | Status | Evidence / change |
|---|---|---|---|
| ti-1 | Clearly identifiable, never confused with Amharic | **OK** + test | ትግርኛ in every Ge'ez UI, "Tigrinya" / "Afaan Tigiraay" elsewhere; indicator `ትግ` vs `አማ`; picker shows ትግርኛ. `LanguageNamesTest.tigrinyaIsNeverNamedLikeAmharic` pins that no UI language gives ti and am the same name. |
| ti-2 | Own Fidel grid | **OK** + test 5ec2f20 | `ti-fidel-draft.json` has its own ቐ key (labialised ቘ…ቝ) next to ቀ; the am grid has none. New `core/tests/fidel_coverage.rs`: every required am and ti form (the coverage matrices) is reachable on the language's own grid by two keys or long-press; ዓ, ኧ and ኋ by two keys; ቐ…ቖ, ቘ ቚ ቛ ቜ ቝ, ኸ…ኾ, ዀ ዂ ዃ ዄ ዅ, ጸ/ፀ, ዐ/አ/ኣ, ኈ…ኍ on the ti grid. |
| ti-3 | Own phonetic profile | **OK** + **Fixed** 5ec2f20 | `ti-phonetic` differs from am where Tigrinya differs: `x` ኸ (am ሸ), `qh` ቐ and ቐ offered for `q`/`k'`, ቐ labiovelars. **Defect:** a vowel-initial `a` gave Amharic's **አ** (`ab` → አብ) with ኣ only in the strip, while Tigrinya writes initial /a/ **ኣ** (ኣብ, ኣነ, ኣደ, ኣስመራ — 11 of the ti seed words; the ti UI glossary's own rule). Since the strip's index 0 is the literal rule output, every such word needed a tap. Profile **0.3.0**: `a` → ኣ, alternatives አ ዓ ዐ; Amharic unchanged. Golden `ti-phonetic-0.3.0.json` (+3 vectors), coverage matrix updated, Android `CoreTypingTest.tigrinyaWritesWordInitialAWithItsOwnLetter`. |
| ti-4 | Ethiopic punctuation and numerals | **OK** | Grid and symbols page: ። ፣ ፤ ፥ ፦ ፧ ፨ ፡ (`Layouts.GEEZ_MARKS` + pack punctuation); doubled `..` `,,` `;;` `::` `??` in Phonetic; Ethiopic numerals on digit long-press (setting); asserted for both grids in `fidel_coverage.rs`. |
| ti-5 | Candidates use the ti pack | **OK** | Lexicon models are per language (`KeyboardEngine.models["ti"]`); the ti strip ranks ti seed words (e.g. ኣብ, ዓመት); `composer_candidates.rs`, `seed_packs.rs`. |
| ti-6 | Translation lists | **OK** | ti in `AiLanguages.ALL`; default destination English; backend V19 en↔ti. |
| ti-7 | Voice locale | **OK** / **Open (device)** | `ti-ET`, then `ti-ER`; availability asked at run time as for om. |
| ti-8 | Settings and onboarding labels | **OK** | Own entry with Phonetic / Fidel / Latin layouts; name ትግርኛ with the UI-language name below (`LanguagePages`). |

## Phonetic coverage audit (was 🟨 PARTIAL)

The PARTIAL status of 2026-11-18 had one open item: the bundled packs still carried profile 0.1.0. That was closed
on 2026-11-19 (packs re-signed with the checkout dev key, plan notes log). This audit re-checked reachability:

- **Phonetic:** 0 unreachable forms in am (282 required) and ti (299). Alternative-only: am ኣ ኧ; ti (0.3.0) አ ኧ
  (ኣ became the ti default). See `docs/specs/phonetic-coverage-am-ti.md`.
- **Fidel:** 0 unreachable forms in either grid (new test).
- **Left open (G0):** a deterministic Latin key for ኧ (both languages), Amharic ኣ and Tigrinya አ. Every candidate
  spelling collides with an existing convention (`e`/`a` vowels, `W` labial marker, apostrophe ejectives, backtick
  pharyngeals), so this needs the panel, not a guess. Also for the panel: confirm the ti `a` → ኣ default.

## Pack build

**Fixed** 9af149b: `build_seed_packs.py` packed layout and profile JSON with the checkout's line ends, so a
Windows checkout (`core.autocrlf`) produced different pack bytes for unchanged layouts; and `--check` did not notice a
shipped layout/profile pack that no longer matched its source (how profile 0.2.0 sat unshipped). JSON artifacts are now
packed with LF line ends, and `--check` compares each shipped rule pack's artifact with its source. Rebuilt with the
checkout dev key (`qal-dev-36652306`): `ti-phonetic-rules.pack` (profile 0.3.0) and `am-phonetic-rules.pack` (same
profile, now LF-packed); layouts byte-identical. Tests: `test_seed_packs.RulePacks` (3).

## Needs a device or a native speaker

- Device: Qubee strip while typing (composition underline in WhatsApp/Chrome/Gmail, apps that reject composing
  text fall back per ADR 0004), om/ti voice support on Pixel and Samsung, emoji search with the hudhaa.
- Native speakers (G0): ti `a` → ኣ default; the om seed lexicon (90 words) and bigrams before suggestions are called
  more than DRAFT; the om/ti spellings አፋን ኦሮሞ / ኣፋን ኦሮሞ and the new settings strings.
