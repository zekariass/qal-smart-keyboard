#!/usr/bin/env bash
# Starts the backend for LOCAL DEVELOPMENT ONLY (plan step A2.1; profile "dev", backend/src/main/resources/application-dev.yaml).
#
#   infra/scripts/run-backend-dev.sh            # starts compose Postgres if needed, then runs the backend on :8080
#   SERVER_PORT=8081 infra/scripts/run-backend-dev.sh
#
# Dev-only switches in effect (all refused by the backend under prod/production/staging):
#   local KMS with a KEK kept in infra/scripts/.dev-state/kek (or QAL_LOCAL_KMS_KEK from the environment),
#   ephemeral JWT / entitlement-token keys and OTP secret (die with the JVM), mock attestation, AI mock,
#   SMS "capture" provider + GET /dev/sms/last (loopback only, no auth).
# Database: compose Postgres at localhost:5434 (override with DB_URL, DB_USER, DB_PASSWORD).
# Stop the backend with Ctrl-C; stop Postgres with: docker compose -f infra/compose/docker-compose.yml down
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_file="$root/infra/compose/docker-compose.yml"
state_dir="$root/infra/scripts/.dev-state"

# --- safety: never run next to a production-like profile -------------------------------------------------------
for var in SPRING_PROFILES_ACTIVE SPRING_PROFILES_INCLUDE; do
  value="${!var:-}"
  if [[ "${value,,}" == *prod* || "${value,,}" == *staging* ]]; then
    echo "REFUSING to start: $var='$value' contains a production-like profile. This launcher is for local development only." >&2
    exit 2
  fi
done
export SPRING_PROFILES_ACTIVE="dev"

# --- KEK for the local KMS ---------------------------------------------------------------------------------------
if [[ -z "${QAL_LOCAL_KMS_KEK:-}" ]]; then
  mkdir -p "$state_dir"
  chmod 700 "$state_dir" 2>/dev/null || true
  if [[ ! -s "$state_dir/kek" ]]; then
    if command -v openssl >/dev/null 2>&1; then
      openssl rand -base64 32 > "$state_dir/kek"
    else
      head -c 32 /dev/urandom | base64 > "$state_dir/kek"
    fi
    chmod 600 "$state_dir/kek" 2>/dev/null || true
    echo "Generated a dev-only KEK in infra/scripts/.dev-state/kek (git-ignored)."
  fi
  QAL_LOCAL_KMS_KEK="$(tr -d '\r\n' < "$state_dir/kek")"
fi
export QAL_LOCAL_KMS_KEK

# --- Postgres (compose, port 5434) -------------------------------------------------------------------------------
if [[ -f "$root/infra/compose/.env" ]]; then
  pg_port="$(sed -n 's/^POSTGRES_HOST_PORT=//p' "$root/infra/compose/.env" | tail -1 | tr -d '\r')"
fi
pg_port="${pg_port:-5434}"
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:${pg_port}/qal}"

pg_up() { (exec 3<>"/dev/tcp/127.0.0.1/${pg_port}") 2>/dev/null; }
if [[ -z "${QAL_DEV_SKIP_POSTGRES:-}" ]] && ! pg_up; then
  echo "Starting compose Postgres on :${pg_port} ..."
  docker compose -f "$compose_file" up -d --wait postgres
fi

# --- run ---------------------------------------------------------------------------------------------------------
java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
extra=()
if [[ -n "$java_major" && "$java_major" -lt 25 ]]; then
  extra+=("-Djava.version=$java_major")   # the project targets Java 25; compile for the local JDK
fi
cd "$root/backend"
echo "Backend (profile dev) on http://localhost:${SERVER_PORT:-8080} ; DEV ONLY, never deploy this configuration."
exec ./mvnw -B -ntp ${extra[@]+"${extra[@]}"} spring-boot:run
