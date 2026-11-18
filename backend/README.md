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
  not `REVOKED` (`ACTIVE` and `RETIRING` verify), a known `size_bytes`, and a resource type the contract can express (`GLOSSARY`
  is not in the contract enum, so it is never listed). `appVersion` (required) must be at least `min_app_version` (SemVer
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
  validity window and, if present, `endorsedByKeyId`/`endorsementSignature` (V6 columns, written by B3.2 key tooling). Public
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

## Identity: phone OTP (B2.1, ADR 0006)

Module `identity`: `POST /api/v1/auth/otp/start` (202 `OtpChallenge`) and `POST /api/v1/auth/otp/verify` (200
`AuthSession`). Rules from SD 9.2:

- **Numbers:** strict E.164 (contract pattern); `+251` numbers must be mobile (`+2519…`/`+2517…`, 9 digits), else 400
  `VALIDATION_FAILED` (`INVALID_PHONE_NUMBER`).
- **Codes:** 6 digits from `SecureRandom`, valid `qal.identity.otp.code-ttl` (5m). Stored only as a keyed hash bound to
  the challenge id; the number only as a keyed hash plus an AES-GCM sealed copy (V3, needed to create the account on
  verify). At most 5 wrong codes per challenge (`OTP_TOO_MANY_ATTEMPTS`, the challenge is then locked). A used,
  superseded (a newer challenge exists for the number) or unknown challenge is `OTP_INVALID`; an expired one
  `OTP_EXPIRED`. New accounts need `ageConfirmed: true` (400 otherwise; the code stays usable).
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

- **Who may link:** a valid guest access token whose account is `GUEST`/`ACTIVE` and whose session family still has an
  ACTIVE refresh token (not logged out, not revoked for reuse). Checked after the code / ID token, before any write.
  Anything else is 401 `UNAUTHENTICATED` with `WWW-Authenticate: Bearer error="invalid_token"`, nothing written, the
  OTP code not consumed (the client drops the guest token and retries).
- **New phone number / Google subject: upgrade in place.** The guest row becomes the `REGISTERED` account (identity,
  phone or display name, `age_confirmed_at`; `ageConfirmed: true` is required as for any new account). Same user id, so
  devices, verdicts, usage, history and keys stay; `isNewUser: true`, `mergedGuestUserId` = `user.id`. The guest's own
  session families are revoked (`ACCOUNT_MERGED`) right after the registered session is issued.
- **Existing account: merge.** In the sign-in transaction: each module moves
  its rows (devices, preferences, typing languages, style and destination profiles, app rules, consents (appended),
  history, dictionary, term-suggestion attribution, current usage counters, glossaries), re-encrypting ciphertext for
  the target (`UserDataEncryption.reencrypt`/`reencryptRow`), target wins on conflicts; unspent nonces burned; guest
  sessions revoked (`ACCOUNT_MERGED`); guest `MERGED` with `merged_into_user_id`; guest keys crypto-shredded; then the target's session. Per-table rules: ADR 0010.
- **Quota:** the guest's consumption in current windows (`used + reserved`) is added to the target's current counter of
  the same feature, so signing in never resets an allowance.
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
effective plan (`EffectivePlan`: plan, rank, `PlanSource`, subscription or organisation id, `effectiveUntil`, grace flag)
and one `FeatureEntitlement` per active `feature_catalog` feature (`accessEnabled`, `includedUnits`, `unit`,
`resetPeriod`, `creditCostPerUnit`, `creditsUsable`, `origin`). No HTTP yet: metering (B4.2), the token (B4.4) and
`/me/entitlements` (B4.5) call this API. It reads the tables through its own package-private JDBC adapter (billing and
organisations have no Java API yet).

- **Account:** only `ACTIVE`, undeleted accounts hold entitlements. SUSPENDED, DELETION_PENDING, DELETED, MERGED, PENDING
  and unknown users get no plan and every gated feature disabled (`AccessState` says why). Ungated features (typing,
  `DETECT_LANGUAGE`) are never gated (R-2) and stay allowed, even then.
- **Sources** (SD 15.5): `user_subscriptions` (source_type PAYMENT/PROMOTION/ADMIN_GRANT = `SUBSCRIPTION`, STORE =
  `STORE_PURCHASE`, PARTNER = `PARTNER`; store and partner grants converge on that table, SD 16.1/16.7) and active
  organisation seats (`ORGANIZATION_SEAT`: ACTIVE assignment + ACTIVE member + ACTIVE organisation + ACTIVE/GRACE_PERIOD
  organisation subscription). Valid at `at` when the start (if any) has been reached and either status ACTIVE and `at <`
  period end (none = open-ended), or status GRACE_PERIOD and `at <` `grace_period_end` (else period end +
  `qal.entitlements.default-grace-period`, 3 days, SD 16.5). Period ends are exclusive. PENDING, PAST_DUE, PAUSED,
  CANCELLED (cancel-at-period-end stays ACTIVE), EXPIRED, REVOKED and UPGRADED grant nothing; a STORE subscription whose
  purchase is REFUNDED/REVOKED grants nothing. `plan_status` is not consulted (sold BUSINESS is still DRAFT in the seed).
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
  source's `effectiveUntil`; `resolve(userId, at)` always computes. Billing/organisation code must call
  `invalidate(userId)` after changing a user's sources or overrides (otherwise visible after the TTL).
- **Contract mapping:** `Entitlements.plan.source` only has DEFAULT, SUBSCRIPTION, ORGANIZATION_SEAT; use
  `PlanSource.contractValue()` (STORE_PURCHASE and PARTNER map to SUBSCRIPTION).
- **Tests:** `EntitlementSeedMatrixTest` (5 plans x 24 features read from `V1__baseline.sql`), `EntitlementResolverTest`
  (precedence, edges, grace, accounts, overrides, cache), `EntitlementResolverIT` (real seed and source tables).

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
    `/auth/guest`, `/billing/webhooks/{provider}/{account}`, `/billing/store-notifications/{provider}`, and
    `/actuator/health`. The `Authorization` header is ignored there.
  - `/partner/v1/**` is closed (401 `API_KEY_INVALID`) until partner API keys exist. User tokens are never accepted
    there.
- **Guest tokens** (`accountType = GUEST`) are accepted only while the account is an `ACTIVE` guest (one primary-key
  read per guest request, B2.6): a merged, upgraded, suspended or deleted guest's tokens are refused at once. Without
  the account store (MVC slice tests) guest tokens are refused.
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
/ env `QAL_SMS_PROVIDER`. Only `log` exists (development and tests; refused under `prod`/`production`/`staging`): it
sends nothing and logs a masked number. To see codes locally set `qal.notifications.sms.log.include-message=true`
(DEV ONLY). The real aggregator adapter comes with OI-11 (WireMock stub: `infra/compose/wiremock`, `/sms/v1/messages`).

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
`DB_URL` (default `localhost:5434`); `*IT` classes use Testcontainers and need Docker. Running the application
(`./mvnw spring-boot:run`) also needs the KMS, OTP secret, SMS provider and access-token key settings above, e.g.
`QAL_KMS_PROVIDER=local QAL_LOCAL_KMS_KEK=$(openssl rand -base64 32) QAL_OTP_SECRET_KEY=$(openssl rand -base64 32) QAL_SMS_PROVIDER=log`
(plus `QAL_ATTESTATION_MOCK=true` for `POST /auth/guest` to work locally)
plus `QAL_JWT_PRIVATE_KEY`/`QAL_JWT_PUBLIC_KEY` from the openssl commands in "Identity: sessions and tokens".
