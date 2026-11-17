# ADR 0008 — Attestation nonces, verifier ports and guest accounts

**Status:** Accepted · **Date:** 2026-11-17 · **Plan steps:** B2.3 (with B2.5 for the device record) · **Spec:** SD §9.1,
§9.4, §15.5; contract `/auth/attestation-nonces`, `/auth/guest`, `/devices/{deviceId}/attestations`; V1 `users`,
`user_devices`, `subscription_plans`, `plan_entitlements`; V5

## Context
Offline typing needs no account. A guest account exists only for the small AI trial and only after device attestation
(Play Integrity on Android, App Attest on iOS) over a server nonce, so attestation tokens cannot be replayed (SD 9.4,
D31). No real verifier can be built before Google and Apple credentials exist, so the ports must be real and the
adapters mockable, and production must fail closed until real adapters exist.

## Decision

### Nonces (V5 `attestation_nonces`)
- 256 bits from `SecureRandom`, base64url (43 characters, inside the contract's 16-128). Valid 5 minutes (the contract's
  value; the setting cannot exceed it). Stored as SHA-256 only: nonces are high-entropy, so an unkeyed hash is enough
  and a table dump yields nothing usable.
- Optional bindings, because the contract's request has no body: the caller (an optional bearer token) and the
  `X-Client-Platform` header. A bound nonce cannot be spent by another user (or anonymously) or for another platform. A
  nonce cannot be bound to the installation id; the guest request names it later.
- Single use by one atomic `UPDATE ... WHERE consumed_at IS NULL AND expires_at > now AND (bindings match)` in its own
  transaction (`REQUIRES_NEW`) that commits whatever the verdict says: replaying a verdict fails, and a failed verdict
  burns its nonce (the client asks for a new one). A mismatching binding does not burn the nonce.
- Rate limit per client IP via `platform.ratelimit` (120/h, PROVISIONAL).
- No purge job yet (retention scheduler, B6.3).

### Verifier ports
- `PlayIntegrityVerifier` and `AppAttestVerifier` are ports with `verify(evidence, expectedNonce)` returning VALID /
  REJECTED / UNAVAILABLE. The verdict must cover the expected nonce (Play Integrity `requestDetails.nonce`, App Attest
  client data hash): the service passes the nonce it just consumed, so a verdict for another nonce is rejected.
- `AttestationVerifier` picks the adapter by platform; a platform without adapter is UNAVAILABLE. An adapter that throws
  is UNAVAILABLE (only the exception class is logged). The call runs outside any transaction (SD 8.3).
- `identity.AttestationChecker` (root of the identity module) is the port the `devices` module uses for
  `POST /devices/{id}/attestations`; devices depends on identity, never the reverse (ADR 0006, B2.5).
- **Mock adapters** accept a published token format (`MockAttestationTokens`) and reject everything else. They exist only
  with `qal.identity.attestation.mock.enabled=true`, which startup refuses under `prod`/`production`/`staging`.
- **Production adapters are not implemented. Fail closed:** no adapter, so UNAVAILABLE, so no guest and no attested device.

### Guest
- Order: platform (native only) → rate limits (per IP 10/h, per installation 5/h) → attestation → transaction: claim the
  installation, create the user, register the device with the VALID verdict, issue the session through `SessionIssuer`.
- **All attestation failures are 403 `GUEST_NOT_ALLOWED`**, one generic body. This is the plan's "done when". The
  contract lists `GUEST_NOT_ALLOWED` and `ATTESTATION_REQUIRED` under 403; a single code gives an attacker no oracle on
  nonces or installation ids. `ATTESTATION_REQUIRED` / `ATTESTATION_FAILED` stay reserved for the AI endpoints (guest AI
  needs a VALID device, D31). A missing `attestation` member is treated as a failed attestation (403) although the
  schema marks it required, so "a guest without a valid attestation" is one case; wrongly shaped values (bad UUID,
  unknown provider, oversize) are 400.
- **One guest per installation, no resume.** The contract says "creates (or resumes)". The installation id is random
  but sent by the client and not bound to the attestation, so resuming on the id would let anyone who learns it take over
  that guest. A second request is refused (403); a client that lost its tokens and wants a guest again uses a fresh
  installation id. Revisit when the attestation can be bound to the installation (App Attest key id, Play Integrity
  request hash).
- **Plan:** `GUEST` is the effective plan of a GUEST account with no subscription (SD 15.5). V1 seeds the `GUEST` plan
  (rank -10, ACTIVE) and its entitlements, so no `user_subscriptions` row is written. The entitlement resolver is B4.1.
- The GUEST user has no identity rows (V1 `ck_users_guest_has_no_identity`), no age confirmation (no personal data, no
  purchases) and is `ACTIVE`. Guest → registered merge is B2.6.

## Consequences
- V5 adds `attestation_nonces` and `ix_user_devices_installation` (guest-per-installation lookup; V1 indexes only
  `(user_id, installation_id)`). The lookup joins `users` from the devices module, read-only, to classify the owner.
- Rate limits are in memory per instance (ADR 0006): move to the shared store before running several instances.
- Until real adapters exist the app cannot create guests in production; clients must handle 403 `GUEST_NOT_ALLOWED`
  (phone sign-in remains).

## Open
- Real Play Integrity / App Attest adapters, and App Attest assertion handling (the contract's evidence is one shape
  for attestation and assertion).
- Resume of a guest session, once the attestation can be bound to the installation.
- Purge of `attestation_nonces` (B6.3). `validUntil` of a verdict is returned but not stored (no column).
