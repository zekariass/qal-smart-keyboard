-- E-mail + password sign-in (ADR 0033): one-time codes sent by e-mail.
--
-- One table for the three e-mail code flows, all with the same shape as otp_challenges (keyed HMAC of the address,
-- keyed HMAC of the code bound to the challenge id, attempt counter, short expiry, single use):
--   REGISTRATION        a sign-up while e-mail verification is required. No account exists yet (user_id is NULL): the
--                       account is created only when the code proves control of the mailbox, so an unverified sign-up
--                       never claims the address. Holds the chosen password's Argon2id hash (pending_password_hash)
--                       and the address and display name sealed with AES-256-GCM (payload_ciphertext, challenge id as
--                       associated data), because the verify request carries only the challenge id and the code.
--   EMAIL_VERIFICATION  an existing, unverified account signs in while verification is required (user_id set).
--   PASSWORD_RESET      "forgot password" for an existing account with a password (user_id set).
-- V1 password_reset_tokens has no attempt counter, which a 6-digit code needs; it stays unused.
-- Resending a code replaces code_hash in place (same id), resets attempts and moves expires_at; sends counts them.
-- Rows are purged by the retention job (email-challenges, 24 h after expiry) and with the account (ON DELETE CASCADE,
-- identity deletion participant).
CREATE TABLE email_challenges (
    id                    UUID PRIMARY KEY,
    user_id               UUID REFERENCES users(id) ON DELETE CASCADE,
    purpose               VARCHAR(24) NOT NULL
                          CHECK (purpose IN ('REGISTRATION', 'EMAIL_VERIFICATION', 'PASSWORD_RESET')),
    email_hash            CHAR(64) NOT NULL CHECK (email_hash ~ '^[0-9a-f]{64}$'),
    code_hash             TEXT NOT NULL,
    payload_ciphertext    BYTEA,
    pending_password_hash TEXT,
    attempts              SMALLINT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts          SMALLINT NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    sends                 SMALLINT NOT NULL DEFAULT 1 CHECK (sends > 0),
    ip_address            INET,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_sent_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at            TIMESTAMPTZ NOT NULL,
    consumed_at           TIMESTAMPTZ,
    CONSTRAINT ck_email_challenge_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_email_challenge_attempts CHECK (attempts <= max_attempts),
    CONSTRAINT ck_email_challenge_registration
        CHECK ((purpose = 'REGISTRATION') = (pending_password_hash IS NOT NULL AND payload_ciphertext IS NOT NULL)),
    CONSTRAINT ck_email_challenge_registration_no_user CHECK (purpose <> 'REGISTRATION' OR user_id IS NULL),
    CONSTRAINT ck_email_challenge_account CHECK (purpose = 'REGISTRATION' OR user_id IS NOT NULL),
    CONSTRAINT ck_email_challenge_payload_size
        CHECK (payload_ciphertext IS NULL OR octet_length(payload_ciphertext) BETWEEN 30 AND 2048)
);

CREATE INDEX ix_email_challenges_email_created ON email_challenges (email_hash, purpose, created_at DESC);
CREATE INDEX ix_email_challenges_expiry ON email_challenges (expires_at);
CREATE INDEX ix_email_challenges_user ON email_challenges (user_id) WHERE user_id IS NOT NULL;

COMMENT ON TABLE email_challenges IS
    'ADR 0033: e-mail codes (sign-up verification, verification of an existing account, password reset). Address and code only as keyed HMACs; a pending sign-up seals the address.';
