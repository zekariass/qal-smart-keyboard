//! Prediction engine (ADR 0039): word suggestions in every mode with data, autocorrection with
//! its harm guard and one-tap undo, next-word predictions, spacing around picked suggestions,
//! the cursor context. Synthetic lexicons with corpus-like counts and the DRAFT seed packs;
//! TEST keys only; synthetic text (N2).

mod common;

use common::{active, engine, fields, pack};
use qal_keyboard_core::lexicon::Lexicon;
use qal_keyboard_core::ranker::bigram_payload_from_text;
use qal_keyboard_core::{
    CandidateKind, EditorUpdate, InputMode, KeyEvent, KeyboardEngine, PackPlatform,
    PredictionOptions, ResourceType,
};
use std::sync::Arc;

const AM_LEXICON: &[u8] = include_bytes!("../../language-data/seed/artifacts/am.lexicon.qlx");
const AM_BIGRAMS: &[u8] = include_bytes!("../../language-data/seed/artifacts/am.bigrams.qbg");
const AM_PROFILE: &[u8] = include_bytes!("../../language-data/profiles/am-phonetic-draft.json");
const AM_LAYOUT: &[u8] = include_bytes!("../../language-data/layouts/am-fidel-draft.json");

/// English words with corpus-like counts, so the harm guard can be confident.
const EN_WORDS: &str = "the\t500000
they\t60000
then\t50000
there\t40000
hello\t30000
help\t25000
world\t20000
good\t18000
morning\t9000
thanks\t8000
keyboard\t500
";
const EN_PAIRS: &str = "good\tmorning\t900\nhello\tworld\t500\nthe\tworld\t50\n";

fn pack_of(lang: &str, rt: ResourceType, artifact: &[u8]) -> Vec<u8> {
    let mut f = fields("0.1.0");
    f.language_code = lang.to_string();
    f.resource_type = rt;
    f.platform = PackPlatform::Shared;
    pack(&active(), &f, artifact)
}

const ALL_ON: PredictionOptions = PredictionOptions {
    suggestions: true,
    next_word: true,
    autocorrect: true,
};

fn english(options: PredictionOptions) -> Arc<KeyboardEngine> {
    let e = engine();
    let lex = Lexicon::from_wordlist_text(EN_WORDS.into());
    e.load_pack(pack_of("en", ResourceType::Dictionary, &lex.to_bytes()))
        .unwrap();
    e.load_pack(pack_of(
        "en",
        ResourceType::FrequencyList,
        &bigram_payload_from_text(EN_PAIRS.into()),
    ))
    .unwrap();
    e.set_input_mode("en".into(), InputMode::English).unwrap();
    e.set_prediction_options(options);
    e
}

fn key(c: char) -> KeyEvent {
    match c {
        ' ' => KeyEvent::Space,
        '\n' => KeyEvent::Enter,
        '\u{8}' => KeyEvent::Backspace,
        _ => KeyEvent::Char {
            text: c.to_string(),
        },
    }
}

/// A minimal editor: deletes clusters (one char each here), commits, shows the composing text.
#[derive(Default)]
struct Editor {
    text: Vec<char>,
    composing: String,
}

impl Editor {
    fn apply(&mut self, u: &EditorUpdate) {
        if u.delete_before > 0 {
            self.composing.clear();
        }
        for _ in 0..u.delete_before {
            self.text.pop();
        }
        self.text.extend(u.commit.chars());
        self.composing.clone_from(&u.composing);
    }

    fn shown(&self) -> String {
        let t: String = self.text.iter().collect();
        format!("{t}{}", self.composing)
    }
}

fn type_into(e: &KeyboardEngine, ed: &mut Editor, s: &str) -> EditorUpdate {
    let mut last = EditorUpdate::default();
    for c in s.chars() {
        last = e.on_key(key(c));
        ed.apply(&last);
    }
    last
}

fn pick(e: &KeyboardEngine, ed: &mut Editor, u: &EditorUpdate, text: &str) -> EditorUpdate {
    let i = u
        .candidates
        .iter()
        .position(|c| c.text == text)
        .unwrap_or_else(|| panic!("{text} not offered: {:?}", texts(u)));
    let p = e.select_candidate(u32::try_from(i).unwrap());
    ed.apply(&p);
    p
}

fn texts(u: &EditorUpdate) -> Vec<String> {
    u.candidates.iter().map(|c| c.text.clone()).collect()
}

#[test]
fn english_words_compose_with_completions() {
    let e = english(PredictionOptions::default());
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "hel");
    assert_eq!(u.composing, "hel");
    assert_eq!(u.candidates[0].text, "hel");
    assert_eq!(u.candidates[0].kind, CandidateKind::Transliteration);
    assert!(texts(&u).contains(&"hello".to_string()));
    assert!(texts(&u).contains(&"help".to_string()));
    let p = pick(&e, &mut ed, &u, "hello");
    assert_eq!(p.commit, "hello ");
    // Defaults: no next-word predictions and no autocorrection unless the host asks.
    assert!(p.candidates.is_empty());
    type_into(&e, &mut ed, "teh ");
    assert_eq!(ed.shown(), "hello teh ");
}

#[test]
fn autocorrection_applies_on_space_and_backspace_undoes_it() {
    let e = english(ALL_ON);
    let mut ed = Editor::default();
    type_into(&e, &mut ed, "hello ");
    let u = type_into(&e, &mut ed, "teh");
    // The strip shows the correction Space will apply.
    assert_eq!(u.autocorrect_index, Some(1));
    assert_eq!(u.candidates[1].text, "the");
    assert_eq!(u.candidates[1].kind, CandidateKind::Correction);
    let s = type_into(&e, &mut ed, " ");
    assert_eq!(s.commit, "the ");
    assert_eq!(ed.shown(), "hello the ");
    // One-tap undo: the typed word composes again and is not corrected a second time.
    let b = e.on_key(KeyEvent::Backspace);
    ed.apply(&b);
    assert_eq!(b.delete_before, 4);
    assert_eq!(b.composing, "teh");
    assert_eq!(b.autocorrect_index, None);
    assert_eq!(ed.shown(), "hello teh");
    type_into(&e, &mut ed, " ");
    assert_eq!(ed.shown(), "hello teh ");
    // A later word is corrected again; a Backspace after another key is a plain Backspace.
    type_into(&e, &mut ed, "teh ");
    assert_eq!(ed.shown(), "hello teh the ");
    type_into(&e, &mut ed, "x");
    let b = type_into(&e, &mut ed, "\u{8}");
    assert_eq!((b.delete_before, b.composing.as_str()), (0, ""));
}

#[test]
fn sentence_punctuation_also_corrects() {
    let e = english(ALL_ON);
    let mut ed = Editor::default();
    type_into(&e, &mut ed, "hello teh.");
    assert_eq!(ed.shown(), "hello the.");
    // Undo removes the punctuation with the correction (one Backspace).
    type_into(&e, &mut ed, "\u{8}");
    assert_eq!(ed.shown(), "hello teh");
}

#[test]
fn the_harm_guard_keeps_names_addresses_and_user_words() {
    let e = english(ALL_ON);
    let mut ed = Editor::default();
    // A capitalised word mid-sentence may be a name.
    type_into(&e, &mut ed, "hello Teh ");
    assert_eq!(ed.shown(), "hello Teh ");
    // At a sentence start a capitalised typo is corrected, keeping the capital.
    type_into(&e, &mut ed, "ok. Teh ");
    assert_eq!(ed.shown(), "hello Teh ok. The ");
    // Words glued to @, digits, / or . are parts of addresses and numbers.
    let mut ed = Editor::default();
    e.reset();
    type_into(&e, &mut ed, "mail a@teh 2teh x/teh www.teh ");
    assert_eq!(ed.shown(), "mail a@teh 2teh x/teh www.teh ");
    // Short words and words with digits are not eligible.
    type_into(&e, &mut ed, "th ");
    assert_eq!(ed.shown(), "mail a@teh 2teh x/teh www.teh th ");
    // A word in the user dictionary is never corrected.
    assert!(e.add_user_word("en".into(), "teh".into()));
    type_into(&e, &mut ed, "teh ");
    assert!(ed.shown().ends_with("th teh "));
}

#[test]
fn autocorrection_off_never_changes_text() {
    let e = english(PredictionOptions {
        autocorrect: false,
        ..ALL_ON
    });
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "hello teh");
    assert_eq!(u.autocorrect_index, None);
    assert!(texts(&u).contains(&"the".to_string())); // still offered
    type_into(&e, &mut ed, " ");
    assert_eq!(ed.shown(), "hello teh ");
}

#[test]
fn a_word_valid_in_the_other_latin_language_is_not_corrected() {
    let e = english(ALL_ON);
    let om = Lexicon::from_wordlist_text("teh\t5\n".into());
    e.load_pack(pack_of("om", ResourceType::Dictionary, &om.to_bytes()))
        .unwrap();
    let mut ed = Editor::default();
    type_into(&e, &mut ed, "hello teh ");
    assert_eq!(ed.shown(), "hello teh ");
}

#[test]
fn next_word_predictions_follow_a_space_and_chain() {
    let e = english(ALL_ON);
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "good ");
    assert_eq!(u.candidates[0].text, "morning");
    assert!(u
        .candidates
        .iter()
        .all(|c| c.kind == CandidateKind::Prediction && c.text != "good"));
    let p = pick(&e, &mut ed, &u, "morning");
    assert_eq!(p.commit, "morning ");
    assert_eq!(ed.shown(), "good morning ");
    // Predictions continue after the picked word.
    assert!(!p.candidates.is_empty());
    // After a sentence end there is no previous word: the most frequent words start the sentence.
    let u = type_into(&e, &mut ed, "thanks. ");
    assert_eq!(u.candidates[0].text, "the");
    assert!(u
        .candidates
        .iter()
        .all(|c| c.kind == CandidateKind::Prediction));
    // After other punctuation (no previous word, not a sentence start) there are none.
    assert!(type_into(&e, &mut ed, "x; ").candidates.is_empty());
}

#[test]
fn a_picked_word_gets_one_space_and_punctuation_attaches() {
    let e = english(ALL_ON);
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "hel");
    pick(&e, &mut ed, &u, "hello");
    // Space right after adds no second space.
    type_into(&e, &mut ed, " ");
    assert_eq!(ed.shown(), "hello ");
    let u = type_into(&e, &mut ed, "wor");
    pick(&e, &mut ed, &u, "world");
    // Punctuation replaces the added space.
    type_into(&e, &mut ed, ",");
    assert_eq!(ed.shown(), "hello world,");
    type_into(&e, &mut ed, " ");
    assert_eq!(ed.shown(), "hello world, ");
}

#[test]
fn cursor_context_gives_predictions_and_spacing_mid_text() {
    let e = english(ALL_ON);
    // The cursor was put after "good " with more text after it.
    let u = e.set_cursor_context("Say good ".into(), " day".into());
    assert_eq!(u.candidates[0].text, "morning");
    let p = e.select_candidate(0);
    // A space already follows the cursor: none is added.
    assert_eq!(p.commit, "morning");
    // Inside a word there are no predictions, and a word typed there is never corrected.
    e.reset();
    assert!(e
        .set_cursor_context("go".into(), String::new())
        .candidates
        .is_empty());
    let mut ed = Editor::default();
    type_into(&e, &mut ed, "teh ");
    assert_eq!(ed.shown(), "teh ");
    // At the start of the field a capitalised typo may be corrected.
    e.reset();
    e.set_cursor_context(String::new(), String::new());
    let mut ed = Editor::default();
    type_into(&e, &mut ed, "Teh ");
    assert_eq!(ed.shown(), "The ");
    // Ignored while a word composes.
    type_into(&e, &mut ed, "wo");
    assert_eq!(
        e.set_cursor_context("x ".into(), String::new()),
        EditorUpdate::default()
    );
}

#[test]
fn suggestions_off_shows_only_the_typed_word() {
    let e = english(PredictionOptions {
        suggestions: false,
        next_word: true,
        autocorrect: true,
    });
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "teh");
    assert_eq!(texts(&u), ["teh"]);
    let u = type_into(&e, &mut ed, " ");
    assert!(u.candidates.is_empty());
    assert_eq!(ed.shown(), "teh ");
}

#[test]
fn oromo_autocorrects_only_when_asked() {
    let e = engine();
    let om = Lexicon::from_wordlist_text("nagaa\t90000\nakkam\t80000\n".into());
    e.load_pack(pack_of("om", ResourceType::Dictionary, &om.to_bytes()))
        .unwrap();
    e.set_input_mode("om".into(), InputMode::Qubee).unwrap();
    let mut ed = Editor::default();
    type_into(&e, &mut ed, "nagaq ");
    assert_eq!(ed.shown(), "nagaq ");
    e.set_prediction_options(ALL_ON);
    type_into(&e, &mut ed, "nagaq ");
    assert_eq!(ed.shown(), "nagaq nagaa ");
}

fn amharic(mode: InputMode) -> Arc<KeyboardEngine> {
    let e = engine();
    e.load_pack(pack_of("am", ResourceType::Dictionary, AM_LEXICON))
        .unwrap();
    e.load_pack(pack_of("am", ResourceType::FrequencyList, AM_BIGRAMS))
        .unwrap();
    e.load_pack(pack_of(
        "am",
        ResourceType::TransliterationRules,
        AM_PROFILE,
    ))
    .unwrap();
    e.load_pack(pack_of("am", ResourceType::KeyboardLayout, AM_LAYOUT))
        .unwrap();
    e.set_input_mode("am".into(), mode).unwrap();
    e.set_prediction_options(ALL_ON);
    e
}

#[test]
fn phonetic_predicts_the_next_word_and_is_never_autocorrected() {
    let e = amharic(InputMode::PhoneticLatin);
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "selam ");
    assert_eq!(u.candidates[0].text, "ነው");
    assert_eq!(u.candidates[0].kind, CandidateKind::Prediction);
    pick(&e, &mut ed, &u, "ነው");
    assert_eq!(ed.shown(), "ሰላም ነው ");
    // A phonetic word is committed as transliterated, never corrected.
    let u = type_into(&e, &mut ed, "selaa");
    assert_eq!(u.autocorrect_index, None);
    type_into(&e, &mut ed, " ");
    assert!(ed.shown().ends_with("ሰላአ "));
    // Completions of a partial word appear in the first slots (after the composition).
    e.reset();
    let u = type_into(&e, &mut Editor::default(), "sela");
    assert_eq!(u.candidates[1].text, "ሰላም");
}

#[test]
fn ethiopic_full_stop_and_comma_keys_attach_and_end_the_sentence() {
    // The phone's comma / period keys type ፣ / ። directly in Phonetic (and Fidel).
    let e = amharic(InputMode::PhoneticLatin);
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "sela");
    pick(&e, &mut ed, &u, "ሰላም");
    // ፣ replaces the space added behind the picked word; a clause comma keeps no previous word.
    let u = type_into(&e, &mut ed, "፣");
    assert_eq!(ed.shown(), "ሰላም፣");
    assert!(u.candidates.is_empty());
    let u = type_into(&e, &mut ed, " selam");
    pick(&e, &mut ed, &u, "ሰላም");
    type_into(&e, &mut ed, "።");
    assert_eq!(ed.shown(), "ሰላም፣ ሰላም።");
    // After ። and a space the next sentence is predicted (frequent words, no previous word).
    let u = type_into(&e, &mut ed, " ");
    assert!(!u.candidates.is_empty());
    assert!(u
        .candidates
        .iter()
        .all(|c| c.kind == CandidateKind::Prediction));
    // Fidel: the same keys commit the marks after a completion.
    let f = amharic(InputMode::DirectFidel);
    let mut ed = Editor::default();
    let u = type_into(&f, &mut ed, "ሰላ");
    pick(&f, &mut ed, &u, "ሰላም");
    type_into(&f, &mut ed, "።");
    assert_eq!(ed.shown(), "ሰላም።");
}

#[test]
fn phonetic_pick_then_ethiopic_punctuation_attaches() {
    let e = amharic(InputMode::PhoneticLatin);
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "sela");
    pick(&e, &mut ed, &u, "ሰላም");
    // `..` is ። in the profile: the first dot replaces the added space, the second completes ።.
    type_into(&e, &mut ed, "..");
    assert_eq!(ed.shown(), "ሰላም።");
}

#[test]
fn fidel_grid_completes_the_letters_typed_so_far() {
    let e = amharic(InputMode::DirectFidel);
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "ሰላ");
    assert_eq!(ed.shown(), "ሰላ");
    assert!(texts(&u).contains(&"ሰላም".to_string()));
    assert!(u.candidates.iter().all(|c| c.text != "ሰላ"));
    let p = pick(&e, &mut ed, &u, "ሰላም");
    assert_eq!((p.delete_before, p.commit.as_str()), (2, "ሰላም "));
    assert_eq!(ed.shown(), "ሰላም ");
    // Then the next word is predicted.
    assert_eq!(p.candidates[0].text, "ነው");
    // Two-key entry: the pending syllable is part of the word and is replaced too.
    e.reset();
    let mut ed = Editor::default();
    type_into(&e, &mut ed, "ሰ");
    let u = e.on_key(KeyEvent::FidelConsonant {
        series_id: "L".into(),
    });
    ed.apply(&u);
    assert_eq!(ed.shown(), "ሰል");
    let u = e.on_key(KeyEvent::FidelVowel { order: 4 });
    ed.apply(&u);
    assert!(texts(&u).contains(&"ሰላም".to_string()));
    // After a cursor move into an Ethiopic word, its letters are completed too.
    e.reset();
    let u = e.set_cursor_context("ሰላ".into(), String::new());
    assert!(texts(&u).contains(&"ሰላም".to_string()));
    assert_eq!(e.select_candidate(0).delete_before, 2);
}

#[test]
fn arabic_learns_word_pairs_from_the_user_only() {
    let e = engine();
    assert!(e.learn_user_word("ar".into(), "صباح".into(), None));
    assert!(e.learn_user_word("ar".into(), "الخير".into(), Some("صباح".into())));
    e.set_input_mode("ar".into(), InputMode::Arabic).unwrap();
    e.set_prediction_options(ALL_ON);
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "صباح ");
    assert_eq!(ed.shown(), "صباح ");
    assert_eq!(u.candidates[0].text, "الخير");
    assert_eq!(u.candidates[0].kind, CandidateKind::Prediction);
}

#[test]
fn abc_mode_never_predicts() {
    let e = amharic(InputMode::Abc);
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "selam ");
    assert!(u.candidates.is_empty());
    assert_eq!(ed.shown(), "selam ");
}
