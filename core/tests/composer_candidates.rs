//! A1.3: phonetic compositions merged with lexicon candidates from DICTIONARY and
//! FREQUENCY_LIST packs (ADR 0004). Uses the DRAFT seed payloads from `language-data/seed/`,
//! TEST signing keys only and synthetic text (N2).

mod common;

use common::{active, config, engine, fields, pack};
use qal_keyboard_core::lexicon::Lexicon;
use qal_keyboard_core::ranker::bigram_payload_from_text;
use qal_keyboard_core::{
    Candidate, CandidateKind, CoreError, EditorUpdate, InputMode, KeyEvent, KeyboardEngine,
    PackPlatform, PackRejectReason, ResourceType,
};
use serde_json::Value;
use std::sync::Arc;

const AM_PROFILE: &[u8] = include_bytes!("../../language-data/profiles/am-phonetic-draft.json");
const AM_LEXICON: &[u8] = include_bytes!("../../language-data/seed/artifacts/am.lexicon.qlx");
const AM_BIGRAMS: &[u8] = include_bytes!("../../language-data/seed/artifacts/am.bigrams.qbg");
const TI_LEXICON: &[u8] = include_bytes!("../../language-data/seed/artifacts/ti.lexicon.qlx");
const AM_GOLDEN: &str = include_str!("golden/am-phonetic-0.1.0.json");

fn pack_of(lang: &str, rt: ResourceType, version: &str, artifact: &[u8]) -> Vec<u8> {
    let mut f = fields(version);
    f.language_code = lang.to_string();
    f.resource_type = rt;
    f.platform = PackPlatform::Shared;
    pack(&active(), &f, artifact)
}

fn phonetic(e: &Arc<KeyboardEngine>) {
    e.load_pack(pack_of(
        "am",
        ResourceType::TransliterationRules,
        "0.1.0",
        AM_PROFILE,
    ))
    .unwrap();
    e.set_input_mode("am".into(), InputMode::PhoneticLatin)
        .unwrap();
}

fn load_dictionary(e: &KeyboardEngine) {
    e.load_pack(pack_of("am", ResourceType::Dictionary, "0.1.0", AM_LEXICON))
        .unwrap();
}

fn load_bigrams(e: &KeyboardEngine) {
    e.load_pack(pack_of(
        "am",
        ResourceType::FrequencyList,
        "0.1.0",
        AM_BIGRAMS,
    ))
    .unwrap();
}

/// Phonetic AM engine without lexicon packs (the C1.4 behaviour).
fn plain() -> Arc<KeyboardEngine> {
    let e = engine();
    phonetic(&e);
    e
}

/// Phonetic AM engine with the seed dictionary and bigrams.
fn full() -> Arc<KeyboardEngine> {
    let e = engine();
    load_dictionary(&e);
    load_bigrams(&e);
    phonetic(&e);
    e
}

fn with_max(max: u32) -> Arc<KeyboardEngine> {
    let mut c = config(&[&active()], None);
    c.max_candidates = max;
    let e = KeyboardEngine::new(c).unwrap();
    load_dictionary(&e);
    phonetic(&e);
    e
}

fn typed(e: &KeyboardEngine, keys: &str) -> EditorUpdate {
    let mut last = EditorUpdate::default();
    for c in keys.chars() {
        last = match c {
            ' ' => e.on_key(KeyEvent::Space),
            _ => e.on_key(KeyEvent::Char { text: c.into() }),
        };
    }
    last
}

fn texts(u: &EditorUpdate) -> Vec<&str> {
    u.candidates.iter().map(|c| c.text.as_str()).collect()
}

fn of_kind(u: &EditorUpdate, kind: CandidateKind) -> Vec<&str> {
    u.candidates
        .iter()
        .filter(|c| c.kind == kind)
        .map(|c| c.text.as_str())
        .collect()
}

#[test]
fn without_lexicon_packs_candidates_are_unchanged() {
    let u = typed(&plain(), "sela");
    assert_eq!(texts(&u), ["ሰላ", "ሠላ"]);
    assert!(u
        .candidates
        .iter()
        .all(|c| c.kind == CandidateKind::Transliteration));
}

#[test]
fn completions_follow_transliteration_alternatives() {
    let u = typed(&full(), "sela");
    assert_eq!(u.composing, "ሰላ");
    assert_eq!(
        u.candidates[..2],
        [
            Candidate {
                text: "ሰላ".into(),
                kind: CandidateKind::Transliteration
            },
            Candidate {
                text: "ሠላ".into(),
                kind: CandidateKind::Transliteration
            },
        ]
    );
    assert!(of_kind(&u, CandidateKind::Completion).contains(&"ሰላም"));
    // Transliteration first, then lexicon candidates: never interleaved.
    let first_lexicon = u
        .candidates
        .iter()
        .position(|c| c.kind != CandidateKind::Transliteration)
        .unwrap();
    assert!(u.candidates[first_lexicon..]
        .iter()
        .all(|c| c.kind != CandidateKind::Transliteration));
}

#[test]
fn selecting_a_completion_commits_it() {
    let e = full();
    let u = typed(&e, "sela");
    let i = u.candidates.iter().position(|c| c.text == "ሰላም").unwrap();
    let s = e.select_candidate(u32::try_from(i).unwrap());
    assert_eq!(s.commit, "ሰላም");
    assert!(s.composing.is_empty() && s.candidates.is_empty());
    // The next word starts a fresh composition.
    assert_eq!(typed(&e, "ne").composing, "ነ");
}

#[test]
fn candidates_are_deduplicated() {
    // "selam" composes the dictionary word itself: it must not appear twice.
    for keys in ["selam", "sela", "s", "ne", "ab"] {
        let u = typed(&full(), keys);
        let mut seen = texts(&u);
        let n = seen.len();
        seen.sort_unstable();
        seen.dedup();
        assert_eq!(seen.len(), n, "{keys}: duplicate candidates");
        assert_eq!(u.candidates[0].text, u.composing, "{keys}: index 0");
    }
}

#[test]
fn merged_list_is_capped_at_max_candidates() {
    for max in [1, 2, 3, 5, 8] {
        let u = typed(&with_max(max), "sela");
        assert!(u.candidates.len() <= max as usize, "max {max}");
        assert_eq!(u.candidates[0].text, "ሰላ");
    }
    // With room for the alternative only, no lexicon candidate squeezes it out.
    assert_eq!(texts(&typed(&with_max(2), "sela")), ["ሰላ", "ሠላ"]);
}

#[test]
fn bigram_context_reorders_completions() {
    // Seed frequencies: ነህ 94 > ነው 93; seed bigram ሰላም -> ነው.
    let cold = typed(&full(), "ne");
    let c = of_kind(&cold, CandidateKind::Completion);
    let pos = |v: &[&str], w: &str| v.iter().position(|x| *x == w);
    assert!(pos(&c, "ነህ") < pos(&c, "ነው"), "no context: {c:?}");

    let e = full();
    typed(&e, "selam ");
    let warm = typed(&e, "ne");
    let w = of_kind(&warm, CandidateKind::Completion);
    assert_eq!(w.first(), Some(&"ነው"), "after ሰላም: {w:?}");
}

#[test]
fn context_is_forgotten_on_reset_and_sentence_end() {
    let e = full();
    typed(&e, "selam ");
    e.reset();
    let c = typed(&e, "ne");
    assert_ne!(of_kind(&c, CandidateKind::Completion).first(), Some(&"ነው"));

    let e = full();
    typed(&e, "selam");
    e.on_key(KeyEvent::Char { text: "?".into() });
    e.on_key(KeyEvent::Space);
    let c = typed(&e, "ne");
    assert_ne!(of_kind(&c, CandidateKind::Completion).first(), Some(&"ነው"));
}

#[test]
fn corrections_are_offered_never_applied() {
    // "slam" composes ስላም (sixth order first letter); ሰላም is a vowel-order slip away.
    let e = full();
    let u = typed(&e, "slam");
    assert_eq!(u.composing, "ስላም");
    let lexicon: Vec<&Candidate> = u
        .candidates
        .iter()
        .filter(|c| c.kind != CandidateKind::Transliteration)
        .collect();
    assert!(
        lexicon.iter().any(|c| c.text == "ሰላም"),
        "expected ሰላም among {lexicon:?}"
    );
    // Harm guard: the composition is committed as typed.
    assert_eq!(e.on_key(KeyEvent::Space).commit, "ስላም ");
}

#[test]
fn merged_candidates_are_deterministic() {
    for keys in ["sela", "ne", "slam", "abeba", "ityo"] {
        assert_eq!(typed(&full(), keys), typed(&full(), keys), "{keys}");
    }
}

#[test]
fn golden_vectors_keep_their_behaviour_with_a_dictionary() {
    // Every C1.4 golden vector, with and without lexicon packs: composing, commit and deletes
    // are identical, and the transliteration candidates are an unchanged prefix of the strip.
    let g: Value = serde_json::from_str(AM_GOLDEN).unwrap();
    let (a, b) = (plain(), full());
    for v in g["vectors"].as_array().unwrap() {
        let name = v["name"].as_str().unwrap();
        a.reset();
        b.reset();
        for k in v["keystrokes"].as_array().unwrap() {
            let k = k.as_str().unwrap();
            let step = |e: &KeyboardEngine| match k {
                "Backspace" => e.on_key(KeyEvent::Backspace),
                "Space" => e.on_key(KeyEvent::Space),
                "Enter" => e.on_key(KeyEvent::Enter),
                "Commit" => e.on_key(KeyEvent::Commit),
                _ => match k.strip_prefix("Select:") {
                    Some(n) => e.select_candidate(n.parse().unwrap()),
                    None => e.on_key(KeyEvent::Char { text: k.into() }),
                },
            };
            let (x, y) = (step(&a), step(&b));
            assert_eq!(x.composing, y.composing, "{name}: composing");
            assert_eq!(x.commit, y.commit, "{name}: commit");
            assert_eq!(x.delete_before, y.delete_before, "{name}: delete_before");
            assert_eq!(
                x.candidates[..],
                y.candidates[..x.candidates.len()],
                "{name}: transliteration prefix"
            );
        }
    }
}

#[test]
fn protected_tokens_and_punctuation_get_no_lexicon_candidates() {
    for keys in ["www.", "http", ":"] {
        assert_eq!(typed(&full(), keys), typed(&plain(), keys), "{keys}");
    }
}

#[test]
fn abc_and_direct_modes_get_no_candidates() {
    let e = full();
    e.set_input_mode("am".into(), InputMode::Abc).unwrap();
    assert!(typed(&e, "sela").candidates.is_empty());
}

#[test]
fn dictionary_loaded_after_mode_switch_takes_effect() {
    let e = plain();
    assert_eq!(typed(&e, "se").candidates.len(), 2);
    load_dictionary(&e);
    let u = typed(&e, "la");
    assert!(of_kind(&u, CandidateKind::Completion).contains(&"ሰላም"));
}

#[test]
fn bigrams_without_dictionary_give_no_candidates_until_it_arrives() {
    let e = engine();
    load_bigrams(&e);
    phonetic(&e);
    assert_eq!(texts(&typed(&e, "sela")), ["ሰላ", "ሠላ"]);
    load_dictionary(&e);
    e.reset();
    assert!(texts(&typed(&e, "sela")).contains(&"ሰላም"));
}

#[test]
fn dictionaries_are_per_language() {
    let e = engine();
    e.load_pack(pack_of("ti", ResourceType::Dictionary, "0.1.0", TI_LEXICON))
        .unwrap();
    phonetic(&e);
    assert_eq!(texts(&typed(&e, "sela")), ["ሰላ", "ሠላ"]);
}

#[test]
fn unparsable_dictionary_keeps_the_last_good_model() {
    let e = full();
    // A newer, validly signed dictionary whose payload is not a lexicon.
    let info = e
        .load_pack(pack_of(
            "am",
            ResourceType::Dictionary,
            "0.2.0",
            b"not a lexicon",
        ))
        .unwrap();
    assert!(!info.payload_usable);
    let dict = |e: &KeyboardEngine| {
        e.loaded_packs()
            .into_iter()
            .find(|p| p.resource_type == ResourceType::Dictionary)
            .unwrap()
    };
    // Activated (rollback protection sees 0.2.0) and reported unusable.
    assert_eq!(dict(&e).version, "0.2.0");
    assert!(!dict(&e).payload_usable);
    // The previous model still serves candidates.
    phonetic(&e);
    assert!(texts(&typed(&e, "sela")).contains(&"ሰላም"));
    // New bigrams are applied on top of the last good lexicon.
    e.load_pack(pack_of(
        "am",
        ResourceType::FrequencyList,
        "0.2.0",
        &bigram_payload_from_text("ሰላም	ነገር	50".into()),
    ))
    .unwrap();
    e.reset();
    assert!(texts(&typed(&e, "sela")).contains(&"ሰላም"));
    // A fixed, still newer pack replaces it.
    let ok = e
        .load_pack(pack_of("am", ResourceType::Dictionary, "0.3.0", AM_LEXICON))
        .unwrap();
    assert!(ok.payload_usable);
    e.reset();
    assert!(texts(&typed(&e, "sela")).contains(&"ሰላም"));
}

#[test]
fn unparsable_dictionary_without_a_previous_model_gives_no_candidates() {
    let e = engine();
    let info = e
        .load_pack(pack_of(
            "am",
            ResourceType::Dictionary,
            "0.1.0",
            b"not a lexicon",
        ))
        .unwrap();
    assert!(!info.payload_usable);
    phonetic(&e);
    assert_eq!(texts(&typed(&e, "sela")), ["ሰላ", "ሠላ"]);
}

#[test]
fn unparsable_bigrams_keep_the_last_good_bigrams() {
    let e = full();
    let info = e
        .load_pack(pack_of(
            "am",
            ResourceType::FrequencyList,
            "0.2.0",
            b"not bigrams",
        ))
        .unwrap();
    assert!(!info.payload_usable);
    phonetic(&e);
    typed(&e, "selam ");
    let w = typed(&e, "ne");
    assert!(!of_kind(&w, CandidateKind::Completion).is_empty());
}

#[test]
fn unparsable_bigrams_keep_unigram_completions() {
    let e = engine();
    load_dictionary(&e);
    e.load_pack(pack_of(
        "am",
        ResourceType::FrequencyList,
        "0.1.0",
        b"not bigrams",
    ))
    .unwrap();
    phonetic(&e);
    assert!(texts(&typed(&e, "sela")).contains(&"ሰላም"));
}

#[test]
fn dictionary_rollback_protection_is_unchanged() {
    let e = full();
    let lower = pack_of(
        "am",
        ResourceType::Dictionary,
        "0.0.9",
        &Lexicon::from_wordlist_text("ሰላቀጨ\t5".into()).to_bytes(),
    );
    match e.load_pack(lower) {
        Err(CoreError::PackRejected { reason, .. }) => {
            assert_eq!(reason, PackRejectReason::Downgrade)
        }
        other => panic!("expected Downgrade, got {other:?}"),
    }
    // The identical pack again is an idempotent success and changes nothing.
    load_dictionary(&e);
    let u = typed(&e, "sela");
    assert!(texts(&u).contains(&"ሰላም") && !texts(&u).contains(&"ሰላቀጨ"));
}

#[test]
fn newer_dictionary_and_bigrams_replace_the_model() {
    let e = full();
    e.load_pack(pack_of(
        "am",
        ResourceType::Dictionary,
        "0.2.0",
        &Lexicon::from_wordlist_text("ሰላምታ\t5\nነገር\t9\nነጭ\t3".into()).to_bytes(),
    ))
    .unwrap();
    let u = typed(&e, "sela");
    assert!(texts(&u).contains(&"ሰላምታ") && !texts(&u).contains(&"ሰላም"));
    // New bigrams reuse the lexicon and change the context ordering.
    e.load_pack(pack_of(
        "am",
        ResourceType::FrequencyList,
        "0.2.0",
        &bigram_payload_from_text("ሰላምታ\tነጭ\t50".into()),
    ))
    .unwrap();
    e.reset();
    typed(&e, "selamta ");
    let w = typed(&e, "ne");
    assert_eq!(of_kind(&w, CandidateKind::Completion).first(), Some(&"ነጭ"));
}

#[test]
fn concurrent_loading_while_typing_is_safe() {
    let e = plain();
    std::thread::scope(|s| {
        s.spawn(|| {
            load_dictionary(&e);
            load_bigrams(&e);
        });
        for _ in 0..50 {
            let u = typed(&e, "sela");
            assert_eq!(u.composing, "ሰላ");
            assert_eq!(u.candidates[..2].len(), 2);
            e.on_key(KeyEvent::Space);
        }
    });
    assert!(texts(&typed(&e, "sela")).contains(&"ሰላም"));
}
