# ADR 0009 — Google sign-in (ID token verification)

**Status:** Accepted · **Date:** 2026-11-17 · **Plan step:** B2.2 (follow-up of ADR 0006) · **Spec:** SD §9.1, §9.2;
contract `POST /api/v1/auth/oauth/{provider}` (`exchangeOAuthIdToken`); V1 `users`, `user_auth_identities`

## Context
SD §9.2: "Google / Apple: the client sends an ID token, which the server verifies against the issuer's JWKS." The
contract carries `idToken` and a **required** `nonce`, an optional `displayName`, `ageConfirmed` and `device`, and
returns `AuthSession`. The SD says nothing about linking accounts by e-mail; it only says that a guest is merged on
sign-in (B2.6, not part of this step). `user_auth_identities` has `UNIQUE (identity_provider, provider_subject)` and
`UNIQUE (user_id, identity_provider)`, and its comment defines the subject of GOOGLE/APPLE as the `sub` claim.

## Decision

### Verification (`identity.oauth.JwksIdTokenVerifier`, Nimbus)
- **Signature:** RS256 only. The key selector is pinned to RS256 and RSA signing keys chosen by `kid`, so `alg: none`,
  HS256 (also keyed with the public key), ES256 and any foreign key are refused before any claim is read.
- **Claims:** `iss` is `https://accounts.google.com` or `accounts.google.com`; `aud` contains one of the configured
  client ids (Android, iOS, web); `exp` in the future and `iat` not in the future, with 60 s skew (max 5 min);
  `sub` present (at most 255 characters, the column size); the `nonce` claim equals the request's `nonce` (constant
  time; a token without a nonce is refused, because the contract makes the nonce mandatory).
- **Nonce limits:** the nonce is client-chosen (the contract has no server nonce for OAuth), so it binds a token to
  one request but does not by itself stop a stolen, still-valid token from being replayed. The bound on that is the
  token's own `exp` (Google: about an hour). A server-issued nonce would need a contract change.
- **`email_verified`:** the e-mail address is used only when it is `true` (boolean, or the string `"true"`);
  otherwise it is ignored as if absent.
- **JWKS:** `qal.identity.oauth.google.jwks-uri` (default `https://www.googleapis.com/oauth2/v3/certs`, https only;
  plain http is accepted for loopback hosts so tests and local stubs can serve a fake JWKS). Nimbus
  `JWKSourceBuilder`: cache for `jwks-cache-ttl` (1 h), refresh when a token names an unknown `kid` (key rotation),
  at most one fetch per `jwks-min-refresh-interval` (30 s) so a flood of unknown key ids cannot hammer Google (the
  throttled case answers 401, not 503), one retry, tolerance of a provider outage for `jwks-outage-tolerance` (6 h:
  cached keys keep verifying), 3 s timeouts, 256 KiB limit, no redirects. If the keys cannot be fetched and none are
  cached the answer is 503 `PROVIDER_UNAVAILABLE`.
- **Fail closed:** `qal.identity.oauth.google.client-ids` (env `QAL_GOOGLE_CLIENT_IDS`, comma separated). Without ids
  Google sign-in is off (400 `PROVIDER_NOT_SUPPORTED`, a startup warning), and under `prod`/`production`/`staging`
  startup fails unless `qal.identity.oauth.google.enabled=false`. No token is ever accepted without an audience check.
- Errors: every rejected token is the same 401 `UNAUTHENTICATED` with a generic detail. The reason is an enum name that
  is only counted (`qal.identity.oauth{provider,outcome=rejected_*}`) and logged at DEBUG; token content, claim values
  and library messages are never logged or returned.

### Account resolution
- **By (provider, subject) only.** An existing `GOOGLE` identity signs in to its account; otherwise a `REGISTERED`,
  `ACTIVE` user and the identity are created in one transaction, serialised per identity by an advisory lock.
- **Never by e-mail.** A provider e-mail can equal a different person's phone-registered or e-mail account, and
  linking on it is an account takeover (for example a Workspace admin creating a Google identity for an address that
  somebody else uses). The SD does not ask for it. Linking Google to an existing account is an explicit, authenticated
  action (B2.6, "account linking"). So a Google sign-in whose verified e-mail equals another account's e-mail creates
  a separate account.
- **Stored claims:** `sub` (`provider_subject`), the e-mail only if verified (`provider_email`, refreshed on each
  sign-in when a verified one is present), and the display name for a *new* account only (the request's
  `displayName`, else the token's `name`, at most 120 characters, never overwritten later). `users.email` is **not**
  set: it is unique among live accounts and reserved for the e-mail sign-in and linking steps. No ID token, nonce or
  authorization code is stored.
- **Status rules** are those of phone sign-in (ADR 0006): `SUSPENDED` gives 403 `ACCOUNT_SUSPENDED`, `DELETED` and
  `MERGED` give 401 `UNAUTHENTICATED`, `PENDING` becomes `ACTIVE`, `DELETION_PENDING` may sign in. A new account needs
  `ageConfirmed: true` (400 `VALIDATION_FAILED` on `ageConfirmed`, nothing written; the token is verified first).
- **Session and device:** the existing `SessionIssuer` in the same transaction; a `device` member is registered through
  `DeviceRegistrar` exactly as on OTP verify, so the session and its access tokens carry `did`.
- **Guest upgrade:** the contract says "same guest-upgrade merge behaviour as OTP verification", which is B2.6. Until
  then a guest bearer token is accepted and ignored (an *invalid* bearer is 401, ADR 0007 D9) and `mergedGuestUserId`
  is never returned.

### Providers
`GOOGLE` is the only verifiable provider. `APPLE`, and any other value, answer 400 `VALIDATION_FAILED` with field error
`provider` / code `PROVIDER_NOT_SUPPORTED`. The contract's error list for this operation had no better fit (the
declared 400 is the closest, and 501 is not in the contract). The verifier class is provider-neutral
(issuers, audiences, JWKS URI), so Apple is a second instance plus Apple's own rules (private relay e-mails, name only
on first sign-in, `authorizationCode` for revocation).

### Limits, metrics, contract
- Per client IP (`exchange-per-ip` 60/h) and per `device.installationId` when sent (`exchange-per-device` 20/h),
  PROVISIONAL, checked before any verification so junk tokens cannot make the server fetch keys or write rows. 429
  `RATE_LIMITED` with `Retry-After`/`RateLimit-*`. In-memory per instance (ADR 0006).
- Metrics `qal.identity.oauth{provider,outcome=success|created|rejected_*|provider_unavailable|age_required|suspended|
  unavailable|rate_limited|unsupported}`; logs carry ids only.
- Contract (docs copy, synced): the operation now also declares 403, 429 and 503; its description states the linking
  rule. `IdentityContractDeclarationsTest` guards it.

## Consequences
- No migration. `DeviceRegistrationDto` moved from `OtpDtos` to `identity.session` so both sign-in controllers share it.
- Real Google verification needs only the client ids; tests use a loopback fake JWKS (`FakeGoogle`).
- Replay of a stolen, unexpired ID token within its lifetime is possible by design of the contract (see nonce
  limits); a server nonce would close it.
