-- B3.6: personal dictionary sync (ADR 0018).
--
-- user_dictionary_sync_state: one row per user that has ever pushed a dictionary change.
--   * key_ciphertext: the per-user dictionary HMAC key (256 random bits; ADR 0007 D2) behind
--     word_hmac = HMAC-SHA256(key, ...). Stored only as ciphertext under the user's data key (bound to user, key version,
--     table, column and row id = user id); crypto-shred makes it unreadable. It is separate from the data key, so rotating
--     the data key never changes a hash, and it is never regenerated.
--   * sync_counter: the last sync_version handed out. A counter (not MAX over the rows) keeps versions monotonic even if
--     tombstones are purged later. The row is also the per-user lock that serialises pushes.
CREATE TABLE user_dictionary_sync_state (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    key_ciphertext  BYTEA NOT NULL,
    key_version     INTEGER NOT NULL CHECK (key_version > 0),
    sync_counter    BIGINT NOT NULL DEFAULT 0 CHECK (sync_counter >= 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Change ids already applied, so re-uploading a change set is a no-op (including a re-uploaded ADD that a later DELETE
-- superseded). Holds ids only, no words. Pruned by the push after 30 days.
CREATE TABLE user_dictionary_applied_changes (
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_change_id    UUID NOT NULL,
    applied_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, client_change_id)
);

CREATE INDEX ix_user_dictionary_applied_at ON user_dictionary_applied_changes (applied_at);
