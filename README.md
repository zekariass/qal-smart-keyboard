# Qal Smart Keyboard

Offline-first Android/iOS keyboard for Amharic, Tigrinya, Afaan Oromoo and English with an AI backend.

Start with `docs/Ethiopian_AI_Keyboard_Implementation_Plan_v2_0.md`. Design: `docs/Ethiopian_AI_Keyboard_System_Design_v2_0.md`.

| Folder | Purpose |
|---|---|
| core | Rust keyboard-core shared by both apps |
| android | Gradle: app (companion, assembles APK), ime, data, billing modules |
| ios | Keyboard extension, companion app, shared code |
| backend | Spring Boot modular monolith |
| admin-web | Next.js admin console |
| language-data | Rules, wordlists, pack build pipeline |
| contracts | OpenAPI contract (synced from docs) |
| infra | Local compose and deployment config |
| docs | Design docs, ADRs, archive |

## Prerequisites

| Tool | Version | Used by |
|---|---|---|
| JDK | 25 (backend), 21+ (Gradle) | backend, android |
| Android Studio / SDK | API 36 | android |
| Docker Desktop | current | local database (infra/compose) |
| Rust + cargo-ndk, uniffi-bindgen | stable | core (from step C1.1) |
| Xcode on macOS | current | ios (from step I5.1) |
| Node.js | LTS | admin-web, OpenAPI lint |

## Build and run

```bash
# Local database (PostgreSQL 18 on host port 5434)
docker compose -f infra/compose/docker-compose.yml up -d

# Backend: Flyway applies V1__baseline.sql on startup
cd backend && ./mvnw test            # on JDK 21 only: ./mvnw -Djava.version=21 test
cd backend && ./mvnw spring-boot:run # DB_URL / DB_USER / DB_PASSWORD override the defaults

# Android: one APK, two flavours
cd android && ./gradlew :app:assemblePlayDebug :app:assembleDirectDebug

# API contract
./contracts/sync.sh --check
npx @redocly/cli@1 lint contracts/openapi.yaml
```

`core/`, `ios/`, `admin-web/` and `language-data/` are placeholders until their plan steps start.
