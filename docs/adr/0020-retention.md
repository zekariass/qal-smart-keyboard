# ADR 0020 — Retention scheduler: every data class, its period and what happens at the end

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B6.3 (depends on B1.4) · **Spec:** SD 19.2 (data
classification and retention), 19.3 (consents append-only), 19.4 (deletion, export link 7 days), 20.1 (append-only ledgers:
"the only exceptions are FK cascades from an account deletion and retention purges run with `SET LOCAL
app.retention_purge = 'on'`"), 8.3 (idempotency keys 24 h), 11.6 (replay cache ≤ 10 min); ADR 0007 D1 (30-day backup cap),
D31 (dictionary tombstones 180 days, resync); ADR 0010 (merged guests, open item B6.3), ADR 0011 (usage reservations),
ADR 0015 (kept rows of deleted accounts, open item 3), ADR 0017 (AI idempotency tables), ADR 0018 (dictionary sync); V1
note 10 (retention purges).

## Context
Every module that writes rows promised a purge to B6.3 (V1 note 10, B2.1 OTP, B2.3 nonces, B2.6 merged guests, B3.6
tombstones, B4.2 reservations, B5.4 fingerprints, B6.2 consents of deleted accounts). SD 19.2 is the only list of
periods; **the retention table is in §19.2** (the plan names §20, whose §20.1 only states the append-only rule and the
purge exception). Some classes have no period in the SD; those are marked `UNDEFINED` below: the value is an assumption, is
configurable, and needs a decision.

## Decisions

### D1. A registry of rules, one participant per module
`platform.retention` (a Modulith named interface) holds `RetentionRule` (code, owner module, action, default period,
reference, description), `RetentionAction` (`DELETE`, `ANONYMISE`, `KEEP`, `DELETE_BY_OWNER`), `RetentionParticipant` and
`RetentionSupport` (batch SQL helpers). Like `UserDeletionParticipant`, **each module owns the rules and the SQL of its
tables** (`IdentityRetention`, `PrivacyRetention`, `UnownedTablesRetention`, `AiRetention`, `UsageRetention`,
`DictionaryRetention`, `AuditRetention`, `PlatformRetention`); the privacy module owns `RetentionPolicy` (the registry),
`RetentionProperties`, `RetentionJob` and the task. A module that adds a table with a retention need adds a rule in the
same change; a module that takes over a table from `UnownedTablesRetention` moves the rule and keeps its code.

`RetentionPolicy` is built at start from all participants: duplicate codes, an override for an unknown code, and an
override for a rule the job does not execute (`KEEP`, `DELETE_BY_OWNER`) stop the application. Periods are overridden with
`qal.retention.periods.<code>` (for example `QAL_RETENTION_PERIODS_OTP_CHALLENGES=48h`); a zero period purges as soon as the
rule's own condition holds.

### D2. The job
A db-scheduler `RecurringTask` `privacy-retention` (cron `qal.retention.cron`, default `0 0 3 * * *` UTC, daily; switch
`qal.retention.enabled`) calls `RetentionJob.run()`:

- **Bounded batches**: per rule, one transaction per batch of `qal.retention.batch-size` (1000) rows; while batches come
  back full it continues, up to `max-batches-per-rule` (1000) per run, and finishes the backlog in the next run.
  Resumable and idempotent by construction: a batch only deletes rows past the cutoff.
- **Row locking**: `DELETE ... WHERE key IN (SELECT key ... LIMIT n FOR UPDATE SKIP LOCKED)` (by `ctid` for tables without a
  single-column key), so rows another transaction holds are skipped, not waited for.
- **Several instances**: every batch first takes `pg_try_advisory_xact_lock(hashtextextended('qal.retention.<code>'))`; an
  instance that does not get it skips that rule (`qal.retention.skipped`), because the holder is purging it. db-scheduler
  already runs the task on one instance at a time; the locks make overlapping runs harmless.
- **Append-only ledgers** (`audit_events`, `user_consents`): the batch runs `set_config('app.retention_purge','on',true)`
  (transaction-local), the exception the V1 trigger documents; UPDATE stays forbidden and any other DELETE still fails.
- **Failure isolation**: a failing rule is logged by code and exception class only (a database message can contain
  values), counted in `qal.retention.failures{rule}` (alert on it) and the other rules still run.
- **Metrics** (rule codes as tags, no content): `qal.retention.purged{rule,action}`, `qal.retention.failures{rule}`,
  `qal.retention.skipped{rule}`, timer `qal.retention.run`. Logs: rule code and row count.
- Rules run in participant order: platform 50, privacy 100, AI 300, usage 400, languages 500, administration 600,
  module-less tables 800, identity 900 (the consents of a deleted account go before its tombstone row, so the counts are
  accurate).

### D3. The table

Periods: "13 months" = 396 days, "18 months" = 548 days, "2 years" = 730 days, "7 years" = 2557 days. `from` is the column the
period counts from; a row is due when that column is **strictly older** than `now - period`.

| Rule code | Data | Owner | Period | Action | Source |
|---|---|---|---|---|---|
| `otp-challenges` | `otp_challenges` | identity | 24 h after `expires_at` | delete | **UNDEFINED** (V1 note 10 lists it) |
| `attestation-nonces` | `attestation_nonces` | identity | 1 h after `expires_at` | delete | **UNDEFINED** (V5 "shortly after expiry") |
| `password-reset-tokens` | `password_reset_tokens` | identity | 24 h after `expires_at` | delete | **UNDEFINED** (V1 note 10; no writer yet) |
| `user-sessions` | `user_sessions`, whole token families | identity | 30 days after the family's last `expires_at` | delete | **UNDEFINED** |
| `merged-guest-accounts` | `users` rows with status `MERGED` (+ cascade: sessions, devices, consents, counters, ...) | identity | 30 days after the merge (`updated_at`) | delete unless a RESTRICT FK points at it | SD 19.2 "life of account + 30 days", ADR 0010 |
| `deleted-account-tombstones` | anonymised `users` rows, status `DELETED` (+ cascade: `user_deletion_requests`, key records) | identity | 30 days after `deleted_at` | delete unless a RESTRICT FK points at it (financial rows keep it) | SD 19.2, ADR 0015, ADR 0007 D1 |
| `user-consents-deleted-accounts` | `user_consents` of `DELETED` accounts | privacy | 30 days after `deleted_at` | delete (append-only bypass); a consent a payment mandate points at stays | SD 19.2, ADR 0015 D3 |
| `user-history-items` | `user_history_items` | privacy | from `expires_at` (the user's 1-365 days; grace 0); favourites have none | delete | SD 11.7, 19.2 |
| `data-export-requests` | `data_export_requests` | privacy | 30 days after `download_expires_at` (else `completed_at`, else `requested_at`) | delete | SD 19.4 (link 7 days); the 30 days are **UNDEFINED** |
| `outbox-published` | `outbox_events` PUBLISHED | platform | 7 days after `published_at` | delete | **UNDEFINED** |
| `outbox-dead` | `outbox_events` DEAD | platform | 30 days after `created_at` | delete | SD 19.2 operational logs, by analogy |
| `processed-events` | `processed_events` | platform | 35 days after `processed_at` (longer than the DEAD period, so a requeued event is still deduplicated) | delete | **UNDEFINED** |
| `operational-logs` | log store | platform | 30 days | deleted by the log store | SD 19.2 |
| `ai-replay-cache` | `ai_replay_cache` | aigateway | 10 minutes | deleted by its own sweeper (`EncryptedReplayCache.sweep`, B5.4); the job does not repeat it | SD 11.6 |
| `ai-request-fingerprints` | `ai_request_fingerprints` | aigateway | 24 h after `created_at` | delete (backstop to the B5.4 sweeper) | SD 8.3, ADR 0017 |
| `ai-requests` | `ai_requests` metadata | aigateway | 13 months after `created_at` | delete (**"then aggregated"** has no target, see Open 1) | SD 19.2 |
| `usage-reservations` | `usage_reservations` SETTLED/RELEASED | usage | 13 months after `closed_at`; open rows never | delete | **UNDEFINED**; ADR 0011 (13-month usage history) |
| `usage-events` | `usage_events` ledger | usage | none | **keep** (append-only metering ledger) | **UNDEFINED** |
| `ai-credit-ledger` | `ai_credit_accounts`, `ai_credit_lots`, `ai_credit_transactions` | usage | 10 years (assumed) | **keep** | SD 19.2 financial records |
| `usage-counters` | `usage_counters` | usage | none | **keep** (one aggregate row per user, feature and window) | **UNDEFINED** |
| `dictionary-applied-changes` | `user_dictionary_applied_changes` | languages | 30 days after `applied_at` | delete | ADR 0018 |
| `dictionary-tombstones` | `user_dictionary_entries` with `deleted_at` | languages | **180 days** after `deleted_at` | delete + raise `tombstone_floor` (D4) | ADR 0007 D31 |
| `audit-events-security` | `audit_events` of entity types `language_resource`, `language_dataset`, `resource_signing_key` | administration | 2 years after `created_at` | delete (append-only bypass) | SD 19.2 |
| `audit-events-financial` | `audit_events` of every other entity type | administration | 7 years after `created_at` | delete (append-only bypass) | SD 19.2 (default is the longer period on purpose) |
| `payment-webhook-events` | `payment_webhook_events` not referenced by a ledger row | billing (held by privacy) | 18 months after `received_at` | delete | SD 19.2 |
| `payment-webhook-payloads-referenced` | `redacted_payload` of the referenced ones | billing (held by privacy) | 18 months after `received_at` | **anonymise** (payload `{}`, row, id and hash stay) | SD 19.2 |
| `voice-jobs` | `voice_jobs` without a pending audio object | speech (held by privacy) | 13 months after `created_at` | delete | **UNDEFINED** (follows `ai_requests`) |
| `realtime-sessions` | `realtime_sessions` | realtime (held by privacy) | 13 months after `created_at` | delete | **UNDEFINED** (follows `ai_requests`) |
| `audio-objects` | temporary audio in object storage | speech (held by privacy) | deleted after processing, 24 h bucket lifecycle | deleted by the owner / bucket lifecycle | SD 19.2, 8.1 |
| `financial-records` | orders, attempts, transactions, receipts, mandates, subscriptions, store purchases and their event ledgers | billing (held by privacy) | 10 years (assumed until confirmed) | **keep** (pseudonymised, ADR 0015); no purge is built until M4 writes them | SD 19.2 |

Not rules, for the record: keystrokes and drafts never leave the device; AI request content is not persisted
(SD 19.2); `scheduled_tasks` (db-scheduler) holds one row per scheduled task and no history, so nothing accumulates;
`user_deletion_requests` go with the tombstone row (cascade); account and preferences data is deleted by the account
deletion (ADR 0015), the retention job only handles what that flow keeps.

**A `users` row is never deleted while a financial row needs it.** The two `users` rules exclude rows that any
`ON DELETE RESTRICT` or `NO ACTION` foreign key points at; the blocking tables are read from `pg_constraint` on every
batch, so a billing table added later is respected without a code change. Such a tombstone stays anonymised
(ADR 0015) and is reconsidered when the financial period ends.

### D4. Dictionary tombstones and the resync response (D31)
A tombstone is what tells a second device that a word was deleted. After 180 days the retention job deletes it and, **in the
same statement and under the same per-user lock a push takes** (`user_dictionary_sync_state` row, `FOR UPDATE SKIP LOCKED`,
taken before the entries, so no deadlock with a push and no purge of a tombstone a push is reviving), raises the new
column `user_dictionary_sync_state.tombstone_floor` (V16) to the highest `sync_version` it removed.

`GET /me/dictionary/changes` compares the cursor with the floor:

- **Contract choice: a 410 Problem with code `SYNC_CURSOR_EXPIRED`** (new `ApiErrorCode`, listed in `Problem.code`, shared
  response `SyncCursorExpired` on `pullDictionaryChanges`). A 200 flag (`resyncRequired: true`) was rejected: an older client
  that ignores the unknown field would take the page for "no changes" and keep a deleted word forever, whereas an error
  cannot be mistaken for success. Pushes are not affected (a push never needs the cursor).
- The client discards its token, pulls **without `since`** (a pull without a token is never refused), replaces its synced
  words with the result and pushes what it still has pending.
- **Tokens carry the floor they were issued under** (`v2.<base64 "version:floor">`; the plain `v1.<base64 version>` form
  is kept while the floor is 0, so nothing changes for users without a purge). The rule is: refuse when `version > 0` and
  `floor_now > max(floor_at_issue, version)`, that is, a tombstone with a version above the cursor was purged since the
  cursor was issued. A plain `since < floor` rule would also refuse the second page of the resync download itself (its cursor
  is below the floor) forever; with the issue-time floor the download proceeds and only a purge that happens *during* it
  restarts it. The floor is read after the page's rows, and a purge commits delete and floor together, so a purge can only
  make the answer stricter, never miss a delete.
- A push still returns the plain high-water-mark token (a client that did not pull up to the previous version keeps its own
  cursor, as before).

### D5. Operations
- `qal.retention.enabled=false` leaves the task out (tests; one-off environments). Overrides as in D1.
- Backups: the 30-day cap (ADR 0007 D1) applies to the database; the retention periods above are the live periods.
- The data-export archive objects (B6.1, ADR 0022) are deleted by the export job when the link expires (7 days) and by the
  account deletion; the 7-day bucket lifecycle of SD 8.1 / D39 is the backstop (a row delete cannot delete a file); the
  row purge (30 days after the link expires) only removes the record. Voice rows whose audio object still exists are never
  purged for the same reason.

## Consequences
- **V16 migration**: `user_dictionary_sync_state.tombstone_floor` (+ CHECK) and purge indexes (`user_sessions.expires_at`,
  `password_reset_tokens.expires_at`, partial indexes on dictionary tombstones, PUBLISHED outbox events, closed
  reservations, DELETED and MERGED users, BRIN on voice jobs, realtime sessions and webhook events).
- Contract (docs copy, synced): `pullDictionaryChanges` 410, `SyncCursorExpired`, `SYNC_CURSOR_EXPIRED` in `Problem.code`.
- `RetentionIT` (PG18, injected clock) is the done-when: every rule just inside / just outside / at the boundary, whole
  session families, merged guests and tombstones with and without RESTRICT rows, append-only bypass only inside the job,
  financial rows untouched, idempotence, resume across runs, two instances (counts add up, an advisory lock held elsewhere
  skips the rule, `SKIP LOCKED` skips held rows), a failing rule, metrics and log hygiene. `DictionaryResyncIT` is the D31
  done-when end to end.
- New periods are assumptions until confirmed; they are configuration, not code.

## Open
1. **`ai_requests` "then aggregated" (SD 19.2):** no rollup table exists, so the rows are deleted at 13 months. Cost and
   usage reporting must roll them up before then (or an `ai_request_daily_stats` table is added and the rule becomes
   "aggregate, then delete").
2. **Periods the SD leaves undefined** (marked above): OTP, nonces, reset tokens, sessions, export rows, outbox,
   processed events, usage reservations, voice and realtime metadata, `usage_events`, `usage_counters`. Confirm or change.
3. **Financial retention (10 years assumed):** `financial-records`, the credit ledger and `usage_events` are kept and no purge
   exists; the purge after the confirmed legal period (child before parent, under the append-only bypass) is built with
   M4, together with the pseudonymisation rules.
4. **Audit split:** `audit_events` has no category column; the split is by entity type (list in `AuditRetention`).
   A module that writes a non-financial audit entity type must add it there, or its rows stay 7 years.
5. **Tombstoned `users` rows kept for financial rows** stay indefinitely under D3's rule; decide whether they should be
   cut when the financial period ends (needs the same purge as 3).
6. Operational log retention (30 days) and the audio / export bucket lifecycles are infrastructure settings that nothing in
   the repository can enforce or test.
