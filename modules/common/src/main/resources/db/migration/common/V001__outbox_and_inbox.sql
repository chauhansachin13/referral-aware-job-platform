-- Cross-cutting infrastructure tables. Feature tables live in each module's own migration
-- folder; Flyway is pointed at all of them via spring.flyway.locations.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- Transactional outbox
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_event (
    id              uuid PRIMARY KEY,
    aggregate_type  text        NOT NULL,
    aggregate_id    uuid        NOT NULL,
    event_type      text        NOT NULL,
    topic           text        NOT NULL,
    partition_key   text        NOT NULL,
    payload         jsonb       NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    attempts        integer     NOT NULL DEFAULT 0,
    last_error      text
);

-- Partial index: the relay only ever reads unpublished rows, and once a row is published it
-- should stop costing anything to skip over. A full index on occurred_at would keep every
-- published row in the hot path.
CREATE INDEX idx_outbox_pending
    ON outbox_event (occurred_at)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_reaper
    ON outbox_event (published_at)
    WHERE published_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Consumer-side idempotency
-- ---------------------------------------------------------------------------
CREATE TABLE processed_message (
    consumer_group text        NOT NULL,
    message_key    text        NOT NULL,
    processed_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, message_key)
);

CREATE INDEX idx_processed_message_purge ON processed_message (processed_at);
