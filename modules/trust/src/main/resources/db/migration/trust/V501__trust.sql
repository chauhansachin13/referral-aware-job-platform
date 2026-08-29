-- ---------------------------------------------------------------------------
-- People
-- ---------------------------------------------------------------------------
CREATE TABLE platform_user (
    id           uuid PRIMARY KEY,
    display_name text        NOT NULL,
    -- Personal email. Never used for verification: anyone can own a gmail address.
    email        text        NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Employee verification
-- ---------------------------------------------------------------------------
-- A referral is only worth anything if the referrer actually works there, so employment is
-- proved by control of an address at the company's own domain. The code is stored as a hash:
-- a leaked database must not let anyone complete someone else's verification.
CREATE TABLE employee_verification (
    id               uuid PRIMARY KEY,
    user_id          uuid        NOT NULL REFERENCES platform_user (id) ON DELETE CASCADE,
    company_id       uuid        NOT NULL,
    work_email       text        NOT NULL,
    email_domain     text        NOT NULL,

    otp_hash         text,
    otp_salt         text,
    otp_expires_at   timestamptz,
    otp_attempts     integer     NOT NULL DEFAULT 0,

    status           text        NOT NULL DEFAULT 'PENDING',
    verified_at      timestamptz,
    -- Verification is a lease, not a fact: people change jobs and nobody tells us.
    expires_at       timestamptz,
    last_reverified_at timestamptz,

    created_at       timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_verification_user_company UNIQUE (user_id, company_id)
);

CREATE INDEX idx_verification_active
    ON employee_verification (company_id, status)
    WHERE status = 'VERIFIED';
CREATE INDEX idx_verification_expiry ON employee_verification (expires_at)
    WHERE status = 'VERIFIED';

-- ---------------------------------------------------------------------------
-- Reputation
-- ---------------------------------------------------------------------------
-- Counters only. The score itself is derived on read by a pure function, so changing the
-- formula is a deploy rather than a backfill.
CREATE TABLE reputation_counters (
    user_id            uuid PRIMARY KEY REFERENCES platform_user (id) ON DELETE CASCADE,
    requests_received  integer NOT NULL DEFAULT 0,
    requests_responded integer NOT NULL DEFAULT 0,
    requests_accepted  integer NOT NULL DEFAULT 0,
    requests_completed integer NOT NULL DEFAULT 0,
    requests_expired   integer NOT NULL DEFAULT 0,
    requests_sent      integer NOT NULL DEFAULT 0,
    updated_at         timestamptz NOT NULL DEFAULT now()
);
