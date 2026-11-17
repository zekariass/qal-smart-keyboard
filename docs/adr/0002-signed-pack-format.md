# ADR 0002 — Signed language-pack container (format v1)

**Status:** Accepted · **Date:** 2026-11-17 · **Plan step:** C1.2 · **Spec:** [docs/specs/pack-format-v1.md](../specs/pack-format-v1.md)

## Context
System Design §7.6 said a pack's signature covers "the manifest without the signature" as JSON. That
requires the Java signer (B3.2) and the Rust verifier to serialise JSON byte-identically, which is
fragile and tends to end in weakened verification.

## Decision
- A pack is one **self-authenticating binary container**: magic `QALPACK\0`, format version, key id,
  64-byte Ed25519 signature, length-prefixed UTF-8 JSON manifest, length-prefixed artifact. Bundled and
  CDN packs use the same file.
- The signature covers the **exact manifest bytes** with a domain-separation prefix
  (`"QAL-PACK-MANIFEST-v1\0"`), verified with strict Ed25519. Nothing is re-serialised.
- The manifest commits to the artifact (`sha256`, `sizeBytes`) and names its key (`signatureKeyId`
  must equal the header key id).
- The device verifies in a fixed order (size → framing → key → signature → manifest → key-id match →
  size → SHA-256) and reads no manifest field before the signature passes. Revoked keys never verify;
  key validity dates are not checked on device (untrusted clock).
- Rollback protection: a pack at the same or lower version for the same (language, resource type,
  platform) is refused, except an identical re-load (same id, version, sha256), which is a no-op.
- Delta packs are verified but applied by the host pack manager; the core loads only full packs.
- The API's `LanguageResourceManifest` is discovery metadata only; it has no signature member.

## Consequences
- B3.2 must sign with the same input and pass the shared vectors in `core/tests/vectors/pack-v1/`.
- L1.1 builds packs with the core's `builder` feature (never compiled into app builds).
- The core holds no state on disk, so **hosts must persist installed pack versions** and re-load the
  current packs at engine start; otherwise rollback protection only lasts for one engine instance
  (A1.6, A2.3, I5.2).
- `licenseCode` is mandatory in the signed manifest (rule N10), although the DB column is nullable.
