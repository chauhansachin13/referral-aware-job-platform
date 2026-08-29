#!/usr/bin/env bash
# Confirms a running stack actually works, rather than merely having started.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
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

# Flyway ran: the dedup module's banding endpoint reads configuration that only resolves
# after the context is fully wired.
curl -sf "$BASE/api/v1/dedup/banding" | grep -q 'similarityThreshold' \
  || fail "dedup banding endpoint did not answer"
ok "dedup configuration"

curl -sf "$BASE/api/v1/ingestion/queue" | grep -q 'scheduledBoards' \
  || fail "ingestion queue endpoint did not answer (is Redis reachable?)"
ok "ingestion queue"

# An empty index must return an empty page, not a 500.
curl -sf "$BASE/api/v1/search?q=engineer&size=1" | grep -q 'hits' \
  || fail "search endpoint did not answer (is OpenSearch reachable?)"
ok "search"

curl -sf "$BASE/v3/api-docs" | grep -q 'openapi' || fail "OpenAPI document is missing"
ok "openapi"

echo "All smoke checks passed."
