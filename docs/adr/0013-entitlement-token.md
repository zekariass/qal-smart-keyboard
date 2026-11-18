# ADR 0013 — Entitlement token: Ed25519 JWS for offline gates

**Status:** Accepted · **Date:** 2026-11-18 · **Plan step:** B4.4 (depends on B4.1) · **Spec:** SD §15.5 (token,
claims, 24 h + 72 h), §11.9 (`ON_DEVICE_AI_MODELS` checked offline), §18 (signing keys in a secret manager or KMS,
rotation), plan rule N11 (entitlement tokens are Ed25519-signed); contract `getEntitlements`, `getEntitlementToken`,
`getEntitlementTokenKeys`, `EntitlementToken`, `JsonWebKeySet`; ADR 0005 (KMS port), ADR 0006 (access-token keys),
ADR 0007 D22 (renewal lag), ADR 0011 (metering)

## Context
Typing and on-device features must work offline (N1). Some on-device features are paid (`ON_DEVICE_AI_MODELS`, SD
11.9), so an offline client needs a statement it can check without the network that the user is entitled. SD 15.5
fixes the form: an Ed25519 JWS, 24 h validity, 72 h offline grace, `kid` from a key set at `/.well-known/jwks.json`,
used **only** for offline gates. The contract embedded the token in `getEntitlements` (B4.5) but had no operation to
fetch a token on its own, no key-set operation and no schema for the claims; SD and contract named the claims
differently (`plan`/`offline_grace_until` vs `planCode`/`deviceId`).

The threat is the device owner: they control the device clock, storage and (on a rooted device) the TLS trust store.
The token protects revenue from on-device features only; it never authorises server work.

## Decisions

### D1. Operations (contract changed)
- `GET /api/v1/me/entitlements/token` (`getEntitlementToken`, bearer): a new token for the caller, `Cache-Control:
  no-store`. The same `EntitlementToken` object is embedded by `getEntitlements` (B4.5 calls
  `EntitlementTokenService.issue`). A separate operation lets the keyboard refresh its gates without usage figures.
- `GET /.well-known/jwks.json` (`getEntitlementTokenKeys`, anonymous, `Authorization` ignored, `max-age=3600, public`):
  RFC 7517 set of the entitlement-token public keys only (`kty OKP`, `crv Ed25519`, `alg EdDSA`, `use sig`). Access-token
  keys (ES256, server-verified only) are not published. Outside `/api/v1`, so no app-version gate.
- The inline `entitlementToken` object became the `EntitlementToken` schema; its description carries the claims and the
  client rules (D4).

### D2. Token format
Header `alg: EdDSA`, `typ: qal-ent+jwt` (explicit typing, RFC 8725 3.11, so no other JWT of ours can stand in), `kid`.
Claims, none of them personal data:

| Claim | Value |
|---|---|
| `iss` | `qal.entitlements.token.issuer` (`qal-smart-keyboard`) |
| `aud` | `qal.entitlements.token.audience` (`qal-keyboard`: the IME, extension and companions) |
| `sub` | user id (SD 15.5 names `sub`; a UUID that the client already holds, not a pseudonym) |
| `deviceId` | the access token's `did`, when the session is bound to a device |
| `planCode` | effective plan (`EntitlementResolver`) |
| `features` | object of feature code → boolean, offline gates only (D3) |
| `policyVersion` | the quota policy version that produced the gates (`PROVISIONAL_V2`) |
| `ver` | token format version, `1` |
| `iat`, `exp`, `offlineGraceUntil` | NumericDate seconds; `exp = iat + 24 h`, `offlineGraceUntil = exp + 72 h` |
| `jti` | random UUID |

Claim names follow the contract (camelCase: `planCode`, `deviceId`, `offlineGraceUntil`); SD 15.5 was amended to point
at the contract instead of its older `plan` / `offline_grace_until`. `iat` is truncated to the second so the edges are
exact. Plan display names, allowances, usage, credits, phone and e-mail are never in the token.

### D3. Which features are offline gates
`qal.entitlements.token.offline-features`, default `[ON_DEVICE_AI_MODELS, PREMIUM_THEMES]` (V18 adds the premium keyboard themes gate; SD 15.5 and 11.9: "currently
`ON_DEVICE_AI_MODELS` and premium on-device packs"; premium packs have no feature code in the catalogue yet, so they
will be gated by `ON_DEVICE_AI_MODELS` or by a new catalogue code added to this list). Whatever is configured, only
**gated features with unit `NONE`** are written: a metered feature (a server AI allowance) or an ungated or unknown code
is left out with a one-time warning. The value is the resolver's `accessEnabled` at issue time, so plan rows, overrides
and the D22 renewal lag apply as for every other gate.

### D4. Client rules (normative; reference verifier `EntitlementTokenVerifier` in test scope)
1. Three base64url parts, no padding, at most 8 KiB.
2. `alg` exactly `EdDSA` (rejects `none`, `HS*`, `ES*`, `RS*` and the fully specified `Ed25519` name before any key
   lookup); `typ` exactly `qal-ent+jwt`; no `crit`, `jwk`, `jku`, `x5u`, `x5c`; `kid` present in the **key set pinned
   in the app build**.
3. Ed25519 signature (64 bytes) over `ASCII(header.payload)` with that key. On Android this one step is done by
   `keyboard-core`'s `verify_ed25519(public_key, message, signature) -> bool` (strict verification: canonical `S`,
   small-order keys rejected, wrong lengths give `false`; the same `ed25519-dalek` rules as pack signatures), so the
   app ships no second Ed25519 implementation (BouncyCastle was dropped, ADR 0007 D40). The Kotlin side keeps every
   other step (JWS parsing, header and claim checks, time windows).
4. `iss`, `aud`, `ver = 1`; `sub` is the signed-in user; `deviceId`, when present, is this device; `iat < exp <= iat +
   24 h`; `exp <= offlineGraceUntil <= exp + 72 h`; `features` an object of booleans; `planCode` and `jti` present;
   `iat` at most the clock skew (5 min) in the future.
5. Time: "now" is the later of the device clock and the newest `iat` or server `Date` seen, so turning the clock back
   cannot extend a token. `now < exp`: gates apply. `exp <= now < offlineGraceUntil`: gates apply **only while
   offline**; online, the client fetches a new token. `now >= offlineGraceUntil`: the token is void.
6. A 401 or 403 from the token endpoint means: delete the cached token.

The reference verifier is an independent implementation (JDK Ed25519, hand-written compact parsing; Nimbus only parses
JSON) so the tests check the format, not one library against itself. (The Android client's tests mint tokens with the
JDK signer and verify them through the Rust core, so they cross-check the two implementations.) Android, iOS and `keyboard-core` implement the same
steps (sync point B4.4 ↔ A2.5).

### D5. Who gets a token
Any ACTIVE account, guests included (the GUEST plan opens no offline gate, so a guest's gates are all `false`; one flow
for every client). From `EntitlementResolver.resolve(userId)` at issue time: SUSPENDED → 403 `ACCOUNT_SUSPENDED`;
DELETION_PENDING/DELETED → 403 `DELETION_PENDING`; MERGED → 401 `GUEST_SESSION_INVALID` (the security chain already
refuses a merged guest's access token, B2.6); PENDING/unknown → 401 `UNAUTHENTICATED`. Registered access tokens are
stateless (15 min), so the account check is the issuer's own, not the chain's.

### D6. Ed25519 implementation: the JDK, no new library
Nimbus 10 (already in the build through Spring Security) has `Ed25519Signer`/`Ed25519Verifier`, but they need Google
Tink, an optional dependency (several MB plus protobuf) that the build does not carry. The JDK has had Ed25519 since 15
(JEP 339). `JdkEd25519Signer` implements Nimbus' `JWSSigner` over `Signature.getInstance("Ed25519")`, so Nimbus still
builds the JWS and JWK structures. Proof of interoperability: the signer reproduces the RFC 8037 A.4 example JWS byte
for byte (Ed25519 is deterministic). No dependency was added.

### D7. Keys, fail closed, rotation
- `qal.entitlements.token.signing-key.key-id|private-key|public-key` (env `QAL_ENTITLEMENT_TOKEN_KEY_ID`,
  `QAL_ENTITLEMENT_TOKEN_PRIVATE_KEY`, `QAL_ENTITLEMENT_TOKEN_PUBLIC_KEY`): PKCS#8 + SPKI PEM (or bare base64 DER), or the
  private key as an OKP JWK (`d` and `x`; the public key is then optional and must match if given). Startup refuses a
  non-Ed25519 key, a pair that does not sign-and-verify a probe, a bad or duplicate `kid`, and a missing key.
  `allow-ephemeral=true` generates a per-JVM key for development and tests and is refused under
  `prod`/`production`/`staging` (same pattern as B1.5's local KMS and B2.4's access-token keys). Error messages never
  echo key material; `toString` redacts.
- `previous-keys[n].key-id|public-key`: published after the signing key and accepted by verifiers.
- **Rotation** (clients pin, so the order matters):
  1. Generate key N+1. Ship its public key in the pinned set of app builds (with key N). Wait for adoption.
  2. Switch signing to N+1; move N to `previous-keys`.
  3. After 96 h (24 h validity + 72 h grace) every token signed by N is void; remove N from `previous-keys`. App builds
     may drop the pin later.
  A suspected compromise skips the wait: switch and remove at once; clients without the new pin lose offline gates
  until they update (fail closed). A remote-update mechanism with endorsements, like `listResourceSigningKeys` for pack
  keys, is possible later if app-release cadence makes this painful.

### D8. Config key now, KMS later
The B1.5 KMS port only wraps and unwraps data keys; it has no signing operation, and the cloud adapter has no SDK yet.
SD 18 accepts signing keys in the secret manager, which in deployment injects the env variables above, as B2.4 does for
the access-token key. Moving to KMS signing (e.g. a cloud KMS Ed25519 asymmetric key) means a second `JWSSigner`
implementation that calls the KMS; the token format, `kid` and the published set do not change. Cost: one remote call
per issued token, which suggests caching a token per user for a few minutes when that happens.

## Consequences / open
- **Revocation latency.** There is no revocation list: a token issued before a suspension, refund or plan downgrade
  keeps its offline gates up to 24 h online and 96 h offline. Accepted: the gates cover on-device features only, which
  cost no server money; every server feature is re-checked on each call. Clients refresh the token when online (and must
  drop it on 401/403), which shortens the window in practice.
- **Token outlives the subscription.** `exp` is always `iat + 24 h`, not capped at the plan's `effectiveUntil`
  (SD 15.5 fixes 24 h). Together with the D22 renewal lag, a lapsed PREMIUM user can keep offline models up to about
  24 h + 24 h + 72 h. Capping `exp` at the source's validity end is a possible later refinement.
- **Clock rollback** is mitigated only by the client's monotonic "now" (D4.5); a rooted device can still patch the app.
  The token raises the cost of piracy; it is not DRM.
- Every call signs a new token (no server state, no table, no migration). The endpoint is not rate limited; resolution
  is cached per user (30 s) and Ed25519 signing is cheap. Add a limit if abuse shows.
- `getEntitlements` (B4.5) must embed `EntitlementTokenService.issue(userId, deviceId)` and map its `ApiException`s the
  same way.
