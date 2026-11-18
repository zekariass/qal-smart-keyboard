# backend

Spring Boot 4 modular monolith (Java 25). Modules per SD section 24. Flyway V1__baseline.sql in src/main/resources/db/migration. Plan steps B*.x, M4.x.

See docs/Ethiopian_AI_Keyboard_Implementation_Plan_v2_0.md for the steps that populate this folder.

## Platform conventions (B1.3)

- **Errors:** every error is an RFC 9457 `application/problem+json` body matching `Problem` in `contracts/openapi.yaml`.
  Modules throw `com.qalsmartkeyboard.platform.ApiException(ApiErrorCode, HttpStatus, detail)`; never put user text in
  `detail` or extension members. Bean Validation, malformed JSON, 404, 405, 415 and unexpected exceptions are mapped
  by `platform.web.ProblemExceptionHandler`. `ApiErrorCode` mirrors the code list in the contract.
- **Correlation ID:** `platform.web.CorrelationIdFilter` (highest precedence) keeps a well-formed inbound
  `X-Correlation-Id` (1-100 chars of `A-Za-z0-9._:-`) or generates a UUID, sets MDC key `correlationId`, echoes the header on every
  response and uses it as `correlationId` in Problems. Read it with `CorrelationId.current()`.

## User-data encryption (B1.5, ADR 0005)

User content stored server-side (`*_ciphertext` columns) is sealed with
`com.qalsmartkeyboard.platform.crypto.UserDataEncryption`: AES-256-GCM under a per-user data key that is kept KMS-wrapped
in `user_data_keys`, created on first use, and bound to the user, key version and storage location.

```java
UUID id = UUID.randomUUID(); // generate the row id first: it is part of the associated data
EncryptionContext ctx = EncryptionContext.of("user_destination_profiles", "label_ciphertext", id);
EncryptedValue sealed = encryption.encryptString(userId, ctx, label);
// INSERT ... (id, label_ciphertext, key_version) VALUES (id, sealed.ciphertext(), sealed.keyVersion())
String label = encryption.decryptString(userId, ctx, row.labelCiphertext());
```

- `rotateKey(userId)` starts a new key version. Old values still decrypt, and existing rows are not re-encrypted.
- `reencrypt(from, ctx, ciphertext, to, ctx)` / `reencryptRow(...)` move a value to another user (guest merge, B2.6):
  decrypt and seal again for the new owner. Ciphertext is bound to its user, so copying the bytes never works.
- `cryptoShred(userId)` (privacy, account deletion) destroys all of the user's keys. It is idempotent and joins the
  caller's transaction. Afterwards decrypt, encrypt and rotate throw `CryptoShreddedException`, and a shredded user
  never gets a new key.
- Failures are `CiphertextRejectedException` (tampered, moved, malformed) and `KeyManagementException`. Never put
  their messages in a client-visible Problem.
- **KMS configuration (required, there is no default):** `qal.crypto.kms.provider` / env `QAL_KMS_PROVIDER`.
  - Local development: `QAL_KMS_PROVIDER=local` and `QAL_LOCAL_KMS_KEK=$(openssl rand -base64 32)`. Keep the same
    KEK across restarts, or the data you stored cannot be read. The local KMS refuses to start under the
    `prod`/`production`/`staging` profiles.
  - Tests: `src/test/resources/config/application.yaml` turns on the local KMS with an ephemeral per-JVM KEK
    (`qal.crypto.local-kms.allow-ephemeral-kek=true`, dev/test only) for every `@SpringBootTest`.
  - Cloud: `provider=cloud` and `qal.crypto.cloud-kms.key-name`, plus a `CloudKmsClient` bean. None exists yet, so
    startup fails (ADR 0005 shows how GCP or AWS KMS plugs in).
- Cache of unwrapped keys: `qal.crypto.dek-cache.max-entries` (10000) and `qal.crypto.dek-cache.ttl` (5m).

## Outbox (B1.4)

Modules publish domain events through the `platform` named interface `outbox`
(`com.qalsmartkeyboard.platform.outbox`); persistence, dispatcher and scheduler wiring are package-private.

```java
@Transactional
void activate(...) {
    // domain change ...
    outboxPublisher.publish("subscription", subscriptionId, "subscription.activated", Map.of("subscriptionId", subscriptionId));
}
```

- `OutboxPublisher.publish` throws `IllegalStateException` outside a transaction, so the event exists iff the domain change commits.
  Ids are UUIDv7. **Payloads and `aggregateId` carry ids/metadata only, never user text** (keystrokes, drafts, transcripts, prompts, names).
- Consume by declaring an `OutboxEventHandler` bean (`consumerName()`, `eventType()`, `handle(event)`).
- Dispatch: a db-scheduler recurring task (`outbox-dispatcher`, core library; its Spring Boot starter targets Boot 3) claims due rows with
  `FOR UPDATE SKIP LOCKED`, marks them PROCESSING with a lease, runs the handlers, then PUBLISHED. Safe on several instances.
  There is no ordering guarantee between events.
- Failure: `attempts` increments on every claim (a crash counts), the error class name (never the message) is stored in `last_error`,
  `available_at` moves by exponential backoff with equal jitter (`base-backoff` doubling, capped by `max-backoff`), and the event is DEAD at `max_attempts` (default 10).
  A PROCESSING row whose `locked_until` has passed is reclaimed.
- **Idempotent consumers:** delivery is at-least-once, but before calling a handler the dispatcher inserts `(consumerName, eventId)` into
  `processed_events` in the same transaction as the handler's writes; a redelivery is skipped and a handler failure rolls both back.
  Writes through the Spring-managed transaction are therefore applied once per event id. External side effects must use `event.id()` as an
  idempotency key. Keep `consumerName` stable. `processed_events` purge belongs to B6.3.
- Config (`qal.outbox.*`): `dispatcher-enabled` (true), `poll-interval` (1s), `batch-size` (20), `lease` (60s; must exceed handler time), `base-backoff` (5s), `max-backoff` (1h).
- **Shared scheduler (B4.2):** any module can run a recurring job on the same db-scheduler instance by declaring a
  db-scheduler `RecurringTask` bean (one instance at a time across the cluster; the job itself must still be idempotent).
  `dispatcher-enabled=false` only leaves the outbox task out; `poll-interval` is the scheduler's polling interval.
- Metrics: `qal.outbox.attempts{outcome=published|failed|dead|lease_reclaimed}`, `qal.outbox.duplicates.skipped`, gauges `qal.outbox.pending`, `qal.outbox.dead`.
  Logs carry event id and type only.
- Schema: `V2__outbox_scheduler.sql` adds db-scheduler's `scheduled_tasks` and `processed_events`.

## Configuration (B1.6)

Module `configuration`: `GET /api/v1/config` and the app-version gate. Both read an in-memory snapshot of
`feature_flags` and `app_configurations` that is reloaded after `qal.config.cache-ttl` (default 30s). Edits to those
tables become visible within the TTL; explicit invalidation comes with the admin module. If a reload fails the previous
snapshot keeps being served.

- **Values:** active `app_configurations` rows of scope `ALL`, overridden per key by the caller's platform row
  (`BACKEND` rows are never served). Reserved keys `minSupportedAppVersion` and `recommendedAppVersion` (JSON strings) fill the
  top-level fields; no `minSupportedAppVersion` row means `0.0.0` (nothing is rejected). `configVersion` is the highest
  `config_version` among the applied rows (0 if none).
- **Flags:** every flag is listed; it is `true` only if enabled, its `platform_scope` is `ALL` or the caller's platform, the
  caller's version meets `min_app_version` (unknown version: not met), `targeting` is empty and the rollout admits the caller.
  Targeted flags (markets/plans/channels) stay `false` until an authenticated context exists (B2.4). Partial rollout
  (`rollout_percentage` 1-99) is deterministic: `SHA-256(flagCode + ":" + subjectKey)` first 4 bytes mod 100 below the percentage.
  The subject key (user id, else device id) is not available yet, so partial rollouts are `false` for now.
- **Version gate (426):** an MVC interceptor on `/api/v1/**` compares `X-App-Version` with the platform's minimum and throws
  `APP_VERSION_UNSUPPORTED` (426, extension member `minSupportedAppVersion`). Exempt: `/api/v1/config` (old clients must be able
  to learn they have to update), `/api/v1/billing/webhooks/**` and `/api/v1/billing/store-notifications/**` (provider/store
  callbacks). `/partner/v1/**` and `/actuator/**` are not covered. The contract makes all client headers optional, so:
  no `X-App-Version` means not enforced; a malformed `X-App-Version` or an `X-Client-Platform` other than `ANDROID|IOS|WEB`
  is 400 `VALIDATION_FAILED`; no platform means the `ALL` minimum applies; an unreadable configuration fails open (logged).
  The headers are client-asserted: this is a compatibility gate, not a security control.
- **Version comparison** is SemVer precedence (`1.10.0 > 1.9.0`); `2.1` means `2.1.0`; a pre-release is below its release
  (`2.0.0-beta.1 < 2.0.0`); build metadata (`+...`) is ignored. See `AppVersion`.
- `/config` is anonymous in the security chain (B2.4). Protected endpoints authenticate first, so an anonymous call
  to one gets 401 before the version gate runs.
- `/config` never fails on bad client headers (the contract declares no 400 for it): they count as unknown.

## Languages and language packs (B3.1)

Module `languages`: the five read operations `GET /languages`, `GET /languages/{code}/transliteration-profiles`,
`GET /language-resources`, `GET /language-resources/{resourceId}`, `GET /resource-signing-keys`. All are optional-bearer
(anonymous, or a valid token; an invalid token is 401). Publishing and signing are B3.2; this module only reads.

- **Pack discovery** returns `LanguageResourceManifest` (discovery only, no signature: the signed manifest travels inside the
  `.pack`, `docs/specs/pack-format-v1.md`). Served rows: `publication_status = PUBLISHED`, active language, a signing key that is
  not `REVOKED` (`ACTIVE` and `RETIRING` verify), a known `size_bytes`, and a resource type of the contract enum (which equals the V1 CHECK
  list, `GLOSSARY` included since ADR 0007 D20). `appVersion` (required) must be at least `min_app_version` (SemVer
  precedence, `1.10.0 > 1.9.0`); `platform` ANDROID sees SHARED and ANDROID packs, IOS sees SHARED and IOS, WEB sees SHARED.
- **Release channels:** `channel` defaults to `STABLE`; a channel also sees every more stable one (BETA sees BETA and STABLE),
  because a client wants the newest valid pack per (language, type, platform) and picks it itself. `INTERNAL` needs a valid access
  token (anonymous: 401) **and** `qal.languages.internal-channel-enabled=true` (env `QAL_PACKS_INTERNAL_CHANNEL`, default off; when
  off, 400 on the channel parameter). An INTERNAL resource fetched by id is 404 unless both hold. There is no tester or staff role
  yet, so any authenticated caller counts once the switch is on (development and internal builds only).
- **Staged rollout:** `rollout_percentage` 100 is visible to everyone. Below 100 only to authenticated callers whose bucket
  `SHA-256(deviceId else userId + ":" + resourceId)` first 4 bytes mod 100 is under the percentage; anonymous callers never see a
  partial rollout (no stable identity). Applies to the detail endpoint too.
- **Deltas:** `baseResourceId` is exposed as stored; clients refuse deltas today (C1.2, P5.4).
- **Paging:** keyset on (`published_at`, `id`) newest first, `limit` 1-100 (default 20), opaque `nextCursor`. Rows removed by the
  app-version or rollout rules are skipped inside the page loop, so pages stay full.
- **`artifactUrl`** comes from `ArtifactUrlSigner`. `artifact_uri` is stored as `s3://<bucket>/<key>` (signed) or `https://...`
  (public CDN, returned unchanged). The S3 adapter pre-signs a SigV4 query-string `GET` (JDK crypto only, verified against the AWS
  documentation example) for `qal.languages.artifacts.*`: `endpoint` (client-reachable; `http://localhost:9000` for the MinIO in
  `infra/compose`), `bucket` (default `qal-language-packs`; any other bucket in a row is refused), `region`, `access-key`,
  `secret-key` (read-only credential, env only), `url-ttl` (default 15m, 1s-7d), `path-style` (MinIO true). Env:
  `QAL_PACKS_S3_ENDPOINT`, `_BUCKET`, `_REGION`, `_ACCESS_KEY`, `_SECRET_KEY`, `_URL_TTL`, `_PATH_STYLE`. Fail closed: under
  `prod`/`production`/`staging` the application does not start without endpoint, access key and secret; elsewhere without them an
  `s3://` row cannot be served (500, logged) while `https://` rows still work. A row with an unusable location (other scheme or
  bucket) is skipped and logged so one bad row cannot break discovery. The URL only carries the transfer: the client trusts the pack
  signature and SHA-256, never the URL.
- **Signing keys:** every key with its status (`ACTIVE`, `RETIRING`, `REVOKED`, so clients can distrust revoked ones), public key,
  validity window and, if present, `endorsedByKeyId`/`endorsementSignature` (V6 columns, written by the B3.2 key registration). Public
  material only; private keys live in KMS (ADR 0007 D10).
- **Profiles:** `ACTIVE` and `RETIRED` profiles of an active language, with `status`, so a client holding a retired code falls back
  to the newest active profile of the language (SD 10.2, F14). `DRAFT` is never listed. `rulesResourceId` is only given while that
  rules pack is published on a client channel. Unknown or inactive language: 404.
- **Languages:** active rows by `sort_order`; `inputModes` comes from `supported_languages.metadata.inputModes` when present
  (the catalogue has no column for it).
- **Caching:** the contract defines no `ETag`/`Cache-Control`, so none is set (Spring Security's default no-cache headers stay,
  which is right for responses carrying signed URLs).
- Migration `V6__language_resource_discovery.sql` adds `language_resources.container_size_bytes` and the two endorsement columns
  of `resource_signing_keys`.

## Admin pack publishing (B3.2, ADR 0019)

`/admin/v1` operations of the contract's `Admin` tag (`x-internal`), in module `languages` with `administration`
(`AdminAccess`, `AuditTrail`):

- **Access (interim):** off unless `qal.admin.enabled=true` (`QAL_ADMIN_ENABLED`; off = 404). A bearer access token of a
  registered ACTIVE user with an unexpired `user_admin_roles` row whose role grants the permission: `resources:upload`
  (datasets, upload), `resources:edit` (stage), `resources:publish` (publish, retire), `resources:approve` (STABLE approval),
  `languages:read` (read), `signing_keys:manage` (keys; SUPER_ADMIN only). Guests never. Workforce OIDC + MFA replaces this.
- **Audit:** every state-changing action and every refusal writes `audit_events` (actor, action code, entity, outcome
  `SUCCESS`/`FAILURE`/`DENIED`, correlation id, metadata of ids and codes only). Refusals are committed separately.
- **Flow:** `POST /admin/v1/language-datasets` (the build's `datasets.manifest.json`, licence allow-list checked) →
  `POST /admin/v1/language-resources?datasetCode=...` (body = the `.pack` file, `application/octet-stream`, max
  `qal.languages.publishing.max-container-bytes`, default 33 685 504) → `DRAFT` → `POST .../{id}/stage` (`STAGED` =
  published on `INTERNAL`) → `POST .../{id}/publish` `{channel BETA|STABLE, rolloutPercentage, minAppVersion?,
  releaseNotes?}`; STABLE needs `POST .../{id}/stable-approval` by another admin first (`403 APPROVAL_REQUIRED`) →
  `POST .../{id}/retire`. Illegal transitions are `409 PUBLICATION_CONFLICT`.
- **Validation:** the Java port of the core verifier (`languages.pack.PackVerifier`: size, framing, key, strict Ed25519,
  manifest, key-id match, size, SHA-256; passes `core/tests/vectors/pack-v1/`) against `resource_signing_keys`, then the
  policy: no deltas, ACTIVE upload key, known language, licence on `language-data/licenses.json` (classpath copy
  `src/main/resources/languages/licenses.json`, kept identical by `PackPrimitivesTest`; or a file via
  `QAL_LICENSE_ALLOW_LIST`), at least one commercial dataset, rules packs with a backend `profile_code` (D21; ACTIVE for
  BETA/STABLE), newer than every released version of the (language, type, platform) slot. Refusal: `422 PACK_REJECTED`
  with `reason` (413 when too large).
- **Signing:** stage and publish re-sign the pack with the server key (signed `releaseChannel`/`minAppVersion` = where it
  is served; same id, version and artifact). `qal.languages.publishing.signing`: `QAL_PACK_SIGNING_KEY_ID` +
  `QAL_PACK_SIGNING_PRIVATE_KEY` (32-byte Ed25519 seed, base64 or hex), or `allow-ephemeral` (dev profile, tests; refused
  under prod/production/staging); `provider=kms` needs a `KmsSigningClient` adapter (none yet). Register the server key
  once: `GET /admin/v1/pack-signer`, then `POST /admin/v1/resource-signing-keys {keyId, publicKey}`. Other keys are
  registered endorsed by it (spec 7.4) or with `endorse:false` (upload-only keys such as the seed packs' dev key from
  `seed-packs.json`); `.../{keyId}/retire` and `.../revoke` move status forward only.
- **Storage:** uploads at `uploads/<id>/<sha256>.pack`, served containers at
  `packs/<lang>/<type>/<platform>/<version>/<id>-<sha>.pack` in the B3.1 bucket, written with the separate write credential
  `QAL_PACKS_S3_WRITE_ACCESS_KEY`/`_SECRET_KEY` (endpoint `QAL_PACKS_S3_WRITE_ENDPOINT`, default the artifacts endpoint).
  Without it uploads fail (500, logged). `artifact_uri` is `s3://bucket/key`; `container_size_bytes` is filled.
- Publication changes emit the outbox event `language-resource.publication-changed` (discovery has no cache).
- Migration `V15__language_resource_datasets.sql` links resources to their source datasets (V14 is reserved for B5.3).
- Local try-out: `infra/compose` (MinIO), `QAL_ADMIN_ENABLED=true`, `QAL_PACKS_S3_ENDPOINT=http://localhost:9000`, read and
  write keys `qal_minio`/`qal_minio_dev_only`, then give a user a role:
  `INSERT INTO user_admin_roles (user_id, admin_role_id) SELECT '<user>', id FROM admin_roles WHERE role_code = 'SUPER_ADMIN'`.

## Preferences and typing languages (B3.3)

`GET/PUT /api/v1/me/preferences` and `GET/PUT /api/v1/me/typing-languages` (module `preferences`). Any valid access token,
guests included (their documents move to the account on sign-in, ADR 0010). Responses are `Cache-Control: no-store`.

- **Replace + revision.** A PUT replaces the whole document (omitted optional members go back to their defaults) and
  carries the `revision` the client read. No document yet = revision 0 (preferences: the documented defaults; typing
  languages: an empty list); the first write sends 0. Success returns the stored document with `revision + 1`. A stale,
  future or "first write on an existing document" revision is **409 `REVISION_CONFLICT`** with the extension member
  `currentRevision` (the client re-reads, merges, retries). The contract defines no `If-Match`/ETag; `revision` is the only
  mechanism.
- **Concurrency.** Preferences: one statement, `UPDATE ... SET revision = revision + 1 WHERE user_id = ? AND revision = ?`
  (first write: `INSERT ... ON CONFLICT DO NOTHING`); typing languages: the same `UPDATE` on the per-user row of
  `user_typing_language_revisions` (V7), which row-locks the user's list until the transaction ends; the list rows are
  rewritten under that lock in the same transaction. Of any number of writers that read the same revision exactly one wins.
- **Validation (400).** Shapes per the contract; `LANGUAGE_NOT_SUPPORTED` for an unknown or inactive language (any
  language member, also in preferences); typing languages: unique language codes and `sortOrder` (default 100), at most one
  `isPrimary`, at most 20 items, input mode fits the language (am/ti: all three modes; others: `LATIN_PASSTHROUGH`), a
  profile code exactly when the mode is `PHONETIC_LATIN_TO_NATIVE`. `historyEnabled: true` needs the latest
  `SAVE_AI_HISTORY` consent to be granted (403 `CONSENT_REQUIRED`).
- **F14 / profiles (422 `PROFILE_NOT_ACTIVE`).** A profile code resolves to the newest ACTIVE profile of that language
  and code. If none is active, the row keeps the profile it already has when that has the same code (RETIRED or older
  version), so reordering, disabling or adding languages never fails because a profile was retired; rows are updated in
  place so the V1 trigger (validates only when mode/profile/language change) stays quiet. Any other code that is not ACTIVE
  (unknown, DRAFT, other language) is 422. A GET returns the stored code; the fallback to a newer active profile and the
  notice are the client's (System Design 10.2).
- **Guest merge.** `PreferencesGuestMerge` moves the guest's preferences row with its revision (never below 1) and the
  list when the target has none; it deletes the guest's list revision row and increments the target's list revision when a
  list moved, so a target device that cached the old document gets a `REVISION_CONFLICT`.

## Destination profiles and Android app rules (B3.4, ADR 0012)

`GET/POST /api/v1/me/destination-profiles`, `PUT/DELETE /api/v1/me/destination-profiles/{profileId}` and
`GET/PUT /api/v1/me/app-destination-rules` (module `preferences`). Any valid access token, guests included. Responses are
`Cache-Control: no-store`. V8 adds `default_style` and `glossary_id` (profiles), `is_enabled` (rules), the rules revision
table `user_app_rule_revisions` and the per-account key table `user_app_rule_keys`.

- **Label = ciphertext only.** `label_ciphertext` is `UserDataEncryption` output bound to user, key version,
  `user_destination_profiles`, `label_ciphertext` and the row id (the id is generated before encrypting). Responses decrypt
  for the owner; every PUT re-encrypts with a fresh nonce.
- **Profile validation (400).** Label 1-80 characters and not blank; destination language active (`LANGUAGE_NOT_SUPPORTED`);
  `formality` INFORMAL/FORMAL/HONORIFIC (default INFORMAL), `addresseeGender` MASCULINE/FEMININE/PLURAL/UNKNOWN (default
  UNKNOWN), `defaultTone` FRIENDLY/POLITE/PROFESSIONAL/CONCISE/APOLOGETIC/HONORIFIC (absent is stored as NATURAL and read
  back without the member), `defaultStyle`, `glossaryId` must be one of the caller's glossaries, `sortOrder` default 100;
  at most 100 profiles per user (defensive cap, not in the contract). Another user's profile is 404. The contract has no
  default-profile flag, so none is set through the API.
- **Concurrency.** Profiles have no revision in the contract: PUT is last-writer-wins on the whole profile. Rules use the
  `revision` protocol of typing languages (`user_app_rule_revisions`, compare-and-set `UPDATE ... WHERE revision = ?`, 409
  `REVISION_CONFLICT` with `currentRevision`). Profile create/delete and the rules PUT share a per-user advisory lock;
  deleting a profile cascades its rules and bumps the rules revision.
- **Rules.** `appPackageHmac` must match `^[0-9a-f]{64}$`, so a package name (dots, other letters, wrong length) is 400
  before it reaches a query, log or row. At most 200 rules, unique hashes, exactly one of `destinationProfileId` (the
  caller's) or `destinationLanguage` (active), `enabled` default true.
- **Rule key.** The client computes `HMAC-SHA256(accountRuleKey, packageName)`. The key is per account (not per device), 256
  random bits created server-side on the first `GET /me/app-destination-rules/key` (or the first non-empty rules document) and stored only
  as ciphertext (`user_app_rule_keys`). The key operation (ADR 0007 D24) returns `{keyId, key}` (`key` is standard base64 of 32
  bytes, `keyId` a truncated SHA-256 of it), `Cache-Control: no-store`, any valid token including guests; repeated reads return
  the same key. It is never logged.
- **Guest merge.** Profiles move with re-encrypted labels. Both accounts have a rule key: the guest's rules are dropped
  (different key, cannot be re-hashed server-side). Only the guest has one: it becomes the target's (re-encrypted), so the
  moved rules keep matching. The target's rules revision is bumped when rules moved. A guest key never survives the merge.

## Personal dictionary sync (B3.6, ADR 0018)

`GET/POST /me/dictionary/changes` (`pullDictionaryChanges`, `pushDictionaryChanges`), code in
`com.qalsmartkeyboard.languages` (the module that owns `user_dictionary_entries`; the SD table lists the endpoint under
preferences, but the table, the guest-merge and the deletion participants are here).

- **Gate:** guests 403 `GUEST_NOT_ALLOWED`; `DICTIONARY_SYNC` entitlement (`FEATURE_NOT_ENTITLED`); `DICTIONARY_SYNC`
  consent via `ConsentGate` (403 `CONSENT_REQUIRED`). Withdrawal blocks both operations and purges nothing.
- **Storage:** word NFC-normalised, stored only as `UserDataEncryption` ciphertext bound to its row (`word_ciphertext`);
  `word_hmac = hex(HMAC-SHA256(dictKey, "qal.dictionary.word.v1" 0x00 languageCode 0x00 NFC(word)))`, UTF-8. `dictKey` is a
  per-user random 256-bit key in `user_dictionary_sync_state` (ciphertext under the user's data key, separate from it, never
  regenerated, shredded with the user). Delete = tombstone row (`deleted_at`).
- **Protocol:** every change takes the next per-user `sync_version` (counter in the state row, which also serialises
  pushes). Pull: `since` = opaque token (`v1.` + base64url of the last version), oldest first, `limit` 1-100, `hasMore`;
  `clientChangeId` on download is the entry id. Push: up to 500 changes, last writer wins in arrival order, `ADD` of a live
  word and `DELETE` of an absent word are no-ops, applied `clientChangeId`s are remembered 30 days (re-upload is a no-op),
  per-change rejections `INVALID_WORD` / `LANGUAGE_NOT_SUPPORTED` in `rejected`.
- **Merge/deletion:** guest merge recomputes `word_hmac` under the target's key; account deletion deletes the entries, the
  applied-change ids and the key row.
- **V13** adds `user_dictionary_sync_state` and `user_dictionary_applied_changes`.
- **Tombstone retention (B6.3, D31):** tombstones are purged 180 days after the delete. The purge raises
  `user_dictionary_sync_state.tombstone_floor` (V16); a pull whose cursor is behind a purged tombstone gets **410
  `SYNC_CURSOR_EXPIRED`** and the client downloads again without `since`. Tokens that carry a floor are `v2.` +
  base64url(`version:floor`) (the plain `v1.` form while no tombstone was purged). See ADR 0020 D4 and
  `DictionaryResyncIT`.

## Consents (B3.5)

`GET /me/consents` (latest state per code) and `PUT /me/consents/{consentCode}` (grant or withdraw); any valid token,
guests included. Code in `com.qalsmartkeyboard.privacy`.

- **Append-only.** Every PUT appends a `user_consents` row (code, client-supplied `consentVersion`, grant/withdraw, platform
  from `X-Client-Platform`, `details` kept in `metadata.details`, time from `clock_timestamp()`); a trigger rejects UPDATE and
  DELETE. The latest row per (user, code) wins; no row means not granted. `grantedAt` is the time of the latest grant (kept
  after a withdrawal), `revokedAt` is set only while the latest decision is a withdrawal.
- **Versions.** The version is recorded, never compared: the System Design (19.3) defines no minimum-version rule, so an older
  grant stays valid until the person decides again.
- **Not revocable.** `TERMS_ACCEPTANCE` and `PRIVACY_NOTICE` (422 `VALIDATION_FAILED`, field `isGranted`); unknown code 400.
- **Gate.** `ConsentGate.requireConsent(userId, ConsentType)` throws 403 `CONSENT_REQUIRED` with the extension member
  `consentCode`; `hasConsent` is the boolean form. `ConsentType.gatedFeatures()` is the single list of which features each
  consent gates (AI_PROCESSING: server AI; SAVE_AI_HISTORY: history writes and `historyEnabled`; SAVE_AUDIO_HISTORY: audio
  history; DICTIONARY_SYNC: user dictionary sync; PERSONAL_STYLE: learned style hints; FEEDBACK_TEXT: feedback comment and
  content; COMMUNITY_CONTRIBUTION: community submissions; PAYMENT_MANDATE: mandates; ANALYTICS; MARKETING).
- **Withdrawal.** Publishes `ConsentChanged` in the same transaction. Preferences listens and switches `historyEnabled` off
  (a revisioned write) when `SAVE_AI_HISTORY` is withdrawn; existing history is kept (contract: "stops new history writes and
  offers deletion"), so no purge event is published. Billing (later) cancels mandates on `PAYMENT_MANDATE` the same way.
- **History.** `GET /me/consents/history` (`listConsentHistory`, ADR 0007 D23) pages the caller's own decisions oldest first
  (`consentCode`, `cursor`, `limit` 1..100; keyset on `recorded_at, id`); `ConsentService.history(userId, type)` is the
  unpaged Java form for the data export. `details.surface` is stored in the record's metadata and never interpreted.
- **Guest merge** (`PrivacyGuestMerge`, ADR 0010) is unchanged: it must preserve the guest's `recorded_at` and version, which
  the PUT path does not.

## Account deletion (B6.2, ADR 0015, SD 19.4)

`POST /me/deletion-request` (`{"confirm": true}`, 202), `GET /me/deletion-request` (the pending request or 404) and
`DELETE /me/deletion-request` (cancel, 204); any valid token, guests included. Code in `com.qalsmartkeyboard.privacy`
(request service, executor) and `identity` (`AccountDeletion` for the `users` row and sessions, the
`UserDeletionParticipant` port).

- **Grace period** `qal.privacy.deletion.grace-period` (default 14 days, SD 19.4; `QAL_DELETION_GRACE_PERIOD`). The
  account becomes `DELETION_PENDING`: AI stops (no plan, ADR 0007 D22), typing and the other features keep working, the
  requesting session stays, every other session is revoked (`ACCOUNT_DELETED`), sign-in still works (to cancel). A
  second request is 409 `DELETION_PENDING`; a suspended account 403 `ACCOUNT_SUSPENDED`. `reason` is not stored.
- **Cancel** while `SCHEDULED` (even past the purge time, until the executor starts): request `CANCELLED`, account
  `ACTIVE`. Events `privacy.account.deletion_requested` / `privacy.account.deletion_cancelled`.
- **Executor** (`DeletionExecutor`, db-scheduler task `privacy-account-deletion`, every
  `qal.privacy.deletion.executor-interval` = 5 min; off in tests, `executor-enabled=false`): per due request three
  committed steps recorded on the request (V11 columns): (1) close the account (`users.deleted_at`: every token refused,
  sign-in no longer finds it), revoke all sessions, `UserDataEncryption.cryptoShred` → `shredded_at`; (2) every
  `UserDeletionParticipant` purges its tables → `purged_at`; (3) anonymise the `users` row (`DELETED`), request
  `COMPLETED`, event `privacy.account.deleted`. Each step locks the request `FOR UPDATE SKIP LOCKED`, so instances never
  repeat or overlap a step; a crash resumes at the next step; a failing step rolls back and is retried after
  `retry-backoff` (1 min) doubling up to `max-retry-backoff` (1 h), forever (alert on
  `qal.privacy.account_deletion{outcome=failed}`; `processing_error` holds the step and exception class only).
- **Kept** (pseudonymised: attached to the anonymised id only): `user_consents`, `usage_events`, credit accounts, lots
  and ledger, `user_subscriptions`/payments (none yet), `ai_requests`, `audit_events`, `user_data_keys` (DESTROYED),
  `user_deletion_requests`. Everything else of the user is deleted; per-table rules in ADR 0015. A module that adds a
  per-user table must handle it in its `UserDeletionParticipant` in the same change.
- **Re-registration:** identities are deleted and the row anonymised, so the same phone number or Google account later
  creates a new, empty account; the deleted account can never be signed in to again.

## Data export (B6.1, ADR 0022, SD 19.4)

`POST /me/data-exports` (202), `GET /me/data-exports/{exportId}`, `GET /me/data-exports/{exportId}/archive`; any valid token,
guests included; every response `no-store`. Code in `com.qalsmartkeyboard.privacy` (`DataExportService`, `DataExportJob`,
`ExportArchive`, `ExportObjectStore`) and `identity` (the `UserExportParticipant` port, `ExportWriter`, `ExportRows`).

- **Request:** records a `REQUESTED` row; the db-scheduler task `privacy-data-export` (every `poll-interval`, 10 s; off in tests,
  `job-enabled=false`) builds it. **One in flight per user** (asking again returns the open export), **3 per 24 h**
  (`max-per-window`, `window`; failed exports do not count), then 429 `RATE_LIMITED` with `Retry-After`. Also allowed while an
  account deletion is pending (the person can take the data with them); a closed or shredded account cannot export.
- **Archive** (JSON, contract `DataExportArchive`): `manifest` (format `qal-data-export`, version, ids, times, one entry per
  section with its row count and SD 19.2 data class, `excluded` with a reason each, notes) and `data` (one array per
  section). Every module contributes through a `UserExportParticipant` (the twin of `UserDeletionParticipant`, run in one
  read-only, repeatable-read transaction), decrypting its ciphertext with `UserDataEncryption`; plaintext exists only in the
  archive. App rules are exported as hashes (the server never sees package names, ADR 0012). A module that adds a per-user
  table must add it to its participant; `DataExportIT` fails on any per-user table that is neither exported nor excluded.
- **At rest:** the document is AES-256-GCM sealed with the user's data key (`data_export_requests / archive / <export id>`) and
  stored as `exports/<256 random bits hex>` in the private bucket (`qal.privacy.export.storage.*`, env `QAL_EXPORTS_S3_*`, bucket
  `qal-exports`; **give the bucket a 7-day lifecycle rule**). The bucket is never exposed: `downloadUrl` is the API's archive
  endpoint, which needs the owner's token and decrypts for the response. Crypto-shred or account deletion makes it unreadable.
- **Expiry:** `link-ttl` 7 days from completion; after that the status reads `EXPIRED` at once, the download is 404 and the
  job deletes the object and clears the key. Another user's id, an unknown id and a not-ready export are all 404.
- **Account deletion:** `PrivacyDeletion` deletes the export rows and their archive objects (a store failure is logged and does
  not hold up the deletion). B6.3 removes the rows 30 days after expiry (`data-export-requests`).
- Metrics `qal.privacy.data_export{outcome=requested|ready|failed|expired|downloaded|rate_limited}`; logs carry ids and counts only.
- Proof: `DataExportIT` (PG18 + MinIO via Testcontainers, contract-validated).

## Retention (B6.3, ADR 0020, SD 19.2)

A daily db-scheduler task `privacy-retention` (`qal.retention.cron`, default `0 0 3 * * *` UTC; `qal.retention.enabled`,
off in tests) purges every data class past its period. Registry and job are in `com.qalsmartkeyboard.privacy`
(`RetentionPolicy`, `RetentionJob`, `RetentionProperties`); each module owns its rules and SQL through a
`platform.retention.RetentionParticipant` (identity, privacy, platform, AI gateway, usage, languages, administration; the
module-less tables are held by `UnownedTablesRetention`). The class, period, action and source of every rule, including the
ones the SD leaves undefined and the ones that are kept (financial), are the table in ADR 0020.

- **Configuration:** `qal.retention.periods.<rule-code>` overrides a default (`QAL_RETENTION_PERIODS_OTP_CHALLENGES=48h`);
  `batch-size` (1000) and `max-batches-per-rule` (1000) bound a run. An unknown code or an override of a kept (financial) or
  owner-deleted class stops the application at start.
- **Batches and locking:** one transaction per batch, `DELETE ... WHERE key IN (SELECT ... LIMIT n FOR UPDATE SKIP LOCKED)`,
  a per-rule transaction advisory lock so two instances never purge the same rule at once; idempotent and resumable (the
  next run continues the backlog). Append-only ledgers (`audit_events`, `user_consents`) are purged under
  `app.retention_purge = 'on'`, only inside the job.
- **Metrics** (rule codes only): `qal.retention.purged{rule,action}`, `qal.retention.failures{rule}` (alert),
  `qal.retention.skipped{rule}`, timer `qal.retention.run`. Logs carry the rule code and a row count, never row content.
- **Users:** `MERGED` guests and `DELETED` tombstones are deleted 30 days later unless an `ON DELETE RESTRICT` foreign key
  (financial rows) points at them; the blocking tables are read from the catalogue.
- **New table with a retention need:** add a `RetentionRule` and its SQL to the module's participant in the same change, and
  a row to ADR 0020. Financial and ledger classes (`financial-records`, `ai-credit-ledger`, `usage-events`) are kept.
- **V16** adds `tombstone_floor` and the purge indexes. Tests: `RetentionIT` (every rule just inside/outside, idempotence,
  resume, two instances, financial tables untouched), `DictionaryResyncIT` (D31).

## Identity: phone OTP (B2.1, ADR 0006)

Module `identity`: `POST /api/v1/auth/otp/start` (202 `OtpChallenge`) and `POST /api/v1/auth/otp/verify` (200
`AuthSession`). Rules from SD 9.2:

- **Numbers:** strict E.164 (contract pattern); `+251` numbers must be mobile (`+2519…`/`+2517…`, 9 digits), else 400
  `VALIDATION_FAILED` (`INVALID_PHONE_NUMBER`).
- **Codes:** 6 digits from `SecureRandom`, valid `qal.identity.otp.code-ttl` (5m). Stored only as a keyed hash bound to
  the challenge id; the number only as a keyed hash plus an AES-GCM sealed copy (V3, needed to create the account on
  verify). At most 5 wrong codes per challenge (`OTP_TOO_MANY_ATTEMPTS`, the challenge is then locked). A used,
  superseded (a newer challenge exists for the number) or unknown challenge is `OTP_INVALID`; a **wrong code** of a
  live challenge is `OTP_INVALID` with the extension member `attemptsRemaining` (1 to 4; ADR 0007 D32, absent for the
  indistinguishable used/superseded/unknown cases); an expired one `OTP_EXPIRED`. New accounts need `ageConfirmed: true` (400 otherwise; the code stays usable).
- **Resend cooldown:** `qal.identity.otp.resend-cooldown` (60s) per number, enforced in PostgreSQL: 429 `RATE_LIMITED`
  with `Retry-After`.
- **Secret (required):** `qal.identity.otp.secret-key` / env `QAL_OTP_SECRET_KEY` = base64 of at least 32 random bytes
  (`openssl rand -base64 32`). Without it startup fails. Tests use `qal.identity.otp.allow-ephemeral-secret-key=true`
  (dev/test only, refused under `prod`/`production`/`staging`).
- **Rate limits** (`qal.identity.otp.limits.<name>.capacity|period`, PROVISIONAL): `start-per-ip` 30/1h,
  `start-per-device` 10/1h (only when the start request carries the optional `installationId`), `start-per-phone`
  5/1h, `start-per-phone-daily` 10/24h, `verify-per-ip` 60/1h, `verify-per-device` 20/1h (device =
  `device.installationId` of the verify request). The caller's IP and device budgets are checked before the number's.
  429 `RATE_LIMITED` with `Retry-After` and `RateLimit-*`; a successful start returns the caller's per-IP `RateLimit-*`.
- **Client IP** is `request.getRemoteAddr()`. Behind a load balancer set `server.forward-headers-strategy` so the
  container resolves `X-Forwarded-For` from the trusted proxy; never parse it in code.
- **Metrics:** `qal.identity.otp.started{outcome,destination}`, `qal.identity.otp.verified{outcome}`,
  `qal.identity.otp.lockouts`, `qal.ratelimit.rejected{policy}`.
- **Logs:** never the code, the number (only masked by the SMS mock) or tokens; `PhoneOtpIT` captures all log events
  of a flow and checks.

## Identity: sessions and tokens (B2.4, ADR 0006)

- **Access token:** ES256 JWT (`typ: at+jwt`, `kid`), lifetime `qal.identity.tokens.access-token-ttl` (15m, at most
  15m). Claims: `iss` (`qal-smart-keyboard`), `aud` (`qal-api`), `sub` (user id), `sid` (session = refresh-token
  family), `did` (device, once B2.5 registers devices), `accountType` (`REGISTERED`/`GUEST`), `iat`, `exp`, `jti`. No
  personal data. Verification is stateless, so a revoked session's access token works until it expires (at most 15 min).
- **Signing key (required):** `QAL_JWT_PRIVATE_KEY` (PKCS#8 PEM), `QAL_JWT_PUBLIC_KEY` (SPKI PEM), `QAL_JWT_KEY_ID`.
  The pair must be P-256 and must match, otherwise startup fails:
  ```sh
  openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out jwt-private.pem
  openssl ec -in jwt-private.pem -pubout -out jwt-public.pem
  ```
  Rotation: list retired public keys under `qal.identity.tokens.previous-keys[n].key-id|public-key` until their
  tokens have expired. Tests use `qal.identity.tokens.signing-key.allow-ephemeral=true` (dev/test only, refused under
  `prod`/`production`/`staging`).
- **Refresh token:** opaque, 256 random bits (43 base64url characters). Only its SHA-256 is stored, as
  `user_sessions.refresh_token_hash`. Lifetime `qal.identity.tokens.refresh-token-ttl` (60d), sliding: each rotation
  starts a new period. `POST /api/v1/auth/refresh` rotates on every use. A new row is created in the same
  `token_family_id`, and the old row becomes `ROTATED` with `replaced_by_session_id`. `last_used_at` stays `NULL`
  until a token is presented.
- **Reuse detection:** presenting a `ROTATED` token (outside the grace window below) gives 401
  `REFRESH_TOKEN_REUSED`. Every `ACTIVE` row of the family is revoked (`TOKEN_REUSE`), `reuse_detected_at` is set, and
  the outbox event `identity.refresh_token.reuse_detected` is published (`userId`, `tokenFamilyId`; notifications will
  tell the user).
- **Grace window (ADR 0007 D8):** `qal.identity.tokens.refresh-reuse-grace` (default `30s`, `0` disables, at most
  `2m`). A retry with a `ROTATED` token rotates again (200) instead of revoking the family if it comes within the
  window of that token's **first** rotation and its direct successor is still `ACTIVE`, unexpired and was never
  presented (`last_used_at IS NULL`, no successor of its own). The unused successor becomes `REVOKED`
  (`SUPERSEDED_RETRY`, V4) and the rotated row points to the new one. Presenting a superseded successor later is
  reuse (the family is revoked). Successor already used, a token two or more generations old, or a successor already
  revoked/expired: reuse as above.
- **Concurrency:** every refresh and logout of a family takes the family's advisory lock first (then the presented
  row, then its successor), so refreshes of one family are serialised. Concurrent grace retries leave exactly one
  `ACTIVE` successor; a retry racing the first use of the successor ends with the family revoked; a family
  revocation never misses a successor inserted by a concurrent rotation.
- Other refresh failures: expired gives `TOKEN_EXPIRED`. Unknown, revoked or a deleted/merged account gives
  `UNAUTHENTICATED`. Suspended gives 403 `ACCOUNT_SUSPENDED`. A malformed body gives 400 `VALIDATION_FAILED`.
- **Logout:** `POST /api/v1/auth/logout` (bearer) revokes the caller's family, or every family of the user with
  `{"allDevices": true}`.
- **Principal:** controllers take `@AuthenticationPrincipal com.qalsmartkeyboard.identity.AuthenticatedUser`
  (`userId`, `sessionId`, `deviceId`, `accountType`).
- **Metrics:** `qal.identity.sessions{event=issued|rotated|rotated_grace|reuse_detected|expired|logout|logout_all}`.

## Devices (B2.5, ADR 0006)

Module `devices` owns `user_devices`: `GET /api/v1/devices` (`listDevices`), `POST /api/v1/devices` (`registerDevice`), `PATCH /api/v1/devices/{deviceId}`
(`updateDevice`) and `DELETE /api/v1/devices/{deviceId}` (`revokeDevice`); all need an access token.

- **Register** is idempotent per user and installation id (`ux_user_device_installation`): the same row comes back and
  the reported fields are refreshed (`null` keeps the stored value). Platform must be `ANDROID` or `IOS` (`WEB` has no
  devices: 400), an installation never changes platform (400 `PLATFORM_MISMATCH`), and a present `X-Client-Platform`
  header must equal the body's platform. `X-App-Version` fills `appVersion` when the body has none (also on PATCH).
  A REVOKED device registered again becomes ACTIVE with its attestation reset. `distribution_channel` stays `OTHER`
  (the contract carries no channel).
- **List (ADR 0007 D15):** `GET /api/v1/devices` (`listDevices`) returns `{items, nextCursor}` per the contract's cursor
  convention: oldest first, `limit` 1-100 (default 20), an opaque keyset cursor over (`created_at`, `id`) that carries no
  user data (malformed: 400 `VALIDATION_FAILED` on `cursor`). Only the caller's rows are ever read. **Revoked devices are
  included** with `status: REVOKED` (the schema has the field, and a devices screen shows them); the push token is never
  returned.
- **Ownership:** every lookup is by device id and the caller's user id. A foreign, unknown or (for PATCH) revoked device is
  the same 404 `NOT_FOUND`. DELETE of an already revoked device is 204 again.
- **Push token:** write-only (never returned, never logged), stored in `push_token` as the V1 column defines it (plain:
  the unique active-token index must recognise a token that moves between accounts; it is a delivery address, not user
  content, so it is not sealed with `UserDataEncryption`). Provider is derived from the platform (`FCM`/`APNS`) and a
  mismatching `pushProvider` is a 400. An empty `pushToken` on PATCH clears it. A token already held by another ACTIVE
  device moves to the newest registration. Revocation drops the token.
- **Device and session (`did`):** identity depends on no devices code. `identity.DeviceRegistrar` (port, implemented by
  `devices`) is called while a sign-in issues its session, so OTP verify with a `device` member registers it and the
  session row, and every access token of that family, carry the device (`AuthSession.deviceId`, claim `did`); `WEB`
  devices are skipped. `POST /devices` binds the caller's session family when it has no device yet (the next refresh
  returns a token with `did`; the current access token is not reissued). `DELETE` calls `identity.DeviceSessions` in
  the same transaction: every family bound to the device is revoked (`DEVICE_REVOKED`), so its refresh token gets
  `UNAUTHENTICATED`. Access tokens already issued still verify for up to 15 minutes. The outbox event
  `devices.device.revoked` (`userId`, `deviceId`, `revokedSessions`) has no consumer yet.
- **Metrics:** `qal.devices{event=registered|refreshed|updated|revoked}`, `qal.identity.sessions{event=device_revoked}`.

## Identity: attestation nonces and guest accounts (B2.3, ADR 0008)

`POST /api/v1/auth/attestation-nonces` (anonymous, or with a valid bearer), `POST /api/v1/auth/guest` (anonymous) and
`POST /api/v1/devices/{deviceId}/attestations` (bearer).

- **Nonce:** 256 random bits (43 base64url characters), valid `qal.identity.attestation.nonce-ttl` (5m, the contract's
  value and the maximum), stored only as SHA-256 in `attestation_nonces` (V5). Bound to the caller when a bearer token is
  sent (only that user can spend it) and to `X-Client-Platform` when sent. Single use: one atomic `UPDATE` marks it
  consumed, in its own committed transaction, so a replayed verdict and a failed verdict both burn it. 429 per client IP
  (`limits.nonce-per-ip`, 120/h). The contract has no body on this call, so a nonce cannot be bound to an installation id.
- **Verifier ports** (`identity.attestation`): `PlayIntegrityVerifier`, `AppAttestVerifier`, combined by the
  `AttestationVerifier` strategy by platform (`ANDROID` uses Play Integrity, `IOS` uses App Attest). An adapter returns
  VALID (genuine, and it covers the expected nonce), REJECTED or UNAVAILABLE and never throws for a bad token; an adapter
  that throws counts as UNAVAILABLE. **No real adapter exists yet.** A real adapter is any bean of the port type.
- **Mock adapters** (development and tests only): `QAL_ATTESTATION_MOCK=true` /
  `qal.identity.attestation.mock.enabled=true`; refused at startup under `prod`/`production`/`staging`. Token format
  (`MockAttestationTokens`): `mock-play-integrity.v1.<nonce>.valid` (provider `PLAY_INTEGRITY`) and
  `mock-app-attest.v1.<nonce>.valid` (provider `APP_ATTEST`, `keyId` required). The nonce in the token and the evidence's
  `nonce` must both equal the server's nonce; any other string, verdict word or part count is rejected.
- **Fail closed:** without an adapter for a platform (the default, hence production until the real adapters exist) every
  verdict is UNAVAILABLE: nonces are still issued, `POST /auth/guest` is always 403 `GUEST_NOT_ALLOWED`, and
  `POST /devices/{id}/attestations` answers `UNSUPPORTED` and leaves the device unattested.
- **Guest:** order is platform (`ANDROID`/`IOS`, else 400), per-IP (`guest-per-ip`, 10/h) and per-installation
  (`guest-per-installation`, 5/h) limits (429), attestation, then one transaction that creates the `GUEST` user (no
  identity rows, `ACTIVE`), registers the device with the VALID verdict and issues the session (`accountType=GUEST` and
  `did` in the access token). **Every attestation failure is 403 `GUEST_NOT_ALLOWED`** with a generic detail (missing or
  empty attestation, unknown/expired/replayed/foreign nonce, wrong provider, rejected verdict, no verifier, installation
  already has a guest). One guest per installation, no resume (ADR 0008).
- **Plan:** a GUEST account's effective plan is `GUEST` while it has no subscription (SD 15.5), so no subscription row is
  written; the `GUEST` plan and its `plan_entitlements` are V1 seed data (TRANSLATE 3/day and so on).
- **Device attestation:** the verdict (`VALID`/`FAILED`; `UNSUPPORTED` if no verifier is available, which never overwrites
  an earlier VALID/FAILED) is stored on `user_devices` (provider, status, `attestation_checked_at`).
- **Metrics:** `qal.identity.attestation.nonces{outcome}`, `qal.identity.attestation.verdicts{outcome}`,
  `qal.identity.guest{outcome}`. Tokens and nonces are never logged.

## Identity: Google sign-in (B2.2, ADR 0009)

`POST /api/v1/auth/oauth/GOOGLE` (`exchangeOAuthIdToken`, anonymous; body `idToken`, **`nonce`**, optional `displayName`,
`ageConfirmed`, `device`) returns the same `AuthSession` as OTP verify. `APPLE` and any other provider: 400
`VALIDATION_FAILED` on `provider` (`PROVIDER_NOT_SUPPORTED`).

- **Configuration:** `QAL_GOOGLE_CLIENT_IDS` (`qal.identity.oauth.google.client-ids`), comma separated Android, iOS and
  web OAuth client ids; a token is accepted only if its `aud` is one of them. With none, Google sign-in is off, and
  under `prod`/`production`/`staging` startup fails (set `qal.identity.oauth.google.enabled=false` to run without it).
  Other settings: `jwks-uri` (default Google's certs URL; https, or http to loopback), `clock-skew` (60s),
  `jwks-cache-ttl` (1h), `jwks-min-refresh-interval` (30s), `jwks-outage-tolerance` (6h), `http-timeout` (3s).
- **Verification:** RS256 only (`none`, HS256 and others are refused), `iss` is `https://accounts.google.com` or
  `accounts.google.com`, `aud` in the client ids, `exp`/`iat` with skew, `sub`, and the `nonce` claim must equal the
  request nonce. JWKS is cached, refreshed on an unknown `kid` (rate limited, so key rotation works and a flood of
  unknown ids does not hit Google), and cached keys keep working through a Google outage. Any rejected token is 401
  `UNAUTHENTICATED` (generic); keys not fetchable and not cached is 503 `PROVIDER_UNAVAILABLE`.
- **Accounts:** found or created by (`GOOGLE`, `sub`) in `user_auth_identities`, **never by e-mail** (account-takeover
  risk). Stored: `sub`, the e-mail only when `email_verified` (on the identity row, `users.email` stays
  empty), the display name for new accounts. Never stored or logged: the ID token, nonce, e-mail in logs. Status rules
  as phone sign-in (suspended 403, deleted/merged 401); new accounts need `ageConfirmed: true`. A `device` member is
  registered like on OTP verify. A guest bearer token links the guest (B2.6, next section).
- **Limits** (`qal.identity.oauth.limits.<name>.capacity|period`, PROVISIONAL): `exchange-per-ip` 60/1h,
  `exchange-per-device` 20/1h; checked before verification.
- **Tests:** `JwksIdTokenVerifierTest` (fake JWKS on a loopback HTTP server: bad signature, wrong `aud`/`iss`, expired,
  unknown `kid` with refresh, alg none/HS256), `OAuthControllerTest` (contract-validated), `GoogleSignInIT`.
- **Metrics:** `qal.identity.oauth{provider,outcome}`.

## Identity: guest linking on sign-in (B2.6, ADR 0010)

`POST /auth/otp/verify` and `POST /auth/oauth/{provider}` with the bearer token of a live guest link that guest to the
account that signs in, and return `mergedGuestUserId`. Without a bearer, or with a registered account's token, nothing
is linked. Code: `identity.merge.GuestMergeService`; module rules: `identity.GuestMergeParticipant` beans.

- **Never into a pending deletion (ADR 0007 D30):** when the account that signs in is `DELETION_PENDING`, the guest is
  not merged: nothing moves, the guest stays an ACTIVE, separate guest (its token keeps working), the sign-in succeeds
  and the response has no `mergedGuestUserId` (`GuestMergeService.mergeInto` returns `false`; metric
  `qal.identity.guest_merge{outcome=refused}`).
- **Who may link:** a valid guest access token whose account is `GUEST`/`ACTIVE` and whose session family still has an
  ACTIVE refresh token (not logged out, not revoked for reuse). Checked after the code / ID token, before any write.
  Anything else is 401 `GUEST_SESSION_INVALID` (ADR 0007 D20) with `WWW-Authenticate: Bearer error="invalid_token"`, nothing
  written, the OTP code not consumed (the client drops the guest token and retries). The security chain gives the same code
  to any guest token whose account is no longer an active guest, on every endpoint.
- **New phone number / Google subject: upgrade in place.** The guest row becomes the `REGISTERED` account (identity,
  phone or display name, `age_confirmed_at`; `ageConfirmed: true` is required as for any new account). Same user id, so
  devices, verdicts, usage, history and keys stay; `isNewUser: true`, `mergedGuestUserId` = `user.id`. The guest's own
  session families are revoked (`ACCOUNT_MERGED`) right after the registered session is issued.
- **Existing account: merge.** In the sign-in transaction: each module moves
  its rows (devices, preferences, typing languages, style and destination profiles, app rules, consents (appended),
  history, dictionary, term-suggestion attribution, current usage counters, glossaries), re-encrypting ciphertext for
  the target (`UserDataEncryption.reencrypt`/`reencryptRow`), target wins on conflicts; unspent nonces burned; guest
  sessions revoked (`ACCOUNT_MERGED`); guest `MERGED` with `merged_into_user_id`; guest keys crypto-shredded; then the target's session. Per-table rules: ADR 0010.
- **Quota:** the guest's consumption in current windows (`used + reserved`) is added to the target's counter of the same
  feature for the window the metering engine computes for the target (B4.2), so signing in never resets an allowance.
- **Exactly once:** the guest row is locked (`FOR NO KEY UPDATE`); a concurrent or replayed sign-in with the same guest
  token gets the 401 above. Guest access tokens are checked against `users` on every request (one indexed read), so a
  merged or upgraded guest's tokens stop working at once (registered tokens stay stateless, 15 min window).
- **Event:** outbox `identity.guest.merged` `{guestUserId, targetUserId, mode: MERGED|UPGRADED, method, revokedSessions}`.
  Metric `qal.identity.guest_merge{outcome,method}`. Logs carry ids only.
- **Adding a per-user table:** extend (or add) the owning module's `GuestMergeParticipant` in the same change; ciphertext
  is re-encrypted, never copied.
- **Tests:** `GuestMergeIT` (contract-validated: upgrade by phone and by Google, merge with data in every table incl.
  re-encryption, shred and quota carry-over, Google merge, 6-way race, replay, registered token, ended guest session,
  log hygiene), `OtpControllerTest` (response field, registered token ignored, guest tokens refused without the
  account store), `EnvelopeEncryptionServiceTest` (re-encryption).

## Entitlements: resolver (B4.1, SD 15)

Module `entitlements`: `EntitlementResolver.resolve(userId)` / `resolve(userId, at)` returns `ResolvedEntitlements`: the
effective plan (`EffectivePlan`: plan, rank, `PlanSource`, subscription or organisation id, `effectiveUntil`, grace flag,
`periodStart` = the paid period start that anchors MONTHLY usage windows)
and one `FeatureEntitlement` per active `feature_catalog` feature (`accessEnabled`, `includedUnits`, `unit`,
`resetPeriod`, `creditCostPerUnit`, `creditsUsable`, `origin`). Metering (B4.2), the token (B4.4, below) and
`/me/entitlements` (B4.5) call this API. It reads the tables through its own package-private JDBC adapter (billing and
organisations have no Java API yet).

- **Account:** only `ACTIVE`, undeleted accounts hold entitlements. SUSPENDED, DELETION_PENDING, DELETED, MERGED, PENDING
  and unknown users get no plan and every gated feature disabled (`AccessState` says why). Ungated features (typing,
  `DETECT_LANGUAGE`) are never gated (R-2) and stay allowed, even then.
- **Sources** (SD 15.5): `user_subscriptions` (source_type PAYMENT/PROMOTION/ADMIN_GRANT = `SUBSCRIPTION`, STORE =
  `STORE_PURCHASE`, PARTNER = `PARTNER`; store and partner grants converge on that table, SD 16.1/16.7) and active
  organisation seats (`ORGANIZATION_SEAT`: ACTIVE assignment + ACTIVE member + ACTIVE organisation + ACTIVE/GRACE_PERIOD
  organisation subscription). Valid at `at` when the start (if any) has been reached and either status ACTIVE and `at <`
  period end + `qal.entitlements.renewal-lag` (none = open-ended), or status GRACE_PERIOD and `at <` `grace_period_end` (else period end +
  `qal.entitlements.default-grace-period`, 3 days, SD 16.5). Period ends are exclusive. PENDING, PAST_DUE, PAUSED,
  CANCELLED (cancel-at-period-end stays ACTIVE), EXPIRED, REVOKED and UPGRADED grant nothing; a STORE subscription whose
  purchase is REFUNDED/REVOKED grants nothing. `plan_status` is not consulted (sold BUSINESS is still DRAFT in the seed).
- **Renewal lag (ADR 0007 D22):** an ACTIVE subscription or organisation subscription just past `current_period_end`
  keeps granting for `qal.entitlements.renewal-lag` (default `24h`, `0s` = off; env `QAL_ENTITLEMENTS_RENEWAL_LAG`), so a
  late store/gateway renewal notification does not cut off a paying user. `effectiveUntil` keeps reporting the period
  end (in the past during the lag: renewal pending). GRACE_PERIOD ends are not lagged; other statuses never grant.
- **Precedence:** highest `plan_rank` among valid sources and the default plan (FREE, or `qal.entitlements.guest-plan-code`
  = GUEST for guests; a guest has no subscription row). Allowances are never summed. Ties: ACTIVE before GRACE_PERIOD,
  later `effectiveUntil` (open-ended last), then SUBSCRIPTION, STORE_PURCHASE, ORGANIZATION_SEAT, PARTNER, then source id.
- **Allowances** come from `plan_entitlements` rows whose `configuration->>'quotaPolicyVersion'` equals
  `qal.entitlements.quota-policy-version` (default `PROVISIONAL_V2`; env `QAL_ENTITLEMENTS_QUOTA_POLICY_VERSION`). No row
  for a gated feature = disabled (fail closed), so a new policy version needs its rows seeded first.
- **Overrides:** `user_feature_overrides` rows count when unexpired and their flag has a `feature_code` (flags without
  one are UI flags). Override beats plan: disable = no access and allowance 0; enable = access with the plan row's
  allowance, so a feature the plan excludes opens with allowance 0 and is payable with credits only. Several overrides
  on one feature: disable wins. Typing/ungated features and denied accounts ignore overrides.
- **Cache:** `resolve(userId)` is cached per user for `qal.entitlements.cache-ttl` (30s, `0s` = off), never beyond the winning
  source's validity end (`effectiveUntil`, plus the renewal lag for an ACTIVE source); `resolve(userId, at)` always computes. Billing/organisation code must call
  `invalidate(userId)` after changing a user's sources or overrides (otherwise visible after the TTL).
- **Contract mapping:** `Entitlements.plan.source` has one value per `PlanSource`; use
  `PlanSource.contractValue()` (ADR 0007 D20 added STORE_PURCHASE and PARTNER to the contract enum; it equals the enum name).
- **Tests:** `EntitlementSeedMatrixTest` (5 plans x 24 features read from `V1__baseline.sql`), `EntitlementResolverTest`
  (precedence, edges, grace, accounts, overrides, cache), `EntitlementResolverIT` (real seed and source tables).

## Entitlements: offline entitlement token (B4.4, ADR 0013, SD 15.5)

`entitlements.token.EntitlementTokenService.issue(userId, deviceId)` signs a compact JWS of the caller's **offline**
gates from `EntitlementResolver.resolve(userId)`. Clients use it only where they cannot ask the server (on-device
models); every server feature is re-checked on each call.

- **Operations:** `GET /api/v1/me/entitlements/token` (bearer, any ACTIVE account incl. guests, `no-store`) and
  `GET /.well-known/jwks.json` (anonymous, `Authorization` ignored, `max-age=3600, public`; public keys only).
  `getEntitlements` (B4.5) embeds the same `EntitlementToken`.
- **Format:** header `alg EdDSA` (Ed25519), `typ qal-ent+jwt`, `kid`. Claims: `iss` (`qal-smart-keyboard`), `aud`
  (`qal-keyboard`), `sub` (user id), `deviceId` (when the access token has `did`), `planCode`, `features` (feature code
  -> boolean), `policyVersion`, `ver` (1), `iat`, `exp` = iat + `qal.entitlements.token.ttl` (24h, at most 24h) but never later than the winning source's end +
  `qal.entitlements.renewal-lag` (no lag for a GRACE_PERIOD source; ADR 0007 D27),
  `offlineGraceUntil` = exp + `offline-grace` (72h, at most 72h), `jti`. No personal data, allowances or usage.
- **Offline gates:** `qal.entitlements.token.offline-features` (default `ON_DEVICE_AI_MODELS`, `PREMIUM_THEMES`). Only gated features
  with unit `NONE` are written; metered (server AI) or unknown codes are left out with a warning.
- **Rate limit:** `GET /me/entitlements/token` 60 per hour per user (`platform.ratelimit` policy `entitlements.token.user`), 429
  `RATE_LIMITED` with `Retry-After`/`RateLimit-*`.
- **Refused:** SUSPENDED 403 `ACCOUNT_SUSPENDED`, DELETION_PENDING/DELETED 403 `DELETION_PENDING`, MERGED 401
  `GUEST_SESSION_INVALID`, PENDING/unknown 401 `UNAUTHENTICATED`.
- **Signing key (required):** `QAL_ENTITLEMENT_TOKEN_PRIVATE_KEY` (PKCS#8 PEM, or an OKP JWK with `d` and `x`),
  `QAL_ENTITLEMENT_TOKEN_PUBLIC_KEY` (SPKI PEM; optional with a JWK), `QAL_ENTITLEMENT_TOKEN_KEY_ID` (default `ent-1`).
  Must be Ed25519 and match, otherwise startup fails:
  ```sh
  openssl genpkey -algorithm ed25519 -out ent-private.pem
  openssl pkey -in ent-private.pem -pubout -out ent-public.pem
  ```
  Tests use `qal.entitlements.token.signing-key.allow-ephemeral=true` (dev/test only, refused under
  `prod`/`production`/`staging`). Ed25519 runs on the JDK (`JdkEd25519Signer`); Nimbus' own Ed25519 classes need
  Tink, which the build does not include.
- **Rotation** (clients pin keys in the app build): ship the new public key in app builds first; then sign with it
  and list the old key under `qal.entitlements.token.previous-keys[n].key-id|public-key`; remove it 96 h later
  (24 h validity + 72 h grace). See ADR 0013 D7.
- **Client rules** (ADR 0013 D4): reference verifier `EntitlementTokenVerifier` (test scope): `alg` EdDSA only,
  `typ`, pinned `kid`, Ed25519 signature, `iss`/`aud`/`ver`/`sub`/`deviceId`, lifetime caps; valid before `exp`,
  offline only until `offlineGraceUntil`, void after.
- **Tests:** `EntitlementTokenServiceTest` (issue/verify, tampering, `none`/HS256/ES256/kid confusion, expiry and grace
  edges, rotation, RFC 8037 A.4 vector), `EntitlementTokenKeysTest`, `EntitlementTokenControllerTest` (contract),
  `EntitlementTokenIT` (PostgreSQL: plans, suspended, deletion-pending, merged).

## Usage: metering engine (B4.2, ADR 0011, SD 15.3)

Module `usage`, API `Metering` (implemented by the package-private `JdbcMetering`):

```java
Reservation r = metering.reserve(userId, "TRANSLATE", 1, idempotencyKey); // 429 QuotaExceededException, 403 FEATURE_NOT_ENTITLED
try {
    ... provider call, outside any transaction ...
    Settlement s = metering.settle(r.id(), actualUnits);                   // -> UsageSummary / ai_requests
} catch (RuntimeException e) {
    metering.release(r.id(), ReleaseReason.FAILED);                       // SD 11.1: no charge on failure
}
```

- **Reserve** (one short transaction): resolve the entitlement (B4.1); unknown/unmetered features (typing,
  `DETECT_LANGUAGE`, boolean features) are `IllegalArgumentException`, no access is 403 `FEATURE_NOT_ENTITLED`. Compute the
  window, upsert + `SELECT ... FOR UPDATE` its `usage_counters` row, take `min(estimate, included - used - reserved)` from
  the allowance and the rest from credits (`units x credit_cost_per_unit`, `BigDecimal`, 6 dp) when `creditsUsable`,
  else 429 `QUOTA_EXCEEDED` (`QuotaExceededException`: `featureCode`, `resetAt` = window end (absent for NONE),
  `creditBalance` rounded down, `upgradeOptions` from ACTIVE `billing_products`: higher-rank sellable plans with access to
  the feature and credit packs; none for guests; empty while all products are DRAFT). All or nothing. Writes a
  `usage_reservations` row (V9), bumps `reserved_units` with a conditional UPDATE that re-checks the limit, appends a
  `usage_events` RESERVE (UUIDv7, `expires_at` = now + `qal.usage.reservation-ttl`, 15m).
- **No overspend:** the counter row lock serialises every reserve of one user/feature/window; the UPDATE's `WHERE` is a
  second guard; credits go through the `CreditWallet` port (all-or-nothing hold in the same transaction). Lock order:
  reservation, counter, wallet. Proven by `MeteringIT` (40 parallel reserves vs 10/day: exactly 10; seconds; credits).
- **Settle** (`actualUnits`): up to the reservation, allowance first (unused credits go back first); above it
  (over-settle) the free room of the window, then credits; anything neither covers is `uncoveredUnits`, **not charged**
  (allowances and credits are never overdrawn; log + `qal.usage.settlements.uncovered`). `0` = release `NOTHING_USED`.
  A split request reports `usageSource = AI_CREDITS` (V1 `ck_ai_request_credits_source`). Appends SETTLE (units charged).
- **Release** gives the allowance units and held credits back and appends RELEASE. Settle-after-release and settle with
  other units throw `ReservationClosedException`; release-after-settle is a no-op (`replayed`).
- **Idempotency:** `(user, Idempotency-Key)` is unique (`usage_reservations`, like `ux_usage_events_idempotency`). The same
  key returns the original reservation (`replayed = true`, current status: RESERVED = in flight, B5.4 answers
  `REQUEST_IN_PROGRESS`); another feature or estimate is 409 `IDEMPOTENCY_KEY_REUSED`; a refused reserve stores nothing.
  Settle/release replays return the original outcome.
- **Windows** (`UsageWindows`, pure): user time zone `users.time_zone` (V1 default `Africa/Addis_Ababa`; invalid id ->
  `qal.usage.default-time-zone`). DAILY = local calendar day (23/25 h on DST days; a missing midnight starts at the first
  valid local time). MONTHLY = calendar month for the DEFAULT plan (FREE/GUEST), else anchored to the paid period start
  (`EffectivePlan.periodStart`): `[anchor + k months, anchor + k + 1 months)` computed from the anchor (31st -> 28th/29th
  -> 31st, local time kept across DST). WEEKLY (ISO week) and YEARLY are supported; NONE = lifetime (no `resetAt`).
- **Expiry sweep:** db-scheduler task `usage-reservation-sweeper` (`qal.usage.sweep-interval` 1m,
  `qal.usage.sweep-batch-size` 100, `qal.usage.sweeper-enabled`) releases RESERVED rows past `expires_at` with reason
  `EXPIRED`, one transaction per row with `FOR UPDATE SKIP LOCKED`: idempotent, safe next to a concurrent settle.
- **Credits port:** `CreditWallet` (`balance`, `hold`, `settle(held, charged)`), joins the engine's transaction. B4.3 implements it on
  credit lots (below).
- **Guest merge** (`UsageGuestMerge`, ADR 0010): the guest's current `used + reserved` goes into the counter of the
  window the engine computes for the *target* now, so a guest window that overlaps but differs from the target's (other
  zone, DAILY vs anchored MONTHLY) is neither lost nor counted twice.
- **Metrics:** `qal.usage.reservations{outcome}`, `qal.usage.settlements{source}`, `qal.usage.releases{reason}`. Logs carry
  ids, feature codes and unit counts only.
- **Tests:** `UsageWindowsTest`, `QuotaExceededExceptionTest`, `MeteringIT` (PG18: concurrency, credits, fractional
  credits, settle/over-settle/release, idempotency, sweep with a locked row, DST/midnight/month-end windows, upgrade
  options, DB guards, guest carry-over), `UsageSweeperSchedulerIT` (the scheduled task end to end). Tests switch the
  sweeper off globally (`src/test/resources/config/application.yaml`).

## Usage: credits (B4.3, ADR 0011 "Credits (B4.3)", SD 15.4)

`usage.Credits` (implemented with the `CreditWallet` by the package-private `JdbcCredits`; V1 tables, no migration):

```java
CreditLot lot = credits.grant(userId, new BigDecimal("500"), CreditSource.PURCHASE, paymentOrderId, expiresAt); // idempotent by (user, source, ref)
CreditBalance b = credits.balance(userId, Instant.now());   // exact (6 dp) and display (rounded down); expired lots excluded
credits.reverse(userId, CreditSource.PURCHASE, paymentOrderId);        // refund: REVERSAL of the unconsumed remainder
credits.adjust(userId, lot.id(), new BigDecimal("-2"), "support-17", "goodwill");  // ADJUSTMENT
```

- **Consumption:** the metering wallet draws from lots FIFO by earliest expiry (then grant time, id; no expiry last),
  all or nothing, one `CONSUMPTION` ledger row per lot; `settle` returns the unused part to the lots it came from (latest
  expiry first) as `ADJUSTMENT` rows. Ledger rows are only inserted (V1 trigger); lot remainders and the account
  balance are updated with them.
- **Expiry:** a lot stops counting at `expires_at` (exclusive end); db-scheduler task `usage-credit-expiry`
  (`qal.usage.credit-expiry-interval` 1h, `credit-expiry-batch-size` 100, `credit-expiry-enabled`) writes `EXPIRY` rows.
- **Locking:** account row, then lots in FIFO order, in every writer. Tests: `CreditsIT` (FIFO, expiry, parallel
  holds, append-only ledger, refunds, metering end to end), `MeteringIT` on the real wallet.

## Usage and entitlement read APIs (B4.5, SD 15)

`GET /api/v1/me/entitlements` and `GET /api/v1/me/usage` (`UsageReadController`, `UsageReadService` in module `usage`, which
depends on `entitlements`). Any valid access token, guests included (a guest sees the GUEST plan). Responses are `no-store`.

- **`/me/entitlements`** = `EntitlementResolver.resolve(userId)` (plan, `source`, `effectiveUntil`, one entry per catalogue
  feature) plus, for each gated metered feature with access, the current window of `usage_counters`: `used` = `used_units`,
  `remaining` = `max(0, included - used - reserved)` (reservations in flight count against it; absent when unlimited),
  `resetsAt` = window end (absent for reset period NONE). `creditBalance` = `Credits.balance(user, now).display` (whole credits,
  rounded down). Ungated typing features are listed with `accessEnabled: true` and no usage; boolean gates and features the plan
  excludes carry no usage either. `entitlementToken` comes from `EntitlementTokenService.issue(userId, deviceId)` (B4.4; `deviceId` is the access token's `did`).
- **`/me/usage`**: without parameters, the current window of every metered feature the plan gives access to
  (`usedUnits`, `includedUnits`, `creditsConsumed` = sum of `credits_charged` of the window's settled reservations, 6 dp).
  With `from` and/or `to` (date-time): the user's counter rows whose window overlaps `[from, to)`, newest first (at most 500; the
  contract has no paging), plus the current empty window of a feature that has no row. A past window's `includedUnits` is the
  allowance its reservations were checked against (absent when none or unlimited). `to <= from` gives an empty list.
- **Windows** are the engine's own (`UserWindows`/`UsageWindows`: the user's `users.time_zone`, anchored monthly windows on paid
  plans), serialised as UTC instants; nothing is recomputed differently for display.
- **Read only:** plain `SELECT`s, no `FOR UPDATE`, no insert. A window with no counter row yet is reported as used 0 without
  creating it, and a read never waits for the row lock a concurrent reserve or settle holds (proved in `UsageReadIT`).
- **Accounts:** SUSPENDED 403 `ACCOUNT_SUSPENDED`; DELETION_PENDING/DELETED 403 `DELETION_PENDING`; PENDING 403 `FORBIDDEN`;
  unknown or MERGED users 401 (a merged guest's token is already refused by the security chain, B2.6). Bad `from`/`to` 400.
- **Tests:** `UsageReadControllerTest` (contract-validated shapes: free, guest, credits, over quota, paid anchored window, 400,
  401, 403), `UsageReadIT` (PG18, V1 seed, real reservations/settlements/credit lots; numbers equal `Metering`/`Credits`).

## Security chain (B2.4, OI-P11)

`identity.security.ApiSecurityConfiguration` is the only `SecurityFilterChain`. It is stateless (no session, no CSRF,
no form/basic login) and works as a resource server for the access tokens above.

- **Anonymous**, from each operation's `security` in the contract:
  - **Bearer optional** (`[{}, bearerAuth]`): `POST /auth/attestation-nonces`, `/auth/otp/start`, `/auth/otp/verify`,
    `/auth/oauth/{provider}`, `/auth/email/register`, `/auth/email/login`; `GET /config`, `/ai/capabilities`,
    `/languages`, `/languages/{code}/transliteration-profiles`, `/language-resources`, `/language-resources/{id}`,
    `/resource-signing-keys`. A bearer token sent there must be valid: an invalid or expired one is a 401 and is
    never downgraded to anonymous (ADR 0007 D9). The contract declares that 401 on each of these operations, so a
    new optional-bearer operation must declare it too.
  - **No bearer** (`[{}]` / `[]`): `POST /auth/password/forgot`, `/auth/password/reset`, `/auth/refresh`,
    `/auth/guest`, `/billing/webhooks/{provider}/{account}`, `/billing/store-notifications/{provider}`,
    `GET /.well-known/jwks.json` (entitlement-token keys, B4.4) and `/actuator/health`. The `Authorization` header is
    ignored there.
  - `/partner/v1/**` is closed (401 `API_KEY_INVALID`) until partner API keys exist. User tokens are never accepted
    there.
- **Account check** (one primary-key read per request): **guest tokens** (`accountType = GUEST`) are accepted only
  while the account is an `ACTIVE` guest or a guest whose deletion is pending (B2.6, B6.2): a merged, upgraded,
  suspended or deleted guest's tokens are refused at once (`GUEST_SESSION_INVALID`). **Registered tokens** are refused
  (`UNAUTHENTICATED`, `invalid_token`) once the account is deleted (from the moment the deletion executor starts) or
  merged; otherwise they are stateless (15 minute revocation window). Without the account store (MVC slice tests) guest
  tokens are refused and registered tokens pass.
- **Everything else**, unknown paths included, needs a valid access token. 401 is a contract Problem with correlation
  id: `TOKEN_EXPIRED` for a correctly signed but expired token, otherwise `UNAUTHENTICATED`, with
  `WWW-Authenticate: Bearer`. 403 is a `FORBIDDEN` Problem. Both are written by `platform.ProblemResponseWriter`.
- New anonymous operations must be added to `identity.security.ApiPaths` together with the contract change.
- Web-slice tests that need open paths can still declare their own permit-all chain (as `ConfigWebTest` and
  `ProblemDetailsContractTest` do). To test with the real chain, `@Import({ApiSecurityConfiguration.class,
  ServletProblemResponseWriter.class})` (see `OtpControllerTest`, `ApiSecurityWebTest`).

## Rate limiting (platform, B2.1)

`com.qalsmartkeyboard.platform.ratelimit` (named interface `ratelimit`):

```java
RateLimitPolicy perIp = new RateLimitPolicy("otp.start.ip", 30, Duration.ofHours(1));
limiter.tryConsume(perIp, clientIp).orThrow(); // 429 RATE_LIMITED with Retry-After + RateLimit-* headers
```

Fixed window per key (Bucket4j `refillIntervally`), buckets in a bounded Caffeine cache per policy
(`qal.ratelimit.local.max-keys-per-policy`, 100000). **In memory, per instance:** with N instances a client can get
N times the limit. Replace with a distributed store (Bucket4j on PostgreSQL per SD 8.1, later Redis/Valkey) before
running more than one instance. Use hashed or opaque keys, never raw personal data. `ApiException` can now carry
response headers (`new ApiException(code, status, detail, errors, extensions, headers)`).

## Notifications: SMS (B2.1)

`notifications.OtpSender` delivers OTP messages through an `SmsProvider`. **Required:** `qal.notifications.sms.provider`
/ env `QAL_SMS_PROVIDER`, no default:

| Provider | Use | Notes |
|---|---|---|
| `sms-ethiopia` | production (ADR 0007 D48, ADR 0025) | SMS Ethiopia v2 API; allowed under `prod`/`production`/`staging` |
| `log` | development, tests | sends nothing, logs a masked number; refused under `prod`/`production`/`staging` |
| `capture` | development (Android local-stack test) | see below; refused under `prod`/`production`/`staging` |

With `log`, set `qal.notifications.sms.log.include-message=true` (DEV ONLY) to see codes locally.

**SMS Ethiopia (`provider=sms-ethiopia`, ADR 0025).** `POST {base-url}/api/v2/sms/send` with header `KEY` and
`{"msisdn":"2519XXXXXXXX","text":"..."}`. Only `+2519` + 8 digits (Ethio Telecom) is sent; anything else is refused
locally. Settings (`qal.notifications.sms.sms-ethiopia.*`):

| Env | Property | Default | |
|---|---|---|---|
| `QAL_SMS_ETHIOPIA_API_KEY` | `api-key` | none | **required**: startup fails if blank; never logged (redacted in `toString`) |
| `QAL_SMS_ETHIOPIA_BASE_URL` | `base-url` | `https://smsethiopia.com/` | must be `https` under production-like profiles; `http://localhost:8089/` for the compose WireMock |
| `QAL_SMS_ETHIOPIA_CONNECT_TIMEOUT` | `connect-timeout` | `2s` | |
| `QAL_SMS_ETHIOPIA_READ_TIMEOUT` | `read-timeout` | `5s` | |

No retries (the user can ask for a new code after the cooldown). After a 429 with `Retry-After` (seconds) the adapter
refuses sends locally until it elapses (capped at 5 minutes). Logs carry the outcome, the vendor error code, the HTTP
status and the vendor message id only (never the number, the text, the vendor's `error_message` or the key). Meter
`qal.notifications.sms.provider.responses{provider,outcome,code,alert}` next to `qal.notifications.sms.sent{provider,outcome}`;
alert on `alert="true"`: `insufficient_balance` (vendor 10005, top up; also an ERROR log `INSUFFICIENT SMS BALANCE`),
`provider_misconfigured` (10002 campaign inactive, 10006 sender ID not approved), `authentication_failed` (401/403),
`integration_error` and `invalid_response`.

*Testing with a real account.* A new SMS Ethiopia account has a free **Default** test campaign (100 SMS, shared sender
`8726`, test footer) that only delivers to numbers verified (whitelisted) in the SMS Ethiopia dashboard; any other number
fails with vendor error 10007 (`recipient_not_whitelisted`, not alertable). Create an API key in that campaign, then
`QAL_SMS_PROVIDER=sms-ethiopia QAL_SMS_ETHIOPIA_API_KEY=...` and sign in with a whitelisted number. **Production** needs a
paid campaign (prepaid SMS package) with a custom sender ID approved by Ethio Telecom (sends fail with 10006 while it is
pending) and an API key created in that campaign. Keys expire: track the expiry date.

*Locally without an account:* start the compose WireMock and run with `QAL_SMS_PROVIDER=sms-ethiopia
QAL_SMS_ETHIOPIA_API_KEY=dev QAL_SMS_ETHIOPIA_BASE_URL=http://localhost:8089/`. The stubs
(`infra/compose/wiremock/mappings/sms-ethiopia-*.json`) accept any `2519XXXXXXXX` and have magic numbers for errors
(`+251900010005` → 10005, `+251900010007` → 10007, `+251900000429` → 429, `+251900000500` → 500). The code is not
visible this way; use `provider=capture` to sign in locally. `SmsEthiopiaProviderTest` covers every outcome against an
in-process WireMock; nothing in the build calls smsethiopia.com.

**Dev SMS capture (ADR 0007 D28, DEV ONLY).** `provider=capture` also sends nothing; it keeps the last message per number in
memory (max 1000 numbers, lost on restart) and logs only a masked number. With
`qal.notifications.sms.capture.endpoint-enabled=true` (default off) the app serves `GET /dev/sms/last?phone=%2B251911234567`
(`{phone, body, code, receivedAt}`, 404 when nothing was captured; encode the `+`) so the Android `:api:localStackTest` reads
the OTP itself. It is not under `/api/v1`, not in the OpenAPI contract, anonymous, and answers only to loopback callers.
Fail closed: the `capture` provider and the endpoint both stop startup under `prod`/`production`/`staging`, and the endpoint
also stops startup unless the provider is `capture`. `DevSmsCaptureTest` covers the guards and `DevSmsCaptureAbsentIT` proves the
endpoint does not exist by default.

## Local development launcher

`infra/scripts/run-backend-dev.sh` starts the backend with the `dev` profile (`src/main/resources/application-dev.yaml`, dev-safe
switches only, no secrets): local KMS (KEK from `QAL_LOCAL_KMS_KEK`, else generated once into the git-ignored
`infra/scripts/.dev-state/kek` so stored data stays readable), ephemeral OTP secret, access-token and entitlement-token keys,
mock attestation, AI mock, SMS `capture` + `/dev/sms/last`, and the compose Postgres at `localhost:5434` (started if it is not
up; `DB_URL`/`DB_USER`/`DB_PASSWORD` override). It refuses to run when `SPRING_PROFILES_ACTIVE` or `SPRING_PROFILES_INCLUDE`
contains `prod`, `production` or `staging` (exit 2), and each switch is refused by its own component under those profiles
anyway. Never deploy the `dev` profile. Ctrl-C stops the backend; `docker compose -f infra/compose/docker-compose.yml down` stops Postgres.

## AI gateway: provider SPI and registry (B5.1, ADR 0014, SD 11)

`aigateway.spi` (Modulith named interface `spi`) is the vendor-neutral contract: `AiProvider` with
`translate` / `llmTransform` / `detectLanguage`, each taking a positive `Duration` deadline (no untimed call; cancel =
interrupt), typed requests/results mirroring the contract schemas, `ProviderUsage` (characters, optional tokens/audio ms;
`units(MeteredUnit)` for ADR 0011 settle), `ProviderCallMetadata` (provider, model, vendor request id, latency) and the
sealed `ProviderException` family (`Unavailable`, `Timeout`, `ContentRejected`, `UnsupportedLanguagePair`,
`QuotaExhaustedAtProvider`, `AuthenticationFailed`, `InvalidResponse`, `Cancelled`; each `Kind` knows its contract code,
retryability and whether it counts against provider health). Text only travels as `UserText` (its `toString` prints a
length), error bodies are never read, nothing is logged by adapters.

`ProviderRegistry.resolve(RouteQuery)` returns providers whose `ai_providers` row is `ACTIVE` (seeded `DISABLED`; the
schema's word for "inactive"), does not train on customer data, has a DPA (unless `SELF_HOSTED`), has an adapter bean with
the same code, satisfies the data policy of the request (ZDR; region allow-list) and has an enabled capability for the
feature, language pair, minimum tier (default BETA) and input size. Descriptors are cached `qal.ai.registry-cache-ttl`
(30 s). Routing order, failover, circuit breaker, kill switch and cost caps are `AiRouter` (below).

Real vendors (OpenAI, Google Cloud Translation) are below. Development adapters, off by default and
refused under `prod`/`production`/`staging`: `qal.ai.mock.enabled` / `QAL_AI_MOCK` (`MockAiProvider`: deterministic,
configurable latency and failure) and `qal.ai.http.enabled` / `QAL_AI_HTTP` (`HttpJsonAiProvider` against the compose
WireMock stubs, `QAL_AI_HTTP_BASE_URL` default `http://localhost:8089`, key `QAL_AI_HTTP_API_KEY`, never logged). V10 seeds
the two matching provider rows `DISABLED`; enable one locally with
`UPDATE ai_providers SET provider_status='ACTIVE', dpa_signed_at=NOW() WHERE provider_code='mock'`. Tests use WireMock
in-process (`wiremock-standalone`, serving `infra/compose/wiremock`).

## AI gateway: OpenAI and Google Cloud Translation (ADR 0007 D46, ADR 0023)

OpenAI (`openai`, Chat Completions, `store: false` on every call) is primary for TRANSLATE and GRAMMAR_CORRECTION /
REWRITE / TONE_ADJUST; Google Cloud Translation Basic v2 (`google-translate`, API key in the `x-goog-api-key` header,
text in a JSON body) is the TRANSLATE fallback and never serves transforms. Both adapters share `adapter/JsonHttpTransport`
(the mechanics and status mapping of `HttpJsonAiProvider`) and are allowed under every profile; a bean exists only when
its key is non-blank.

| Variable | Default | Meaning |
|---|---|---|
| `QAL_OPENAI_API_KEY` | empty | OpenAI secret key; blank = no OpenAI adapter |
| `QAL_OPENAI_MODEL` | empty | OpenAI model id; **required** when the key is set (startup fails otherwise) |
| `QAL_OPENAI_MAX_OUTPUT_TOKENS` | `4096` | `max_completion_tokens` (includes reasoning tokens); `0` = not sent |
| `QAL_OPENAI_REASONING_EFFORT` | empty | `reasoning_effort` for reasoning models (e.g. `low`); empty = model default |
| `QAL_OPENAI_BASE_URL` | `https://api.openai.com/v1` | tests only |
| `QAL_GOOGLE_TRANSLATE_API_KEY` | empty | API key restricted to the Cloud Translation API; blank = no Google adapter |
| `QAL_GOOGLE_TRANSLATE_BASE_URL` | `https://translation.googleapis.com` | tests only |

V19 seeds both providers `DISABLED` (OpenAI ZDR FALSE until granted, Google ZDR TRUE; no training; regions `{us}` /
`{global}`), models `openai-llm` (the configured model), `google-nmt`, `google-lid`, BETA capabilities (TRANSLATE en↔am/ti/om
on both, transforms am/ti/om/en on OpenAI; Google DETECT_LANGUAGE EXPERIMENTAL) and routing policies (TRANSLATE openai 1,
google-translate 2; transforms openai 1) seeded **disabled** because an enabled policy is an allow-list that would starve
the dev mocks. No prices or budgets are seeded. To go live: set the keys and model, record `dpa_signed_at` (and ZDR when
granted), insert prices and a daily hard limit, then set both providers `ACTIVE` and enable their policy rows (SQL in ADR
0023). The test configuration pins both keys to empty; `AiVendorRoutingIT` runs the real adapters against WireMock.

## AI gateway: routing (B5.2, ADR 0014 "Routing (B5.2)", SD 11/17/19)

`AiRouter.route(RouteRequest<T>)` runs one call (`RouteRequest.translate/transform/detect`, or any `ProviderCall`) over
an ordered provider list and returns a `RouteResult<T>`: `outcome` (`SUCCEEDED`, `PROVIDER_UNAVAILABLE`,
`PROVIDER_TIMEOUT`, `ZERO_RETENTION_UNAVAILABLE`, `LANGUAGE_PAIR_UNAVAILABLE`, `CONTENT_REJECTED`, `CANCELLED`),
`contractCode()` for B5.3 (null for success and `CANCELLED`), `providerCode`, `servedByLastResort`, every `Attempt`
(number, provider, latency, error kind), every `Skip` (kill switch, open breaker, cost cap) and `finalErrorKind`.

- **Order:** the registry decides who may serve; enabled `ai_routing_policies` rows (feature x language pair, NULL = any)
  order them: most specific language scope wins, lowest `priority` first, equal priorities by weighted random draw on
  `traffic_weight`; a matching policy is also an allow-list; `requires_zero_retention` rows skip non-ZDR providers. No
  matching row: registry order (provider code). DEGRADED providers that otherwise qualify come after all ACTIVE ones
  (ADR 0007 D29).
- **Fallback:** UNAVAILABLE, TIMEOUT, INVALID_RESPONSE, QUOTA_EXHAUSTED_AT_PROVIDER, AUTHENTICATION_FAILED go to the next
  provider; UNSUPPORTED_LANGUAGE_PAIR goes to the next without a health impact; CONTENT_REJECTED ends the route;
  CANCELLED (thread interrupt) ends it as a client abort. A provider is never retried within a request.
  `qal.ai.routing.max-attempts` (3), `attempt-timeout` (4 s), `deadline` (8 s); a request may override them.
- **Circuit breaker** per provider, in-house (`routing/CircuitBreaker`): sliding window of the last `window-size` calls
  (20), opens at `failure-rate-percent` (50) once `minimum-calls` (10) are in, or on `slow-call-rate-percent` (80) of calls
  slower than `slow-call-threshold` (3 s); open for `open-duration` (30 s), then `half-open-calls` (3) probes, all of
  which must succeed. Quota-exhausted and credentials-refused trip it at once (Retry-After honoured). State is per JVM.
- **Kill switch, no deploy:** BACKEND `app_configurations` row `ai.kill_switch` =
  `{"providers": ["code"], "features": ["TRANSLATE"]}`, re-read every `kill-switch-cache-ttl` (5 s); restart-time
  `qal.ai.routing.killed-providers` / `killed-features` (`QAL_AI_KILLED_PROVIDERS`, `QAL_AI_KILLED_FEATURES`). Killing a
  feature answers `PROVIDER_UNAVAILABLE` without calling anyone.
- **Cost cap (V12):** `ai_provider_prices` (micro-USD per unit; feature-specific row overrides the NULL-feature row),
  `ai_provider_budgets` (daily soft and hard limit; NULL = none), `ai_provider_daily_spend` (UTC day, successful calls
  only). Soft limit: one warning log and `qal.ai.cost.alerts{level=SOFT}` per day; hard limit: provider skipped until the
  next UTC day or a raise. Nothing is seeded (gate G0).
- **Metrics:** `qal.ai.provider.calls|latency`, `qal.ai.route.outcome|failovers|skipped`, `qal.ai.circuit.state|transitions`,
  `qal.ai.cost.micros|spend.micros|alerts` (tags: provider, feature, outcome, reason only). Logs carry provider codes,
  attempt numbers and error kinds, never text.
- **Request filters (B5.3):** `RouteRequest.pinnedTo(code)` (one attempt on that provider) and `excluding(codes)`;
  `RouteResult.capability()` is the capability row that matched the serving provider (quality tier, model code).

## AI gateway: request pipeline (B5.3, ADR 0017, SD 11.1/11.4/11.5)

`AiPipeline.execute(AiCommand)` runs one explicit AI action; `AiCommand.Translate` / `Transform` (GRAMMAR_CORRECTION,
REWRITE, TONE_ADJUST) / `Detect` mirror the contract request bodies and `AiResult.TranslateResponse` /
`TransformResponse` / `DetectLanguageResponse` the response bodies (JSON-ready; `toString()` prints lengths only). The
endpoints are described in "AI gateway: HTTP endpoints" below (B5.5). Must not be called inside a transaction (throws
`IllegalStateException`).

1. **Account:** only ACTIVE accounts (403 `ACCOUNT_SUSPENDED`, 403 `DELETION_PENDING` per ADR 0007 D22, 401
   `GUEST_SESSION_INVALID` / `UNAUTHENTICATED`); exception (D33): unmetered DETECT_LANGUAGE is also served while the
   deletion is pending (SUSPENDED stays refused). A user whose `user_preferences.ai_processing_preference` is
   `ON_DEVICE_ONLY` gets 403 `ON_DEVICE_ONLY_PREFERRED` for every server AI feature, before validation and any charge. `AI_PROCESSING` consent only with
   `qal.ai.pipeline.require-ai-processing-consent=true` (off: SD does not require it for explicit actions).
2. **Validation** before any charge: text <= 5,000 code points (detect 1,000) else `INPUT_TOO_LONG`; languages active in
   `supported_languages` else `LANGUAGE_NOT_SUPPORTED`; option enums, required tone, mode per task, destination profile
   ownership, `Idempotency-Key` present else `VALIDATION_FAILED`.
3. **Entitlement:** 403 `FEATURE_NOT_ENTITLED` (a REJECTED `ai_requests` row without the key).
4. **Reserve** (`Metering.reserve`, own transaction) with the key; estimate 1 (REQUEST) or input characters (CHARACTER).
   `QUOTA_EXCEEDED` calls no provider (REJECTED row, key not stored so it can be retried). DETECT_LANGUAGE is unmetered.
5. **Idempotency seam:** a new reservation is announced to `AiIdempotency.onReserved` (B5.4 stores the request
   fingerprint); a replayed key goes to `AiIdempotency.onReplay` instead of a provider. Since B5.4 the bean is
   `idempotency.EncryptedReplayCache` (next section); B5.3's `ReplayWithoutCache` (in flight = 409
   `REQUEST_IN_PROGRESS` + `Retry-After`; settled = uncharged recompute; released = the original error code, D25) is
   its fallback for in-flight and failed originals.
6. **Prompt:** before routing, for every candidate provider the active `ai_prompt_templates` row (APPROVED +
   `is_active`) for (feature, language, model family) is rendered; exact language beats `*`, then exact family
   (`ai_models.configuration->>'modelFamily'`, else the model code) beats `*`. Placeholders (`PromptRenderer.PLACEHOLDERS`)
   are option codes, language names, `addresseeInstruction`, `culturalInstruction` and Ethiopian-calendar context
   (`todayEthiopian`, `calendarContext`; `EthiopianDate`, user time zone). There is no placeholder for user text; a
   template with an unknown placeholder is skipped. No active template = no instructions (allowed by the SPI; metric
   `qal.ai.prompt.template.missing`). Nothing is seeded: operators add and approve templates.
7. **Route** (`AiRouter`), all provider calls sharing `qal.ai.pipeline.deadline` (8 s).
8. **Guardrails** (`pipeline/Guardrails`): empty output, script share (>= `min-script-ratio` 0.9 of letters in the
   destination's `script_code`, protected tokens excluded; transforms keep about the input's share), English-for-Oromo
   language check, per-feature length bounds, no-op, refusal phrases, lost URLs/e-mails/numbers, echoed instructions. A
   failure gets one retry on the same provider, then one fallback route over providers not yet tried; then 422
   `OUTPUT_GUARDRAIL_FAILED`, released.
9. **Settle** with the serving provider's `ProviderUsage.units(...)`; **release** on every failure (FAILED) and on
   cancellation (CANCELLED; `AiRequestCancelledException`, no body, interrupt flag restored).
10. **`ai_requests`** row: id = reservation id (else UUIDv7), feature, surface, languages, provider, model, template id,
    status, usage source, units, credits, character and token counts, provider duration, guardrail flag names,
    idempotency key (only for the reserving request), error code. Never text.

Settings `qal.ai.pipeline.*`: `deadline` (8s), `require-ai-processing-consent` (false), `catalog-cache-ttl` (30s, prompt
templates and language catalogue), `min-script-ratio` (0.9), `max-text-characters` (5000), `max-detect-characters`
(1000). Metrics: `qal.ai.pipeline.requests{feature,outcome}`, `qal.ai.guardrail.failures{feature,check}`,
`qal.ai.requests.write-failures`. Proof: `AiPipelineIT` (PG18, mock providers, log capture, contract validation of the
responses).

## AI gateway: idempotency and replay cache (B5.4, ADR 0017 "Replay cache (B5.4)", N5, SD 8.3/11.6)

At-most-once charging per `Idempotency-Key` is metering's (`usage_reservations` is unique per user and key, B4.2). B5.4
(`aigateway.idempotency.EncryptedReplayCache`, the default `AiIdempotency` bean) adds:

- **Request fingerprint** (`RequestFingerprint`): SHA-256 over a versioned, length-prefixed encoding of the body as the
  client sent it: feature/task, languages, style, mode, tone, target length, addressee, cultural options,
  preserveNames, glossary, destination profile and the exact text (headers are not part of it). Never stored as it is:
  sealed with `UserDataEncryption` for the user (context `ai_request_fingerprints` / `fingerprint_ciphertext` / request
  id) into `ai_request_fingerprints` by `onReserved`, i.e. after the reservation and **before** the provider call.
  Kept `fingerprint-retention` (24 h, minimum 24 h). Request id = reservation id (unique per user and key).
- **Replay cache:** after a settled success `onCompleted` serialises the response (contract JSON) and seals it for the
  user (context `ai_replay_cache` / `response_ciphertext` / request id) into the **UNLOGGED** table `ai_replay_cache`
  for `cache-ttl` (10 min, at most 10 min, `0s` = off; a CHECK enforces the ceiling). UNLOGGED: never in the WAL, so
  never in WAL archives, PITR/base backups or replicas; a crash empties it (= recompute, uncharged). Logical dumps should
  use `pg_dump --no-unlogged-table-data`.
- **Replays** (`onReplay`), after comparing fingerprints (constant time): another body = 409 `IDEMPOTENCY_KEY_REUSED`
  (in flight too; another feature is already refused by metering). RESERVED = 409 `REQUEST_IN_PROGRESS` +
  `Retry-After: 2`. SETTLED = the cached response with `replayed: true` (same `requestId` and `usage`, no provider
  call, no `ai_requests` row); cache expired, swept or unreadable = recompute with `usageSource UNMETERED`, which is never
  cached; fingerprint expired (key older than 24 h) = 409 `IDEMPOTENCY_KEY_REUSED` (an unverifiable body never gets an
  uncharged result). RELEASED = the cached response if the original succeeded uncharged, else the original failure
  (D25).
- **Expiry:** reads ignore rows past `expires_at` (application clock); db-scheduler task `ai-replay-cache-sweeper`
  (every `sweep-interval`, 1 min; off in tests with `sweeper-enabled=false`) deletes expired cache rows and
  fingerprints in batches of `sweep-batch-size` (1000).
- **Account deletion:** `AiIdempotencyDeletion` (order `AI_GATEWAY`) deletes both tables' rows; they are ciphertext
  under the already shredded key anyway. Guest merge moves nothing (the guest's keys and rows expire).
- Failures never change a charge: a failed fingerprint or cache write is logged (`qal.ai.idempotency.hook-failures`)
  and the request goes on; a KMS failure while reading a fingerprint fails that replay (500, nothing charged).

Settings `qal.ai.idempotency.*`: `cache-ttl` (10m, `QAL_AI_REPLAY_CACHE_TTL`), `fingerprint-retention` (24h),
`sweep-interval` (1m), `sweep-batch-size` (1000), `sweeper-enabled` (true). Metrics:
`qal.ai.idempotency.replay-cache{outcome=hit|miss|stored|in_progress|released|key_reused|key_expired|unreadable|fingerprint_unreadable}`,
`qal.ai.idempotency.swept{table}`. Schema: `V14__ai_replay_cache.sql`. Proof: `AiReplayCacheIT` (PG18, mock provider,
injected clock: retry within the TTL, after expiry, concurrent duplicates, other bodies, failed originals, ciphertext
scan, sweep, deletion), `RequestFingerprintTest`.

## AI gateway: HTTP endpoints (B5.5, ADR 0017 "HTTP endpoints (B5.5)")

`aigateway.AiController` serves the contract operations `translateText` (`POST /api/v1/ai/translate`), `transformText`
(`POST /api/v1/ai/transform`: GRAMMAR_CORRECTION, REWRITE, TONE_ADJUST), `detectLanguage`
(`POST /api/v1/ai/detect-language`) and `getAiCapabilities` (`GET /api/v1/ai/capabilities`). The contract has no
`/grammar` or `/rewrite`: they are tasks of `/transform`. `/transliterate` exists only as the partner operation
`/partner/v1/transliterate` (it needs the server-side keyboard core, a later step) and is **not built**.

- **Mapping:** body bytes -> `AiRequestParser` -> `AiCommand` (`Translate` / `Transform` / `Detect`) -> `AiPipeline.execute`
  -> the `AiResult` record as the response (200, `Cache-Control: no-store`). `Idempotency-Key` is required (a UUID) on
  translate and transform; detect needs none. `X-Client-Surface` (KEYBOARD | COMPANION) sets the surface. Accepted and
  ignored for now: `applyStyleProfile`, `options.explainIn`, `options.sourceRomanization` (`saveToHistory` is
  honoured since B5.7, see below). The tasks
  SUMMARIZE, EXPLAIN_TEXT and SCRIPT_CONVERSION answer 400 `VALIDATION_FAILED` (`NOT_AVAILABLE` on `task`). SSE streaming
  (`Accept: text/event-stream`) is not implemented (406); `capabilities` says `supportsStreaming: false`.
- **Bodies are read by hand** (`byte[]` + `AiRequestParser`), not bound by the framework: a binding error quotes the
  rejected value ("... from String "<text>""), and the framework logs resolved exceptions at DEBUG. Every parse failure is
  a static 400 `VALIDATION_FAILED` naming the field only (`MALFORMED_REQUEST_BODY` for syntax errors). The log-scrub IT
  runs with the Spring web loggers at DEBUG and caught exactly this.
- **Errors** are the pipeline's `ApiException`s written by the global handler (ProviderException outcomes were already
  mapped by the router to contract codes): 400 VALIDATION_FAILED / INPUT_TOO_LONG / LANGUAGE_NOT_SUPPORTED, 401
  UNAUTHENTICATED / GUEST_SESSION_INVALID, 403 ACCOUNT_SUSPENDED / DELETION_PENDING / FEATURE_NOT_ENTITLED /
  CONSENT_REQUIRED / ON_DEVICE_ONLY_PREFERRED, 409 IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS (+ `Retry-After`), 422
  OUTPUT_GUARDRAIL_FAILED / CONTENT_REJECTED, 429 QUOTA_EXCEEDED (extensions `featureCode`, `resetAt`, ...) / RATE_LIMITED,
  503 PROVIDER_UNAVAILABLE / PROVIDER_TIMEOUT / LANGUAGE_PAIR_UNAVAILABLE / ZERO_RETENTION_UNAVAILABLE. Anything else is an
  opaque 500 `INTERNAL_ERROR`.
- **Cancellation** (`AiRequestCancelledException`: the calling thread was interrupted, reservation already released,
  D29): the response is an empty **499** (not a contract status; nobody is listening) and the thread's interrupt flag is
  cleared. Note that a plain servlet container does not interrupt the request thread when the client disconnects; the
  interrupt comes from whoever cancels the thread (shutdown, a future async wrapper). Until then a vanished client's
  request completes and is charged once; the client's retry with the same key is answered from the replay cache.
- **Rate limits** (`platform.ratelimit`, per user, fixed window, in memory): `ai.translate.user` 30/min,
  `ai.transform.user` 30/min, `ai.detect.user` 60/min, `ai.capabilities.client` 120/min (per user, or per remote address
  when anonymous). 429 `RATE_LIMITED` with `Retry-After`; translate and transform 200s carry `RateLimit-*` headers.
- **`GET /ai/capabilities`** (bearer optional): the active providers' enabled BETA/PRODUCTION capabilities for the five
  served features, one item per (feature, source, destination) with the best tier, `execution: SERVER`,
  `supportsStreaming: false`, `maxInputCharacters` = min(capability, contract limit). A signed-in caller sees only what
  its plan includes; nothing when the account may not use server AI (SUSPENDED, MERGED...) or prefers `ON_DEVICE_ONLY`;
  only DETECT_LANGUAGE while the deletion is pending. `no-store`, private.
- **Privacy:** metadata only (`ai_requests`, ledger); nothing in this layer logs, counts or tags text.
  Proof: `AiControllerTest` (contract validation of every operation and error mapping, mocked pipeline),
  `AiControllerCancelTest`, `AiEndpointsIT` (PG18, mock providers, full stack through HTTP) including the log scrub:
  distinctive input and provider output text must not appear in any log line (web loggers at DEBUG, exception class and
  message included), Problem body, metric name or tag, or any column of any table (plaintext or the hex of its bytes,
  covering `ai_requests`, `usage_events`, `usage_reservations`, `outbox_events`, `audit_events`, the replay cache).

## AI feedback and history (B5.6, B5.7, ADR 0021)

**Feedback** (`POST /api/v1/ai/feedback`, `aigateway.AiFeedback`, 202 without a body): `rating` is `POSITIVE` (stored +1) or
`NEGATIVE` (-1) per the contract, plus `reasonCodes` (contract enum, V17 widens the V1 check). `aiRequestId` must be a
`SUCCEEDED` `ai_requests` row of the caller, otherwise 404 (foreign, unknown and failed requests look the same). One rating
per (request, user): sending it again **replaces** the earlier one (rating, reasons, comment, content), never 409. A
`comment`, and with `includeContent` the client-supplied `inputText` / `outputText`, need a granted `FEEDBACK_TEXT` consent;
without it the whole request is 403 `CONSENT_REQUIRED` (`consentCode`) and nothing is stored. Without `includeContent` the
texts are ignored. Text is sealed per column and row (`ai_feedback`, `comment_ciphertext | input_ciphertext |
output_ciphertext`, row id) and the row's `consent_record_id` is the grant it was stored under (V1 composite FK); V17 adds
the two content columns. 60/min per user (`ai.feedback.user`, 429). The body is read by hand (no value in a binding error).

**History** (module `personalization`): `AiHistorySink` (aigateway) is implemented by `AiHistoryRecorder`. **Off by default.**
The pipeline offers a result after a settled success of the request that reserved its key and said `saveToHistory: true`
(the contract default is false), before the replay cache keeps the response; the recorder keeps it only if the user has
`user_preferences.ai_history_enabled`, a granted `SAVE_AI_HISTORY` consent **and** the `HISTORY_SYNC` entitlement (SD 11.7;
guests have none). Failures, cache replays and uncharged recomputes write nothing; `UNIQUE (user_id, ai_request_id)` (V17)
backs this. The row holds `input_ciphertext` and `output_ciphertext` (separate AAD per column and row), languages, feature,
`ai_request_id` and `expires_at` = now + `history_retention_days` (1-365, default 30, from the preferences); the response's
`historyItemId` names it (and is kept in the replay cache, so a replay returns the same id). A history failure is logged by
class and counted (`qal.ai.history.write-failures`); it never fails the AI call.

Endpoints (`HistoryController`, always `no-store`, caller's rows only, foreign id 404): `GET /me/history` (newest first, keyset
cursor, `favorite` and `featureCode` filters, `limit` 1-100), `GET/PATCH/DELETE /me/history/{itemId}` (PATCH `{favorite}`:
true removes the expiry, false starts a new one of `historyRetentionDays` from now), `DELETE /me/history` (all, favourites
included). Items past `expires_at` are invisible at once and removed by the B6.3 job (`user-history-items`; favourites never
expire). Listing and deleting work with history off or the consent withdrawn. Withdrawing `SAVE_AI_HISTORY` stops new writes
(B3.5 switches the preference off; the recorder checks the consent itself); existing items stay until the person deletes them
(the client offers that using `DELETE /me/history`). Account deletion and guest merge need no change: `PrivacyDeletion`
already deletes `user_history_items` and `ai_feedback` (crypto-shredded), `PrivacyGuestMerge` re-encrypts history rows
(same contexts, new user) and does not move `ai_feedback` (shredded with the guest, ADR 0010).
Withdrawing `FEEDBACK_TEXT` **deletes the stored comment and content** of the person's feedback rows (ciphertext, key version,
consent link; the rating and reason codes stay) in the withdrawal's transaction, through the `ConsentChanged` event
(`AiFeedback.onConsentChanged`, ADR 0007 D41); `ai_feedback` rows are purged 13 months after `created_at`
(`ai-feedback` retention rule, `AiRetention`).
Proof: `AiFeedbackHistoryIT` (PG18, 15 tests, contract-validated, DB scan of every table, log scrub of comment and history text).

## Crash reporting: Sentry (ADR 0024, ADR 0007 D47)

Off unless `QAL_SENTRY_DSN` is set (`sentry.dsn`, blank by default: local, CI and tests send nothing). SDK
`sentry-spring-boot-4-starter` + `sentry-logback` 8.62.0 (`sentry-bom`). Content-free by construction:

- `platform.observability.SentryConfiguration` forces, whatever the `sentry.*` properties say: `sendDefaultPii=false`, no
  request bodies, no tracing, no Sentry Logs, zero breadcrumbs, and `SentryScrubber` as `beforeSend`.
- `SentryScrubber`: exception messages always become `[redacted]` (type and stack frames stay); log events keep the message
  template only (no formatted text, no arguments); no user, extras, cookies, query string, fragment, env or body; request
  headers limited to `User-Agent`, `X-Correlation-Id`, `X-Request-Id`, `X-App-Version`, `X-Client-Platform`,
  `X-Client-Surface`, `Content-Type`, `Accept`, `Content-Length`; contexts limited to os/runtime/app/trace/spring and the
  MDC `correlationId`.
- Events come from uncaught exceptions and ERROR log lines (`ProblemExceptionHandler` logs every 5xx at ERROR); 4xx are not
  reported. Log lines never become breadcrumbs.
- Environment: `QAL_SENTRY_ENVIRONMENT`, else the active profiles (`default`). Release: `SENTRY_RELEASE` (optional).
- In the Sentry project turn on *Prevent Storing of IP Addresses* and the server-side scrubbers (ADR 0024 item 5).
- Tests: `SentryScrubberTest` (unit), `SentryContextIT` (blank DSN: disabled), `SentryEnabledContextIT` (fake DSN on a
  local socket: enabled, hardened, no connection at startup).

## Contract tests

Controller tests validate every response against `contracts/openapi.yaml` (OpenAPI 3.1) by adding one annotation:

```java
@WebMvcTest(MyController.class)
@ContractValidated   // com.qalsmartkeyboard.testsupport.contract
class MyControllerTest { ... }
```

An undeclared path/status, wrong content type, missing header or body-schema violation fails the `perform(...)` call.
`OpenApiContractTest` and `ProblemDetailsContractTest` show the validator failing on violating responses.
Run only these with `./mvnw -Djava.version=21 test -Dtest='OpenApiContractTest,ProblemDetailsContractTest'`.
Never edit `contracts/openapi.yaml` directly: change the docs copy and run `contracts/sync.sh`.

## Generated client types (CI)

CI generates TypeScript types from the contract with `openapi-typescript` (pinned) and type-checks them with `tsc`,
so a contract change that breaks code generation fails the build. Nothing generated is committed. Locally (Node 22+):

```sh
backend/scripts/gen-client-types.sh            # writes backend/target/generated-client-types/api.d.ts
```

Android and iOS client generation are added with their own steps (A2.1, I-steps).

## Build

`./mvnw verify` (CI: Java 25; locally `-Djava.version=21` on JDK 21). `QalSmartKeyboardApplicationTests` needs PostgreSQL at
`DB_URL` (default `localhost:5434`); `*IT` classes use Testcontainers and need Docker. For local development use
`infra/scripts/run-backend-dev.sh` (above). Running the application by hand
(`./mvnw spring-boot:run`) also needs the KMS, OTP secret, SMS provider and access-token key settings above, e.g.
`QAL_KMS_PROVIDER=local QAL_LOCAL_KMS_KEK=$(openssl rand -base64 32) QAL_OTP_SECRET_KEY=$(openssl rand -base64 32) QAL_SMS_PROVIDER=log`
(plus `QAL_ATTESTATION_MOCK=true` for `POST /auth/guest` to work locally)
plus `QAL_JWT_PRIVATE_KEY`/`QAL_JWT_PUBLIC_KEY` from the openssl commands in "Identity: sessions and tokens".
