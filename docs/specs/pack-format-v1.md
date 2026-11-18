# Qal language-pack container, format v1

| | |
|---|---|
| Status | Normative. Implemented by `core/src/pack` (plan step C1.2). |
| Implementations | Verifiers: `keyboard-core` (Rust) and the backend publisher (Java, `languages.pack.PackVerifier`, B3.2). Signers: `pack::builder` (Rust, feature `builder`, used by L1.1) and the backend publisher (Java, B3.2, ADR 0019). |
| Test vectors | `core/tests/vectors/pack-v1/*.json` (§11) |
| Related | System Design §7.6, §18; DB tables `resource_signing_keys`, `language_resources`; plan rules N1, N2, N10, N11 |

The key words MUST, MUST NOT, SHOULD and MAY are used as in RFC 2119.

## 1. Goals

1. A pack is activated only if it is **authentic** (signed by a pinned, non-revoked key) and
   **intact** (artifact size and SHA-256 match the signed manifest).
2. Signers and verifiers in different languages agree **byte for byte**: the signature covers
   the exact transmitted manifest bytes. No JSON canonicalisation is ever needed, and a
   verifier MUST NOT verify a re-serialised manifest.
3. Signatures cannot be replayed in another context (domain separation) and are not malleable
   (strict Ed25519 verification).
4. Parsing untrusted input is bounded: hard size limits, no allocation from an unchecked length,
   no crash on any input.
5. The same file works for packs bundled in the app and packs downloaded from the CDN.

## 2. Why a single self-contained container

A pack is **one file** holding the signed manifest and the artifact, not a detached
manifest + artifact pair. Reasons:

- **Bundled and CDN packs are identical.** iOS ships baseline packs inside the keyboard extension
  (SD §6.3) and Android ships seed packs in assets (L1.3). One file per pack means one code path
  and one install step.
- **No pairing errors.** A detached design needs rules for matching a manifest to its artifact
  (and for what happens when they drift). In one file the manifest's SHA-256 and size commit to
  the bytes right next to it.
- **Atomic install is simple.** The host pack manager (A1.6, A2.3) writes one temp file, verifies
  it, and renames it into place; last-known-good is the previous file.
- **The core API stays small.** `load_pack(bytes)` takes one buffer.

The cost is that a client must download the whole container before it can verify it. That is
acceptable: typing packs are ≤ 25 MB (SD §17.1), downloads are resumable, and the decision
*whether* to download is made from the API listing (§10), not from the container.

## 3. Byte layout

All integers are unsigned **big-endian**. There is no padding and nothing after the artifact.

| Offset | Size | Field | Rules |
|---|---|---|---|
| 0 | 8 | `magic` | ASCII `QALPACK` followed by `0x00` (`51 41 4C 50 41 43 4B 00`). |
| 8 | 2 | `format_version` | `0x0001`. |
| 10 | 1 | `key_id_len` | *k*, 1 ≤ *k* ≤ 120. |
| 11 | *k* | `key_id` | ASCII `[A-Za-z0-9._-]`. Names the signing key (`resource_signing_keys.key_id`). |
| 11+*k* | 64 | `signature` | Ed25519 signature `R ‖ S` (RFC 8032), see §4. |
| 75+*k* | 4 | `manifest_len` | *m*, 1 ≤ *m* ≤ manifest limit. |
| 79+*k* | *m* | `manifest` | UTF-8 JSON object, see §5. |
| 79+*k*+*m* | 8 | `artifact_len` | *a* ≤ artifact limit. MUST equal the number of remaining bytes. |
| 87+*k*+*m* | *a* | `artifact` | The resource payload (opaque to this spec). |

Total length = 87 + *k* + *m* + *a*. The smallest possible container is 89 bytes.

Recommended file extension: `.pack`. The artifact format is defined per `resourceType` by the
module that consumes it (C1.3 onwards).

## 4. Signature

```
signed_message = "QAL-PACK-MANIFEST-v1" || 0x00 || manifest
signature      = Ed25519-Sign(private_key, signed_message)        // RFC 8032 "pure" Ed25519
```

- The context prefix is the 21 ASCII bytes `QAL-PACK-MANIFEST-v1` plus one zero byte
  (`51 41 4C 2D 50 41 43 4B 2D 4D 41 4E 49 46 45 53 54 2D 76 31 00`). It binds the signature
  to this purpose and format version: a signature made for anything else (another message
  type, a future v2 context) never verifies here.
- `manifest` is the exact byte sequence in the container. Whitespace, member order and string
  escapes are whatever the signer emitted; the verifier never re-encodes.
- Not Ed25519ph, not Ed25519ctx: the context is a plain message prefix so every standard
  Ed25519 API (JCA `Ed25519`, BouncyCastle, libsodium, OpenSSL) can produce it.
- Ed25519 signing is deterministic, so a conforming signer given the same seed, key id,
  manifest and artifact produces the same container bytes. The test vectors rely on this.
- The artifact is covered indirectly: the manifest commits to its SHA-256 and size (§5).
- The header `key_id` is not signed directly; it is bound by the manifest's `signatureKeyId`,
  which MUST equal it (§6 step 9).

Verifiers MUST use **strict** verification: reject a non-canonical `S` (S ≥ L), a small-order
`R`, a small-order public key, and a non-canonical encoding of `R`. (`ed25519-dalek`
`verify_strict`; in Java use an implementation documented as rejecting `S ≥ L` and
check the vectors in §11.)

## 5. Manifest

### 5.1 Encoding

- UTF-8, no byte-order mark. The first byte MUST be `{` (no leading whitespace); trailing
  whitespace is allowed. Invalid UTF-8 anywhere, including in members the verifier ignores, is
  rejected.
- One JSON object (RFC 8259). Members defined below MUST NOT be duplicated; verifiers reject
  duplicates of the defined members. Nesting depth is limited to 128.
- Unknown members are **ignored** (forward compatibility). A change that old verifiers must not
  silently ignore requires a new `format_version` and signature context.
- Optional members may be absent or `null`.
- Integers are written without fraction or exponent (`26`, not `26.0` or `2.6e1`).

### 5.2 Members

Names follow SD §7.6; the DB column each one feeds is given for the backend.

| Member | Req. | Type and constraints | DB column |
|---|---|---|---|
| `resourceId` | yes | Lowercase canonical UUID `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`. | `language_resources.id` |
| `languageCode` | yes | `^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$`, ≤ 35 chars (OpenAPI `LanguageCode`). | `supported_languages.language_code` |
| `resourceType` | yes | One of `KEYBOARD_LAYOUT`, `TRANSLITERATION_RULES`, `DICTIONARY`, `FREQUENCY_LIST`, `PREDICTION_MODEL`, `AUTOCORRECTION_MODEL`, `PROTECTED_TERMS`, `PRONUNCIATION`, `EMOJI_KEYWORDS`, `GLOSSARY`, `SPELL_MODEL`, `LANGUAGE_ID_MODEL`, `TRANSLATION_MODEL`, `ASR_MODEL`, `TTS_VOICE`, `ON_DEVICE_LLM`, `OTHER`. | `resource_type` |
| `version` | yes | SemVer 2.0.0, ≤ 40 chars, **no build metadata** (`+…`), no leading zeros, no `v` prefix. Pre-release allowed. | `resource_version` |
| `platform` | yes | `SHARED`, `ANDROID` or `IOS`. | `platform` |
| `releaseChannel` | yes | `INTERNAL`, `BETA` or `STABLE`. | `release_channel` |
| `sha256` | yes | SHA-256 of the artifact, 64 lowercase hex digits. | `sha256_checksum` |
| `sizeBytes` | yes | Artifact length in bytes, integer ≥ 0. | `size_bytes` |
| `licenseCode` | yes | 1–60 printable ASCII chars (0x20–0x7E); SPDX id or `LicenseRef-…`. Required so every shipped pack carries its licence (N10). | `license_code` |
| `signatureKeyId` | yes | Same syntax as the header `key_id`; MUST equal it. | `resource_signing_keys.key_id` |
| `baseResourceId` | no | Lowercase UUID ≠ `resourceId`. Non-null means the artifact is a **delta** (§8.3). | `base_resource_id` |
| `minAppVersion` | no | SemVer as for `version`. Enforced by the host (the core does not know the app version). | `min_app_version` |
| `artifactUrl` | no | String ≤ 2048 chars. Informational only; never used for verification. | `artifact_uri` |
| `deviceRequirements` | no | JSON object (e.g. `{"minRamMb":0}`); interpreted by the host. | `device_requirements` |
| `profile` | no | JSON object (transliteration profile metadata, SD §7.6); interpreted by C1.4. | — |

The manifest no longer contains a `signature` member: the signature lives in the container
header. If present, it is an unknown member and ignored.

`language_resources.manifest_json` stores the manifest; the backend MUST also keep the exact
signed bytes (or the whole container) because the signature covers bytes, not JSON values.

The backend publisher (B3.2, ADR 0019) re-signs every pack it stages or publishes with the server
key: it sets `releaseChannel`, `minAppVersion` and `signatureKeyId` for the channel it serves the
pack on and drops `artifactUrl`. `resourceId`, `version` and the artifact (`sha256`, `sizeBytes`)
never change, so a host treats the re-signed pack as the same pack (§8.1).

## 6. Verification procedure

A verifier performs these steps **in order** and stops at the first failure, reporting the
reason code shown. No manifest member is interpreted before step 7 succeeds. When an input has
several faults, the first failing step determines the code (the test vectors depend on this).

| Step | Check | Reason code |
|---|---|---|
| 1 | Container length ≤ container limit. | `TOO_LARGE` |
| 2 | `magic` present and correct. | `MALFORMED` |
| 3 | `format_version` = 1 (a container too short to hold it is `MALFORMED`). | `UNSUPPORTED_FORMAT_VERSION` |
| 4 | `key_id_len` ≥ 1, `key_id` present and well-formed, 64 signature bytes present. | `MALFORMED` |
| 5 | `manifest_len` ≥ 1 (`MALFORMED`), ≤ manifest limit (`TOO_LARGE`), and that many bytes present (`MALFORMED`). | `MALFORMED` / `TOO_LARGE` |
| 6 | `artifact_len` ≤ artifact limit (`TOO_LARGE`) and exactly equal to the remaining length: fewer bytes is truncation, more is trailing data (`MALFORMED`). | `TOO_LARGE` / `MALFORMED` |
| 7a | `key_id` is a trusted key. | `UNKNOWN_KEY` |
| 7b | That key is not `REVOKED`. | `REVOKED_KEY` |
| 7c | Strict Ed25519 verification of `signature` over `signed_message` (§4). | `BAD_SIGNATURE` |
| 8 | Manifest encoding (§5.1) and members (§5.2), in table order; an unknown `resourceType` is `UNSUPPORTED_RESOURCE_TYPE`, every other violation (including a missing `resourceType`) is `INVALID_MANIFEST`. | `INVALID_MANIFEST` / `UNSUPPORTED_RESOURCE_TYPE` |
| 9 | `signatureKeyId` = header `key_id`. | `KEY_MISMATCH` |
| 10 | Artifact length = `sizeBytes`. | `SIZE_MISMATCH` |
| 11 | SHA-256(artifact) = `sha256`. | `CHECKSUM_MISMATCH` |

Lengths read in steps 4–6 are compared against limits and the actual buffer length **before**
use; a verifier MUST NOT allocate memory sized by an unchecked length field. Steps 1–6 only
slice the input; the only allocation before step 7c is the signed message, bounded by the
manifest limit.

A container that passes all steps is **verified**. Activation additionally applies the engine
policy in §8.

## 7. Keys

### 7.1 Trusted key set

The app pins public keys (SD §7.6, §18) and passes them to the core in
`EngineConfig.trusted_keys`, each `{ key_id, public_key (32 raw bytes), status }`. Engine
construction fails with `CoreError::InvalidConfig` if any key:

- has an id that is not 1–120 chars of `[A-Za-z0-9._-]`, or an id listed twice;
- is not exactly 32 bytes, does not decode to an Ed25519 point, or is of small order;
- repeats a public key already listed under another id (so a revocation cannot be bypassed
  through an alias).

### 7.2 Status semantics (`resource_signing_keys.key_status`)

| Status | Verifies? | Use |
|---|---|---|
| `ACTIVE` | yes | Signs new packs. |
| `RETIRING` | yes | No new packs; packs it signed stay valid until re-signed or retired. |
| `REVOKED` | **never** | Rejected with `REVOKED_KEY` before the signature is checked. |

An id that is not in the set is `UNKNOWN_KEY`. Time-based validity (`valid_from`,
`valid_until`) is not checked by the core: the device clock is untrusted. The host moves a key
to `REVOKED` (or drops it) instead.

### 7.3 Rotation and revocation

1. Publish the next key in `resource_signing_keys` (status `ACTIVE`) and ship it pinned in the
   app **before** using it, together with the current key (SD §7.6).
2. Sign new packs with the next key; mark the old key `RETIRING`.
3. Re-sign still-published packs with the new key (same manifest content, same `version` and
   artifact: the engine treats them as identical, §8.1), then mark the old key `REVOKED`.
4. On suspected compromise, mark the key `REVOKED` immediately and push the new trusted set to
   apps. Keys announced by `GET /resource-signing-keys` are accepted by the host only when the
   announcement is endorsed by an already-trusted key (OpenAPI `endorsementSignature`; host
   side, A2.3; format in §7.4).

### 7.4 Key endorsements

Defined by B3.2 (ADR 0019). An endorsement lets an already trusted key vouch for an additional key:

```
message   = "QAL-KEY-ENDORSEMENT-v1" || 0x00 || u8 len(keyId) || keyId || publicKey
signature = Ed25519-Sign(endorsing private key, message)          // RFC 8032 "pure" Ed25519
```

- `keyId` is the endorsed key id (ASCII, same syntax as the header `key_id`, so its length fits
  in one byte); `publicKey` is its raw 32-byte Ed25519 public key.
- `GET /resource-signing-keys` carries the endorsing key id in `endorsedByKeyId` and the 64
  signature bytes, standard base64 with padding, in `endorsementSignature`.
- A host accepts an announced key only if the endorsing key is one it already trusts and is not
  `REVOKED`, the signature verifies **strictly** (§4), and the announced key passes the §7.1
  checks (valid point, not small order, no id or public key already listed).
- The endorsement covers the key id and public key, not the status. A host may apply a
  `REVOKED` status from any announcement (that only removes trust); it never trusts a key, or
  lifts a revocation, without an endorsement.
- The context prefix differs from §4 (`QAL-PACK-MANIFEST-v1`), so a pack signature can never
  pass as an endorsement or the other way round.
- The backend endorses with its signing key when a key is registered
  (`POST /admin/v1/resource-signing-keys`). Keys registered with `endorse: false` (for example a
  build pipeline key that may only sign uploads) have no endorsement and are never trusted by
  hosts.

## 8. Engine policy (activation)

### 8.1 Rollback protection

The engine keeps at most one pack per **slot** = (`languageCode`, `resourceType`, `platform`).
For a verified, non-delta pack:

- slot empty, or new `version` **greater** (SemVer 2.0.0 precedence: numeric fields compared
  numerically, a pre-release is lower than its release) → activate, replacing the old pack;
- same `version`, same `resourceId` and same `sha256` → **idempotent success**: nothing changes
  and the already loaded pack's info is returned (covers reloading, and the same pack re-signed
  by a new key);
- otherwise (lower version, or same version with different content) → `DOWNGRADE`.

This state lives in the engine instance only: the core performs no I/O (N1). The host pack
manager MUST persist the highest installed version per slot and refuse to install a lower one
from the network; its last-known-good rollback (A1.6, A2.3) is a deliberate host action done by
creating a new engine and loading the previous file.

### 8.2 What the host still enforces

The core cannot know them, so the host checks `minAppVersion`, `releaseChannel` (e.g. no
`INTERNAL` packs in production builds), `platform` (an `IOS` pack is not installed on Android)
and `deviceRequirements` before installing. All are available in `PackInfo` or the API listing.

### 8.3 Delta packs

A pack with non-null `baseResourceId` is verified completely (so tampering is still
detected), then refused with `DELTA_NOT_SUPPORTED`. Deltas are applied by the host pack
manager (P5.4): it verifies the delta container, applies it to the installed base, and loads
the result, which MUST itself be a **full, signed** pack. The core therefore never activates
bytes whose signed hash it has not checked. The patch format is out of scope for v1.

## 9. Limits

| Limit | Default | Configurable | Hard cap |
|---|---|---|---|
| Container | 32 MiB + 128 KiB (33 685 504 bytes) | `PackLimits.max_container_bytes` | 2³¹ − 1 |
| Manifest | 64 KiB (65 536 bytes) | `PackLimits.max_manifest_bytes` | 1 MiB |
| Artifact | 32 MiB (33 554 432 bytes) | `PackLimits.max_artifact_bytes` | container cap |

All limits must be non-zero. The artifact default covers typing packs (≤ 15 MB Ethiopic,
≤ 25 MB English per language, SD §17.1). Model packs (E7.2) need an engine configured with
higher limits.

## 10. Errors and privacy

Rejections surface as `CoreError::PackRejected { reason: PackRejectReason, detail }`. `reason`
is machine-readable (codes in §6 and §8: `MALFORMED`, `TOO_LARGE`,
`UNSUPPORTED_FORMAT_VERSION`, `UNKNOWN_KEY`, `REVOKED_KEY`, `BAD_SIGNATURE`,
`INVALID_MANIFEST`, `UNSUPPORTED_RESOURCE_TYPE`, `KEY_MISMATCH`, `SIZE_MISMATCH`,
`CHECKSUM_MISMATCH`, `DOWNGRADE`, `DELTA_NOT_SUPPORTED`, and `INVALID_ARTIFACT` for a verified
pack whose artifact fails validation for its resource type, e.g. a `KEYBOARD_LAYOUT` per
`keyboard-layout-v1.md`). `detail` is a fixed English sentence
and never contains pack content, key ids or parser messages (N2). Hosts may log the reason
code.

The API's `LanguageResourceManifest` (OpenAPI) is **discovery metadata**: it tells the host
what to download. It is not authoritative; the signed manifest inside the container is. The API
schema therefore has no `signature` member; its `sha256`/`sizeBytes` repeat the signed artifact
values and `containerSizeBytes` gives the file size for download planning.

## 11. Test vectors

`core/tests/vectors/pack-v1/*.json`, one vector per file:

```json
{
  "format": "qal-pack-v1-test-vector",
  "name": "valid-minimal",
  "description": "…",
  "trustedKeys": [ { "keyId": "test-active-1", "status": "ACTIVE", "publicKeyHex": "…" } ],
  "limits": { "maxContainerBytes": 33685504, "maxManifestBytes": 65536, "maxArtifactBytes": 33554432 },
  "input": { "signingKeyId": "…", "signingSeedHex": "…", "manifestUtf8": "…", "artifactHex": "…" },
  "containerHex": "…",
  "expected": { "verifies": true, "load": "ACCEPT", "packInfo": { … } }
}
```

- `input` (present when the container is exactly a signer's output): a conforming **signer**
  given the 32-byte Ed25519 seed, key id, UTF-8 manifest bytes and artifact MUST produce
  exactly `containerHex`.
- `expected.verifies`: result of §6 with `trustedKeys` and `limits`.
- `expected.load`: result of loading the container into a fresh engine (`ACCEPT` with
  `packInfo`, or `REJECT` with `reason`).
- Keys are **test keys** from fixed public seeds (`00 01 … 1f` for `test-active-1`, `20 … 3f`
  for `test-retiring-1`, `40 … 5f` for `test-revoked-1`, `60 … 7f` for the untrusted
  `test-untrusted-1`). They MUST never be trusted by a production build.

The Rust test `core/tests/pack_vectors.rs` regenerates and checks them
(`QAL_REGEN_PACK_VECTORS=1 cargo test --test pack_vectors`). The vectors were also checked
against an independent Ed25519 implementation (Python `cryptography`/OpenSSL) when they were
created.

## 12. Versioning

`format_version` and the signature context change together. A v1 verifier rejects any other
version with `UNSUPPORTED_FORMAT_VERSION`; apps that must read both carry both verifiers.
Additive optional manifest members do not need a new version.
