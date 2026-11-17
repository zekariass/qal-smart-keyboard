//! Direct Fidel input (C1.3): layout packs, mode switching, key handling and golden vectors.
//! Uses TEST signing keys only.

mod common;

use common::{active, engine, fields, pack, reason};
use qal_keyboard_core::composer::{syllable, Layout};
use qal_keyboard_core::{
    CoreError, EditorUpdate, InputMode, KeyEvent, KeyboardEngine, PackPlatform, PackRejectReason,
    ReleaseChannel, ResourceType,
};
use serde_json::{json, Value};
use std::sync::Arc;

const AM_LAYOUT: &str = include_str!("../../language-data/layouts/am-fidel-draft.json");
const TI_LAYOUT: &str = include_str!("../../language-data/layouts/ti-fidel-draft.json");
const GOLDEN: &str = include_str!("golden/fidel-direct-0.1.0.json");
const TABLE_JSON: &str = include_str!("../../language-data/generated/fidel-table-v1.json");

fn layout_pack(lang: &str, version: &str, artifact: &[u8]) -> Vec<u8> {
    let mut f = fields(version);
    f.language_code = lang.to_string();
    f.resource_type = ResourceType::KeyboardLayout;
    f.platform = PackPlatform::Shared;
    f.release_channel = ReleaseChannel::Stable;
    pack(&active(), &f, artifact)
}

fn engine_with(lang: &str, layout: &str) -> Arc<KeyboardEngine> {
    let e = engine();
    e.load_pack(layout_pack(lang, "0.1.0", layout.as_bytes()))
        .unwrap();
    e.set_input_mode(lang.into(), InputMode::DirectFidel)
        .unwrap();
    e
}

fn parse_event(s: &str) -> KeyEvent {
    match s {
        "Backspace" => KeyEvent::Backspace,
        "Space" => KeyEvent::Space,
        "Enter" => KeyEvent::Enter,
        "Commit" => KeyEvent::Commit,
        _ => {
            let (kind, rest) = s.split_once(':').expect("event notation");
            match kind {
                "C" => KeyEvent::FidelConsonant {
                    series_id: rest.into(),
                },
                "V" => KeyEvent::FidelVowel {
                    order: rest.parse().unwrap(),
                },
                "T" => KeyEvent::Char { text: rest.into() },
                _ => panic!("bad event {s}"),
            }
        }
    }
}

#[test]
fn golden_vectors() {
    let g: Value = serde_json::from_str(GOLDEN).unwrap();
    let mut engines: std::collections::HashMap<&str, Arc<KeyboardEngine>> = Default::default();
    for (lang, src) in [("am", AM_LAYOUT), ("ti", TI_LAYOUT)] {
        engines.insert(lang, engine_with(lang, src));
    }
    let vectors = g["vectors"].as_array().unwrap();
    assert!(vectors.len() >= 10);
    for v in vectors {
        let name = v["name"].as_str().unwrap();
        let e = &engines[v["language"].as_str().unwrap()];
        e.reset();
        let keys = v["keystrokes"].as_array().unwrap();
        let steps = v["steps"].as_array().unwrap();
        assert_eq!(keys.len(), steps.len(), "{name}: one step per keystroke");
        let mut committed: Vec<char> = Vec::new();
        for (i, (k, step)) in keys.iter().zip(steps).enumerate() {
            let u = e.on_key(parse_event(k.as_str().unwrap()));
            assert_eq!(
                u.composing,
                step["composing"].as_str().unwrap(),
                "{name}#{i} composing"
            );
            assert_eq!(
                u.commit,
                step["commit"].as_str().unwrap(),
                "{name}#{i} commit"
            );
            assert_eq!(
                u64::from(u.delete_before),
                step["delete_before"].as_u64().unwrap_or(0),
                "{name}#{i} delete_before"
            );
            assert!(u.candidates.is_empty());
            for _ in 0..u.delete_before {
                committed.pop();
            }
            committed.extend(u.commit.chars());
        }
        let committed: String = committed.into_iter().collect();
        assert_eq!(
            committed,
            v["committed"].as_str().unwrap(),
            "{name} committed"
        );
    }
}

#[test]
fn table_json_agrees_with_compiled_table() {
    // Every table entry in the generated JSON resolves identically through the core lookup.
    let t: Value = serde_json::from_str(TABLE_JSON).unwrap();
    let numbers: std::collections::HashMap<&str, u64> = t["orders"]
        .as_array()
        .unwrap()
        .iter()
        .map(|o| (o["id"].as_str().unwrap(), o["number"].as_u64().unwrap()))
        .collect();
    let mut n = 0;
    for s in t["series"].as_array().unwrap() {
        for (order, cp) in s["forms"].as_object().unwrap() {
            let cp = u32::from_str_radix(&cp.as_str().unwrap()[2..], 16).unwrap();
            let got = syllable(
                s["id"].as_str().unwrap(),
                u8::try_from(numbers[order.as_str()]).unwrap(),
            );
            assert_eq!(got.map(u32::from), Some(cp));
            n += 1;
        }
    }
    assert!(n > 450, "table too small: {n}");
}

#[test]
fn source_layouts_are_valid() {
    for (lang, src) in [("am", AM_LAYOUT), ("ti", TI_LAYOUT)] {
        let l = Layout::parse(src.as_bytes()).unwrap();
        assert_eq!(l.language(), lang);
        let f = engine_with(lang, src).fidel_layout(lang.into()).unwrap();
        assert_eq!(f.status, "DRAFT");
        assert!(f.long_press && f.two_key);
        assert_eq!(f.default_order, 6);
        let keys: Vec<_> = f.rows.iter().flatten().collect();
        assert!(keys.len() >= 34);
        // Every key shows its seven main orders; the popup is in popupOrders order.
        for k in &keys {
            assert!(k.forms.len() >= 6, "{}", k.series_id);
            assert!(k.forms.windows(2).all(|w| w[0].order < w[1].order));
        }
        assert_eq!(f.punctuation.join(""), "።፣፤፥፦፧፨፡");
        assert_eq!(f.digits.len(), 10);
    }
    let has = |src: &str, series: &str| {
        Layout::parse(src.as_bytes())
            .unwrap()
            .to_ffi()
            .rows
            .iter()
            .flatten()
            .any(|k| k.series_id == series)
    };
    assert!(has(TI_LAYOUT, "QH") && has(TI_LAYOUT, "KX"));
    assert!(!has(AM_LAYOUT, "QH") && has(AM_LAYOUT, "KX"));
}

#[test]
fn fidel_layout_describes_keys_and_popups() {
    let e = engine_with("am", AM_LAYOUT);
    let f = e.fidel_layout("am".into()).unwrap();
    assert_eq!(f.language_code, "am");
    let g = f
        .rows
        .iter()
        .flatten()
        .find(|k| k.series_id == "G")
        .unwrap();
    assert_eq!(g.label, "ገ");
    assert_eq!(g.tap_text, "ግ");
    let texts: Vec<&str> = g.forms.iter().map(|x| x.text.as_str()).collect();
    // Seven orders (G has no eighth), then the labiovelar series forms (order + 10).
    assert_eq!(
        texts,
        ["ገ", "ጉ", "ጊ", "ጋ", "ጌ", "ግ", "ጎ", "ጐ", "ጒ", "ጓ", "ጔ", "ጕ"]
    );
    let orders: Vec<u8> = g.forms.iter().map(|x| x.order).collect();
    assert_eq!(orders, [1, 2, 3, 4, 5, 6, 7, 11, 13, 14, 15, 16]);
    let l = f
        .rows
        .iter()
        .flatten()
        .find(|k| k.series_id == "L")
        .unwrap();
    assert_eq!(l.forms.len(), 8);
    assert_eq!(l.forms[7].text, "ሏ");
    assert_eq!(f.vowel_keys.len(), 8);
    assert_eq!(f.digits[0].digit, "1");
    assert_eq!(f.digits[0].alternatives, ["፩", "፲"]);
}

#[test]
fn mode_needs_a_loaded_layout() {
    let e = engine();
    for lang in ["am", "ti"] {
        match e.set_input_mode(lang.into(), InputMode::DirectFidel) {
            Err(CoreError::InvalidState { reason }) => {
                assert!(reason.contains("no keyboard layout"), "{reason}");
                assert!(reason.contains(lang));
            }
            other => panic!("expected InvalidState, got {other:?}"),
        }
        assert!(matches!(
            e.fidel_layout(lang.into()),
            Err(CoreError::InvalidState { .. })
        ));
    }
    // Still in ABC; keystrokes are not swallowed.
    assert_eq!(e.on_key(KeyEvent::Char { text: "a".into() }).commit, "a");
    assert!(matches!(
        e.fidel_layout("xx".into()),
        Err(CoreError::UnsupportedLanguage { .. })
    ));
    // A layout for am does not enable ti, and en never fits DirectFidel.
    e.load_pack(layout_pack("am", "0.1.0", AM_LAYOUT.as_bytes()))
        .unwrap();
    e.set_input_mode("am".into(), InputMode::DirectFidel)
        .unwrap();
    assert!(e
        .set_input_mode("ti".into(), InputMode::DirectFidel)
        .is_err());
    assert!(e
        .set_input_mode("en".into(), InputMode::DirectFidel)
        .is_err());
    // Failed switches keep the current DirectFidel session.
    assert_eq!(
        e.on_key(KeyEvent::FidelConsonant {
            series_id: "S".into()
        })
        .composing,
        "ስ"
    );
}

#[test]
fn switching_modes_drops_the_composition_and_two_key_events_are_inert_in_abc() {
    let e = engine_with("am", AM_LAYOUT);
    e.on_key(KeyEvent::FidelConsonant {
        series_id: "S".into(),
    });
    e.set_input_mode("am".into(), InputMode::Abc).unwrap();
    assert_eq!(e.on_key(KeyEvent::Commit), EditorUpdate::default());
    assert_eq!(
        e.on_key(KeyEvent::FidelConsonant {
            series_id: "S".into()
        }),
        EditorUpdate::default()
    );
    assert_eq!(
        e.on_key(KeyEvent::FidelVowel { order: 1 }),
        EditorUpdate::default()
    );
    e.set_input_mode("am".into(), InputMode::DirectFidel)
        .unwrap();
    assert_eq!(e.on_key(KeyEvent::Commit), EditorUpdate::default());
}

#[test]
fn reset_clears_pending_composition() {
    let e = engine_with("am", AM_LAYOUT);
    e.on_key(KeyEvent::FidelConsonant {
        series_id: "S".into(),
    });
    e.reset();
    assert_eq!(e.on_key(KeyEvent::Space).commit, " ");
}

#[test]
fn layout_pack_is_listed_and_idempotent_and_rollback_protected() {
    let e = engine();
    let bytes = layout_pack("am", "0.2.0", AM_LAYOUT.as_bytes());
    let info = e.load_pack(bytes.clone()).unwrap();
    assert_eq!(info.resource_type, ResourceType::KeyboardLayout);
    assert_eq!(e.load_pack(bytes).unwrap(), info);
    let old = layout_pack("am", "0.1.0", AM_LAYOUT.as_bytes());
    assert_eq!(reason(e.load_pack(old)), PackRejectReason::Downgrade);
    assert_eq!(e.loaded_packs().len(), 1);
}

#[test]
fn newer_layout_replaces_the_older_one() {
    let e = engine();
    e.load_pack(layout_pack("am", "0.1.0", AM_LAYOUT.as_bytes()))
        .unwrap();
    let newer = AM_LAYOUT.replace(
        "\"layoutVersion\": \"0.1.0\"",
        "\"layoutVersion\": \"0.2.0\"",
    );
    assert_ne!(newer, AM_LAYOUT);
    e.load_pack(layout_pack("am", "0.2.0", newer.as_bytes()))
        .unwrap();
    assert_eq!(e.fidel_layout("am".into()).unwrap().layout_version, "0.2.0");
}

fn mutated(f: impl FnOnce(&mut Value)) -> Vec<u8> {
    let mut v: Value = serde_json::from_str(AM_LAYOUT).unwrap();
    f(&mut v);
    serde_json::to_vec(&v).unwrap()
}

fn all_keys() -> Vec<Value> {
    let v: Value = serde_json::from_str(AM_LAYOUT).unwrap();
    v["rows"]
        .as_array()
        .unwrap()
        .iter()
        .flat_map(|r| r.as_array().unwrap().clone())
        .collect()
}

#[test]
fn malformed_layouts_are_rejected() {
    let cases: Vec<(&str, Vec<u8>)> = vec![
        ("not json", b"nope".to_vec()),
        ("empty", vec![]),
        ("wrong format", mutated(|v| v["format"] = json!("other"))),
        ("version 2", mutated(|v| v["formatVersion"] = json!(2))),
        ("unknown field", mutated(|v| v["extra"] = json!(1))),
        (
            "missing rows",
            mutated(|v| {
                v.as_object_mut().unwrap().remove("rows");
            }),
        ),
        ("bad language", mutated(|v| v["language"] = json!("AM"))),
        ("bad id", mutated(|v| v["layoutId"] = json!("Bad Id"))),
        (
            "bad version",
            mutated(|v| v["layoutVersion"] = json!("one")),
        ),
        ("bad status", mutated(|v| v["status"] = json!("FINAL"))),
        (
            "unknown interaction",
            mutated(|v| v["interaction"] = json!(["SWIPE"])),
        ),
        ("no interaction", mutated(|v| v["interaction"] = json!([]))),
        (
            "dup interaction",
            mutated(|v| v["interaction"] = json!(["TWO_KEY", "TWO_KEY"])),
        ),
        (
            "two_key without vowel keys",
            mutated(|v| v["vowelKeys"] = json!([])),
        ),
        (
            "long press without popup",
            mutated(|v| v["popupOrders"] = json!([])),
        ),
        (
            "popup order 0",
            mutated(|v| v["popupOrders"] = json!([0, 1])),
        ),
        (
            "popup order dup",
            mutated(|v| v["popupOrders"] = json!([1, 1])),
        ),
        (
            "default order 10",
            mutated(|v| v["defaultOrder"] = json!(10)),
        ),
        (
            "vowel order dup",
            mutated(|v| {
                v["vowelKeys"] = json!([{"order": 1, "label": "ሀ"}, {"order": 1, "label": "ሁ"}])
            }),
        ),
        (
            "vowel order 10",
            mutated(|v| v["vowelKeys"] = json!([{"order": 10, "label": "ሀ"}])),
        ),
        ("empty rows", mutated(|v| v["rows"] = json!([]))),
        ("empty row", mutated(|v| v["rows"][1] = json!([]))),
        (
            "too many rows",
            mutated(|v| v["rows"] = json!(vec![json!([{"series": "S", "label": "ሰ"}]); 9])),
        ),
        (
            "unknown series",
            mutated(|v| v["rows"][0][0]["series"] = json!("NOPE")),
        ),
        (
            "unknown labialized",
            mutated(|v| v["rows"][1][0]["labialized"] = json!("NOPE")),
        ),
        (
            "self labialized",
            mutated(|v| v["rows"][1][1]["labialized"] = json!("Q")),
        ),
        (
            "duplicate key",
            mutated(|v| v["rows"][0][1]["series"] = json!("H")),
        ),
        (
            "empty label",
            mutated(|v| v["rows"][0][0]["label"] = json!("")),
        ),
        (
            "long label",
            mutated(|v| v["rows"][0][0]["label"] = json!("ሀሀሀሀሀ")),
        ),
        (
            "non-NFC label",
            mutated(|v| v["rows"][0][0]["label"] = json!("e\u{301}\u{323}")),
        ),
        (
            "control label",
            mutated(|v| v["rows"][0][0]["label"] = json!("\u{0}")),
        ),
        (
            "17 keys in a row",
            mutated(|v| v["rows"] = json!([all_keys()[..17].to_vec()])),
        ),
        (
            "latin punctuation",
            mutated(|v| v["punctuation"] = json!(["."])),
        ),
        (
            "multi-char punctuation",
            mutated(|v| v["punctuation"] = json!(["።።"])),
        ),
        (
            "dup punctuation",
            mutated(|v| v["punctuation"] = json!(["።", "።"])),
        ),
        (
            "digit not ascii",
            mutated(|v| v["digits"][0]["digit"] = json!("፩")),
        ),
        (
            "digit alt latin",
            mutated(|v| v["digits"][0]["alternatives"] = json!(["a"])),
        ),
        (
            "digit alt empty",
            mutated(|v| v["digits"][0]["alternatives"] = json!([])),
        ),
        (
            "digit dup",
            mutated(|v| v["digits"][1]["digit"] = json!("1")),
        ),
        ("oversize", {
            let mut b = AM_LAYOUT.as_bytes().to_vec();
            b.extend(std::iter::repeat_n(b' ', 70_000));
            b
        }),
    ];
    for (name, bytes) in &cases {
        assert!(
            Layout::parse(bytes).is_err(),
            "{name} should be rejected by parse"
        );
        let e = engine();
        assert_eq!(
            reason(e.load_pack(layout_pack("am", "0.1.0", bytes))),
            PackRejectReason::InvalidArtifact,
            "{name} should be rejected by load_pack"
        );
        assert!(
            e.loaded_packs().is_empty(),
            "{name}: nothing may be activated"
        );
        assert!(e
            .set_input_mode("am".into(), InputMode::DirectFidel)
            .is_err());
    }
}

#[test]
fn layout_language_must_match_the_manifest() {
    let e = engine();
    let r = e.load_pack(layout_pack("ti", "0.1.0", AM_LAYOUT.as_bytes()));
    assert_eq!(reason(r), PackRejectReason::InvalidArtifact);
    assert!(e.loaded_packs().is_empty());
}

#[test]
fn rejection_details_never_contain_layout_content() {
    let bad = mutated(|v| v["rows"][0][0]["series"] = json!("SECRETWORD"));
    match engine().load_pack(layout_pack("am", "0.1.0", &bad)) {
        Err(CoreError::PackRejected { detail, .. }) => {
            assert!(!detail.contains("SECRETWORD"), "{detail}");
        }
        other => panic!("unexpected {other:?}"),
    }
}

#[test]
fn non_layout_packs_do_not_enable_direct_fidel() {
    let e = engine();
    e.load_pack(common::valid_pack()).unwrap();
    assert!(e
        .set_input_mode("am".into(), InputMode::DirectFidel)
        .is_err());
}

#[test]
fn no_panic_on_odd_input_in_direct_mode() {
    let e = engine_with("am", AM_LAYOUT);
    let junk = [
        "",
        "\u{0}",
        "\u{301}",
        "\u{1200}\u{135D}",
        "\u{10FFFF}",
        "A\u{0}B",
        "ሰ\u{200D}",
    ];
    for t in junk {
        e.on_key(KeyEvent::Char { text: t.into() });
        e.on_key(KeyEvent::FidelConsonant {
            series_id: t.into(),
        });
    }
    for order in [0u8, 9, 10, 255] {
        e.on_key(KeyEvent::FidelVowel { order });
    }
    let long = "ሰ".repeat(100_000);
    assert_eq!(
        e.on_key(KeyEvent::Char { text: long }),
        EditorUpdate::default()
    );
    e.on_key(KeyEvent::FidelConsonant {
        series_id: "x".repeat(100_000),
    });
}
