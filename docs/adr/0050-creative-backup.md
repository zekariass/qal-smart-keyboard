# ADR 0050 — Opt-in encrypted cloud backup of creative assets (T8.15 S1)

**Status:** Accepted · **Date:** 2026-11-26 · **Plan step:** T8.15 (Draw & Sketch phase 2), backend part · **Spec:** SD 6.6
("Cloud synchronisation ... MUST use the same encrypted-per-user-key model as history (D17), opt-in only"), SD 19.2/20.2 (user
creative assets), SD 19.3 (consents), plan N12, N13, R-8; ADR 0005 (envelope encryption), 0015 (account deletion), 0020
(retention), 0021 (history), 0022 (data export); migration `V34__creative_backup.sql`; contract tag `CreativeBackup`.

## Context
Draw & Sketch keeps drawings in an app-private on-device gallery (R-8). Phase 2 adds an **optional** cloud backup so a person
can restore drawings on a new phone. The plan's done-when is "backup round-trip with consent off by default". History (ADR
0021) is the model the System Design prescribes. The app side (gallery, the backup client) is built separately against this
contract; this ADR fixes the server rules.

## Decisions

1. **Server-side envelope encryption, not end-to-end.** The app uploads an opaque blob (the drawing in whatever format the
   app chooses); the server seals it with the user's data key (`UserDataEncryption`, AES-256-GCM, context
   `creative_backup_items / content_ciphertext / <row id>`, ADR 0005) and stores only the ciphertext plus `key_version`.
   **The server can decrypt** (it does so to serve a download, to compare an idempotent PUT and to build the data export).
   Destroying the user's data key (account deletion, step 1) makes every stored copy unreadable, backups included (crypto-
   shredding, N13). This is exactly history's model (D17). An end-to-end variant (a key the server never sees, derived from a
   passphrase or held in the platform keystore with its own recovery story) would be a **separate ADR** if the owner wants it;
   the wire format would not change (the blob is already opaque), only who holds the key.

2. **Consent `CREATIVE_BACKUP`, off by default.** A new purpose in `ConsentType`, the contract `ConsentCode` and the V34
   CHECK. No row means not granted. Listing, downloading and uploading need a granted consent (403 `CONSENT_REQUIRED`,
   `consentCode`); the PUT checks it again under the per-user lock (decision 5). Deleting one item, deleting everything and
   the usage summary work without it, so a person can always see and erase what is stored (as history, ADR 0021 #13).
   Guests are refused on every operation (403 `GUEST_NOT_ALLOWED`): a guest's backup could not follow the person.

3. **Withdrawal deletes everything (delete-on-withdraw).** Unlike history (where withdrawal only stops writes), withdrawing
   `CREATIVE_BACKUP` deletes all of the user's items through the `ConsentChanged` event, in the withdrawal's own
   transaction (it commits or rolls back with the decision, as `AiFeedback` does for `FEEDBACK_TEXT`). Reason: the backup is
   a copy of data the device still has, so keeping it after "stop backing up" has no benefit to the person and the consent
   text can promise erasure. A re-grant brings nothing back; the app uploads again.

4. **Storage.** `creative_backup_items`: row UUID (application-generated, part of the AAD), `user_id` (`ON DELETE CASCADE`),
   `client_item_id` (`^[A-Za-z0-9_-]{1,64}$`, a UUID fits; unique per **user**, not per user and kind, because the item URL
   has no kind; the kind of an item cannot change), `item_kind` (CHECK, `DRAWING` only; new kinds by migration), `revision`,
   `content_ciphertext`, `key_version`, `size_bytes` (plaintext size), `client_updated_at` (the app's last-modified time) and
   server `created_at`/`updated_at`. **No other plaintext**: no title, name or thumbnail. A CHECK rejects a ciphertext shorter
   than its plaintext plus the 33-byte envelope (a guard against storing plaintext by mistake).

5. **Last writer wins, with optional compare-and-set.** The server assigns revisions (1 on create, +1 per change). A PUT
   without `expectedRevision` overwrites; with it, it must match (`0` = must not exist) or it is 409 `REVISION_CONFLICT` with
   `currentRevision` (N12, the same Problem as preferences). The revision travels in the JSON body, not `If-Match`/`ETag`,
   because that is the repository's convention for revisioned resources and the generated Kotlin client exposes body
   fields directly (response headers only through `...WithHttpInfo`). **Why last-writer-wins:** a drawing is an opaque blob
   the server cannot merge; one person edits on one device at a time; the app decides which copy is newer from the metadata
   (`revision`, `updatedAt`) it lists. CAS is there for the app that wants to detect a concurrent change from another
   device; neither mode loses data silently on a single device. Every write of a user takes a transaction-scoped advisory
   lock (`qal.creative_backup:<user>`), which makes revisions and caps exact and orders a PUT against a withdrawal (the
   listener takes the same lock).

6. **Idempotent PUT.** A PUT whose kind, `updatedAt` and content equal the stored item changes nothing and returns 200 with
   the stored revision, whatever `expectedRevision` says. The comparison decrypts the stored copy (no content hash is
   stored: a hash would be a content-derived value at rest). Sizes are compared first, so only a same-size PUT decrypts.

7. **Caps** (configuration, `qal.creative-backup.*`, defaults): `max-item-bytes` 2 MiB of content (413
   `BACKUP_ITEM_TOO_LARGE`, `maxItemBytes`; the body is read with a bound and refused before parsing when it is longer than
   any valid body could be), `max-items-per-kind` 100 and `max-total-bytes` **32 MiB** per user (409
   `BACKUP_QUOTA_EXCEEDED`, `quota` = `ITEMS_PER_KIND` | `TOTAL_BYTES`, `limit`). 32 rather than 64 MiB: the data export
   carries the content as base64 (4/3) inside a JSON archive that is capped at 64 MiB (ADR 0022 #11); a full backup must
   leave room for the other sections. 413 for "this one item can never fit", 409 for "the account's storage is full, delete
   something" (state-dependent, like the other 409s). Two new error codes, listed in `Problem.code`.

8. **API** (contract-first, tag `CreativeBackup`, all `no-store`): `GET /me/creative-backup/items` (`listCreativeBackupItems`,
   metadata only, keyset by item id, `kind` filter, `limit` 1-100 default 50), `GET|PUT|DELETE /me/creative-backup/items/{itemId}`
   (`getCreativeBackupItem`, `putCreativeBackupItem` 201 create / 200 replace or unchanged, `deleteCreativeBackupItem`),
   `DELETE /me/creative-backup` (`clearCreativeBackup`) and `GET /me/creative-backup/summary` (`getCreativeBackupSummary`:
   counts and bytes per kind and in total, the three caps, `consentGranted`). The body is JSON with the content as standard
   base64 (`contentEncoding: base64`, typed as a plain string), not `application/octet-stream`: the generated Kotlin client
   (`jvm-okhttp4`) maps binary bodies to `java.io.File` in both directions, and `format: byte` to a `ByteArray` that
   kotlinx.serialization would write as a number array; a base64 string works with the generated client and with the
   app's hand-written `SyncHttp` alike, at a 33 % size cost that the 2 MiB cap keeps small. Bodies are parsed by hand so no
   value reaches a framework error or a log.

9. **Not metered, no entitlement.** The backup is free for every signed-in user (feature `DRAW_SKETCH`, ungated, R-8).
   The product decision is one switch, `qal.creative-backup.enabled` (default `true`, env `QAL_CREATIVE_BACKUP_ENABLED`):
   `false` answers 404 `NOT_FOUND` on every operation (the admin surface's convention; the app hides the feature on a 404 of
   the summary). Stored items stay while the switch is off, and withdrawal and account deletion still delete them.
   One per-user rate limit covers all operations (`creative-backup.user`, 600 per hour by default, 429 `RATE_LIMITED`).

10. **Privacy lifecycle.** Account deletion: `CreativeBackupLifecycle` (personalization, new order `PERSONALIZATION`) deletes
    the rows after the shred (added to ADR 0015's table). Data export: section `creativeBackup` (data class "User-created
    media (opt-in backup)"), one row per item with its metadata and `contentBase64`, decrypted like history's text (ADR
    0022 #9). Guest merge: nothing to move (guests cannot back up); but `PrivacyGuestMerge` appends a guest's later
    withdrawal of `CREATIVE_BACKUP` to the account without a `ConsentChanged` event, so the same participant (merge order
    `PERSONALIZATION`, after privacy) deletes the account's items when its merged decision is not a grant. Retention: no
    time limit (SD 19.2 "until the user deletes them" for creative media); items go with withdrawal, deletion or account
    deletion.

## Consequences
- V34 is forward-only: it replaces the `user_consents` code CHECK (same name) and adds one table (108 tables).
- `DataExportIT` and `DeletionIT` seed and check the new table; `CreativeBackupIT` and `CreativeBackupDisabledIT` prove the
  rest (PG18, contract-validated, DB scan of every text and bytea column, TRACE log scrub).
- The app must ask for `CREATIVE_BACKUP` (with a text that says withdrawal erases the backup) before the first upload, and
  should treat 409 `BACKUP_QUOTA_EXCEEDED` as "show usage and offer to delete", using the summary.

## Open
1. End-to-end encryption (decision 1) if the owner wants the server unable to read drawings.
2. Dormant accounts: a backup of an account nobody signs in to stays until the account is deleted (no inactivity rule exists
   for any data class yet).
3. Rate limiting is per instance in memory (platform limitation, README "Rate limiting").
4. The guest-merge gap above is general: a merged withdrawal of `SAVE_AI_HISTORY` or `FEEDBACK_TEXT` does not reach the
   preferences and feedback listeners either. Publishing `ConsentChanged` from the merge would close it for every
   purpose; that is a privacy-module change outside this step.
5. Thumbnails for a restore picker would need either a second encrypted column or a client-side download of each item.
