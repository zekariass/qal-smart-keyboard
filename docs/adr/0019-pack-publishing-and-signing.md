# ADR 0019 — Admin pack publishing and server-side pack signing

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B3.2 (depends on B3.1, C1.2, L1.2) · **Spec:**
`docs/specs/pack-format-v1.md` (sections 5, 6, 7.4 new), SD §6.5, §7.6, §18, §21; Review D25; ADR 0002, ADR 0007 D10/D19/D21,
ADR 0013 D8; contract tag `Admin`; V1 `language_resources`, `resource_signing_keys`, `language_datasets`, `admin_roles`,
`user_admin_roles`, `audit_events`; V15 `language_resource_datasets`

## Context
B3.1 serves `PUBLISHED` rows of `language_resources`. Nothing produced those rows: packs are built and signed with a
per-checkout dev key (L1.1, L1.3), production private keys must live in a KMS used by the backend (ADR 0007 D10), every
shipped pack needs a commercial licence (N10), rules packs must carry the backend `profile_code` (D21), and publishing to
STABLE needs a second admin (Review D25). The admin console (SD §6.5) needs `/admin/v1` operations for upload, sign,
stage, publish and roll back.

## Decisions

### D1. Admin authentication and authorisation (interim)
SD §9.2/§18 require workforce OIDC with MFA and an IP allow-list for admins; none exists yet. Until it does:
- `/admin/v1/**` accepts the app's ES256 access token (same security chain), and `AdminAccess` requires a **registered,
  ACTIVE** user with an unexpired `user_admin_roles` assignment whose `admin_roles.permissions` contain the operation's
  permission (or `*`). Guests are never admins. The V1 roles are used as seeded: `resources:upload` (datasets, upload),
  `resources:edit` (stage), `resources:publish` (publish, retire), `resources:approve` (STABLE approval),
  `languages:read` (read), and a new permission string `signing_keys:manage` that only SUPER_ADMIN's `*` grants.
- The whole surface is **off** unless `qal.admin.enabled=true` (`QAL_ADMIN_ENABLED`); off, every admin operation is 404.
  This keeps "consumer credentials never grant admin roles" (SD §9.2) true by default; the switch is for development and
  internal environments until workforce OIDC lands (follow-up, then drop the switch).
- **Audit:** every state-changing action writes one `audit_events` row (`actor_type ADMIN`, actor, action code, entity
  type and id, outcome `SUCCESS`/`FAILURE`/`DENIED`, correlation id, metadata of ids/codes/versions only — never pack
  content, tokens or secrets). Refusals are written in their own transaction (`REQUIRES_NEW`) so they survive the rollback.
  Reads are audited only when denied.

### D2. Upload: raw body, verified before anything is stored
`POST /admin/v1/language-resources` takes the `.pack` container as `application/octet-stream` (query `datasetCode`,
repeatable). Raw body instead of multipart (no global multipart limits, no temp files, the size cap is enforced by reading
at most `max-container-bytes + 1` bytes, 413 above) and instead of a pre-signed PUT to MinIO (nothing unverified ever
lands in the bucket, one round trip, no orphaned objects to scan). Default cap: the format's container limit
(33 685 504 bytes). Only a pack that passes verification and policy is written to object storage
(`uploads/<resourceId>/<container sha256>.pack`, write credential `qal.languages.publishing.storage.*`, separate from
B3.1's read credential) and recorded as a `DRAFT` row (`artifact_uri = s3://bucket/key`, `container_size_bytes`,
artifact `sha256`/`size_bytes`, the upload signature and key, `manifest_json`).

### D3. Java verifier with the core's exact order
`languages.pack.PackVerifier` ports `verify_pack`: size, framing, key (unknown, revoked), strict Ed25519, manifest,
key-id match, size, SHA-256, with the same reason codes. Strictness is explicit, not left to the JDK: `S < L`, canonical
`R` and public key, small-order `R`/public key rejected, then JDK `Signature "Ed25519"`. The manifest parser mirrors
serde's behaviour (UTF-8, `{` first, duplicates of defined members, u64 integers only, unknown members ignored at any
depth, `deviceRequirements`/`profile` at most 126 levels deep). It passes all 20 cross-language vectors in
`core/tests/vectors/pack-v1/` (verification outcome and reason, pack info) and reproduces every signer `input` byte for
byte. The trusted key set is `resource_signing_keys` (ACTIVE and RETIRING verify, REVOKED never).

### D4. Publishing policy
After verification an upload must: not be a delta (`DELTA_NOT_SUPPORTED`, until P5.4); be signed by an `ACTIVE` key
(`KEY_NOT_ACTIVE`: RETIRING keys sign no new packs, spec 7.2); name a catalogue language; carry a licence that passes the
allow-list (`LICENSE_MISSING`/`LICENSE_UNKNOWN`/`LICENSE_NOT_COMMERCIAL`); name at least one registered dataset, each with
an allow-listed licence, `COMMERCIAL` in `permitted_uses`, no `RESEARCH_ONLY`, not `REJECTED`/`RESTRICTED`
(`DATASET_*`); for `TRANSLITERATION_RULES`, have an artifact `profileId` (and manifest `profile.code`, if any) equal to a
non-retired backend `profile_code` of the language (`PROFILE_UNKNOWN`/`PROFILE_MISMATCH`, D21 — the publisher checks the
code, it does not rewrite artifacts); and be newer (SemVer precedence) than every version of its (language, type,
platform) slot that was ever released, retired ones included (`DOWNGRADE`). Stage and publish re-check licence, datasets,
profile and downgrade. Refusals are `422 PACK_REJECTED` with a `reason` extension member.
- **One licence source of truth:** `language-data/licenses.json`. The backend loads it from
  `qal.languages.publishing.license-allow-list` or from a classpath copy that a unit test keeps byte-identical to the
  language-data file; the rules (`commercialUse: true` only, unknown fails closed, any `NC` component refused) are those
  of `build_packs.py`.
- **Datasets:** `POST /admin/v1/language-datasets` accepts the build's `datasets.manifest.json` as is and inserts new
  codes as `PENDING_REVIEW` with `permitted_uses = [COMMERCIAL]`; an existing code must keep its licence. V15
  `language_resource_datasets` links each resource to its datasets so release re-checks them.

### D5. State machine (no new status)
V1 statuses only. `STAGED` = `PUBLISHED` on channel `INTERNAL` (B3.1 serves it to internal builds only, rollout 100):
`upload → DRAFT → stage → STAGED → publish(BETA|STABLE, rollout 0-100, minAppVersion) → PUBLISHED`, repeat publish on
the same or a wider channel (rollout changes, re-sign after rotation), `retire` from DRAFT/STAGED/PUBLISHED. Publishing
straight from DRAFT, narrowing a channel, and acting on a retired row are `409 PUBLICATION_CONFLICT`. A rules resource
backing an ACTIVE profile cannot be retired (V1 trigger 19.2). Staging links DRAFT profiles of the code to the staged
rules pack (so a later profile activation finds a published rules resource, V1 trigger 19.1); publishing to BETA/STABLE
requires an ACTIVE profile (N9) and links ACTIVE profiles. STABLE needs a prior `stable-approval` by another admin
(Review D25), stored as an audit row and copied to the publish row's `approved_by_user_id`; otherwise
`403 APPROVAL_REQUIRED`. Each publication change writes the outbox event `language-resource.publication-changed`;
discovery has no cache of its own, so this event is the hook for CDN purges.

### D6. Server-side signing: re-sign on stage and publish
Stage and publish read the stored container back, check its artifact against the row's `sha256`/`size_bytes` (the
database is the integrity anchor, not the bucket), rebuild the manifest from `manifest_json` with `releaseChannel` =
target channel, `minAppVersion` = the published one, `signatureKeyId` = server key and no `artifactUrl`, sign it with the
server key, verify the result with that key alone, and store it under
`packs/<lang>/<type>/<platform>/<version>/<id>-<sha prefix>.pack`. So the served container always carries the
production signature (D10) and a signed channel matching where it is served (hosts refuse e.g. INTERNAL packs in
release builds, spec 8.2), while resource id, version and artifact are unchanged (devices treat it as the same pack,
spec 8.1). The upload signature only proves the pack came from a trusted builder (dev or pipeline key registered with
`endorse=false`). The server key must be registered `ACTIVE` with the same public key, or stage/publish is 409.

### D7. `PackSigner` port, local signer now, KMS seam
`PackSigner { keyId(); publicKey(); sign(message) }` (raw pure Ed25519; callers only sign through `PackWriter`, context
`QAL-PACK-MANIFEST-v1\0`, and `KeyEndorsement`, context `QAL-KEY-ENDORSEMENT-v1\0`).
- `LocalEd25519PackSigner`: JDK Ed25519 from a configured 32-byte seed (`QAL_PACK_SIGNING_KEY_ID`,
  `QAL_PACK_SIGNING_PRIVATE_KEY`, base64 or hex) with a sign/verify probe at startup; `allow-ephemeral` (dev profile and
  tests) generates one per JVM and is **refused under prod/production/staging**. With no key, an
  `UnconfiguredPackSigner` makes stage/publish fail (fail closed; a warning is logged in production-like profiles).
- **KMS seam** (ADR 0013 D8 pattern, no SDK now): `provider=kms` builds `KmsPackSigner` over a `KmsSigningClient`
  (`publicKey(keyName)`, `signEd25519(keyName, message)`). Adapters: Google Cloud KMS `AsymmetricSign` with an
  `EC_SIGN_ED25519` key, or AWS KMS `Sign` where Ed25519 keys are offered; other providers need a different algorithm and
  are out of scope. The public key is read and probed at startup and every KMS signature is strictly verified before use.
  Without an adapter bean `provider=kms` fails at startup.

### D8. Key registration, endorsement, rotation
`POST /admin/v1/resource-signing-keys` registers public keys only (valid point, not small order, unique id and public
key). The server's own key needs no endorsement (it is pinned in app builds); any other key is endorsed by the server key
per the new spec §7.4 unless `endorse=false` (upload-only keys). `.../retire` (ACTIVE → RETIRING) and `.../revoke`
(ACTIVE/RETIRING → REVOKED) only move forward. Rotation: register the next key (endorsed by the current one), switch the
signer configuration to it, retire the old key, re-publish live packs (re-signs them), revoke the old key.

## Consequences
- Contract: new tag `Admin` (`x-internal`), 11 operations under `/admin/v1`, schemas `AdminLanguageResource`,
  `PublishLanguageResourceRequest`, `RegisterLanguageDatasetsRequest`, `LanguageDatasetRecord`, `LanguageDataset(List)`,
  `PackSigner`, `RegisterSigningKeyRequest`, `AdminSigningKey`, response `PackRejected`, parameter `SigningKeyIdPath`,
  Problem codes `PACK_REJECTED`, `PUBLICATION_CONFLICT`, `APPROVAL_REQUIRED`. SD §14.1 says `/admin/v1` is "not in the
  public contract"; it is in the contract file but marked `x-internal` so it is validated by the same tests (reviewer to
  confirm, or move to a separate admin contract when the admin console is built).
- Migration V15 (`language_resource_datasets`); V14 is left to B5.3.
- The A2.3 host side must implement endorsement verification exactly as spec §7.4.
- Seed packs signed with the per-checkout dev key upload fine (key registered with `endorse=false`); the seed rules packs
  are refused until `language-data` renames profiles to the canonical codes (D21).

## Open
- Workforce OIDC + MFA + IP allow-list for `/admin/v1`, then remove `qal.admin.enabled`.
- Whether STABLE also needs datasets with `review_status = APPROVED` (no dataset review endpoint yet).
- Deleting objects of retired drafts (uploads stay in the bucket; no lifecycle rule yet).
- Delta packs (P5.4) and an admin list/search endpoint for the console.
