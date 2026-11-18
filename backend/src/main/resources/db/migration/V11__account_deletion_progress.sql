-- B6.2: account deletion executor (System Design 19.4, ADR 0015).
--
-- The executor works through a due request in three committed steps so that a crash between them resumes where it
-- stopped and never repeats a finished step:
--   1. SCHEDULED -> PROCESSING: close the account (users.deleted_at), revoke sessions, crypto-shred the data keys;
--      shredded_at is set in the same transaction.
--   2. purge every module's rows (UserDeletionParticipant); purged_at is set in the same transaction.
--   3. anonymise the users row (DELETED), request COMPLETED with completed_at, outbox privacy.account.deleted.
-- A failed step rolls back, increments attempts and waits until next_attempt_at (exponential backoff).
-- V1 has no column for any of this; all additions are nullable or defaulted, so existing rows stay valid.
ALTER TABLE user_deletion_requests
    ADD COLUMN shredded_at     TIMESTAMPTZ,
    ADD COLUMN purged_at       TIMESTAMPTZ,
    ADD COLUMN attempts        INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

-- Steps happen in order and only while (or after) the request is processed; a scheduled request has a purge time.
ALTER TABLE user_deletion_requests
    ADD CONSTRAINT ck_user_deletion_scheduled_time
        CHECK (request_status NOT IN ('SCHEDULED', 'PROCESSING', 'COMPLETED') OR scheduled_purge_at IS NOT NULL),
    ADD CONSTRAINT ck_user_deletion_step_order
        CHECK (purged_at IS NULL OR shredded_at IS NOT NULL),
    ADD CONSTRAINT ck_user_deletion_steps_while_processing
        CHECK (shredded_at IS NULL OR request_status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    ADD CONSTRAINT ck_user_deletion_completed_steps
        CHECK (request_status <> 'COMPLETED' OR (purged_at IS NOT NULL AND completed_at IS NOT NULL));

COMMENT ON COLUMN user_deletion_requests.shredded_at IS
    'B6.2 step 1 done: account closed (users.deleted_at), sessions revoked, user data keys DESTROYED.';
COMMENT ON COLUMN user_deletion_requests.purged_at IS
    'B6.2 step 2 done: every module purged or pseudonymised the user''s rows.';
COMMENT ON COLUMN user_deletion_requests.next_attempt_at IS
    'B6.2: after a failed step, the executor retries no earlier than this (NULL = as soon as due).';
