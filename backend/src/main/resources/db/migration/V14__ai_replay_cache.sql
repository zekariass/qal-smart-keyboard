-- B5.4: AI idempotency (plan rule N5; SD 8.3, SD 11.6; contract IdempotencyKey; ADR 0017 "Replay cache (B5.4)").
--
-- At-most-once charging is metering's job (usage_reservations, unique per user and Idempotency-Key, V9). These two
-- tables add what metering cannot know. Both are keyed by the request id = usage_reservations.id (a logical reference:
-- the AI gateway does not reach into the usage module's tables), which is unique per (user, key).

-- What a key was used for: SHA-256 of the canonical request body, never stored as it is but sealed with the user's
-- data key (UserDataEncryption, bound to user, key version, this table, fingerprint_ciphertext and request_id), so the
-- column is no text oracle and is crypto-shredded with the user. Written right after the reservation, before the
-- provider call, so a different body under the same key is refused even while the original is in flight. Kept for
-- qal.ai.idempotency.fingerprint-retention (24 h, SD 8.3), then swept. No text, no idempotency key value.
CREATE TABLE ai_request_fingerprints (
    request_id              UUID PRIMARY KEY,
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    fingerprint_ciphertext  BYTEA NOT NULL,
    key_version             INTEGER NOT NULL CHECK (key_version > 0),
    created_at              TIMESTAMPTZ NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_ai_request_fingerprint_expiry CHECK (expires_at > created_at)
);

COMMENT ON TABLE ai_request_fingerprints IS
    'B5.4: sealed request-body digest per AI request id, to detect IDEMPOTENCY_KEY_REUSED; swept after 24 h.';

CREATE INDEX ix_ai_request_fingerprints_user ON ai_request_fingerprints (user_id);
CREATE INDEX ix_ai_request_fingerprints_expiry ON ai_request_fingerprints (expires_at);

-- The ephemeral replay cache (SD 11.6 "encrypted ephemeral result cache, <= 10 minutes"): the response of a settled
-- request, serialised as the contract JSON and sealed with the user's data key (bound to user, key version, this
-- table, response_ciphertext and request_id). Never plaintext. Reads ignore rows past expires_at; the sweeper deletes
-- them. The CHECK enforces the 10-minute ceiling whatever the configuration says.
--
-- UNLOGGED on purpose: its rows never reach the WAL, so they are not shipped to WAL archives, point-in-time-recovery
-- backups, physical base backups or streaming replicas, where they would outlive the 10 minutes. A crash empties the
-- table, and an empty cache only means a replay is recomputed without a charge.
CREATE UNLOGGED TABLE ai_replay_cache (
    request_id          UUID PRIMARY KEY,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    response_ciphertext BYTEA NOT NULL,
    key_version         INTEGER NOT NULL CHECK (key_version > 0),
    created_at          TIMESTAMPTZ NOT NULL,
    expires_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_ai_replay_cache_ttl
        CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '10 minutes')
);

COMMENT ON TABLE ai_replay_cache IS
    'B5.4: encrypted AI responses for idempotent replay; <= 10 min, UNLOGGED (never in WAL, backups or replicas).';

CREATE INDEX ix_ai_replay_cache_user ON ai_replay_cache (user_id);
CREATE INDEX ix_ai_replay_cache_expiry ON ai_replay_cache (expires_at);
