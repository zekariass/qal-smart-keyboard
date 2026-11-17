-- B2.3: single-use attestation nonces (POST /auth/attestation-nonces) and a lookup of devices by installation id.
--
-- Only the SHA-256 of a nonce is stored (nonces are 256-bit random values, so an unkeyed hash is enough). A nonce can be
-- bound to the user who asked for it (bearer present) and to the client platform header; consumption is one atomic
-- UPDATE of consumed_at. Rows are purged by the retention scheduler (B6.3) shortly after expiry.
CREATE TABLE attestation_nonces (
    id          UUID PRIMARY KEY,
    nonce_hash  CHAR(64) NOT NULL UNIQUE CHECK (nonce_hash ~ '^[0-9a-f]{64}$'),
    user_id     UUID REFERENCES users(id) ON DELETE CASCADE,
    platform    VARCHAR(12) CHECK (platform IN ('ANDROID', 'IOS', 'WEB')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT ck_attestation_nonce_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_attestation_nonce_consumed CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX ix_attestation_nonces_expiry ON attestation_nonces (expires_at);

-- Guest creation is one account per installation: find the guest that owns an installation id (V1 only indexes
-- (user_id, installation_id)).
CREATE INDEX ix_user_devices_installation ON user_devices (installation_id);
