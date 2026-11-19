//! Arabic typing (ADR 0034): the `Arabic` input mode is a sanitised NFC passthrough for `ar` only.
//! Synthetic text only (N2).

use qal_keyboard_core::{CoreError, EngineConfig, InputMode, KeyEvent, KeyboardEngine};
use std::sync::Arc;

fn engine() -> Arc<KeyboardEngine> {
    KeyboardEngine::new(EngineConfig {
        max_candidates: 5,
        trusted_keys: vec![],
        pack_limits: None,
    })
    .unwrap()
}

fn arabic() -> Arc<KeyboardEngine> {
    let e = engine();
    e.set_input_mode("ar".into(), InputMode::Arabic).unwrap();
    e
}

fn ch(s: &str) -> KeyEvent {
    KeyEvent::Char { text: s.into() }
}

/// Types each key and returns the concatenated commits; nothing may compose, delete or suggest.
fn type_keys(e: &Arc<KeyboardEngine>, keys: &[KeyEvent]) -> String {
    let mut out = String::new();
    for k in keys {
        let u = e.on_key(k.clone());
        assert!(u.composing.is_empty() && u.candidates.is_empty() && u.delete_before == 0);
        out.push_str(&u.commit);
    }
    out
}

#[test]
fn arabic_mode_is_only_for_ar() {
    let e = engine();
    for lang in ["am", "ti", "om", "en"] {
        assert!(
            matches!(
                e.set_input_mode(lang.into(), InputMode::Arabic),
                Err(CoreError::InvalidState { .. })
            ),
            "{lang} must not take the Arabic mode"
        );
    }
    for mode in [
        InputMode::English,
        InputMode::Qubee,
        InputMode::DirectFidel,
        InputMode::PhoneticLatin,
    ] {
        assert!(
            matches!(
                e.set_input_mode("ar".into(), mode),
                Err(CoreError::InvalidState { .. })
            ),
            "ar must not take {mode:?}"
        );
    }
    // Latin passthrough stays available for every language, ar included.
    e.set_input_mode("ar".into(), InputMode::Abc).unwrap();
    e.set_input_mode("ar".into(), InputMode::Arabic).unwrap();
}

#[test]
fn letters_words_and_punctuation_commit_in_logical_order() {
    let e = arabic();
    // "مرحبا بالعالم، كيف حالك؟" key by key: logical (typing) order, never reordered.
    let mut keys: Vec<KeyEvent> = Vec::new();
    for c in "مرحبا بالعالم، كيف حالك؟".chars() {
        keys.push(if c == ' ' {
            KeyEvent::Space
        } else {
            ch(&c.to_string())
        });
    }
    keys.push(KeyEvent::Enter);
    assert_eq!(type_keys(&e, &keys), "مرحبا بالعالم، كيف حالك؟\n");
}

#[test]
fn lam_alef_key_commits_two_letters() {
    let e = arabic();
    let u = e.on_key(ch("لا"));
    assert_eq!(u.commit, "\u{0644}\u{0627}");
    assert_eq!(u.commit.chars().count(), 2);
}

#[test]
fn harakat_and_tatweel_commit_alone() {
    let e = arabic();
    for mark in [
        "\u{064B}", "\u{064C}", "\u{064D}", "\u{064E}", "\u{064F}", "\u{0650}", "\u{0651}",
        "\u{0652}", "\u{0670}", "\u{0640}",
    ] {
        assert_eq!(e.on_key(ch(mark)).commit, mark);
    }
    // Letter then marks, as separate keys: each is committed as typed (no reordering across keys).
    assert_eq!(
        type_keys(&e, &[ch("ب"), ch("\u{0651}"), ch("\u{064E}")]),
        "ب\u{0651}\u{064E}"
    );
}

#[test]
fn committed_text_is_nfc() {
    let e = arabic();
    // Alef + combining hamza above / below / madda compose to the precomposed letters.
    assert_eq!(e.on_key(ch("\u{0627}\u{0654}")).commit, "\u{0623}");
    assert_eq!(e.on_key(ch("\u{0627}\u{0655}")).commit, "\u{0625}");
    assert_eq!(e.on_key(ch("\u{0627}\u{0653}")).commit, "\u{0622}");
    assert_eq!(e.on_key(ch("\u{0648}\u{0654}")).commit, "\u{0624}");
    assert_eq!(e.on_key(ch("\u{064A}\u{0654}")).commit, "\u{0626}");
    // Precomposed popup choices pass unchanged; Arabic-Indic digits and punctuation too.
    for s in [
        "أ", "إ", "آ", "ٱ", "ء", "ئ", "ى", "ؤ", "ة", "٠", "٩", "،", "؛", "؟", "٪",
    ] {
        assert_eq!(e.on_key(ch(s)).commit, s);
    }
}

#[test]
fn control_characters_and_oversized_text_are_not_committed() {
    let e = arabic();
    assert_eq!(e.on_key(ch("ب\u{0000}")).commit, "");
    assert_eq!(e.on_key(ch("\u{001B}")).commit, "");
    assert_eq!(e.on_key(ch(&"ب".repeat(65))).commit, "");
    assert_eq!(e.on_key(ch("")).commit, "");
    // Tab and newline are allowed inside a Char event, as in the other script modes.
    assert_eq!(e.on_key(ch("\t")).commit, "\t");
}

#[test]
fn backspace_deletes_one_cluster_and_nothing_composes() {
    let e = arabic();
    e.on_key(ch("س"));
    let u = e.on_key(KeyEvent::Backspace);
    assert_eq!(u.delete_before, 1);
    assert!(u.commit.is_empty() && u.composing.is_empty());
    assert_eq!(e.on_key(KeyEvent::Commit), Default::default());
    // Two-key Fidel events mean nothing here.
    assert_eq!(
        e.on_key(KeyEvent::FidelConsonant {
            series_id: "h".into()
        }),
        Default::default()
    );
    assert_eq!(
        e.on_key(KeyEvent::FidelVowel { order: 1 }),
        Default::default()
    );
    assert_eq!(e.select_candidate(0), Default::default());
}

#[test]
fn ar_has_no_packs_but_learns_the_users_own_words() {
    let e = engine();
    // ar is a supported language (no UnsupportedLanguage), it just has no layout or profile pack.
    assert!(matches!(
        e.fidel_layout("ar".into()),
        Err(CoreError::InvalidState { .. })
    ));
    assert!(matches!(
        e.translit_profile("ar".into()),
        Err(CoreError::InvalidState { .. })
    ));
    // An empty overlay import creates nothing: Arabic stays plain passthrough.
    e.import_user_data("ar".into(), vec![], vec![]).unwrap();
    e.set_input_mode("ar".into(), InputMode::Arabic).unwrap();
    assert_eq!(e.on_key(ch("ك")).commit, "ك");
    // No Arabic dictionary is licensed (N10), but the user's own words are (ADR 0039): learning
    // creates a user-only model, and Arabic words compose with the learned words as suggestions.
    assert!(e.learn_user_word("ar".into(), "كتاب".into(), None));
    assert!(e.add_user_word("ar".into(), "مدرسة".into()));
    e.set_input_mode("ar".into(), InputMode::Arabic).unwrap();
    e.on_key(ch("ك"));
    let u = e.on_key(ch("ت"));
    assert_eq!(u.composing, "كت");
    assert_eq!(u.candidates[0].text, "كت");
    assert!(u.candidates.iter().any(|c| c.text == "كتاب"));
    // Never autocorrected, even with autocorrection on.
    assert_eq!(u.autocorrect_index, None);
    let i = u.candidates.iter().position(|c| c.text == "كتاب").unwrap();
    let picked = e.select_candidate(u32::try_from(i).unwrap());
    assert_eq!(picked.commit, "كتاب ");
    // Lam-alef (one key, two letters) composes as part of the word.
    let u = e.on_key(ch("لا"));
    assert_eq!(u.composing, "لا");
}

#[test]
fn switching_away_from_arabic_restores_plain_passthrough() {
    let e = arabic();
    e.set_input_mode("en".into(), InputMode::English).unwrap();
    // English stays exact (not normalised): a decomposed é is committed as received.
    assert_eq!(e.on_key(ch("e\u{0301}")).commit, "e\u{0301}");
    e.set_input_mode("ar".into(), InputMode::Arabic).unwrap();
    assert_eq!(e.on_key(ch("e\u{0301}")).commit, "\u{00E9}");
}
