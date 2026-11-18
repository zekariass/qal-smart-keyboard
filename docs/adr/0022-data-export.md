# ADR 0022 — Data export

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B6.1 (depends on B3.x, B5.7) · **Spec:** SD 19.2 (data classes), 19.4
(data subject rights: export), 8 (object storage, 7-day export lifecycle), 20 (data model); contract `requestDataExport`,
`getDataExport`, `downloadDataExport`; ADR 0005 (encryption contexts, crypto-shredding), 0010 (guest merge), 0012 (app rules are
hashes), 0015 (account deletion, participants), 0020 (retention), 0021 (feedback, history), 0007 D39 (export archive objects
get a 7-day lifecycle) and D41.

## Context
SD 19.4 promises an export of "account, preferences, consents, decrypted history, subscriptions and receipts", delivered through a
signed URL that expires after 7 days; the plan's done-when is "the export contains all user data classes in SD 19". V1 has
`data_export_requests` (status, `export_object_key`, `download_expires_at`) and a one-open-request-per-user index; B6.3 purges the
rows 30 days after expiry and says the archive objects need a bucket lifecycle. This ADR fixes the rules the code follows.

## Decisions

### Request, job, status
1. **Asynchronous.** `POST /me/data-exports` records a `REQUESTED` row (202). A db-scheduler recurring task
   (`privacy-data-export`, 10 s) claims requests with `FOR UPDATE SKIP LOCKED`, builds the archive and publishes it. No outbox
   event: nothing consumes one, and polling a table that is already the queue keeps one mechanism. An export left `PROCESSING`
   for 15 minutes (a crashed instance) is queued again.
2. **One in flight per user:** asking while a request is `REQUESTED` or `PROCESSING` returns that request (the V1 partial unique
   index is the race guard); this is not an error, the client's retry is harmless.
3. **Rate limit:** 3 requests per rolling 24 hours per user (failed exports do not count), then 429 `RATE_LIMITED` with
   `Retry-After`. Counted from the table, so it holds across instances and restarts; configurable.
4. **Who:** every account that still has its data, including `DELETION_PENDING` (during the 14-day grace period the person can
   take their data with them) and guests (a guest's data is theirs). A closed or shredded account cannot export: its tokens are
   refused already, the service and the job check again (`ACCOUNT_CLOSED`).
5. **Status** follows the contract (`REQUESTED`, `PROCESSING`, `READY`, `EXPIRED`, `FAILED`); the internal `DOWNLOADED` shows as
   `READY`. A `READY` export whose `download_expires_at` has passed reads as `EXPIRED` at once, before the sweeper has run.
6. **Format:** one JSON document (the contract's only format). SD 19.4 also says "CSV"; the contract is authoritative and CSV is
   a follow-up (Open 1).

### The archive and its manifest
7. **Participants.** `identity.UserExportParticipant` (the twin of `UserDeletionParticipant`, ordered, one per module or per
   group of tables) writes sections through an `ExportWriter`. All run in one read-only, repeatable-read transaction, so the
   archive is one consistent snapshot. `ExportRows` turns rows into JSON-ready camelCase maps and **refuses `byte[]`**: a
   participant must decrypt or drop every binary column, so ciphertext, hashes and keys cannot leak by accident.
8. **Document:** `{ "manifest": {...}, "data": { "<section>": [ rows ] } }`. The manifest has format `qal-data-export`, format
   version, export and user ids, generation and link-expiry times, **one entry per section with its data class (SD 19.2 name),
   description and row count**, an `excluded` list (code and reason) and notes. A section with no rows is still listed.
9. **Plaintext only inside the archive.** History, dictionary words, destination-profile labels, style profile text and feedback
   comment/content are decrypted with `UserDataEncryption` under their original contexts. A row that cannot be opened fails the
   whole export (`FAILED`, the class name only): a silent gap in a legally required export is worse than a retry.
10. **App rules are hashes.** The server never receives package names (ADR 0012); the section carries `app_package_hmac` and the
    manifest says so. The HMAC key is a secret and is not exported.
11. **Limit:** the document is built in memory; above `max-archive-bytes` (64 MiB) the export fails with `ARCHIVE_TOO_LARGE`
    (Open 2).

### Encryption at rest, download, expiry
12. **Design choice: server-side sealing under the user's own data key**, not a key handed to the client. The archive plaintext is
    AES-256-GCM encrypted with `UserDataEncryption.encrypt` (context `data_export_requests / archive / <export id>`; the key
    version is inside the ciphertext) and only the ciphertext is written to the bucket. Reasons: (a) the key is the one ADR 0005
    already protects (KMS-wrapped, per user), so **crypto-shredding the account makes every export, and any copy of the object
    that survives in a backup, unreadable** with no extra key to track; (b) a client-held key would have to be delivered in the
    download response and then carried by the person to whatever tool opens the file, which for "my data" on a phone is worse
    usability and no gain, since the backend must see the plaintext to build it anyway; (c) binding to the export id means an
    object copied under another export row does not open. Rejected: a per-export random key wrapped by the user's key (a column
    and a lifecycle for a property the user key already gives), and SSE-C / bucket-side encryption alone (the bucket operator
    could read it).
13. **Object key:** `exports/` + 256 random bits in hex, unrelated to the user or the export id; the bucket is private and has no
    public or presigned access for clients. Only the backend's credential (read, write, delete on that bucket) touches it.
14. **Download** is through the API: `GET /me/data-exports/{id}/archive` with the owner's access token opens the object and
    streams the JSON as an attachment (`no-store`). `downloadUrl` in the status is this endpoint (an absolute URL, built from
    the request or `qal.privacy.export.public-base-url`), valid until `downloadExpiresAt`. The contract text "signed URL valid up
    to 15 minutes" is replaced accordingly. Chosen over a presigned bucket URL because (12) the stored bytes are ciphertext that
    only the backend can open, and because every download is then authenticated and attributable to the owner.
15. **Ownership:** every read filters on the caller; another user's id, an unknown id, an export that is not `READY` and an
    expired one are all 404 (no oracle).
16. **Expiry:** the link lives 7 days (`link-ttl`, never above 7). At the end the job deletes the object and clears
    `export_object_key` (row `EXPIRED`); the bucket lifecycle (7 days, SD 8, D39) is the backstop and must be set on the bucket
    (release checklist: nothing in the repository can enforce it). The row is purged by B6.3 30 days later.
17. **Storage client:** `S3ExportObjectStore`, JDK HTTP + SigV4 header auth, the same primitives as the pack store (a shared
    platform class is a follow-up, Open 3). Fails closed: under prod/production/staging the application does not start without
    credentials; elsewhere an unconfigured store fails exports at use (`FAILED`).

### Deletion and shredding
18. **Account deletion:** `PrivacyDeletion` deletes the user's export rows and, first, their archive objects (a store failure is
    logged by class and does not hold up the deletion: the object is unreadable once the keys are shredded, and the lifecycle
    removes it). A request still `PROCESSING` when the account is deleted loses its row; the job notices that when it
    publishes (0 rows updated) and deletes the object it just wrote. A queued request of a shredded account fails with
    `ACCOUNT_CLOSED` and stores nothing.
19. **Guest merge:** `data_export_requests` are not moved (a short-lived artifact of the guest; the guest account goes through the
    normal retention). The registered user exports the merged data.

### What is exported (SD 19.2 class to section)
| Data class (SD 19.2) | Sections | Notes |
|---|---|---|
| Account and preferences | `account`, `authIdentities`, `devices`, `deviceLanguageInstallations`, `preferences`, `typingLanguages`, `destinationProfiles`, `appDestinationRules`, `styleProfiles`, `glossaries`, `glossaryEntries`, `featureOverrides`, `consents`, `deletionRequests`, `organizationMemberships` | labels, sign-off and phrases decrypted; app rules as hashes |
| History (opt-in) | `history` | decrypted; expired items not shown (the reads hide them too) |
| AI request metadata | `aiRequests`, `aiFeedback` | metadata only (no text exists); feedback comment/content decrypted |
| Sessions | `sessions` | metadata incl. IP address and user agent; no token hashes |
| Dictionary sync | `dictionary`, `termSuggestions` | words decrypted; deleted words (tombstones) not exported |
| Metering state | `usageCounters`, `usageEvents` | open reservations are transient and not exported |
| Financial records | `creditAccounts`, `creditLots`, `creditTransactions`, `subscriptions`, `paymentOrders`, `storePurchases` | purchase tokens left out |
| Voice / realtime metadata | `voiceJobs`, `realtimeSessions` | no audio, no audio object key |
| Data export rows | `exportRequests` | state and times, no object key |
| Keystrokes, drafts, audio, AI request content | none | never stored (SD 19.1); listed under `excluded` |

Deliberately excluded (also in the manifest): credentials and security material (password hashes, reset tokens, OTP challenges,
nonces, refresh-token hashes, data keys, app-rule and dictionary keys), sync bookkeeping (revision counters, applied-change
ledger), the AI replay cache and fingerprints, admin roles, audit events and other internal service records, other users' data.
Billing tables no code writes yet (payment attempts, transactions, mandates, allocations, refunds, seat assignments) are listed
as excluded with that reason; the billing module (M4) adds them with its own participant. `DataExportIT` carries a **schema
guard**: every table with a `user_id` (or `submitted_by_user_id`) column must be in an exported or an excluded list, so a new
per-user table cannot be forgotten.

### D41 (ADR 0007), implemented with this step
20. **Withdrawing `FEEDBACK_TEXT` deletes stored feedback text:** `AiFeedback` listens to `ConsentChanged` (published in the
    withdrawal's transaction, ADR 0021 consent events) and nulls `comment_ciphertext`, `input_ciphertext`, `output_ciphertext`,
    `key_version` and `consent_record_id` of the user's feedback rows. Rating and reason codes stay. A later re-grant brings
    nothing back; new text can be sent again.
21. **`ai-feedback` retention rule:** 13 months from `created_at` (396 days, like `ai_requests`), delete, owner `aigateway`
    (`AiRetention`); added to ADR 0020's table.

## Consequences
- **No migration** (V1's table is enough; the data-class guard test replaces a schema change).
- Contract (docs copy, synced): `downloadDataExport` (`GET /me/data-exports/{id}/archive`), schema `DataExportArchive`,
  `DataExport.downloadUrl` redefined, 400/401/403 on the request, 401 on the status.
- New configuration `qal.privacy.export.*` (`QAL_EXPORTS_S3_*`, `QAL_EXPORT_*`); the `qal-exports` bucket is already created by
  infra/compose and needs its 7-day lifecycle rule.
- `DataExportIT` (PG18 + MinIO, contract-validated) is the done-when: every class seeded and checked against the manifest,
  plaintext values, nothing of another user, object at rest is ciphertext that opens only for this user and export, anonymous
  bucket access refused, link expiry and sweep, ownership, one in flight, rate limit, deletion and shredding, log scrub.

## Open
1. **CSV** (SD 19.4 "JSON + CSV"): the contract has `format: JSON` only; add CSV sections to the contract and the writer if the
   product wants them (a spreadsheet-friendly copy of the tabular sections).
2. **Large archives:** built and sealed in memory (64 MiB cap). A person with years of history can approach it; move to a chunked
   sealed stream (segment-wise AES-GCM) if that happens.
3. **Shared S3 client:** the pack store, the URL signer and this store each carry the SigV4 primitives; extract
   `platform.objectstore` when a third consumer (audio) appears.
4. **Bucket lifecycle** (7 days) and the backend's bucket credential are infrastructure settings; add the lifecycle rule to the
   compose init script and the production Terraform/Helm (release checklist).
5. **Notification** when an export is ready (push or e-mail) is not built; the client polls the status.
6. **Billing tables** (mandates, attempts, transactions, refunds, seat assignments) join the export with the billing module (M4).
