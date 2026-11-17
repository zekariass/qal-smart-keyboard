//! L1.3: the committed seed payloads (`language-data/seed/artifacts/`) load in the core and
//! behave sanely. They are DRAFT / UNVERIFIED tiny seeds, so only smoke checks live here.
use std::path::PathBuf;

use qal_keyboard_core::langid::{LangIdDetector, LangTag};
use qal_keyboard_core::lexicon::Lexicon;
use qal_keyboard_core::ranker::Ranker;

fn read(name: &str) -> Vec<u8> {
    let p = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../language-data/seed/artifacts")
        .join(name);
    std::fs::read(&p).unwrap_or_else(|e| panic!("{}: {e}", p.display()))
}

#[test]
fn lexicons_and_bigrams_load_and_answer() {
    for (lang, prefix, word) in [
        ("am", "ሰላ", "ሰላም"),
        ("ti", "ሰላ", "ሰላም"),
        ("om", "nag", "nagaa"),
        ("en", "hel", "hello"),
    ] {
        let lex = Lexicon::from_bytes(read(&format!("{lang}.lexicon.qlx"))).expect("lexicon");
        assert!(lex.base_word_count() >= 80, "{lang}: too few words");
        assert!(lex.contains(word.to_string()), "{lang}: {word}");
        let hits = lex.suggest(prefix.to_string(), 5);
        assert!(
            hits.iter().any(|m| m.word == word),
            "{lang}: prefix {prefix}"
        );
        let ranker = Ranker::new(lex);
        ranker
            .load_bigrams(read(&format!("{lang}.bigrams.qbg")))
            .expect("bigrams");
    }
}

#[test]
fn bigram_seed_predicts_next_word() {
    let lex = Lexicon::from_bytes(read("en.lexicon.qlx")).expect("lexicon");
    let ranker = Ranker::new(lex);
    ranker
        .load_bigrams(read("en.bigrams.qbg"))
        .expect("bigrams");
    let next = ranker.next_words("good".to_string(), 3);
    assert_eq!(next.first().map(|c| c.text.as_str()), Some("morning"));
}

#[test]
fn langid_seed_loads_and_separates_scripts() {
    let det = LangIdDetector::from_bytes(read("mul.langid.qlid")).expect("langid model");
    let d = det.detect_word("ሰላም".to_string(), vec![], vec![]);
    assert!(matches!(
        d.candidates.first().map(|c| c.lang),
        Some(LangTag::Am | LangTag::Ti)
    ));
}
