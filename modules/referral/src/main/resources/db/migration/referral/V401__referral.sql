-- ---------------------------------------------------------------------------
-- Resumes (PII)
-- ---------------------------------------------------------------------------
-- The bytes live in object storage, encrypted before they leave this process. This table holds
-- only the metadata needed to find, decrypt and destroy them. There is no column here that
-- contains resume content, and deletion is a hard delete by design: "soft deleted" PII is
-- still PII.
CREATE TABLE resume (
    id            uuid PRIMARY KEY,
    owner_id      uuid        NOT NULL,
    object_key    text        NOT NULL UNIQUE,
    filename      text        NOT NULL,
    content_type  text        NOT NULL,
    size_bytes    integer     NOT NULL,
    sha256        text        NOT NULL,
    -- AES-GCM nonce. Unique per object; never reused with the same key.
    encryption_iv text        NOT NULL,
    key_id        text        NOT NULL,
    uploaded_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_resume_owner ON resume (owner_id);

-- ---------------------------------------------------------------------------
-- Referral requests
-- ---------------------------------------------------------------------------
CREATE TABLE referral_request (
    id               uuid PRIMARY KEY,
    seeker_id        uuid        NOT NULL,
    referrer_id      uuid,
    canonical_job_id uuid        NOT NULL,
    company_id       uuid        NOT NULL,
    resume_id        uuid REFERENCES resume (id) ON DELETE SET NULL,

    state            text        NOT NULL DEFAULT 'REQUESTED',
    message          text        NOT NULL DEFAULT '',
    decline_reason   text,

    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    expires_at       timestamptz NOT NULL,
    accepted_at      timestamptz,
    submitted_at     timestamptz,
    closed_at        timestamptz,

    -- One seeker cannot queue the same job twice; that is spam, not enthusiasm.
    CONSTRAINT uq_referral_seeker_job UNIQUE (seeker_id, canonical_job_id)
);

CREATE INDEX idx_referral_state ON referral_request (state);
CREATE INDEX idx_referral_referrer_open ON referral_request (referrer_id)
    WHERE state = 'ACCEPTED';
CREATE INDEX idx_referral_seeker ON referral_request (seeker_id, created_at DESC);
-- Drives the expiry sweeper; only non-terminal rows can ever expire.
CREATE INDEX idx_referral_expiry ON referral_request (expires_at)
    WHERE state IN ('REQUESTED', 'ACCEPTED');

-- ---------------------------------------------------------------------------
-- Audit log
-- ---------------------------------------------------------------------------
-- Append only. Every state change, who caused it, and when. This is the record that answers
-- "the referrer says they never got it" without anyone having to trust a log file.
CREATE TABLE referral_transition (
    id          uuid PRIMARY KEY,
    request_id  uuid        NOT NULL REFERENCES referral_request (id) ON DELETE CASCADE,
    from_state  text,
    to_state    text        NOT NULL,
    actor_type  text        NOT NULL,
    actor_id    uuid,
    reason      text,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_transition_request ON referral_transition (request_id, occurred_at);

-- ---------------------------------------------------------------------------
-- Idempotency
-- ---------------------------------------------------------------------------
-- A client that retries a transition after a timeout must not accept the same request twice.
-- The key is supplied by the caller and the resulting state is remembered, so a replay returns
-- the original outcome instead of hitting an illegal-transition error for work it already did.
CREATE TABLE referral_idempotency (
    idempotency_key text PRIMARY KEY,
    request_id      uuid        NOT NULL REFERENCES referral_request (id) ON DELETE CASCADE,
    operation       text        NOT NULL,
    resulting_state text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_idempotency_created ON referral_idempotency (created_at);
