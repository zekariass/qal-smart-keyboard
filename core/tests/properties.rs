//! Cross-module property tests (plan C1.10): the segmenter never splits grapheme clusters,
//! everything that normalises text is NFC-idempotent, and phonetic Backspace undoes the last
//! key. Ethiopic numeral round-trips live in `calendar.rs` (C1.9) and are not repeated.
//! Synthetic text only (N2).

mod common;

use common::{active, engine, fields, pack};
use proptest::prelude::*;
use qal_keyboard_core::lexicon::{Lexicon, WordEntry};
use qal_keyboard_core::ranker::Ranker;
use qal_keyboard_core::segmenter::{
    boundaries, grapheme_count, graphemes, take_graphemes, without_last_grapheme,
};
use qal_keyboard_core::{
    EditorUpdate, InputMode, KeyEvent, KeyboardEngine, PackPlatform, ReleaseChannel, ResourceType,
};
use std::sync::Arc;
use unicode_normalization::{is_nfc, UnicodeNormalization};

const AM_PROFILE: &str = include_str!("../../language-data/profiles/am-phonetic-draft.json");
const AM_LAYOUT: &str = include_str!("../../language-data/layouts/am-fidel-draft.json");

/// Text mixing Ge'ez, Latin, combining marks, ZWJ/variation selectors, emoji, CR/LF and
/// regional indicators: the cases where cluster boundaries are interesting.
fn text_strategy() -> impl Strategy<Value = String> {
    let piece = prop_oneof![
        4 => "[\u{1200}-\u{1248}]",
        3 => "[a-zA-Z]",
        3 => "[\u{300}-\u{36F}\u{135D}-\u{135F}]",
        1 => Just("\u{200D}".to_string()),
        1 => Just("\u{FE0F}".to_string()),
        1 => Just("\r\n".to_string()),
        1 => Just("\u{1F469}".to_string()),
        1 => Just("\u{1F1EA}\u{1F1F9}".to_string()),
        1 => "[\u{00C0}-\u{017F}]",
        1 => "\\PC",
    ];
    proptest::collection::vec(piece, 0..24).prop_map(|v| v.concat())
}

fn is_combining(c: char) -> bool {
    matches!(c as u32, 0x300..=0x36F | 0x135D..=0x135F | 0x200D | 0xFE00..=0xFE0F)
}

fn nfc(s: &str) -> String {
    s.nfc().collect()
}

proptest! {
    /// Clusters tile the text exactly and boundaries are valid char boundaries.
    #[test]
    fn segments_tile_the_text(s in text_strategy()) {
        let gs = graphemes(&s);
        prop_assert_eq!(gs.concat(), s.clone());
        prop_assert!(gs.iter().all(|g| !g.is_empty()));
        prop_assert_eq!(gs.len(), grapheme_count(&s));
        let b = boundaries(&s);
        prop_assert_eq!(b.len(), gs.len() + 1);
        prop_assert!(b.iter().all(|i| s.is_char_boundary(*i)));
    }

    /// A combining mark, ZWJ or variation selector never starts a cluster after a non-control
    /// character (UAX #29 GB9: no break before Extend/ZWJ).
    #[test]
    fn marks_never_start_a_cluster_after_a_base(s in text_strategy()) {
        let gs = graphemes(&s);
        for pair in gs.windows(2) {
            let prev_last = pair[0].chars().last().unwrap();
            let next_first = pair[1].chars().next().unwrap();
            if is_combining(next_first) {
                prop_assert!(
                    prev_last.is_control(),
                    "mark split from its base: {:?} | {:?}",
                    pair[0],
                    pair[1]
                );
            }
        }
    }

    /// Truncating helpers cut only on cluster boundaries and agree with the cluster count.
    #[test]
    fn truncation_keeps_clusters_whole(s in text_strategy(), n in 0usize..30) {
        let b = boundaries(&s);
        let t = take_graphemes(&s, n);
        prop_assert!(b.contains(&t.len()));
        prop_assert_eq!(grapheme_count(t), n.min(grapheme_count(&s)));
        prop_assert!(s.starts_with(t));
        let w = without_last_grapheme(&s);
        prop_assert!(b.contains(&w.len()));
        prop_assert_eq!(grapheme_count(w), grapheme_count(&s).saturating_sub(1));
    }

    /// Ge'ez syllables are single code points, hence single clusters.
    #[test]
    fn ethiopic_syllables_are_single_clusters(s in "[\u{1200}-\u{135A}]{0,20}") {
        prop_assert_eq!(grapheme_count(&s), s.chars().count());
    }
}

// ---- NFC idempotence --------------------------------------------------------------------

/// Words with composed and decomposed Latin, Ge'ez and stray marks.
fn word_strategy() -> impl Strategy<Value = String> {
    proptest::collection::vec(
        prop_oneof![
            4 => "[a-z]",
            2 => "[\u{00C0}-\u{017F}]",
            2 => "[\u{300}-\u{36F}]",
            4 => "[\u{1200}-\u{1248}]",
            1 => "[\u{135D}-\u{135F}]",
        ],
        1..10,
    )
    .prop_map(|v| v.concat())
}

proptest! {
    /// Lexicon: lookups are NFC-insensitive and everything it returns is already NFC (so
    /// normalising again changes nothing), including after a byte round-trip.
    #[test]
    fn lexicon_is_nfc_idempotent(words in proptest::collection::vec(word_strategy(), 1..12)) {
        let entries: Vec<WordEntry> = words
            .iter()
            .map(|w| WordEntry { word: w.clone(), frequency: 10 })
            .collect();
        let lex = Lexicon::from_entries(entries);
        let reloaded = Lexicon::from_bytes(lex.to_bytes()).unwrap();
        for w in &words {
            let d: String = w.nfd().collect();
            prop_assert!(lex.contains(w.clone()));
            prop_assert!(lex.contains(nfc(w)));
            prop_assert!(lex.contains(d));
            for lx in [&lex, &reloaded] {
                for m in lx.suggest(w.clone(), 20) {
                    prop_assert!(is_nfc(&m.word));
                    prop_assert_eq!(nfc(&m.word), m.word);
                }
            }
        }
        prop_assert_eq!(reloaded.base_word_count(), lex.base_word_count());
    }

    /// User overlay: the NFD and NFC spellings are one word.
    #[test]
    fn user_overlay_is_nfc_idempotent(w in word_strategy()) {
        let lex = Lexicon::new_empty();
        lex.add_user_word(w.clone());
        lex.add_user_word(nfc(&w));
        lex.add_user_word(w.nfd().collect());
        prop_assert!(lex.user_word_count() <= 1);
    }

    /// Ranker: folds normalise the typed word, so NFC and NFD input rank identically and all
    /// candidate texts are NFC.
    #[test]
    fn ranker_is_nfc_idempotent(
        words in proptest::collection::vec(word_strategy(), 1..12),
        typed in word_strategy(),
    ) {
        let entries: Vec<WordEntry> = words
            .into_iter()
            .map(|w| WordEntry { word: w, frequency: 100 })
            .collect();
        let ranker = Ranker::new(Lexicon::from_entries(entries));
        let texts = |t: &str| -> Vec<String> {
            ranker
                .rank(t.to_string(), None, 8)
                .into_iter()
                .map(|c| c.text)
                .collect()
        };
        let a = texts(&typed);
        prop_assert_eq!(&a, &texts(&typed.nfd().collect::<String>()));
        prop_assert_eq!(&a, &texts(&nfc(&typed)));
        prop_assert!(a.iter().all(|t| is_nfc(t)));
    }
}

fn pack_for(rt: ResourceType, artifact: &str) -> Vec<u8> {
    let mut f = fields("0.1.0");
    f.language_code = "am".to_string();
    f.resource_type = rt;
    f.platform = PackPlatform::Shared;
    f.release_channel = ReleaseChannel::Stable;
    pack(&active(), &f, artifact.as_bytes())
}

fn phonetic() -> Arc<KeyboardEngine> {
    let e = engine();
    e.load_pack(pack_for(ResourceType::TransliterationRules, AM_PROFILE))
        .unwrap();
    e.set_input_mode("am".into(), InputMode::PhoneticLatin)
        .unwrap();
    e
}

fn direct() -> Arc<KeyboardEngine> {
    let e = engine();
    e.load_pack(pack_for(ResourceType::KeyboardLayout, AM_LAYOUT))
        .unwrap();
    e.set_input_mode("am".into(), InputMode::DirectFidel)
        .unwrap();
    e
}

fn assert_update_nfc(u: &EditorUpdate) -> Result<(), TestCaseError> {
    prop_assert!(is_nfc(&u.commit), "commit not NFC");
    prop_assert!(is_nfc(&u.composing), "composing not NFC");
    prop_assert!(u.candidates.iter().all(|c| is_nfc(&c.text)));
    Ok(())
}

proptest! {
    /// Composer output (direct Fidel and phonetic) is NFC for any host text, including
    /// decomposed Latin and stray combining marks.
    #[test]
    fn composer_output_is_nfc(texts in proptest::collection::vec(text_strategy(), 1..6)) {
        for e in [direct(), phonetic()] {
            for t in &texts {
                let u = e.on_key(KeyEvent::Char { text: t.clone() });
                assert_update_nfc(&u)?;
                prop_assert_eq!(nfc(&u.commit), u.commit.clone());
            }
            assert_update_nfc(&e.on_key(KeyEvent::Commit))?;
        }
    }
}

// ---- Declared reversibility: phonetic Backspace undoes the last key ---------------------

proptest! {
    /// "The last Latin keystroke is gone" (translit session): for any letter prefix, typing one
    /// more letter and pressing Backspace restores exactly the previous composing text and
    /// candidate list. The prefix stays below the 48-key forced-commit limit.
    #[test]
    fn phonetic_backspace_undoes_the_last_key(
        prefix in "[a-zA-Z']{0,30}",
        extra in "[a-zA-Z']",
    ) {
        let e = phonetic();
        let mut before = EditorUpdate::default();
        for c in prefix.chars() {
            before = e.on_key(KeyEvent::Char { text: c.to_string() });
        }
        let typed = e.on_key(KeyEvent::Char { text: extra });
        prop_assume!(typed.commit.is_empty());
        let after = e.on_key(KeyEvent::Backspace);
        prop_assert_eq!(after.commit, String::new());
        prop_assert_eq!(after.delete_before, 0);
        prop_assert_eq!(after.composing, before.composing);
        prop_assert_eq!(after.candidates, before.candidates);
    }

    /// Backspacing a whole composition leaves nothing composing; one more Backspace is
    /// delegated to the host (delete one cluster before the cursor).
    #[test]
    fn phonetic_backspacing_everything_empties_the_buffer(prefix in "[a-zA-Z']{1,30}") {
        let e = phonetic();
        for c in prefix.chars() {
            e.on_key(KeyEvent::Char { text: c.to_string() });
        }
        let mut last = EditorUpdate::default();
        for _ in prefix.chars() {
            last = e.on_key(KeyEvent::Backspace);
        }
        prop_assert_eq!(last.composing, String::new());
        prop_assert!(last.candidates.is_empty());
        prop_assert_eq!(e.on_key(KeyEvent::Backspace).delete_before, 1);
    }
}
