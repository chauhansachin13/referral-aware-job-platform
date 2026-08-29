-- ---------------------------------------------------------------------------
-- Companies and their ATS boards
-- ---------------------------------------------------------------------------
CREATE TABLE company (
    id            uuid PRIMARY KEY,
    name          text        NOT NULL,
    slug          text        NOT NULL UNIQUE,
    -- Email domain used by the trust module to verify that an employee actually
    -- works here. Nullable: plenty of companies are worth indexing before anyone
    -- from them has signed up to refer.
    email_domain  text,
    careers_url   text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE company_board (
    id                        uuid PRIMARY KEY,
    company_id                uuid        NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    source                    text        NOT NULL,
    board_token               text        NOT NULL,
    enabled                   boolean     NOT NULL DEFAULT true,

    -- Conditional-fetch validators, straight from the last response.
    etag                      text,
    last_modified             timestamptz,

    -- Semantic hash of the last parse; see ContentHasher.
    last_content_hash         text,

    crawl_interval_seconds    integer     NOT NULL DEFAULT 3600,
    last_crawled_at           timestamptz,
    last_changed_at           timestamptz,
    consecutive_unchanged     integer     NOT NULL DEFAULT 0,
    observed_postings_per_day double precision NOT NULL DEFAULT 1.0,

    created_at                timestamptz NOT NULL DEFAULT now(),

    -- One company can have a Greenhouse board and a Lever board, but not two of the same.
    CONSTRAINT uq_board_source_token UNIQUE (source, board_token)
);

CREATE INDEX idx_board_enabled ON company_board (enabled) WHERE enabled;
CREATE INDEX idx_board_company ON company_board (company_id);

-- ---------------------------------------------------------------------------
-- Raw payloads, written before anything is parsed
-- ---------------------------------------------------------------------------
-- Storing the untouched response is what makes a parser bug recoverable. When an adapter is
-- fixed, the fix is replayed over this table; without it the only way to re-parse is to
-- re-crawl, and by then the posting may be gone from the board entirely.
CREATE TABLE raw_payload (
    id           uuid PRIMARY KEY,
    board_id     uuid        NOT NULL REFERENCES company_board (id) ON DELETE CASCADE,
    fetched_at   timestamptz NOT NULL DEFAULT now(),
    http_status  integer     NOT NULL,
    raw_hash     text        NOT NULL,
    byte_size    integer     NOT NULL,
    body         text        NOT NULL
);

CREATE INDEX idx_raw_payload_board ON raw_payload (board_id, fetched_at DESC);
CREATE INDEX idx_raw_payload_hash ON raw_payload (raw_hash);

-- ---------------------------------------------------------------------------
-- Postings as each source sees them
-- ---------------------------------------------------------------------------
-- Deliberately per-source. Reconciling "the same job on three boards" is the dedup module's
-- job; this table is the faithful record of what each board actually said.
CREATE TABLE raw_posting (
    id               uuid PRIMARY KEY,
    board_id         uuid        NOT NULL REFERENCES company_board (id) ON DELETE CASCADE,
    company_id       uuid        NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    source           text        NOT NULL,
    external_id      text        NOT NULL,

    title            text        NOT NULL,
    description_html text        NOT NULL DEFAULT '',
    location         text        NOT NULL DEFAULT '',
    remote           boolean     NOT NULL DEFAULT false,
    department       text        NOT NULL DEFAULT '',
    apply_url        text        NOT NULL DEFAULT '',
    posted_at        timestamptz,

    content_hash     text        NOT NULL,
    raw_json         jsonb       NOT NULL,

    first_seen_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at     timestamptz NOT NULL DEFAULT now(),
    closed_at        timestamptz,

    CONSTRAINT uq_posting_source_external UNIQUE (source, external_id)
);

CREATE INDEX idx_posting_board_open ON raw_posting (board_id) WHERE closed_at IS NULL;
CREATE INDEX idx_posting_company ON raw_posting (company_id);
CREATE INDEX idx_posting_last_seen ON raw_posting (last_seen_at);

-- ---------------------------------------------------------------------------
-- Crawl history
-- ---------------------------------------------------------------------------
CREATE TABLE crawl_log (
    id                uuid PRIMARY KEY,
    board_id          uuid        NOT NULL REFERENCES company_board (id) ON DELETE CASCADE,
    started_at        timestamptz NOT NULL DEFAULT now(),
    outcome           text        NOT NULL,
    http_status       integer,
    elapsed_ms        integer     NOT NULL,
    postings_seen     integer     NOT NULL DEFAULT 0,
    postings_changed  integer     NOT NULL DEFAULT 0,
    error             text
);

CREATE INDEX idx_crawl_log_board ON crawl_log (board_id, started_at DESC);
