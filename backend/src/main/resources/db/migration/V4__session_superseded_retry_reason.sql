-- B2.4 follow-up (ADR 0007 D8): refresh-token grace window.
--
-- A client that lost the response of a refresh retries with the old (now ROTATED)
-- token. Within the grace window, and only if the successor issued by the lost
-- response was never presented, the server rotates again: it revokes that unused
-- successor and issues a new one instead of revoking the whole family.
--
-- The revoked successor needs its own revoked_reason, SUPERSEDED_RETRY, because the
-- reason drives behaviour and not only the audit trail: presenting a superseded
-- successor later means a second party holds tokens of the family, so it is treated
-- as reuse (the family is revoked), while a token revoked by LOGOUT/ADMIN/... only
-- gets UNAUTHENTICATED. None of the V1 reasons fits: TOKEN_REUSE would claim a theft
-- that did not happen and LOGOUT/ADMIN would lose the distinction.
--
-- V1 is immutable, so the column CHECK (auto-named by PostgreSQL) is replaced here.
-- NOT VALID + VALIDATE keeps the exclusive lock short; existing rows all satisfy it.

ALTER TABLE user_sessions
    DROP CONSTRAINT user_sessions_revoked_reason_check;

ALTER TABLE user_sessions
    ADD CONSTRAINT user_sessions_revoked_reason_check
        CHECK (revoked_reason IN ('LOGOUT', 'TOKEN_REUSE', 'DEVICE_REVOKED', 'PASSWORD_CHANGED', 'ADMIN',
                                  'ACCOUNT_DELETED', 'ACCOUNT_MERGED', 'SUPERSEDED_RETRY'))
        NOT VALID;

ALTER TABLE user_sessions
    VALIDATE CONSTRAINT user_sessions_revoked_reason_check;

COMMENT ON COLUMN user_sessions.last_used_at IS
    'When the refresh token was last presented; NULL = never presented (the refresh grace window only supersedes such a successor).';
