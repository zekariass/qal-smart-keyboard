//! Pack signing (feature `builder`): creates format-v1 containers.
//!
//! Used by tests, the fuzz crate and the L1.1 pack build tool. App builds never enable this
//! feature. Never embed real private keys in code: tests derive keys from fixed public seeds,
//! production keys live in KMS/HSM (SD §18) and the backend signs with its own implementation of
//! `docs/specs/pack-format-v1.md`.

use super::{
    is_valid_key_id, signing_input, to_hex, PackPlatform, ReleaseChannel, ResourceType,
    FORMAT_VERSION, MAGIC, SIGNATURE_LEN,
};
use ed25519_dalek::Signer;
pub use ed25519_dalek::SigningKey;
use serde::Serialize;
use sha2::{Digest, Sha256};

/// Why a container could not be built.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
#[error("cannot build pack: {0}")]
pub struct BuildError(pub &'static str);

/// Deterministic test key from a 32-byte seed (RFC 8032 secret key).
pub fn signing_key_from_seed(seed: [u8; 32]) -> SigningKey {
    SigningKey::from_bytes(&seed)
}

/// Manifest fields chosen by the publisher. `sha256`, `sizeBytes` and `signatureKeyId` are
/// derived when the manifest is rendered.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManifestFields {
    pub resource_id: String,
    pub language_code: String,
    pub resource_type: ResourceType,
    pub version: String,
    pub platform: PackPlatform,
    pub release_channel: ReleaseChannel,
    pub license_code: String,
    pub base_resource_id: Option<String>,
    pub min_app_version: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct WireManifest<'a> {
    resource_id: &'a str,
    language_code: &'a str,
    resource_type: &'a str,
    version: &'a str,
    platform: &'a str,
    release_channel: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    min_app_version: Option<&'a str>,
    sha256: String,
    size_bytes: u64,
    base_resource_id: Option<&'a str>,
    license_code: &'a str,
    signature_key_id: &'a str,
}

impl ManifestFields {
    /// Renders compact manifest JSON for `artifact`. The output is just one valid encoding: the
    /// verifier checks the signature over whatever bytes are transmitted, never a re-encoding.
    pub fn to_json(&self, key_id: &str, artifact: &[u8]) -> Result<Vec<u8>, BuildError> {
        let wire = WireManifest {
            resource_id: &self.resource_id,
            language_code: &self.language_code,
            resource_type: self.resource_type.as_wire(),
            version: &self.version,
            platform: self.platform.as_wire(),
            release_channel: self.release_channel.as_wire(),
            min_app_version: self.min_app_version.as_deref(),
            sha256: to_hex(&Sha256::digest(artifact)),
            size_bytes: u64::try_from(artifact.len())
                .map_err(|_| BuildError("artifact too large"))?,
            base_resource_id: self.base_resource_id.as_deref(),
            license_code: &self.license_code,
            signature_key_id: key_id,
        };
        serde_json::to_vec(&wire).map_err(|_| BuildError("manifest serialisation failed"))
    }
}

/// Signs `manifest` (exact bytes) with domain separation and assembles a container.
pub fn sign_container(
    signing_key: &SigningKey,
    key_id: &str,
    manifest: &[u8],
    artifact: &[u8],
) -> Result<Vec<u8>, BuildError> {
    let signature = signing_key.sign(&signing_input(manifest)).to_bytes();
    assemble(key_id.as_bytes(), &signature, manifest, artifact)
}

/// Convenience: render the manifest for `fields` and sign a container.
pub fn build_pack(
    signing_key: &SigningKey,
    key_id: &str,
    fields: &ManifestFields,
    artifact: &[u8],
) -> Result<Vec<u8>, BuildError> {
    let manifest = fields.to_json(key_id, artifact)?;
    sign_container(signing_key, key_id, &manifest, artifact)
}

/// Assembles a container from raw parts without signing (tests use it to forge containers).
pub fn assemble(
    key_id: &[u8],
    signature: &[u8; SIGNATURE_LEN],
    manifest: &[u8],
    artifact: &[u8],
) -> Result<Vec<u8>, BuildError> {
    if !is_valid_key_id(key_id) {
        return Err(BuildError("key id must be 1-120 chars of [A-Za-z0-9._-]"));
    }
    let key_id_len = u8::try_from(key_id.len()).map_err(|_| BuildError("key id too long"))?;
    let manifest_len =
        u32::try_from(manifest.len()).map_err(|_| BuildError("manifest too large"))?;
    if manifest_len == 0 {
        return Err(BuildError("manifest is empty"));
    }
    let artifact_len =
        u64::try_from(artifact.len()).map_err(|_| BuildError("artifact too large"))?;

    let capacity = [
        MAGIC.len(),
        2,
        1,
        key_id.len(),
        SIGNATURE_LEN,
        4,
        manifest.len(),
        8,
        artifact.len(),
    ]
    .iter()
    .try_fold(0usize, |acc, n| acc.checked_add(*n))
    .ok_or(BuildError("container too large"))?;

    let mut out = Vec::with_capacity(capacity);
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&FORMAT_VERSION.to_be_bytes());
    out.push(key_id_len);
    out.extend_from_slice(key_id);
    out.extend_from_slice(signature);
    out.extend_from_slice(&manifest_len.to_be_bytes());
    out.extend_from_slice(manifest);
    out.extend_from_slice(&artifact_len.to_be_bytes());
    out.extend_from_slice(artifact);
    Ok(out)
}
