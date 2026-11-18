//! A1.8: the engine's per-language user overlay (add / learn / forget / clear / import) and its
//! survival across pack loads. DRAFT seed payloads, TEST signing keys only, synthetic text (N2).

mod common;

use common::{active, engine, fields, pack};
use qal_keyboard_core::lexicon::Lexicon;
use qal_keyboard_core::ranker::Ranker;
use qal_keyboard_core::{
    CoreError, InputMode, KeyEvent, KeyboardEngine, PackPlatform, ResourceType,
};
use std::sync::Arc;

const AM_PROFILE: &[u8] = include_bytes!("../../language-data/profiles/am-phonetic-draft.json");
const AM_LEXICON: &[u8] = include_bytes!("../../language-data/seed/artifacts/am.lexicon.qlx");

fn pack_of(rt: ResourceType, version: &str, artifact: &[u8]) -> Vec<u8> {
    let mut f = fields(version);
    f.language_code = "am".to_string();
    f.resource_type = rt;
    f.platform = PackPlatform::Shared;
    pack(&active(), &f, artifact)
}

fn phonetic_engine() -> Arc<KeyboardEngine> {
    let e = engine();
    e.load_pack(pack_of(ResourceType::Dictionary, "0.1.0", AM_LEXICON))
        .unwrap();
    e.load_pack(pack_of(
        ResourceType::TransliterationRules,
        "0.1.0",
        AM_PROFILE,
    ))
    .unwrap();
    e.set_input_mode("am".into(), InputMode::PhoneticLatin)
        .unwrap();
    e
}

/// Types `keys` and returns the completion texts offered for the composition.
fn offered(e: &KeyboardEngine, keys: &str) -> Vec<String> {
    e.reset();
    let mut last = Default::default();
    for c in keys.chars() {
        last = e.on_key(KeyEvent::Char { text: c.into() });
    }
    let u: qal_keyboard_core::EditorUpdate = last;
    u.candidates.into_iter().map(|c| c.text).collect()
}

/// A word the seed lexicon cannot know: a rare syllable run built from the phonetic keys "gWa".
const NEW_WORD: &str = "ጓጓጓ";

#[test]
fn learned_word_is_suggested_and_survives_an_exported_restart() {
    let e = phonetic_engine();
    assert!(!offered(&e, "gWagWa").contains(&NEW_WORD.to_string()));
    assert!(e.learn_user_word("am".into(), NEW_WORD.into(), None));

    // The host keeps its own overlay copy (a Lexicon / Ranker) and re-imports it after a restart.
    let lex = Lexicon::new_empty();
    lex.learn_word(NEW_WORD.into());
    let bytes = lex.export_user_dictionary();
    let fresh = phonetic_engine();
    fresh
        .import_user_data("am".into(), bytes, Vec::new())
        .unwrap();
    assert!(offered(&fresh, "gWagWa").contains(&NEW_WORD.to_string()));
}

#[test]
fn forget_and_clear_remove_learned_words() {
    let e = phonetic_engine();
    assert!(e.learn_user_word("am".into(), NEW_WORD.into(), None));
    assert!(e.forget_user_word("am".into(), NEW_WORD.into()));
    assert!(!e.forget_user_word("am".into(), NEW_WORD.into()));
    assert!(e.add_user_word("am".into(), NEW_WORD.into()));
    e.clear_user_data("am".into());
    assert!(!e.forget_user_word("am".into(), NEW_WORD.into()));
}

#[test]
fn import_before_the_dictionary_is_applied_when_it_loads() {
    let lex = Lexicon::new_empty();
    lex.learn_word(NEW_WORD.into());
    let e = engine();
    e.import_user_data("am".into(), lex.export_user_dictionary(), Vec::new())
        .unwrap();
    assert!(!e.forget_user_word("am".into(), NEW_WORD.into())); // no model yet
    e.load_pack(pack_of(ResourceType::Dictionary, "0.1.0", AM_LEXICON))
        .unwrap();
    assert!(e.forget_user_word("am".into(), NEW_WORD.into()));
}

#[test]
fn user_overlay_survives_a_newer_dictionary_pack() {
    let e = phonetic_engine();
    assert!(e.learn_user_word("am".into(), NEW_WORD.into(), Some("ሰላም".into())));
    e.load_pack(pack_of(ResourceType::Dictionary, "0.2.0", AM_LEXICON))
        .unwrap();
    assert!(e.forget_user_word("am".into(), NEW_WORD.into()));
}

#[test]
fn bad_or_unsupported_input_changes_nothing() {
    let e = phonetic_engine();
    assert!(e.learn_user_word("am".into(), NEW_WORD.into(), None));
    assert!(matches!(
        e.import_user_data("am".into(), b"garbage".to_vec(), Vec::new()),
        Err(CoreError::InvalidState { .. })
    ));
    assert!(matches!(
        e.import_user_data("am".into(), Vec::new(), b"garbage".to_vec()),
        Err(CoreError::InvalidState { .. })
    ));
    assert!(matches!(
        e.import_user_data("xx".into(), Vec::new(), Vec::new()),
        Err(CoreError::UnsupportedLanguage { .. })
    ));
    assert!(e.forget_user_word("am".into(), NEW_WORD.into())); // still there
                                                               // No dictionary for the language: refused, nothing stored.
    assert!(!e.learn_user_word("ti".into(), NEW_WORD.into(), None));
    // Words with whitespace are refused.
    assert!(!e.learn_user_word("am".into(), "a b".into(), None));
}

#[test]
fn user_words_lists_and_forgets_on_a_plain_lexicon() {
    let lex = Lexicon::new_empty();
    lex.learn_word("ቤት".into());
    lex.add_user_word("ሰላም".into());
    assert_eq!(lex.user_words().len(), 2);
    assert!(lex.forget_user_word("ቤት".into()));
    assert_eq!(lex.user_words(), vec!["ሰላም".to_string()]);
    // An empty bigram overlay exports bytes that import again.
    let r = Ranker::new(lex);
    r.import_user_bigrams(r.export_user_bigrams()).unwrap();
}
