//! Direct Fidel coverage: every Fidel form Amharic and Tigrinya need (the same required forms as
//! the phonetic coverage matrices, `golden/<lang>-phonetic-coverage.json`) is reachable on the
//! language's own Fidel grid, by the two-key entry (consonant key, then vowel-order key) or the
//! long-press popup, end to end through a signed test pack and `KeyboardEngine`. Also pins what
//! makes the Tigrinya grid its own: the ቐ key with its labialised forms, Ethiopic punctuation
//! and numerals. Uses TEST signing keys only.

mod common;

use common::{active, engine, fields, pack};
use qal_keyboard_core::composer::syllable;
use qal_keyboard_core::{
    FidelLayout, InputMode, KeyEvent, KeyboardEngine, PackPlatform, ReleaseChannel, ResourceType,
};
use serde_json::Value;
use std::collections::BTreeSet;
use std::sync::Arc;

const AM_LAYOUT: &str = include_str!("../../language-data/layouts/am-fidel-draft.json");
const TI_LAYOUT: &str = include_str!("../../language-data/layouts/ti-fidel-draft.json");
const AM_COVERAGE: &str = include_str!("golden/am-phonetic-coverage.json");
const TI_COVERAGE: &str = include_str!("golden/ti-phonetic-coverage.json");

fn engine_with(lang: &str, layout: &str) -> Arc<KeyboardEngine> {
    let mut f = fields("0.1.0");
    f.language_code = lang.to_string();
    f.resource_type = ResourceType::KeyboardLayout;
    f.platform = PackPlatform::Shared;
    f.release_channel = ReleaseChannel::Stable;
    let e = engine();
    e.load_pack(pack(&active(), &f, layout.as_bytes())).unwrap();
    e.set_input_mode(lang.into(), InputMode::DirectFidel)
        .unwrap();
    e
}

/// The required forms of a coverage matrix: series x orders 1-7, the "wa" forms, and orders
/// 1/3/4/5/6 of the labiovelar series.
fn required(coverage: &str) -> Vec<String> {
    let cov: Value = serde_json::from_str(coverage).unwrap();
    let req = &cov["required"];
    let ids = |k: &str| -> Vec<String> {
        req[k]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| v.as_str().unwrap().to_string())
            .collect()
    };
    let form = |s: &str, o: u8| syllable(s, o).unwrap().to_string();
    let mut out = Vec::new();
    for s in ids("series") {
        out.extend((1..=7).map(|o| form(&s, o)));
    }
    for s in ids("wa") {
        out.push(form(&s, 8));
    }
    for s in ids("labiovelar") {
        out.extend([1, 3, 4, 5, 6].map(|o| form(&s, o)));
    }
    out
}

/// Everything the grid can type: each consonant key followed by each vowel-order key, plus
/// every long-press popup form.
fn reachable(e: &KeyboardEngine, layout: &FidelLayout) -> (BTreeSet<String>, BTreeSet<String>) {
    let mut two_key = BTreeSet::new();
    let mut popup = BTreeSet::new();
    for k in layout.rows.iter().flatten() {
        for v in &layout.vowel_keys {
            e.reset();
            e.on_key(KeyEvent::FidelConsonant {
                series_id: k.series_id.clone(),
            });
            let u = e.on_key(KeyEvent::FidelVowel { order: v.order });
            if !u.commit.is_empty() {
                two_key.insert(u.commit);
            }
        }
        popup.extend(k.forms.iter().map(|f| f.text.clone()));
    }
    e.reset();
    (two_key, popup)
}

fn check(lang: &str, layout: &str, coverage: &str) {
    let e = engine_with(lang, layout);
    let l = e.fidel_layout(lang.into()).unwrap();
    assert_eq!(
        l.status, "DRAFT",
        "{lang}: unreviewed layouts stay DRAFT (N9)"
    );
    let (two_key, popup) = reachable(&e, &l);
    let missing: Vec<String> = required(coverage)
        .into_iter()
        .filter(|f| !two_key.contains(f) && !popup.contains(f))
        .collect();
    assert!(
        missing.is_empty(),
        "{lang}: not on the Fidel grid: {missing:?}"
    );
    // The forms the phonetic audit found hard to reach are typed with two keys, not hidden in a popup.
    for f in ["ዓ", "ኧ", "ኋ"] {
        assert!(two_key.contains(f), "{lang}: {f} by two keys");
    }
    // Ethiopic punctuation and numerals come with the grid.
    for m in ["።", "፣", "፤", "፥", "፦", "፧", "፨", "፡"] {
        assert!(l.punctuation.iter().any(|p| p == m), "{lang}: {m}");
    }
    assert_eq!(
        l.digits.len(),
        10,
        "{lang}: Ethiopic numerals for every digit"
    );
}

#[test]
fn amharic_grid_reaches_every_required_form() {
    check("am", AM_LAYOUT, AM_COVERAGE);
}

#[test]
fn tigrinya_grid_reaches_every_required_form() {
    check("ti", TI_LAYOUT, TI_COVERAGE);
}

#[test]
fn tigrinya_has_its_own_grid() {
    let e = engine_with("ti", TI_LAYOUT);
    let l = e.fidel_layout("ti".into()).unwrap();
    assert_eq!(
        (l.language_code.as_str(), l.layout_id.as_str()),
        ("ti", "ti-fidel")
    );
    let keys: Vec<&str> = l
        .rows
        .iter()
        .flatten()
        .map(|k| k.series_id.as_str())
        .collect();
    assert!(keys.contains(&"QH"), "the ቐ key");
    let (two_key, popup) = reachable(&e, &l);
    // ቐ in all orders and its labialised forms ቘ ቚ ቛ ቜ ቝ; ኸ and ዀ ዂ ዃ ዄ ዅ; ጸ and ፀ; ዐ and አ.
    for f in "ቐቑቒቓቔቕቖቘቚቛቜቝኸኹኺኻኼኽኾዀዂዃዄዅጸፀዐዓአኣኧኈኊኋኌኍ".chars()
    {
        let f = f.to_string();
        assert!(two_key.contains(&f) || popup.contains(&f), "ti: {f}");
    }
    // The Amharic grid is a different layout (no ቐ key), so a language mix-up shows at once.
    let am = engine_with("am", AM_LAYOUT)
        .fidel_layout("am".into())
        .unwrap();
    assert_eq!(am.layout_id, "am-fidel");
    assert!(!am.rows.iter().flatten().any(|k| k.series_id == "QH"));
}
