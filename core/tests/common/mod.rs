//! Shared helpers for the pack tests. All keys are TEST keys derived from fixed public seeds; they
//! must never be trusted by a production build.
#![allow(dead_code)]

use qal_keyboard_core::pack::builder::{self, ManifestFields, SigningKey};
use qal_keyboard_core::{
    CoreError, EngineConfig, KeyStatus, KeyboardEngine, PackInfo, PackLimits, PackPlatform,
    PackRejectReason, ReleaseChannel, ResourceType, TrustedKey,
};
use std::sync::Arc;

pub const RESOURCE_ID: &str = "0f8fad5b-d9cb-469f-a165-70867728950e";
pub const OTHER_RESOURCE_ID: &str = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
pub const ARTIFACT: &[u8] = b"synthetic test artifact v1";

/// 32-byte seed `start, start+1, ..., start+31`.
pub fn seed(start: u8) -> [u8; 32] {
    std::array::from_fn(|i| start.wrapping_add(u8::try_from(i).unwrap()))
}

pub struct TestKey {
    pub id: &'static str,
    pub seed: [u8; 32],
    pub sk: SigningKey,
    pub status: KeyStatus,
}

impl TestKey {
    fn new(id: &'static str, seed_start: u8, status: KeyStatus) -> Self {
        let seed = seed(seed_start);
        Self {
            id,
            seed,
            sk: builder::signing_key_from_seed(seed),
            status,
        }
    }

    pub fn public(&self) -> [u8; 32] {
        self.sk.verifying_key().to_bytes()
    }

    pub fn trusted(&self) -> TrustedKey {
        TrustedKey {
            key_id: self.id.to_string(),
            public_key: self.public().to_vec(),
            status: self.status,
        }
    }
}

pub fn active() -> TestKey {
    TestKey::new("test-active-1", 0x00, KeyStatus::Active)
}
pub fn retiring() -> TestKey {
    TestKey::new("test-retiring-1", 0x20, KeyStatus::Retiring)
}
pub fn revoked() -> TestKey {
    TestKey::new("test-revoked-1", 0x40, KeyStatus::Revoked)
}
/// Not trusted by `engine()`.
pub fn untrusted() -> TestKey {
    TestKey::new("test-untrusted-1", 0x60, KeyStatus::Active)
}

pub fn config(keys: &[&TestKey], limits: Option<PackLimits>) -> EngineConfig {
    EngineConfig {
        max_candidates: 5,
        trusted_keys: keys.iter().map(|k| k.trusted()).collect(),
        pack_limits: limits,
    }
}

/// Engine trusting the active, retiring and revoked test keys.
pub fn engine() -> Arc<KeyboardEngine> {
    KeyboardEngine::new(config(&[&active(), &retiring(), &revoked()], None)).unwrap()
}

pub fn engine_with_limits(limits: PackLimits) -> Arc<KeyboardEngine> {
    KeyboardEngine::new(config(&[&active()], Some(limits))).unwrap()
}

pub fn fields(version: &str) -> ManifestFields {
    ManifestFields {
        resource_id: RESOURCE_ID.to_string(),
        language_code: "am".to_string(),
        resource_type: ResourceType::TransliterationRules,
        version: version.to_string(),
        platform: PackPlatform::Shared,
        release_channel: ReleaseChannel::Stable,
        license_code: "LicenseRef-Qal-Test".to_string(),
        base_resource_id: None,
        min_app_version: None,
    }
}

pub fn pack(key: &TestKey, fields: &ManifestFields, artifact: &[u8]) -> Vec<u8> {
    builder::build_pack(&key.sk, key.id, fields, artifact).unwrap()
}

pub fn valid_pack() -> Vec<u8> {
    pack(&active(), &fields("1.3.0"), ARTIFACT)
}

/// Manifest JSON for the default fields, as a mutable JSON object.
pub fn manifest_value(
    key: &TestKey,
    artifact: &[u8],
) -> serde_json::Map<String, serde_json::Value> {
    let bytes = fields("1.3.0").to_json(key.id, artifact).unwrap();
    match serde_json::from_slice(&bytes).unwrap() {
        serde_json::Value::Object(m) => m,
        _ => unreachable!(),
    }
}

/// Signs arbitrary manifest bytes with `key` and wraps them with `artifact`.
pub fn signed_raw(key: &TestKey, manifest: &[u8], artifact: &[u8]) -> Vec<u8> {
    builder::sign_container(&key.sk, key.id, manifest, artifact).unwrap()
}

pub fn reason(r: Result<PackInfo, CoreError>) -> PackRejectReason {
    match r {
        Err(CoreError::PackRejected { reason, .. }) => reason,
        other => panic!("expected PackRejected, got {other:?}"),
    }
}

pub fn hex_decode(s: &str) -> Vec<u8> {
    assert!(s.len().is_multiple_of(2), "odd hex length");
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

/// Byte offsets inside a container built with key id `key_id_len` bytes long.
pub struct Layout {
    pub version: usize,
    pub key_id_len: usize,
    pub signature: usize,
    pub manifest_len: usize,
    pub manifest: usize,
}

pub fn layout(key_id_len: usize) -> Layout {
    let signature = 8 + 2 + 1 + key_id_len;
    Layout {
        version: 8,
        key_id_len: 10,
        signature,
        manifest_len: signature + 64,
        manifest: signature + 64 + 4,
    }
}
