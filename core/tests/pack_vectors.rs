//! Cross-language test vectors for pack format v1 (`tests/vectors/pack-v1/*.json`).
//!
//! The vectors are the contract with the backend signer (plan step B3.2): for a vector with an
//! `input` section, a conforming signer given `input` must produce exactly `containerHex`
//! (Ed25519 is deterministic), and a conforming verifier must produce `expected`.
//!
//! `cargo test --test pack_vectors` checks that every file on disk matches what this generator
//! produces and that the engine yields the expected outcome from the file contents alone.
//! Regenerate after an intentional change with `QAL_REGEN_PACK_VECTORS=1 cargo test --test
//! pack_vectors`.

mod common;

use common::*;
use qal_keyboard_core::pack::{self, builder, KeyRing, SIGNATURE_LEN};
use qal_keyboard_core::{
    CoreError, EngineConfig, KeyStatus, KeyboardEngine, PackInfo, PackLimits, TrustedKey,
};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::PathBuf;

fn dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/vectors/pack-v1")
}

fn status_code(s: KeyStatus) -> &'static str {
    match s {
        KeyStatus::Active => "ACTIVE",
        KeyStatus::Retiring => "RETIRING",
        KeyStatus::Revoked => "REVOKED",
    }
}

fn parse_status(s: &str) -> KeyStatus {
    match s {
        "ACTIVE" => KeyStatus::Active,
        "RETIRING" => KeyStatus::Retiring,
        "REVOKED" => KeyStatus::Revoked,
        other => panic!("unknown status {other}"),
    }
}

fn info_json(i: &PackInfo) -> Value {
    json!({
        "packId": i.pack_id,
        "languageCode": i.language_code,
        "resourceType": i.resource_type.as_wire(),
        "version": i.version,
        "platform": i.platform.as_wire(),
        "releaseChannel": i.release_channel.as_wire(),
        "sizeBytes": i.size_bytes,
        "sha256": i.sha256,
        "isDelta": i.is_delta,
        "keyId": i.key_id,
        "minAppVersion": i.min_app_version,
    })
}

/// (signing key, manifest bytes, artifact) when a container is exactly the signer output.
type SignerInput = Option<(TestKey, Vec<u8>, Vec<u8>)>;

struct Gen {
    name: &'static str,
    description: &'static str,
    container: Vec<u8>,
    input: SignerInput,
    limits: PackLimits,
}

fn signed(key: TestKey, manifest: Vec<u8>, artifact: &[u8]) -> (Vec<u8>, SignerInput) {
    let c = signed_raw(&key, &manifest, artifact);
    (c, Some((key, manifest, artifact.to_vec())))
}

fn edited_manifest(edit: impl FnOnce(&mut serde_json::Map<String, Value>)) -> Vec<u8> {
    let mut m = manifest_value(&active(), ARTIFACT);
    edit(&mut m);
    serde_json::to_vec(&m).unwrap()
}

fn generate() -> Vec<Gen> {
    let d = PackLimits::default();
    let a_id_len = active().id.len();
    let lay = layout(a_id_len);
    let minimal_manifest = fields("1.3.0").to_json(active().id, ARTIFACT).unwrap();
    let minimal = signed_raw(&active(), &minimal_manifest, ARTIFACT);
    let mut out = Vec::new();
    let mut push = |name, description, (container, input): (Vec<u8>, _), limits| {
        out.push(Gen {
            name,
            description,
            container,
            input,
            limits,
        })
    };

    push(
        "valid-minimal",
        "Compact manifest with required members only, signed by an ACTIVE key.",
        signed(active(), minimal_manifest.clone(), ARTIFACT),
        d,
    );
    push(
        "valid-retiring-key",
        "Signed by a RETIRING key: still verifies.",
        signed(
            retiring(),
            fields("1.3.0").to_json(retiring().id, ARTIFACT).unwrap(),
            ARTIFACT,
        ),
        d,
    );
    let pretty = format!(
        "{{\n  \"resourceId\": \"{RESOURCE_ID}\",\n  \"languageCode\": \"ti\",\n  \
         \"resourceType\": \"DICTIONARY\",\n  \"version\": \"2.0.0-beta.1\",\n  \
         \"platform\": \"ANDROID\",\n  \"releaseChannel\": \"BETA\",\n  \
         \"minAppVersion\": \"1.4.0\",\n  \
         \"artifactUrl\": \"https://cdn.example.com/packs/ti/dictionary/2.0.0-beta.1.pack\",\n  \
         \"sha256\": \"{}\",\n  \"sizeBytes\": {},\n  \"baseResourceId\": null,\n  \
         \"deviceRequirements\": {{ \"minRamMb\": 0 }},\n  \"licenseCode\": \"LicenseRef-Qal-Test\",\n  \
         \"profile\": {{ \"code\": \"TI_TEST\", \"displayName\": \"ትግርኛ\", \"normalization\": \"NFC\" }},\n  \
         \"futureMember\": [1, 2, 3],\n  \"signatureKeyId\": \"{}\"\n}}\n",
        pack::to_hex(&<sha2::Sha256 as sha2::Digest>::digest(ARTIFACT)),
        ARTIFACT.len(),
        active().id
    );
    push(
        "valid-optional-fields-pretty",
        "Pretty-printed manifest with optional, unknown and non-ASCII members: the signature covers \
         these exact bytes; no canonical JSON form is involved.",
        signed(active(), pretty.into_bytes(), ARTIFACT),
        d,
    );
    let delta = {
        let mut f = fields("1.4.0");
        f.base_resource_id = Some(OTHER_RESOURCE_ID.into());
        f.to_json(active().id, ARTIFACT).unwrap()
    };
    push(
        "delta-verifies-but-not-loadable",
        "Delta pack (baseResourceId set): verification succeeds, the core refuses to load it.",
        signed(active(), delta, ARTIFACT),
        d,
    );

    let mut t = minimal.clone();
    let version_at = lay.manifest
        + minimal_manifest
            .windows(5)
            .position(|w| w == b"1.3.0")
            .unwrap()
        + 4;
    t[version_at] = b'1'; // "1.3.0" -> "1.3.1"
    push(
        "reject-manifest-tampered",
        "valid-minimal with the manifest version changed after signing.",
        (t, None),
        d,
    );
    let mut t = minimal.clone();
    *t.last_mut().unwrap() ^= 0x01;
    push(
        "reject-artifact-tampered",
        "valid-minimal with the last artifact byte flipped.",
        (t, None),
        d,
    );
    push(
        "reject-size-mismatch",
        "Validly signed manifest whose sizeBytes is one more than the artifact length.",
        signed(
            active(),
            edited_manifest(|m| {
                m.insert("sizeBytes".into(), json!(ARTIFACT.len() + 1));
            }),
            ARTIFACT,
        ),
        d,
    );
    push(
        "reject-unknown-key",
        "Signed by a key whose id is not trusted.",
        signed(
            untrusted(),
            fields("1.3.0").to_json(untrusted().id, ARTIFACT).unwrap(),
            ARTIFACT,
        ),
        d,
    );
    push(
        "reject-revoked-key",
        "Valid signature by a REVOKED key.",
        signed(
            revoked(),
            fields("1.3.0").to_json(revoked().id, ARTIFACT).unwrap(),
            ARTIFACT,
        ),
        d,
    );
    let wrong_key = {
        use ed25519_dalek::Signer;
        let sig = untrusted()
            .sk
            .sign(&pack::signing_input(&minimal_manifest))
            .to_bytes();
        builder::assemble(active().id.as_bytes(), &sig, &minimal_manifest, ARTIFACT).unwrap()
    };
    push(
        "reject-wrong-key",
        "Header names the ACTIVE key but the signature was made with another key.",
        (wrong_key, None),
        d,
    );
    let no_context = {
        use ed25519_dalek::Signer;
        let sig = active().sk.sign(&minimal_manifest).to_bytes();
        builder::assemble(active().id.as_bytes(), &sig, &minimal_manifest, ARTIFACT).unwrap()
    };
    push(
        "reject-no-domain-separation",
        "Signature over the manifest bytes without the QAL-PACK-MANIFEST-v1 context prefix.",
        (no_context, None),
        d,
    );
    let malleable = {
        const L: [u8; 32] = [
            0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9,
            0xde, 0x14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
        ];
        let mut t = minimal.clone();
        let s_at = lay.signature + SIGNATURE_LEN / 2;
        let mut carry = 0u16;
        for (i, l) in L.iter().enumerate() {
            let sum = u16::from(t[s_at + i]) + u16::from(*l) + carry;
            t[s_at + i] = sum as u8;
            carry = sum >> 8;
        }
        assert_eq!(carry, 0);
        t
    };
    push(
        "reject-malleable-signature",
        "valid-minimal with S replaced by S + L (non-canonical scalar); strict verification rejects it.",
        (malleable, None),
        d,
    );
    push(
        "reject-key-mismatch",
        "Signed by the ACTIVE key but the manifest's signatureKeyId names the RETIRING key.",
        signed(
            active(),
            fields("1.3.0").to_json(retiring().id, ARTIFACT).unwrap(),
            ARTIFACT,
        ),
        d,
    );
    push(
        "reject-unsupported-resource-type",
        "Validly signed manifest with resourceType FOO.",
        signed(
            active(),
            edited_manifest(|m| {
                m.insert("resourceType".into(), json!("FOO"));
            }),
            ARTIFACT,
        ),
        d,
    );
    push(
        "reject-invalid-version",
        "Validly signed manifest whose version 1.3 is not a semantic version.",
        signed(
            active(),
            edited_manifest(|m| {
                m.insert("version".into(), json!("1.3"));
            }),
            ARTIFACT,
        ),
        d,
    );
    let mut t = minimal.clone();
    t.push(0);
    push(
        "reject-trailing-byte",
        "valid-minimal plus one trailing byte.",
        (t, None),
        d,
    );
    let t = minimal[..minimal.len() - 1].to_vec();
    push(
        "reject-truncated",
        "valid-minimal without its last byte.",
        (t, None),
        d,
    );
    let mut t = minimal.clone();
    t[0] = b'q';
    push(
        "reject-bad-magic",
        "valid-minimal with a corrupted magic.",
        (t, None),
        d,
    );
    let mut t = minimal.clone();
    t[lay.version..lay.version + 2].copy_from_slice(&2u16.to_be_bytes());
    push(
        "reject-format-version-2",
        "valid-minimal with format version 2.",
        (t, None),
        d,
    );
    push(
        "reject-manifest-over-limit",
        "valid-minimal checked with maxManifestBytes = 64 (the manifest is longer).",
        signed(active(), minimal_manifest.clone(), ARTIFACT),
        PackLimits {
            max_manifest_bytes: 64,
            ..d
        },
    );
    out
}

fn trusted_keys() -> Vec<TestKey> {
    vec![active(), retiring(), revoked()]
}

fn outcome(container: &[u8], keys: &[TrustedKey], limits: PackLimits) -> Value {
    let ring = KeyRing::new(keys).unwrap();
    let verifies = pack::verify_pack(container.to_vec(), &ring, &limits).is_ok();
    let e = KeyboardEngine::new(EngineConfig {
        max_candidates: 5,
        trusted_keys: keys.to_vec(),
        pack_limits: Some(limits),
    })
    .unwrap();
    match e.load_pack(container.to_vec()) {
        Ok(info) => json!({ "verifies": verifies, "load": "ACCEPT", "packInfo": info_json(&info) }),
        Err(CoreError::PackRejected { reason, .. }) => {
            json!({ "verifies": verifies, "load": "REJECT", "reason": reason.code() })
        }
        Err(other) => panic!("unexpected error {other:?}"),
    }
}

fn to_json(g: &Gen) -> Value {
    let keys: Vec<TrustedKey> = trusted_keys().iter().map(|k| k.trusted()).collect();
    let input = g.input.as_ref().map(|(k, m, a)| {
        json!({
            "signingKeyId": k.id,
            "signingSeedHex": pack::to_hex(&k.seed),
            "manifestUtf8": String::from_utf8(m.clone()).unwrap(),
            "artifactHex": pack::to_hex(a),
        })
    });
    json!({
        "format": "qal-pack-v1-test-vector",
        "name": g.name,
        "description": g.description,
        "trustedKeys": trusted_keys().iter().map(|k| json!({
            "keyId": k.id,
            "status": status_code(k.status),
            "publicKeyHex": pack::to_hex(&k.public()),
        })).collect::<Vec<_>>(),
        "limits": {
            "maxContainerBytes": g.limits.max_container_bytes,
            "maxManifestBytes": g.limits.max_manifest_bytes,
            "maxArtifactBytes": g.limits.max_artifact_bytes,
        },
        "input": input,
        "containerHex": pack::to_hex(&g.container),
        "expected": outcome(&g.container, &keys, g.limits),
    })
}

/// Independent statement of the intended outcome of each generated vector.
const INTENDED: &[(&str, &str)] = &[
    ("valid-minimal", "ACCEPT"),
    ("valid-retiring-key", "ACCEPT"),
    ("valid-optional-fields-pretty", "ACCEPT"),
    ("delta-verifies-but-not-loadable", "DELTA_NOT_SUPPORTED"),
    ("reject-manifest-tampered", "BAD_SIGNATURE"),
    ("reject-artifact-tampered", "CHECKSUM_MISMATCH"),
    ("reject-size-mismatch", "SIZE_MISMATCH"),
    ("reject-unknown-key", "UNKNOWN_KEY"),
    ("reject-revoked-key", "REVOKED_KEY"),
    ("reject-wrong-key", "BAD_SIGNATURE"),
    ("reject-no-domain-separation", "BAD_SIGNATURE"),
    ("reject-malleable-signature", "BAD_SIGNATURE"),
    ("reject-key-mismatch", "KEY_MISMATCH"),
    (
        "reject-unsupported-resource-type",
        "UNSUPPORTED_RESOURCE_TYPE",
    ),
    ("reject-invalid-version", "INVALID_MANIFEST"),
    ("reject-trailing-byte", "MALFORMED"),
    ("reject-truncated", "MALFORMED"),
    ("reject-bad-magic", "MALFORMED"),
    ("reject-format-version-2", "UNSUPPORTED_FORMAT_VERSION"),
    ("reject-manifest-over-limit", "TOO_LARGE"),
];

#[test]
fn vectors_on_disk_match_generator() {
    let gens = generate();
    assert_eq!(gens.len(), INTENDED.len());
    for (g, (name, want)) in gens.iter().zip(INTENDED) {
        assert_eq!(g.name, *name);
        let e = &to_json(g)["expected"];
        let got = if e["load"] == "ACCEPT" {
            "ACCEPT"
        } else {
            e["reason"].as_str().unwrap()
        };
        assert_eq!(got, *want, "{name}");
        assert_eq!(
            e["verifies"],
            json!(want.starts_with("ACCEPT") || *want == "DELTA_NOT_SUPPORTED"),
            "{name}"
        );
    }
    let generated: BTreeMap<String, Value> = gens
        .iter()
        .map(|g| (format!("{}.json", g.name), to_json(g)))
        .collect();
    if std::env::var_os("QAL_REGEN_PACK_VECTORS").is_some() {
        std::fs::create_dir_all(dir()).unwrap();
        for (file, v) in &generated {
            let text = serde_json::to_string_pretty(v).unwrap() + "\n";
            std::fs::write(dir().join(file), text).unwrap();
        }
    }
    let mut on_disk = Vec::new();
    for entry in std::fs::read_dir(dir()).expect("vectors directory exists") {
        let path = entry.unwrap().path();
        if path.extension().is_some_and(|e| e == "json") {
            on_disk.push(path.file_name().unwrap().to_string_lossy().into_owned());
        }
    }
    on_disk.sort();
    assert_eq!(
        on_disk,
        generated.keys().cloned().collect::<Vec<_>>(),
        "vector files differ from the generator; rerun with QAL_REGEN_PACK_VECTORS=1"
    );
    for (file, v) in &generated {
        let disk: Value =
            serde_json::from_str(&std::fs::read_to_string(dir().join(file)).unwrap()).unwrap();
        assert_eq!(
            &disk, v,
            "{file} is stale; rerun with QAL_REGEN_PACK_VECTORS=1"
        );
    }
}

/// Uses only the file contents, the way another implementation would.
#[test]
fn vectors_produce_expected_outcomes() {
    let mut accepted = 0;
    let mut rejected = 0;
    for entry in std::fs::read_dir(dir()).unwrap() {
        let path = entry.unwrap().path();
        if path.extension().is_none_or(|e| e != "json") {
            continue;
        }
        let v: Value = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        let name = v["name"].as_str().unwrap();
        let keys: Vec<TrustedKey> = v["trustedKeys"]
            .as_array()
            .unwrap()
            .iter()
            .map(|k| TrustedKey {
                key_id: k["keyId"].as_str().unwrap().into(),
                public_key: hex_decode(k["publicKeyHex"].as_str().unwrap()),
                status: parse_status(k["status"].as_str().unwrap()),
            })
            .collect();
        let l = &v["limits"];
        let limits = PackLimits {
            max_container_bytes: l["maxContainerBytes"].as_u64().unwrap(),
            max_manifest_bytes: l["maxManifestBytes"].as_u64().unwrap(),
            max_artifact_bytes: l["maxArtifactBytes"].as_u64().unwrap(),
        };
        let container = hex_decode(v["containerHex"].as_str().unwrap());

        // A conforming signer reproduces the container byte for byte.
        if let Some(input) = v["input"].as_object() {
            let seed: [u8; 32] = hex_decode(input["signingSeedHex"].as_str().unwrap())
                .try_into()
                .unwrap();
            let sk = builder::signing_key_from_seed(seed);
            let rebuilt = builder::sign_container(
                &sk,
                input["signingKeyId"].as_str().unwrap(),
                input["manifestUtf8"].as_str().unwrap().as_bytes(),
                &hex_decode(input["artifactHex"].as_str().unwrap()),
            )
            .unwrap();
            assert_eq!(rebuilt, container, "{name}: signer output differs");
        }

        let got = outcome(&container, &keys, limits);
        assert_eq!(got, v["expected"], "{name}");
        if got["load"] == "ACCEPT" {
            accepted += 1;
        } else {
            rejected += 1;
        }
    }
    assert!(accepted >= 3, "need valid vectors");
    assert!(rejected >= 10, "need invalid vectors");
}
