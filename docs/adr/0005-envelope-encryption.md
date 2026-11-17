# ADR 0005 — Per-user envelope encryption and crypto-shredding

**Status:** Accepted · **Date:** 2026-11-17 · **Plan step:** B1.5 · **Spec:** SD §18, §19.4, §20.1; Review D17; plan N13; `user_data_keys` in `V1__baseline.sql`

## Context
User-authored content that the server stores (history, synced dictionary words, destination-profile
labels, style sign-offs/phrases, feedback comments) must be application-encrypted under a per-user data
key, so destroying that key on account deletion makes every copy unreadable, including backups (SD §19.4
step 1). V1 already has `user_data_keys` (versioned, `wrapped_dek`, `kms_key_id`, `ACTIVE/ROTATED/DESTROYED`,
`ck_user_data_key_destroyed`, one ACTIVE per user) and `*_ciphertext BYTEA` + `key_version` columns on the
content tables. SD §8.1 says "Cloud KMS + secret manager"; the cloud and region are still open (ADR 0001
OI-04).

## Decision

### Placement and API
- Code lives in the `platform` module (SD §8.2: cross-cutting infrastructure). The API is the named
  interface `platform.crypto` (`UserDataEncryption`, `EncryptionContext`, `EncryptedValue`, exceptions).
  Implementation (`platform.crypto.internal`) and the KMS port (`platform.crypto.kms`) are module-internal.
- `privacy` calls `UserDataEncryption.cryptoShred(userId)` in its account-deletion job (B6.2).

### Keys
- One 256-bit random DEK per user and key version, created lazily on the first `encrypt`. Stored only
  KMS-wrapped in `user_data_keys.wrapped_dek`; `kms_key_id` records the KEK that wrapped it.
- The DEK is wrapped with KMS context `{purpose: "qal.user-data-key", userId, keyVersion}`. A wrapped DEK
  copied into another user's row, or to another version, fails to unwrap.
- Key creation, rotation and shredding take a transaction-scoped PostgreSQL advisory lock keyed by the
  user id (`pg_advisory_xact_lock(hashtextextended('qal.user_data_keys:<uuid>', 0))`). This serialises
  them per user across all instances without touching identity's `users` row. Creation also re-reads
  state under the lock, and a unique-index violation (`ux_user_data_keys_one_active`,
  `ux_user_data_key_version`) is handled by re-reading. So two concurrent first uses always end up with
  one ACTIVE key. The new DEK is wrapped (KMS call) **before** that short transaction opens.
- Creation and rotation run in their own transaction (`REQUIRES_NEW`), so a losing racer never poisons
  the caller's transaction. Reads join the caller's transaction, so a shred that the caller has made but
  not yet committed is already visible to it.

### Ciphertext format v1 (self-describing)
```
offset size field
  0     1   format = 0x01
  1     4   key_version, big-endian, 1..2^31-1   (= user_data_keys.key_version INTEGER)
  5    12   nonce, random per value (SecureRandom)
 17     n   AES-256-GCM ciphertext
17+n   16   GCM tag
```
Overhead: 33 bytes. It fits every V1 `*_ciphertext BYTEA` column. The row's `key_version` column must
equal the header's version (`EncryptedValue.keyVersion()`). The column stays useful for "rows still on
version N" queries when re-encryption is added. A new format byte is needed for any change of
algorithm or layout. Readers reject unknown format bytes.

### Associated data (authenticated, not stored)
```
"qal.user-content.v1" || 0x01 || user_id (16 bytes, RFC 4122 order) || key_version (4, BE)
  || u16 len || table || u16 len || column || u16 len || row_id          (UTF-8, big-endian lengths)
```
`EncryptionContext(table, column, rowId)` is supplied by the caller (table and column are lower-case SQL
identifiers; rowId is 1–128 printable ASCII, normally the row's UUID). Ciphertext moved to another
user, row, column or table, or with an edited version or nonce, fails authentication. Because of this,
callers must generate row UUIDs in the application, before the insert.

### KMS port
`KeyManagementService { activeKeyId(); wrap(dek, Map context); unwrap(WrappedKey, Map context) }`.
The context is a string map so it maps 1:1 to an AWS KMS `EncryptionContext`. For byte-AAD KMSs it is
encoded canonically (`KmsContext.canonicalBytes`: sorted, length-prefixed).
- **Local dev KMS** (`provider=local`): AES-256-GCM under a KEK from `qal.crypto.local-kms.kek` (env
  `QAL_LOCAL_KMS_KEK`, base64 of 32 bytes). No key exists in source. Its wrapped format is
  `0x01 || nonce || ct || tag`, and its AAD includes the key id. It is **refused at startup** under the
  `prod`, `production` and `staging` profiles. Without a KEK, startup fails unless the dev/test-only
  `qal.crypto.local-kms.allow-ephemeral-kek=true` is set. That switch generates a random KEK per JVM
  (logged as a WARN, and data is unreadable after a restart). Tests set it in
  `src/test/resources/config/application.yaml`.
- **Cloud KMS** (`provider=cloud`): `CloudKeyManagementService` delegates to a `CloudKmsClient` bean.
  No client and no SDK dependency are shipped yet, so selecting `cloud` without one fails startup.
  `kms_key_id` stores the key name, and unwrap uses the stored name, so changing the configured key only
  affects new wraps. Provider errors are rethrown without their message.
  - GCP: `KeyManagementServiceClient.encrypt/decrypt(name = projects/../cryptoKeys/.., additionalAuthenticatedData = canonicalBytes(context))`.
    Cloud KMS selects the key version itself, so KEK rotation is transparent.
  - AWS: `KmsClient.encrypt/decrypt(keyId = ARN, encryptionContext = context)`.
- `qal.crypto.kms.provider` has **no default**. If it is unset, the application does not start.

### Cache and memory hygiene
- Unwrapped DEKs are cached in memory: LRU bounded by `qal.crypto.dek-cache.max-entries` (default
  10 000), and each entry expires after `ttl` (default 5 min). Setting either to 0 disables the cache.
  The cache keeps its own copy and returns copies. Evicted entries are zeroed, and callers zero their
  copies after use. Generated DEKs and decoded KEK arrays are zeroed.
- Limits: a JCA `SecretKeySpec` copy and caller `String`s cannot be wiped. A shredded user's DEK can
  stay in another instance's memory until its TTL ends, but it is never *used*, because every operation
  re-reads the key row (see below).
- Nothing logs keys, plaintext or ciphertext. Exception messages carry no key material, content or user
  id. `EncryptedValue`, `WrappedKey` and the properties override `toString`.

### Rotation
`rotateKey(userId)` marks the ACTIVE version `ROTATED` (`rotated_at` set, wrapped DEK kept) and creates
version N+1. Old ciphertext still decrypts, and new values use N+1. If another rotation finishes at the
same time, that rotation's key is returned instead of creating a second one. **Re-encrypting existing
rows to the new version is out of scope** (a later batch job can find them by `key_version`). KEK
rotation is the KMS's concern. Re-wrapping DEKs under a new KEK id is not implemented.

### Crypto-shred
- `cryptoShred(userId)` sets every non-destroyed key to `DESTROYED`, with `wrapped_dek = NULL` and
  `destroyed_at = now()`, which satisfies `ck_user_data_key_destroyed`. It also evicts the user's cache
  entries. It is idempotent (a second call returns 0 and changes nothing). It joins the caller's
  transaction, so it commits or rolls back with the rest of the account deletion.
- Every encrypt and decrypt reads the key row first, so after the shred commits **every** instance
  fails at once with `CryptoShreddedException` (it does not wait for the cache TTL).
- **A shredded user can never get a new key.** Shredding is the irreversible step of account deletion
  (SD §19.4; V1 notes §11 keep the anonymised `users` row with status `DELETED`). Any later attempt to
  store content for that user id is a bug, so `encrypt` and `rotateKey` throw `CryptoShreddedException`.
  If the user had no key, shredding inserts a marker row (version 1, `DESTROYED`,
  `kms_key_id = 'none:shredded'`) so the rule still holds. No API reactivates a user. A returning person
  gets a new account and user id. Shredding an unknown user id is a no-op.
- Backups: a restored backup taken before the shred still holds the wrapped DEK. It is unreadable only
  if the KEK (or the KEK version) that wrapped it is unavailable to the restore. For this reason, backup
  retention and KEK-version destruction policy need an operational decision (see Consequences).

## Consequences
- Every user-content write needs `(userId, EncryptionContext)` and stores `ciphertext` + `keyVersion`.
  Moving data between users (guest → registered merge, B2.6) or between rows means decrypt + re-encrypt.
  It can never be a byte copy.
- Each operation costs one indexed read of `user_data_keys`, plus a KMS unwrap on a cache miss. Key
  creation and rotation also cost one KMS wrap and a short transaction.
- Creation and rotation use a second pooled connection while the caller holds one (`REQUIRES_NEW`).
- Open items: choose the cloud KMS and implement `CloudKmsClient` with the hosting decision (OI-04).
  Decide the KEK-version destruction and backup retention policy, so that crypto-shredding also covers
  backups restored before the shred. A re-encryption job after rotation. The keyed HMAC for
  `user_dictionary_entries.word_hmac` (Review F17) is not provided here (see the B1.5 report: an HMAC
  key derived from a rotating DEK would break the uniqueness index across rotations). Decide the HMAC
  key's lifecycle in B3.6.
