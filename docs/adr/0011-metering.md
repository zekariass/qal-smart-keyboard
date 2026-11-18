# ADR 0011 — Metering engine: reserve, settle, release

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B4.2 (depends on B4.1) · **Spec:** SD §11.1 (reserve before
the provider call, settle with actual usage, release on failure), §11.6 (at most one charge per idempotency key), §14.4
(`QUOTA_EXCEEDED` Problem), §15.2–15.4 (windows, units, credits); V1 `usage_counters`, `usage_events`,
`feature_catalog.credit_cost_per_unit`, `ai_credit_*`, `users.time_zone`, implementation note 3; contract
`IdempotencyKey` parameter, `TooManyRequests`/`QuotaExceededProblem`, `UsageSummary`; ADR 0007 D22, ADR 0010 (open item
"carried counters with different windows")

## Context
B5 (AI gateway), V5.1 (speech), D6.4/D6.7 (TTS, realtime) and E7.5 (partner API) all charge plan allowances and AI
credits. They need one engine that never spends more than the allowance plus the credits under parallel requests,
charges a retried request at most once, gives everything back when a call fails or is abandoned, and computes windows in
the user's time zone. B4.1 provides `EntitlementResolver` (access, `includedUnits`, `unit`, `resetPeriod`,
`creditCostPerUnit`, `creditsUsable`). Credit lots (B4.3) do not exist yet.

## Decision

### API (`com.qalsmartkeyboard.usage`)
`Metering.reserve(userId, featureCode, estimatedUnits, idempotencyKey)` → `Reservation` (id, split allowance/credit units,
credits held, `expiresAt`, window, status, `replayed`) or `QuotaExceededException` (429) / `ApiException`
`FEATURE_NOT_ENTITLED` (403) / `IDEMPOTENCY_KEY_REUSED` (409); `settle(reservationId, actualUnits)` → `Settlement`
(charged units split, `creditsCharged`, `uncoveredUnits`, `usageSource`, `remainingAllowance`, `resetAt`,
`creditBalance`: the contract's `UsageSummary` plus what `ai_requests` stores); `release(reservationId, ReleaseReason)` →
`Release`. Unknown or unmetered features (typing, `DETECT_LANGUAGE`, unit NONE) are programming errors
(`IllegalArgumentException`): the gateway knows the catalogue. Each call is one short transaction; callers must not hold a
transaction across the provider call. Subjects: USER only (organisation / API-client counters, E7.5, later).

### Concurrency: row lock plus a conditional UPDATE
Reserve upserts the window's `usage_counters` row (`INSERT … ON CONFLICT DO NOTHING`, so concurrent first reserves
converge on one row) and locks it `SELECT … FOR UPDATE`. Every reserve of one user, feature and window therefore runs
the check `used + reserved + allowanceUnits ≤ included` one after the other. The `UPDATE … SET reserved_units =
reserved_units + :n WHERE used_units + reserved_units + :n <= :included` repeats the check in the database, so a bug in
the Java arithmetic fails loudly instead of overspending; the closing UPDATE does the same for over-settles. Credits go
through `CreditWallet.hold`, all or nothing, in the same transaction, so a refused hold rolls the whole reservation back.
Lock order is always reservation row → counter row → wallet account; no path takes them in another order, so the paths
cannot deadlock each other. Proof (`MeteringIT`, PostgreSQL 18): 40 parallel reserves against FREE `TRANSLATE` 10/day →
exactly 10 succeed, 30 `QUOTA_EXCEEDED`, counter `reserved = 10`, 10 RESERVE events; a second parallel phase mixes
settles, releases and new reserves and the counter still equals the sum of its reservations; 25 × 50 s against 600 s →
exactly 12; 30 parallel reserves with a 5-credit wallet → exactly 15 (10 allowance + 5 credits), balance 0, never below.

### State: `usage_reservations` (V9)
`usage_events` stays the append-only ledger (RESERVE with `expires_at`, then exactly one SETTLE or RELEASE, V1 unique
index). It cannot hold what settle needs later: the allowance/credit split, the credit price and allowance the
reservation was checked against, the idempotency fingerprint and the status. Deriving open reservations from the ledger
would also mean anti-joining a table that grows forever on every sweep. So V9 adds one mutable row per reservation
(`id` = the RESERVE event id) with CHECKs that tie the columns to the status (RESERVED: nothing settled; SETTLED:
`allowance + credit + uncovered = actual`, `credits_charged = credit units × price`; RELEASED: a reason), a unique
`(user_id, idempotency_key)` and a partial index on open reservations by `expires_at`. Numbered V9 because a parallel
branch may take V8.

### Windows (`UsageWindows`, pure)
Time zone: `users.time_zone` (V1 default `Africa/Addis_Ababa`, SD 15.3); an invalid id falls back to
`qal.usage.default-time-zone` (same default). DAILY: the local calendar day `[start of day, start of next day)`; DST days
are 23/25 hours; where local midnight does not exist the day starts at the first valid local time
(`LocalDate.atStartOfDay`). MONTHLY: calendar months in the zone for the DEFAULT plan (FREE, GUEST); for any other source
anchored to its current period start (`EffectivePlan.periodStart`, new in B4.2): `[anchor + k months, anchor + (k+1)
months)`, every boundary computed from the anchor on the local time line (31 Jan → 28/29 Feb → 31 Mar, no drift; the
local time of day survives DST; the contract example's `resetsAt` equal to the period end follows). WEEKLY (ISO week)
and YEARLY (like MONTHLY in 12-month steps) are supported because `plan_entitlements.reset_period` allows them. NONE: one
lifetime window `[1970-01-01, 9999-12-31)` without `resetAt`. All windows are half-open; `resetAt` = window end.

### Units and credits
Units are the feature's `metering_unit` (REQUEST, SECOND, CHARACTER) as `long`; the caller measures (text = 1 request,
audio = seconds, TTS = characters). Credit amounts are `BigDecimal` with scale 6 (`units × credit_cost_per_unit`, both
exact, so no rounding ever happens; e.g. 30 s × 0.166667 = 5.000010); displayed balances round down
(`QUOTA_EXCEEDED.creditBalance`). The allowance is used first, the remainder goes to credits when `creditsUsable`.
Counters hold allowance units only, so `used + reserved ≤ included` stays true; credit-paid units are on the reservation
rows (B4.5 can sum them per window for `UsageReport.creditsConsumed`).

`CreditWallet` is the port to B4.3: `balance`, `hold(user, reservation, amount)` (all-or-nothing, never below zero),
`settle(user, reservation, held, charged)` (keep `charged`, return the rest). Implementations join the engine's
transaction and are called under the reservation row lock at most once per transition. B4.3 implements it on credit lots (`JdbcCredits`, see "Credits (B4.3)" below; the interim always-empty `NoCreditWallet` is gone).

### Settle, over-settle, release
- Settle ≤ reserved: allowance units first, then credit units; unused credits go back first.
- Over-settle (actual > reserved, e.g. audio longer than estimated): the extra is charged to the window's free allowance
  (beyond everything reserved), then to credits as far as the balance allows; whatever is left is `uncoveredUnits`,
  recorded and **not charged**: allowances and credits are never overdrawn (SD 15.4 "never driven negative"). The gateway
  should bound the estimate (`AUDIO_TOO_LONG`) so this stays rare; it is logged and counted.
- `settle(0)` = release with `NOTHING_USED`. A split request reports `usageSource = AI_CREDITS` (V1
  `ck_ai_request_credits_source`: credits charged implies AI_CREDITS).
- Release returns the allowance units and the held credits (SD 11.1). Settle after release (for example after the
  sweep) and settle with other units throw `ReservationClosedException`; release after settle changes nothing.

### Idempotency (at-most-once charging)
The same key for the same user returns the original reservation (`replayed = true`) with its current status: RESERVED =
still in flight (B5.4 answers `REQUEST_IN_PROGRESS`), SETTLED = already charged (B5.4 replays from its cache or
recomputes without charge). Another feature or another estimate with the key is `IDEMPOTENCY_KEY_REUSED`. Concurrent
duplicates converge: the reservation insert uses `ON CONFLICT DO NOTHING`, which waits for the other inserter, then the
original is read (12 parallel requests with one key → one reservation). A refused reserve stores nothing, so the key can
be retried. Settle and release replay their original outcome. Keys are scoped per user, like V1
`ux_usage_events_idempotency`.

### Expiry sweep
db-scheduler recurring task `usage-reservation-sweeper` (every `qal.usage.sweep-interval`, 1 min) releases RESERVED rows
past `expires_at` (`qal.usage.reservation-ttl`, 15 min, SD 15.3 step 6) with reason EXPIRED, one transaction per row,
locked `FOR UPDATE SKIP LOCKED`: a reservation being settled right now is skipped, and repeated or concurrent sweeps
release nothing twice. A reservation past `expires_at` but not yet swept can still be settled. The platform scheduler
(B1.4) now runs every `RecurringTask` bean modules contribute; `qal.outbox.dispatcher-enabled=false` only drops the
outbox task.

### Guest merge carry-over (resolves the ADR 0010 open item)
`UsageGuestMerge` adds the guest's current `used + reserved` to the target's counter for **the window the engine computes
for the target now** (its plan, reset period, anchor and time zone), instead of "the target's counter containing now, else
a copy of the guest's window". A guest window that overlaps the target's without being equal (another zone, a DAILY guest
window against an anchored MONTHLY one) therefore lands exactly where the target's next reserve looks: never lost, never
counted twice. `GuestMergeIT` now seeds the target's counter with its real window; `MeteringIT` covers UTC guest →
New York target.

### Entitlement resolver changes
`EffectivePlan.periodStart` (the winning source's `current_period_start`) anchors monthly windows. D22 (renewal lag) is a
separate commit.

## Consequences
- One new table (V9); `FlywayBaselineIT` counts 8 migrations / 91 tables.
- Every metered request costs one counter row lock per user and feature: requests of one user serialise per feature,
  other users never contend. The lock is held for a few statements, never across the provider call.
- `usage_reservations` grows with every request; retention (keep at least the 24 h idempotency window, ideally the
  13-month usage history) is B6.3.
- Reserve uses `resolve(userId)` (30 s cache): a plan change reaches metering within the TTL unless billing calls
  `invalidate`.
- Tests switch the sweeper off globally (`src/test/resources/config/application.yaml`); `UsageSweeperSchedulerIT` runs it.

## Contract gaps (not changed here)
1. `UsageSummary` has one `usageSource` and `unitsCharged`, but a request can be paid partly by the allowance and partly
   by credits; there is no `creditsCharged` or split. Reported as `AI_CREDITS` (as V1 requires for `ai_requests`).
2. `IdempotencyKey` says keys are "retained for 24 h"; the engine keeps them as long as `usage_reservations` rows live
   (B6.3), so a key reused after 24 h still replays. Suggest "at least 24 h".
3. Not specified: what a retry with the same key returns after the first attempt failed and was released (no charge).
   V1 allows one RESERVE per key and subject, so the engine replays the RELEASED reservation; B5.4 must decide (answer the
   original Problem, or require a new key) and the contract should say it.
4. `QuotaExceededProblem.creditBalance` does not say it is rounded down (SD 15.4 "shown rounded down"); the engine sends
   whole credits. `resetAt` is omitted for allowances that never reset (reset period NONE); the schema allows that.
5. V1 implementation note 3c names the credit usage source `CREDITS`; the enum value is `AI_CREDITS` (comment only).

## Open
- A time-zone change (no API yet) or a change of reset period on plan change starts a new window, so a user could get
  at most one extra window's allowance by changing zone; plan upgrades getting a fresh paid window is intended. Decide
  when the profile time zone becomes editable.
- Long realtime sessions (D6.7) need reservations that outlive 15 minutes or incremental reserves; add a per-call TTL or
  an extend operation then.
- The guest's in-flight reservations count as used for the target at merge time and later close on the guest's counter,
  so the target can be charged up to the guest's in-flight units (a few) too much.
- B4.3: implement `CreditWallet` on lots and the ledger. V1 `ck_credit_tx_sign` allows positive deltas only for
  PURCHASE/GRANT/REFUND (ADJUSTMENT either sign), so giving held credits back needs ADJUSTMENT or REFUND rows.
- `upgradeOptions` ignore market price books and channel policy (checked at checkout, SD 16.3).
- Flyway runs in order: if this V9 is deployed before the parallel V8, a later V8 is refused; merge V8 first or renumber.

## Credits (B4.3)

Module `usage` owns the credit tables (SD 24 has no separate credits module; billing, M4, will call the `usage.Credits` API).
No migration: V1 already models accounts, lots and an append-only ledger (trigger `fn_forbid_update_delete` on
`ai_credit_transactions`). `JdbcCredits` implements both `Credits` (grant, balance, reverse, adjust, expireDue) and
`CreditWallet`.

- **Lots and idempotency:** `grant(user, units, source, sourceRef, expiresAt)` creates a lot and its opening ledger row
  (`PURCHASE`/`GRANT`/`REFUND`/`ADJUSTMENT` by source; `PROMOTION` is written as `GRANT`). V1 has no source-reference
  column, so the lot id is a deterministic name-based UUID of `(user, source, sourceRef)`: a replay hits the primary key,
  returns the existing lot (`created = false`) and a different amount or expiry is refused. `PURCHASE` needs the payment
  order id as `sourceRef` (V1 FK). Lots are `NUMERIC(18,6)`; units must be positive with at most 6 decimals.
- **Balance:** `balance(user, at)` = sum of remainders of lots with `expires_at IS NULL OR expires_at > at` (exclusive end,
  the injected clock), read from the lots; `display` is rounded down to whole credits (SD 15.4). `ai_credit_accounts.
  balance_units` is the sum of all remainders including expired-but-unswept lots, kept in step by every writer;
  `balance_after` on a ledger row is that account value after the row.
- **FIFO:** `hold` locks the account row, then the active lots `ORDER BY expires_at NULLS LAST, created_at, id FOR UPDATE`
  (the order of V1 `ix_credit_lots_consumption`), checks the total, then takes from lot to lot, one `CONSUMPTION` row per
  lot (key `hold:<reservation>:<n>`, `ai_request_id` = reservation id). All or nothing: when the active total is short,
  nothing is written. Expired lots are never touched.
- **Return to the same lots:** `settle(held, charged)` reads what the reservation drew per lot (consumptions minus
  returns by `ai_request_id`) and gives `held - charged` back **latest expiry first**, so the result equals consuming
  `charged` FIFO; the earliest lots stay the ones paying. Returns are `ADJUSTMENT` rows (V1 `ck_credit_tx_sign`: positive
  deltas only for `PURCHASE/GRANT/REFUND/ADJUSTMENT`, `REVERSAL` is negative only, ADR 0007 D25). A lot that
  expired meanwhile still receives its part and the next expiry run writes `EXPIRY` for it. Settle is idempotent (a second
  call finds net held equal to charged and does nothing); a `held` that differs from what the ledger shows is an
  `IllegalStateException`. The reservation id is stored in `ai_request_id`; B5.3 should create `ai_requests` with the
  same id (or link it) so the logical reference stays meaningful.
- **Refund/adjust API:** `reverse(user, source, sourceRef)` empties a purchase lot with one `REVERSAL` row (SD 15.4:
  the unconsumed remainder; consumed credit is never driven negative; idempotent). `grant(..., REFUND, ref, expiresAt)`
  gives credit back as a new `REFUND` lot; `adjust(user, lot, delta, ref, description)` writes an `ADJUSTMENT` of either
  sign within `0..initial_units`, idempotent per `(lot, ref)`.
- **Expiry job:** db-scheduler `usage-credit-expiry` (`qal.usage.credit-expiry-interval` 1h, `credit-expiry-batch-size`
  100, `credit-expiry-enabled`). Per lot, one transaction: lock the account, lock the lot, re-check, write `EXPIRY`
  (`-remaining`) and zero the lot. Idempotent and safe on several instances (the lock plus the re-check make a second
  run find nothing). The job only settles the books; spendability ends at the instant `expires_at` anyway.
- **Locking:** every writer takes the account row first, then lots in FIFO order, so writers on one account serialise
  and cannot deadlock; the engine's order stays reservation, counter, wallet. `hold` and `settle` refuse to run without
  a transaction (they must join the engine's).
- **Ledger types used:** `PURCHASE`, `GRANT`, `REFUND`, `ADJUSTMENT` (opening rows, returns of unused credit, manual
  corrections), `CONSUMPTION`, `EXPIRY`, `REVERSAL`.
- **Proof:** `CreditsIT` (PG18): FIFO by expiry, grant time and never-expiring last; hold across lots; settle return;
  expiry boundary and job idempotency (also with six parallel runs); 40 parallel holds against 10 credits (13 x 0.75
  succeed, 0.25 left); mixed hold/settle/grant/expiry races keep lot remainders equal to the sum of their ledger deltas;
  UPDATE/DELETE on the ledger fail; metering end to end with real lots. `MeteringIT` now runs on the real wallet.
- **Not covered:** a successful `PURCHASE` grant (needs a payment order; M4.1 will add it); credit sharing for
  organizations (accounts are per user here).
