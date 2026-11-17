#!/usr/bin/env bash
# Keeps generated copies identical to their canonical sources in docs/.
#   docs/..._OpenAPI_v2_0.yaml          -> contracts/openapi.yaml
#   docs/..._Database_Design_v2_0.sql   -> backend/.../db/migration/V1__baseline.sql
#
#   ./contracts/sync.sh          copy docs -> targets
#   ./contracts/sync.sh --check  exit 1 if any copy differs (used by CI)
#
# NOTE: once V1__baseline.sql is applied to a shared environment it is
# immutable. From then on schema changes are new V2__... migrations and the
# docs SQL must be kept as the documented final shape, not re-synced.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
pairs=(
  "docs/Ethiopian_AI_Keyboard_OpenAPI_v2_0.yaml|contracts/openapi.yaml"
  "docs/Ethiopian_AI_Keyboard_Database_Design_v2_0.sql|backend/src/main/resources/db/migration/V1__baseline.sql"
)
status=0
for p in "${pairs[@]}"; do
  src="$root/${p%%|*}"; dst="$root/${p##*|}"
  if [[ "${1:-}" == "--check" ]]; then
    # compare ignoring CR so Windows checkouts do not report false drift
    if diff -q <(tr -d '\r' < "$src") <(tr -d '\r' < "$dst") > /dev/null; then
      echo "in sync: ${p##*|}"
    else
      echo "DRIFT: ${p##*|} differs from ${p%%|*}. Edit the docs copy, then run contracts/sync.sh." >&2
      status=1
    fi
  else
    cp "$src" "$dst"; echo "synced ${p##*|}"
  fi
done
exit $status
