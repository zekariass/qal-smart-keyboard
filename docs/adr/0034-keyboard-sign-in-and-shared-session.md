# ADR 0034 — Keyboard sign-in and one shared session

**Status:** Accepted · **Date:** 2026-11-19 · **Builds on:** ADR 0006 (session tokens), ADR 0007 (D8 refresh rotation,
D40 toolbar and plan state), ADR 0009 (Google sign-in), ADR 0010 (guest merge, B2.6), ADR 0013 (entitlement token),
ADR 0031 (AI panels) · **Contract:** none (no API change; the backend is unchanged)

## Context
On a real phone against the development server the owner reported:

1. Signing in from the keyboard (gear or "Sign in to use AI" → the app's sign-in screen) failed with "Google sign-in was not
   accepted" (`SignInModel`: Google + HTTP 401 → `GoogleRejected`).
2. After signing in in the companion app, the keyboard still behaved as signed out.

The app and the keyboard run in one process (no `android:process` in either manifest) and already share one
`SessionManager` (`AuthGraph.session`; `AiGraph` reads it through `SessionAccessInfo`). The causes were elsewhere:

- **Stale bearer on sign-in.** `BearerInterceptor` adds the stored access token to every request, sign-in calls included.
  `/auth/otp/*` and `/auth/oauth/{provider}` are "optional bearer" operations: a presented token must be valid, so a token
  the server cannot verify is a 401 before the controller runs (`ApiPaths.OPTIONAL_BEARER`, `ApiAuthenticationEntryPoint`:
  `UNAUTHENTICATED` with `WWW-Authenticate: Bearer error="invalid_token"`). The development profile signs access tokens
  with an ephemeral key (`allow-ephemeral: true` in `application-dev.yaml`), so every server restart leaves every stored
  access token unverifiable. `TokenAuthenticator` only refreshed on `TOKEN_EXPIRED` and ignored `UNAUTHENTICATED`, and
  `AuthRepository.linking` only retried on `GUEST_SESSION_INVALID`, so the sign-in failed with a 401 and the UI said
  "Google sign-in was not accepted". The same dead token also made every keyboard AI call answer "sign in required"
  while the app still showed "signed in": the session was never cleared ("half signed in"). A registered user's token was
  also sent on a fresh sign-in, which links nothing and only adds a way to fail.
- **The keyboard never heard of session changes.** `ServerAiActions.availability` reads the session live, but its
  listeners only fired on connectivity and entitlement changes. The toolbar renders on listener events, so after a
  sign-in in the app the bar kept showing "Sign in to use AI"; the cached plan state and the verified entitlement token
  of the previous account (or none) stayed until a throttled refresh, and premium themes were judged on them. The AI
  panels kept "not on your plan" locks learned for another account. `SessionManager.state` is a `StateFlow`, which
  conflates `SignedIn → SignedIn`, so an account switch was not observable at all.
- **The gear's settings did not lead with the account.** The keyboard-opened settings had the account card below the setup
  and language cards, without saying who is signed in, and the bar's `SIGN_IN` link opened the sign-in pane without the
  keyboard's "Back to typing" (and, when already signed in, the plain settings).

## Decisions

### 1. Bearer policy of sign-in calls
- `otp/start`, `otp/verify` and `oauth/GOOGLE` carry a bearer **only** when the stored session is a guest's (stored kind
  `GUEST` and tokens present): that is the B2.6 link. Every other stored session — a registered account, or tokens whose kind
  is unknown — is **never** presented. Those calls go through `ApiClients.anonymousAuth` (the auth API over the bearer-free,
  authenticator-free client that already served `/auth/refresh`). A successful sign-in replaces the old pair locally
  (`session.signedIn`), which is the local sign-out of the previous account; nothing is revoked on the server for it (its
  refresh token expires on its own; signing in as someone else from the account card goes through "Sign out" first, which
  does revoke).
- `/auth/attestation-nonces` and `/auth/guest` (guest creation) also go out without a bearer.
- If the guest bearer is refused and the session ends (`ApiError.SignedOut`: `GUEST_SESSION_INVALID`, a refresh refused
  after an `invalid_token`, or reuse detection), the dead guest session is cleared (tokens, kind, label) and the **same**
  call is repeated **exactly once** over `anonymousAuth`. The retry has no bearer, so the authenticator cannot act on it:
  there is no loop. A refused Google ID token is `UNAUTHENTICATED` **without** `invalid_token`; it is not about the session,
  so a live guest keeps its session and the user sees "not accepted" as before.
- **Trade-off vs ADR 0010.** ADR 0010 links a guest only while its bearer is valid; a guest whose token can no longer link
  gets a 401 and nothing is written. Retrying without the bearer means the dead guest's server-side data is not linked —
  but it was unreachable for this device anyway (its tokens are gone), and the alternative is a user who cannot sign in at
  all. A guest whose *access* token is merely unverifiable (key rotation, dev restart) but whose refresh token is still good
  is **not** lost: see §2, the refresh happens first and the sign-in then links as usual.

### 2. Refused access tokens and ended sessions
`TokenAuthenticator` now also handles 401 `UNAUTHENTICATED` **with** `WWW-Authenticate: Bearer error="invalid_token"`
(RFC 6750: the bearer itself was refused) on a request that carried one, exactly like `TOKEN_EXPIRED`: one refresh under the
session lock, then one retry. If the refresh is refused (401/403) the session is cleared (`REFRESH_REJECTED` /
`ACCOUNT_BLOCKED` / `REFRESH_TOKEN_REUSED`, as before). If the retry with the newest token is refused with `invalid_token`
again (e.g. a closed account) the session is cleared with the new reason `ACCESS_TOKEN_REJECTED`. Either way the UI shows
"signed out" instead of a session no call can use. A session-ending answer about a token the session no longer holds (a
sign-in or a rotation replaced it while the request was in flight — e.g. a keyboard request still carrying the guest token
that a sign-in just merged) never ends the new session; the request is retried once with the current token.

### 3. One live session for the app and the keyboard
- `SessionManager.addListener` is a plain callback after every `signedIn`/`signedOut`, account switches and token
  rotations included (the `state` flow stays for coroutine code). It runs on the thread that changed the session (maybe an
  OkHttp thread inside the refresh lock) and must return at once.
- `SessionInfo.addListener` exposes it to `:ai-client`. `ServerAiActions` registers one listener and hands each event to its
  single background executor. There it compares the account (`sub` of the access token, or "signed out") with the one its
  cached plan state belongs to:
  - **Same account** (token rotation): the availability listeners fire; nothing is fetched.
  - **Different account** (sign-in, sign-out, guest → account, another account): `accountGeneration` increments,
    `EntitlementManager.clear()` drops the previous plan hints and the cached entitlement token (memory and disk), the
    listeners fire, and, if someone is signed in, `EntitlementManager.refresh(force = true)` fetches the new account's
    plan state and offline token.
- What follows from the listeners, with no restart: the toolbar re-renders (`SIGNED_OUT` ↔ available; the "Sign in to use
  AI" link disappears or appears), an open AI panel re-renders, premium themes are re-resolved (`ServerThemeAccess` listens
  to the same events), and `AiAvailability.accountGeneration` scopes the panels' "not on your plan" locks (Assist, Email)
  to the account that got them.
- **No polling and no network on the keyboard's start-up path.** Building the AI client still only reads the disk. Network
  happens only after an account change, which is a user action in the app or in the keyboard's sign-in screen; a token
  rotation never fetches.

### 4. Signing in from the keyboard
- The keyboard's gear (`AiLink.SETTINGS`) opens the settings with "Back to typing" (as before) and the **account card
  first**, right under the title: signed out → "Sign in" (the full sign-in pane: phone and Google; the e-mail option
  arrives with the e-mail sign-in work); guest → "Sign in"; signed in → "Signed in as …" with the masked phone number
  (`phoneNumberMasked`) or a masked e-mail (`z•••@example.com`), and "Sign out". The label is stored encrypted next to the
  tokens (`AccountLabelStore`, `noBackupFilesDir/auth`) and cleared on sign-out.
- The bar's "Sign in to use AI" (`AiLink.SIGN_IN`) opens the sign-in pane **over those keyboard settings**: "Done" (or Back)
  leaves the pane for the settings, which keep "Back to typing"; that finishes the task (`finishAndRemoveTask`), so the user
  lands back in the app they were typing in. When an account is already signed in, `SIGN_IN` opens the keyboard settings
  directly (they say who).
- Everything stays in the one `MainActivity` task; Google sign-in therefore uses the activity's context
  (`CredentialManagerGoogleSource(this@MainActivity, …)`), the only place it is created. The keyboard service never starts a
  Credential Manager flow itself.
- Kept from before: `CredentialManagerGoogleSource` falls back from the bottom sheet (`GetGoogleIdOption`) to the explicit
  "Sign in with Google" flow (`GetSignInWithGoogleOption`) when the sheet finds no usable account (`NoCredentialException`),
  verified on a Samsung with several Google accounts.

## Alternatives considered
- **Backend ignores an invalid bearer on sign-in endpoints** (treat them as anonymous). Rejected for now: `OPTIONAL_BEARER`
  deliberately refuses an invalid token "so a client never silently loses its identity, e.g. for the guest merge on verify";
  a server that silently drops it would sign a guest in without linking and without telling the client. The client fix
  keeps that contract and handles both the dev key and real revocations. If the backend ever wants to be more forgiving,
  the client keeps working.
- **Polling the session from the keyboard** or re-reading it on every `onStartInputView`: needs a timer or still misses a
  sign-in while the keyboard is visible; the listener is exact and free.
- **Sending a registered token on sign-in "just in case"**: it links nothing (the server only links guests) and turns any
  stale token into a failed sign-in.

## Consequences
- Signing in works after a development server restart, after a key rotation, and with any leftover tokens.
- Signing in or out in the app updates the keyboard immediately, and vice versa (the keyboard's sign-in screen is the app's).
- A dead session is shown as signed out everywhere instead of "signed in" in the app and "sign in required" in the keyboard.
- New user-visible string: `acct_signed_in_as` (English; DRAFT am/ti/om under N9).
- Tests: `:api` `ApiClientTest` (refused tokens refresh/sign out without looping, a replaced token never ends the new
  session, listeners see account switches, `anonymousAuth` sends no bearer), `:app` `SignInModelTest` (bearer policy: no
  session / guest / registered / kind-less / stale guest refreshed and linked / dead guest cleared and retried once / no loop
  / refused Google token keeps a live guest; the account label), `KeyboardAccountTest` and `DeepLinksTest` (account first
  from the gear, "Signed in as", the `SIGN_IN` path), `:ai-client` `SharedSessionTest` (availability, plan state, token and
  themes follow sign-in, rotation, account switch and sign-out; nothing fetched at start-up), `:ime` panel locks per account.
