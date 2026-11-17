//! Qubee, English and ABC passthrough (C1.5). Synthetic text only (N2).

mod common;

use common::{active, engine, fields, pack};
use qal_keyboard_core::latin::Orthography;
use qal_keyboard_core::{
    CoreError, InputMode, KeyEvent, KeyboardEngine, PackPlatform, ReleaseChannel, ResourceType,
};
use std::sync::Arc;

const AM_PROFILE: &str = include_str!("../../language-data/profiles/am-phonetic-draft.json");

fn ch(s: &str) -> KeyEvent {
    KeyEvent::Char { text: s.into() }
}

/// Types every character of `s` as a key; Space and Enter map to their events. Returns the
/// concatenated commits and asserts nothing is ever composed, deleted or suggested.
fn type_str(e: &Arc<KeyboardEngine>, s: &str) -> String {
    let mut out = String::new();
    for c in s.chars() {
        let ev = match c {
            ' ' => KeyEvent::Space,
            '\n' => KeyEvent::Enter,
            _ => ch(&c.to_string()),
        };
        let u = e.on_key(ev);
        assert!(u.composing.is_empty() && u.candidates.is_empty() && u.delete_before == 0);
        out.push_str(&u.commit);
    }
    out
}

fn mode_engine(lang: &str, mode: InputMode) -> Arc<KeyboardEngine> {
    let e = engine();
    e.set_input_mode(lang.into(), mode).unwrap();
    e
}

#[test]
fn passthrough_is_exact_in_every_latin_mode() {
    let samples = [
        "Dhugaa",
        "DHUGAA",
        "ka'a ka\u{2019}a du\u{02bc}a",
        "ba' ' 'ba",
        "hello World. It's 5 o'clock!\nNext",
        "caf\u{e9} \u{1200}",
        "  ",
        "",
    ];
    for (lang, mode) in [
        ("om", InputMode::Qubee),
        ("en", InputMode::English),
        ("om", InputMode::Abc),
        ("en", InputMode::Abc),
        ("am", InputMode::Abc),
        ("ti", InputMode::Abc),
    ] {
        let e = mode_engine(lang, mode);
        for s in samples {
            assert_eq!(type_str(&e, s), s, "{lang} {mode:?}");
        }
    }
}

#[test]
fn no_auto_capitalisation_after_sentence_end() {
    for (lang, mode) in [("en", InputMode::English), ("om", InputMode::Qubee)] {
        let e = mode_engine(lang, mode);
        assert_eq!(type_str(&e, "ok. next? yes\nfine"), "ok. next? yes\nfine");
    }
}

#[test]
fn qubee_digraphs_and_apostrophe_commit_unit_by_unit() {
    let e = mode_engine("om", InputMode::Qubee);
    for k in ["d", "h", "u", "g", "a", "a"] {
        assert_eq!(e.on_key(ch(k)).commit, k);
    }
    // The apostrophe key commits the typed character; it is not a closing quote.
    assert_eq!(e.on_key(ch("'")).commit, "'");
    assert_eq!(e.on_key(ch("\u{2019}")).commit, "\u{2019}");
    // A multi-letter event (e.g. a host long-press choice) is committed whole.
    assert_eq!(e.on_key(ch("dh")).commit, "dh");
    // Two-key Fidel events mean nothing here.
    assert_eq!(
        e.on_key(KeyEvent::FidelConsonant {
            series_id: "S".into()
        }),
        Default::default()
    );
    assert_eq!(
        e.on_key(KeyEvent::FidelVowel { order: 1 }),
        Default::default()
    );
    assert_eq!(e.on_key(KeyEvent::Commit), Default::default());
}

#[test]
fn qubee_backspace_deletes_one_grapheme_cluster() {
    let e = mode_engine("om", InputMode::Qubee);
    type_str(&e, "dh");
    // Plain (not digraph-aware) backspace: `dh` is two clusters, so two presses.
    for _ in 0..2 {
        let u = e.on_key(KeyEvent::Backspace);
        assert_eq!(u.delete_before, 1);
        assert!(u.commit.is_empty() && u.composing.is_empty());
    }
}

#[test]
fn qubee_segmentation_keeps_hudhaa_in_the_word() {
    let o = Orthography::builtin("om").unwrap();
    let typed = type_str(&mode_engine("om", InputMode::Qubee), "ka'a dhugaa 'sh'");
    assert_eq!(typed, "ka'a dhugaa 'sh'");
    assert_eq!(o.words(&typed), ["ka'a", "dhugaa", "sh"]);
    assert_eq!(o.units("dhugaa"), ["dh", "u", "g", "aa"]);
    assert_eq!(o.capitalize("dhugaa"), "Dhugaa");
}

#[test]
fn mode_validity_per_language() {
    let e = engine();
    for (lang, mode) in [
        ("om", InputMode::English),
        ("en", InputMode::Qubee),
        ("am", InputMode::Qubee),
        ("ti", InputMode::Qubee),
        ("om", InputMode::DirectFidel),
        ("om", InputMode::PhoneticLatin),
        ("en", InputMode::PhoneticLatin),
    ] {
        assert!(
            matches!(
                e.set_input_mode(lang.into(), mode),
                Err(CoreError::InvalidState { .. })
            ),
            "{lang} {mode:?}"
        );
    }
    assert!(matches!(
        e.set_input_mode("xx".into(), InputMode::Qubee),
        Err(CoreError::UnsupportedLanguage { .. })
    ));
}

#[test]
fn failed_mode_switch_keeps_previous_mode() {
    // Qubee stays Qubee (passthrough) after refused switches.
    let e = mode_engine("om", InputMode::Qubee);
    assert!(e.set_input_mode("om".into(), InputMode::English).is_err());
    assert!(e.set_input_mode("xx".into(), InputMode::Abc).is_err());
    assert!(e
        .set_input_mode("om".into(), InputMode::DirectFidel)
        .is_err());
    assert_eq!(type_str(&e, "dh'a"), "dh'a");

    // A running phonetic composition survives a refused switch to Qubee/English.
    let e = engine();
    let mut f = fields("0.1.0");
    f.language_code = "am".into();
    f.resource_type = ResourceType::TransliterationRules;
    f.platform = PackPlatform::Shared;
    f.release_channel = ReleaseChannel::Stable;
    e.load_pack(pack(&active(), &f, AM_PROFILE.as_bytes()))
        .unwrap();
    e.set_input_mode("am".into(), InputMode::PhoneticLatin)
        .unwrap();
    let composing = e.on_key(ch("s")).composing;
    assert!(!composing.is_empty());
    assert!(e.set_input_mode("am".into(), InputMode::Qubee).is_err());
    assert!(e
        .set_input_mode("en".into(), InputMode::PhoneticLatin)
        .is_err());
    assert_eq!(e.on_key(ch("e")).composing, "\u{1230}"); // still phonetic: s + e
}

#[test]
fn successful_switch_discards_composition_and_changes_behaviour() {
    let e = engine();
    let mut f = fields("0.1.0");
    f.language_code = "am".into();
    f.resource_type = ResourceType::TransliterationRules;
    f.platform = PackPlatform::Shared;
    f.release_channel = ReleaseChannel::Stable;
    e.load_pack(pack(&active(), &f, AM_PROFILE.as_bytes()))
        .unwrap();
    e.set_input_mode("am".into(), InputMode::PhoneticLatin)
        .unwrap();
    assert!(!e.on_key(ch("s")).composing.is_empty());
    e.set_input_mode("am".into(), InputMode::Abc).unwrap();
    let u = e.on_key(ch("s"));
    assert_eq!((u.commit.as_str(), u.composing.as_str()), ("s", ""));
    e.set_input_mode("om".into(), InputMode::Qubee).unwrap();
    assert_eq!(e.on_key(ch("s")).commit, "s");
    e.set_input_mode("en".into(), InputMode::English).unwrap();
    assert_eq!(e.on_key(ch("s")).commit, "s");
    assert_eq!(e.reset(), Default::default());
}

#[test]
fn select_candidate_is_a_noop_in_passthrough_modes() {
    let e = mode_engine("om", InputMode::Qubee);
    assert_eq!(e.select_candidate(0), Default::default());
}
