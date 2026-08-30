-- ---------------------------------------------------------------------------
-- Authentication
-- ---------------------------------------------------------------------------
-- Added after the rest of the platform. Until this migration every endpoint took the acting
-- user's id as a parameter and believed it, which is fine for a demo and indefensible for
-- anything else: any caller could accept another person's referral, or read a resume released
-- to someone else.

ALTER TABLE platform_user
    -- BCrypt, so the work factor travels with the hash and can be raised later without a
    -- migration. Nullable because rows created before this migration have no password; those
    -- accounts cannot log in until one is set.
    ADD COLUMN password_hash text,
    -- Comma-separated rather than a join table. There are two roles and no prospect of
    -- per-role attributes; a join table here would be structure without information.
    ADD COLUMN roles text NOT NULL DEFAULT 'USER',
    ADD COLUMN last_login_at timestamptz;

CREATE INDEX idx_user_email_login ON platform_user (email) WHERE password_hash IS NOT NULL;
