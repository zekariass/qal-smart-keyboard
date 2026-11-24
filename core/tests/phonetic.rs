//! Phonetic Latin to Ge'ez transliteration (C1.4): golden corpora per profile version, profile
//! validation, engine integration. Uses TEST signing keys only and synthetic text (N2).

mod common;

use common::{active, config, engine, fields, pack, retiring, revoked};
use proptest::prelude::*;
use qal_keyboard_core::translit::{Profile, ProfileStatus};
use qal_keyboard_core::{
    CoreError, EditorUpdate, InputMode, KeyEvent, KeyboardEngine, PackPlatform, ReleaseChannel,
    ResourceType, TranslitProfileStatus,
};
use serde_json::Value;
use std::sync::Arc;

const AM_PROFILE: &str = include_str!("../../language-data/profiles/am-phonetic-draft.json");
const TI_PROFILE: &str = include_str!("../../language-data/profiles/ti-phonetic-draft.json");
const AM_GOLDEN: &str = include_str!("golden/am-phonetic-0.4.0.json");
const TI_GOLDEN: &str = include_str!("golden/ti-phonetic-0.4.0.json");

fn profile_pack(lang: &str, version: &str, artifact: &[u8]) -> Vec<u8> {
    let mut f = fields(version);
    f.language_code = lang.to_string();
    f.resource_type = ResourceType::TransliterationRules;
    f.platform = PackPlatform::Shared;
    f.release_channel = ReleaseChannel::Stable;
    pack(&active(), &f, artifact)
}

fn engine_with(lang: &str, profile: &str) -> Arc<KeyboardEngine> {
    let e = engine();
    e.load_pack(profile_pack(lang, "0.1.0", profile.as_bytes()))
        .unwrap();
    e.set_input_mode(lang.into(), InputMode::PhoneticLatin)
        .unwrap();
    e
}

fn parse_event(s: &str) -> Result<KeyEvent, usize> {
    Ok(match s {
        "Backspace" => KeyEvent::Backspace,
        "Space" => KeyEvent::Space,
        "Enter" => KeyEvent::Enter,
        "Commit" => KeyEvent::Commit,
        _ => match s.strip_prefix("Select:") {
            Some(n) => return Err(n.parse().unwrap()),
            None => {
                assert_eq!(s.chars().count(), 1, "a key is one character: {s}");
                KeyEvent::Char { text: s.into() }
            }
        },
    })
}

fn alternatives(u: &EditorUpdate) -> Vec<String> {
    u.candidates
        .iter()
        .skip(1)
        .map(|c| c.text.clone())
        .collect()
}

fn strings(v: &Value) -> Vec<String> {
    v.as_array()
        .unwrap()
        .iter()
        .map(|x| x.as_str().unwrap().to_string())
        .collect()
}

fn run_golden(golden: &str, lang: &str, profile: &str, min_vectors: usize) {
    let g: Value = serde_json::from_str(golden).unwrap();
    assert_eq!(g["status"], "DRAFT", "golden corpus of a DRAFT profile");
    let pv = Profile::parse(profile.as_bytes()).unwrap();
    assert_eq!(g["profile"].as_str().unwrap(), pv.profile_id());
    assert_eq!(g["profile_version"].as_str().unwrap(), pv.profile_version());
    let e = engine_with(lang, profile);
    let vectors = g["vectors"].as_array().unwrap();
    assert!(vectors.len() >= min_vectors, "corpus too small");
    for v in vectors {
        let name = v["name"].as_str().unwrap();
        e.reset();
        let steps = v.get("steps").and_then(Value::as_array);
        let mut committed: Vec<char> = Vec::new();
        let mut last = EditorUpdate::default();
        for (i, k) in v["keystrokes"].as_array().unwrap().iter().enumerate() {
            let n = i + 1;
            let u = match parse_event(k.as_str().unwrap()) {
                Ok(ev) => e.on_key(ev),
                Err(index) => e.select_candidate(u32::try_from(index).unwrap()),
            };
            for _ in 0..u.delete_before {
                committed.pop();
            }
            committed.extend(u.commit.chars());
            if let Some(step) = steps
                .into_iter()
                .flatten()
                .find(|s| s["after"].as_u64() == Some(n as u64))
            {
                assert_eq!(
                    u.composing,
                    step["composing"].as_str().unwrap(),
                    "{name}@{n} composing"
                );
                assert_eq!(
                    u.commit,
                    step["commit"].as_str().unwrap(),
                    "{name}@{n} commit"
                );
                assert_eq!(
                    u64::from(u.delete_before),
                    step["delete_before"].as_u64().unwrap_or(0),
                    "{name}@{n} delete_before"
                );
                if let Some(a) = step.get("alternatives") {
                    assert_eq!(alternatives(&u), strings(a), "{name}@{n} alternatives");
                }
            }
            last = u;
        }
        assert_eq!(
            last.composing,
            v["composing"].as_str().unwrap(),
            "{name} composing"
        );
        assert_eq!(
            alternatives(&last),
            strings(&v["alternatives"]),
            "{name} alternatives"
        );
        let committed: String = committed.into_iter().collect();
        assert_eq!(
            committed,
            v["committed"].as_str().unwrap(),
            "{name} committed"
        );
    }
}

#[test]
fn golden_am() {
    run_golden(AM_GOLDEN, "am", AM_PROFILE, 40);
}

#[test]
fn golden_ti() {
    run_golden(TI_GOLDEN, "ti", TI_PROFILE, 10);
}

#[test]
fn source_profiles_are_valid_and_draft() {
    for (lang, src, version) in [("am", AM_PROFILE, "0.4.0"), ("ti", TI_PROFILE, "0.4.0")] {
        let p = Profile::parse(src.as_bytes()).unwrap();
        assert_eq!(p.language(), lang);
        // N9: unreviewed profiles stay DRAFT until the G0 panel approves them.
        assert_eq!(p.status(), ProfileStatus::Draft);
        let e = engine_with(lang, src);
        let info = e.translit_profile(lang.into()).unwrap();
        assert_eq!(info.status, TranslitProfileStatus::Draft);
        assert_eq!(info.profile_id, format!("{lang}-phonetic"));
        assert_eq!(info.profile_version, version);
    }
}

#[test]
fn phonetic_mode_needs_a_loaded_profile() {
    let e = engine();
    assert!(matches!(
        e.set_input_mode("am".into(), InputMode::PhoneticLatin),
        Err(CoreError::InvalidState { .. })
    ));
    assert!(matches!(
        e.translit_profile("am".into()),
        Err(CoreError::InvalidState { .. })
    ));
    assert!(matches!(
        e.translit_profile("xx".into()),
        Err(CoreError::UnsupportedLanguage { .. })
    ));
    // Still ABC: keys are not swallowed.
    assert_eq!(e.on_key(KeyEvent::Char { text: "a".into() }).commit, "a");
    // A Tigrinya profile does not enable Amharic.
    e.load_pack(profile_pack("ti", "0.1.0", TI_PROFILE.as_bytes()))
        .unwrap();
    assert!(e
        .set_input_mode("am".into(), InputMode::PhoneticLatin)
        .is_err());
}

#[test]
fn invalid_profiles_only_fail_phonetic_mode() {
    // The pack is authentic, so it loads (other tests use synthetic transliteration packs);
    // the profile is validated when phonetic mode is requested.
    for (lang, artifact) in [
        ("am", AM_PROFILE.replace("DRAFT", "FINAL")),
        ("am", "not json".into()),
    ] {
        let e = engine();
        e.load_pack(profile_pack(lang, "0.1.0", artifact.as_bytes()))
            .unwrap();
        assert!(matches!(
            e.set_input_mode(lang.into(), InputMode::PhoneticLatin),
            Err(CoreError::InvalidState { .. })
        ));
        assert!(matches!(
            e.translit_profile(lang.into()),
            Err(CoreError::InvalidState { .. })
        ));
        // Still ABC.
        assert_eq!(e.on_key(KeyEvent::Char { text: "a".into() }).commit, "a");
    }
    // Language mismatch between manifest and profile.
    let e = engine();
    e.load_pack(profile_pack("ti", "0.1.0", AM_PROFILE.as_bytes()))
        .unwrap();
    assert!(e
        .set_input_mode("ti".into(), InputMode::PhoneticLatin)
        .is_err());
}

#[test]
fn profile_validation() {
    let ok = |s: &str| Profile::parse(s.as_bytes());
    assert!(ok(AM_PROFILE).is_ok());
    let mutate = |from: &str, to: &str| {
        assert!(AM_PROFILE.contains(from), "test needs {from}");
        AM_PROFILE.replacen(from, to, 1)
    };
    for (from, to, what) in [
        (
            "\"formatVersion\": 1",
            "\"formatVersion\": 2",
            "format version",
        ),
        ("\"status\": \"DRAFT\"", "\"status\": \"FINAL\"", "status"),
        ("\"language\": \"am\"", "\"language\": \"om\"", "language"),
        (
            "\"profileVersion\": \"0.4.0\"",
            "\"profileVersion\": \"x\"",
            "semver",
        ),
        (
            "\"series\": \"H\", \"alt\": [\"HH\", \"X\"]",
            "\"series\": \"NOPE\"",
            "unknown series",
        ),
        (
            "\"alt\": [\"HH\", \"X\"]",
            "\"alt\": [\"H\"]",
            "alt equals series",
        ),
        (
            "{\"in\": \"hh\", \"series\": \"HH\"}",
            "{\"in\": \"h\", \"series\": \"HH\"}",
            "duplicate input",
        ),
        (
            "{\"in\": \"e\", \"order\": 1}",
            "{\"in\": \"e\", \"order\": 10}",
            "order range",
        ),
        (
            "\"separator\": \"_\"",
            "\"separator\": \"s\"",
            "separator clashes with a rule",
        ),
        (
            "\"labialMarker\": \"W\"",
            "\"labialMarker\": \"a\"",
            "marker clashes with a rule",
        ),
        (
            "\"labialAltMarker\": \"w\"",
            "\"labialAltMarker\": \"W\"",
            "alternative marker equals the labial marker",
        ),
        (
            "\"labialAltMarker\": \"w\"",
            "\"labialAltMarker\": \"wu\"",
            "alternative marker is not a consonant input",
        ),
        (
            "{\"in\": \"..\", \"out\": \"።\"}",
            "{\"in\": \"..\", \"out\": \".\"}",
            "punctuation output",
        ),
        (
            "{\"in\": \"..\", \"out\": \"።\"}",
            "{\"in\": \"s.\", \"out\": \"።\"}",
            "punctuation starts with a letter",
        ),
        (
            "\"consonants\": [",
            "\"extra\": 1, \"consonants\": [",
            "unknown field",
        ),
    ] {
        assert!(ok(&mutate(from, to)).is_err(), "{what} must be rejected");
    }
    assert!(ok(&"x".repeat(300 * 1024)).is_err());
    // Errors never echo profile content.
    let msg = ok(&mutate(
        "\"series\": \"H\", \"alt\"",
        "\"series\": \"SECRETWORD\", \"alt\"",
    ))
    .unwrap_err()
    .to_string();
    assert!(!msg.contains("SECRETWORD"));
}

#[test]
fn candidates_respect_max_candidates() {
    let mut c = config(&[&active(), &retiring(), &revoked()], None);
    c.max_candidates = 2;
    let e = KeyboardEngine::new(c).unwrap();
    e.load_pack(profile_pack("am", "0.1.0", AM_PROFILE.as_bytes()))
        .unwrap();
    e.set_input_mode("am".into(), InputMode::PhoneticLatin)
        .unwrap();
    let mut u = EditorUpdate::default();
    for k in ["a", "m", "a", "n"] {
        u = e.on_key(KeyEvent::Char { text: k.into() });
    }
    // "aman" has three alternatives; only the default and one fit.
    assert_eq!(u.candidates.len(), 2);
    assert_eq!(u.candidates[0].text, u.composing);
}

/// Owner report (repeated-key bug): typing ግግር needs ግ twice, but the second press of the same key
/// never showed because every doubled consonant collapsed as gemination. Every letter key whose
/// single press is a bare consonant must now give two letters when pressed twice (and the word
/// committed by Space keeps both), in both profiles, whatever comes after it except a vowel.
#[test]
fn a_repeated_consonant_key_is_never_swallowed() {
    for (lang, profile) in [("am", AM_PROFILE), ("ti", TI_PROFILE)] {
        let e = engine_with(lang, profile);
        for key in ('a'..='z')
            .filter(|c| !"aeiou".contains(*c))
            .map(String::from)
        {
            e.reset();
            let once = e.on_key(KeyEvent::Char { text: key.clone() });
            if once.composing.chars().count() != 1 || once.composing.is_ascii() {
                continue; // a vowel, an unmapped key, or a digraph start
            }
            let twice = e.on_key(KeyEvent::Char { text: key.clone() });
            if twice.composing == once.composing {
                // A digraph of a doubled letter (`hh` ሐ) is a different letter, not a lost one.
                panic!("{lang} {key}{key} swallowed the second press");
            }
            let bare = once.composing.clone();
            let space = e.on_key(KeyEvent::Space);
            if key != "h" {
                assert_eq!(space.commit, format!("{bare}{bare} "), "{lang} {key}{key}");
            }
        }
        // Before a vowel a doubled consonant is still gemination (one letter), with the
        // two-letter reading as an alternative.
        e.reset();
        let mut u = EditorUpdate::default();
        for k in ["g", "g", "e"] {
            u = e.on_key(KeyEvent::Char { text: k.into() });
        }
        assert_eq!(u.composing, "ገ", "{lang}");
        assert!(u.candidates.iter().any(|c| c.text == "ግገ"), "{lang}");
        // Owner words.
        for (keys, want) in [("ggr ", "ግግር "), ("selam ", "ሰላም "), ("tegt'ami ", "ተግጣሚ ")]
        {
            e.reset();
            let mut out = String::new();
            for ch in keys.chars() {
                let ev = if ch == ' ' {
                    KeyEvent::Space
                } else {
                    KeyEvent::Char { text: ch.into() }
                };
                out.push_str(&e.on_key(ev).commit);
            }
            assert_eq!(out, want, "{lang} {keys}");
        }
    }
}

#[test]
fn fidel_keys_and_control_text_do_not_disturb_composing() {
    let e = engine_with("am", AM_PROFILE);
    e.on_key(KeyEvent::Char { text: "s".into() });
    let u = e.on_key(KeyEvent::FidelConsonant {
        series_id: "S".into(),
    });
    assert_eq!((u.composing.as_str(), u.commit.as_str()), ("ስ", ""));
    let u = e.on_key(KeyEvent::FidelVowel { order: 1 });
    assert_eq!((u.composing.as_str(), u.commit.as_str()), ("ስ", ""));
    // Control characters are refused; the composition survives.
    let u = e.on_key(KeyEvent::Char {
        text: "\u{7}".into(),
    });
    assert_eq!((u.composing.as_str(), u.commit.as_str()), ("ስ", ""));
    // Switching modes drops the composition without committing it.
    e.set_input_mode("am".into(), InputMode::Abc).unwrap();
    assert_eq!(e.on_key(KeyEvent::Char { text: "a".into() }).commit, "a");
}

#[test]
fn reset_clears_the_composition_and_candidates() {
    let e = engine_with("am", AM_PROFILE);
    for k in ["s", "e"] {
        e.on_key(KeyEvent::Char { text: k.into() });
    }
    e.reset();
    assert_eq!(e.select_candidate(0), EditorUpdate::default());
    let u = e.on_key(KeyEvent::Commit);
    assert_eq!(u.commit, "");
    assert_eq!(u.composing, "");
}

fn key_strategy() -> impl Strategy<Value = KeyEvent> {
    prop_oneof![
        8 => "[a-zA-Z'_@./:;,?!0-9-]".prop_map(|text| KeyEvent::Char { text }),
        1 => Just(KeyEvent::Space),
        1 => Just(KeyEvent::Backspace),
        1 => Just(KeyEvent::Enter),
        1 => Just(KeyEvent::Commit),
    ]
}

proptest! {
    /// Any key sequence is handled without panic; a Commit always leaves nothing composing,
    /// and the default candidate always equals the composing text.
    #[test]
    fn arbitrary_keys_are_safe(keys in proptest::collection::vec(key_strategy(), 0..120)) {
        let e = engine_with("am", AM_PROFILE);
        for k in keys {
            let u = e.on_key(k);
            if let Some(c) = u.candidates.first() {
                prop_assert_eq!(&c.text, &u.composing);
            }
        }
        let u = e.on_key(KeyEvent::Commit);
        prop_assert!(u.composing.is_empty());
        prop_assert!(u.candidates.is_empty());
    }

    /// Typing a word and deleting it syllable by syllable ends with nothing composing and
    /// never commits text; once the buffer is empty the host gets the next Backspace.
    #[test]
    fn backspacing_a_word_undoes_it(word in "[a-wyz]{1,20}") {
        let e = engine_with("am", AM_PROFILE);
        for c in word.chars() {
            e.on_key(KeyEvent::Char { text: c.to_string() });
        }
        for _ in 0..word.len() {
            let u = e.on_key(KeyEvent::Backspace);
            prop_assert_eq!(u.commit, "");
            if u.composing.is_empty() {
                break;
            }
            prop_assert_eq!(u.delete_before, 0);
        }
        // One press can erase a multi-key syllable, so |word| presses are always enough.
        prop_assert_eq!(e.on_key(KeyEvent::Backspace).delete_before, 1);
        prop_assert_eq!(e.on_key(KeyEvent::Commit), EditorUpdate::default());
    }
}
