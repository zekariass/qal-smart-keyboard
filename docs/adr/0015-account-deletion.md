# ADR 0015 — Account deletion: grace period, crypto-shred, purge and pseudonymisation

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B6.2 (depends on B1.5) · **Spec:** SD §19.4 (deletion:
14-day cancellable window, then destroy the data key, delete personal rows, pseudonymise retained financial records,
revoke sessions and mandates, store-side handling), §19.2 (retention per data class), §19.3 (consents append-only),
§20.1 (append-only ledgers; "the only exceptions are FK cascades from an account deletion and retention purges"); V1
comment 11 (account deletion), `users`, `user_deletion_requests`, `fn_forbid_update_delete`; contract
`requestAccountDeletion`, `cancelAccountDeletion`; ADR 0005 (envelope encryption, crypto-shred), ADR 0006 (sessions),
ADR 0007 D1/OI-P6 (backups), D22 (deletion-pending accounts lose AI), ADR 0010 (guest merge: participants per module),
ADR 0012 (app-rule key shredded with the user)

## Context
SD 19.4 describes the deletion flow in five lines; the V1 schema has `users.account_status` `DELETION_PENDING`/
`DELETED`, `users.deleted_at` and a `user_deletion_requests` table, and B1.5 built `UserDataEncryption.cryptoShred`.
What was open: what the person can still do during the window, who carries out the purge and in which order, how a
crash or a second instance is handled, what happens to the append-only and financial rows (the triggers refuse
application deletes, and payment tables reference `users` with `ON DELETE RESTRICT`, so the `users` row cannot be
deleted), and whether the same phone number can come back.

## Decisions

### D1. Request, grace period and cancellation
- `POST /me/deletion-request` with `{"confirm": true}` (400 otherwise): the account (`ACTIVE` only) becomes
  `DELETION_PENDING`; a `SCHEDULED` request is stored with `scheduled_purge_at = requested_at + grace`, grace
  `qal.privacy.deletion.grace-period`, default **14 days** (SD 19.4); outbox `privacy.account.deletion_requested`.
  202 with the request. Already pending: 409 `DELETION_PENDING`. `SUSPENDED`: 403 `ACCOUNT_SUSPENDED` (deletion goes
  through support: a suspension can mean an investigation that needs the data). Any other state: 403 `FORBIDDEN`.
- **During the window** (SD 19.4 lists session revocation among the steps *after* the window, and the contract's
  cancel operation needs a bearer token):
  - the requesting session family stays signed in; **every other session family is revoked** (`ACCOUNT_DELETED`), as
    a deletion request is an account-security action like "log out everywhere";
  - **sign-in keeps working** (phone or Google), so the person can cancel from a new or reinstalled device;
  - AI features stop at once (ADR 0007 D22: a `DELETION_PENDING` account holds no plan; entitlement reads and the
    entitlement token answer 403 `DELETION_PENDING`); typing (offline anyway) and the other account features (sync,
    consents, export) keep working;
  - devices and push tokens stay until the purge.
- `GET /me/deletion-request` (**new operation**) returns the pending request (or 404), so an app can show the date and
  offer to cancel. `DELETE /me/deletion-request` cancels while the request is `SCHEDULED`, even after
  `scheduled_purge_at` if the executor has not started: request `CANCELLED` (`cancellation_reason USER_CANCELLED`),
  account `ACTIVE`, outbox `privacy.account.deletion_cancelled`. Sessions ended by the request stay ended. Once the
  executor has started, cancel is 409 `DELETION_PENDING` (in practice the account's tokens are already refused, 401).
- `reason` (contract, optional free text) is validated (≤ 255) and **not stored**: V1 has no column for it, and free
  text is user content that would outlive the account.
- **Guests** may delete their account the same way. A guest's token is honoured while its deletion is pending (so it
  can see and cancel the request); such a guest can no longer be merged into an account on sign-in (merge needs an
  `ACTIVE` guest, ADR 0010), the sign-in answers `GUEST_SESSION_INVALID` and the client signs in without it.

### D2. Executor: three committed steps, resumable and race-free
A db-scheduler `RecurringTask` (`privacy-account-deletion`, every `executor-interval`, default 5 min) runs
`DeletionExecutor`. For each request with `request_status IN (SCHEDULED, PROCESSING)`, `scheduled_purge_at <= now`
and no pending retry, it runs the remaining steps, **each in its own transaction that also records the step** on the
request row (**V11 migration**: `shredded_at`, `purged_at`, `attempts`, `next_attempt_at`, plus CHECKs that the steps
happen in order and only while processing):

| Step | State after commit | What it does |
|---|---|---|
| 1 SHREDDED | `PROCESSING`, `shredded_at` | Close the account: `users.deleted_at = now` (status still `DELETION_PENDING`). From this commit every access token of the account is refused (D4), sign-in and refresh find a deleted account (401), and phone/Google sign-in no longer match it. Revoke every session (`ACCOUNT_DELETED`). **`cryptoShred(user)`**: all data keys DESTROYED, wrapped keys NULL, so every encrypted column of the user (history, dictionary words, destination-profile labels, style sign-off and phrases, feedback comments, the app-rule HMAC key) is unreadable at once, and nothing new can ever be encrypted for the user. |
| 2 PURGED | `purged_at` | Every `identity.UserDeletionParticipant` bean (devices 100, preferences 200, privacy 300, languages 400, usage 500, glossaries 600, entitlements 700, module-less tables 800, identity 900) deletes or detaches its rows (D3). |
| 3 DONE | `COMPLETED`, `completed_at` | Anonymise the `users` row and set it `DELETED` (D3), publish `privacy.account.deleted` (`requestId`, `userId`, `requestedAt`, `completedAt`). |

Why this order: the shred first makes the data unreadable even if a later step keeps failing (and in every backup
that holds only the wrapped key); closing the account in the same transaction stops new writes before the purge.

- **Crash between steps:** the next run reads the recorded state under the lock and continues with the next step; a
  finished step is never repeated (the event is published exactly once, with the `COMPLETED` change).
- **A failing step** rolls back completely (participants join the step's transaction). The request records
  `attempts`, `next_attempt_at = now + backoff` (1 min, doubling, max 1 h: `retry-backoff`, `max-retry-backoff`) and
  `processing_error` = step + exception class (never the message: a constraint violation message can contain values).
  It is retried **forever**; metric `qal.privacy.account_deletion{outcome=failed}` must alert. `FAILED` is not used
  (a deletion is never abandoned).
- **Concurrency:** every step locks its request row `FOR UPDATE SKIP LOCKED` and re-reads it; a request held by
  another executor is skipped, so instances (or overlapping runs) never run a step twice or in parallel and share the
  work across requests. Lock order everywhere: request row → `users` row (`FOR NO KEY UPDATE`, the lock of sign-in and
  guest merge) → per-user crypto advisory lock / session-family locks. A new request locks only the `users` row and
  then inserts (the V1 partial unique index allows one open request per user).
- **Cancel vs executor:** both lock the request row; whoever is first wins; the cancel sees `PROCESSING` (409) or the
  executor sees `CANCELLED` (skip).

### D3. Per-table rules (all tables with a user column, V1–V11)
Deleted rows are deleted by their module's participant. "Pseudonymised" = the row is kept and still carries the user
id, but after step 3 that id is a random UUID of an anonymised `users` row: nothing in the database links it to a
person any more (identities, contact data and devices are gone); re-identification would need data held elsewhere
(payment providers, stores), which is what SD 19.4 step 3 means.

| Table | Rule | Module |
|---|---|---|
| `users` | Kept as an anonymised tombstone: `email`, `email_normalized`, `email_verified_at`, `phone_e164`, `phone_verified_at`, `display_name`, `market_country_code`/`market_source`, `ui_locale`, `age_confirmed_at`, `last_login_at` cleared, `time_zone` reset to its default; `account_status DELETED`, `deleted_at` set. Kept because financial rows reference it `ON DELETE RESTRICT` and ledgers by id. `account_type`, `created_at` stay. | identity |
| `user_auth_identities` | Deleted (phone number / IdP subject / provider e-mail). | identity |
| `user_sessions` | Revoked in step 1, deleted in step 2 (IP, user agent). | identity |
| `user_password_credentials`, `password_reset_tokens`, `otp_challenges` (with `user_id`), `attestation_nonces` (with `user_id`) | Deleted. Challenges keyed only by phone hash expire and are purged by B6.3. | identity |
| `user_data_keys` | Kept: DESTROYED rows with NULL wrapped keys are the shred record and stop any new key for the user (ADR 0005). | platform |
| `user_devices` (+ `device_language_resource_installations` by cascade) | Deleted (device name, versions, verdicts, push token). | devices |
| `user_preferences`, `user_typing_languages`, `user_typing_language_revisions`, `user_style_profiles`, `user_destination_profiles`, `user_app_destination_rules`, `user_app_rule_revisions`, `user_app_rule_keys` | Deleted (ciphertext already shredded). | preferences |
| `user_history_items`, `data_export_requests` | Deleted. B6.1 must also delete an export's archive object. | privacy |
| `user_consents` | **Kept, pseudonymised** (append-only, SD 19.3/20.1): proof of what was agreed and withdrawn; no contact data. Removed by the retention job (B6.3) at the end of SD 19.2's "life of account + 30 days", i.e. 30 days after `deleted_at`. | privacy |
| `user_deletion_requests` | Kept: the record that and when the deletion was done (no personal data). | privacy |
| `user_dictionary_entries` | Deleted (the word HMACs would still allow a dictionary guess). | languages |
| `term_suggestions.submitted_by_user_id` | Detached (`NULL`, what its `ON DELETE SET NULL` prescribes); the suggestion is catalogue data contributed under `COMMUNITY_CONTRIBUTION`. | languages |
| `usage_counters`, `usage_reservations` | Deleted (operational state). | usage |
| `usage_events` | **Kept, pseudonymised** (append-only metering ledger; `subject_id` logical). Retention: B6.3. | usage |
| `ai_credit_accounts`, `ai_credit_lots`, `ai_credit_transactions` | **Kept, pseudonymised** (financial: purchased credits are part of the payment trail; `ON DELETE RESTRICT`, append-only ledger). Remaining credit is not paid out or zeroed (the account cannot use it). | usage |
| `glossaries` (+ `glossary_entries` by cascade) | Deleted (personal glossaries). | glossaries |
| `user_feature_overrides` | Deleted; resolver cache invalidated. | entitlements |
| `user_subscriptions`, `store_purchases`, `payment_orders`, `payment_attempts`, `payment_mandates`, `subscription_payment_allocations`, `payment_transactions`, `subscription_events`, `payment_mandate_events`, `store_purchase_events` | **Kept, pseudonymised** (financial records, SD 19.2 "as required by tax law", `ON DELETE RESTRICT` / append-only). No writer exists yet; billing/stores (M4) must add a participant that cancels mandates and auto-renew (SD 19.4 step 4) and requests store-side handling (step 5). | billing/stores (M4) |
| `ai_feedback` | Deleted (rating linked to the person; comment already shredded). | privacy until the AI module owns it |
| `voice_jobs`, `realtime_sessions` | Detached (`user_id = NULL`, as their FK prescribes); audio has its own ≤ 24 h lifecycle. | privacy until speech/realtime own them |
| `organization_members` (+ `organization_seat_assignments` by cascade), `user_admin_roles` | Deleted. | privacy until organizations/administration own them |
| `ai_requests` | **Kept, pseudonymised** (metadata only, logical user id; SD 19.2: 13 months then aggregated). | AI (B5.x) |
| `audit_events` | **Kept, pseudonymised** (security and financial audit, SD 19.2: 2/7 years; `actor_user_id` logical). | administration |
| `outbox_events` | Kept (ids only, never personal data; outbox retention). | platform |
| Admin attribution columns (`created_by_user_id`, `reviewed_by_user_id`, `granted_by_user_id`, `assigned_by`, ...) | Kept: they point at the anonymised row (pseudonymous attribution, like `audit_events`). | — |

The module-less rows (AI feedback, voice, organisations, admin roles) are handled by `privacy.UnownedTablesDeletion`
until their module exists; that module takes its rule over into its own participant. Every statement is idempotent.

### D4. Tokens of a deleted account
Registered access tokens were fully stateless (ADR 0006/0010: a revoked session's access token lives out its 15
minutes). For a deleted account that window would let a request write rows after the purge. The security chain now
reads the account (one primary-key lookup, the same read guests already had, ADR 0010) for **every** token: a
registered token of a deleted (from step 1, `deleted_at` set) or merged account is refused with 401 `UNAUTHENTICATED`
(`invalid_token`); a guest token additionally needs an `ACTIVE` or `DELETION_PENDING` guest (`GUEST_SESSION_INVALID`).
A token whose account row does not exist is left to the endpoint (it resolves to nothing). Refresh tokens die with
their (deleted) sessions; the entitlement token endpoint refuses (401 at the chain, 403 `DELETION_PENDING` during the
window). Accepted residue: a request authenticated just before step 1 committed and still running when step 2 ran
could leave a row; encrypted writes fail (the user is shredded), and B6.3 can sweep rows of `DELETED` users.

### D5. Re-registration
V1 has `UNIQUE (identity_provider, provider_subject)` on `user_auth_identities` and a partial unique index on
`users.phone_e164 WHERE deleted_at IS NULL`. Step 2 deletes the identities and step 3 clears the phone, so the same
phone number or Google account **can register again and gets a new, empty account** (new id, new data key). The
deleted account can never be reached again: from step 1 its row is `deleted_at`-closed (sign-in answers 401 while the
identity rows still exist, for the seconds between steps 1 and 2), and afterwards nothing points at it. Nothing about
the old account (plan, credits, consents) carries over. A "deleted number" block list would need to keep the number,
which contradicts the deletion.

## Consequences
- **V11 migration** (`V10` is reserved for B5.1). New identity API: `AccountDeletion`, `UserDeletionParticipant`;
  `SessionService.revokeAllForDeletion`; `UserAccountStore.state` and the effective status `DELETED` as soon as
  `deleted_at` is set (sign-in, refresh, security chain).
- Contract (docs copy, synced): `requestAccountDeletion` documents the window, 400/401/403 and the 409 code;
  `cancelAccountDeletion` 401/409; new `getAccountDeletion`.
- Modules that add per-user tables must extend their participant (or add one) in the same change, and add the table to
  `DeletionIT.PURGED` or to the kept list here.
- `DeletionIT` (PG18) is the done-when: every ciphertext fails with `CryptoShreddedException`, keys DESTROYED with
  NULL wrapped keys, purged tables empty for the user, append-only tables keep only the pseudonymised rows, sessions and
  tokens dead, phone and Google sign-in create new accounts; plus cancel, crash between steps, a failing step with
  backoff, racing executors, a cancel racing the executor, guests, validation and the V11 guards.

## Open
1. **Backups (OI-P6, ADR 0007 D1):** the shred removes the wrapped DEK from the live database only. A backup taken
   before step 1 still holds the wrapped DEK and the ciphertext, and the KEK can unwrap it until the KEK version is
   destroyed. Deletion is therefore complete only when (a) backups older than the shred have expired (30-day cap) and
   (b) the KEK versions they were wrapped with are destroyed. Both need the cloud KMS (OI-04) and the backup policy;
   nothing in B6.2 does it. The privacy notice should say "deleted within 30 days from backups" (D1).
2. **Billing/stores (M4):** cancel mandates and auto-renew, request store-side handling, and fill
   `activeStoreSubscriptions` in the response (omitted today: no store purchase can exist).
3. **B6.3 retention:** purge consents and other kept rows of `DELETED` users at the end of their period; optionally
   hard-delete tombstones without financial rows (the FK cascades then remove what is left); sweep any row written
   for a `DELETED` user by a request racing step 1 (D4).
4. **Export before deletion:** B6.1 may want to offer an export when a deletion is requested (SD silent).
5. **Notifications:** a reminder before the purge (SD silent); `privacy.account.*` events have no consumer yet.
6. Merging a guest into a `DELETION_PENDING` registered account is not refused (the moved data is deleted with the
   account at the purge); decide whether sign-in should refuse it.
