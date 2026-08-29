-- ---------------------------------------------------------------------------
-- Canonical jobs
-- ---------------------------------------------------------------------------
-- One row per real job. A job posted to both Greenhouse and a company careers page, or reposted
-- next quarter under a new requisition id, is one canonical_job with several job_source rows.
CREATE TABLE canonical_job (
    id               uuid PRIMARY KEY,
    company_id       uuid        NOT NULL,

    title            text        NOT NULL,
    canonical_role   text        NOT NULL,
    canonical_level  text        NOT NULL,
    specialization   text        NOT NULL DEFAULT '',

    description_html text        NOT NULL DEFAULT '',
    location         text        NOT NULL DEFAULT '',
    remote           boolean     NOT NULL DEFAULT false,

    -- MinHash signature. Kept so a candidate can be ranked without re-shingling its
    -- description; only the top few survivors pay for an exact Jaccard.
    signature        integer[]   NOT NULL,

    source_count     integer     NOT NULL DEFAULT 0,
    first_seen_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at     timestamptz NOT NULL DEFAULT now(),
    closed_at        timestamptz
);

CREATE INDEX idx_canonical_company ON canonical_job (company_id) WHERE closed_at IS NULL;
CREATE INDEX idx_canonical_role_level ON canonical_job (canonical_role, canonical_level);
CREATE INDEX idx_canonical_last_seen ON canonical_job (last_seen_at DESC);

-- ---------------------------------------------------------------------------
-- The postings that back each canonical job
-- ---------------------------------------------------------------------------
CREATE TABLE job_source (
    id               uuid PRIMARY KEY,
    canonical_job_id uuid        NOT NULL REFERENCES canonical_job (id) ON DELETE CASCADE,
    raw_posting_id   uuid        NOT NULL,
    source           text        NOT NULL,
    external_id      text        NOT NULL,
    apply_url        text        NOT NULL DEFAULT '',
    match_score      double precision NOT NULL,
    attached_at      timestamptz NOT NULL DEFAULT now(),

    -- One posting belongs to exactly one canonical job. Re-processing the same event must
    -- update the attachment, never create a second one.
    CONSTRAINT uq_job_source_posting UNIQUE (raw_posting_id)
);

CREATE INDEX idx_job_source_canonical ON job_source (canonical_job_id);
CREATE INDEX idx_job_source_external ON job_source (source, external_id);

-- ---------------------------------------------------------------------------
-- LSH band index
-- ---------------------------------------------------------------------------
-- Candidate generation. A lookup is one index scan per band; without this, finding near
-- duplicates means comparing against every canonical job the company has.
CREATE TABLE lsh_bucket (
    band_index       smallint NOT NULL,
    band_hash        bigint   NOT NULL,
    canonical_job_id uuid     NOT NULL REFERENCES canonical_job (id) ON DELETE CASCADE,
    company_id       uuid     NOT NULL,
    PRIMARY KEY (band_index, band_hash, canonical_job_id)
);

-- Company is in the index because candidates are always scoped to one company: a "duplicate"
-- across two companies is, by definition, a different job.
CREATE INDEX idx_lsh_lookup ON lsh_bucket (company_id, band_index, band_hash);
