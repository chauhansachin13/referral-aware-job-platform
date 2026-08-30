#!/usr/bin/env bash
# Runs the platform without Docker, against natively installed Postgres and Redis.
#
# The supported path is `make up`, which brings up all five dependencies in containers.
# This exists for machines with no Docker daemon. It runs the real application; the parts
# that need OpenSearch, MinIO or Kafka are switched off rather than faked, and the script
# says so at the end.
#
#   ./scripts/run-local.sh          start everything
#   ./scripts/run-local.sh stop     stop the app and the datastores
#   ./scripts/run-local.sh reset    drop the database and start clean
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_HOME="${PG_HOME:-/opt/homebrew/opt/postgresql@16}"
REDIS_HOME="${REDIS_HOME:-/opt/homebrew/opt/redis}"
PG_DATA="${PG_DATA:-/opt/homebrew/var/postgresql@16}"
STATE_DIR="$ROOT/.local-run"
ENV_FILE="$ROOT/.env.local"
JAR="$ROOT/modules/app/build/libs/app.jar"

DB_NAME=referralhub
DB_USER=referralhub
DB_PASSWORD=referralhub

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

stop_all() {
  pkill -f "$JAR" 2>/dev/null && log "stopped the application" || true
  "$REDIS_HOME/bin/redis-cli" shutdown nosave 2>/dev/null && log "stopped redis" || true
  "$PG_HOME/bin/pg_ctl" -D "$PG_DATA" stop -s -m fast 2>/dev/null && log "stopped postgres" || true
}

case "${1:-start}" in
  stop) stop_all; exit 0 ;;
  reset) RESET=1 ;;
  start) RESET=0 ;;
  *) die "usage: $0 [start|stop|reset]" ;;
esac

# ------------------------------------------------------------------------------------------
# Dependencies
# ------------------------------------------------------------------------------------------
[ -x "$PG_HOME/bin/pg_ctl" ] || die "Postgres 16 not found at $PG_HOME. Install with:
  brew install postgresql@16 redis"
[ -x "$REDIS_HOME/bin/redis-server" ] || die "Redis not found at $REDIS_HOME. Install with:
  brew install postgresql@16 redis"

mkdir -p "$STATE_DIR"

# Before anything else. A running application keeps connections open, which makes DROP DATABASE
# fail, and leaves a second instance fighting for port 8080 on a plain restart.
if pkill -f "$JAR" 2>/dev/null; then
  log "stopped the previous application instance"
  sleep 1
fi

if ! "$PG_HOME/bin/pg_isready" -q 2>/dev/null; then
  log "starting postgres"
  LC_ALL="en_US.UTF-8" "$PG_HOME/bin/pg_ctl" -D "$PG_DATA" -l "$STATE_DIR/postgres.log" start -w -s
else
  log "postgres already running"
fi

if ! "$REDIS_HOME/bin/redis-cli" ping >/dev/null 2>&1; then
  log "starting redis"
  "$REDIS_HOME/bin/redis-server" --daemonize yes --port 6379 --save '' --appendonly no
else
  log "redis already running"
fi

# ------------------------------------------------------------------------------------------
# Secrets: generated once, then reused
# ------------------------------------------------------------------------------------------
# Regenerating these on every start would invalidate every issued token and make every stored
# resume undecryptable, because the key that encrypted it would be gone.
if [ ! -f "$ENV_FILE" ]; then
  log "generating local secrets into .env.local (gitignored, generated once)"
  cat > "$ENV_FILE" <<EOF
REFERRALHUB_STORAGE_ENCRYPTIONKEY=$(openssl rand -base64 32)
REFERRALHUB_STORAGE_URLSIGNINGSECRET=$(openssl rand -hex 32)
REFERRALHUB_AUTH_JWTSECRET=$(openssl rand -base64 48)
BOOTSTRAP_ADMIN_EMAIL=admin@referralhub.local
BOOTSTRAP_ADMIN_PASSWORD=local-development-admin
EOF
fi
set -a; . "$ENV_FILE"; set +a

# ------------------------------------------------------------------------------------------
# Database
# ------------------------------------------------------------------------------------------
export PGPASSWORD="$DB_PASSWORD"
PSQL="$PG_HOME/bin/psql"

if ! "$PSQL" -h localhost -U "$DB_USER" -d postgres -c '\q' 2>/dev/null; then
  log "creating the $DB_USER role"
  "$PSQL" -d postgres -qc "CREATE ROLE $DB_USER LOGIN PASSWORD '$DB_PASSWORD' SUPERUSER;"
fi

if [ "$RESET" = "1" ]; then
  log "dropping $DB_NAME"
  "$PSQL" -h localhost -U "$DB_USER" -d postgres -qc "DROP DATABASE IF EXISTS $DB_NAME;"
  "$REDIS_HOME/bin/redis-cli" flushall >/dev/null
fi

if ! "$PSQL" -h localhost -U "$DB_USER" -lqt | cut -d'|' -f1 | grep -qw "$DB_NAME"; then
  log "creating $DB_NAME"
  "$PSQL" -h localhost -U "$DB_USER" -d postgres -qc "CREATE DATABASE $DB_NAME OWNER $DB_USER;"
fi
# Flyway needs pgcrypto and cannot create an extension without superuser rights.
"$PSQL" -h localhost -U "$DB_USER" -d "$DB_NAME" -qc "CREATE EXTENSION IF NOT EXISTS pgcrypto;"

# ------------------------------------------------------------------------------------------
# Application
# ------------------------------------------------------------------------------------------
[ -f "$JAR" ] || { log "building the jar"; (cd "$ROOT" && ./gradlew :app:bootJar -q); }

export DATABASE_URL="jdbc:postgresql://localhost:5432/$DB_NAME"
export DATABASE_USER="$DB_USER"
export DATABASE_PASSWORD="$DB_PASSWORD"
export REDIS_HOST=localhost

log "starting the application"
# OpenSearch, MinIO and Kafka are not running, so the components that talk to them are off.
# Everything else is the real application, unmodified.
nohup java -jar "$JAR" \
  --referralhub.outbox.relay-enabled=false \
  --referralhub.dedup.consumer-enabled=false \
  --referralhub.search.indexer-enabled=false \
  --referralhub.ingestion.crawl-enabled=false \
  --spring.kafka.bootstrap-servers=localhost:1 \
  > "$STATE_DIR/app.log" 2>&1 &

for _ in $(seq 1 60); do
  curl -sf http://localhost:8080/actuator/health/readiness >/dev/null 2>&1 && break
  sleep 1
done
curl -sf http://localhost:8080/actuator/health/readiness >/dev/null 2>&1 \
  || die "the application did not become ready; see $STATE_DIR/app.log"

# The bootstrap only creates an administrator when none exists, so it will not overwrite one
# left behind by an earlier run with different credentials. Check rather than assume: printing
# a password that does not work is worse than printing none.
ADMIN_NOTE=""
if ! curl -sf -X POST http://localhost:8080/api/v1/auth/login \
      -H 'Content-Type: application/json' \
      -d "{\"email\":\"$BOOTSTRAP_ADMIN_EMAIL\",\"password\":\"$BOOTSTRAP_ADMIN_PASSWORD\"}" \
      >/dev/null 2>&1; then
  ADMIN_NOTE="  (these do NOT work: an administrator already existed when this database was
   created, so the bootstrap was skipped. Run ./scripts/run-local.sh reset to start clean.)"
fi

cat <<EOF

  Running.  http://localhost:8080

    console      http://localhost:8080/
    api docs     http://localhost:8080/docs
    health       http://localhost:8080/actuator/health
    logs         tail -f .local-run/app.log

  Administrator: $BOOTSTRAP_ADMIN_EMAIL / $BOOTSTRAP_ADMIN_PASSWORD
$ADMIN_NOTE

  Not available without Docker, because their services are not running:
    search results   needs OpenSearch
    resume storage   needs MinIO
    the event path   needs Kafka  (events still accumulate in the outbox table)

  Everything else is live: registration and login, employee verification, the referral
  lifecycle, and crawling real public ATS boards.

  Stop with: ./scripts/run-local.sh stop
EOF
