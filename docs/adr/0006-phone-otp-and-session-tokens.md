# ADR 0006 — Phone OTP sign-in and session tokens

**Status:** Accepted · **Date:** 2026-11-17 · **Plan steps:** B2.1 (phone OTP), B2.4 (tokens), B2.4 follow-up
(ADR 0007 D8/D9) · **Spec:** SD §9.2, §9.3, §18; contract `/api/v1/auth/otp/*`, `/auth/refresh`, `/auth/logout`;
V1 `otp_challenges`, `users`, `user_auth_identities`, `user_sessions`; V3, V4

## Context
SD §9.2 fixes the OTP rules: E.164 numbers, 6-digit codes, at most 5 attempts per challenge, resend cooldown,
per-number and per-IP limits, codes stored only as hashes, a pluggable SMS provider. SD §18 asks for Bucket4j limits
per IP/device/user and forbids logging tokens, OTPs or user text. The SMS aggregator for +251 is still open (OI-11).

## Decision — phone OTP (B2.1)

### Numbers
Strict E.164 (the contract's pattern) plus one country rule: +251 numbers must be mobile numbers
(`^\+251[79][0-9]{8}$`, Ethio Telecom 9x and Safaricom 7x), so no SMS is paid for landlines or wrong lengths.
libphonenumber is not used (large, frequently updated metadata; the aggregator rejects unroutable numbers; the limits
bound the cost of junk). Revisit for per-country diaspora rules.

### Codes, hashes and the sealed number
- Code: 6 digits, uniform from `SecureRandom`.
- One server secret `qal.identity.otp.secret-key` (env `QAL_OTP_SECRET_KEY`, base64 of >= 32 bytes). Three keys are
  derived from it with HMAC-SHA-256 under distinct labels.
  - `phone_hash` = hex HMAC(K_phone, E.164). It is keyed, so the column cannot be reversed by enumerating the small
    phone-number space. It is used for the cooldown, supersession, the per-number advisory lock and as the
    per-number rate-limit key.
  - `code_hash` = `"v1:"` + hex HMAC(K_code, challengeId || code). A database dump is useless without the secret,
    and the hash is bound to its challenge. It is compared with `MessageDigest.isEqual`, and an unknown format never
    matches.
  - `phone_ciphertext` (**V3 migration**): AES-256-GCM(K_enc, E.164) with the challenge id as associated data. The
    verify request carries only `challengeId` and `code`, but a successful verify has to find or create the account by
    its number, and the V1 table keeps only a one-way hash. The number is never stored in clear in this table.
- Fail closed like the local KMS (ADR 0005): no secret means no startup. The dev/test-only
  `qal.identity.otp.allow-ephemeral-secret-key=true` generates one per JVM and is refused under the
  `prod`/`production`/`staging` profiles. Rotating the secret only invalidates challenges in flight (minutes).

### Challenge lifecycle
- **Start:** number rules (400) → per-IP bucket → transaction { `pg_advisory_xact_lock` on the phone hash → resend
  cooldown from the newest challenge of the number (429 `RATE_LIMITED` + `Retry-After`) → per-number buckets →
  insert } → SMS **after commit** (SD §8.3). A delivery failure is counted and logged, and the answer is still 202
  ("code sent or silently suppressed"). The response never depends on whether the number has an account.
- **Verify:** per-IP bucket and per-device bucket (`device.installationId`) → transaction { same advisory lock →
  `SELECT … FOR UPDATE` → reject if consumed (**replay**), superseded (a newer challenge exists for the number),
  expired (`OTP_EXPIRED`), or locked (`attempts >= max_attempts`, `OTP_TOO_MANY_ATTEMPTS`) → constant-time compare →
  a wrong code increments `attempts`. Reaching 5 locks the challenge (that answer is already `OTP_TOO_MANY_ATTEMPTS`) →
  `consumed_at` → find or create the account → issue the session }. Failures return normally from the transaction so
  the attempt counter commits, then become Problems. Unknown, replayed, superseded and wrong codes all give the same
  `OTP_INVALID`.
- **Accounts:** lookup by `PHONE` identity, then by a live `users.phone_e164` (the identity row is added then).
  Otherwise a `REGISTERED`/`ACTIVE` user + `PHONE` identity is created, which requires `ageConfirmed: true`. Without
  it the answer is 400 `VALIDATION_FAILED` on `ageConfirmed`, and the code is neither consumed nor counted. `SUSPENDED`
  gives 403 `ACCOUNT_SUSPENDED`. `DELETED`/`MERGED` rows give 401 `UNAUTHENTICATED`. `DELETION_PENDING` may sign in.
- `VOICE_CALL` is accepted and delivered as SMS until a voice provider exists.
- Defaults (PROVISIONAL, config): code TTL 5 min, cooldown 60 s, 5 attempts.

### Rate limits
- `platform.ratelimit` (named interface): `RateLimiter.tryConsume(policy, key)` → `RateLimitDecision`, which renders
  a 429 `RATE_LIMITED` Problem with `Retry-After` and `RateLimit-Limit/Remaining/Reset`. To allow this,
  `ApiException` gained response headers (`Content-Type`, `Content-Length` and `X-Correlation-Id` stay
  platform-owned).
- Implementation: Bucket4j local buckets (fixed window per key: `refillIntervally`) in a bounded Caffeine cache per
  policy (`qal.ratelimit.local.max-keys-per-policy`, 100 000; idle buckets expire after one period). **Per instance
  only**: N instances allow up to N× the limit, and a flood of distinct keys evicts (resets) old buckets. SD §8.1
  names "Bucket4j on PostgreSQL first". That store (or Redis/Valkey) replaces the implementation behind the same
  interface before more than one instance serves traffic. The resend cooldown, supersession and the 5-attempt
  lockout are in PostgreSQL and are already exact across instances.
- Policies (PROVISIONAL, `qal.identity.otp.limits.*`): start per IP 30/h (generous because of carrier CGNAT), start
  per device 10/h, start per number 5/h and 10/24h, verify per IP 60/h, verify per device 20/h. The caller's own
  buckets (IP, then device) are checked before the number's, so a client that is already blocked cannot drain a
  victim number's budget. A successful start returns the per-IP `RateLimit-*` headers, never the per-number budget
  (which would hint at activity on someone else's number).
- Device: verify uses `device.installationId`. Start uses the optional `OtpStartRequest.installationId` (added to the
  contract by ADR 0007 D9, same UUID). A start without it is limited per IP and number only. The id is
  client-chosen, so this limit slows a single honest-but-buggy or scripted installation; it is no defence against a
  client that rotates ids (the IP and number limits are).

### SMS
`notifications` owns SMS (SD §8.2): public port `OtpSender`/`OtpMessage` (redacting `toString`), internal
`SmsProvider` adapters. Only `log` exists (`qal.notifications.sms.provider`, no default, refused under production-like
profiles). It logs a masked number (`+251*******67`) and never the body. The DEV-ONLY switch
`qal.notifications.sms.log.include-message=true` prints the body so a developer can sign in locally. Provider errors
are rethrown without cause or message. The WireMock stubs under `infra/compose/wiremock` (`/sms/v1/messages`) are
ready for an HTTP adapter once OI-11 picks an aggregator.

### Metrics and logs
`qal.identity.otp.started{outcome,destination=ET|INTL}`, `qal.identity.otp.verified{outcome}`,
`qal.identity.otp.lockouts`, `qal.ratelimit.rejected{policy}`, `qal.notifications.sms.sent{provider,outcome}`. No
number, hash, IP or code is ever a tag or log argument. A test captures every log event of a full flow (TRACE for
`com.qalsmartkeyboard`) and asserts that the code, the number and the tokens are absent.

## Decision — sessions and tokens (B2.4)

### Access token
- ES256 (ECDSA P-256/SHA-256) JWT signed with Nimbus. Header `typ: at+jwt` (RFC 9068), `kid`. SD §9.3 allows EdDSA
  or ES256. ES256 needs no extra provider in Nimbus (its Ed25519 signer needs Tink), and as an asymmetric algorithm it
  lets other services verify with the public key alone.
- Claims: `iss`, `aud`, `sub` (user id), `sid` (refresh-token family), `did` (device, only once B2.5 registers
  devices), `accountType` (`REGISTERED`/`GUEST`; the contract's `bearerAuth` says guest tokens carry it), `iat`,
  `exp`, `jti`. There is no personal data. SD's `plan` (hint) and `scope` are left out until entitlements (B4.x) and
  scoped tokens need them. Lifetime 15 min (configurable, capped at 15 min by the contract's "10–15 min").
- Keys: `qal.identity.tokens.signing-key.{key-id, private-key (PKCS#8 PEM), public-key (SPKI PEM)}`. The pair must be
  P-256 and must match (a probe is signed at startup). `previous-keys` keeps retired public keys for verification
  during a rotation. Fail closed like B1.5: no key means no startup. `allow-ephemeral` (dev/test) generates a pair per
  JVM and is refused under production-like profiles. No JWKS endpoint is published (the contract has none).
- Verification (Spring Security resource server, `NimbusJwtDecoder` over the configured public JWK set, ES256 only):
  signature first (so `alg: none`, HS256, foreign keys and unknown `kid` fail as `UNAUTHENTICATED`), then the
  validators: `typ`, `iss`, `aud`, `iat` not in the future, UUID `sub`/`sid`/`did`, known `accountType`, `jti`,
  `exp` with 30 s skew. A token whose **only** failure is expiry answers `TOKEN_EXPIRED`. Every other failure answers
  `UNAUTHENTICATED`.
- Stateless: no database lookup per request. A session revoked by logout or reuse detection keeps a valid access token
  for at most 15 minutes. Operations that cannot accept that must check the session explicitly.

### Refresh tokens, families, reuse
- Opaque, 256 random bits (base64url, 43 characters). Only the SHA-256 is stored (`user_sessions.refresh_token_hash`,
  SD §9.3). An unkeyed hash is enough for 256-bit random values. Sliding 60-day lifetime: each rotation's new row gets
  a fresh `expires_at`.
- One `user_sessions` row per refresh token. Sign-in creates a new `token_family_id`, which is the access token's
  `sid`. Refresh takes the family's transaction-scoped advisory lock (`hashtextextended('qal.session-family:' || id)`,
  found by an unlocked read of the token's family), then locks the presented row (`SELECT … FOR UPDATE`). If it is
  ACTIVE and unexpired, a successor row is inserted in the same family (current IP/user agent, same device), and the
  old row becomes `ROTATED` with `rotated_at` and `replaced_by_session_id` through a compare-and-set on
  `session_status = 'ACTIVE'`.
- `last_used_at` is `NULL` on a new row and is set whenever the token is presented (rotation, expiry, a refresh refused
  for a suspended account, a grace retry of a rotated row). It is the "never used" signal of the grace window.
- **Reuse:** a `ROTATED` token outside the grace window below gives `REFRESH_TOKEN_REUSED`. Every ACTIVE row of the
  family becomes `REVOKED` (`TOKEN_REUSE`), and `reuse_detected_at` is set on the presented row. One outbox event per
  incident, `identity.refresh_token.reuse_detected` with `{userId, tokenFamilyId, revokedSessions}`, lets
  notifications tell the user (SD §9.3). No consumer exists yet. Rotated rows keep their status, so the chain stays
  auditable.
- **Grace window (ADR 0007 D8, SD §9.3 amendment).** A lost response on a slow network makes the client retry with the
  token that was just rotated. With the presented row `P` (ROTATED) and its direct successor `S`
  (`P.replaced_by_session_id`), the retry rotates again if **all** of these hold:
  1. `qal.identity.tokens.refresh-reuse-grace` (default 30 s, `0` disables, at most 2 min) is not zero, and
     `now < P.rotated_at + grace`. `rotated_at` is the **first** rotation of `P` and is never moved, so repeated
     retries cannot extend the window past 30 s.
  2. `P` is unexpired and `P.reuse_detected_at IS NULL`.
  3. `S`, locked `FOR UPDATE` after `P`, is in the same family, `ACTIVE`, unexpired, has no successor
     (`replaced_by_session_id IS NULL`), no `reuse_detected_at`, and `last_used_at IS NULL` (never presented).
  4. The account checks of a normal rotation pass (deleted/merged revokes the family, suspended gives 403 and changes
     nothing).

  Then `S` becomes `REVOKED` with the new reason `SUPERSEDED_RETRY` (compare-and-set on `ACTIVE` and
  `last_used_at IS NULL`), a new successor `S'` is inserted in the same family, and `P.replaced_by_session_id` moves
  from `S` to `S'` (compare-and-set on the old pointer; `last_used_at` set). The answer is a normal 200 pair
  (`qal.identity.sessions{event=rotated_grace}`, an INFO log with the family id). Every other case is reuse as above:
  after the window, successor already rotated or presented, a token two or more generations old (its successor is
  `ROTATED`), a successor already revoked (logout, reuse) or expired.
- **Presenting a superseded successor is reuse.** `S` never reached the client that retried, so whoever presents it
  later is a second holder of the family: the family is revoked and the event is published, like a `ROTATED` token.
  Other revoked tokens still give `UNAUTHENTICATED`. This is why `SUPERSEDED_RETRY` is its own reason (V4) and not
  `TOKEN_REUSE` or `LOGOUT`. It also bounds the accepted risk of the window: an attacker who replays a stolen `P`
  within 30 s of the legitimate rotation, before the legitimate client uses `S`, gets `S'`, but the legitimate client's
  next refresh with `S` revokes the family (and notifies the user). The access token returned with `S` stays valid
  for its remaining lifetime (at most 15 min), like any revoked session's access token.
- **Concurrency.** All refreshes of one family are serialised by the family lock; the row locks are taken in one
  order (presented row, then its successor, then the family's ACTIVE rows), so no deadlock is possible between
  refreshes. Two concurrent grace retries of `P` run one after the other: the second finds the first one's `S'`
  unused and supersedes it, so exactly one ACTIVE successor remains (the first response's token is dead and presenting
  it revokes the family). A retry racing the first use of `S` ends either as grace rotation followed by reuse (the use
  presents a superseded token) or as reuse (the retry sees `S` rotated); both revoke the family, never two valid
  tokens. The family lock also closes a READ COMMITTED gap of the original B2.4 code: a family revocation running
  next to a rotation could miss the successor that rotation inserted (the `UPDATE`'s snapshot predates it). Logout
  takes the family lock(s) (sorted, for "all devices") for the same reason.
- With the window disabled (`0`) every presentation of a `ROTATED` token is reuse, as before: concurrent refreshes of
  one token revoke the family, the winner's successor included.
- Other outcomes: expired gives `TOKEN_EXPIRED` (the row is marked `EXPIRED`). Unknown or revoked gives
  `UNAUTHENTICATED`. A `DELETED`/`MERGED` account revokes the family and gives `UNAUTHENTICATED`. `SUSPENDED` gives
  403 `ACCOUNT_SUSPENDED` without revoking.
- Logout (bearer) revokes the caller's family (`LOGOUT`), or all of the user's families with `allDevices`.

### Security chain (OI-P11)
- One `SecurityFilterChain` in `identity.security`: stateless, CSRF off (bearer tokens, no cookies), no
  form/basic/logout/request cache, `oauth2ResourceServer().jwt()` with the decoder above and a converter to
  `AuthenticatedUser` (identity's public API; authorities `ROLE_REGISTERED`/`ROLE_GUEST`).
- Allow-list = the contract's per-operation `security` (`ApiPaths`). Operations with `[{}, bearerAuth]` are anonymous
  but validate a presented token. Operations with `[{}]`/`[]` (refresh, guest, password reset, webhooks, store
  notifications) and actuator health ignore `Authorization` (callbacks may send their own, and refresh must work next
  to an expired access token). `/partner/v1/**` is closed until API keys exist, and user tokens are never accepted
  there. Everything else, unknown paths included, needs a token. ERROR dispatches are permitted.
- An invalid or expired bearer token on a `[{}, bearerAuth]` operation stays a 401 and is never downgraded to
  anonymous (ADR 0007 D9): a client must not silently lose its identity (for example the guest merge on verify). The
  contract declares that 401 on every such operation.
- 401/403 are written as contract Problems with correlation id by the new platform `ProblemResponseWriter`, which
  shares the body builder with `ProblemExceptionHandler`. 401 carries an RFC 6750 `WWW-Authenticate` that never
  echoes the token.
- The B1.6 version gate stays an MVC interceptor, so it runs after authentication. `/config` remains anonymous and
  exempt.

## Consequences
- V3 adds `otp_challenges.phone_ciphertext BYTEA NOT NULL` (+ length CHECK). No purge job for expired challenges yet
  (retention scheduler, B6.3). The V1 comment says "purged shortly after expiry".
- V4 replaces the `user_sessions.revoked_reason` CHECK to allow `SUPERSEDED_RETRY` (V1 is immutable) and documents
  `last_used_at`. Rows written before this change have `last_used_at = created_at` and therefore never qualify as an
  unused successor (fail-safe: reuse as before).
- Multi-instance deployments need the distributed rate-limit store first (see above).
- Each authenticated request costs one ECDSA verification and no database access. Each refresh costs one unlocked
  read, one advisory lock, one locked row read, one insert and one update (a grace retry: one more locked read and
  update).
- Client IP = `getRemoteAddr()`. Behind a proxy, configure the container to trust `X-Forwarded-For` from the load
  balancer only (`server.forward-headers-strategy`). The application never parses it.

## Resolved by the B2.4 follow-up (ADR 0007 D8/D9)
- Grace window for lost refresh responses: see "Refresh tokens, families, reuse" (SD §9.3 amended).
- Contract: `OtpStartRequest.installationId` (optional) added; per-device start limit `start-per-device` (10/h).
- Contract: 403 (`ACCOUNT_SUSPENDED`, shared `Forbidden`) declared on `otp/verify` and `auth/refresh`; 400 on
  `auth/refresh`; 401 on every optional-bearer operation (an invalid bearer stays a 401).
- Contract: `UserProfile.marketSource` enum is now `USER_SELECTED, STOREFRONT, SIM_COUNTRY, BILLING, ADMIN`, the
  `users.market_source` CHECK list (SQL is canonical). Market fields are still omitted from the profile until
  something resolves the market.

## Open
- OTP SMS text is English only. Localised templates need native review (G0).
- No consumer yet for `identity.refresh_token.reuse_detected` (notifications), and `did`/device binding waits for
  B2.5. Guest upgrade on verify (`mergedGuestUserId`) is B2.6.
