//! Phonetic coverage (C1.4): every Fidel form Amharic and Tigrinya need is reachable from Latin
//! keys, end to end through a signed test pack and `KeyboardEngine` (no dictionary, so the strip
//! is the profile's own alternatives, capped at `max_candidates` like on a device).
//!
//! Two independent checks, so a profile edit cannot silently drop a letter:
//! - **Profile-driven:** every consonant rule typed alone gives its series' sixth order, followed
//!   by each vowel rule gives that order, and with the labial marker gives the labialised form.
//! - **Checked-in matrix** (`golden/<lang>-phonetic-coverage.json`, mirrored in
//!   `docs/specs/phonetic-coverage-am-ti.md`): the required forms (series x orders 1-7, the "wa"
//!   forms and the labiovelar series) each list the sequences that give them as the default
//!   composition and/or as a strip alternative; every listed sequence is typed and checked, and
//!   every required form must have at least one.
//!
//! Synthetic input only (N2); the profiles and the matrix are DRAFT until the G0 panel signs off.

mod common;

use common::{active, engine, fields, pack};
use qal_keyboard_core::composer::syllable;
use qal_keyboard_core::{
    EditorUpdate, InputMode, KeyEvent, KeyboardEngine, PackPlatform, ReleaseChannel, ResourceType,
};
use serde_json::Value;
use std::collections::BTreeSet;
use std::sync::Arc;

const AM_PROFILE: &str = include_str!("../../language-data/profiles/am-phonetic-draft.json");
const TI_PROFILE: &str = include_str!("../../language-data/profiles/ti-phonetic-draft.json");
const AM_COVERAGE: &str = include_str!("golden/am-phonetic-coverage.json");
const TI_COVERAGE: &str = include_str!("golden/ti-phonetic-coverage.json");

fn engine_with(lang: &str, profile: &str) -> Arc<KeyboardEngine> {
    let mut f = fields("0.1.0");
    f.language_code = lang.to_string();
    f.resource_type = ResourceType::TransliterationRules;
    f.platform = PackPlatform::Shared;
    f.release_channel = ReleaseChannel::Stable;
    let e = engine();
    e.load_pack(pack(&active(), &f, profile.as_bytes()))
        .unwrap();
    e.set_input_mode(lang.into(), InputMode::PhoneticLatin)
        .unwrap();
    e
}

/// Types `keys` into a fresh composition and returns the last update.
fn typed(e: &KeyboardEngine, keys: &str) -> EditorUpdate {
    e.reset();
    let mut last = EditorUpdate::default();
    for c in keys.chars() {
        last = e.on_key(KeyEvent::Char {
            text: c.to_string(),
        });
    }
    last
}

fn alternatives(u: &EditorUpdate) -> Vec<String> {
    u.candidates
        .iter()
        .skip(1)
        .map(|c| c.text.clone())
        .collect()
}

fn strs(v: &Value) -> Vec<String> {
    v.as_array()
        .map(|a| a.iter().map(|x| x.as_str().unwrap().to_string()).collect())
        .unwrap_or_default()
}

fn form(series: &str, order: u8) -> String {
    syllable(series, order)
        .unwrap_or_else(|| panic!("{series} has no order {order}"))
        .to_string()
}

/// Every consonant rule alone, with each vowel, and with the labial marker.
fn profile_driven(lang: &str, profile: &str) {
    let e = engine_with(lang, profile);
    let p: Value = serde_json::from_str(profile).unwrap();
    let marker = p["labialMarker"].as_str().unwrap_or("");
    let labialized = p["labializedSeries"]
        .as_object()
        .cloned()
        .unwrap_or_default();
    let vowels: Vec<(String, u8)> = p["vowels"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| {
            let order = u8::try_from(v["order"].as_u64().unwrap()).unwrap();
            (v["in"].as_str().unwrap().to_string(), order)
        })
        .collect();
    for c in p["consonants"].as_array().unwrap() {
        let input = c["in"].as_str().unwrap();
        let series = c["series"].as_str().unwrap();
        let u = typed(&e, input);
        assert_eq!(u.composing, form(series, 6), "{lang}: {input} alone");
        for (v, order) in &vowels {
            let seq = format!("{input}{v}");
            if let Some(g) = syllable(series, *order) {
                assert_eq!(typed(&e, &seq).composing, g.to_string(), "{lang}: {seq}");
            }
            if marker.is_empty() {
                continue;
            }
            // Labialised: the labiovelar series' form, or the series' own "wa" for the 4th order.
            let lab = labialized
                .get(series)
                .and_then(|l| syllable(l.as_str().unwrap(), *order))
                .or_else(|| (*order == 4).then(|| syllable(series, 8)).flatten());
            if let Some(g) = lab {
                let seq = format!("{input}{marker}{v}");
                assert_eq!(typed(&e, &seq).composing, g.to_string(), "{lang}: {seq}");
            }
        }
    }
}

/// The checked-in matrix: required forms, each with sequences that reach it.
fn matrix(lang: &str, profile: &str, coverage: &str) -> (usize, usize) {
    let e = engine_with(lang, profile);
    let p: Value = serde_json::from_str(profile).unwrap();
    let cov: Value = serde_json::from_str(coverage).unwrap();
    assert_eq!(cov["language"], lang);
    assert_eq!(
        cov["status"], "DRAFT",
        "the matrix of a DRAFT profile is DRAFT"
    );
    assert_eq!(cov["profile"], p["profileId"]);
    assert_eq!(
        cov["profile_version"], p["profileVersion"],
        "the coverage matrix pins one profile version"
    );

    // Required forms from the series ids and orders, never typed glyphs.
    let req = &cov["required"];
    let mut required: Vec<String> = Vec::new();
    for s in strs(&req["series"]) {
        for o in 1..=7 {
            required.push(form(&s, o));
        }
    }
    for s in strs(&req["wa"]) {
        required.push(form(&s, 8));
    }
    for s in strs(&req["labiovelar"]) {
        for o in [1, 3, 4, 5, 6] {
            required.push(form(&s, o));
        }
    }
    let unique: BTreeSet<&String> = required.iter().collect();
    assert_eq!(
        unique.len(),
        required.len(),
        "{lang}: required forms repeat"
    );

    let forms = cov["forms"].as_array().unwrap();
    let mut listed = BTreeSet::new();
    let (mut by_default, mut by_alternative) = (0, 0);
    for f in forms {
        let glyph = f["form"].as_str().unwrap().to_string();
        let series = f["series"].as_str().unwrap();
        let order = u8::try_from(f["order"].as_u64().unwrap()).unwrap();
        assert_eq!(
            glyph,
            form(series, order),
            "{lang}: {glyph} is {series}/{order}"
        );
        assert!(listed.insert(glyph.clone()), "{lang}: {glyph} listed twice");
        let defaults = strs(&f["default"]);
        let alts = strs(&f["alternative"]);
        assert!(
            !defaults.is_empty() || !alts.is_empty(),
            "{lang}: {glyph} is unreachable"
        );
        for seq in &defaults {
            assert_eq!(
                typed(&e, seq).composing,
                glyph,
                "{lang}: {seq} gives {glyph}"
            );
        }
        for seq in &alts {
            let u = typed(&e, seq);
            assert!(
                alternatives(&u).contains(&glyph),
                "{lang}: {seq} offers {glyph} (strip: {:?})",
                u.candidates.iter().map(|c| &c.text).collect::<Vec<_>>()
            );
        }
        if defaults.is_empty() {
            by_alternative += 1;
        } else {
            by_default += 1;
        }
    }
    for r in &required {
        assert!(
            listed.contains(r),
            "{lang}: required form {r} is not in the matrix"
        );
    }
    let summary = &cov["summary"];
    assert_eq!(summary["required"].as_u64(), Some(required.len() as u64));
    assert_eq!(summary["default"].as_u64(), Some(by_default as u64));
    assert_eq!(
        summary["alternative_only"].as_u64(),
        Some(by_alternative as u64)
    );
    (by_default, by_alternative)
}

#[test]
fn am_every_rule_reaches_its_forms() {
    profile_driven("am", AM_PROFILE);
}

#[test]
fn ti_every_rule_reaches_its_forms() {
    profile_driven("ti", TI_PROFILE);
}

#[test]
fn am_matrix_is_reachable_end_to_end() {
    matrix("am", AM_PROFILE, AM_COVERAGE);
}

#[test]
fn ti_matrix_is_reachable_end_to_end() {
    matrix("ti", TI_PROFILE, TI_COVERAGE);
}
