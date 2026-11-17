//! Pack verification and engine policy (plan step C1.2). Synthetic artifacts and test keys only.

mod common;

use common::*;
use qal_keyboard_core::pack::{self, builder, KeyRing, MAGIC};
use qal_keyboard_core::{
    CoreError, EngineConfig, KeyStatus, KeyboardEngine, PackLimits, PackPlatform,
    PackRejectReason as R, ReleaseChannel, ResourceType, TrustedKey,
};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

// ------------------------------------------------------------------------- happy path

#[test]
fn valid_pack_loads_with_full_info() {
    let e = engine();
    let info = e.load_pack(valid_pack()).unwrap();
    assert_eq!(info.pack_id, RESOURCE_ID);
    assert_eq!(info.language_code, "am");
    assert_eq!(info.resource_type, ResourceType::TransliterationRules);
    assert_eq!(info.version, "1.3.0");
    assert_eq!(info.platform, PackPlatform::Shared);
    assert_eq!(info.release_channel, ReleaseChannel::Stable);
    assert_eq!(info.size_bytes, ARTIFACT.len() as u64);
    assert_eq!(info.sha256, pack::to_hex(&Sha256::digest(ARTIFACT)));
    assert!(!info.is_delta);
    assert_eq!(info.key_id, "test-active-1");
    assert_eq!(info.min_app_version, None);
    assert_eq!(e.loaded_packs(), vec![info]);
}

#[test]
fn verified_pack_exposes_exact_artifact_and_manifest() {
    let a = active();
    let ring = KeyRing::new(&[a.trusted()]).unwrap();
    let manifest = fields("1.3.0").to_json(a.id, ARTIFACT).unwrap();
    let bytes = signed_raw(&a, &manifest, ARTIFACT);
    let v = pack::verify_pack(bytes, &ring, &PackLimits::default()).unwrap();
    assert_eq!(v.artifact(), ARTIFACT);
    assert_eq!(v.manifest_bytes(), manifest.as_slice());
}

#[test]
fn retiring_key_still_verifies() {
    let info = engine()
        .load_pack(pack(&retiring(), &fields("1.0.0"), ARTIFACT))
        .unwrap();
    assert_eq!(info.key_id, "test-retiring-1");
}

#[test]
fn optional_fields_and_unknown_members_are_accepted() {
    let a = active();
    let mut m = manifest_value(&a, ARTIFACT);
    m.insert("minAppVersion".into(), json!("1.4.0"));
    m.insert("version".into(), json!("2.0.0-beta.1"));
    m.insert(
        "artifactUrl".into(),
        json!("https://cdn.example.com/packs/am/translit/2.0.0-beta.1.pack"),
    );
    m.insert("deviceRequirements".into(), json!({ "minRamMb": 0 }));
    m.insert(
        "profile".into(),
        json!({ "code": "AM_PHONETIC_QWERTY_TO_ETHIOPIC", "normalization": "NFC" }),
    );
    m.insert("someFutureField".into(), json!([1, 2, 3]));
    let bytes = signed_raw(&a, &serde_json::to_vec(&m).unwrap(), ARTIFACT);
    let info = engine().load_pack(bytes).unwrap();
    assert_eq!(info.version, "2.0.0-beta.1");
    assert_eq!(info.min_app_version.as_deref(), Some("1.4.0"));
}

#[test]
fn every_resource_type_and_platform_is_accepted() {
    let types = [
        "KEYBOARD_LAYOUT",
        "TRANSLITERATION_RULES",
        "DICTIONARY",
        "FREQUENCY_LIST",
        "PREDICTION_MODEL",
        "AUTOCORRECTION_MODEL",
        "PROTECTED_TERMS",
        "PRONUNCIATION",
        "EMOJI_KEYWORDS",
        "GLOSSARY",
        "SPELL_MODEL",
        "LANGUAGE_ID_MODEL",
        "TRANSLATION_MODEL",
        "ASR_MODEL",
        "TTS_VOICE",
        "ON_DEVICE_LLM",
        "OTHER",
    ];
    let a = active();
    let e = engine();
    for t in types {
        for p in ["SHARED", "ANDROID", "IOS"] {
            // A KEYBOARD_LAYOUT artifact is validated as a layout after verification (C1.3).
            let artifact: &[u8] = if t == "KEYBOARD_LAYOUT" {
                include_bytes!("../../language-data/layouts/am-fidel-draft.json")
            } else {
                ARTIFACT
            };
            let mut m = manifest_value(&a, artifact);
            m.insert("resourceType".into(), json!(t));
            m.insert("platform".into(), json!(p));
            let info = e
                .load_pack(signed_raw(&a, &serde_json::to_vec(&m).unwrap(), artifact))
                .unwrap();
            assert_eq!(info.resource_type.as_wire(), t);
            assert_eq!(info.platform.as_wire(), p);
        }
    }
    assert_eq!(e.loaded_packs().len(), types.len() * 3);
}

#[test]
fn empty_artifact_is_allowed() {
    assert!(engine()
        .load_pack(pack(&active(), &fields("1.0.0"), b""))
        .is_ok());
}

// ------------------------------------------------------------------------- keys

#[test]
fn revoked_key_is_rejected_even_with_a_valid_signature() {
    let r = engine().load_pack(pack(&revoked(), &fields("1.0.0"), ARTIFACT));
    assert_eq!(reason(r), R::RevokedKey);
}

#[test]
fn unknown_key_id_is_rejected() {
    let r = engine().load_pack(pack(&untrusted(), &fields("1.0.0"), ARTIFACT));
    assert_eq!(reason(r), R::UnknownKey);
}

#[test]
fn engine_without_keys_rejects_everything() {
    let e = KeyboardEngine::new(config(&[], None)).unwrap();
    assert_eq!(reason(e.load_pack(valid_pack())), R::UnknownKey);
}

#[test]
fn wrong_key_under_a_trusted_key_id_is_rejected() {
    // Signed with the untrusted key but claiming the active key id.
    let a = active();
    let u = untrusted();
    let manifest = fields("1.0.0").to_json(a.id, ARTIFACT).unwrap();
    let sig = {
        use ed25519_dalek::Signer;
        u.sk.sign(&pack::signing_input(&manifest)).to_bytes()
    };
    let bytes = builder::assemble(a.id.as_bytes(), &sig, &manifest, ARTIFACT).unwrap();
    assert_eq!(reason(engine().load_pack(bytes)), R::BadSignature);
}

#[test]
fn signature_without_domain_separation_is_rejected() {
    let a = active();
    let manifest = fields("1.0.0").to_json(a.id, ARTIFACT).unwrap();
    let sig = {
        use ed25519_dalek::Signer;
        a.sk.sign(&manifest).to_bytes() // raw manifest, no context prefix
    };
    let bytes = builder::assemble(a.id.as_bytes(), &sig, &manifest, ARTIFACT).unwrap();
    assert_eq!(reason(engine().load_pack(bytes)), R::BadSignature);
}

#[test]
fn signature_over_a_different_manifest_is_rejected() {
    let a = active();
    let m1 = fields("1.0.0").to_json(a.id, ARTIFACT).unwrap();
    let m2 = fields("9.0.0").to_json(a.id, ARTIFACT).unwrap();
    let sig_for_m2: [u8; 64] = signed_raw(&a, &m2, ARTIFACT)
        [layout(a.id.len()).signature..layout(a.id.len()).signature + 64]
        .try_into()
        .unwrap();
    let bytes = builder::assemble(a.id.as_bytes(), &sig_for_m2, &m1, ARTIFACT).unwrap();
    assert_eq!(reason(engine().load_pack(bytes)), R::BadSignature);
}

#[test]
fn manifest_key_id_must_match_header_key_id() {
    // Validly signed by the active key, but the manifest names the retiring key.
    let a = active();
    let manifest = fields("1.0.0").to_json(retiring().id, ARTIFACT).unwrap();
    let bytes = signed_raw(&a, &manifest, ARTIFACT);
    assert_eq!(reason(engine().load_pack(bytes)), R::KeyMismatch);
}

#[test]
fn malleable_signature_s_plus_l_is_rejected() {
    // Group order l, little-endian.
    const L: [u8; 32] = [
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde,
        0x14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
    ];
    let mut bytes = valid_pack();
    let s_at = layout(active().id.len()).signature + 32;
    let mut carry = 0u16;
    for i in 0..32 {
        let sum = u16::from(bytes[s_at + i]) + u16::from(L[i]) + carry;
        bytes[s_at + i] = sum as u8;
        carry = sum >> 8;
    }
    assert_eq!(carry, 0, "s + l must still fit in 32 bytes");
    assert_eq!(reason(engine().load_pack(bytes)), R::BadSignature);
}

#[test]
fn small_order_r_signature_is_rejected() {
    let a = active();
    let manifest = fields("1.0.0").to_json(a.id, ARTIFACT).unwrap();
    let mut sig = [0u8; 64];
    sig[0] = 1; // R = identity point (small order), s = 0
    let bytes = builder::assemble(a.id.as_bytes(), &sig, &manifest, ARTIFACT).unwrap();
    assert_eq!(reason(engine().load_pack(bytes)), R::BadSignature);
}

fn config_error(keys: Vec<TrustedKey>, limits: Option<PackLimits>) -> bool {
    matches!(
        KeyboardEngine::new(EngineConfig {
            max_candidates: 5,
            trusted_keys: keys,
            pack_limits: limits,
        }),
        Err(CoreError::InvalidConfig { .. })
    )
}

#[test]
fn invalid_trusted_keys_fail_engine_construction() {
    let a = active();
    let key = |id: &str, pk: Vec<u8>| TrustedKey {
        key_id: id.to_string(),
        public_key: pk,
        status: KeyStatus::Active,
    };
    let pk = a.public().to_vec();
    assert!(config_error(vec![key("k1", pk[..31].to_vec())], None));
    assert!(config_error(
        vec![key("k1", [pk.clone(), vec![0]].concat())],
        None
    ));
    assert!(config_error(vec![key("k1", vec![])], None));
    assert!(config_error(vec![key("", pk.clone())], None));
    assert!(config_error(vec![key("bad id", pk.clone())], None));
    assert!(config_error(vec![key(&"k".repeat(121), pk.clone())], None));
    // Identity point: valid encoding but small order.
    let mut identity = vec![0u8; 32];
    identity[0] = 1;
    assert!(config_error(vec![key("k1", identity)], None));
    // An encoding that is not a curve point.
    let not_a_point = (2u8..=255)
        .map(|b| {
            let mut k = [0u8; 32];
            k[0] = b;
            k
        })
        .find(|k| ed25519_dalek::VerifyingKey::from_bytes(k).is_err())
        .expect("some y has no x");
    assert!(config_error(vec![key("k1", not_a_point.to_vec())], None));
    // Duplicate id, and the same key under two ids (a revocation must not be bypassable).
    let b = retiring().public().to_vec();
    assert!(config_error(
        vec![key("k1", pk.clone()), key("k1", b.clone())],
        None
    ));
    let mut dup = key("k2", pk.clone());
    dup.status = KeyStatus::Revoked;
    assert!(config_error(vec![key("k1", pk.clone()), dup], None));
    // Valid.
    assert!(!config_error(vec![key("k1", pk), key("k2", b)], None));
}

#[test]
fn invalid_limits_fail_engine_construction() {
    let d = PackLimits::default();
    assert!(config_error(
        vec![],
        Some(PackLimits {
            max_artifact_bytes: 0,
            ..d
        })
    ));
    assert!(config_error(
        vec![],
        Some(PackLimits {
            max_manifest_bytes: 2 * 1024 * 1024,
            ..d
        })
    ));
    assert!(!config_error(vec![], Some(d)));
}

// ------------------------------------------------------------------------- integrity

#[test]
fn tampered_artifact_is_rejected() {
    let mut bytes = valid_pack();
    *bytes.last_mut().unwrap() ^= 0x01;
    assert_eq!(reason(engine().load_pack(bytes)), R::ChecksumMismatch);
}

#[test]
fn artifact_swap_between_two_signed_packs_is_rejected() {
    let a = active();
    let art1 = b"artifact one, same length".as_slice();
    let art2 = b"artifact two, same length".as_slice();
    let art3 = b"a longer third artifact payload".as_slice();
    let m1 = fields("1.0.0").to_json(a.id, art1).unwrap();
    let p1 = signed_raw(&a, &m1, art1);
    let sig1: [u8; 64] = p1[layout(a.id.len()).signature..][..64].try_into().unwrap();
    // Manifest + signature of pack 1 with the artifact of pack 2 (same size, other hash).
    let swapped = builder::assemble(a.id.as_bytes(), &sig1, &m1, art2).unwrap();
    assert_eq!(reason(engine().load_pack(swapped)), R::ChecksumMismatch);
    // ... and with an artifact of another size.
    let swapped = builder::assemble(a.id.as_bytes(), &sig1, &m1, art3).unwrap();
    assert_eq!(reason(engine().load_pack(swapped)), R::SizeMismatch);
    // The genuine pack 2 still loads.
    assert!(engine().load_pack(pack(&a, &fields("1.0.0"), art2)).is_ok());
}

#[test]
fn signed_size_mismatch_is_rejected() {
    let a = active();
    let mut m = manifest_value(&a, ARTIFACT);
    m.insert("sizeBytes".into(), json!(ARTIFACT.len() + 1));
    let bytes = signed_raw(&a, &serde_json::to_vec(&m).unwrap(), ARTIFACT);
    assert_eq!(reason(engine().load_pack(bytes)), R::SizeMismatch);
}

// ------------------------------------------------------------------------- framing

#[test]
fn framing_errors_are_malformed() {
    let a = active();
    let lay = layout(a.id.len());
    let good = valid_pack();

    let mut bad_magic = good.clone();
    bad_magic[0] = b'X';
    assert_eq!(reason(engine().load_pack(bad_magic)), R::Malformed);

    let mut trailing = good.clone();
    trailing.push(0);
    assert_eq!(reason(engine().load_pack(trailing)), R::Malformed);

    let mut zero_manifest = good.clone();
    zero_manifest[lay.manifest_len..lay.manifest_len + 4].copy_from_slice(&[0, 0, 0, 0]);
    assert_eq!(reason(engine().load_pack(zero_manifest)), R::Malformed);

    let mut zero_key_id = good.clone();
    zero_key_id[lay.key_id_len] = 0;
    assert_eq!(reason(engine().load_pack(zero_key_id)), R::Malformed);

    let mut bad_key_char = good.clone();
    bad_key_char[lay.key_id_len + 1] = b' ';
    assert_eq!(reason(engine().load_pack(bad_key_char)), R::Malformed);

    for bytes in [
        vec![],
        MAGIC.to_vec(),
        b"not a pack".to_vec(),
        vec![0u8; 100],
    ] {
        assert_eq!(reason(engine().load_pack(bytes)), R::Malformed);
    }
}

#[test]
fn unsupported_format_version() {
    for v in [0u16, 2, 0x0100, u16::MAX] {
        let mut bytes = valid_pack();
        bytes[8..10].copy_from_slice(&v.to_be_bytes());
        assert_eq!(
            reason(engine().load_pack(bytes)),
            R::UnsupportedFormatVersion
        );
    }
}

#[test]
fn every_truncation_is_malformed() {
    let good = valid_pack();
    let e = engine();
    for len in 0..good.len() {
        assert_eq!(
            reason(e.load_pack(good[..len].to_vec())),
            R::Malformed,
            "truncated to {len}"
        );
    }
    assert!(e.loaded_packs().is_empty());
}

#[test]
fn every_single_bit_flip_is_rejected() {
    let good = valid_pack();
    let e = engine();
    for i in 0..good.len() {
        for bit in 0..8 {
            let mut bytes = good.clone();
            bytes[i] ^= 1 << bit;
            let r = e.load_pack(bytes);
            assert!(
                matches!(r, Err(CoreError::PackRejected { .. })),
                "flip byte {i} bit {bit} not rejected: {r:?}"
            );
        }
    }
    assert!(e.loaded_packs().is_empty());
}

#[test]
fn every_byte_replacement_is_rejected() {
    let good = valid_pack();
    let e = engine();
    for i in 0..good.len() {
        for v in [0x00, 0xff, good[i].wrapping_add(1)] {
            if v == good[i] {
                continue;
            }
            let mut bytes = good.clone();
            bytes[i] = v;
            assert!(e.load_pack(bytes).is_err(), "byte {i} := {v:#04x} accepted");
        }
    }
    assert!(e.loaded_packs().is_empty());
}

// ------------------------------------------------------------------------- limits

#[test]
fn oversize_container_manifest_and_artifact_are_rejected() {
    let a = active();
    let lay = layout(a.id.len());
    let good = valid_pack();
    let manifest_len = u32::from_be_bytes(good[lay.manifest_len..][..4].try_into().unwrap());
    let d = PackLimits::default();

    let e = engine_with_limits(PackLimits {
        max_container_bytes: good.len() as u64 - 1,
        ..d
    });
    assert_eq!(reason(e.load_pack(good.clone())), R::TooLarge);

    let e = engine_with_limits(PackLimits {
        max_manifest_bytes: u64::from(manifest_len) - 1,
        ..d
    });
    assert_eq!(reason(e.load_pack(good.clone())), R::TooLarge);

    let e = engine_with_limits(PackLimits {
        max_artifact_bytes: ARTIFACT.len() as u64 - 1,
        ..d
    });
    assert_eq!(reason(e.load_pack(good.clone())), R::TooLarge);

    // Exactly at the limits is fine.
    let e = engine_with_limits(PackLimits {
        max_container_bytes: good.len() as u64,
        max_manifest_bytes: u64::from(manifest_len),
        max_artifact_bytes: ARTIFACT.len() as u64,
    });
    assert!(e.load_pack(good).is_ok());
}

#[test]
fn huge_length_fields_do_not_allocate_or_panic() {
    let a = active();
    let lay = layout(a.id.len());
    let good = valid_pack();

    let mut huge_manifest = good.clone();
    huge_manifest[lay.manifest_len..lay.manifest_len + 4].copy_from_slice(&u32::MAX.to_be_bytes());
    assert_eq!(reason(engine().load_pack(huge_manifest)), R::TooLarge);

    let manifest_len = u32::from_be_bytes(good[lay.manifest_len..][..4].try_into().unwrap());
    let artifact_len_at = lay.manifest + manifest_len as usize;
    for v in [u64::MAX, 1 << 40, u64::from(u32::MAX) + 1] {
        let mut bytes = good.clone();
        bytes[artifact_len_at..artifact_len_at + 8].copy_from_slice(&v.to_be_bytes());
        assert_eq!(reason(engine().load_pack(bytes)), R::TooLarge);
    }
    // Within the limit but longer than the data: truncated.
    let mut bytes = good.clone();
    bytes[artifact_len_at..artifact_len_at + 8].copy_from_slice(&1000u64.to_be_bytes());
    assert_eq!(reason(engine().load_pack(bytes)), R::Malformed);
}

// ------------------------------------------------------------------------- manifest fields

fn signed_manifest_reason(edit: impl FnOnce(&mut serde_json::Map<String, Value>)) -> R {
    let a = active();
    let mut m = manifest_value(&a, ARTIFACT);
    edit(&mut m);
    reason(engine().load_pack(signed_raw(&a, &serde_json::to_vec(&m).unwrap(), ARTIFACT)))
}

#[test]
fn unsupported_resource_type() {
    for t in ["FOO", "keyboard_layout", "", "TRANSLITERATION_RULES "] {
        assert_eq!(
            signed_manifest_reason(|m| {
                m.insert("resourceType".into(), json!(t));
            }),
            R::UnsupportedResourceType,
            "{t}"
        );
    }
}

#[test]
fn invalid_manifest_fields() {
    let cases: Vec<(&str, Value)> = vec![
        ("version", json!("1.0")),
        ("version", json!("v1.0.0")),
        ("version", json!("01.0.0")),
        ("version", json!("1.0.0+build.1")),
        ("version", json!(format!("1.0.0-{}", "a".repeat(40)))),
        ("version", json!(1)),
        ("resourceId", json!("0F8FAD5B-D9CB-469F-A165-70867728950E")),
        ("resourceId", json!("not-a-uuid")),
        ("languageCode", json!("AM")),
        ("languageCode", json!("amharic")),
        ("languageCode", json!("")),
        ("platform", json!("WINDOWS")),
        ("platform", json!("shared")),
        ("releaseChannel", json!("NIGHTLY")),
        ("sha256", json!("A".repeat(64))),
        ("sha256", json!("abc")),
        ("sizeBytes", json!(-1)),
        ("sizeBytes", json!(26.0)),
        ("sizeBytes", json!("26")),
        ("licenseCode", json!("")),
        ("licenseCode", json!("x".repeat(61))),
        ("licenseCode", json!("bad\nlicence")),
        ("signatureKeyId", json!("bad id")),
        ("baseResourceId", json!("nope")),
        ("baseResourceId", json!(RESOURCE_ID)),
        ("minAppVersion", json!("1.4")),
        ("artifactUrl", json!(7)),
        ("artifactUrl", json!("x".repeat(2049))),
        ("deviceRequirements", json!([])),
        ("profile", json!("AM")),
    ];
    for (field, value) in cases {
        assert_eq!(
            signed_manifest_reason(|m| {
                m.insert(field.into(), value.clone());
            }),
            R::InvalidManifest,
            "{field} = {value}"
        );
    }
    for field in [
        "resourceId",
        "languageCode",
        "version",
        "platform",
        "releaseChannel",
        "sha256",
        "sizeBytes",
        "licenseCode",
        "signatureKeyId",
    ] {
        assert_eq!(
            signed_manifest_reason(|m| {
                m.remove(field);
            }),
            R::InvalidManifest,
            "missing {field}"
        );
    }
    // A missing resourceType is invalid JSON shape, not an unsupported type.
    assert_eq!(
        signed_manifest_reason(|m| {
            m.remove("resourceType");
        }),
        R::InvalidManifest
    );
}

#[test]
fn non_canonical_manifest_encodings_are_rejected() {
    let a = active();
    let good = fields("1.0.0").to_json(a.id, ARTIFACT).unwrap();
    let text = String::from_utf8(good.clone()).unwrap();
    let duplicate = text.replacen(
        "\"version\":\"1.0.0\"",
        "\"version\":\"1.0.0\",\"version\":\"9.0.0\"",
        1,
    );
    assert_ne!(duplicate, text);
    let array = serde_json::to_vec(
        &serde_json::from_slice::<Value>(&good)
            .unwrap()
            .as_object()
            .unwrap()
            .values()
            .collect::<Vec<_>>(),
    )
    .unwrap();
    let cases: Vec<Vec<u8>> = vec![
        duplicate.into_bytes(),
        [b" ".as_slice(), &good].concat(),
        [b"\xEF\xBB\xBF".as_slice(), &good].concat(),
        [good.as_slice(), b"x"].concat(),
        [good.as_slice(), b"{}"].concat(),
        array,
        b"{".to_vec(),
        b"null".to_vec(),
        [&good[..good.len() - 1], b",\"x\":\"\xff\"}".as_slice()].concat(),
    ];
    for m in cases {
        let r = engine().load_pack(signed_raw(&a, &m, ARTIFACT));
        assert_eq!(
            reason(r),
            R::InvalidManifest,
            "{}",
            String::from_utf8_lossy(&m)
        );
    }
    // Trailing whitespace is valid JSON and accepted (the signature covers it anyway).
    let ws = [good.as_slice(), b"\n"].concat();
    assert!(engine().load_pack(signed_raw(&a, &ws, ARTIFACT)).is_ok());
}

#[test]
fn errors_never_contain_pack_content() {
    const MARKER: &str = "ZZSECRETMARKERZZ";
    let a = active();
    let probes: Vec<Vec<u8>> = vec![
        {
            let mut m = manifest_value(&a, ARTIFACT);
            m.insert("resourceType".into(), json!(MARKER));
            signed_raw(&a, &serde_json::to_vec(&m).unwrap(), ARTIFACT)
        },
        {
            let mut m = manifest_value(&a, ARTIFACT);
            m.insert("version".into(), json!(MARKER));
            signed_raw(&a, &serde_json::to_vec(&m).unwrap(), ARTIFACT)
        },
        {
            let raw = format!("{{\"resourceId\":\"{MARKER}\"");
            signed_raw(&a, raw.as_bytes(), ARTIFACT)
        },
        pack(&untrusted(), &fields("1.0.0"), MARKER.as_bytes()),
    ];
    for p in probes {
        let err = engine().load_pack(p).unwrap_err();
        let shown = format!("{err} {err:?}");
        assert!(!shown.contains(MARKER), "error leaks content: {shown}");
    }
}

// ------------------------------------------------------------------------- rollback

#[test]
fn rollback_protection() {
    let a = active();
    let e = engine();
    e.load_pack(pack(&a, &fields("1.3.0"), ARTIFACT)).unwrap();

    // Lower versions, including pre-releases of the same version, are refused.
    for v in ["1.2.9", "1.3.0-rc.1", "0.9.0", "1.2.10"] {
        assert_eq!(
            reason(e.load_pack(pack(&a, &fields(v), ARTIFACT))),
            R::Downgrade,
            "{v}"
        );
    }
    // Same version, different artifact: refused.
    assert_eq!(
        reason(e.load_pack(pack(&a, &fields("1.3.0"), b"other content"))),
        R::Downgrade
    );
    // Same version, same artifact (even re-signed by another trusted key): idempotent.
    let again = e
        .load_pack(pack(&retiring(), &fields("1.3.0"), ARTIFACT))
        .unwrap();
    assert_eq!(again.key_id, "test-active-1", "the loaded pack is kept");

    // Upgrades use semantic precedence, not string order.
    e.load_pack(pack(&a, &fields("1.10.0-beta.2"), ARTIFACT))
        .unwrap();
    e.load_pack(pack(&a, &fields("1.10.0-beta.10"), ARTIFACT))
        .unwrap();
    e.load_pack(pack(&a, &fields("1.10.0"), ARTIFACT)).unwrap();
    assert_eq!(
        reason(e.load_pack(pack(&a, &fields("1.9.0"), ARTIFACT))),
        R::Downgrade
    );
    let loaded = e.loaded_packs();
    assert_eq!(loaded.len(), 1);
    assert_eq!(loaded[0].version, "1.10.0");
}

#[test]
fn rollback_slots_are_per_language_type_and_platform() {
    let a = active();
    let e = engine();
    e.load_pack(pack(&a, &fields("2.0.0"), ARTIFACT)).unwrap();

    let mut ti = fields("1.0.0");
    ti.language_code = "ti".into();
    let mut android = fields("1.0.0");
    android.platform = PackPlatform::Android;
    let mut dict = fields("1.0.0");
    dict.resource_type = ResourceType::Dictionary;
    for f in [ti, android, dict] {
        e.load_pack(pack(&a, &f, ARTIFACT)).unwrap();
    }
    assert_eq!(e.loaded_packs().len(), 4);
}

#[test]
fn rollback_state_is_per_engine_instance() {
    // A fresh engine (e.g. host rolled back to last-known-good) accepts an older version.
    let a = active();
    engine()
        .load_pack(pack(&a, &fields("2.0.0"), ARTIFACT))
        .unwrap();
    assert!(engine()
        .load_pack(pack(&a, &fields("1.0.0"), ARTIFACT))
        .is_ok());
}

// ------------------------------------------------------------------------- deltas

#[test]
fn delta_pack_is_verified_then_rejected() {
    let a = active();
    let mut f = fields("1.4.0");
    f.base_resource_id = Some(OTHER_RESOURCE_ID.into());
    let bytes = pack(&a, &f, ARTIFACT);

    let ring = KeyRing::new(&[a.trusted()]).unwrap();
    let v = pack::verify_pack(bytes.clone(), &ring, &PackLimits::default()).unwrap();
    assert!(v.is_delta() && v.info().is_delta);

    let e = engine();
    assert_eq!(reason(e.load_pack(bytes.clone())), R::DeltaNotSupported);
    assert!(e.loaded_packs().is_empty());

    // A tampered delta fails verification first.
    let mut tampered = bytes;
    *tampered.last_mut().unwrap() ^= 0x80;
    assert_eq!(reason(e.load_pack(tampered)), R::ChecksumMismatch);
}

#[test]
fn concurrent_loads_of_the_same_pack_are_idempotent() {
    let e = engine();
    let bytes = valid_pack();
    std::thread::scope(|s| {
        for _ in 0..8 {
            let e = &e;
            let b = bytes.clone();
            s.spawn(move || e.load_pack(b).unwrap());
        }
    });
    assert_eq!(e.loaded_packs().len(), 1);
}

#[test]
fn reject_reason_codes_are_stable() {
    let all = [
        (R::Malformed, "MALFORMED"),
        (R::TooLarge, "TOO_LARGE"),
        (R::UnsupportedFormatVersion, "UNSUPPORTED_FORMAT_VERSION"),
        (R::UnknownKey, "UNKNOWN_KEY"),
        (R::RevokedKey, "REVOKED_KEY"),
        (R::BadSignature, "BAD_SIGNATURE"),
        (R::InvalidManifest, "INVALID_MANIFEST"),
        (R::UnsupportedResourceType, "UNSUPPORTED_RESOURCE_TYPE"),
        (R::KeyMismatch, "KEY_MISMATCH"),
        (R::SizeMismatch, "SIZE_MISMATCH"),
        (R::ChecksumMismatch, "CHECKSUM_MISMATCH"),
        (R::Downgrade, "DOWNGRADE"),
        (R::DeltaNotSupported, "DELTA_NOT_SUPPORTED"),
    ];
    for (r, code) in all {
        assert_eq!(r.code(), code);
    }
}
