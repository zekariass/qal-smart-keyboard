# infra/compose

Local dev stack (plan step F0.4, design v2.0 section 23 `local` environment).
All credentials are dev-only. Defaults work with no `.env`; copy `.env.example` to `.env` to override ports or credentials.

```bash
docker compose -f infra/compose/docker-compose.yml up -d      # start (waits for MinIO, creates buckets)
docker compose -f infra/compose/docker-compose.yml ps -a
docker compose -f infra/compose/docker-compose.yml down       # stop, keep data
docker compose -f infra/compose/docker-compose.yml down -v    # stop and RESET all data (pgdata, miniodata)
```

## Running the backend against this stack

`infra/scripts/run-backend-dev.sh` starts the Spring Boot backend (profile `dev`, DEV ONLY) against the Postgres here
(`localhost:5434`), starting that one service if it is down. Only `postgres` is needed for the backend; MinIO and WireMock are optional
(`provider=capture` replaces the SMS stub for sign-in). It generates a dev KEK in `infra/scripts/.dev-state/kek` (git-ignored) and
refuses to run with a `prod`/`production`/`staging` profile. Then, from `android/`:
`./gradlew :api:localStackTest -PlocalStack=true -PlocalStackUrl=http://localhost:8080` (reads the OTP from `/dev/sms/last`).
See `backend/README.md`, "Local development launcher".

After editing files under `wiremock/`, run `docker compose -f infra/compose/docker-compose.yml restart wiremock`.

## Services

| Service | Image | Host port | Purpose |
|---|---|---|---|
| postgres | `postgres:18` | 5434 | Application database (db/user `qal`, password `qal_dev_only`) |
| minio | `cgr.dev/chainguard/minio@sha256:5995...` (MinIO RELEASE.2026-11-16T00-11-40Z) | 9000 (S3 API), 9001 (console) | S3-compatible storage. Root user `qal_minio` / `qal_minio_dev_only` |
| minio-init | `cgr.dev/chainguard/minio-client@sha256:5eee...` | none | One-shot: creates buckets `qal-language-packs`, `qal-exports`, `qal-audio-temp`, then exits 0 |
| wiremock | `wiremock/wiremock:3.13.1` | 8089 | Mock SMS, AI and Chapa providers (mappings in `wiremock/mappings`, files in `wiremock/__files`) |

Note on MinIO images: the upstream `minio/minio` and `minio/mc` images are no longer published on Docker Hub or Quay, so the Chainguard builds are used, pinned by digest (the free Chainguard tier only offers `latest`). Revisit if the project moves to another S3-compatible store.

MinIO console: http://localhost:9001 (root credentials above). S3 endpoint: `http://localhost:9000`, path-style access.

## Mock endpoints

Base URL `http://localhost:8089`. Each group has its own prefix so an adapter can use it as a base URL.
Request bodies are not validated beyond what is noted; responses are fixed samples.

### SMS: `/sms`

```bash
# Send OTP (202, returns message_id, echoes "to")
curl -X POST localhost:8089/sms/v1/messages -H 'Content-Type: application/json' \
  -d '{"to":"+251911000001","text":"Your Qal code is 123456"}'

# Provider failure: magic number +251900000503 returns 503
curl -i -X POST localhost:8089/sms/v1/messages -H 'Content-Type: application/json' \
  -d '{"to":"+251900000503","text":"x"}'
```

### AI: `/ai`

```bash
curl -X POST localhost:8089/ai/v1/translate -H 'Content-Type: application/json' \
  -d '{"text":"Hello","source":"en","target":"am"}'          # Amharic (Ge'ez) translation
curl -X POST localhost:8089/ai/v1/llm/chat -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"Rewrite politely"}]}'   # LLM chat/transform
curl -X POST localhost:8089/ai/v1/detect-language -H 'Content-Type: application/json' \
  -d '{"text":"ሰላም"}'                                        # language "am", script "Ethi"
curl -X POST localhost:8089/ai/v1/stt -F audio=@sample.wav -F language=am   # transcript + duration_seconds
curl -X POST localhost:8089/ai/v1/tts -H 'Content-Type: application/json' \
  -d '{"text":"ሰላም","language":"am"}' -o out.wav            # tiny silent audio/wav
```

Scenario header `X-Mock-Scenario` (applies to any path under `/ai/`, for testing routing and fallback):

| Value | Behaviour |
|---|---|
| `slow` | Responds 200 after a fixed 15 s delay (use to trigger client timeouts) |
| `error` | Responds 503 with `{"error":{"code":"provider_unavailable",...}}` |
| (absent) | Normal sample response |

```bash
curl -i -X POST localhost:8089/ai/v1/translate -H 'X-Mock-Scenario: error' -H 'Content-Type: application/json' -d '{}'
```

### Payments (Chapa): `/chapa`

```bash
# Initialize: returns data.checkout_url that echoes tx_ref
curl -X POST localhost:8089/chapa/v1/transaction/initialize -H 'Content-Type: application/json' \
  -d '{"amount":"100","currency":"ETB","tx_ref":"qal-123","email":"test.user@example.com"}'

# Verify: tx_ref picks the outcome
curl localhost:8089/chapa/v1/transaction/verify/qal-123       # data.status = "success"
curl localhost:8089/chapa/v1/transaction/verify/fail-1        # data.status = "failed"   (prefix fail-)
curl localhost:8089/chapa/v1/transaction/verify/pending-1     # data.status = "pending"  (prefix pending-)
```

The Chapa stubs do not check the `Authorization: Bearer` header and do not send webhooks.

**Unverified:** the Chapa field names and response shapes (`message`, `status`, `data.checkout_url`, `data.status`, `charge`, `reference`, etc.), the `/v1/...` paths and the failed/pending representation are written from memory and are plausible, not confirmed. Check them against Chapa's current documentation before M4.2.
