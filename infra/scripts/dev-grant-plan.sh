#!/usr/bin/env bash
# LOCAL DEVELOPMENT ONLY: grant (or revoke) a subscription plan for a dev account in the compose Postgres.
#
#   infra/scripts/dev-grant-plan.sh <+E164 phone | user-uuid> <PLAN_CODE> [days=30]
#   infra/scripts/dev-grant-plan.sh --revoke <+E164 phone | user-uuid> <PLAN_CODE>
#
# Inserts an ADMIN_GRANT / GRANT / ACTIVE row into user_subscriptions (sql/dev-grant-plan.sql); --revoke sets such
# rows to REVOKED (sql/dev-revoke-plan.sql). Plan codes: GUEST, FREE, PREMIUM, PREMIUM_PLUS, BUSINESS.
# A phone number is resolved through users.phone_e164 (stored in plain text for REGISTERED accounts); otherwise pass the
# user id, which is the `sub` claim of the access token.
# Refuses unless the container compose-postgres-1 is running with host port 5434 (the local compose database).
# The backend caches resolved entitlements ~30 s (qal.entitlements.cache-ttl); the app then needs a refresh.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
container="compose-postgres-1"
revoke=0
if [[ "${1:-}" == "--revoke" ]]; then revoke=1; shift; fi
if [[ $# -lt 2 || $# -gt 3 ]]; then
  sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 64
fi
target="$1"; plan="${2^^}"; days="${3:-30}"

[[ "$plan" =~ ^[A-Z_]{1,30}$ ]] || { echo "Bad plan code '$2'." >&2; exit 64; }
[[ "$days" =~ ^[0-9]{1,4}$ && "$days" -ge 1 ]] || { echo "days must be a positive integer (max 9999)." >&2; exit 64; }

# --- guard: only the local compose database ------------------------------------------------------------------------
if [[ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || true)" != "true" ]]; then
  echo "REFUSING: container $container is not running (this script only touches the local compose Postgres)." >&2
  exit 2
fi
if ! docker port "$container" 5432/tcp 2>/dev/null | grep -q ':5434$'; then
  echo "REFUSING: $container does not publish host port 5434; not the local compose database." >&2
  exit 2
fi
psql_() { MSYS_NO_PATHCONV=1 docker exec -i "$container" psql -U qal -d qal -v ON_ERROR_STOP=1 -X -q "$@"; }

# --- resolve the user ----------------------------------------------------------------------------------------------
if [[ "$target" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
  user_id="${target,,}"
elif [[ "$target" =~ ^\+[1-9][0-9]{6,14}$ ]]; then
  user_id="$(psql_ -tA -v "phone=$target" <<< "SELECT id FROM users WHERE phone_e164 = :'phone' AND deleted_at IS NULL;")"
  if [[ -z "$user_id" ]]; then
    echo "No registered user with phone $target. Sign in on the device first, or pass the user id (the 'sub' claim of the access token)." >&2
    exit 1
  fi
else
  echo "'$target' is neither a +E164 phone number nor a user UUID." >&2
  exit 64
fi
exists="$(psql_ -tA -v "uid=$user_id" <<< "SELECT count(*) FROM users WHERE id = :'uid'::uuid;")"
[[ "$exists" == "1" ]] || { echo "No user with id $user_id." >&2; exit 1; }

if ((revoke)); then
  out="$(psql_ -tA -v "user_id=$user_id" -v "plan_code=$plan" -f - < "$here/sql/dev-revoke-plan.sql")"
  n="$(grep -c . <<< "$out" || true)"
  echo "Revoked $n dev grant(s) of $plan for user $user_id."
else
  if [[ "$(psql_ -tA -v "p=$plan" <<< "SELECT count(*) FROM subscription_plans WHERE plan_code = :'p';")" != "1" ]]; then
    echo "Unknown plan code $plan (nothing granted)." >&2; exit 1
  fi
  psql_ -tA -F ' until ' -v "user_id=$user_id" -v "plan_code=$plan" -v "days=$days" -f - < "$here/sql/dev-grant-plan.sql" | sed "s/^/Granted $plan to $user_id: subscription /"
fi
cat <<MSG
Note: the backend caches resolved entitlements for ~30 s (qal.entitlements.cache-ttl), and the app keeps its entitlement
token/quota hints (quota refreshes every 5 min). Wait ~30 s, then reopen the app or open the keyboard's AI row so the new
plan state is fetched; a sign-out/sign-in forces a fresh token.
MSG
