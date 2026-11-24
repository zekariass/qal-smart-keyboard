# :ai-client (A2.5)

Network side of the keyboard's AI toolbar. Implements `:ime`'s `AiActions` port (`ServerAiActions`) over `:api`'s
OkHttp stack (bearer, client headers with `X-Client-Surface: KEYBOARD`, refresh authenticator), with the contract's
8 s client timeout. `:app` wires it (`AiGraph`, `QalApplication`).

- `POST /ai/translate`, `POST /ai/transform` (GRAMMAR_CORRECTION, REWRITE — NATURAL and the SHORTEN / EXPAND / CLARIFY /
  SIMPLIFY modes —, TONE_ADJUST, FORMAT_TEXT), `POST /ai/replies` (with the optional preset `intent`), `POST /ai/drafts`
  (`documentType`: e-mail, job application, CV, cover letter, business message); `Idempotency-Key` = the
  request id; source language left to server detection. `saveToHistory: true` is added only while the `saveToHistory`
  supplier of `ServerAiActions` returns true (A2.6: the person turned AI history on; default never, the member is then absent).
  Errors map to `AiFailure` by Problem `code`
  (`QUOTA_EXCEEDED` with `resetAt`/`upgradeOptions`, `FEATURE_NOT_ENTITLED`, `ON_DEVICE_ONLY_PREFERRED`,
  `CONSENT_REQUIRED`, `OUTPUT_GUARDRAIL_FAILED`, `REQUEST_IN_PROGRESS`, 426, sign-in codes, ...).
- Offline: no call is made (`AndroidConnectivity`, needs ACCESS_NETWORK_STATE, merged into `:app`).
- **Entitlement token (ADR 0013):** `EntitlementManager` fetches `GET /me/entitlements/token` in the background,
  `EntitlementTokenVerifier` checks it on device (EdDSA only, `typ qal-ent+jwt`, no crit/jwk/jku/x5*, `kid` in
  the keys **pinned at build time**, the Ed25519 signature through `keyboard-core`'s `verifyEd25519` (strict; no
  BouncyCastle, the JDK's Ed25519 exists from Android 13 only), iss/aud/ver/sub/deviceId/time windows). JVM tests
  load the host build of the core through JNA (`:core-bindings:cargoHostBuild`), like `:ime`. `OfflineGates` applies D4.5: before `exp` gates open; in the 72 h grace
  only offline; then void. "Now" never goes back (newest `iat`/server `Date` seen is stored). A 401/403 drops the
  cached token. The token only opens offline gates (`ON_DEVICE_AI_MODELS`); server AI is decided by the server per call.
- **Pinned keys:** build with `-Pqal.entitlementJwksFile=<jwks.json>` (the format of `/.well-known/jwks.json`) or
  `qal.entitlementJwksFile` in `local.properties`. Without it the set is empty and no gate ever opens.
- Plan hints (`3 of 10 left today`) come from `GET /me/entitlements` (cached 5 min) and each answer's `usage`.
- **No network on keyboard show (ADR 0007 D40).** `AiActions.refresh()` (plan state and token) runs on a user
  action in the toolbar (`AiToolbarController.onToolbarOpened`, at most once per 5 min) and from `:app`'s
  `SyncWorker` (`ServerAiActions.refreshNow()`, blocking), never because the keyboard appeared. Tests:
  `ToolbarFollowUpsTest` (50 show/hide cycles: zero requests; opening the toolbar: at most one refresh in the window).
- **Last-used destination per app (D40).** `LastDestinationStore`: device-local, in the encrypted `ai-last-dest`
  file; the app key is `hex(HMAC-SHA256(deviceKey, packageName))` (A2.4's `RuleKeyStore.hash`) with a random
  per-device 32-byte key in the same file (A2.4's account key needs the network), so no package name is stored in
  plain text; capped at 200 apps; never synced. Set when the user picks a destination chip.
  **Precedence of the preselected destination** (`ServerAiActions.defaultDestination`): 1. the app's enabled A2.4 rule
  (its profile if it still exists, else its language); 2. the last-used destination for this app, if it still exists;
  3. the first profile; 4. English.
- Destinations: A2.4's `DestinationStore` (profiles, per-app rules) snapshotted off the main thread, plus plain languages.
