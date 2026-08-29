#!/usr/bin/env bash
# Registers a handful of real public ATS boards so there is something to crawl.
#
# Every board below is a documented public endpoint that powers the company's own careers page.
# Nothing here scrapes a site that does not offer one.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"

register() {
  local name="$1" slug="$2" source="$3" token="$4" domain="$5"
  echo "Registering $name ($source/$token)"
  curl -sf -X POST "$BASE/api/v1/ingestion/boards" \
    -H 'Content-Type: application/json' \
    -d "{\"companyName\":\"$name\",\"companySlug\":\"$slug\",\"source\":\"$source\",\"boardToken\":\"$token\",\"emailDomain\":\"$domain\"}" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print("  id:", d["id"])' \
    || echo "  (already registered or unavailable)"
}

register "Stripe"     "stripe"     "greenhouse" "stripe"     "stripe.com"
register "Databricks" "databricks" "greenhouse" "databricks" "databricks.com"
register "Netflix"    "netflix"    "lever"      "netflix"    "netflix.com"
register "Ramp"       "ramp"       "ashby"      "ramp"       "ramp.com"
register "Linear"     "linear"     "ashby"      "linear"     "linear.app"

echo
echo "Crawl queue depth:"
curl -sf "$BASE/api/v1/ingestion/queue"
echo
echo "Boards are queued. Watch progress with: make logs"
