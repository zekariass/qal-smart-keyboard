//! Signed language-pack container, format v1 (plan step C1.2, rule N11).
//!
//! The normative specification is `docs/specs/pack-format-v1.md`. In short, a container is
//!
//! ```text
//! magic "QALPACK\0" | u16 format_version=1 | u8 key_id_len | key_id | 64-byte signature
//!   | u32 manifest_len | manifest (UTF-8 JSON) | u64 artifact_len | artifact
//! ```
//!
//! (all integers big-endian), and the signature is
//! `Ed25519(SIGNATURE_CONTEXT || manifest bytes)` over the exact transmitted manifest bytes. The
//! manifest commits to the artifact by SHA-256 and size.
//!
//! [`verify_pack`] checks, in this order: container size, framing, key lookup (unknown and revoked
//! keys rejected), strict Ed25519 signature, manifest JSON and fields, key id binding, artifact
//! size, artifact SHA-256. No manifest field is interpreted before the signature has been
//! verified. Rollback protection and delta handling are engine policy (see `KeyboardEngine`).
//!
//! The parser never panics, never allocates based on an unchecked length and never puts pack
//! content in an error (N2): error details are static strings.

#![forbid(unsafe_code)]
#![deny(
    clippy::indexing_slicing,
    clippy::unwrap_used,
    clippy::expect_used,
    clippy::panic,
    clippy::arithmetic_side_effects
)]

#[cfg(feature = "builder")]
pub mod builder;

use crate::error::CoreError;
use ed25519_dalek::{Signature, VerifyingKey};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::ops::Range;

/// First 8 bytes of every container.
pub const MAGIC: [u8; 8] = *b"QALPACK\0";
/// The only container format version this build understands.
pub const FORMAT_VERSION: u16 = 1;
/// Domain-separation prefix: the signed message is `SIGNATURE_CONTEXT || manifest bytes`.
pub const SIGNATURE_CONTEXT: &[u8] = b"QAL-PACK-MANIFEST-v1\0";
/// Ed25519 signature length.
pub const SIGNATURE_LEN: usize = 64;
/// Ed25519 raw public key length.
pub const PUBLIC_KEY_LEN: usize = 32;
/// Maximum key id length (matches `resource_signing_keys.key_id VARCHAR(120)`).
pub const MAX_KEY_ID_LEN: usize = 120;
/// Smallest possible container: magic, version, 1-byte key id, signature, 1-byte manifest.
pub const MIN_CONTAINER_LEN: usize = 8 + 2 + 1 + 1 + SIGNATURE_LEN + 4 + 1 + 8;

/// Default cap on the whole container (artifact cap + manifest cap + framing headroom).
pub const DEFAULT_MAX_CONTAINER_BYTES: u64 = 32 * 1024 * 1024 + 128 * 1024;
/// Default cap on the manifest.
pub const DEFAULT_MAX_MANIFEST_BYTES: u64 = 64 * 1024;
/// Default cap on the artifact. Typing packs are budgeted at 15 to 25 MB per language (SD §17.1).
pub const DEFAULT_MAX_ARTIFACT_BYTES: u64 = 32 * 1024 * 1024;
/// Configured manifest caps may not exceed this.
pub const HARD_MAX_MANIFEST_BYTES: u64 = 1024 * 1024;
/// Configured container caps may not exceed this (largest Java/Kotlin byte array).
pub const HARD_MAX_CONTAINER_BYTES: u64 = 0x7FFF_FFFF;

const MAX_VERSION_LEN: usize = 40;
const MAX_LANGUAGE_CODE_LEN: usize = 35;
const MAX_LICENSE_CODE_LEN: usize = 60;
const MAX_ARTIFACT_URL_LEN: usize = 2048;

// ------------------------------------------------------------------------------------------
// FFI-visible types
// ------------------------------------------------------------------------------------------

/// Lifecycle status of a trusted signing key (`resource_signing_keys.key_status`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum KeyStatus {
    /// Current signing key.
    Active,
    /// Being phased out: packs it signed still verify, new packs use the next key.
    Retiring,
    /// Never verifies anything, whatever the signature.
    Revoked,
}

/// A pinned Ed25519 public key the engine trusts for pack signatures.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TrustedKey {
    /// Key id as written in the container header (1 to 120 chars of `[A-Za-z0-9._-]`).
    pub key_id: String,
    /// Raw 32-byte Ed25519 public key.
    pub public_key: Vec<u8>,
    pub status: KeyStatus,
}

/// Resource-exhaustion limits applied before any expensive work.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct PackLimits {
    pub max_container_bytes: u64,
    pub max_manifest_bytes: u64,
    pub max_artifact_bytes: u64,
}

impl Default for PackLimits {
    fn default() -> Self {
        Self {
            max_container_bytes: DEFAULT_MAX_CONTAINER_BYTES,
            max_manifest_bytes: DEFAULT_MAX_MANIFEST_BYTES,
            max_artifact_bytes: DEFAULT_MAX_ARTIFACT_BYTES,
        }
    }
}

impl PackLimits {
    /// Checks the limits are usable: all non-zero, manifest at most 1 MiB, container at most
    /// 2^31-1 bytes. (A part limit above the container limit is harmless: the container limit
    /// applies first.)
    pub fn validate(&self) -> Result<(), CoreError> {
        let bad = |reason: &str| {
            Err(CoreError::InvalidConfig {
                reason: reason.to_string(),
            })
        };
        if self.max_container_bytes == 0
            || self.max_manifest_bytes == 0
            || self.max_artifact_bytes == 0
        {
            return bad("pack limits must be non-zero");
        }
        if self.max_manifest_bytes > HARD_MAX_MANIFEST_BYTES {
            return bad("max_manifest_bytes exceeds the hard limit (1 MiB)");
        }
        if self.max_container_bytes > HARD_MAX_CONTAINER_BYTES {
            return bad("max_container_bytes exceeds the hard limit (2^31-1)");
        }
        Ok(())
    }
}

/// Resource types (`language_resources.resource_type` CHECK list).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, uniffi::Enum)]
pub enum ResourceType {
    KeyboardLayout,
    TransliterationRules,
    Dictionary,
    FrequencyList,
    PredictionModel,
    AutocorrectionModel,
    ProtectedTerms,
    Pronunciation,
    EmojiKeywords,
    Glossary,
    SpellModel,
    LanguageIdModel,
    TranslationModel,
    AsrModel,
    TtsVoice,
    OnDeviceLlm,
    Other,
}

impl ResourceType {
    const ALL: [(ResourceType, &'static str); 17] = [
        (ResourceType::KeyboardLayout, "KEYBOARD_LAYOUT"),
        (ResourceType::TransliterationRules, "TRANSLITERATION_RULES"),
        (ResourceType::Dictionary, "DICTIONARY"),
        (ResourceType::FrequencyList, "FREQUENCY_LIST"),
        (ResourceType::PredictionModel, "PREDICTION_MODEL"),
        (ResourceType::AutocorrectionModel, "AUTOCORRECTION_MODEL"),
        (ResourceType::ProtectedTerms, "PROTECTED_TERMS"),
        (ResourceType::Pronunciation, "PRONUNCIATION"),
        (ResourceType::EmojiKeywords, "EMOJI_KEYWORDS"),
        (ResourceType::Glossary, "GLOSSARY"),
        (ResourceType::SpellModel, "SPELL_MODEL"),
        (ResourceType::LanguageIdModel, "LANGUAGE_ID_MODEL"),
        (ResourceType::TranslationModel, "TRANSLATION_MODEL"),
        (ResourceType::AsrModel, "ASR_MODEL"),
        (ResourceType::TtsVoice, "TTS_VOICE"),
        (ResourceType::OnDeviceLlm, "ON_DEVICE_LLM"),
        (ResourceType::Other, "OTHER"),
    ];

    /// Parses the manifest / database spelling, e.g. `TRANSLITERATION_RULES`.
    pub fn from_wire(s: &str) -> Option<Self> {
        Self::ALL.iter().find(|(_, w)| *w == s).map(|(t, _)| *t)
    }

    /// The manifest / database spelling.
    pub fn as_wire(self) -> &'static str {
        Self::ALL
            .iter()
            .find(|(t, _)| *t == self)
            .map_or("OTHER", |(_, w)| w)
    }
}

/// Target platform of a pack (`language_resources.platform`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, uniffi::Enum)]
pub enum PackPlatform {
    Shared,
    Android,
    Ios,
}

impl PackPlatform {
    pub fn from_wire(s: &str) -> Option<Self> {
        match s {
            "SHARED" => Some(Self::Shared),
            "ANDROID" => Some(Self::Android),
            "IOS" => Some(Self::Ios),
            _ => None,
        }
    }

    pub fn as_wire(self) -> &'static str {
        match self {
            Self::Shared => "SHARED",
            Self::Android => "ANDROID",
            Self::Ios => "IOS",
        }
    }
}

/// Release channel (`language_resources.release_channel`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum ReleaseChannel {
    Internal,
    Beta,
    Stable,
}

impl ReleaseChannel {
    pub fn from_wire(s: &str) -> Option<Self> {
        match s {
            "INTERNAL" => Some(Self::Internal),
            "BETA" => Some(Self::Beta),
            "STABLE" => Some(Self::Stable),
            _ => None,
        }
    }

    pub fn as_wire(self) -> &'static str {
        match self {
            Self::Internal => "INTERNAL",
            Self::Beta => "BETA",
            Self::Stable => "STABLE",
        }
    }
}

/// Machine-readable reason a pack was rejected. Wire codes (`code()`) are used by the test
/// vectors and by the backend implementation of the same checks.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum PackRejectReason {
    /// Container framing is invalid (bad magic, bad lengths, truncated, trailing bytes).
    Malformed,
    /// Container, manifest or artifact exceeds the configured limits.
    TooLarge,
    /// Container format version is not 1.
    UnsupportedFormatVersion,
    /// The header key id is not in the trusted key set.
    UnknownKey,
    /// The header key id names a revoked key.
    RevokedKey,
    /// Ed25519 strict verification failed.
    BadSignature,
    /// The (authenticated) manifest is not valid JSON or a field is missing or invalid.
    InvalidManifest,
    /// `resourceType` is not one of the known resource types.
    UnsupportedResourceType,
    /// The manifest's `signatureKeyId` differs from the header key id.
    KeyMismatch,
    /// The artifact length differs from the manifest's `sizeBytes`.
    SizeMismatch,
    /// The artifact SHA-256 differs from the manifest's `sha256`.
    ChecksumMismatch,
    /// A pack for the same language, type and platform is already loaded at the same or a
    /// higher version (rollback protection).
    Downgrade,
    /// The pack is a delta (`baseResourceId` set); deltas are applied by the host pack manager.
    DeltaNotSupported,
    /// The pack verified, but its artifact is not a valid resource of the declared type (for
    /// example a `KEYBOARD_LAYOUT` that fails layout validation).
    InvalidArtifact,
}

impl PackRejectReason {
    /// Stable SCREAMING_SNAKE_CASE code.
    pub fn code(self) -> &'static str {
        match self {
            Self::Malformed => "MALFORMED",
            Self::TooLarge => "TOO_LARGE",
            Self::UnsupportedFormatVersion => "UNSUPPORTED_FORMAT_VERSION",
            Self::UnknownKey => "UNKNOWN_KEY",
            Self::RevokedKey => "REVOKED_KEY",
            Self::BadSignature => "BAD_SIGNATURE",
            Self::InvalidManifest => "INVALID_MANIFEST",
            Self::UnsupportedResourceType => "UNSUPPORTED_RESOURCE_TYPE",
            Self::KeyMismatch => "KEY_MISMATCH",
            Self::SizeMismatch => "SIZE_MISMATCH",
            Self::ChecksumMismatch => "CHECKSUM_MISMATCH",
            Self::Downgrade => "DOWNGRADE",
            Self::DeltaNotSupported => "DELTA_NOT_SUPPORTED",
            Self::InvalidArtifact => "INVALID_ARTIFACT",
        }
    }
}

/// Metadata of a verified pack, returned to the host.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PackInfo {
    /// The manifest `resourceId` (lowercase UUID).
    pub pack_id: String,
    pub language_code: String,
    pub resource_type: ResourceType,
    /// Semantic version, as written in the manifest.
    pub version: String,
    pub platform: PackPlatform,
    pub release_channel: ReleaseChannel,
    /// Artifact size in bytes.
    pub size_bytes: u64,
    /// Artifact SHA-256, lowercase hex.
    pub sha256: String,
    /// True when the manifest names a `baseResourceId`.
    pub is_delta: bool,
    /// Id of the key that signed the manifest.
    pub key_id: String,
    /// Minimum app version from the manifest, for the host to enforce.
    pub min_app_version: Option<String>,
    /// False when a verified `DICTIONARY` / `FREQUENCY_LIST` payload does not parse. Such a pack
    /// is activated for rollback protection but never replaces a working model; hosts should
    /// treat it as corrupt. Always true for other resource types (checked when first used) and
    /// from `VerifiedPack::info`.
    pub payload_usable: bool,
}

// ------------------------------------------------------------------------------------------
// Errors
// ------------------------------------------------------------------------------------------

/// Verification failure. `detail` is a fixed string and never contains pack content.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[error("pack rejected ({}): {detail}", .reason.code())]
pub struct PackError {
    pub reason: PackRejectReason,
    pub detail: &'static str,
}

impl From<PackError> for CoreError {
    fn from(e: PackError) -> Self {
        CoreError::PackRejected {
            reason: e.reason,
            detail: e.detail.to_string(),
        }
    }
}

fn reject(reason: PackRejectReason, detail: &'static str) -> PackError {
    PackError { reason, detail }
}

// ------------------------------------------------------------------------------------------
// Trusted keys
// ------------------------------------------------------------------------------------------

#[derive(Debug, Clone)]
struct KeyEntry {
    key_id: String,
    key: VerifyingKey,
    status: KeyStatus,
}

/// Validated set of trusted signing keys.
#[derive(Debug, Clone, Default)]
pub struct KeyRing {
    entries: Vec<KeyEntry>,
}

impl KeyRing {
    /// Validates every key: well-formed unique key id, exactly 32 bytes, a valid Ed25519 point
    /// that is not of small order, and no public key listed twice (so a revocation cannot be
    /// bypassed through a second id). Revoked keys are kept so they are reported as revoked.
    pub fn new(keys: &[TrustedKey]) -> Result<Self, CoreError> {
        let bad = |reason: &str| CoreError::InvalidConfig {
            reason: reason.to_string(),
        };
        let mut entries: Vec<KeyEntry> = Vec::with_capacity(keys.len());
        for k in keys {
            if !is_valid_key_id(k.key_id.as_bytes()) {
                return Err(bad("trusted key id must be 1-120 chars of [A-Za-z0-9._-]"));
            }
            let raw: [u8; PUBLIC_KEY_LEN] = k
                .public_key
                .as_slice()
                .try_into()
                .map_err(|_| bad("trusted public key must be exactly 32 bytes"))?;
            let key = VerifyingKey::from_bytes(&raw)
                .map_err(|_| bad("trusted public key is not a valid Ed25519 point"))?;
            if key.is_weak() {
                return Err(bad("trusted public key has small order"));
            }
            if entries.iter().any(|e| e.key_id == k.key_id) {
                return Err(bad("duplicate trusted key id"));
            }
            if entries.iter().any(|e| e.key.as_bytes() == key.as_bytes()) {
                return Err(bad("the same public key is listed twice"));
            }
            entries.push(KeyEntry {
                key_id: k.key_id.clone(),
                key,
                status: k.status,
            });
        }
        Ok(Self { entries })
    }

    fn lookup(&self, key_id: &[u8]) -> Option<&KeyEntry> {
        self.entries.iter().find(|e| e.key_id.as_bytes() == key_id)
    }
}

/// `1..=120` bytes of `[A-Za-z0-9._-]`.
fn is_valid_key_id(id: &[u8]) -> bool {
    !id.is_empty()
        && id.len() <= MAX_KEY_ID_LEN
        && id
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
}

// ------------------------------------------------------------------------------------------
// Verified pack
// ------------------------------------------------------------------------------------------

/// Validated manifest content.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Manifest {
    pub resource_id: String,
    pub language_code: String,
    pub resource_type: ResourceType,
    pub version: semver::Version,
    pub platform: PackPlatform,
    pub release_channel: ReleaseChannel,
    /// Lowercase hex SHA-256 of the artifact.
    pub sha256: String,
    pub size_bytes: u64,
    pub base_resource_id: Option<String>,
    pub min_app_version: Option<semver::Version>,
    pub license_code: String,
    pub signature_key_id: String,
}

/// A pack that passed every check in [`verify_pack`]. Owns the container bytes (no copy).
#[derive(Debug, Clone)]
pub struct VerifiedPack {
    bytes: Vec<u8>,
    manifest_range: Range<usize>,
    artifact_range: Range<usize>,
    manifest: Manifest,
}

impl VerifiedPack {
    pub fn manifest(&self) -> &Manifest {
        &self.manifest
    }

    /// The exact signed manifest bytes.
    pub fn manifest_bytes(&self) -> &[u8] {
        self.bytes.get(self.manifest_range.clone()).unwrap_or(&[])
    }

    /// The artifact payload, whose size and SHA-256 match the manifest.
    pub fn artifact(&self) -> &[u8] {
        self.bytes.get(self.artifact_range.clone()).unwrap_or(&[])
    }

    pub fn is_delta(&self) -> bool {
        self.manifest.base_resource_id.is_some()
    }

    pub fn info(&self) -> PackInfo {
        let m = &self.manifest;
        PackInfo {
            pack_id: m.resource_id.clone(),
            language_code: m.language_code.clone(),
            resource_type: m.resource_type,
            version: m.version.to_string(),
            platform: m.platform,
            release_channel: m.release_channel,
            size_bytes: m.size_bytes,
            sha256: m.sha256.clone(),
            is_delta: self.is_delta(),
            key_id: m.signature_key_id.clone(),
            min_app_version: m.min_app_version.as_ref().map(|v| v.to_string()),
            payload_usable: true,
        }
    }
}

// ------------------------------------------------------------------------------------------
// Framing
// ------------------------------------------------------------------------------------------

/// Bounds-checked big-endian reader over untrusted bytes.
struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let end = self.pos.checked_add(n)?;
        let s = self.buf.get(self.pos..end)?;
        self.pos = end;
        Some(s)
    }

    fn array<const N: usize>(&mut self) -> Option<[u8; N]> {
        self.take(N)?.try_into().ok()
    }

    fn remaining(&self) -> usize {
        self.buf.len().saturating_sub(self.pos)
    }
}

/// Offsets of a container's sections. Lengths are bounds-checked, nothing is authenticated yet.
struct Framing<'a> {
    key_id: &'a [u8],
    signature: [u8; SIGNATURE_LEN],
    manifest: Range<usize>,
    artifact: Range<usize>,
}

fn limit_usize(limit: u64) -> usize {
    usize::try_from(limit).unwrap_or(usize::MAX)
}

fn parse_framing<'a>(buf: &'a [u8], limits: &PackLimits) -> Result<Framing<'a>, PackError> {
    use PackRejectReason::{Malformed, TooLarge, UnsupportedFormatVersion};
    let truncated = || reject(Malformed, "container is truncated");

    if buf.len() > limit_usize(limits.max_container_bytes) {
        return Err(reject(TooLarge, "container exceeds the size limit"));
    }
    let mut r = Reader { buf, pos: 0 };
    if r.array::<8>() != Some(MAGIC) {
        return Err(reject(Malformed, "not a pack container (bad magic)"));
    }
    let version = u16::from_be_bytes(r.array::<2>().ok_or_else(truncated)?);
    if version != FORMAT_VERSION {
        return Err(reject(
            UnsupportedFormatVersion,
            "unsupported container format version",
        ));
    }
    let [key_id_len] = r.array::<1>().ok_or_else(truncated)?;
    let key_id_len = usize::from(key_id_len);
    let key_id = r.take(key_id_len).ok_or_else(truncated)?;
    if !is_valid_key_id(key_id) {
        return Err(reject(Malformed, "invalid key id in header"));
    }
    let signature = r.array::<SIGNATURE_LEN>().ok_or_else(truncated)?;

    let manifest_len = u32::from_be_bytes(r.array::<4>().ok_or_else(truncated)?);
    if manifest_len == 0 {
        return Err(reject(Malformed, "empty manifest"));
    }
    if u64::from(manifest_len) > limits.max_manifest_bytes {
        return Err(reject(TooLarge, "manifest exceeds the size limit"));
    }
    let manifest_len = usize::try_from(manifest_len).map_err(|_| truncated())?;
    let manifest_start = r.pos;
    r.take(manifest_len).ok_or_else(truncated)?;
    let manifest = manifest_start..r.pos;

    let artifact_len = u64::from_be_bytes(r.array::<8>().ok_or_else(truncated)?);
    if artifact_len > limits.max_artifact_bytes {
        return Err(reject(TooLarge, "artifact exceeds the size limit"));
    }
    let artifact_len = usize::try_from(artifact_len).map_err(|_| truncated())?;
    match r.remaining().cmp(&artifact_len) {
        std::cmp::Ordering::Less => return Err(truncated()),
        std::cmp::Ordering::Greater => {
            return Err(reject(Malformed, "trailing bytes after the artifact"))
        }
        std::cmp::Ordering::Equal => {}
    }
    let artifact_start = r.pos;
    r.take(artifact_len).ok_or_else(truncated)?;
    let artifact = artifact_start..r.pos;

    Ok(Framing {
        key_id,
        signature,
        manifest,
        artifact,
    })
}

/// The exact message an Ed25519 pack signature covers: `SIGNATURE_CONTEXT || manifest`.
pub fn signing_input(manifest: &[u8]) -> Vec<u8> {
    let mut m = Vec::with_capacity(SIGNATURE_CONTEXT.len().saturating_add(manifest.len()));
    m.extend_from_slice(SIGNATURE_CONTEXT);
    m.extend_from_slice(manifest);
    m
}

// ------------------------------------------------------------------------------------------
// Manifest
// ------------------------------------------------------------------------------------------

/// Wire form of the manifest. Unknown members are ignored (forward compatibility); duplicate
/// known members are rejected by serde.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawManifest {
    resource_id: String,
    language_code: String,
    resource_type: String,
    version: String,
    platform: String,
    release_channel: String,
    sha256: String,
    size_bytes: u64,
    signature_key_id: String,
    license_code: String,
    base_resource_id: Option<String>,
    min_app_version: Option<String>,
    // Informational members, type-checked only.
    artifact_url: Option<String>,
    #[allow(dead_code)]
    device_requirements: Option<serde_json::Map<String, serde_json::Value>>,
    #[allow(dead_code)]
    profile: Option<serde_json::Map<String, serde_json::Value>>,
}

fn parse_manifest(bytes: &[u8]) -> Result<Manifest, PackError> {
    use PackRejectReason::{InvalidManifest, UnsupportedResourceType};
    let invalid = |detail| reject(InvalidManifest, detail);

    // A JSON object with no leading whitespace or BOM (serde would also accept an array).
    if bytes.first() != Some(&b'{') {
        return Err(invalid("manifest is not a JSON object"));
    }
    // The whole manifest must be UTF-8, including members this version ignores.
    let text = std::str::from_utf8(bytes).map_err(|_| invalid("manifest is not UTF-8"))?;
    // serde_json limits nesting depth (128); its messages may quote content, so they are
    // deliberately dropped.
    let raw: RawManifest = serde_json::from_str(text)
        .map_err(|_| invalid("manifest JSON is invalid or a required member is missing"))?;

    if !is_uuid(&raw.resource_id) {
        return Err(invalid("resourceId is not a lowercase UUID"));
    }
    if !is_language_code(&raw.language_code) {
        return Err(invalid("languageCode is not a valid language tag"));
    }
    let resource_type = ResourceType::from_wire(&raw.resource_type).ok_or(reject(
        UnsupportedResourceType,
        "resourceType is not supported",
    ))?;
    let version = parse_semver(&raw.version).ok_or(invalid("version is not a semantic version"))?;
    let platform =
        PackPlatform::from_wire(&raw.platform).ok_or(invalid("platform is not supported"))?;
    let release_channel = ReleaseChannel::from_wire(&raw.release_channel)
        .ok_or(invalid("releaseChannel is not supported"))?;
    if !is_sha256_hex(&raw.sha256) {
        return Err(invalid("sha256 is not 64 lowercase hex digits"));
    }
    if !is_valid_key_id(raw.signature_key_id.as_bytes()) {
        return Err(invalid("signatureKeyId is invalid"));
    }
    if raw.license_code.is_empty()
        || raw.license_code.len() > MAX_LICENSE_CODE_LEN
        || !raw.license_code.bytes().all(|b| (0x20..=0x7E).contains(&b))
    {
        return Err(invalid("licenseCode is missing or invalid"));
    }
    if let Some(base) = &raw.base_resource_id {
        if !is_uuid(base) || *base == raw.resource_id {
            return Err(invalid("baseResourceId is invalid"));
        }
    }
    let min_app_version = match &raw.min_app_version {
        Some(v) => Some(parse_semver(v).ok_or(invalid("minAppVersion is not a semantic version"))?),
        None => None,
    };
    if raw
        .artifact_url
        .as_ref()
        .is_some_and(|u| u.len() > MAX_ARTIFACT_URL_LEN)
    {
        return Err(invalid("artifactUrl is too long"));
    }

    Ok(Manifest {
        resource_id: raw.resource_id,
        language_code: raw.language_code,
        resource_type,
        version,
        platform,
        release_channel,
        sha256: raw.sha256,
        size_bytes: raw.size_bytes,
        base_resource_id: raw.base_resource_id,
        min_app_version,
        license_code: raw.license_code,
        signature_key_id: raw.signature_key_id,
    })
}

/// Canonical lowercase UUID `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`.
fn is_uuid(s: &str) -> bool {
    s.len() == 36
        && s.bytes().enumerate().all(|(i, b)| match i {
            8 | 13 | 18 | 23 => b == b'-',
            _ => matches!(b, b'0'..=b'9' | b'a'..=b'f'),
        })
}

/// `^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$`, at most 35 chars (OpenAPI `LanguageCode`).
fn is_language_code(s: &str) -> bool {
    if s.len() > MAX_LANGUAGE_CODE_LEN {
        return false;
    }
    let mut parts = s.split('-');
    let primary_ok = parts
        .next()
        .is_some_and(|p| (2..=3).contains(&p.len()) && p.bytes().all(|b| b.is_ascii_lowercase()));
    primary_ok
        && parts.all(|p| (2..=8).contains(&p.len()) && p.bytes().all(|b| b.is_ascii_alphanumeric()))
}

fn is_sha256_hex(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

/// Semantic version 2.0.0 without build metadata, at most 40 chars. The `semver` crate rejects
/// leading zeros, whitespace and a `v` prefix. Without build metadata, `Ord` is SemVer
/// precedence.
fn parse_semver(s: &str) -> Option<semver::Version> {
    if s.len() > MAX_VERSION_LEN {
        return None;
    }
    let v = semver::Version::parse(s).ok()?;
    v.build.is_empty().then_some(v)
}

/// Lowercase hex encoding.
pub fn to_hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    let mut s = String::with_capacity(bytes.len().saturating_mul(2));
    for b in bytes {
        // Writing to a String cannot fail.
        let _ = write!(s, "{b:02x}");
    }
    s
}

// ------------------------------------------------------------------------------------------
// Verification
// ------------------------------------------------------------------------------------------

/// Verifies a container completely (see the module docs for the order). On success the
/// returned pack owns `bytes`; nothing is copied.
pub fn verify_pack(
    bytes: Vec<u8>,
    keys: &KeyRing,
    limits: &PackLimits,
) -> Result<VerifiedPack, PackError> {
    use PackRejectReason::*;

    let (manifest, manifest_range, artifact_range) = {
        let f = parse_framing(&bytes, limits)?;

        let entry = keys
            .lookup(f.key_id)
            .ok_or(reject(UnknownKey, "signing key is not trusted"))?;
        if entry.status == KeyStatus::Revoked {
            return Err(reject(RevokedKey, "signing key is revoked"));
        }

        let manifest_bytes = bytes
            .get(f.manifest.clone())
            .ok_or(reject(Malformed, "container is truncated"))?;
        let signature = Signature::from_bytes(&f.signature);
        entry
            .key
            .verify_strict(&signing_input(manifest_bytes), &signature)
            .map_err(|_| reject(BadSignature, "manifest signature is invalid"))?;

        // Authenticated from here on.
        let manifest = parse_manifest(manifest_bytes)?;
        if manifest.signature_key_id.as_bytes() != f.key_id {
            return Err(reject(
                KeyMismatch,
                "manifest signatureKeyId differs from the header key id",
            ));
        }
        let artifact = bytes
            .get(f.artifact.clone())
            .ok_or(reject(Malformed, "container is truncated"))?;
        if u64::try_from(artifact.len()).ok() != Some(manifest.size_bytes) {
            return Err(reject(SizeMismatch, "artifact size differs from sizeBytes"));
        }
        if to_hex(&Sha256::digest(artifact)) != manifest.sha256 {
            return Err(reject(
                ChecksumMismatch,
                "artifact SHA-256 differs from sha256",
            ));
        }
        (manifest, f.manifest, f.artifact)
    };

    Ok(VerifiedPack {
        bytes,
        manifest_range,
        artifact_range,
        manifest,
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::indexing_slicing)]
mod tests {
    use super::*;

    #[test]
    fn uuid_rules() {
        assert!(is_uuid("0f8fad5b-d9cb-469f-a165-70867728950e"));
        assert!(!is_uuid("0F8FAD5B-D9CB-469F-A165-70867728950E"));
        assert!(!is_uuid("0f8fad5bd9cb469fa16570867728950e"));
        assert!(!is_uuid("0f8fad5b-d9cb-469f-a165-70867728950"));
        assert!(!is_uuid("0f8fad5b-d9cb-469f-a165_70867728950e"));
    }

    #[test]
    fn language_code_rules() {
        for ok in ["am", "ti", "om", "en", "gez", "ti-ER", "sr-Latn-RS"] {
            assert!(is_language_code(ok), "{ok}");
        }
        for bad in [
            "",
            "a",
            "AM",
            "amha",
            "am-",
            "am-E",
            "am_ET",
            "am-ETHIOPIAN1",
        ] {
            assert!(!is_language_code(bad), "{bad}");
        }
    }

    #[test]
    fn semver_rules() {
        assert!(parse_semver("1.3.0").is_some());
        assert!(parse_semver("1.3.0-beta.2").is_some());
        for bad in [
            "1.3",
            "v1.3.0",
            "01.3.0",
            "1.3.0+build.5",
            " 1.3.0",
            "1.3.0 ",
        ] {
            assert!(parse_semver(bad).is_none(), "{bad}");
        }
        assert!(parse_semver(&format!("1.0.0-{}", "a".repeat(40))).is_none());
        assert!(parse_semver("1.0.0-rc.1").unwrap() < parse_semver("1.0.0").unwrap());
        assert!(parse_semver("1.10.0").unwrap() > parse_semver("1.9.0").unwrap());
    }

    #[test]
    fn hex_encoding() {
        assert_eq!(to_hex(&[0x00, 0x0f, 0xa5, 0xff]), "000fa5ff");
    }

    #[test]
    fn key_id_rules() {
        assert!(is_valid_key_id(b"packs-2026-01"));
        assert!(is_valid_key_id(&[b'a'; 120]));
        assert!(!is_valid_key_id(&[b'a'; 121]));
        assert!(!is_valid_key_id(b""));
        assert!(!is_valid_key_id(b"packs 2026"));
        assert!(!is_valid_key_id("päck".as_bytes()));
    }

    #[test]
    fn resource_type_wire_round_trip() {
        for (t, w) in ResourceType::ALL {
            assert_eq!(ResourceType::from_wire(w), Some(t));
            assert_eq!(t.as_wire(), w);
        }
        assert_eq!(ResourceType::from_wire("keyboard_layout"), None);
    }

    #[test]
    fn limits_validation() {
        let d = PackLimits::default();
        assert!(d.validate().is_ok());
        for bad in [
            PackLimits {
                max_manifest_bytes: 0,
                ..d
            },
            PackLimits {
                max_manifest_bytes: HARD_MAX_MANIFEST_BYTES + 1,
                ..d
            },
            PackLimits {
                max_container_bytes: HARD_MAX_CONTAINER_BYTES + 1,
                ..d
            },
        ] {
            assert!(bad.validate().is_err(), "{bad:?}");
        }
    }

    #[test]
    fn min_container_len_matches_layout() {
        assert_eq!(MIN_CONTAINER_LEN, 89);
    }
}
