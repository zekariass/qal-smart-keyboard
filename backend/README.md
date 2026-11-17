# backend

Spring Boot 4 modular monolith (Java 25). Modules per SD section 24. Flyway V1__baseline.sql in src/main/resources/db/migration. Plan steps B*.x, M4.x.

See docs/Ethiopian_AI_Keyboard_Implementation_Plan_v2_0.md for the steps that populate this folder.

## Platform conventions (B1.3)

- **Errors:** every error is an RFC 9457 `application/problem+json` body matching `Problem` in `contracts/openapi.yaml`.
  Modules throw `com.qalsmartkeyboard.platform.ApiException(ApiErrorCode, HttpStatus, detail)`; never put user text in
  `detail` or extension members. Bean Validation, malformed JSON, 404, 405, 415 and unexpected exceptions are mapped
  by `platform.web.ProblemExceptionHandler`. `ApiErrorCode` mirrors the code list in the contract.
- **Correlation ID:** `platform.web.CorrelationIdFilter` (highest precedence) keeps a well-formed inbound
  `X-Correlation-Id` (1-100 chars of `A-Za-z0-9._:-`) or generates a UUID, sets MDC key `correlationId`, echoes the header on every
  response and uses it as `correlationId` in Problems. Read it with `CorrelationId.current()`.

## User-data encryption (B1.5, ADR 0005)

User content stored server-side (`*_ciphertext` columns) is sealed with
`com.qalsmartkeyboard.platform.crypto.UserDataEncryption`: AES-256-GCM under a per-user data key that is kept KMS-wrapped
in `user_data_keys`, created on first use, and bound to the user, key version and storage location.

```java
UUID id = UUID.randomUUID(); // generate the row id first: it is part of the associated data
EncryptionContext ctx = EncryptionContext.of("user_destination_profiles", "label_ciphertext", id);
EncryptedValue sealed = encryption.encryptString(userId, ctx, label);
// INSERT ... (id, label_ciphertext, key_version) VALUES (id, sealed.ciphertext(), sealed.keyVersion())
String label = encryption.decryptString(userId, ctx, row.labelCiphertext());
```

- `rotateKey(userId)` starts a new key version. Old values still decrypt, and existing rows are not re-encrypted.
- `cryptoShred(userId)` (privacy, account deletion) destroys all of the user's keys. It is idempotent and joins the
  caller's transaction. Afterwards decrypt, encrypt and rotate throw `CryptoShreddedException`, and a shredded user
  never gets a new key.
- Failures are `CiphertextRejectedException` (tampered, moved, malformed) and `KeyManagementException`. Never put
  their messages in a client-visible Problem.
- **KMS configuration (required, there is no default):** `qal.crypto.kms.provider` / env `QAL_KMS_PROVIDER`.
  - Local development: `QAL_KMS_PROVIDER=local` and `QAL_LOCAL_KMS_KEK=$(openssl rand -base64 32)`. Keep the same
    KEK across restarts, or the data you stored cannot be read. The local KMS refuses to start under the
    `prod`/`production`/`staging` profiles.
  - Tests: `src/test/resources/config/application.yaml` turns on the local KMS with an ephemeral per-JVM KEK
    (`qal.crypto.local-kms.allow-ephemeral-kek=true`, dev/test only) for every `@SpringBootTest`.
  - Cloud: `provider=cloud` and `qal.crypto.cloud-kms.key-name`, plus a `CloudKmsClient` bean. None exists yet, so
    startup fails (ADR 0005 shows how GCP or AWS KMS plugs in).
- Cache of unwrapped keys: `qal.crypto.dek-cache.max-entries` (10000) and `qal.crypto.dek-cache.ttl` (5m).

## Outbox (B1.4)

Modules publish domain events through the `platform` named interface `outbox`
(`com.qalsmartkeyboard.platform.outbox`); persistence, dispatcher and scheduler wiring are package-private.

```java
@Transactional
void activate(...) {
    // domain change ...
    outboxPublisher.publish("subscription", subscriptionId, "subscription.activated", Map.of("subscriptionId", subscriptionId));
}
```

- `OutboxPublisher.publish` throws `IllegalStateException` outside a transaction, so the event exists iff the domain change commits.
  Ids are UUIDv7. **Payloads and `aggregateId` carry ids/metadata only, never user text** (keystrokes, drafts, transcripts, prompts, names).
- Consume by declaring an `OutboxEventHandler` bean (`consumerName()`, `eventType()`, `handle(event)`).
- Dispatch: a db-scheduler recurring task (`outbox-dispatcher`, core library; its Spring Boot starter targets Boot 3) claims due rows with
  `FOR UPDATE SKIP LOCKED`, marks them PROCESSING with a lease, runs the handlers, then PUBLISHED. Safe on several instances.
  There is no ordering guarantee between events.
- Failure: `attempts` increments on every claim (a crash counts), the error class name (never the message) is stored in `last_error`,
  `available_at` moves by exponential backoff with equal jitter (`base-backoff` doubling, capped by `max-backoff`), and the event is DEAD at `max_attempts` (default 10).
  A PROCESSING row whose `locked_until` has passed is reclaimed.
- **Idempotent consumers:** delivery is at-least-once, but before calling a handler the dispatcher inserts `(consumerName, eventId)` into
  `processed_events` in the same transaction as the handler's writes; a redelivery is skipped and a handler failure rolls both back.
  Writes through the Spring-managed transaction are therefore applied once per event id. External side effects must use `event.id()` as an
  idempotency key. Keep `consumerName` stable. `processed_events` purge belongs to B6.3.
- Config (`qal.outbox.*`): `dispatcher-enabled` (true), `poll-interval` (1s), `batch-size` (20), `lease` (60s; must exceed handler time), `base-backoff` (5s), `max-backoff` (1h).
- Metrics: `qal.outbox.attempts{outcome=published|failed|dead|lease_reclaimed}`, `qal.outbox.duplicates.skipped`, gauges `qal.outbox.pending`, `qal.outbox.dead`.
  Logs carry event id and type only.
- Schema: `V2__outbox_scheduler.sql` adds db-scheduler's `scheduled_tasks` and `processed_events`.

## Configuration (B1.6)

Module `configuration`: `GET /api/v1/config` and the app-version gate. Both read an in-memory snapshot of
`feature_flags` and `app_configurations` that is reloaded after `qal.config.cache-ttl` (default 30s). Edits to those
tables become visible within the TTL; explicit invalidation comes with the admin module. If a reload fails the previous
snapshot keeps being served.

- **Values:** active `app_configurations` rows of scope `ALL`, overridden per key by the caller's platform row
  (`BACKEND` rows are never served). Reserved keys `minSupportedAppVersion` and `recommendedAppVersion` (JSON strings) fill the
  top-level fields; no `minSupportedAppVersion` row means `0.0.0` (nothing is rejected). `configVersion` is the highest
  `config_version` among the applied rows (0 if none).
- **Flags:** every flag is listed; it is `true` only if enabled, its `platform_scope` is `ALL` or the caller's platform, the
  caller's version meets `min_app_version` (unknown version: not met), `targeting` is empty and the rollout admits the caller.
  Targeted flags (markets/plans/channels) stay `false` until an authenticated context exists (B2.4). Partial rollout
  (`rollout_percentage` 1-99) is deterministic: `SHA-256(flagCode + ":" + subjectKey)` first 4 bytes mod 100 below the percentage.
  The subject key (user id, else device id) is not available yet, so partial rollouts are `false` for now.
- **Version gate (426):** an MVC interceptor on `/api/v1/**` compares `X-App-Version` with the platform's minimum and throws
  `APP_VERSION_UNSUPPORTED` (426, extension member `minSupportedAppVersion`). Exempt: `/api/v1/config` (old clients must be able
  to learn they have to update), `/api/v1/billing/webhooks/**` and `/api/v1/billing/store-notifications/**` (provider/store
  callbacks). `/partner/v1/**` and `/actuator/**` are not covered. The contract makes all client headers optional, so:
  no `X-App-Version` means not enforced; a malformed `X-App-Version` or an `X-Client-Platform` other than `ANDROID|IOS|WEB`
  is 400 `VALIDATION_FAILED`; no platform means the `ALL` minimum applies; an unreadable configuration fails open (logged).
  The headers are client-asserted: this is a compatibility gate, not a security control.
- **Version comparison** is SemVer precedence (`1.10.0 > 1.9.0`); `2.1` means `2.1.0`; a pre-release is below its release
  (`2.0.0-beta.1 < 2.0.0`); build metadata (`+...`) is ignored. See `AppVersion`.
- Until B2.4 adds a `SecurityFilterChain`, Spring Boot's default security answers 401 before any of this runs for anonymous
  callers (the contract allows anonymous `/config`); the tests use a permit-all chain.
- `/config` never fails on bad client headers (the contract declares no 400 for it): they count as unknown.

## Contract tests

Controller tests validate every response against `contracts/openapi.yaml` (OpenAPI 3.1) by adding one annotation:

```java
@WebMvcTest(MyController.class)
@ContractValidated   // com.qalsmartkeyboard.testsupport.contract
class MyControllerTest { ... }
```

An undeclared path/status, wrong content type, missing header or body-schema violation fails the `perform(...)` call.
`OpenApiContractTest` and `ProblemDetailsContractTest` show the validator failing on violating responses.
Run only these with `./mvnw -Djava.version=21 test -Dtest='OpenApiContractTest,ProblemDetailsContractTest'`.
Never edit `contracts/openapi.yaml` directly: change the docs copy and run `contracts/sync.sh`.

## Generated client types (CI)

CI generates TypeScript types from the contract with `openapi-typescript` (pinned) and type-checks them with `tsc`,
so a contract change that breaks code generation fails the build. Nothing generated is committed. Locally (Node 22+):

```sh
backend/scripts/gen-client-types.sh            # writes backend/target/generated-client-types/api.d.ts
```

Android and iOS client generation are added with their own steps (A2.1, I-steps).

## Build

`./mvnw verify` (CI: Java 25; locally `-Djava.version=21` on JDK 21). `QalSmartKeyboardApplicationTests` needs PostgreSQL at
`DB_URL` (default `localhost:5434`); `*IT` classes use Testcontainers and need Docker. Running the application
(`./mvnw spring-boot:run`) also needs the KMS settings above.
