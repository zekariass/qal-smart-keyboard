# :ai-client (A2.5)

Network side of the keyboard's AI toolbar. Implements `:ime`'s `AiActions` port (`ServerAiActions`) over `:api`'s
OkHttp stack (bearer, client headers with `X-Client-Surface: KEYBOARD`, refresh authenticator), with the contract's
8 s client timeout. `:app` wires it (`AiGraph`, `QalApplication`).

- `POST /ai/translate`, `POST /ai/transform` (GRAMMAR_CORRECTION, REWRITE, TONE_ADJUST); `Idempotency-Key` = the
  request id; source language left to server detection. Errors map to `AiFailure` by Problem `code`
  (`QUOTA_EXCEEDED` with `resetAt`/`upgradeOptions`, `FEATURE_NOT_ENTITLED`, `ON_DEVICE_ONLY_PREFERRED`,
  `CONSENT_REQUIRED`, `OUTPUT_GUARDRAIL_FAILED`, `REQUEST_IN_PROGRESS`, 426, sign-in codes, ...).
- Offline: no call is made (`AndroidConnectivity`, needs ACCESS_NETWORK_STATE, merged into `:app`).
- **Entitlement token (ADR 0013):** `EntitlementManager` fetches `GET /me/entitlements/token` in the background,
  `EntitlementTokenVerifier` checks it on device (EdDSA only, `typ qal-ent+jwt`, no crit/jwk/jku/x5*, `kid` in
  the keys **pinned at build time**, Ed25519 via BouncyCastle because the JDK's exists from Android 13 only,
  iss/aud/ver/sub/deviceId/time windows). `OfflineGates` applies D4.5: before `exp` gates open; in the 72 h grace
  only offline; then void. "Now" never goes back (newest `iat`/server `Date` seen is stored). A 401/403 drops the
  cached token. The token only opens offline gates (`ON_DEVICE_AI_MODELS`); server AI is decided by the server per call.
- **Pinned keys:** build with `-Pqal.entitlementJwksFile=<jwks.json>` (the format of `/.well-known/jwks.json`) or
  `qal.entitlementJwksFile` in `local.properties`. Without it the set is empty and no gate ever opens.
- Plan hints (`3 of 10 left today`) come from `GET /me/entitlements` (cached 5 min) and each answer's `usage`.
- Destinations: A2.4's `DestinationStore` (profiles, per-app rules) snapshotted off the main thread, plus plain languages.
