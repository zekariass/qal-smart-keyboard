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
