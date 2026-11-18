# ADR 0017 — AI request pipeline, prompt templates and output guardrails

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B5.3 (depends on B5.2, B4.2) · **Spec:** SD §11.1 (pipeline),
§11.4 (templates, guardrails, "one retry on the same route and then a fallback route; if both fail
`OUTPUT_GUARDRAIL_FAILED` with no charge"), §11.5 (addressee, calendar, clock, numerals), §11.6 (idempotency), §14.4,
§15.3, §19 (no content in logs or storage); contract `TranslateRequest/Response`, `TransformRequest/Response`,
`DetectLanguageRequest/Response`, `UsageSummary`, `AiNote`, `IdempotencyKey`; V1 `ai_prompt_templates`, `ai_requests`;
ADR 0011 (metering), ADR 0014 (SPI, routing), ADR 0007 D22, D25, D29.

## Context
B5.1 gave a provider SPI, B5.2 a router, B4.2 a metering engine. B5.3 joins them into the one path every server AI action
takes, so B5.4 (replay cache) and B5.5 (endpoints) only add a cache and HTTP mapping.

## Decision

### Order and transaction boundaries
`AiPipeline.execute(AiCommand)`; it refuses to run inside a transaction.

| # | Step | Transaction | Failure |
|---|---|---|---|
| 1 | Account state from `EntitlementResolver` (ACTIVE only); optional `AI_PROCESSING` consent | read | 403 `ACCOUNT_SUSPENDED` / `DELETION_PENDING` (D22), 401 `GUEST_SESSION_INVALID` / `UNAUTHENTICATED` |
| 2 | Validation and defaults (request → destination profile → preferences) | reads | 400 `VALIDATION_FAILED` / `INPUT_TOO_LONG` / `LANGUAGE_NOT_SUPPORTED` |
| 3 | Entitlement | none | 403 `FEATURE_NOT_ENTITLED`, REJECTED row |
| 4 | `Metering.reserve` with the `Idempotency-Key` (estimate 1 per text request, or characters) | own, short | 429 `QUOTA_EXCEEDED` (no provider call), REJECTED row without the key |
| 5 | Replay seam (`AiIdempotency`) when the key was used before | — | decision of the seam |
| 6 | Prompt rendered per candidate provider (before any call) | snapshot reads | — |
| 7 | `AiRouter.route` | **none** (no lock held) | route code |
| 8 | Guardrails; retry same provider; fallback route | none | 422 `OUTPUT_GUARDRAIL_FAILED` |
| 9 | `settle` (success) / `release` (every other path) | own, short | — |
| 10 | `ai_requests` insert | own, short | logged, never changes the outcome |

Validation runs before the entitlement check and before reserving, so a malformed request never holds allowance.
`release` failures are logged; the expiry sweep releases within 15 min, so a failure is still never charged.

### Consent
SD 19.3 lists `AI_PROCESSING`, but no SD section requires it for an explicit server action; SD 17 makes server fallback
under PREFER_ON_DEVICE a per-action client confirmation. The gate is therefore behind
`qal.ai.pipeline.require-ai-processing-consent` (default false) and uses `ConsentGate` when on.

### Prompt templates
Stored only in V1 `ai_prompt_templates` (no resource files: `ai_requests.prompt_template_id` is a foreign key, so a
resource template could not be recorded). Served rows: `is_active` (V1 CHECK: only APPROVED) — one per (feature, language
scope, model family). Selection: exact language before `*`, then exact model family before `*`; the family is
`ai_models.configuration->>'modelFamily'`, else the model code. Templates are rendered before routing for every
candidate provider, so a template problem never counts against a provider's health. The served template id is written to
`ai_requests` (the version is that row's `template_version`). Syntax `{{name}}` from a fixed allow-list; **user text has
no placeholder** (it travels next to the instructions as delimited data, SPI contract), and a row with an unknown
placeholder is skipped. No template is seeded: wording needs the G0 native-speaker review and an evaluation run (SD
11.4); without an active row the adapter receives no instructions, which the SPI allows.

### Cultural context (SD 11.5)
Addressee formality and gender: request field, then destination profile, then preferences, per field.
`addresseeInstruction` turns them into one English sentence (honorific forms skip the gender sentence). Calendar, clock
and numeral options default to the preferences; `culturalInstruction` describes the conversions. `calendarContext` gives
today in the user's time zone in both calendars, from a small Java `EthiopianDate` (Julian-day arithmetic, Amete Mihret;
the backend does not link the Rust core). Notes are static: `QUALITY` for BETA tiers, `AMBIGUITY` when translating into
Ethiopic script with unknown gender and no honorific.

### Guardrails
`Guardrails.check` returns flag names, stored in `ai_requests.guardrail_flags`:
EMPTY_OUTPUT; SCRIPT_MISMATCH (>= 90 % of letters in the destination's `supported_languages.script_code`; URLs, e-mails,
handles and, for translations, words copied from the input do not count unless everything was copied; transforms only
need about the input's own share, because code-switched input is normal); LANGUAGE_MISMATCH (mostly English function
words in a translation into a Latin-script language other than English; Amharic vs Tigrinya is not separable without a
LID model); LENGTH_EXCEEDED / LENGTH_TOO_SHORT (per feature and mode, generous for Ge'ez density); NO_OP (translation, or
SHORTEN/EXPAND rewrites, identical to the input); REFUSAL (model refusal phrases the input does not contain);
PROTECTED_TOKEN_LOST (URLs, e-mails, numbers of 3+ digits unless a calendar/clock/numeral conversion is in play);
PROMPT_LEAK (output repeats an instruction line). Alternatives that fail are dropped.

Flow: a failed check → one route **pinned** to the same provider (`RouteRequest.pinnedTo`) → one fallback route over the
providers not yet tried (`RouteRequest.excluding`) → `OUTPUT_GUARDRAIL_FAILED`, release. All share
`qal.ai.pipeline.deadline` (8 s). A provider error during retry/fallback leaves the guardrail failure as the answer, except
CANCELLED (abort) and CONTENT_REJECTED (final). Guardrail failures never touch the circuit breaker (they are successes
for it); each successful provider call is still booked for cost control.

### Metering on each path
Success: `settle(reservation, usage.units(unit))` with the **serving** provider's usage. Provider failure, guardrail
failure, unexpected exception: `release(FAILED)`. Cancellation: `release(CANCELLED)`, `AiRequestCancelledException`
(no body), interrupt flag cleared for the database work and restored. Quota or entitlement refusal: nothing reserved.
Settle finding the reservation already closed by the sweep: answered, not charged (`UNMETERED`).

### `ai_requests`
id = reservation id (ADR 0011: credit ledger rows already reference it), else UUIDv7; subject USER, device, feature,
surface, execution SERVER, source (or detected) and destination language, provider, model, template, status
(SUCCEEDED, FAILED, CANCELLED, REJECTED for content refusals and pre-reservation refusals), usage source, units, credits,
character and token counts, provider duration (serving call, or all attempts), flags, idempotency key (only on the
reserving request; refusals and recomputes store none, so V1's unique index never blocks a retry), error code,
`content_retained` FALSE, created/completed. No text column exists or is written; `AiPipelineIT` scans whole rows.

### Idempotency seam (B5.4)
`AiIdempotency.onReplay(command, reservation)` → `Respond(result)` | `Recompute` | `Fail(problem)`, and
`onCompleted(command, requestId, result)` after a settled success. B5.3's `ReplayWithoutCache` (replaceable,
`@ConditionalOnMissingBean`): RESERVED → 409 `REQUEST_IN_PROGRESS` + `Retry-After: 2`; SETTLED → uncharged recompute;
RELEASED → the original `error_code` again (D25), `PROVIDER_UNAVAILABLE` when it was cancelled or has no row. Recomputing a
released key would hand out a result for a key that was never charged.
Since B5.4 the default bean is `EncryptedReplayCache`, which keeps this behaviour as its fallback (see "Replay cache
(B5.4)" below).

### Router changes
`RouteRequest` gained `onlyProviders`/`excludedProviders` (`pinnedTo`, `excluding`, `withCall`); `RouteResult` gained the
serving `capability` (quality tier for the response).

## Consequences
- No migration. B5.5 maps `AiResult` records to HTTP directly (validated against the contract in `AiPipelineIT`).
- The pipeline reads `user_preferences`, `user_destination_profiles` (enum/language columns, never the label) and
  `supported_languages` through read-only ports (`AiUserContext`, `LanguageCatalog`) instead of the owning modules' APIs,
  to stay clear of the preferences/languages work in flight; swap for module APIs later.
- DETECT_LANGUAGE runs through the pipeline unmetered and is refused for non-ACTIVE accounts, although the contract
  declares no 403 for it.

## Contract gaps (not changed here)
1. `AiNote.type` lacks `CONVERSION` (SD 11.5 lists it); calendar/clock conversion notes cannot be sent.
2. `detect-language` declares no 403 (suspended / deletion pending) and no `Idempotency-Key`.
3. `REQUEST_IN_PROGRESS` and the D25 replay of a cancelled original have no documented outcome.
4. `TranslateRequest.glossaryId` / profile glossary are accepted but not applied (no glossary step yet).

## Open
- Language-ID beyond scripts (am vs ti, om vs other Latin) needs a server LID model or the provider's own detection.
- Template authoring/approval tooling (admin) and the evaluation gate for promotion.
- `estimated_cost_micros` is not written: the router books cost but does not return it.
- Prompt wording, refusal phrases and length bounds need native-speaker and evaluation-run review (G0).

## Replay cache (B5.4)

**Plan step:** B5.4 (depends on B5.3, B1.5) · **Spec:** plan rule N5; SD §8.3 ("keys are stored with a hash of the
request body for 24 hours"), §11.6, §19; contract `IdempotencyKey`, `Conflict` (`IDEMPOTENCY_KEY_REUSED`,
`REQUEST_IN_PROGRESS`); ADR 0005, ADR 0011, ADR 0015, ADR 0007 D25/D33.

### Context
Metering already charges at most once per (user, key) and reports a replay with the original reservation's status. It
only compares feature and estimate, and text features are metered per REQUEST (estimate 1), so it cannot tell the same
body from another one under the same key. B5.3 recomputed every settled replay uncharged: correct for the charge, but a
provider call each time, and any body could be sent under an old key to get an uncharged result.

### Decisions
1. **Storage: PostgreSQL, not Redis.** SD §8.1/§11.6 name Redis for the cache "once AI idempotent replay is enabled",
   but the stack has no Redis yet (SD §17.3 adds it once PostgreSQL contention is measured) and the cache must
   work across instances. Two tables in `V14__ai_replay_cache.sql`, both keyed by the request id (= reservation id,
   unique per user and key):
   - `ai_replay_cache`, **UNLOGGED**: its rows never enter the WAL, so they never reach WAL archives, point-in-time or
     base backups or replicas, where ciphertext would outlive the 10 minutes; a crash empties it, which only means an
     uncharged recompute. A CHECK caps `expires_at - created_at` at 10 minutes. (`pg_dump` still includes unlogged data
     unless `--no-unlogged-table-data`; operators should pass it.)
   - `ai_request_fingerprints` (logged): needed for 24 h and for in-flight comparison, cheap to lose only at the cost of
     a 409 on a legitimate retry.
   A Redis cache would be a drop-in replacement of `EncryptedReplayCache`'s storage (same seam).
2. **Encryption with the user's data key, not "a rotating server key".** SD §11.6 suggests AES-GCM under a server key.
   `UserDataEncryption` (ADR 0005) is the existing per-user AES-256-GCM envelope and adds crypto-shred: account deletion
   makes every row unreadable, including copies. Contexts: (`ai_replay_cache`, `response_ciphertext`, request id) and
   (`ai_request_fingerprints`, `fingerprint_ciphertext`, request id), so a value moved to another row or user does not
   open. The plaintext is the contract JSON of the `AiResult` (a private Jackson mapper; the response type follows the
   command, so no type tag), wiped after sealing.
3. **Fingerprint** (`RequestFingerprint`): SHA-256 over a versioned, length-prefixed encoding of the body as sent
   (feature, languages, style, mode, tone, target length, addressee, cultural options, preserveNames, glossary,
   destination profile, exact text; not headers, not resolved defaults, which may change between retries of the same
   body). It is not keyed by an HMAC secret; instead the digest is **only stored sealed** under the user's key, so it is
   no text oracle without that key and is shredded with it. That avoids a new server secret and per-user HMAC key; the
   cost is one AES-GCM seal per metered request and one open per replay. Compared in constant time.
4. **When it is written.** A new `AiIdempotency.onReserved(command, reservation)` hook runs right after a new
   reservation, before the provider call, so a different body is refused while the original is in flight. A failure is
   logged and the request goes on (metering already guarantees the single charge); the later replays then fall under
   decision 6.
5. **Replay matrix** (`onReplay`, after the fingerprint check; a mismatch is 409 `IDEMPOTENCY_KEY_REUSED` in every state):

   | Reservation | Fingerprint | Cache | Answer |
   |---|---|---|---|
   | RESERVED | any | — | 409 `REQUEST_IN_PROGRESS`, `Retry-After: 2` |
   | SETTLED | matches | live entry | the original response, `replayed: true`, same `requestId` and `usage`; no provider call, no charge, no `ai_requests` row |
   | SETTLED | matches | none / expired / unreadable | recompute, `usageSource UNMETERED`; never cached (only `onCompleted` of a reserving request writes the cache) |
   | SETTLED | absent (older than 24 h, or never written) | — | 409 `IDEMPOTENCY_KEY_REUSED` ("no longer recognised; use a new key") |
   | RELEASED | matches / absent | live entry | the cached response (the original succeeded uncharged after the sweep released it) |
   | RELEASED | matches / absent | none | the original failure (D25, `ReplayWithoutCache`) |

6. **Expired keys are refused, not recomputed.** Recomputing an unverifiable body would let a client reuse any key older
   than the fingerprint retention to get uncharged AI results. The contract keeps keys "at least 24 h"; after that a key
   is spent. Keys used before B5.4 was deployed behave the same.
7. **TTL and sweep.** `qal.ai.idempotency.cache-ttl` 10 min (0 = off, above 10 min refused at startup),
   `fingerprint-retention` 24 h (below refused). Reads compare `expires_at` with the application clock, so expired rows
   are never served before the db-scheduler task `ai-replay-cache-sweeper` (1 min, batches of 1000) deletes them.
8. **Concurrency.** Two identical requests race in `Metering.reserve`: the counter lock and `ON CONFLICT` let exactly
   one reserve; the other gets a RESERVED replay → `REQUEST_IN_PROGRESS` (whether or not the fingerprint is written yet),
   or, once settled, the cached response. `AiReplayCacheIT` runs six identical requests at once against a slow
   provider: one provider call, one charge, five `REQUEST_IN_PROGRESS`, then a late duplicate gets the cache.
9. **Account deletion and guest merge.** `AiIdempotencyDeletion` (new order `UserDeletionParticipant.AI_GATEWAY` = 750)
   deletes both tables' rows (ADR 0015 table). Guest merge moves nothing: reservations stay with the guest (ADR 0011),
   and its rows expire.

### Consequences
- One extra insert and AES-GCM seal per metered request before the provider call; one more after settling. A cache hit
  costs a read and an open, no provider call.
- The cache's lifetime ceiling is enforced three times: configuration, CHECK constraint, read filter.
- `AiPipelineIT.replaysNeverChargeTwice` now expects the cached response; `AiReplayCacheIT` holds the done-when.

### Open
- SD §11.6 / contract say "PostgreSQL never stores AI input or output text, except as encrypted opt-in history". The
  cache stores AI output **only as ciphertext**, ≤ 10 min, in an unlogged table; the wording should be amended (or a
  Redis cache adopted) by the owner of SD/contract. Same for SD's "rotating server key" (here: per-user data key).
- Contract `IdempotencyKey` could say that a key older than 24 h may be answered `IDEMPOTENCY_KEY_REUSED` and that
  replayed responses keep the original `requestId` and `usage` (D20 batch).
- Redis/Valkey replacement when measured load requires it (SD §17.3).
