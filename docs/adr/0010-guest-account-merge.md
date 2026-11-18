# ADR 0010 — Guest account linking and merge on sign-in

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B2.6 (depends on B2.3) · **Spec:** SD §9.1 ("a guest is
merged on sign-in": preferences, consents and remaining trial usage move, the guest row gets `merged_into_user_id`),
§15.3/§15.5, §19–20; contract `verifyPhoneOtp`, `exchangeOAuthIdToken` (`AuthSession.mergedGuestUserId`); V1 `users`
(`account_status` `MERGED`, `merged_into_user_id`, `ck_users_merge_only_guest`, `ck_users_guest_has_no_identity`) and
every table with a user column (V1–V5); ADR 0005 (ciphertext bound to the user, so moves re-encrypt), ADR 0006, ADR 0007
D9/D14, ADR 0008, ADR 0009

## Context
A guest (ADR 0008) is an attested, anonymous account for the AI trial. When that person signs in with a phone number or
Google, the contract says the request may carry the guest's bearer token, and that the guest's data is then merged and
`mergedGuestUserId` returned. Until now the token was accepted and ignored. Three constraints shape the design: per-user
ciphertext opens only for the user it was sealed for (ADR 0005), so data that changes owner must be decrypted and
re-encrypted; most per-user tables have no writer yet (B3.x, B4.x, B5.7), so the merge rules must live with the modules
that will own them; and a merge must not reset quotas or let one guest token merge twice.

## Decision

### When a merge happens
- Only on `POST /auth/otp/verify` and `POST /auth/oauth/{provider}`, only when the request carries a **guest** access
  token that is valid in every respect: signature, `typ`, issuer, audience and expiry (security chain, ADR 0006),
  `accountType = GUEST`, the account is `GUEST` + `ACTIVE` and not deleted, and the token's session family (`sid`)
  still has an ACTIVE, unexpired refresh token. The last rule means a guest that logged out, or whose family was
  revoked for refresh-token reuse (a stolen-token signal), cannot hand its data to anybody.
- A **registered** account's token in that position links nothing: the sign-in proceeds as if no bearer was sent (the
  contract merges "if the request carries a guest bearer token"); the other account is untouched. An invalid bearer is
  still a 401 (ADR 0007 D9).
- The guest checks run **after** the OTP code (or ID token) is verified and before anything is written, so a refused
  guest consumes nothing: the OTP code stays usable.

### New identity: upgrade the guest in place
If the phone number or Google subject has no account yet, the guest row itself becomes the registered account:
`account_type` `GUEST` → `REGISTERED`, the verified phone (with the `PHONE` identity) or the Google identity (and the
display name if the row has none), `age_confirmed_at`. The age confirmation is required exactly as for a new account
(400 on `ageConfirmed`, nothing written).

Why not create a new registered user and move the guest into it:
- SD 9.1 describes merging into *a registered user*; for a new identity there is none, and the observable result of
  "create + move everything + mark the guest MERGED" equals the upgrade except for the user id.
- V1 allows `merged_into_user_id` only on a GUEST row pointing at another row (`ck_users_merge_only_guest`); an upgraded
  row is no longer a guest and needs no pointer. The V1 comment on that column ("upgraded/merged") anticipates both.
- Nothing moves, so nothing can be lost or double-counted: the user id, devices and their attestation verdicts, usage
  counters, history and the data key stay as they are; no re-encryption, no shred. Usage carry-over is automatic.
- Re-encrypting for a user created in the same, uncommitted transaction cannot work: `platform.crypto` creates the
  target's first key in its own transaction, whose foreign-key check would wait for the sign-in transaction (a
  self-deadlock).

Consequences: the registered account's `id` and `created_at` are the guest's; `isNewUser` is `true` (a registered
account was created by this sign-in); `mergedGuestUserId` equals `user.id`; right after the registered session is
issued, every other session family of the user, i.e. every guest family, is revoked (`ACCOUNT_MERGED`), so a guest
refresh token can never become a registered session; the event below is published with mode `UPGRADED`.

### Existing account: move the guest's data, then shred
In the sign-in transaction, after the target was signed in (status rules unchanged: suspended 403, deleted/merged 401,
in which case the guest is untouched):
1. Lock the target row.
2. Every `identity.GuestMergeParticipant` bean moves its module's rows, in `mergeOrder()` order (devices first). Each
   joins the transaction, keeps row ids, re-encrypts ciphertext with `UserDataEncryption.reencrypt/reencryptRow`
   (guest → target under the same `(table, column, row id)` context; every column of a row gets one key version), and
   deletes the guest's conflict losers where the table allows it.
3. Burn the guest's unspent attestation nonces; revoke every guest session family (`ACCOUNT_MERGED`, family locks
   first as in logout-all).
4. Mark the guest `MERGED` with `merged_into_user_id` (it stays a `GUEST` row).
5. `cryptoShred(guest)`: whatever is still sealed for the guest (feedback comments, rows that did not decrypt) becomes
   unreadable, and the guest can never get a key again.
6. Publish `identity.guest.merged`; then issue the target's session (the sign-in's device registration now finds the
   moved device).

A guest value that does not open (corrupt) is not moved; its table and row id are logged at WARN, and it is deleted or
shredded with the guest. A KMS failure fails the sign-in (500, nothing committed; retry).

### Per-table rules (all tables with a user column in V1–V5)
| Table | Rule |
|---|---|
| `users` | Guest → `MERGED`, `merged_into_user_id` = target. The target's profile fields win (a guest has none). |
| `user_auth_identities`, `user_password_credentials`, `password_reset_tokens` | A guest has none (`ck_users_guest_has_no_identity`; no credential path). |
| `otp_challenges.user_id` | Never set by the code. Not touched. |
| `user_devices` (devices) | Installation unknown to the target: the row is rebound (id, verdict, push token kept). Installation the target has: **target wins**; it takes the guest row's verdict only if VALID/FAILED, newer, same platform and the target row is ACTIVE; the guest row is revoked (push token dropped) and stays with the guest. Same advisory lock as registration. |
| `user_sessions` | Guest families revoked (`ACCOUNT_MERGED`); rows stay (audit). |
| `user_data_keys` | Guest keys crypto-shredded after re-encryption. |
| `user_preferences` (preferences) | Moved if the target has none, else **target wins**; guest row deleted. |
| `user_typing_languages` | The guest's whole list moves only if the target has none (lists are never interleaved: ordering, one primary). |
| `user_style_profiles` | Per language; **target wins**; moved rows have sign-off/phrases re-encrypted. |
| `user_destination_profiles` | All move, labels re-encrypted; a moved profile stays default only if the target has no default. Labels are not compared (duplicates possible). |
| `user_app_destination_rules` | A rule for an app HMAC the target already has is dropped (**target wins**); the rest move with their profile (taken off and re-inserted around the profile move because of the composite FK) or language. |
| `user_consents` (privacy) | Append-only: decisions are **appended** for the target, never moved. Guest's latest per code is appended if the target has no decision, or if it is a revocation later than the target's latest grant (a withdrawal is never lost). A guest grant never overrides a target decision. Appended rows keep version, value, platform, `recorded_at`, and name their origin in `metadata`. Guest rows stay. |
| `user_history_items` | All move, input/output re-encrypted, expiry unchanged. |
| `data_export_requests` | Stays (an export of the guest; expires on its own). |
| `user_deletion_requests` | Not applicable: a guest with an open request is not `ACTIVE`, so it is not mergeable. |
| `user_dictionary_entries` (languages) | Live entries move, word re-encrypted, **target wins** on `(language, word_hmac)` incl. its tombstones; new `sync_version`s after the target's highest; guest tombstones and losers deleted. `word_hmac` copied unchanged (see Open). |
| `term_suggestions.submitted_by_user_id` | Re-attributed to the target. `reviewed_by_user_id`: admins only. |
| `usage_counters` (usage) | Carry-over, see below. Guest counters stay. |
| `usage_events` | Append-only ledger keyed by subject id; stays with the guest. |
| `ai_requests`, `audit_events` | Logical ids, metadata only; stay (13-month / audit retention). |
| `ai_feedback` | Stays with the guest: it is bound to the guest's request and, via a composite FK, to the guest's consent row; the rating is anonymous quality data; a comment is shredded with the guest. |
| `glossaries` (glossaries) | Personal glossaries move with their entries. |
| `voice_jobs`, `realtime_sessions` | Transient, `SET NULL`; stay. |
| `attestation_nonces` (V5) | Unspent guest nonces are burned. |
| `ai_credit_accounts`, `user_subscriptions`, `store_purchases`, `payment_orders`, `payment_mandates`, `payment_attempts` | None for guests: guests cannot buy (SD 9.1) and no grant path exists. If B4.3 ever grants credits to guests, it adds a participant. |
| `user_feature_overrides` | Admin decisions about the guest account; not moved (the target's apply). |
| `organization_members`, `organization_seat_assignments`, `user_admin_roles` and every admin attribution column (`created_by_user_id`, `reviewed_by_user_id`, `granted_by_user_id`, ...) | Not applicable to guests. |

### Quota carry-over (SD 15.3, abuse)
For each guest counter whose window has not ended, `used_units + reserved_units` (an in-flight reservation counts as
used) is added to the target's counter of the same feature whose window contains now; if the target has none, a
counter with the guest's window is created for the target (or added to). The target's plan limit then applies to the
combined consumption, so using the guest trial and then signing in never yields a fresh allowance for the same window.
Allowances are never summed. On the upgrade path the counters already belong to the account.

### Tokens and the 15 minute window
- Guest refresh tokens die with their families (`ACCOUNT_MERGED`); the B2.4 refresh check also refuses a
  `MERGED`/deleted account.
- **Guest access tokens are no longer purely stateless:** the security chain accepts a token with
  `accountType = GUEST` only while its account is an `ACTIVE GUEST` (one primary-key lookup per guest request). So a
  merged or upgraded (or suspended, deleted) guest's access tokens are refused at once, on every endpoint, sign-in
  included; they can neither act on the merged guest nor trigger a second merge. Without the account store (MVC slice
  tests) guest tokens are refused (fail closed).
- Registered access tokens stay stateless: the target's tokens are unaffected, and the general rule of ADR 0006 (a
  revoked session's access token lives up to 15 minutes) is unchanged for them.

### Races and idempotency
- The guest's `users` row is locked (`SELECT ... FOR NO KEY UPDATE`) before any write, and the guest is re-checked
  under the lock. Of two concurrent sign-ins with one guest token (double tap, OTP and Google at once) exactly one
  links it; the other finds it `MERGED` (or `REGISTERED`) and gets the stale-token 401 with nothing written.
  `FOR NO KEY UPDATE` rather than `FOR UPDATE`: it serialises merges and sign-ins, but does not block the foreign-key
  check of the target's data key, which `platform.crypto` creates in its own transaction while re-encrypting.
- Lock order: the sign-in's advisory lock (per number / per identity) → OTP challenge row → guest row → target row →
  per-module rows (guest device rows, then device advisory locks per installation in installation order) → session
  family locks. Device locks before family locks is the order device registration (`POST /devices` binds the family)
  and device revocation already use, which is why the guest's families are revoked after the participants, and, on an
  upgrade, after the registered session (and its device registration) is issued.
- A retry with the old guest token after a successful merge (for example after a lost response) is the same 401; the
  client drops the guest token and signs in without it (the data is already merged, the code is not consumed).
- Stale guest token answer: 401 `UNAUTHENTICATED`, detail "The access token is not valid.", `WWW-Authenticate: Bearer
  error="invalid_token"`, identical whether the security chain or the sign-in transaction notices it.

### Event, logs, metrics
- Outbox `identity.guest.merged`, aggregate `user`/target id, payload `{guestUserId, targetUserId, mode: MERGED |
  UPGRADED, method: PHONE | GOOGLE, revokedSessions}`. No consumer yet (entitlement caches, analytics identity
  stitching, notifications).
- Logs carry ids and counts only (an IT asserts that no number, token or decrypted value appears).
  Metric `qal.identity.guest_merge{outcome=merged|upgraded|refused, method}`; sessions count `guest_merged`.

## Consequences
- No migration: V1 already has `MERGED`, `merged_into_user_id`, `ACCOUNT_MERGED`.
- New public API: `identity.GuestMergeParticipant` (port, implemented by devices, preferences, privacy, languages,
  usage, glossaries); `UserDataEncryption.reencrypt` and `reencryptRow` (default methods). Modules that add per-user
  tables later must extend their participant (or add one) in the same change.
- Each guest-token request costs one indexed read of `users`.
- Participants hold the guest's rows in memory per table; fine for trial accounts, revisit if guests ever sync large
  histories.

## Contract gaps (not changed here)
1. No error code distinguishes "the guest bearer can no longer be merged" from a rejected ID token: both are 401
   `UNAUTHENTICATED` on `oauth/{provider}`. Clients can tell them apart only by `WWW-Authenticate: Bearer
   error="invalid_token"` (and the detail). Suggest documenting this 401 on both operations, or a dedicated code.
2. The contract does not say what `mergedGuestUserId` and `isNewUser` are on an upgrade in place (here: guest id =
   `user.id`, `isNewUser: true`), nor that a merge can happen at most once (a replay is 401).
3. The operation text lists "preferences, usage, history"; SD 9.1 lists "preferences, consents and remaining trial
   usage". Both are covered; the two texts should name the same set.
4. ADR 0009 calls linking Google to an existing *registered* account "an explicit, authenticated action (B2.6)". The
   plan's B2.6 is guest → phone/Google only; there is no contract operation for adding an identity to a registered
   account. Registered-to-registered linking needs its own step and contract operation.

## Open
- B3.4: if the Android app-rule HMAC key is per account, moved rules never match on the target's devices; the client
  must re-upload them (or the key must be per person, not per account).
- B3.6: when the per-user dictionary HMAC key (ADR 0007 D2) exists, the dictionary participant must recompute
  `word_hmac` for moved words under the target's key and compare conflicts on it.
- B4.2: the metering engine must count a carried-over counter whose window overlaps (but differs from) the window it
  computes for the target (different time zone or plan period).
- Consent versions: a guest's acceptance of a newer `TERMS_ACCEPTANCE` version does not replace the target's older
  acceptance (target decision wins); the client re-asks.
- B6.3: purge of `MERGED` guest rows (and what stays on them: consents, sessions, revoked devices, usage history) after
  the retention period.
- Push tokens of a guest device that loses to the target's row for the same installation are dropped; the app sends
  the token again on its next device registration.
