#!/usr/bin/env bash
# Generates TypeScript client types from contracts/openapi.yaml and type-checks them, so a contract change
# that breaks code generation (or yields uncompilable types) fails CI. Nothing is committed: output goes to
# $1 (default: backend/target/generated-client-types). Requires Node 22+ and network access to npm.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="${1:-$root/backend/target/generated-client-types}"
mkdir -p "$out"
npx --yes openapi-typescript@7.13.0 "$root/contracts/openapi.yaml" -o "$out/api.d.ts"
npx --yes -p typescript@5.9.3 tsc --noEmit --strict --skipLibCheck false --target es2022 "$out/api.d.ts"
echo "client types generated and type-checked: $out/api.d.ts"
