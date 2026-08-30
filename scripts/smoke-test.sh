#!/usr/bin/env bash
# Confirms a running stack actually works, rather than merely having started.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@referralhub.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-change-me-in-any-real-deployment}"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "  ok  $*"; }

echo "Smoke testing $BASE"

for _ in $(seq 1 60); do
  if curl -sf "$BASE/actuator/health/readiness" | grep -q UP; then break; fi
  sleep 2
done
curl -sf "$BASE/actuator/health/readiness" | grep -q UP || fail "readiness never came up"
ok "readiness"

curl -sf "$BASE/actuator/health/liveness" | grep -q UP || fail "liveness is down"
ok "liveness"

curl -sf "$BASE/actuator/prometheus" | grep -q 'jvm_memory_used_bytes' \
  || fail "prometheus endpoint is not exporting metrics"
ok "metrics"

# ---------------------------------------------------------------------------------------
# Public surface
# ---------------------------------------------------------------------------------------
curl -sf "$BASE/api/v1/dedup/banding" | grep -q 'similarityThreshold' \
  || fail "dedup banding endpoint did not answer"
ok "dedup configuration (public)"

# An empty index must return an empty page, not a 500.
curl -sf "$BASE/api/v1/search?q=engineer&size=1" | grep -q 'hits' \
  || fail "search endpoint did not answer (is OpenSearch reachable?)"
ok "search (public)"

curl -sf "$BASE/v3/api-docs" | grep -q 'openapi' || fail "OpenAPI document is missing"
ok "openapi"

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

curl -sf "$BASE/api/v1/auth/me" -H "Authorization: Bearer $TOKEN" | grep -q 'ADMIN' \
  || fail "the token does not carry the ADMIN role"
ok "token carries its roles"

curl -sf "$BASE/api/v1/ingestion/queue" -H "Authorization: Bearer $TOKEN" | grep -q 'scheduledBoards' \
  || fail "authenticated ingestion queue call failed (is Redis reachable?)"
ok "ingestion queue (authenticated)"

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

curl -sf "$BASE/api/v1/trust/standing" -H "Authorization: Bearer $USER_TOKEN" | grep -q 'reputation' \
  || fail "standing endpoint did not answer for an authenticated user"
ok "standing (authenticated)"

echo "All smoke checks passed."
