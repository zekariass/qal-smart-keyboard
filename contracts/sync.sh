#!/usr/bin/env bash
# Keeps contracts/openapi.yaml identical to the canonical docs copy.
#   ./contracts/sync.sh          copy docs -> contracts
#   ./contracts/sync.sh --check  exit 1 if they differ (used by CI)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/docs/Ethiopian_AI_Keyboard_OpenAPI_v2_0.yaml"
dst="$root/contracts/openapi.yaml"
if [[ "${1:-}" == "--check" ]]; then
  if cmp -s "$src" "$dst"; then echo "contract in sync"; else
    echo "contracts/openapi.yaml differs from docs/. Edit the docs copy, then run contracts/sync.sh." >&2; exit 1; fi
else
  cp "$src" "$dst"; echo "synced $dst"
fi
