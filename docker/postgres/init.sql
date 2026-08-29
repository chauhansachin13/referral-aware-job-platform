-- Runs once, on first boot of an empty data volume. Flyway owns everything else; this file
-- only installs the extensions Flyway itself cannot create without superuser rights.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
