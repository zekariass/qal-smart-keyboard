-- B3.4: destination profiles and Android app rules (System Design 10.3, contract /me/destination-profiles and
-- /me/app-destination-rules, ADR 0012).
--
-- The contract's DestinationProfileInput carries a default translation style and a glossary, and AppDestinationRule an
-- `enabled` flag; V1 has no columns for them. All are additive and nullable/defaulted, so existing rows stay valid.
ALTER TABLE user_destination_profiles
    ADD COLUMN default_style VARCHAR(16)
        CHECK (default_style IN ('LITERAL', 'NATURAL', 'POLITE', 'PROFESSIONAL', 'FRIENDLY')),
    ADD COLUMN glossary_id UUID REFERENCES glossaries(id) ON DELETE SET NULL;

ALTER TABLE user_app_destination_rules
    ADD COLUMN is_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- The rules document is a list of rows, so its optimistic-concurrency revision needs a row of its own (same pattern as
-- user_typing_language_revisions, V7). Absent = revision 0.
CREATE TABLE user_app_rule_revisions (
    user_id     UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    revision    INTEGER NOT NULL CHECK (revision >= 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The per-account key the client uses for appPackageHmac = HMAC-SHA256(key, packageName) (ADR 0012). Stored only as
-- ciphertext under the user's data key (bound to user, key version, table, column and row id = user id); crypto-shred
-- makes it unreadable together with the rest of the user's data.
CREATE TABLE user_app_rule_keys (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    key_ciphertext  BYTEA NOT NULL,
    key_version     INTEGER NOT NULL CHECK (key_version > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
