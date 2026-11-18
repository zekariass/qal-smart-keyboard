# android/api

The typed API client (plan step A2.1). A plain Kotlin/JVM module (no Android dependency), so the whole
transport layer, including the interceptors and the authenticator, is tested on the JVM. **Why not `:data`:** `:data`
holds local state and its `NoNetworkTest` asserts it uses no network API; keeping networking in its own module keeps
that guarantee, lets `:ime` and `:app` depend on the client without pulling DataStore, and avoids Android lint and
API-level constraints for pure HTTP code. Nothing depends on `:api` yet; A2.2 (sign-in, Keystore token store) is the
first user.

## Generated client
OpenAPI Generator **7.14.0** (Gradle plugin, pinned in `gradle/libs.versions.toml`), generator `kotlin`, library
`jvm-okhttp4`, `kotlinx.serialization`, from `../contracts/openapi.yaml` into `build/generated/openapi` (task
`generateApiClient`, wired before `compileKotlin`; nothing generated is committed). Calls are blocking (OkHttp
`execute`); use `apiCall { ... }` which runs them on `Dispatchers.IO` and maps failures to `ApiError`. Generated
APIs are created over the shared client: `clients.api(::MeApi)`.

The generator does not choke on OpenAPI 3.1 as a whole (it logs a "3.1 is in beta" warning). Four constructs are handled
without touching the contract:

| Problem | Workaround |
|---|---|
| `const: true` on booleans (`DeletionRequestCreate.confirm`, `EmailRegisterRequest`, `WebhookAck.received`) generates an enum whose value is a String and does not compile | `normalizeContract` task drops `, const: true` in a build-time copy (`build/generated/openapi-input/openapi.yaml`) |
| `properties` + `oneOf: [{required: [a]}, {required: [b]}]` generates an empty data class (`InvitationCreate`, `StorePurchaseRequest`, `TranscriptionRequest`, `PartnerTranscriptionRequest`) | same task drops the `oneOf` (it only expresses "exactly one of", which the server validates) |
| free-form objects (`values`, `details`) become `Map<String, Any>`, which kotlinx.serialization cannot serialise (compiler back-end crash) | `typeMappings AnyType=JsonElement` |
| schema `ApiClient` collides with the generated `infrastructure.ApiClient` | `modelNameMappings ApiClient=PartnerApiClient` |

Other generator options: `dateLibrary=string` (timestamps stay ISO-8601 strings: `java.time` is missing below API 26 and
`minSdk` is 24, so no desugaring is required of the app; parse with a desugared or `ThreadLocal` formatter when a
timestamp is needed). The contract's optional `X-Client-Platform` / `X-App-Version` / `X-Client-Surface` parameters
appear on a few operations; leave them unset, the interceptor overwrites them.

## Wiring
```kotlin
val session = SessionManager(InMemoryTokenStore())              // Keystore store arrives with A2.2
val clients = ApiClients(
    TransportConfig(baseUrl = "https://api.example.com",
        client = ClientInfo(appVersion = BuildConfig.VERSION_NAME, surface = ClientSurface.COMPANION)),
    session,
)
val challenge = apiCall { clients.auth.startPhoneOtp(OtpStartRequest(phoneNumber = ...)) }
session.signedIn(apiCall { clients.auth.verifyPhoneOtp(...) })   // stores the pair, state -> SignedIn
```
The `:app` module does not declare the `INTERNET` permission yet (A2.2 adds it together with the first caller).

## Transport behaviour
- **`ClientHeadersInterceptor`:** `X-Client-Platform: ANDROID`, `X-App-Version`, `X-Client-Surface: KEYBOARD | COMPANION` on every request.
- **`BearerInterceptor`:** `Authorization: Bearer` from `SessionManager.tokens()` unless the request already has one.
- **`CorrelationIdInterceptor`:** reports `(method, status, X-Correlation-Id)` to a `CallLogger`. No URL (some paths carry tokens), headers or bodies.
- **`TokenAuthenticator`** (refresh rotation, ADR 0006 / ADR 0007 D8):
  - 401 `TOKEN_EXPIRED` with a bearer: take the session lock, refresh once with the stored refresh token (a separate client without bearer or authenticator, so no recursion), store the new pair atomically, retry **once**. Concurrent 401s queue on the lock; a request whose token is no longer the stored one just retries with the stored one, so N parallel expiries cause one refresh.
  - Only the newest pair is kept. A refresh whose response is lost leaves the old pair in place; the next refresh sends the old token again and the server's 30 s grace window accepts it.
  - `REFRESH_TOKEN_REUSED` / `GUEST_SESSION_INVALID` on a request, or 401/403 from the refresh call: tokens cleared, `SessionManager.state` becomes `SignedOut(reason)`, the call fails with `ApiError.SignedOut`. A guest session is never refreshed on `GUEST_SESSION_INVALID`; the app creates a new guest or signs in (and retries the same OTP code without the discarded guest token, per the contract).
  - A refresh that cannot complete (offline, 5xx, 429, 426) keeps the session and fails with the typed error (`Network`, `Http`, `RateLimited`, `UpdateRequired`).
  - Other 401s (`UNAUTHENTICATED`) are surfaced as they are.
- **Tokens never appear in logs or exception messages** (`TokenPair.toString()` is redacted; `ApiError` messages are status plus code only).

## Errors (`ApiError`, sealed)
`Http(details)` carries `code`, `status`, `title`, `correlationId`, `fieldErrors` and every extension member
(`extensions`); subclasses `UpdateRequired` (426; `minSupportedAppVersion`), `RateLimited` (429; `retryAfterSeconds` from
`Retry-After`) and `SignedOut(reason)`. `Timeout`, `Network` and `Unexpected` (unreadable success body, redirect) cover
the rest. A non-Problem error body (for example an edge HTML page) becomes `Http` with code `UNKNOWN`, the HTTP status
and the `X-Correlation-Id` header. Clients switch on `code`, never on `title`. `apiResult { }` returns a `Result`
instead of throwing.

## Transport security
ADR 0016: no API certificate pinning (SD 18), system trust anchors only, no cleartext except the debug local stack
(Network Security Config in `:app`). `TransportConfig.certificatePins` is an off-by-default hook; `http://` base URLs
are refused outside `localhost`, `127.0.0.1` and `10.0.2.2`.

## Tests
- `./gradlew :api:test` (also run by the CI command through the `testDebugUnitTest` alias): OkHttp `MockWebServer` tests for the interceptors, refresh rotation (two or four concurrent 401s -> one refresh; reuse -> signed out; lost refresh response; refresh 5xx/offline keeps the session; retry once), 426, 429, field errors, non-Problem bodies, timeouts and the pinning/cleartext config.
- **Local stack (optional, skipped unless `-PlocalStack=true`):**
  ```sh
  # 1. backend + compose Postgres, dev profile with the SMS capture (infra/scripts/run-backend-dev.sh, DEV ONLY)
  infra/scripts/run-backend-dev.sh
  # 2. in another shell
  cd android && ./gradlew :api:localStackTest -PlocalStack=true            # http://localhost:8080
  ./gradlew :api:localStackTest -PlocalStack=true -PlocalStackUrl=http://localhost:8080 [-PlocalStackOtpCode=123456]
  ```
  It calls `GET /api/v1/config`, starts an OTP challenge for a fixed test number, verifies a wrong code (expects the `OTP_INVALID` Problem), reads the real code from the backend's dev-only `GET /dev/sms/last` (or uses `-PlocalStackOtpCode`), completes the sign-in, calls `GET /me/preferences`, refreshes the session and calls it again. From an Android emulator the host is `http://10.0.2.2:8080` (allowed by the debug Network Security Config).
