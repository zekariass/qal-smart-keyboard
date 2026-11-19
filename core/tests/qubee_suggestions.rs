//! Afaan Oromoo (Qubee) word suggestions: once the `om` DICTIONARY pack is loaded, the word being
//! typed composes and the strip offers dictionary words (seed DRAFT data, TEST keys, synthetic
//! text only, N2). Without a dictionary Qubee stays plain passthrough (`tests/latin_modes.rs`).

mod common;

use common::{active, engine, fields, pack};
use qal_keyboard_core::{
    CandidateKind, EditorUpdate, InputMode, KeyEvent, KeyboardEngine, PackPlatform, ResourceType,
};
use std::sync::Arc;

const OM_LEXICON: &[u8] = include_bytes!("../../language-data/seed/artifacts/om.lexicon.qlx");
const OM_BIGRAMS: &[u8] = include_bytes!("../../language-data/seed/artifacts/om.bigrams.qbg");
const AM_LEXICON: &[u8] = include_bytes!("../../language-data/seed/artifacts/am.lexicon.qlx");

fn pack_of(lang: &str, rt: ResourceType, artifact: &[u8]) -> Vec<u8> {
    let mut f = fields("0.1.0");
    f.language_code = lang.to_string();
    f.resource_type = rt;
    f.platform = PackPlatform::Shared;
    pack(&active(), &f, artifact)
}

/// Qubee with the om seed dictionary and bigrams.
fn oromo() -> Arc<KeyboardEngine> {
    let e = engine();
    e.load_pack(pack_of("om", ResourceType::Dictionary, OM_LEXICON))
        .unwrap();
    e.load_pack(pack_of("om", ResourceType::FrequencyList, OM_BIGRAMS))
        .unwrap();
    e.set_input_mode("om".into(), InputMode::Qubee).unwrap();
    e
}

fn key(c: char) -> KeyEvent {
    match c {
        ' ' => KeyEvent::Space,
        '\n' => KeyEvent::Enter,
        _ => KeyEvent::Char {
            text: c.to_string(),
        },
    }
}

/// A minimal editor: applies deletions, commits and the composing text like the host does.
#[derive(Default)]
struct Editor {
    text: String,
    composing: String,
}

impl Editor {
    fn apply(&mut self, u: &EditorUpdate) {
        for _ in 0..u.delete_before {
            self.text.pop();
        }
        self.text.push_str(&u.commit);
        self.composing.clone_from(&u.composing);
    }

    fn shown(&self) -> String {
        format!("{}{}", self.text, self.composing)
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

fn texts(u: &EditorUpdate) -> Vec<String> {
    u.candidates.iter().map(|c| c.text.clone()).collect()
}

#[test]
fn a_word_composes_and_the_strip_offers_dictionary_words() {
    let e = oromo();
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "akk");
    assert_eq!(u.composing, "akk");
    assert!(u.commit.is_empty());
    // Index 0 is the word exactly as typed; dictionary words follow.
    assert_eq!(u.candidates[0].text, "akk");
    assert_eq!(u.candidates[0].kind, CandidateKind::Transliteration);
    assert!(texts(&u).contains(&"akkam".to_string()), "{:?}", texts(&u));
    assert!(u.candidates.len() <= 5);
    // Space commits the word as typed: nothing is ever applied automatically.
    type_into(&e, &mut ed, " ");
    assert_eq!(ed.shown(), "akk ");
}

#[test]
fn picking_a_suggestion_replaces_the_composed_word() {
    let e = oromo();
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "nag");
    let i = texts(&u).iter().position(|t| t == "nagaa").unwrap();
    let picked = e.select_candidate(u32::try_from(i).unwrap());
    ed.apply(&picked);
    // The picked word comes with its space (ADR 0039); a Space right after adds no second one.
    assert_eq!(picked.commit, "nagaa ");
    assert!(picked.composing.is_empty() && picked.candidates.is_empty());
    type_into(&e, &mut ed, " ");
    assert_eq!(ed.shown(), "nagaa ");
    // Out of range changes nothing.
    assert_eq!(e.select_candidate(9), EditorUpdate::default());
}

#[test]
fn the_hudhaa_is_part_of_the_word() {
    let e = oromo();
    let mut ed = Editor::default();
    // `har'` waits for its vowel: composed, not looked up yet.
    let u = type_into(&e, &mut ed, "har'");
    assert_eq!(u.composing, "har'");
    assert_eq!(texts(&u), ["har'"]);
    let u = type_into(&e, &mut ed, "a");
    assert_eq!(u.composing, "har'a");
    // Typographic hudhaa variants match the dictionary's spelling too.
    let mut ed2 = Editor::default();
    let e2 = oromo();
    let u = type_into(&e2, &mut ed2, "baay\u{2019}");
    assert_eq!(u.composing, "baay\u{2019}");
    let u = type_into(&e2, &mut ed2, "e");
    assert!(
        texts(&u).contains(&"baay'ee".to_string()),
        "{:?}",
        texts(&u)
    );
    // A leading apostrophe is an opening quote: committed, not composed.
    let mut ed3 = Editor::default();
    let e3 = oromo();
    let u = type_into(&e3, &mut ed3, "'");
    assert_eq!((u.commit.as_str(), u.composing.as_str()), ("'", ""));
    type_into(&e3, &mut ed3, "dhugaa' jedhe");
    type_into(&e3, &mut ed3, " ");
    assert_eq!(ed3.shown(), "'dhugaa' jedhe ");
}

#[test]
fn capitals_are_kept_in_suggestions() {
    let e = oromo();
    let mut ed = Editor::default();
    let u = type_into(&e, &mut ed, "Akk");
    assert_eq!(u.candidates[0].text, "Akk");
    assert!(texts(&u).contains(&"Akkam".to_string()), "{:?}", texts(&u));
    // Digraph-initial words capitalise the first letter only.
    let mut ed = Editor::default();
    let e = oromo();
    let u = type_into(&e, &mut ed, "Dhu");
    assert!(
        texts(&u).iter().all(|t| !t.starts_with("DH")),
        "{:?}",
        texts(&u)
    );
}

#[test]
fn text_is_committed_exactly_as_typed() {
    let e = oromo();
    let mut ed = Editor::default();
    let s = "Akkam jirta? Nagaa, ani har'a 5 kitaaba dubbise.\nBaay\u{2019}ee galatoomi!";
    type_into(&e, &mut ed, s);
    ed.apply(&e.on_key(KeyEvent::Commit));
    assert_eq!(ed.shown(), s);
    assert!(ed.composing.is_empty());
}

#[test]
fn backspace_edits_the_word_then_deletes_text() {
    let e = oromo();
    let mut ed = Editor::default();
    type_into(&e, &mut ed, "ka dh");
    let u = e.on_key(KeyEvent::Backspace);
    ed.apply(&u);
    assert_eq!((u.composing.as_str(), u.delete_before), ("d", 0));
    let u = e.on_key(KeyEvent::Backspace);
    ed.apply(&u);
    assert!(u.composing.is_empty() && u.candidates.is_empty());
    let u = e.on_key(KeyEvent::Backspace);
    ed.apply(&u);
    assert_eq!(u.delete_before, 1);
    assert_eq!(ed.shown(), "ka");
}

#[test]
fn the_previous_word_ranks_the_next_one() {
    let e = oromo();
    let mut ed = Editor::default();
    // Seed bigram "baga nagaan": after `baga`, `naga` offers `nagaan` before `nagaa`.
    let u = type_into(&e, &mut ed, "baga naga");
    let t = texts(&u);
    let (a, b) = (
        t.iter().position(|x| x == "nagaan"),
        t.iter().position(|x| x == "nagaa"),
    );
    assert!(a.is_some(), "{t:?}");
    if let (Some(a), Some(b)) = (a, b) {
        assert!(a < b, "{t:?}");
    }
}

#[test]
fn reset_and_mode_switch_drop_the_composition() {
    let e = oromo();
    let mut ed = Editor::default();
    type_into(&e, &mut ed, "ak");
    e.reset();
    // The next letter starts a new word; select has nothing to pick.
    let u = e.on_key(key('b'));
    assert_eq!(u.composing, "b");
    e.set_input_mode("om".into(), InputMode::Qubee).unwrap();
    assert_eq!(e.select_candidate(0), EditorUpdate::default());
    // Commit after a switch has nothing left to commit.
    assert_eq!(e.on_key(KeyEvent::Commit), EditorUpdate::default());
}

#[test]
fn qubee_never_suggests_from_another_language() {
    // Only an Amharic dictionary: Oromo typing stays passthrough with no strip.
    let e = engine();
    e.load_pack(pack_of("am", ResourceType::Dictionary, AM_LEXICON))
        .unwrap();
    e.set_input_mode("om".into(), InputMode::Qubee).unwrap();
    let u = e.on_key(key('a'));
    assert_eq!(u.commit, "a");
    assert!(u.composing.is_empty() && u.candidates.is_empty());
}

#[test]
fn english_and_abc_stay_passthrough_with_an_oromo_dictionary() {
    let e = oromo();
    for (lang, mode) in [("en", InputMode::English), ("om", InputMode::Abc)] {
        e.set_input_mode(lang.into(), mode).unwrap();
        let u = e.on_key(key('a'));
        assert_eq!(u.commit, "a", "{lang} {mode:?}");
        assert!(u.composing.is_empty() && u.candidates.is_empty());
    }
}
