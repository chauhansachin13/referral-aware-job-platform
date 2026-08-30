#!/usr/bin/env bash
# Confirms a running stack actually works, rather than merely having started.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@referralhub.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-change-me-in-any-real-deployment}"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "  ok  $*"; }

# Reports the status alongside the failure. Without it a refused request and a missing route
# look identical, which cost a CI round trip when /actuator/prometheus quietly moved behind
# authentication.
status_of() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

expect_body() {
  local what="$1" url="$2" needle="$3"; shift 3
  local body
  if ! body=$(curl -sf "$url" "$@" 2>/dev/null); then
    fail "$what: HTTP $(status_of "$url" "$@") from $url"
  fi
  echo "$body" | grep -q "$needle" || fail "$what: response did not contain '$needle'"
  ok "$what"
}

echo "Smoke testing $BASE"

for _ in $(seq 1 60); do
  if curl -sf "$BASE/actuator/health/readiness" | grep -q UP; then break; fi
  sleep 2
done
curl -sf "$BASE/actuator/health/readiness" | grep -q UP || fail "readiness never came up"
ok "readiness"

curl -sf "$BASE/actuator/health/liveness" | grep -q UP || fail "liveness is down"
ok "liveness"

expect_body "metrics" "$BASE/actuator/prometheus" 'jvm_memory_used_bytes'

# ---------------------------------------------------------------------------------------
# Public surface
# ---------------------------------------------------------------------------------------
expect_body "dedup configuration (public)" "$BASE/api/v1/dedup/banding" 'similarityThreshold'

# An empty index must return an empty page, not a 500.
expect_body "search (public)" "$BASE/api/v1/search?q=engineer&size=1" 'hits'

expect_body "openapi" "$BASE/v3/api-docs" 'openapi'

# ---------------------------------------------------------------------------------------
# Authentication is actually enforced
# ---------------------------------------------------------------------------------------
# The point of asserting the negative: a filter chain that silently permits everything looks
# identical to a working one from the happy path alone.
status=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/ingestion/queue")
[ "$status" = "401" ] || fail "an unauthenticated call to a protected route returned $status, not 401"
ok "protected routes refuse anonymous callers"

status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/ingestion/boards" \
  -H 'Content-Type: application/json' \
  -d '{"companyName":"X","companySlug":"x","source":"greenhouse","boardToken":"x"}')
[ "$status" = "401" ] || fail "board registration without a token returned $status, not 401"
ok "board registration refuses anonymous callers"

TOKEN=$(curl -sf -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])') \
  || fail "could not log in as the bootstrap administrator"
[ -n "$TOKEN" ] || fail "login returned an empty token"
ok "administrator login"

expect_body "token carries its roles" "$BASE/api/v1/auth/me" 'ADMIN' -H "Authorization: Bearer $TOKEN"

expect_body "ingestion queue (authenticated)" "$BASE/api/v1/ingestion/queue" 'scheduledBoards' \
  -H "Authorization: Bearer $TOKEN"

# A token with no ADMIN role must not be able to register a board.
curl -sf -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Smoke User","email":"smoke-user@example.com","password":"smoke-test-password-1"}' \
  > /dev/null || true   # already registered on a re-run is fine
USER_TOKEN=$(curl -sf -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"smoke-user@example.com","password":"smoke-test-password-1"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')

status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/ingestion/boards" \
  -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"companyName":"X","companySlug":"x","source":"greenhouse","boardToken":"x"}')
[ "$status" = "403" ] || fail "an ordinary account registering a board returned $status, not 403"
ok "ordinary accounts cannot register boards"

expect_body "standing (authenticated)" "$BASE/api/v1/trust/standing" 'reputation' \
  -H "Authorization: Bearer $USER_TOKEN"

echo "All smoke checks passed."
