-- B6.3: retention scheduler (ADR 0020; SD 19.2).
--
-- 1. D31 (ADR 0007, ADR 0018): dictionary tombstones are purged after 180 days. A client whose sync cursor is older than
--    a purged tombstone would never learn of that delete, so the purge records, per user, the highest sync_version of any
--    tombstone it removed. The pull endpoint answers 410 SYNC_CURSOR_EXPIRED to a cursor that is behind it (full resync).
--    Raised only by the purge, in the same transaction as the delete, under the same per-user row lock a push takes.
ALTER TABLE user_dictionary_sync_state
    ADD COLUMN tombstone_floor BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_dictionary_sync_tombstone_floor CHECK (tombstone_floor >= 0 AND tombstone_floor <= sync_counter);

COMMENT ON COLUMN user_dictionary_sync_state.tombstone_floor IS
    'B6.3/D31: highest sync_version of a purged tombstone (0 = none purged); cursors behind it must resync.';

-- 2. Indexes for the purge queries (every batch is "oldest rows past the cutoff", so each needs an index on its time
--    column; tables that already have one are not repeated: otp_challenges, attestation_nonces, user_history_items,
--    ai_request_fingerprints, user_dictionary_applied_changes, processed_events, audit_events, ai_requests, DEAD outbox).
CREATE INDEX ix_user_sessions_expiry ON user_sessions (expires_at);
CREATE INDEX ix_password_reset_tokens_expiry ON password_reset_tokens (expires_at);
CREATE INDEX ix_user_dictionary_tombstones ON user_dictionary_entries (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX ix_outbox_events_published ON outbox_events (published_at) WHERE event_status = 'PUBLISHED';
CREATE INDEX ix_usage_reservations_closed ON usage_reservations (closed_at) WHERE reservation_status <> 'RESERVED';
CREATE INDEX ix_users_deleted_at ON users (deleted_at) WHERE account_status = 'DELETED';
CREATE INDEX ix_users_merged_updated ON users (updated_at) WHERE account_status = 'MERGED';
CREATE INDEX brin_voice_jobs_created ON voice_jobs USING BRIN (created_at);
CREATE INDEX brin_realtime_sessions_created ON realtime_sessions USING BRIN (created_at);
CREATE INDEX brin_payment_webhook_events_received ON payment_webhook_events USING BRIN (received_at);
