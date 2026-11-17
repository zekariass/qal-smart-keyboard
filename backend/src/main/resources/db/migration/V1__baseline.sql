-- ============================================================================
-- Qal Smart Keyboard — PostgreSQL baseline schema
-- Version: 2.0 (consolidated baseline; ship as Flyway V1__baseline.sql)
-- Target:  PostgreSQL 17+ (18 recommended). Uses gen_random_uuid(), UNIQUE
--          NULLS NOT DISTINCT (PG15+) and the btree_gist extension.
-- Spec:    Ethiopian_AI_Keyboard_Architecture_Review_v2_0.md (D1–D32) and
--          Ethiopian_AI_Keyboard_System_Design_v2_0.md.
--
-- This file replaces the v1.2 -> v1.3 -> v1.4 migration chain. Nothing was
-- deployed, so every table is written in its final shape. After this baseline
-- is applied to ANY shared environment it is immutable; all later changes are
-- forward-only Flyway migrations (V2__..., V3__...).
--
-- Privacy rules encoded by design:
--   * No keystroke, raw draft, transcript or audio table exists. Ordinary typing
--     and transliteration are local; the backend only distributes resources
--     and syncs preferences.
--   * ai_requests, voice_jobs and realtime_sessions hold operational metadata
--     only (counts, durations, cost, status) — never input/output text.
--   * Anything user-authored that is stored at all (history, synced dictionary
--     words, destination-profile labels, style sign-offs, feedback comments) is
--     application-encrypted with a per-user data key (user_data_keys). Destroying
--     that key crypto-shreds the data, including copies inside backups.
--   * Contacts and host-app package names are never uploaded in plaintext; only
--     keyed HMACs computed on-device are synced (destination profiles, D10).
--   * Consent is an append-only decision log; the latest row per code wins.
--   * Billing separates products/prices/entitlements from payment rails. ETB and
--     country/channel-specific price books are first-class. Money is integer
--     minor units; AI credits are NUMERIC(18,6) because per-second voice usage
--     costs fractional credits.
--   * Payment providers, merchant accounts and account-enabled methods all fail
--     closed: seeded inactive, checkout requires every gate to be open.
--   * App-store purchases (store_purchases) and gateway orders (payment_orders)
--     are separate rails that converge on user_subscriptions.
--
-- High-volume append tables (ai_requests, usage_events, audit_events,
-- outbox_events) take APPLICATION-GENERATED UUIDv7 primary keys (or PG18
-- uuidv7()) for index locality, use BRIN indexes on created_at, and are NEVER
-- the target of a foreign key. Their references to other rows are logical so
-- they can be range-partitioned later without rewriting dependants (D18).
-- ============================================================================

BEGIN;

-- Needed by the exclusion constraint that prevents overlapping active prices
-- (equality on uuid/text columns inside a GiST index).
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- 1. Identity, authentication, devices and per-user encryption keys
--
-- Offline typing never requires an account (D3). A GUEST account is created per
-- installation only for the small AI trial and only after device attestation.
-- Phone OTP is first-class because phone numbers are the dominant identity in
-- Ethiopia. Admins authenticate with WORKFORCE_OIDC (never consumer login).
-- ---------------------------------------------------------------------------

CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_type        VARCHAR(20) NOT NULL DEFAULT 'REGISTERED'
                        CHECK (account_type IN ('REGISTERED', 'GUEST')),
    email               VARCHAR(320),
    email_normalized    VARCHAR(320),
    email_verified_at   TIMESTAMPTZ,
    phone_e164          VARCHAR(16),
    phone_verified_at   TIMESTAMPTZ,
    display_name        VARCHAR(120),
    -- Billing/market country. Decided from storefront, user selection, SIM
    -- country or billing evidence — never from IP geolocation alone.
    market_country_code CHAR(2) CHECK (market_country_code IS NULL OR market_country_code ~ '^[A-Z]{2}$'),
    market_source       VARCHAR(20)
                        CHECK (market_source IN ('USER_SELECTED', 'STOREFRONT', 'SIM_COUNTRY', 'BILLING', 'ADMIN')),
    ui_locale           VARCHAR(35),
    -- Used for DAILY quota windows and reminder scheduling (D6).
    time_zone           VARCHAR(64) NOT NULL DEFAULT 'Africa/Addis_Ababa',
    account_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                        CHECK (account_status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DELETION_PENDING', 'DELETED', 'MERGED')),
    -- Onboarding age gate (minimum 13 or local digital age of consent; D26).
    age_confirmed_at    TIMESTAMPTZ,
    -- Set on a GUEST row when it is upgraded/merged into a registered user.
    merged_into_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT ck_users_email_normalized
        CHECK (email_normalized IS NULL OR email_normalized = LOWER(BTRIM(email_normalized))),
    CONSTRAINT ck_users_email_pair
        CHECK ((email IS NULL) = (email_normalized IS NULL)),
    CONSTRAINT ck_users_phone_e164
        CHECK (phone_e164 IS NULL OR phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
    CONSTRAINT ck_users_market_pair
        CHECK ((market_country_code IS NULL) = (market_source IS NULL)),
    CONSTRAINT ck_users_guest_has_no_identity
        CHECK (account_type = 'REGISTERED' OR (email IS NULL AND phone_e164 IS NULL)),
    CONSTRAINT ck_users_merge_only_guest
        CHECK (merged_into_user_id IS NULL OR (account_type = 'GUEST' AND merged_into_user_id <> id))
);

CREATE UNIQUE INDEX ux_users_email_normalized
    ON users (email_normalized)
    WHERE email_normalized IS NOT NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX ux_users_phone_e164
    ON users (phone_e164)
    WHERE phone_e164 IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX ix_users_status_created
    ON users (account_status, created_at DESC);

CREATE TABLE user_password_credentials (
    user_id             UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    password_hash       TEXT NOT NULL,          -- Argon2id/bcrypt encoded hash only.
    password_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    failed_attempts     INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_auth_identities (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    identity_provider   VARCHAR(20) NOT NULL
                        CHECK (identity_provider IN ('EMAIL', 'PHONE', 'GOOGLE', 'APPLE', 'WORKFORCE_OIDC')),
    -- PHONE: E.164 number; GOOGLE/APPLE/WORKFORCE_OIDC: the IdP 'sub' claim.
    provider_subject    VARCHAR(255) NOT NULL,
    provider_email      VARCHAR(320),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at        TIMESTAMPTZ,
    CONSTRAINT ux_auth_provider_subject UNIQUE (identity_provider, provider_subject),
    CONSTRAINT ux_auth_user_provider UNIQUE (user_id, identity_provider)
);

CREATE INDEX ix_auth_identities_user
    ON user_auth_identities (user_id);

-- One-time-password challenges. The phone number is stored only as a keyed
-- HMAC (rate limiting/lookup) and the code only as a hash. Rows are purged
-- shortly after expiry.
CREATE TABLE otp_challenges (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID REFERENCES users(id) ON DELETE CASCADE,
    phone_hash          CHAR(64) NOT NULL CHECK (phone_hash ~ '^[0-9a-f]{64}$'),
    code_hash           TEXT NOT NULL,
    purpose             VARCHAR(24) NOT NULL
                        CHECK (purpose IN ('SIGN_IN', 'PHONE_VERIFICATION', 'PHONE_CHANGE', 'ACCOUNT_RECOVERY')),
    delivery_channel    VARCHAR(12) NOT NULL DEFAULT 'SMS'
                        CHECK (delivery_channel IN ('SMS', 'VOICE_CALL')),
    attempts            SMALLINT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts        SMALLINT NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    ip_address          INET,
    expires_at          TIMESTAMPTZ NOT NULL,
    consumed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_otp_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_otp_attempts CHECK (attempts <= max_attempts)
);

CREATE INDEX ix_otp_challenges_phone_created
    ON otp_challenges (phone_hash, created_at DESC);

CREATE INDEX ix_otp_challenges_expiry
    ON otp_challenges (expires_at);

CREATE TABLE user_devices (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    installation_id     UUID NOT NULL,
    platform            VARCHAR(12) NOT NULL CHECK (platform IN ('ANDROID', 'IOS')),
    distribution_channel VARCHAR(24) NOT NULL DEFAULT 'OTHER'
                        CHECK (distribution_channel IN ('GOOGLE_PLAY', 'DIRECT_DOWNLOAD', 'APPLE_APP_STORE', 'TESTFLIGHT', 'ENTERPRISE', 'OTHER')),
    device_name         VARCHAR(160),
    app_version         VARCHAR(40),
    os_version          VARCHAR(80),
    -- iOS only: whether the keyboard extension currently has Full Access.
    -- Without it the extension cannot use the network, App Group container or
    -- shared keychain, so it runs on bundled packs only (D15).
    keyboard_full_access BOOLEAN,
    -- Device integrity (Play Integrity / App Attest). Guest AI requires VALID (D31).
    attestation_provider VARCHAR(20) CHECK (attestation_provider IN ('PLAY_INTEGRITY', 'APP_ATTEST')),
    attestation_status  VARCHAR(20) NOT NULL DEFAULT 'NOT_CHECKED'
                        CHECK (attestation_status IN ('NOT_CHECKED', 'VALID', 'FAILED', 'UNSUPPORTED')),
    attestation_checked_at TIMESTAMPTZ,
    push_provider       VARCHAR(20) CHECK (push_provider IN ('FCM', 'APNS')),
    push_token          TEXT,
    device_status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (device_status IN ('ACTIVE', 'REVOKED')),
    last_seen_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_user_device_installation UNIQUE (user_id, installation_id),
    CONSTRAINT ck_device_push_provider_platform CHECK (
        push_provider IS NULL
        OR (platform = 'ANDROID' AND push_provider = 'FCM')
        OR (platform = 'IOS' AND push_provider = 'APNS')
    ),
    CONSTRAINT ck_device_full_access_ios_only CHECK (keyboard_full_access IS NULL OR platform = 'IOS'),
    CONSTRAINT ck_device_attestation_provider CHECK (
        attestation_provider IS NULL
        OR (platform = 'ANDROID' AND attestation_provider = 'PLAY_INTEGRITY')
        OR (platform = 'IOS' AND attestation_provider = 'APP_ATTEST')
    ),
    CONSTRAINT ck_device_attestation_checked CHECK (
        attestation_status IN ('NOT_CHECKED', 'UNSUPPORTED') OR attestation_checked_at IS NOT NULL
    )
);

CREATE INDEX ix_user_devices_user_status
    ON user_devices (user_id, device_status);

CREATE UNIQUE INDEX ux_user_devices_push_token
    ON user_devices (push_token)
    WHERE push_token IS NOT NULL AND device_status = 'ACTIVE';

-- Refresh-token rotation with reuse detection (D4). Every refresh creates a new
-- row in the same token_family_id and marks the old one ROTATED. Presenting a
-- ROTATED token again sets reuse_detected_at and revokes the whole family.
CREATE TABLE user_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id           UUID REFERENCES user_devices(id) ON DELETE SET NULL,
    token_family_id     UUID NOT NULL,
    refresh_token_hash  CHAR(64) NOT NULL UNIQUE CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    session_status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (session_status IN ('ACTIVE', 'ROTATED', 'REVOKED', 'EXPIRED')),
    replaced_by_session_id UUID REFERENCES user_sessions(id) ON DELETE SET NULL,
    rotated_at          TIMESTAMPTZ,
    reuse_detected_at   TIMESTAMPTZ,
    revoked_reason      VARCHAR(40)
                        CHECK (revoked_reason IN ('LOGOUT', 'TOKEN_REUSE', 'DEVICE_REVOKED', 'PASSWORD_CHANGED', 'ADMIN', 'ACCOUNT_DELETED', 'ACCOUNT_MERGED')),
    ip_address          INET,
    user_agent          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    CONSTRAINT ck_session_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_session_rotation CHECK (
        session_status <> 'ROTATED' OR (rotated_at IS NOT NULL AND replaced_by_session_id IS NOT NULL)
    ),
    CONSTRAINT ck_session_revocation CHECK (session_status <> 'REVOKED' OR revoked_at IS NOT NULL)
);

CREATE INDEX ix_user_sessions_user_status
    ON user_sessions (user_id, session_status, expires_at DESC);

CREATE INDEX ix_user_sessions_family
    ON user_sessions (token_family_id);

CREATE TABLE password_reset_tokens (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash          CHAR(64) NOT NULL UNIQUE,
    expires_at          TIMESTAMPTZ NOT NULL,
    consumed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_password_reset_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_password_reset_user_expiry
    ON password_reset_tokens (user_id, expires_at DESC);

-- Per-user envelope encryption (D17). wrapped_dek is the user's data key
-- encrypted by a KMS key; plaintext DEKs never touch the database. Every
-- *_ciphertext column elsewhere carries the key_version it was sealed with.
-- On account deletion the key is DESTROYED (wrapped_dek nulled), which renders
-- all ciphertext — including backup copies — unreadable.
CREATE TABLE user_data_keys (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key_version         INTEGER NOT NULL CHECK (key_version > 0),
    wrapped_dek         BYTEA,
    kms_key_id          VARCHAR(255) NOT NULL,
    key_status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (key_status IN ('ACTIVE', 'ROTATED', 'DESTROYED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    rotated_at          TIMESTAMPTZ,
    destroyed_at        TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_user_data_key_version UNIQUE (user_id, key_version),
    CONSTRAINT ck_user_data_key_destroyed CHECK (
        (key_status = 'DESTROYED' AND wrapped_dek IS NULL AND destroyed_at IS NOT NULL)
        OR (key_status <> 'DESTROYED' AND wrapped_dek IS NOT NULL AND destroyed_at IS NULL)
    )
);

CREATE UNIQUE INDEX ux_user_data_keys_one_active
    ON user_data_keys (user_id)
    WHERE key_status = 'ACTIVE';

COMMENT ON TABLE user_data_keys IS
    'KMS-wrapped per-user data keys. Destroying a key crypto-shreds history, synced dictionary, profile labels, style text and feedback comments.';

-- ---------------------------------------------------------------------------
-- 2. Organisations and partner API clients (D9)
--
-- Organisations buy seat-based plans (section 14) and own partner API clients
-- and glossaries. API keys are stored as a public prefix plus SHA-256 hash; the
-- full key is shown once at creation and never stored.
-- ---------------------------------------------------------------------------

CREATE TABLE organizations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_slug                VARCHAR(60) NOT NULL UNIQUE CHECK (org_slug ~ '^[a-z0-9][a-z0-9-]{1,58}[a-z0-9]$'),
    display_name            VARCHAR(160) NOT NULL,
    legal_name              VARCHAR(255),
    country_code            CHAR(2) CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),
    org_status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (org_status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
    -- Data policy enforced by AI routing: zero-retention providers only, and
    -- only in allowed processing regions (empty array = no restriction).
    zero_retention_required BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_processing_regions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    billing_email           VARCHAR(320),
    created_by_user_id      UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE organization_members (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    member_role             VARCHAR(12) NOT NULL DEFAULT 'MEMBER'
                            CHECK (member_role IN ('OWNER', 'ADMIN', 'BILLING', 'MEMBER')),
    member_status           VARCHAR(12) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (member_status IN ('ACTIVE', 'SUSPENDED')),
    joined_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_organization_member UNIQUE (organization_id, user_id)
);

CREATE INDEX ix_organization_members_user
    ON organization_members (user_id);

CREATE TABLE organization_invitations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    invited_email_normalized VARCHAR(320),
    invited_phone_e164      VARCHAR(16) CHECK (invited_phone_e164 IS NULL OR invited_phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
    member_role             VARCHAR(12) NOT NULL DEFAULT 'MEMBER'
                            CHECK (member_role IN ('ADMIN', 'BILLING', 'MEMBER')),
    token_hash              CHAR(64) NOT NULL UNIQUE,
    invitation_status       VARCHAR(12) NOT NULL DEFAULT 'PENDING'
                            CHECK (invitation_status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    invited_by_user_id      UUID REFERENCES users(id) ON DELETE SET NULL,
    accepted_by_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    accepted_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_invitation_target CHECK (num_nonnulls(invited_email_normalized, invited_phone_e164) >= 1),
    CONSTRAINT ck_invitation_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_organization_invitations_org_status
    ON organization_invitations (organization_id, invitation_status);

CREATE TABLE api_clients (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    client_code             VARCHAR(60) NOT NULL,
    display_name            VARCHAR(160) NOT NULL,
    environment             VARCHAR(16) NOT NULL CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    client_status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                            CHECK (client_status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    created_by_user_id      UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_api_client_code UNIQUE (organization_id, client_code),
    CONSTRAINT ux_api_client_org_binding UNIQUE (id, organization_id)
);

CREATE TABLE api_keys (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    api_client_id           UUID NOT NULL REFERENCES api_clients(id) ON DELETE CASCADE,
    -- Public, non-secret identifier shown in dashboards/logs (e.g. 'ek_live_7Hq2').
    key_prefix              VARCHAR(24) NOT NULL UNIQUE,
    key_hash                CHAR(64) NOT NULL UNIQUE CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    display_name            VARCHAR(120),
    scopes                  TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    rate_limit_per_minute   INTEGER CHECK (rate_limit_per_minute IS NULL OR rate_limit_per_minute > 0),
    created_by_user_id      UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at            TIMESTAMPTZ,
    expires_at              TIMESTAMPTZ,
    revoked_at              TIMESTAMPTZ,
    CONSTRAINT ck_api_key_scopes CHECK (
        scopes <@ ARRAY['translate', 'transform', 'transliterate', 'detect_language', 'speech', 'usage_read']::TEXT[]
    ),
    CONSTRAINT ck_api_key_expiry CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE INDEX ix_api_keys_client
    ON api_keys (api_client_id)
    WHERE revoked_at IS NULL;

-- ---------------------------------------------------------------------------
-- 3. Languages, signed language resources, transliteration and term data
--
-- Large dictionaries/models are versioned files on object storage/CDN; this
-- section stores metadata, checksums, signatures and rollout state only.
-- ---------------------------------------------------------------------------

CREATE TABLE supported_languages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    language_code       VARCHAR(35) NOT NULL UNIQUE,   -- BCP 47
    english_name        VARCHAR(100) NOT NULL,
    native_name         VARCHAR(100),
    script_code         VARCHAR(20),                   -- ISO 15924
    writing_direction   VARCHAR(3) NOT NULL DEFAULT 'LTR'
                        CHECK (writing_direction IN ('LTR', 'RTL')),
    market_group        VARCHAR(40) NOT NULL DEFAULT 'ETHIOPIA',
    -- Product readiness. Promote to GA only after the language quality gates
    -- in System Design §13 pass; a provider accepting the code is not enough.
    support_level       VARCHAR(10) NOT NULL DEFAULT 'PLANNED'
                        CHECK (support_level IN ('PLANNED', 'ALPHA', 'BETA', 'GA')),
    is_launch_language  BOOLEAN NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order          INTEGER NOT NULL DEFAULT 100,
    metadata            JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_supported_languages_active_order
    ON supported_languages (is_active, sort_order, english_name);

-- Initial catalogue. Launch languages start at BETA until native-speaker gates
-- pass. Arabic is RTL (v1.4 bug F13 seeded it LTR). `om` = Afaan Oromoo (Qubee).
INSERT INTO supported_languages
    (language_code, english_name, native_name, script_code, writing_direction, market_group,
     support_level, is_launch_language, sort_order)
VALUES
    ('am',  'Amharic',      'አማርኛ',           'Ethi', 'LTR', 'ETHIOPIA',           'BETA',    TRUE,  10),
    ('ti',  'Tigrinya',     'ትግርኛ',           'Ethi', 'LTR', 'ETHIOPIA',           'BETA',    TRUE,  20),
    ('om',  'Afaan Oromoo', 'Afaan Oromoo',    'Latn', 'LTR', 'ETHIOPIA',           'BETA',    TRUE,  30),
    ('en',  'English',      'English',         'Latn', 'LTR', 'GLOBAL',             'BETA',    TRUE,  40),
    ('so',  'Somali',       'Soomaali',        'Latn', 'LTR', 'AFRICA_EXPANSION',   'PLANNED', FALSE, 50),
    ('aa',  'Afar',         'Qafár af',        'Latn', 'LTR', 'ETHIOPIA_EXPANSION', 'PLANNED', FALSE, 60),
    ('sid', 'Sidama',       'Sidaamu Afoo',    'Latn', 'LTR', 'ETHIOPIA_EXPANSION', 'PLANNED', FALSE, 70),
    ('wal', 'Wolaytta',     'Wolayttatto Doonaa', 'Latn', 'LTR', 'ETHIOPIA_EXPANSION', 'PLANNED', FALSE, 80),
    ('sw',  'Swahili',      'Kiswahili',       'Latn', 'LTR', 'AFRICA_EXPANSION',   'PLANNED', FALSE, 90),
    ('ar',  'Arabic',       'العربية',          'Arab', 'RTL', 'REGIONAL_EXPANSION', 'PLANNED', FALSE, 100),
    ('gez', 'Ge''ez',       'ግዕዝ',            'Ethi', 'LTR', 'ETHIOPIA_EXPANSION', 'PLANNED', FALSE, 110)
ON CONFLICT (language_code) DO NOTHING;

-- Public keys that clients pin to verify signed resource manifests (D16).
-- Private keys live in KMS/HSM only. Rotation: add a new ACTIVE key, publish
-- resources with it, move the old key to RETIRING, later REVOKED.
CREATE TABLE resource_signing_keys (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_id              VARCHAR(120) NOT NULL UNIQUE,
    algorithm           VARCHAR(16) NOT NULL DEFAULT 'ED25519' CHECK (algorithm IN ('ED25519')),
    public_key          TEXT NOT NULL,                 -- base64 raw public key
    key_status          VARCHAR(12) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (key_status IN ('ACTIVE', 'RETIRING', 'REVOKED')),
    valid_from          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_signing_key_validity CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE TABLE language_resources (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    language_id         UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    resource_type       VARCHAR(30) NOT NULL
                        CHECK (resource_type IN (
                            'KEYBOARD_LAYOUT', 'TRANSLITERATION_RULES', 'DICTIONARY',
                            'FREQUENCY_LIST', 'PREDICTION_MODEL', 'AUTOCORRECTION_MODEL',
                            'PROTECTED_TERMS', 'PRONUNCIATION', 'EMOJI_KEYWORDS', 'GLOSSARY',
                            'SPELL_MODEL', 'LANGUAGE_ID_MODEL', 'TRANSLATION_MODEL',
                            'ASR_MODEL', 'TTS_VOICE', 'ON_DEVICE_LLM', 'OTHER'
                        )),
    resource_version    VARCHAR(40) NOT NULL,          -- semantic version
    platform            VARCHAR(12) NOT NULL DEFAULT 'SHARED'
                        CHECK (platform IN ('SHARED', 'ANDROID', 'IOS')),
    release_channel     VARCHAR(10) NOT NULL DEFAULT 'INTERNAL'
                        CHECK (release_channel IN ('INTERNAL', 'BETA', 'STABLE')),
    -- Staged rollout: clients hash (installation_id, resource) into 0..99.
    rollout_percentage  SMALLINT NOT NULL DEFAULT 0 CHECK (rollout_percentage BETWEEN 0 AND 100),
    -- Non-null means this artifact is a delta patch applied on top of base.
    base_resource_id    UUID REFERENCES language_resources(id) ON DELETE RESTRICT,
    artifact_uri        TEXT NOT NULL,
    sha256_checksum     CHAR(64) NOT NULL,
    size_bytes          BIGINT CHECK (size_bytes IS NULL OR size_bytes >= 0),
    min_app_version     VARCHAR(40),
    -- e.g. {"minRamMb":3072,"accelerators":["NNAPI","COREML"],"minOs":{"android":29},
    --       "wifiOnly":true,"iosExtensionAllowed":false}
    device_requirements JSONB NOT NULL DEFAULT '{}'::JSONB,
    -- SPDX-style licence id of the artifact (models/datasets must pass the
    -- commercial-use licence gate before STABLE; see language_datasets).
    license_code        VARCHAR(60),
    signature_key_id    UUID REFERENCES resource_signing_keys(id) ON DELETE RESTRICT,
    manifest_signature  TEXT,
    manifest_json       JSONB NOT NULL DEFAULT '{}'::JSONB,
    release_notes       TEXT,
    publication_status  VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                        CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'RETIRED', 'REVOKED')),
    published_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_language_resource_version UNIQUE (language_id, resource_type, resource_version, platform),
    CONSTRAINT ux_language_resource_id_language UNIQUE (id, language_id),
    CONSTRAINT ck_language_resource_sha256 CHECK (sha256_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_language_resource_signature_pair CHECK (
        (signature_key_id IS NULL) = (manifest_signature IS NULL)
    ),
    CONSTRAINT ck_language_resource_published CHECK (
        publication_status <> 'PUBLISHED'
        OR (published_at IS NOT NULL AND signature_key_id IS NOT NULL)
    ),
    CONSTRAINT ck_language_resource_delta_base CHECK (base_resource_id IS NULL OR base_resource_id <> id)
);

CREATE INDEX ix_language_resources_published
    ON language_resources (language_id, publication_status, release_channel, resource_type, published_at DESC);

-- Language-specific input/transliteration profiles. Latin-QWERTY -> Ge'ez
-- conversion runs locally in the shared Rust keyboard-core from a published
-- TRANSLITERATION_RULES resource. Amharic and Tigrinya have separate profiles:
-- shared script does not mean shared romanisation (Tigrinya has ቐ/ኸ series and
-- different conventions). Profiles stay DRAFT until native-speaker review and
-- golden-vector tests pass.
CREATE TABLE transliteration_profiles (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    language_id             UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    profile_code            VARCHAR(100) NOT NULL,
    display_name            VARCHAR(160) NOT NULL,
    source_script_code      VARCHAR(20) NOT NULL DEFAULT 'Latn',
    target_script_code      VARCHAR(20) NOT NULL,
    input_scheme            VARCHAR(24) NOT NULL DEFAULT 'PHONETIC'
                            CHECK (input_scheme IN ('PHONETIC', 'KEYMAP', 'ROMANIZATION')),
    profile_version         VARCHAR(40) NOT NULL,
    rules_resource_id       UUID,
    normalization_form      VARCHAR(8) NOT NULL DEFAULT 'NFC'
                            CHECK (normalization_form IN ('NFC', 'NFD')),
    ambiguity_strategy      VARCHAR(24) NOT NULL DEFAULT 'CANDIDATES'
                            CHECK (ambiguity_strategy IN ('CANDIDATES', 'PREFERRED_CANDIDATE', 'PRESERVE_INPUT')),
    -- Reverse (Ge'ez -> Latin) is used for pronunciation hints and romanised display.
    supports_reverse        BOOLEAN NOT NULL DEFAULT FALSE,
    profile_status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                            CHECK (profile_status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_translit_profile_version UNIQUE (language_id, profile_code, profile_version),
    CONSTRAINT ux_translit_profile_id_language UNIQUE (id, language_id),
    CONSTRAINT fk_translit_profile_rules_resource
        FOREIGN KEY (rules_resource_id, language_id)
        REFERENCES language_resources (id, language_id) ON DELETE RESTRICT,
    CONSTRAINT ck_translit_profile_scripts_differ CHECK (source_script_code <> target_script_code)
);

CREATE INDEX ix_transliteration_profiles_active
    ON transliteration_profiles (language_id, profile_code)
    WHERE profile_status = 'ACTIVE';

-- DRAFT seed rows describe the intended capability without pretending an
-- unreviewed mapping is production-ready (activation trigger in section 19).
INSERT INTO transliteration_profiles
    (language_id, profile_code, display_name, source_script_code, target_script_code,
     input_scheme, profile_version, supports_reverse, profile_status, metadata)
SELECT l.id, seed.profile_code, seed.display_name, 'Latn', 'Ethi', 'PHONETIC', '1.0.0', TRUE, 'DRAFT',
       jsonb_build_object(
           'keyLayout', 'QWERTY',
           'conversion', 'LATIN_PHONETIC_TO_GEEZ',
           'baseScheme', 'SERA-derived, pending native-speaker review',
           'requiresNativeSpeakerApproval', TRUE,
           'requiresGoldenTestVectors', TRUE,
           'description', seed.description
       )
FROM (VALUES
    ('am', 'AM_PHONETIC_QWERTY_TO_ETHIOPIC', 'Amharic phonetic QWERTY to Ge''ez',
     'Latin QWERTY phonetic input converted locally to Amharic Ethiopic composing text. Homophone series (ሀ/ሐ/ኀ, ሰ/ሠ, አ/ዐ, ጸ/ፀ) are offered as candidates ranked by dictionary frequency.'),
    ('ti', 'TI_PHONETIC_QWERTY_TO_ETHIOPIC', 'Tigrinya phonetic QWERTY to Ge''ez',
     'Latin QWERTY phonetic input converted locally to Tigrinya Ethiopic composing text, including ቐ and ኸ series.')
) AS seed(language_code, profile_code, display_name, description)
JOIN supported_languages l ON l.language_code = seed.language_code
ON CONFLICT (language_id, profile_code, profile_version) DO NOTHING;

-- Operational install telemetry per device (no content). Used for rollout
-- health and to know which versions must remain downloadable.
CREATE TABLE device_language_resource_installations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id           UUID NOT NULL REFERENCES user_devices(id) ON DELETE CASCADE,
    resource_id         UUID NOT NULL REFERENCES language_resources(id) ON DELETE CASCADE,
    installed_version   VARCHAR(40) NOT NULL,
    installation_status VARCHAR(20) NOT NULL DEFAULT 'INSTALLED'
                        CHECK (installation_status IN ('PENDING', 'INSTALLED', 'FAILED', 'REMOVED', 'ROLLED_BACK')),
    checksum_verified   BOOLEAN NOT NULL DEFAULT FALSE,
    signature_verified  BOOLEAN NOT NULL DEFAULT FALSE,
    installed_at        TIMESTAMPTZ,
    last_error_code     VARCHAR(80),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_device_resource_install UNIQUE (device_id, resource_id),
    CONSTRAINT ck_install_verified CHECK (
        installation_status <> 'INSTALLED' OR (checksum_verified AND signature_verified)
    )
);

CREATE INDEX ix_resource_installations_device_status
    ON device_language_resource_installations (device_id, installation_status);

-- Provenance and licensing of every corpus, wordlist, gazetteer or model input
-- (D27, F22). Several attractive open models/datasets are NON-COMMERCIAL;
-- only rows whose permitted_uses include COMMERCIAL may feed shipped products.
CREATE TABLE language_datasets (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_code        VARCHAR(80) NOT NULL UNIQUE,
    name                VARCHAR(200) NOT NULL,
    language_id         UUID REFERENCES supported_languages(id) ON DELETE RESTRICT,  -- NULL = multilingual
    description         TEXT,
    source_url          TEXT,
    license_code        VARCHAR(60) NOT NULL,          -- SPDX id or 'PROPRIETARY'/'UNKNOWN'
    license_url         TEXT,
    permitted_uses      TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    consent_basis       VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
                        CHECK (consent_basis IN ('PUBLIC_DOMAIN', 'OPEN_LICENSE', 'COMMERCIAL_LICENSE',
                                                 'CONTRIBUTOR_CONSENT', 'OWN_CREATION', 'UNKNOWN')),
    review_status       VARCHAR(16) NOT NULL DEFAULT 'PENDING_REVIEW'
                        CHECK (review_status IN ('PENDING_REVIEW', 'APPROVED', 'RESTRICTED', 'REJECTED')),
    reviewed_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_dataset_permitted_uses CHECK (
        permitted_uses <@ ARRAY['TRAINING', 'EVALUATION', 'DICTIONARY', 'COMMERCIAL', 'RESEARCH_ONLY']::TEXT[]
    ),
    CONSTRAINT ck_dataset_research_only CHECK (
        NOT ('RESEARCH_ONLY' = ANY (permitted_uses) AND 'COMMERCIAL' = ANY (permitted_uses))
    ),
    CONSTRAINT ck_dataset_approval CHECK (
        review_status IN ('PENDING_REVIEW') OR (reviewed_at IS NOT NULL AND reviewed_by_user_id IS NOT NULL)
    )
);

-- Curated terms only (names, places, cultural expressions, protected words).
-- Large dictionaries belong in versioned resource files, not millions of rows.
CREATE TABLE language_terms (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    language_id         UUID NOT NULL REFERENCES supported_languages(id) ON DELETE CASCADE,
    surface_form        TEXT NOT NULL,
    normalized_form     TEXT NOT NULL,
    term_category       VARCHAR(30) NOT NULL DEFAULT 'GENERAL'
                        CHECK (term_category IN ('GENERAL', 'PERSON_NAME', 'PLACE_NAME', 'CULTURAL', 'RELIGIOUS',
                                                 'TECHNICAL', 'BRAND', 'PROTECTED')),
    -- e.g. [{"form":"Abebe","script":"Latn"},{"form":"ኣበበ","script":"Ethi","region":"ER"}]
    variants            JSONB NOT NULL DEFAULT '[]'::JSONB,
    region_code         VARCHAR(10),
    name_gender         VARCHAR(16) CHECK (name_gender IN ('MASCULINE', 'FEMININE', 'UNISEX')),
    frequency_rank      INTEGER CHECK (frequency_rank IS NULL OR frequency_rank > 0),
    is_protected        BOOLEAN NOT NULL DEFAULT FALSE,
    source              VARCHAR(30) NOT NULL DEFAULT 'ADMIN'
                        CHECK (source IN ('ADMIN', 'CURATED_CORPUS', 'COMMUNITY')),
    dataset_id          UUID REFERENCES language_datasets(id) ON DELETE SET NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'RETIRED')),
    metadata            JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_language_term UNIQUE (language_id, normalized_form, term_category),
    CONSTRAINT ck_language_term_name_gender CHECK (name_gender IS NULL OR term_category = 'PERSON_NAME'),
    CONSTRAINT ck_language_term_variants_array CHECK (jsonb_typeof(variants) = 'array')
);

CREATE INDEX ix_language_terms_lookup
    ON language_terms (language_id, normalized_form)
    WHERE status = 'ACTIVE';

-- Moderated community contributions to names/places/expressions (D27).
-- Submission requires a granted COMMUNITY_CONTRIBUTION consent (checked by the
-- application). Suggestions never feed a resource until APPROVED by a reviewer.
CREATE TABLE term_suggestions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    language_id             UUID NOT NULL REFERENCES supported_languages(id) ON DELETE CASCADE,
    surface_form            TEXT NOT NULL,
    normalized_form         TEXT NOT NULL,
    term_category           VARCHAR(30) NOT NULL DEFAULT 'GENERAL'
                            CHECK (term_category IN ('GENERAL', 'PERSON_NAME', 'PLACE_NAME', 'CULTURAL', 'RELIGIOUS',
                                                     'TECHNICAL', 'BRAND', 'PROTECTED')),
    suggested_variants      JSONB NOT NULL DEFAULT '[]'::JSONB,
    region_code             VARCHAR(10),
    contributor_note        VARCHAR(500),
    submitted_by_user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    credit_contributor      BOOLEAN NOT NULL DEFAULT FALSE,
    suggestion_status       VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED'
                            CHECK (suggestion_status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'DUPLICATE')),
    reviewed_by_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at             TIMESTAMPTZ,
    reviewer_note           VARCHAR(500),
    resulting_term_id       UUID REFERENCES language_terms(id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_term_suggestion_review CHECK (
        suggestion_status IN ('SUBMITTED', 'UNDER_REVIEW') OR reviewed_at IS NOT NULL
    ),
    CONSTRAINT ck_term_suggestion_approved CHECK (
        suggestion_status <> 'APPROVED' OR resulting_term_id IS NOT NULL
    )
);

CREATE INDEX ix_term_suggestions_queue
    ON term_suggestions (language_id, suggestion_status, created_at);

-- User dictionary sync is OPT-IN (DICTIONARY_SYNC consent + entitlement). The
-- dictionary lives on-device by default. When synced, words are ciphertext and
-- uniqueness uses a keyed HMAC of the normalized word (D17). Tombstones
-- (deleted_at) + sync_version support incremental multi-device sync.
CREATE TABLE user_dictionary_entries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    language_id         UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    word_ciphertext     BYTEA NOT NULL,
    word_hmac           CHAR(64) NOT NULL CHECK (word_hmac ~ '^[0-9a-f]{64}$'),
    key_version         INTEGER NOT NULL CHECK (key_version > 0),
    sync_version        BIGINT NOT NULL DEFAULT 1 CHECK (sync_version > 0),
    deleted_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_user_dictionary_word UNIQUE (user_id, language_id, word_hmac)
);

CREATE INDEX ix_user_dictionary_sync
    ON user_dictionary_entries (user_id, sync_version);

-- ---------------------------------------------------------------------------
-- 4. Feature catalogue (D5)
--
-- Single source of feature codes for entitlements, AI capabilities, prompts,
-- routing, requests, usage and history. Adding a feature is a data change, not
-- a CHECK-constraint migration on five tables. TYPING features are catalogued
-- for flags/telemetry only and can never be entitlement-gated: basic typing,
-- direct Fidel and phonetic transliteration are free forever.
-- ---------------------------------------------------------------------------

CREATE TABLE feature_catalog (
    feature_code            VARCHAR(40) PRIMARY KEY CHECK (feature_code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    display_name            VARCHAR(120) NOT NULL,
    category                VARCHAR(12) NOT NULL
                            CHECK (category IN ('TYPING', 'TEXT_AI', 'VOICE', 'REALTIME', 'PLATFORM')),
    metering_unit           VARCHAR(12) NOT NULL
                            CHECK (metering_unit IN ('NONE', 'REQUEST', 'SECOND', 'CHARACTER')),
    is_entitlement_gated    BOOLEAN NOT NULL,
    -- AI credits consumed per metering unit when the plan allowance is exhausted.
    credit_cost_per_unit    NUMERIC(12,6) NOT NULL DEFAULT 0 CHECK (credit_cost_per_unit >= 0),
    release_phase           VARCHAR(4) NOT NULL CHECK (release_phase IN ('P0', 'P1', 'P2', 'P3')),
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    description             TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Composite key lets plan_entitlements bind unit + gated flag declaratively.
    CONSTRAINT ux_feature_catalog_entitlement_binding UNIQUE (feature_code, metering_unit, is_entitlement_gated),
    CONSTRAINT ck_feature_typing_never_gated CHECK (
        category <> 'TYPING' OR (is_entitlement_gated = FALSE AND metering_unit = 'NONE')
    ),
    CONSTRAINT ck_feature_unmetered_free CHECK (metering_unit <> 'NONE' OR credit_cost_per_unit = 0)
);

COMMENT ON TABLE feature_catalog IS
    'Canonical feature codes (Architecture Review §5.1). TYPING features are never entitlement-gated.';

INSERT INTO feature_catalog
    (feature_code, display_name, category, metering_unit, is_entitlement_gated, credit_cost_per_unit, release_phase, description)
VALUES
    ('OFFLINE_TYPING',           'Offline typing',                 'TYPING',   'NONE',      FALSE, 0,        'P0', 'Local keyboard input. Always free.'),
    ('DIRECT_SCRIPT_LAYOUT',     'Direct Ge''ez/Fidel layout',     'TYPING',   'NONE',      FALSE, 0,        'P0', 'Fidel keys. Always free.'),
    ('PHONETIC_TRANSLITERATION', 'Phonetic QWERTY to Ge''ez',      'TYPING',   'NONE',      FALSE, 0,        'P0', 'Local transliteration. Always free.'),
    ('OFFLINE_SUGGESTIONS',      'Offline suggestions',            'TYPING',   'NONE',      FALSE, 0,        'P0', 'Local dictionary, prediction and autocorrection.'),
    ('DETECT_LANGUAGE',          'Language detection',             'TEXT_AI',  'NONE',      FALSE, 0,        'P0', 'Server language identification; rate-limited, not metered.'),
    ('TRANSLATE',                'Translation',                    'TEXT_AI',  'REQUEST',   TRUE,  1,        'P0', 'Translate a user-selected draft.'),
    ('GRAMMAR_CORRECTION',       'Grammar and spelling',           'TEXT_AI',  'REQUEST',   TRUE,  1,        'P0', 'Grammar correction including spelling mode.'),
    ('REWRITE',                  'Rewrite',                        'TEXT_AI',  'REQUEST',   TRUE,  1,        'P0', 'Natural / shorten / expand / clarify.'),
    ('TONE_ADJUST',              'Tone adjustment',                'TEXT_AI',  'REQUEST',   TRUE,  1,        'P1', 'Friendly / polite / professional / concise / apologetic / honorific.'),
    ('SMART_REPLY',              'Smart replies',                  'TEXT_AI',  'REQUEST',   TRUE,  1,        'P1', 'Replies to text the user explicitly supplied.'),
    ('SUMMARIZE',                'Summarisation',                  'TEXT_AI',  'REQUEST',   TRUE,  2,        'P2', 'Summarise selected text.'),
    ('EXPLAIN_TEXT',             'Explain text',                   'TEXT_AI',  'REQUEST',   TRUE,  1,        'P2', 'Meaning, idiom and cultural notes.'),
    ('DRAFT_DOCUMENT',           'Email and letter drafting',      'TEXT_AI',  'REQUEST',   TRUE,  3,        'P2', 'Professional email, letter and formal application drafting.'),
    ('SCRIPT_CONVERSION',        'Romanised to Ge''ez conversion', 'TEXT_AI',  'REQUEST',   TRUE,  1,        'P2', 'AI whole-sentence conversion of romanised Amharic/Tigrinya to Ge''ez.'),
    ('SPEECH_TO_TEXT',           'Voice typing',                   'VOICE',    'SECOND',    TRUE,  0.166667, 'P1', 'Speech transcription, metered per audio second.'),
    ('SPEECH_TRANSLATE',         'Speech translation',             'VOICE',    'SECOND',    TRUE,  0.25,     'P1', 'Transcribe and translate audio.'),
    ('TEXT_TO_SPEECH',           'Text-to-speech',                 'VOICE',    'CHARACTER', TRUE,  0.01,     'P2', 'Speech synthesis and pronunciation playback.'),
    ('VOICE_TO_VOICE',           'Voice-to-voice translation',     'REALTIME', 'SECOND',    TRUE,  0.333333, 'P2', 'Single-turn speech to translated speech.'),
    ('LIVE_CONVERSATION',        'Live conversation mode',         'REALTIME', 'SECOND',    TRUE,  0.333333, 'P2', 'Two-party turn-taking interpreter mode.'),
    ('HISTORY_SYNC',             'Translation history',            'PLATFORM', 'NONE',      TRUE,  0,        'P1', 'Opt-in encrypted history.'),
    ('DICTIONARY_SYNC',          'Dictionary sync',                'PLATFORM', 'NONE',      TRUE,  0,        'P1', 'Opt-in encrypted user-dictionary sync.'),
    ('PERSONAL_STYLE',           'Personal writing style',         'PLATFORM', 'NONE',      TRUE,  0,        'P2', 'Style profile applied to AI writing tasks.'),
    ('CUSTOM_GLOSSARY',          'Custom glossaries',              'PLATFORM', 'NONE',      TRUE,  0,        'P2', 'User/organisation terminology enforcement.'),
    ('ON_DEVICE_AI_MODELS',      'On-device AI models',            'PLATFORM', 'NONE',      TRUE,  0,        'P3', 'Downloadable on-device models; offline-checked via entitlement token.')
ON CONFLICT (feature_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 5. User preferences, typing languages, destination profiles and style
--
-- Preferences are synced and cached on-device. The keyboard never calls the
-- backend on a keypress. Per-contact preferences from v1.4 are replaced by
-- destination profiles (D10): a keyboard is never told who the recipient is.
-- ---------------------------------------------------------------------------

CREATE TABLE user_preferences (
    user_id                     UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    default_source_language_id  UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    default_destination_language_id UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    source_detection_mode       VARCHAR(20) NOT NULL DEFAULT 'AUTO'
                                CHECK (source_detection_mode IN ('AUTO', 'MANUAL')),
    translation_mode            VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
                                CHECK (translation_mode IN ('DISABLED', 'MANUAL', 'PROMPT')),
    preserve_names_in_translation BOOLEAN NOT NULL DEFAULT TRUE,
    default_translation_style   VARCHAR(20) NOT NULL DEFAULT 'NATURAL'
                                CHECK (default_translation_style IN ('LITERAL', 'NATURAL', 'POLITE', 'PROFESSIONAL', 'FRIENDLY')),
    -- Cultural intelligence defaults (D11). Amharic/Tigrinya inflect for the
    -- addressee's gender and formality; honorific forms matter socially.
    default_formality           VARCHAR(12) NOT NULL DEFAULT 'INFORMAL'
                                CHECK (default_formality IN ('INFORMAL', 'FORMAL', 'HONORIFIC')),
    default_addressee_gender    VARCHAR(12) NOT NULL DEFAULT 'UNKNOWN'
                                CHECK (default_addressee_gender IN ('MASCULINE', 'FEMININE', 'PLURAL', 'UNKNOWN')),
    calendar_system             VARCHAR(12) NOT NULL DEFAULT 'GREGORIAN'
                                CHECK (calendar_system IN ('GREGORIAN', 'ETHIOPIAN')),
    time_notation               VARCHAR(16) NOT NULL DEFAULT 'INTERNATIONAL'
                                CHECK (time_notation IN ('INTERNATIONAL', 'ETHIOPIAN')),
    numeral_style               VARCHAR(10) NOT NULL DEFAULT 'WESTERN'
                                CHECK (numeral_style IN ('WESTERN', 'ETHIOPIC')),
    voice_input_language_mode   VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
                                CHECK (voice_input_language_mode IN ('AUTO', 'MANUAL')),
    preferred_voice_language_id UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    -- Privacy routing preference for AI tasks with an on-device option (D14).
    ai_processing_preference    VARCHAR(20) NOT NULL DEFAULT 'SERVER'
                                CHECK (ai_processing_preference IN ('SERVER', 'PREFER_ON_DEVICE', 'ON_DEVICE_ONLY')),
    ai_history_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    history_retention_days      SMALLINT NOT NULL DEFAULT 30 CHECK (history_retention_days BETWEEN 1 AND 365),
    analytics_opt_in            BOOLEAN NOT NULL DEFAULT FALSE,
    keyboard_theme              VARCHAR(40) NOT NULL DEFAULT 'SYSTEM',
    haptics_enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    sound_enabled               BOOLEAN NOT NULL DEFAULT FALSE,
    settings_json               JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_typing_languages (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    language_id                 UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    is_enabled                  BOOLEAN NOT NULL DEFAULT TRUE,
    is_primary                  BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order                  INTEGER NOT NULL DEFAULT 100,
    -- DIRECT_SCRIPT_LAYOUT: the language's native layout (Fidel keys).
    -- PHONETIC_LATIN_TO_NATIVE: Latin QWERTY keycaps, native-script output
    --   (e.g. `selam` -> `ሰላም`), converted locally.
    -- LATIN_PASSTHROUGH: Latin input unchanged (English, Qubee).
    input_mode                  VARCHAR(32) NOT NULL DEFAULT 'DIRECT_SCRIPT_LAYOUT'
                                CHECK (input_mode IN ('DIRECT_SCRIPT_LAYOUT', 'PHONETIC_LATIN_TO_NATIVE', 'LATIN_PASSTHROUGH')),
    transliteration_profile_id  UUID,
    conversion_trigger          VARCHAR(24) NOT NULL DEFAULT 'LIVE_COMPOSING'
                                CHECK (conversion_trigger IN ('LIVE_COMPOSING', 'WORD_BOUNDARY', 'MANUAL')),
    show_transliteration_candidates BOOLEAN NOT NULL DEFAULT TRUE,
    unknown_token_behavior      VARCHAR(24) NOT NULL DEFAULT 'PRESERVE_LATIN'
                                CHECK (unknown_token_behavior IN ('PRESERVE_LATIN', 'SUGGEST_ONLY')),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_user_typing_language UNIQUE (user_id, language_id),
    CONSTRAINT fk_user_typing_transliteration_profile
        FOREIGN KEY (transliteration_profile_id, language_id)
        REFERENCES transliteration_profiles (id, language_id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_typing_transliteration_mode CHECK (
        (input_mode = 'PHONETIC_LATIN_TO_NATIVE') = (transliteration_profile_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX ux_user_one_primary_typing_language
    ON user_typing_languages (user_id)
    WHERE is_primary = TRUE AND is_enabled = TRUE;

CREATE INDEX ix_user_typing_languages_order
    ON user_typing_languages (user_id, is_enabled, sort_order);

-- Named recipients/contexts ("Mum", "Work email") selectable from the keyboard
-- destination chip. The label is user-authored, so it is ciphertext. Optional
-- linking of a phone contact to a profile happens only on-device.
CREATE TABLE user_destination_profiles (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label_ciphertext        BYTEA NOT NULL,
    key_version             INTEGER NOT NULL CHECK (key_version > 0),
    destination_language_id UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    formality               VARCHAR(12) NOT NULL DEFAULT 'INFORMAL'
                            CHECK (formality IN ('INFORMAL', 'FORMAL', 'HONORIFIC')),
    addressee_gender        VARCHAR(12) NOT NULL DEFAULT 'UNKNOWN'
                            CHECK (addressee_gender IN ('MASCULINE', 'FEMININE', 'PLURAL', 'UNKNOWN')),
    default_tone            VARCHAR(16) NOT NULL DEFAULT 'NATURAL'
                            CHECK (default_tone IN ('NATURAL', 'FRIENDLY', 'POLITE', 'PROFESSIONAL', 'CONCISE', 'APOLOGETIC', 'HONORIFIC')),
    sort_order              INTEGER NOT NULL DEFAULT 100,
    is_default              BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_destination_profile_owner UNIQUE (id, user_id)
);

CREATE INDEX ix_destination_profiles_user
    ON user_destination_profiles (user_id, sort_order);

CREATE UNIQUE INDEX ux_destination_profiles_one_default
    ON user_destination_profiles (user_id)
    WHERE is_default = TRUE;

-- Android only: EditorInfo.packageName is available to an IME; iOS does not
-- reliably expose the host app. The package name is HMAC'd on-device with a
-- per-user key, so the server never learns which apps the user uses.
CREATE TABLE user_app_destination_rules (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform                VARCHAR(12) NOT NULL DEFAULT 'ANDROID' CHECK (platform = 'ANDROID'),
    app_package_hmac        CHAR(64) NOT NULL CHECK (app_package_hmac ~ '^[0-9a-f]{64}$'),
    destination_profile_id  UUID,
    destination_language_id UUID REFERENCES supported_languages(id) ON DELETE CASCADE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_app_destination_rule UNIQUE (user_id, platform, app_package_hmac),
    CONSTRAINT fk_app_rule_profile_owner FOREIGN KEY (destination_profile_id, user_id)
        REFERENCES user_destination_profiles (id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_app_rule_one_target CHECK (num_nonnulls(destination_profile_id, destination_language_id) = 1)
);

-- Personal writing style (D29): explicit settings per language plus optional
-- learned hints computed ON-DEVICE from accepted AI results (never keystrokes).
-- Only structured parameters reach prompts; raw samples are never stored.
CREATE TABLE user_style_profiles (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    language_id             UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    formality               VARCHAR(12) NOT NULL DEFAULT 'INFORMAL'
                            CHECK (formality IN ('INFORMAL', 'FORMAL', 'HONORIFIC')),
    length_preference       VARCHAR(8) NOT NULL DEFAULT 'MEDIUM'
                            CHECK (length_preference IN ('SHORT', 'MEDIUM', 'LONG')),
    emoji_usage             VARCHAR(10) NOT NULL DEFAULT 'LIGHT'
                            CHECK (emoji_usage IN ('NONE', 'LIGHT', 'FREQUENT')),
    signoff_ciphertext      BYTEA,
    preferred_phrases_ciphertext BYTEA,
    key_version             INTEGER CHECK (key_version IS NULL OR key_version > 0),
    -- Structured hints only, e.g. {"avgSentenceLength":"SHORT","greetingStyle":"RELIGIOUS"}
    learned_hints           JSONB NOT NULL DEFAULT '{}'::JSONB,
    is_enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_user_style_profile UNIQUE (user_id, language_id),
    CONSTRAINT ck_style_ciphertext_key CHECK (
        (signoff_ciphertext IS NULL AND preferred_phrases_ciphertext IS NULL) OR key_version IS NOT NULL
    )
);

-- ---------------------------------------------------------------------------
-- 6. Consent, encrypted history, data export and deletion
-- ---------------------------------------------------------------------------

-- Append-only consent decision log (UPDATE/DELETE blocked in section 18).
-- Revocation is a new row with is_granted = FALSE; the latest row per
-- (user_id, consent_code) is the current state.
CREATE TABLE user_consents (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    consent_code        VARCHAR(40) NOT NULL
                        CHECK (consent_code IN (
                            'TERMS_ACCEPTANCE', 'PRIVACY_NOTICE', 'AI_PROCESSING', 'PAYMENT_MANDATE',
                            'SAVE_AI_HISTORY', 'SAVE_AUDIO_HISTORY', 'DICTIONARY_SYNC', 'PERSONAL_STYLE',
                            'FEEDBACK_TEXT', 'COMMUNITY_CONTRIBUTION', 'ANALYTICS', 'MARKETING'
                        )),
    consent_version     VARCHAR(40) NOT NULL,
    is_granted          BOOLEAN NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_platform     VARCHAR(12) CHECK (source_platform IN ('ANDROID', 'IOS', 'WEB', 'OTHER')),
    metadata            JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT ux_user_consents_id_user UNIQUE (id, user_id)
);

CREATE INDEX ix_user_consents_latest
    ON user_consents (user_id, consent_code, recorded_at DESC);

COMMENT ON TABLE user_consents IS
    'Append-only consent log. Latest row per (user_id, consent_code) wins; revocation is a new is_granted=FALSE row.';

-- Opt-in history (D30; replaces v1.4 user_saved_ai_results). Ciphertext only,
-- sealed with the user data key. Favourites may be kept without expiry;
-- everything else expires per user_preferences.history_retention_days.
CREATE TABLE user_history_items (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature_code            VARCHAR(40) NOT NULL REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,
    ai_request_id           UUID,   -- logical reference to ai_requests (never an FK; D18)
    source_language_id      UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    destination_language_id UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    input_ciphertext        BYTEA NOT NULL,
    output_ciphertext       BYTEA NOT NULL,
    key_version             INTEGER NOT NULL CHECK (key_version > 0),
    is_favorite             BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at              TIMESTAMPTZ,
    CONSTRAINT ck_history_expiry CHECK (
        (expires_at IS NULL AND is_favorite = TRUE) OR (expires_at IS NOT NULL AND expires_at > created_at)
    )
);

CREATE INDEX ix_user_history_user_created
    ON user_history_items (user_id, created_at DESC);

CREATE INDEX ix_user_history_expiry
    ON user_history_items (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE TABLE data_export_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_status      VARCHAR(16) NOT NULL DEFAULT 'REQUESTED'
                        CHECK (request_status IN ('REQUESTED', 'PROCESSING', 'READY', 'DOWNLOADED', 'EXPIRED', 'FAILED')),
    export_object_key   TEXT,             -- private bucket, short-lived signed download URL
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    download_expires_at TIMESTAMPTZ,
    processing_error    VARCHAR(500),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_data_export_ready CHECK (
        request_status NOT IN ('READY', 'DOWNLOADED') OR (export_object_key IS NOT NULL AND download_expires_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX ux_data_export_one_open
    ON data_export_requests (user_id)
    WHERE request_status IN ('REQUESTED', 'PROCESSING');

CREATE TABLE user_deletion_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_status      VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
                        CHECK (request_status IN ('REQUESTED', 'SCHEDULED', 'CANCELLED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    scheduled_purge_at  TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    cancellation_reason VARCHAR(255),
    processing_error    VARCHAR(500),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_user_deletion_schedule CHECK (scheduled_purge_at IS NULL OR scheduled_purge_at >= requested_at),
    CONSTRAINT ck_user_deletion_completed CHECK (completed_at IS NULL OR completed_at >= requested_at)
);

CREATE UNIQUE INDEX ux_user_one_open_deletion_request
    ON user_deletion_requests (user_id)
    WHERE request_status IN ('REQUESTED', 'SCHEDULED', 'PROCESSING');

CREATE INDEX ix_user_deletion_requests_status_schedule
    ON user_deletion_requests (request_status, scheduled_purge_at);

-- ---------------------------------------------------------------------------
-- 7. AI gateway: providers, models, capabilities, routing, prompts,
--    evaluations, operational requests and feedback (D12, D28)
-- ---------------------------------------------------------------------------

CREATE TABLE ai_providers (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code           VARCHAR(50) NOT NULL UNIQUE,
    display_name            VARCHAR(120) NOT NULL,
    provider_type           VARCHAR(30) NOT NULL
                            CHECK (provider_type IN ('TRANSLATION', 'LLM', 'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH', 'MULTI', 'SELF_HOSTED')),
    -- Fail closed: a new provider is unusable until an operator enables it.
    provider_status         VARCHAR(20) NOT NULL DEFAULT 'DISABLED'
                            CHECK (provider_status IN ('ACTIVE', 'DEGRADED', 'DISABLED')),
    -- Contractual data handling, used by routing (organisations may require ZDR).
    zero_data_retention     BOOLEAN NOT NULL DEFAULT FALSE,
    trains_on_customer_data BOOLEAN NOT NULL DEFAULT TRUE,     -- assume the worst until contract says otherwise
    processing_regions      TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    dpa_signed_at           TIMESTAMPTZ,
    configuration           JSONB NOT NULL DEFAULT '{}'::JSONB,
    secret_reference        VARCHAR(255),  -- secret-manager key/reference, never a plaintext secret
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ai_provider_active_contract CHECK (
        provider_status = 'DISABLED' OR (dpa_signed_at IS NOT NULL AND trains_on_customer_data = FALSE)
        OR provider_type = 'SELF_HOSTED'
    )
);

COMMENT ON COLUMN ai_providers.trains_on_customer_data IS
    'Must be FALSE (contractually confirmed) before an external provider can be enabled for user text.';

CREATE TABLE ai_models (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id             UUID NOT NULL REFERENCES ai_providers(id) ON DELETE RESTRICT,
    model_code              VARCHAR(120) NOT NULL,
    display_name            VARCHAR(160) NOT NULL,
    model_kind              VARCHAR(8) NOT NULL CHECK (model_kind IN ('LLM', 'MT', 'ASR', 'TTS', 'LID')),
    deployment              VARCHAR(12) NOT NULL DEFAULT 'SERVER' CHECK (deployment IN ('SERVER', 'ON_DEVICE')),
    -- On-device models ship as signed language_resources.
    language_resource_id    UUID REFERENCES language_resources(id) ON DELETE RESTRICT,
    license_code            VARCHAR(60),
    context_window_tokens   INTEGER CHECK (context_window_tokens IS NULL OR context_window_tokens > 0),
    model_status            VARCHAR(20) NOT NULL DEFAULT 'DISABLED'
                            CHECK (model_status IN ('ACTIVE', 'DEGRADED', 'DISABLED')),
    configuration           JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_ai_model_provider_code UNIQUE (provider_id, model_code),
    CONSTRAINT ck_ai_model_on_device_resource CHECK (deployment = 'SERVER' OR language_resource_id IS NOT NULL)
);

CREATE INDEX ix_ai_models_provider_status
    ON ai_models (provider_id, model_status);

-- Explicit capability matrix: a task/language pair is offered only if a row
-- exists, is enabled and its quality tier allows it — never merely because a
-- provider accepts a language code. Promote quality_tier only on evidence from
-- ai_evaluation_runs.
CREATE TABLE ai_language_capabilities (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id                UUID NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    feature_code            VARCHAR(40) NOT NULL REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,
    source_language_id      UUID REFERENCES supported_languages(id) ON DELETE CASCADE,
    destination_language_id UUID REFERENCES supported_languages(id) ON DELETE CASCADE,
    is_enabled              BOOLEAN NOT NULL DEFAULT FALSE,
    supports_auto_detection BOOLEAN NOT NULL DEFAULT FALSE,
    supports_streaming      BOOLEAN NOT NULL DEFAULT FALSE,
    quality_tier            VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
                            CHECK (quality_tier IN ('UNVERIFIED', 'EXPERIMENTAL', 'BETA', 'PRODUCTION')),
    max_input_characters    INTEGER CHECK (max_input_characters IS NULL OR max_input_characters > 0),
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_ai_language_capability UNIQUE NULLS NOT DISTINCT
        (model_id, feature_code, source_language_id, destination_language_id)
);

CREATE INDEX ix_ai_language_capabilities_lookup
    ON ai_language_capabilities (feature_code, source_language_id, destination_language_id)
    WHERE is_enabled = TRUE;

-- Routing: lowest priority number wins; equal priorities split traffic by
-- traffic_weight (A/B and canary). NULL language = any. Policies requiring
-- zero retention only resolve to providers with zero_data_retention = TRUE.
CREATE TABLE ai_routing_policies (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_code            VARCHAR(40) NOT NULL REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,
    source_language_id      UUID REFERENCES supported_languages(id) ON DELETE CASCADE,
    destination_language_id UUID REFERENCES supported_languages(id) ON DELETE CASCADE,
    model_id                UUID NOT NULL REFERENCES ai_models(id) ON DELETE RESTRICT,
    execution_target        VARCHAR(12) NOT NULL DEFAULT 'SERVER' CHECK (execution_target IN ('SERVER', 'ON_DEVICE')),
    priority                SMALLINT NOT NULL DEFAULT 100 CHECK (priority >= 0),
    traffic_weight          SMALLINT NOT NULL DEFAULT 100 CHECK (traffic_weight BETWEEN 0 AND 100),
    requires_zero_retention BOOLEAN NOT NULL DEFAULT FALSE,
    is_enabled              BOOLEAN NOT NULL DEFAULT FALSE,
    valid_from              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until             TIMESTAMPTZ,
    notes                   VARCHAR(500),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ai_routing_validity CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE INDEX ix_ai_routing_policies_lookup
    ON ai_routing_policies (feature_code, source_language_id, destination_language_id, priority)
    WHERE is_enabled = TRUE;

-- Prompt templates are versioned and must pass evaluation before activation.
-- User text is always inserted as delimited data, never as instructions.
CREATE TABLE ai_prompt_templates (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    feature_code        VARCHAR(40) NOT NULL REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,
    template_version    INTEGER NOT NULL CHECK (template_version > 0),
    language_scope      VARCHAR(35) NOT NULL DEFAULT '*',
    model_family        VARCHAR(60) NOT NULL DEFAULT '*',
    template_body       TEXT NOT NULL,
    output_schema       JSONB NOT NULL DEFAULT '{}'::JSONB,
    evaluation_status   VARCHAR(12) NOT NULL DEFAULT 'DRAFT'
                        CHECK (evaluation_status IN ('DRAFT', 'EVALUATING', 'APPROVED', 'REJECTED')),
    is_active           BOOLEAN NOT NULL DEFAULT FALSE,
    created_by          UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_ai_prompt_version UNIQUE (feature_code, template_version, language_scope, model_family),
    CONSTRAINT ck_ai_prompt_active_approved CHECK (is_active = FALSE OR evaluation_status = 'APPROVED')
);

CREATE UNIQUE INDEX ux_ai_prompt_one_active_scope
    ON ai_prompt_templates (feature_code, language_scope, model_family)
    WHERE is_active = TRUE;

CREATE TABLE ai_evaluation_runs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    capability_id       UUID NOT NULL REFERENCES ai_language_capabilities(id) ON DELETE RESTRICT,
    prompt_template_id  UUID REFERENCES ai_prompt_templates(id) ON DELETE SET NULL,
    dataset_id          UUID REFERENCES language_datasets(id) ON DELETE SET NULL,
    metric_name         VARCHAR(40) NOT NULL
                        CHECK (metric_name IN ('CHRF_PLUS_PLUS', 'COMET', 'BLEU', 'WER', 'CER', 'MOS',
                                               'HUMAN_ADEQUACY', 'HUMAN_FLUENCY', 'HUMAN_CULTURAL_FIT',
                                               'SCRIPT_VALIDITY_RATE', 'LATENCY_P95_MS')),
    score               NUMERIC(10,4) NOT NULL,
    sample_size         INTEGER NOT NULL CHECK (sample_size > 0),
    rater_type          VARCHAR(16) NOT NULL CHECK (rater_type IN ('AUTOMATIC', 'NATIVE_SPEAKER')),
    notes               TEXT,
    run_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_ai_evaluation_runs_capability
    ON ai_evaluation_runs (capability_id, metric_name, run_at DESC);

-- Operational ledger ONLY. Never add input/output text columns.
-- High volume: the application supplies a UUIDv7 id (no DB default on purpose),
-- no FK points here, and subject/device references are logical (D18).
-- Idempotency (F3): the (subject, idempotency_key) uniqueness guarantees
-- at-most-once CHARGING. A retried request may be answered from an encrypted
-- <=10-minute ephemeral cache or recomputed with usage_source = UNMETERED.
CREATE TABLE ai_requests (
    id                      UUID PRIMARY KEY,
    subject_type            VARCHAR(16) NOT NULL CHECK (subject_type IN ('USER', 'ORGANIZATION', 'API_CLIENT')),
    user_id                 UUID,     -- logical -> users.id
    organization_id         UUID,     -- logical -> organizations.id
    api_client_id           UUID,     -- logical -> api_clients.id
    device_id               UUID,     -- logical -> user_devices.id
    feature_code            VARCHAR(40) NOT NULL REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,
    surface                 VARCHAR(16) NOT NULL CHECK (surface IN ('KEYBOARD', 'COMPANION', 'PARTNER_API', 'ADMIN')),
    execution               VARCHAR(12) NOT NULL DEFAULT 'SERVER' CHECK (execution IN ('SERVER', 'ON_DEVICE')),
    source_language_id      UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    destination_language_id UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    provider_id             UUID REFERENCES ai_providers(id) ON DELETE SET NULL,
    model_id                UUID REFERENCES ai_models(id) ON DELETE SET NULL,
    prompt_template_id      UUID REFERENCES ai_prompt_templates(id) ON DELETE SET NULL,
    routing_policy_id       UUID,     -- logical -> ai_routing_policies.id
    request_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                            CHECK (request_status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED')),
    usage_source            VARCHAR(16) NOT NULL DEFAULT 'PLAN_ALLOWANCE'
                            CHECK (usage_source IN ('PLAN_ALLOWANCE', 'AI_CREDITS', 'ORGANIZATION', 'PARTNER_CONTRACT', 'UNMETERED')),
    metered_units           BIGINT NOT NULL DEFAULT 0 CHECK (metered_units >= 0),
    credits_charged         NUMERIC(18,6) NOT NULL DEFAULT 0 CHECK (credits_charged >= 0),
    input_character_count   INTEGER NOT NULL DEFAULT 0 CHECK (input_character_count >= 0),
    output_character_count  INTEGER NOT NULL DEFAULT 0 CHECK (output_character_count >= 0),
    input_token_count       INTEGER CHECK (input_token_count IS NULL OR input_token_count >= 0),
    output_token_count      INTEGER CHECK (output_token_count IS NULL OR output_token_count >= 0),
    audio_duration_ms       INTEGER CHECK (audio_duration_ms IS NULL OR audio_duration_ms >= 0),
    duration_ms             INTEGER CHECK (duration_ms IS NULL OR duration_ms >= 0),
    estimated_cost_micros   BIGINT CHECK (estimated_cost_micros IS NULL OR estimated_cost_micros >= 0),
    cost_currency           CHAR(3) NOT NULL DEFAULT 'USD',
    guardrail_flags         TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],  -- e.g. SCRIPT_MISMATCH, NO_OP, LENGTH_EXCEEDED
    idempotency_key         VARCHAR(128),
    content_retained        BOOLEAN NOT NULL DEFAULT FALSE,           -- TRUE only if an encrypted history item was written
    error_code              VARCHAR(100),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    CONSTRAINT ck_ai_request_subject CHECK (
        (subject_type = 'USER' AND user_id IS NOT NULL)
        OR (subject_type = 'ORGANIZATION' AND organization_id IS NOT NULL)
        OR (subject_type = 'API_CLIENT' AND api_client_id IS NOT NULL AND organization_id IS NOT NULL)
    ),
    CONSTRAINT ck_ai_request_completed_time CHECK (completed_at IS NULL OR completed_at >= created_at),
    CONSTRAINT ck_ai_request_credits_source CHECK (credits_charged = 0 OR usage_source = 'AI_CREDITS')
);

CREATE UNIQUE INDEX ux_ai_request_user_idempotency
    ON ai_requests (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND user_id IS NOT NULL;

CREATE UNIQUE INDEX ux_ai_request_api_client_idempotency
    ON ai_requests (api_client_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND api_client_id IS NOT NULL;

CREATE INDEX ix_ai_requests_user_created
    ON ai_requests (user_id, created_at DESC)
    WHERE user_id IS NOT NULL;

CREATE INDEX ix_ai_requests_org_created
    ON ai_requests (organization_id, created_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX ix_ai_requests_feature_status_created
    ON ai_requests (feature_code, request_status, created_at DESC);

CREATE INDEX brin_ai_requests_created
    ON ai_requests USING BRIN (created_at);

COMMENT ON TABLE ai_requests IS
    'Operational AI ledger: metadata only, no text. App-generated UUIDv7 ids. Never an FK target (partition-ready).';

-- Thumbs feedback with reason codes. Free-text comments are stored only with a
-- granted FEEDBACK_TEXT consent and only as ciphertext (D28).
CREATE TABLE ai_feedback (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ai_request_id       UUID NOT NULL,   -- logical -> ai_requests.id
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature_code        VARCHAR(40) NOT NULL REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,
    rating              SMALLINT NOT NULL CHECK (rating IN (-1, 1)),
    reason_codes        TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    comment_ciphertext  BYTEA,
    key_version         INTEGER CHECK (key_version IS NULL OR key_version > 0),
    consent_record_id   UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_ai_feedback_request_user UNIQUE (ai_request_id, user_id),
    CONSTRAINT fk_ai_feedback_consent_owner FOREIGN KEY (consent_record_id, user_id)
        REFERENCES user_consents (id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_ai_feedback_reasons CHECK (
        reason_codes <@ ARRAY['WRONG_MEANING', 'WRONG_TONE', 'WRONG_GENDER', 'WRONG_FORMALITY', 'WRONG_SCRIPT',
                              'GRAMMAR', 'SPELLING', 'UNNATURAL', 'CULTURALLY_INAPPROPRIATE', 'OFFENSIVE',
                              'TOO_LONG', 'TOO_SHORT', 'NAME_CHANGED', 'OTHER']::TEXT[]
    ),
    CONSTRAINT ck_ai_feedback_comment_consent CHECK (
        comment_ciphertext IS NULL OR (consent_record_id IS NOT NULL AND key_version IS NOT NULL)
    )
);

CREATE INDEX ix_ai_feedback_feature_created
    ON ai_feedback (feature_code, created_at DESC);

-- ---------------------------------------------------------------------------
-- 8. Speech jobs and realtime voice sessions (D13)
--
-- Metadata only. Audio is not retained by default; when a provider requires a
-- file upload, the object is private, short-lived and deleted by a sweeper.
-- Transcripts and translations are returned to the client, never stored here.
-- ---------------------------------------------------------------------------

CREATE TABLE voice_jobs (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID REFERENCES users(id) ON DELETE SET NULL,
    device_id               UUID REFERENCES user_devices(id) ON DELETE SET NULL,
    ai_request_id           UUID,     -- logical -> ai_requests.id
    feature_code            VARCHAR(40) NOT NULL REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,
    source_language_id      UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    destination_language_id UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    job_status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                            CHECK (job_status IN ('PENDING', 'UPLOADING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    audio_object_key        TEXT,     -- NULL by default; private temporary object only when required
    audio_retention_until   TIMESTAMPTZ,
    audio_deleted_at        TIMESTAMPTZ,
    audio_duration_ms       INTEGER CHECK (audio_duration_ms IS NULL OR audio_duration_ms >= 0),
    audio_size_bytes        BIGINT CHECK (audio_size_bytes IS NULL OR audio_size_bytes >= 0),
    billed_seconds          INTEGER CHECK (billed_seconds IS NULL OR billed_seconds >= 0),
    transcript_character_count INTEGER CHECK (transcript_character_count IS NULL OR transcript_character_count >= 0),
    provider_job_reference  VARCHAR(255),
    error_code              VARCHAR(100),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    CONSTRAINT ck_voice_job_feature CHECK (feature_code IN ('SPEECH_TO_TEXT', 'SPEECH_TRANSLATE', 'TEXT_TO_SPEECH')),
    CONSTRAINT ck_voice_audio_retention CHECK (audio_retention_until IS NULL OR audio_retention_until > created_at),
    CONSTRAINT ck_voice_audio_has_retention CHECK (audio_object_key IS NULL OR audio_retention_until IS NOT NULL)
);

CREATE INDEX ix_voice_jobs_user_created
    ON voice_jobs (user_id, created_at DESC);

CREATE INDEX ix_voice_jobs_status_created
    ON voice_jobs (job_status, created_at);

CREATE INDEX ix_voice_jobs_audio_retention
    ON voice_jobs (audio_retention_until)
    WHERE audio_object_key IS NOT NULL AND audio_deleted_at IS NULL;

-- One row per realtime WebSocket session. The client exchanges a short-lived
-- ticket (stored hashed) for the socket. Billing uses server-measured seconds.
-- The session mode is the feature_code (VOICE_TO_VOICE or LIVE_CONVERSATION).
CREATE TABLE realtime_sessions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID REFERENCES users(id) ON DELETE SET NULL,
    device_id               UUID REFERENCES user_devices(id) ON DELETE SET NULL,
    feature_code            VARCHAR(40) NOT NULL REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,
    language_a_id           UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    language_b_id           UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    session_status          VARCHAR(12) NOT NULL DEFAULT 'CREATED'
                            CHECK (session_status IN ('CREATED', 'ACTIVE', 'ENDED', 'FAILED', 'EXPIRED')),
    ticket_hash             CHAR(64) NOT NULL UNIQUE,
    ticket_expires_at       TIMESTAMPTZ NOT NULL,
    max_duration_seconds    INTEGER NOT NULL CHECK (max_duration_seconds > 0),
    started_at              TIMESTAMPTZ,
    ended_at                TIMESTAMPTZ,
    end_reason              VARCHAR(30)
                            CHECK (end_reason IN ('USER_ENDED', 'QUOTA_EXHAUSTED', 'MAX_DURATION', 'IDLE_TIMEOUT',
                                                  'NETWORK', 'PROVIDER_ERROR', 'SERVER_SHUTDOWN')),
    billed_seconds          INTEGER NOT NULL DEFAULT 0 CHECK (billed_seconds >= 0),
    turn_count              INTEGER NOT NULL DEFAULT 0 CHECK (turn_count >= 0),
    ai_request_id           UUID,     -- logical -> ai_requests.id (summary row)
    provider_metadata       JSONB NOT NULL DEFAULT '{}'::JSONB,   -- providers/models/latency only; no audio/transcript
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_realtime_feature CHECK (feature_code IN ('VOICE_TO_VOICE', 'LIVE_CONVERSATION')),
    CONSTRAINT ck_realtime_language_pair CHECK (language_a_id <> language_b_id),
    CONSTRAINT ck_realtime_ticket_expiry CHECK (ticket_expires_at > created_at),
    CONSTRAINT ck_realtime_ended CHECK (ended_at IS NULL OR (started_at IS NOT NULL AND ended_at >= started_at))
);

CREATE INDEX ix_realtime_sessions_user_created
    ON realtime_sessions (user_id, created_at DESC);

CREATE INDEX ix_realtime_sessions_open
    ON realtime_sessions (session_status, created_at)
    WHERE session_status IN ('CREATED', 'ACTIVE');

-- ---------------------------------------------------------------------------
-- 9. Glossaries (D9)
--
-- Terminology enforced during translation/rewriting. Owned by a user
-- (CUSTOM_GLOSSARY entitlement) or an organisation. Organisation glossaries are
-- business data; personal glossaries are deleted with the user.
-- ---------------------------------------------------------------------------

CREATE TABLE glossaries (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID REFERENCES users(id) ON DELETE CASCADE,
    organization_id         UUID REFERENCES organizations(id) ON DELETE CASCADE,
    name                    VARCHAR(160) NOT NULL,
    source_language_id      UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    target_language_id      UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    glossary_status         VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (glossary_status IN ('ACTIVE', 'ARCHIVED')),
    -- PREFER: apply terms where possible; STRICT: guardrail fails (retry, then QUALITY note) if a matched term is not rendered as specified.
    enforcement             VARCHAR(8) NOT NULL DEFAULT 'PREFER' CHECK (enforcement IN ('PREFER', 'STRICT')),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_glossary_one_owner CHECK (num_nonnulls(user_id, organization_id) = 1),
    CONSTRAINT ck_glossary_language_pair CHECK (source_language_id <> target_language_id)
);

CREATE INDEX ix_glossaries_user ON glossaries (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ix_glossaries_org ON glossaries (organization_id) WHERE organization_id IS NOT NULL;

CREATE TABLE glossary_entries (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    glossary_id             UUID NOT NULL REFERENCES glossaries(id) ON DELETE CASCADE,
    source_term             TEXT NOT NULL,
    source_term_normalized  TEXT NOT NULL,
    target_term             TEXT,
    case_sensitive          BOOLEAN NOT NULL DEFAULT FALSE,
    do_not_translate        BOOLEAN NOT NULL DEFAULT FALSE,
    notes                   VARCHAR(500),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_glossary_entry UNIQUE (glossary_id, source_term_normalized),
    CONSTRAINT ck_glossary_entry_target CHECK (do_not_translate OR target_term IS NOT NULL)
);

-- ---------------------------------------------------------------------------
-- 10. Plans, entitlements and the sellable catalogue
--
-- A product/entitlement is independent from the payment rail used to buy it.
-- Prices are integer minor units; ETB is first-class. Price changes create a
-- new price row (new version, new validity range); history is never edited.
-- ---------------------------------------------------------------------------

CREATE TABLE subscription_plans (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code           VARCHAR(30) NOT NULL UNIQUE,
    display_name        VARCHAR(100) NOT NULL,
    description         TEXT,
    plan_status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (plan_status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    -- Effective plan = highest rank among active subscriptions + seats (D8).
    plan_rank           INTEGER NOT NULL UNIQUE,
    audience            VARCHAR(12) NOT NULL DEFAULT 'CONSUMER' CHECK (audience IN ('CONSUMER', 'BUSINESS')),
    is_sellable         BOOLEAN NOT NULL DEFAULT TRUE,
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,
    display_order       INTEGER NOT NULL DEFAULT 100,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_default_plan_not_sellable CHECK (is_default = FALSE OR is_sellable = FALSE)
);

CREATE UNIQUE INDEX ux_subscription_one_default_plan
    ON subscription_plans (is_default)
    WHERE is_default = TRUE;

-- GUEST applies to attested anonymous installations; FREE is the default for
-- registered users. Neither is sold (v1.4 bug F16 sold a FREE product).
INSERT INTO subscription_plans
    (plan_code, display_name, description, plan_status, plan_rank, audience, is_sellable, is_default, display_order)
VALUES
    ('GUEST',        'Guest',        'Attested anonymous installation: small AI trial.',           'ACTIVE', -10, 'CONSUMER', FALSE, FALSE, 5),
    ('FREE',         'Free',         'Core keyboard and basic included AI usage.',                  'ACTIVE',   0, 'CONSUMER', FALSE, TRUE,  10),
    ('PREMIUM',      'Premium',      'Expanded translation, writing and voice allowances.',         'ACTIVE',  10, 'CONSUMER', TRUE,  FALSE, 20),
    ('PREMIUM_PLUS', 'Premium Plus', 'Higher AI and speech allowances, live conversation.',         'ACTIVE',  20, 'CONSUMER', TRUE,  FALSE, 30),
    ('BUSINESS',     'Business',     'Per-seat organisation plan with glossaries and data controls.', 'DRAFT', 30, 'BUSINESS', TRUE,  FALSE, 40)
ON CONFLICT (plan_code) DO NOTHING;

-- Allowances per plan and feature. The composite FK binds metering_unit and the
-- gated flag to feature_catalog, so a TYPING feature (never gated) or a wrong
-- unit is rejected declaratively. included_units NULL on a metered feature
-- means unlimited; on a NONE-unit feature the row is a boolean access switch.
CREATE TABLE plan_entitlements (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id             UUID NOT NULL REFERENCES subscription_plans(id) ON DELETE CASCADE,
    feature_code        VARCHAR(40) NOT NULL,
    metering_unit       VARCHAR(12) NOT NULL,
    is_entitlement_gated BOOLEAN NOT NULL DEFAULT TRUE CHECK (is_entitlement_gated = TRUE),
    access_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    included_units      BIGINT CHECK (included_units IS NULL OR included_units >= 0),
    reset_period        VARCHAR(20) NOT NULL DEFAULT 'NONE'
                        CHECK (reset_period IN ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    configuration       JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_plan_entitlement_feature UNIQUE (plan_id, feature_code),
    CONSTRAINT fk_plan_entitlement_feature_binding
        FOREIGN KEY (feature_code, metering_unit, is_entitlement_gated)
        REFERENCES feature_catalog (feature_code, metering_unit, is_entitlement_gated)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_plan_entitlement_boolean CHECK (
        metering_unit <> 'NONE' OR (included_units IS NULL AND reset_period = 'NONE')
    ),
    CONSTRAINT ck_plan_entitlement_disabled CHECK (
        access_enabled = TRUE OR metering_unit = 'NONE' OR included_units = 0
    )
);

COMMENT ON TABLE plan_entitlements IS
    'Plan allowances. Seed quotas (quotaPolicyVersion=PROVISIONAL_V2) are proposals pending AI cost modelling and closed-beta data.';

-- Currency exponent makes minor-unit handling explicit (ETB 2, UGX 0, etc.).
CREATE TABLE billing_currencies (
    currency_code       CHAR(3) PRIMARY KEY CHECK (currency_code ~ '^[A-Z]{3}$'),
    currency_name       VARCHAR(80) NOT NULL,
    minor_unit_exponent SMALLINT NOT NULL CHECK (minor_unit_exponent BETWEEN 0 AND 4),
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO billing_currencies (currency_code, currency_name, minor_unit_exponent)
VALUES
    ('ETB', 'Ethiopian Birr', 2),
    ('USD', 'US Dollar', 2),
    ('GBP', 'Pound Sterling', 2),
    ('EUR', 'Euro', 2),
    ('CAD', 'Canadian Dollar', 2),
    ('KES', 'Kenyan Shilling', 2),
    ('UGX', 'Ugandan Shilling', 0),
    ('TZS', 'Tanzanian Shilling', 2),
    ('GHS', 'Ghanaian Cedi', 2),
    ('NGN', 'Nigerian Naira', 2),
    ('ZAR', 'South African Rand', 2)
ON CONFLICT (currency_code) DO NOTHING;

-- A billing product is the commercial item, not a gateway/store SKU.
CREATE TABLE billing_products (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_code            VARCHAR(60) NOT NULL UNIQUE,
    product_type            VARCHAR(24) NOT NULL
                            CHECK (product_type IN ('SUBSCRIPTION', 'AI_CREDIT_PACK', 'ADD_ON')),
    plan_id                 UUID REFERENCES subscription_plans(id) ON DELETE RESTRICT,
    credit_units            NUMERIC(18,6) CHECK (credit_units IS NULL OR credit_units > 0),
    credit_validity_days    INTEGER CHECK (credit_validity_days IS NULL OR credit_validity_days > 0),
    display_name            VARCHAR(120) NOT NULL,
    description             TEXT,
    product_status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                            CHECK (product_status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_billing_product_type_fields CHECK (
        (product_type = 'SUBSCRIPTION' AND plan_id IS NOT NULL AND credit_units IS NULL)
        OR (product_type = 'AI_CREDIT_PACK' AND plan_id IS NULL AND credit_units IS NOT NULL)
        OR (product_type = 'ADD_ON' AND plan_id IS NULL AND credit_units IS NULL)
    )
);

INSERT INTO billing_products (product_code, product_type, plan_id, display_name, description, product_status)
SELECT 'PLAN_' || plan_code, 'SUBSCRIPTION', id, display_name, description, 'DRAFT'
FROM subscription_plans
WHERE plan_code IN ('PREMIUM', 'PREMIUM_PLUS')
ON CONFLICT (product_code) DO NOTHING;

INSERT INTO billing_products
    (product_code, product_type, credit_units, credit_validity_days, display_name, description, product_status)
VALUES
    ('CREDITS_500', 'AI_CREDIT_PACK', 500, 365, '500 AI credits', 'Top-up used after plan allowances; valid 365 days.', 'DRAFT')
ON CONFLICT (product_code) DO NOTHING;

-- Market- and channel-specific price book. ISO 3166-1 alpha-2 country codes;
-- ZZ = generic fallback. tax_behavior describes whether amount_minor already
-- includes tax (INCLUSIVE) or tax is added at order time (EXCLUSIVE, D21).
CREATE TABLE billing_prices (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_product_id      UUID NOT NULL REFERENCES billing_products(id) ON DELETE RESTRICT,
    market_country_code     CHAR(2) NOT NULL DEFAULT 'ZZ'
                            CHECK (market_country_code ~ '^[A-Z]{2}$'),
    sales_channel           VARCHAR(24) NOT NULL
                            CHECK (sales_channel IN ('WEB', 'ANDROID_GOOGLE_PLAY', 'ANDROID_DIRECT', 'IOS_APP_STORE', 'IOS_DIRECT', 'ADMIN', 'PARTNER')),
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code) ON DELETE RESTRICT,
    amount_minor            BIGINT NOT NULL CHECK (amount_minor >= 0),
    tax_behavior            VARCHAR(16) NOT NULL DEFAULT 'INCLUSIVE'
                            CHECK (tax_behavior IN ('INCLUSIVE', 'EXCLUSIVE', 'NOT_APPLICABLE')),
    tax_rate_basis_points   INTEGER CHECK (tax_rate_basis_points IS NULL OR tax_rate_basis_points BETWEEN 0 AND 10000),
    billing_period          VARCHAR(20) NOT NULL
                            CHECK (billing_period IN ('ONE_TIME', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'ANNUAL', 'LIFETIME')),
    renewal_model           VARCHAR(20) NOT NULL
                            CHECK (renewal_model IN ('NONE', 'MANUAL_RENEW', 'AUTO_RENEW', 'STORE_MANAGED')),
    price_version           INTEGER NOT NULL DEFAULT 1 CHECK (price_version > 0),
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until             TIMESTAMPTZ,
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_billing_price_validity CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_billing_price_tax_rate CHECK (tax_behavior <> 'EXCLUSIVE' OR tax_rate_basis_points IS NOT NULL),
    CONSTRAINT ux_billing_price_version UNIQUE (
        billing_product_id, market_country_code, sales_channel,
        currency_code, billing_period, renewal_model, price_version
    ),
    -- Composite keys let payment_orders bind the exact price scope + amount.
    CONSTRAINT ux_billing_price_scope_amount UNIQUE (
        id, billing_product_id, currency_code, market_country_code, sales_channel, amount_minor
    ),
    -- Active prices for the same commercial scope may not overlap in time.
    CONSTRAINT ex_billing_prices_no_active_overlap EXCLUDE USING gist (
        billing_product_id WITH =,
        market_country_code WITH =,
        sales_channel WITH =,
        currency_code WITH =,
        billing_period WITH =,
        renewal_model WITH =,
        tstzrange(valid_from, COALESCE(valid_until, 'infinity'::timestamptz), '[)') WITH &&
    ) WHERE (is_active = TRUE)
);

CREATE INDEX ix_billing_prices_market_active
    ON billing_prices (market_country_code, sales_channel, currency_code, is_active, valid_from DESC);

-- Promotion codes (D22). Codes are stored normalised (upper-case). Redemption
-- is counted when an order using the code is PAID, under a row lock.
CREATE TABLE promotion_codes (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code_normalized         VARCHAR(40) NOT NULL UNIQUE CHECK (code_normalized ~ '^[A-Z0-9_-]{3,40}$'),
    description             VARCHAR(255),
    discount_type           VARCHAR(12) NOT NULL CHECK (discount_type IN ('PERCENT', 'FIXED_AMOUNT')),
    percent_off_basis_points INTEGER CHECK (percent_off_basis_points IS NULL OR percent_off_basis_points BETWEEN 1 AND 10000),
    amount_off_minor        BIGINT CHECK (amount_off_minor IS NULL OR amount_off_minor > 0),
    currency_code           CHAR(3) REFERENCES billing_currencies(currency_code) ON DELETE RESTRICT,
    market_country_code     CHAR(2) CHECK (market_country_code IS NULL OR market_country_code ~ '^[A-Z]{2}$'),
    applicable_product_ids  UUID[] NOT NULL DEFAULT ARRAY[]::UUID[],   -- empty = all products
    max_redemptions         INTEGER CHECK (max_redemptions IS NULL OR max_redemptions > 0),
    redeemed_count          INTEGER NOT NULL DEFAULT 0 CHECK (redeemed_count >= 0),
    promotion_status        VARCHAR(12) NOT NULL DEFAULT 'DRAFT'
                            CHECK (promotion_status IN ('DRAFT', 'ACTIVE', 'PAUSED', 'EXPIRED')),
    valid_from              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until             TIMESTAMPTZ,
    created_by_user_id      UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_promotion_discount_fields CHECK (
        (discount_type = 'PERCENT' AND percent_off_basis_points IS NOT NULL AND amount_off_minor IS NULL)
        OR (discount_type = 'FIXED_AMOUNT' AND amount_off_minor IS NOT NULL AND currency_code IS NOT NULL
            AND percent_off_basis_points IS NULL)
    ),
    CONSTRAINT ck_promotion_redemptions CHECK (max_redemptions IS NULL OR redeemed_count <= max_redemptions),
    CONSTRAINT ck_promotion_validity CHECK (valid_until IS NULL OR valid_until > valid_from)
);

-- Maps internal prices to external store SKUs / provider product IDs.
CREATE TABLE external_product_mappings (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_price_id        UUID NOT NULL REFERENCES billing_prices(id) ON DELETE RESTRICT,
    provider_id             UUID NOT NULL,   -- FK added after payment_providers (section 11)
    external_product_id     VARCHAR(255) NOT NULL,
    external_offer_id       VARCHAR(255),    -- Play base plan/offer id; Apple has none
    environment             VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION'
                            CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- NULLS NOT DISTINCT fixes v1.4: a NULL offer id allowed duplicate mappings.
    CONSTRAINT ux_external_product_mapping UNIQUE NULLS NOT DISTINCT
        (provider_id, external_product_id, external_offer_id, environment),
    CONSTRAINT ux_external_product_mapping_provider UNIQUE (id, provider_id)
);

-- ---------------------------------------------------------------------------
-- 11. Payment providers, methods, merchant accounts and market policy
--
-- Checkout eligibility (all must hold; fail closed):
--   provider is_active + ACTIVE, adapter registered (application), merchant
--   account for country/currency/environment is_active + ACTIVE, base method
--   enabled + AVAILABLE, account method enabled + AVAILABLE, market/channel
--   policy APPROVED. Recurring additionally needs supports_auto_renew on both
--   method rows, a provider-approved mandate and unrevoked consent.
-- ---------------------------------------------------------------------------

CREATE TABLE payment_providers (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code           VARCHAR(40) NOT NULL UNIQUE,
    display_name            VARCHAR(120) NOT NULL,
    provider_type           VARCHAR(24) NOT NULL
                            CHECK (provider_type IN ('PAYMENT_GATEWAY', 'WALLET_DIRECT', 'MOBILE_MONEY_NETWORK', 'APP_STORE', 'MANUAL')),
    provider_status         VARCHAR(28) NOT NULL DEFAULT 'CONFIGURATION_REQUIRED'
                            CHECK (provider_status IN ('CONFIGURATION_REQUIRED', 'UNDER_REVIEW', 'TESTING', 'ACTIVE', 'DEGRADED', 'DISABLED')),
    -- Global operator-controlled checkout switch; all seeds start inactive.
    is_active               BOOLEAN NOT NULL DEFAULT FALSE,
    supports_webhooks       BOOLEAN NOT NULL DEFAULT FALSE,
    supports_refunds        BOOLEAN NOT NULL DEFAULT FALSE,
    supports_payment_verification BOOLEAN NOT NULL DEFAULT TRUE,
    documentation_url       TEXT,
    configuration           JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_payment_provider_type_binding UNIQUE (id, provider_type),
    CONSTRAINT ck_payment_provider_activation CHECK (is_active = FALSE OR provider_status IN ('ACTIVE', 'DEGRADED'))
);

CREATE INDEX ix_payment_providers_active_status
    ON payment_providers (is_active, provider_status, provider_type);

COMMENT ON COLUMN payment_providers.is_active IS
    'Global Active/Inactive operator switch. Checkout also requires provider_status=ACTIVE, an active merchant account, enabled/available base and account methods, market policy approval and a registered adapter.';
COMMENT ON COLUMN payment_providers.provider_code IS
    'Stable adapter registry key. Add providers as rows; never as schema or Java enums.';

INSERT INTO payment_providers
    (provider_code, display_name, provider_type, provider_status, is_active, supports_webhooks, supports_refunds, documentation_url)
VALUES
    ('CHAPA', 'Chapa', 'PAYMENT_GATEWAY', 'CONFIGURATION_REQUIRED', FALSE, TRUE, TRUE, 'https://docs.chapa.global/docs/v2'),
    ('ARIFPAY', 'ArifPay', 'PAYMENT_GATEWAY', 'UNDER_REVIEW', FALSE, TRUE, TRUE, 'https://developer.arifpay.net/docs/api/overview'),
    ('SANTIMPAY', 'SantimPay', 'PAYMENT_GATEWAY', 'UNDER_REVIEW', FALSE, TRUE, TRUE, 'https://santimpay.com/'),
    ('TELEBIRR_DIRECT', 'Telebirr Direct', 'WALLET_DIRECT', 'UNDER_REVIEW', FALSE, TRUE, TRUE, 'https://developer.ethiotelecom.et/'),
    ('MPESA', 'M-Pesa', 'MOBILE_MONEY_NETWORK', 'CONFIGURATION_REQUIRED', FALSE, FALSE, FALSE, NULL),
    ('FLUTTERWAVE', 'Flutterwave', 'PAYMENT_GATEWAY', 'CONFIGURATION_REQUIRED', FALSE, FALSE, FALSE, NULL),
    ('PESAPAL', 'Pesapal', 'PAYMENT_GATEWAY', 'CONFIGURATION_REQUIRED', FALSE, FALSE, FALSE, NULL),
    ('PAYSTACK', 'Paystack', 'PAYMENT_GATEWAY', 'CONFIGURATION_REQUIRED', FALSE, FALSE, FALSE, NULL),
    ('INTERSWITCH', 'Interswitch', 'PAYMENT_GATEWAY', 'CONFIGURATION_REQUIRED', FALSE, FALSE, FALSE, NULL),
    ('GOOGLE_PLAY', 'Google Play Billing', 'APP_STORE', 'CONFIGURATION_REQUIRED', FALSE, TRUE, TRUE, 'https://developer.android.com/google/play/billing'),
    ('APPLE_APP_STORE', 'Apple StoreKit', 'APP_STORE', 'CONFIGURATION_REQUIRED', FALSE, TRUE, TRUE, 'https://developer.apple.com/in-app-purchase/'),
    ('STRIPE', 'Stripe', 'PAYMENT_GATEWAY', 'CONFIGURATION_REQUIRED', FALSE, TRUE, TRUE, 'https://docs.stripe.com/'),
    ('PAYPAL', 'PayPal', 'PAYMENT_GATEWAY', 'CONFIGURATION_REQUIRED', FALSE, TRUE, TRUE, 'https://developer.paypal.com/docs/'),
    ('MANUAL_BANK_TRANSFER', 'Manual Bank Transfer', 'MANUAL', 'CONFIGURATION_REQUIRED', FALSE, FALSE, FALSE, NULL)
ON CONFLICT (provider_code) DO NOTHING;

ALTER TABLE external_product_mappings
    ADD CONSTRAINT fk_external_product_mapping_provider
    FOREIGN KEY (provider_id) REFERENCES payment_providers(id) ON DELETE RESTRICT;

-- Provider-level method catalogue for a country/currency (e.g. CHAPA+TELEBIRR+ET+ETB).
-- Refresh from provider discovery where available (Chapa GET /v2/payments/methods).
-- This is NOT proof a specific merchant account has the method (see account methods).
CREATE TABLE provider_payment_methods (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id             UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    method_code             VARCHAR(40) NOT NULL,
    provider_method_code    VARCHAR(100),
    display_name            VARCHAR(120) NOT NULL,
    country_code            CHAR(2) NOT NULL DEFAULT 'ZZ' CHECK (country_code ~ '^[A-Z]{2}$'),
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code) ON DELETE RESTRICT,
    method_type             VARCHAR(24) NOT NULL
                            CHECK (method_type IN ('MOBILE_MONEY', 'BANK_TRANSFER', 'CARD', 'PAYPAL', 'APP_STORE', 'MANUAL')),
    availability_status     VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
                            CHECK (availability_status IN ('UNVERIFIED', 'APPROVAL_REQUIRED', 'AVAILABLE', 'DISABLED')),
    is_enabled              BOOLEAN NOT NULL DEFAULT FALSE,
    supports_one_time       BOOLEAN NOT NULL DEFAULT TRUE,
    supports_direct_charge  BOOLEAN NOT NULL DEFAULT FALSE,
    supports_auto_renew     BOOLEAN NOT NULL DEFAULT FALSE,
    requires_redirect       BOOLEAN NOT NULL DEFAULT TRUE,
    min_amount_minor        BIGINT CHECK (min_amount_minor IS NULL OR min_amount_minor >= 0),
    max_amount_minor        BIGINT CHECK (max_amount_minor IS NULL OR max_amount_minor >= 0),
    last_verified_at        TIMESTAMPTZ,
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_provider_payment_method UNIQUE (provider_id, method_code, country_code, currency_code),
    CONSTRAINT ux_provider_method_binding UNIQUE (id, provider_id, currency_code),
    CONSTRAINT ux_provider_payment_method_scope UNIQUE (id, provider_id, country_code, currency_code),
    CONSTRAINT ck_provider_payment_method_amount_range CHECK (
        min_amount_minor IS NULL OR max_amount_minor IS NULL OR max_amount_minor >= min_amount_minor
    ),
    CONSTRAINT ck_provider_method_activation CHECK (is_enabled = FALSE OR availability_status = 'AVAILABLE'),
    CONSTRAINT ck_method_auto_renew_capability CHECK (
        supports_auto_renew = FALSE OR supports_direct_charge = TRUE OR method_type = 'APP_STORE'
    )
);

CREATE INDEX ix_provider_payment_methods_available
    ON provider_payment_methods (country_code, currency_code, availability_status, is_enabled);

-- Each real merchant relationship / store configuration is an account scoped
-- by provider, country, currency and environment. Only secret-manager
-- references are stored — never API keys, webhook secrets, PINs or card data.
CREATE TABLE payment_provider_accounts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id                 UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    account_code                VARCHAR(60) NOT NULL CHECK (account_code ~ '^[a-z0-9][a-z0-9_-]{1,58}[a-z0-9]$'),
    display_name                VARCHAR(160) NOT NULL,
    country_code                CHAR(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    currency_code               CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code) ON DELETE RESTRICT,
    environment                 VARCHAR(16) NOT NULL CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    account_status              VARCHAR(24) NOT NULL DEFAULT 'CONFIGURATION_REQUIRED'
                                CHECK (account_status IN ('CONFIGURATION_REQUIRED', 'UNDER_REVIEW', 'TESTING', 'ACTIVE', 'DEGRADED', 'SUSPENDED', 'CLOSED')),
    is_active                   BOOLEAN NOT NULL DEFAULT FALSE,
    merchant_account_reference  VARCHAR(255),
    credential_secret_reference VARCHAR(255),
    webhook_secret_reference    VARCHAR(255),
    configuration               JSONB NOT NULL DEFAULT '{}'::JSONB,
    last_verified_at            TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Webhook route /billing/webhooks/{providerCode}/{accountCode} resolves this (D20).
    CONSTRAINT ux_provider_account_code UNIQUE (provider_id, account_code),
    CONSTRAINT ux_provider_account_scope UNIQUE (id, provider_id, country_code, currency_code, environment),
    CONSTRAINT ux_provider_account_market UNIQUE (id, provider_id, country_code, currency_code),
    CONSTRAINT ck_provider_account_activation CHECK (is_active = FALSE OR account_status = 'ACTIVE')
);

CREATE INDEX ix_provider_accounts_lookup
    ON payment_provider_accounts (provider_id, country_code, currency_code, environment, is_active, account_status);

COMMENT ON TABLE payment_provider_accounts IS
    'Merchant/store accounts scoped by provider, market, currency and environment. Secret-manager references only.';

-- Final method-availability gate used by checkout: which methods are actually
-- approved and enabled for THIS merchant account.
CREATE TABLE payment_provider_account_methods (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_account_id         UUID NOT NULL,
    provider_id                 UUID NOT NULL,
    provider_payment_method_id  UUID NOT NULL,
    country_code                CHAR(2) NOT NULL,
    currency_code               CHAR(3) NOT NULL,
    availability_status         VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
                                CHECK (availability_status IN ('UNVERIFIED', 'APPROVAL_REQUIRED', 'AVAILABLE', 'DISABLED')),
    is_enabled                  BOOLEAN NOT NULL DEFAULT FALSE,
    supports_one_time           BOOLEAN NOT NULL DEFAULT TRUE,
    supports_auto_renew         BOOLEAN NOT NULL DEFAULT FALSE,
    requires_redirect           BOOLEAN NOT NULL DEFAULT TRUE,
    min_amount_minor            BIGINT CHECK (min_amount_minor IS NULL OR min_amount_minor >= 0),
    max_amount_minor            BIGINT CHECK (max_amount_minor IS NULL OR max_amount_minor >= 0),
    merchant_method_reference   VARCHAR(255),
    last_verified_at            TIMESTAMPTZ,
    metadata                    JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_account_method_account_scope
        FOREIGN KEY (provider_account_id, provider_id, country_code, currency_code)
        REFERENCES payment_provider_accounts (id, provider_id, country_code, currency_code) ON DELETE RESTRICT,
    CONSTRAINT fk_account_method_provider_method_scope
        FOREIGN KEY (provider_payment_method_id, provider_id, country_code, currency_code)
        REFERENCES provider_payment_methods (id, provider_id, country_code, currency_code) ON DELETE RESTRICT,
    CONSTRAINT ux_provider_account_method UNIQUE (provider_account_id, provider_payment_method_id),
    CONSTRAINT ux_provider_account_method_binding UNIQUE (id, provider_account_id, provider_id, provider_payment_method_id),
    CONSTRAINT ck_account_method_activation CHECK (is_enabled = FALSE OR availability_status = 'AVAILABLE'),
    CONSTRAINT ck_account_method_auto_renew CHECK (supports_auto_renew = FALSE OR availability_status = 'AVAILABLE'),
    CONSTRAINT ck_account_method_amount_range CHECK (min_amount_minor IS NULL OR max_amount_minor IS NULL OR max_amount_minor >= min_amount_minor)
);

CREATE INDEX ix_provider_account_methods_available
    ON payment_provider_account_methods (provider_account_id, availability_status, is_enabled);

-- Storefront/payment-policy decisions: country-scoped and fail closed. A
-- missing, expired, REVIEW_REQUIRED or BLOCKED policy means no purchase UI for
-- that flow. Approval requires a named reviewer (four-eyes in the app, D25).
CREATE TABLE billing_market_channel_policies (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code            CHAR(2) NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    client_platform         VARCHAR(12) NOT NULL CHECK (client_platform IN ('ANDROID', 'IOS', 'WEB')),
    distribution_channel    VARCHAR(24) NOT NULL
                            CHECK (distribution_channel IN ('GOOGLE_PLAY', 'APPLE_APP_STORE', 'DIRECT_DOWNLOAD', 'WEB')),
    purchase_flow           VARCHAR(28) NOT NULL
                            CHECK (purchase_flow IN ('STORE_BILLING', 'LOCAL_GATEWAY_IN_APP', 'EXTERNAL_WEB_CHECKOUT', 'CONSUMPTION_ONLY', 'NONE')),
    approval_status         VARCHAR(20) NOT NULL DEFAULT 'REVIEW_REQUIRED'
                            CHECK (approval_status IN ('REVIEW_REQUIRED', 'APPROVED', 'BLOCKED')),
    purchase_ui_allowed     BOOLEAN NOT NULL DEFAULT FALSE,
    external_payment_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    policy_source_url       TEXT,
    policy_notes            TEXT,
    reviewed_at             TIMESTAMPTZ,
    reviewed_by_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    effective_from          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    effective_until         TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_billing_market_channel_policy UNIQUE (country_code, client_platform, distribution_channel),
    CONSTRAINT ck_billing_policy_validity CHECK (effective_until IS NULL OR effective_until > effective_from),
    CONSTRAINT ck_billing_policy_approval CHECK (
        approval_status <> 'APPROVED' OR (reviewed_at IS NOT NULL AND reviewed_by_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_billing_policy_purchase_ui CHECK (purchase_ui_allowed = FALSE OR approval_status = 'APPROVED'),
    CONSTRAINT ck_billing_policy_external_flow CHECK (
        external_payment_allowed = FALSE
        OR (approval_status = 'APPROVED' AND purchase_flow IN ('LOCAL_GATEWAY_IN_APP', 'EXTERNAL_WEB_CHECKOUT'))
    )
);

CREATE INDEX ix_billing_market_policy_lookup
    ON billing_market_channel_policies (country_code, client_platform, distribution_channel, approval_status, effective_from DESC);

-- ---------------------------------------------------------------------------
-- 12. Gateway/manual orders, attempts, mandates, ledger, refunds, webhooks,
--     receipts and reconciliation
--
-- App-store purchases do NOT use these tables (section 13, D23).
-- ---------------------------------------------------------------------------

-- The server resolves the price and snapshots every amount; never trust a
-- client amount. amount_minor = list - discount + tax, where tax_minor is only
-- non-zero for EXCLUSIVE prices (enforced by trigger). Upgrade orders carry a
-- server-computed proration discount for the unused current term (D22).
CREATE TABLE payment_orders (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_reference         VARCHAR(40) NOT NULL UNIQUE,
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    billing_product_id      UUID NOT NULL REFERENCES billing_products(id) ON DELETE RESTRICT,
    billing_price_id        UUID NOT NULL REFERENCES billing_prices(id) ON DELETE RESTRICT,
    order_type              VARCHAR(28) NOT NULL
                            CHECK (order_type IN ('SUBSCRIPTION_PURCHASE', 'SUBSCRIPTION_RENEWAL', 'SUBSCRIPTION_UPGRADE',
                                                  'AI_CREDIT_TOPUP', 'ADD_ON_PURCHASE')),
    sales_channel           VARCHAR(24) NOT NULL
                            CHECK (sales_channel IN ('WEB', 'ANDROID_GOOGLE_PLAY', 'ANDROID_DIRECT', 'IOS_APP_STORE', 'IOS_DIRECT', 'ADMIN', 'PARTNER')),
    market_country_code     CHAR(2) NOT NULL CHECK (market_country_code ~ '^[A-Z]{2}$'),
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code) ON DELETE RESTRICT,
    list_amount_minor       BIGINT NOT NULL CHECK (list_amount_minor >= 0),
    discount_minor          BIGINT NOT NULL DEFAULT 0 CHECK (discount_minor >= 0),
    tax_minor               BIGINT NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
    amount_minor            BIGINT NOT NULL CHECK (amount_minor >= 0),
    promotion_code_id       UUID REFERENCES promotion_codes(id) ON DELETE RESTRICT,
    upgrade_from_subscription_id UUID,   -- FK added in section 14
    order_status            VARCHAR(24) NOT NULL DEFAULT 'CREATED'
                            CHECK (order_status IN ('CREATED', 'AWAITING_PAYMENT', 'PAID', 'FAILED', 'CANCELLED', 'EXPIRED', 'PARTIALLY_REFUNDED', 'REFUNDED')),
    idempotency_key         VARCHAR(128) NOT NULL,
    expires_at              TIMESTAMPTZ,
    paid_at                 TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_payment_order_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ux_payment_order_owner_binding UNIQUE (id, user_id),
    CONSTRAINT ux_payment_order_scope UNIQUE (id, user_id, market_country_code, currency_code),
    -- The selected price must belong to this product, currency, market and
    -- channel, and the list amount must equal that price's amount.
    CONSTRAINT fk_payment_order_price_amount_scope
        FOREIGN KEY (billing_price_id, billing_product_id, currency_code, market_country_code, sales_channel, list_amount_minor)
        REFERENCES billing_prices (id, billing_product_id, currency_code, market_country_code, sales_channel, amount_minor)
        ON DELETE RESTRICT,
    CONSTRAINT ck_payment_order_amount CHECK (amount_minor = list_amount_minor - discount_minor + tax_minor),
    CONSTRAINT ck_payment_order_discount CHECK (discount_minor <= list_amount_minor),
    CONSTRAINT ck_payment_order_discount_source CHECK (
        discount_minor = 0 OR promotion_code_id IS NOT NULL OR order_type = 'SUBSCRIPTION_UPGRADE'
    ),
    CONSTRAINT ck_payment_order_upgrade CHECK (
        (order_type = 'SUBSCRIPTION_UPGRADE') = (upgrade_from_subscription_id IS NOT NULL)
    ),
    CONSTRAINT ck_payment_order_paid CHECK (order_status <> 'PAID' OR paid_at IS NOT NULL)
);

CREATE INDEX ix_payment_orders_user_created
    ON payment_orders (user_id, created_at DESC);

CREATE INDEX ix_payment_orders_status_created
    ON payment_orders (order_status, created_at);

-- A recurring mandate is a distinct, user-authorised permission — never a
-- reused one-off payment. It is bound to the exact merchant account and
-- account-enabled method, and to a granted, unrevoked PAYMENT_MANDATE consent
-- (guard trigger, section 19). Provider references only; never PINs/card data.
CREATE TABLE payment_mandates (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    provider_id                 UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    provider_account_id         UUID NOT NULL,
    provider_payment_method_id  UUID NOT NULL,
    provider_account_method_id  UUID NOT NULL,
    consent_record_id           UUID NOT NULL,
    provider_mandate_reference  VARCHAR(255) NOT NULL,
    mandate_status              VARCHAR(24) NOT NULL DEFAULT 'PENDING'
                                CHECK (mandate_status IN ('PENDING', 'SIGNING_REQUIRED', 'ACTIVE', 'PAUSED', 'CANCELLED', 'EXPIRED', 'FAILED', 'REVOKED')),
    currency_code               CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code) ON DELETE RESTRICT,
    max_amount_minor            BIGINT CHECK (max_amount_minor IS NULL OR max_amount_minor > 0),
    interval_unit               VARCHAR(16) NOT NULL CHECK (interval_unit IN ('DAY', 'WEEK', 'MONTH', 'YEAR')),
    interval_count              SMALLINT NOT NULL DEFAULT 1 CHECK (interval_count > 0),
    consent_text_version        VARCHAR(40) NOT NULL,
    consented_at                TIMESTAMPTZ NOT NULL,
    provider_approved_at        TIMESTAMPTZ,
    next_charge_at              TIMESTAMPTZ,
    last_charge_at              TIMESTAMPTZ,
    cancelled_at                TIMESTAMPTZ,
    expires_at                  TIMESTAMPTZ,
    metadata                    JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_payment_mandate_provider_reference UNIQUE (provider_id, provider_mandate_reference),
    CONSTRAINT ux_payment_mandate_full_binding
        UNIQUE (id, user_id, provider_id, provider_payment_method_id, provider_account_id, provider_account_method_id),
    CONSTRAINT fk_payment_mandate_method_currency FOREIGN KEY (provider_payment_method_id, provider_id, currency_code)
        REFERENCES provider_payment_methods (id, provider_id, currency_code) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_mandate_account_method_binding
        FOREIGN KEY (provider_account_method_id, provider_account_id, provider_id, provider_payment_method_id)
        REFERENCES payment_provider_account_methods (id, provider_account_id, provider_id, provider_payment_method_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_mandate_consent_owner FOREIGN KEY (consent_record_id, user_id)
        REFERENCES user_consents (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_mandate_expiry CHECK (expires_at IS NULL OR expires_at > consented_at),
    CONSTRAINT ck_payment_mandate_approval CHECK (mandate_status <> 'ACTIVE' OR provider_approved_at IS NOT NULL)
);

CREATE INDEX ix_payment_mandates_user_status
    ON payment_mandates (user_id, mandate_status, expires_at);

CREATE INDEX ix_payment_mandates_due
    ON payment_mandates (next_charge_at)
    WHERE mandate_status = 'ACTIVE';

-- Append-only mandate audit trail.
CREATE TABLE payment_mandate_events (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_mandate_id      UUID NOT NULL REFERENCES payment_mandates(id) ON DELETE RESTRICT,
    event_type              VARCHAR(60) NOT NULL,
    previous_status         VARCHAR(24),
    new_status              VARCHAR(24),
    actor_type              VARCHAR(20) NOT NULL DEFAULT 'SYSTEM'
                            CHECK (actor_type IN ('SYSTEM', 'USER', 'ADMIN', 'PROVIDER')),
    event_details           JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_payment_mandate_events_mandate_created
    ON payment_mandate_events (payment_mandate_id, created_at DESC);

-- An order may have several attempts (e.g. Telebirr fails, retry via CBE Birr)
-- without duplicating the order or the entitlement. Every attempt is bound to
-- the order's owner/market/currency and to one merchant account + account
-- method. The account/method choice is resolved server-side; clients only
-- send a public method code.
CREATE TABLE payment_attempts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_order_id            UUID NOT NULL,
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    attempt_number              SMALLINT NOT NULL CHECK (attempt_number > 0),
    provider_id                 UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    provider_account_id         UUID NOT NULL,
    provider_payment_method_id  UUID NOT NULL,
    provider_account_method_id  UUID NOT NULL,
    market_country_code         CHAR(2) NOT NULL,
    currency_code               CHAR(3) NOT NULL,
    environment                 VARCHAR(16) NOT NULL CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    payment_mandate_id          UUID,
    attempt_status              VARCHAR(24) NOT NULL DEFAULT 'INITIATED'
                                CHECK (attempt_status IN ('INITIATED', 'PENDING', 'REQUIRES_ACTION', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'UNKNOWN')),
    provider_payment_reference  VARCHAR(255),
    provider_customer_reference VARCHAR(255),
    checkout_url                TEXT,
    idempotency_key             VARCHAR(128) NOT NULL,
    failure_code                VARCHAR(100),
    failure_message_safe        VARCHAR(500),
    expires_at                  TIMESTAMPTZ,
    verified_at                 TIMESTAMPTZ,
    metadata                    JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_payment_attempt_number UNIQUE (payment_order_id, attempt_number),
    -- F15: idempotency is scoped to the user, not the provider.
    CONSTRAINT ux_payment_attempt_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ux_payment_attempt_provider_currency_binding UNIQUE (id, provider_id, currency_code),
    CONSTRAINT fk_payment_attempt_order_market_currency
        FOREIGN KEY (payment_order_id, user_id, market_country_code, currency_code)
        REFERENCES payment_orders (id, user_id, market_country_code, currency_code) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_attempt_provider_method_scope
        FOREIGN KEY (provider_payment_method_id, provider_id, market_country_code, currency_code)
        REFERENCES provider_payment_methods (id, provider_id, country_code, currency_code) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_attempt_provider_account_scope
        FOREIGN KEY (provider_account_id, provider_id, market_country_code, currency_code, environment)
        REFERENCES payment_provider_accounts (id, provider_id, country_code, currency_code, environment) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_attempt_account_method_binding
        FOREIGN KEY (provider_account_method_id, provider_account_id, provider_id, provider_payment_method_id)
        REFERENCES payment_provider_account_methods (id, provider_account_id, provider_id, provider_payment_method_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_attempt_mandate_binding
        FOREIGN KEY (payment_mandate_id, user_id, provider_id, provider_payment_method_id, provider_account_id, provider_account_method_id)
        REFERENCES payment_mandates (id, user_id, provider_id, provider_payment_method_id, provider_account_id, provider_account_method_id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_payment_attempt_provider_reference
    ON payment_attempts (provider_id, provider_payment_reference)
    WHERE provider_payment_reference IS NOT NULL;

CREATE INDEX ix_payment_attempts_order_status
    ON payment_attempts (payment_order_id, attempt_status, created_at DESC);

CREATE INDEX ix_payment_attempts_provider_account
    ON payment_attempts (provider_account_id, provider_account_method_id);

CREATE INDEX ix_payment_attempts_mandate
    ON payment_attempts (payment_mandate_id)
    WHERE payment_mandate_id IS NOT NULL;

-- Append-only financial ledger. Rows are written only after server-side
-- provider verification, so they record facts (SUCCEEDED/FAILED), not
-- in-flight states (those live on payment_attempts). Corrections are new rows
-- (REVERSAL, CHARGEBACK, CHARGEBACK_REVERSAL) linked via parent_transaction_id.
-- Amounts are positive; transaction_kind gives the accounting meaning.
CREATE TABLE payment_transactions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_attempt_id      UUID NOT NULL,
    provider_id             UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    parent_transaction_id   UUID REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    transaction_kind        VARCHAR(20) NOT NULL
                            CHECK (transaction_kind IN ('CAPTURE', 'REFUND', 'REVERSAL', 'CHARGEBACK', 'CHARGEBACK_REVERSAL', 'FEE')),
    transaction_status      VARCHAR(20) NOT NULL CHECK (transaction_status IN ('SUCCEEDED', 'FAILED')),
    provider_transaction_reference VARCHAR(255),
    amount_minor            BIGINT NOT NULL CHECK (amount_minor > 0),
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code) ON DELETE RESTRICT,
    idempotency_key         VARCHAR(128) NOT NULL,
    occurred_at             TIMESTAMPTZ,
    verified_at             TIMESTAMPTZ NOT NULL,
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_payment_transaction_idempotency UNIQUE (provider_id, idempotency_key),
    CONSTRAINT ux_payment_transaction_binding UNIQUE (id, provider_id, currency_code),
    CONSTRAINT ux_payment_transaction_kind_binding UNIQUE (id, provider_id, currency_code, transaction_kind),
    -- A transaction cannot claim a provider/currency different from its attempt.
    CONSTRAINT fk_payment_transaction_attempt_provider_currency
        FOREIGN KEY (payment_attempt_id, provider_id, currency_code)
        REFERENCES payment_attempts (id, provider_id, currency_code) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_transaction_parent CHECK (
        transaction_kind IN ('CAPTURE', 'FEE') OR parent_transaction_id IS NOT NULL
    )
);

CREATE UNIQUE INDEX ux_payment_transaction_provider_reference
    ON payment_transactions (provider_id, provider_transaction_reference, transaction_kind)
    WHERE provider_transaction_reference IS NOT NULL;

CREATE INDEX ix_payment_transactions_attempt
    ON payment_transactions (payment_attempt_id, created_at DESC);

-- Refund workflow. Bound to a CAPTURE of the same provider and currency (F15).
CREATE TABLE payment_refunds (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    capture_transaction_id  UUID NOT NULL,
    capture_transaction_kind VARCHAR(20) NOT NULL DEFAULT 'CAPTURE' CHECK (capture_transaction_kind = 'CAPTURE'),
    refund_transaction_id   UUID,
    provider_id             UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code) ON DELETE RESTRICT,
    refund_reference        VARCHAR(100) NOT NULL UNIQUE,
    amount_minor            BIGINT NOT NULL CHECK (amount_minor > 0),
    refund_status           VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
                            CHECK (refund_status IN ('REQUESTED', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    reason_code             VARCHAR(60),
    reason_text             VARCHAR(500),
    requested_by_user_id    UUID REFERENCES users(id) ON DELETE SET NULL,
    requested_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_payment_refund_capture_binding
        FOREIGN KEY (capture_transaction_id, provider_id, currency_code, capture_transaction_kind)
        REFERENCES payment_transactions (id, provider_id, currency_code, transaction_kind) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_refund_refund_tx_binding
        FOREIGN KEY (refund_transaction_id, provider_id, currency_code)
        REFERENCES payment_transactions (id, provider_id, currency_code) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_refund_succeeded CHECK (refund_status <> 'SUCCEEDED' OR refund_transaction_id IS NOT NULL)
);

CREATE INDEX ix_payment_refunds_capture
    ON payment_refunds (capture_transaction_id);

-- Webhook inbox for gateways (account-scoped route, D20) and app-store
-- notifications. Persist first, ack fast, process via outbox. If a provider
-- sends no event id, external_event_id = sha256(raw payload) and
-- is_synthetic_event_id = TRUE. Only a redacted payload is retained.
CREATE TABLE payment_webhook_events (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id             UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    provider_account_id     UUID REFERENCES payment_provider_accounts(id) ON DELETE RESTRICT,
    external_event_id       VARCHAR(255) NOT NULL,
    is_synthetic_event_id   BOOLEAN NOT NULL DEFAULT FALSE,
    event_type              VARCHAR(120) NOT NULL,
    signature_status        VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
                            CHECK (signature_status IN ('VALID', 'INVALID', 'NOT_APPLICABLE', 'UNKNOWN')),
    event_status            VARCHAR(20) NOT NULL DEFAULT 'RECEIVED'
                            CHECK (event_status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'IGNORED')),
    payload_sha256          CHAR(64) NOT NULL,
    redacted_payload        JSONB NOT NULL DEFAULT '{}'::JSONB,
    processing_attempts     SMALLINT NOT NULL DEFAULT 0 CHECK (processing_attempts >= 0),
    processing_error        VARCHAR(500),
    received_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at            TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_payment_webhook_provider_event UNIQUE (provider_id, external_event_id),
    CONSTRAINT ck_payment_webhook_synthetic CHECK (is_synthetic_event_id = FALSE OR external_event_id = payload_sha256)
);

CREATE INDEX ix_payment_webhook_status_received
    ON payment_webhook_events (event_status, received_at);

-- Manual bank transfer: a submitted reference/evidence is a CLAIM, never proof.
-- Settlement requires reconciliation against bank data or an authorised reviewer.
CREATE TABLE manual_payment_reviews (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_attempt_id      UUID NOT NULL UNIQUE REFERENCES payment_attempts(id) ON DELETE RESTRICT,
    payer_name              VARCHAR(160),
    bank_reference          VARCHAR(160),
    transfer_date           DATE,
    evidence_object_key     TEXT,
    review_status           VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED'
                            CHECK (review_status IN ('SUBMITTED', 'UNDER_REVIEW', 'MATCHED', 'REJECTED')),
    reviewed_by_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewer_note           VARCHAR(500),
    submitted_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_manual_review_decision CHECK (
        review_status NOT IN ('MATCHED', 'REJECTED') OR (reviewed_at IS NOT NULL AND reviewed_by_user_id IS NOT NULL)
    )
);

-- Customer receipts (D21). receipt_number is allocated by the application from
-- a per-market sequence (e.g. ET-2026-000123) inside the fulfilment transaction.
CREATE TABLE payment_receipts (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_order_id        UUID NOT NULL UNIQUE REFERENCES payment_orders(id) ON DELETE RESTRICT,
    payment_transaction_id  UUID NOT NULL REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    market_country_code     CHAR(2) NOT NULL CHECK (market_country_code ~ '^[A-Z]{2}$'),
    receipt_number          VARCHAR(40) NOT NULL,
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code) ON DELETE RESTRICT,
    amount_minor            BIGINT NOT NULL CHECK (amount_minor >= 0),
    tax_minor               BIGINT NOT NULL DEFAULT 0 CHECK (tax_minor >= 0),
    tax_behavior            VARCHAR(16) NOT NULL CHECK (tax_behavior IN ('INCLUSIVE', 'EXCLUSIVE', 'NOT_APPLICABLE')),
    receipt_object_key      TEXT,
    issued_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_payment_receipt_number UNIQUE (market_country_code, receipt_number)
);

-- Settlement reconciliation against provider reports.
CREATE TABLE reconciliation_runs (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_account_id     UUID NOT NULL REFERENCES payment_provider_accounts(id) ON DELETE RESTRICT,
    period_start            TIMESTAMPTZ NOT NULL,
    period_end              TIMESTAMPTZ NOT NULL,
    source_reference        VARCHAR(255),   -- provider settlement report id/file
    run_status              VARCHAR(12) NOT NULL DEFAULT 'RUNNING'
                            CHECK (run_status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    totals                  JSONB NOT NULL DEFAULT '{}'::JSONB,
    started_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    created_by_user_id      UUID REFERENCES users(id) ON DELETE SET NULL,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_reconciliation_period CHECK (period_end > period_start)
);

CREATE TABLE reconciliation_items (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reconciliation_run_id   UUID NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
    payment_transaction_id  UUID REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    provider_transaction_reference VARCHAR(255),
    match_status            VARCHAR(20) NOT NULL
                            CHECK (match_status IN ('MATCHED', 'MISSING_INTERNAL', 'MISSING_PROVIDER', 'AMOUNT_MISMATCH',
                                                    'CURRENCY_MISMATCH', 'STATUS_MISMATCH')),
    currency_code           CHAR(3) REFERENCES billing_currencies(currency_code) ON DELETE RESTRICT,
    internal_amount_minor   BIGINT,
    provider_amount_minor   BIGINT,
    provider_fee_minor      BIGINT,
    resolution_status       VARCHAR(12) NOT NULL DEFAULT 'OPEN'
                            CHECK (resolution_status IN ('OPEN', 'RESOLVED', 'WRITTEN_OFF')),
    resolved_by_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_at             TIMESTAMPTZ,
    note                    VARCHAR(500),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_reconciliation_items_open
    ON reconciliation_items (reconciliation_run_id, resolution_status);

-- ---------------------------------------------------------------------------
-- 13. App-store purchases (D23)
--
-- Google Play and Apple StoreKit renewals happen inside the store, outside our
-- order flow, so they are modelled separately and fulfil user_subscriptions
-- directly (source_type STORE). Verify every token server-to-server (Play
-- Developer API / App Store Server API); acknowledge Play purchases within 3
-- days or Google refunds them. The raw token is kept only as ciphertext for
-- re-verification; lookups use its SHA-256 hash.
-- ---------------------------------------------------------------------------

CREATE TABLE store_purchases (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    provider_id                 UUID NOT NULL,
    provider_type               VARCHAR(24) NOT NULL DEFAULT 'APP_STORE' CHECK (provider_type = 'APP_STORE'),
    external_product_mapping_id UUID NOT NULL,
    purchase_token_hash         CHAR(64) NOT NULL CHECK (purchase_token_hash ~ '^[0-9a-f]{64}$'),
    purchase_token_ciphertext   BYTEA,
    original_transaction_id     VARCHAR(255),   -- Apple originalTransactionId / Play orderId root
    latest_transaction_id       VARCHAR(255),
    environment                 VARCHAR(16) NOT NULL CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    storefront_country_code     CHAR(2) CHECK (storefront_country_code IS NULL OR storefront_country_code ~ '^[A-Z]{2}$'),
    purchase_status             VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                                CHECK (purchase_status IN ('PENDING', 'ACTIVE', 'IN_GRACE_PERIOD', 'ON_HOLD', 'PAUSED',
                                                           'CANCELLED', 'EXPIRED', 'REVOKED', 'REFUNDED')),
    auto_renewing               BOOLEAN NOT NULL DEFAULT FALSE,
    purchased_at                TIMESTAMPTZ NOT NULL,
    expires_at                  TIMESTAMPTZ,
    acknowledged_at             TIMESTAMPTZ,
    last_verified_at            TIMESTAMPTZ,
    metadata                    JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_store_purchase_provider_is_store FOREIGN KEY (provider_id, provider_type)
        REFERENCES payment_providers (id, provider_type) ON DELETE RESTRICT,
    CONSTRAINT fk_store_purchase_product_mapping FOREIGN KEY (external_product_mapping_id, provider_id)
        REFERENCES external_product_mappings (id, provider_id) ON DELETE RESTRICT,
    CONSTRAINT ux_store_purchase_token UNIQUE (provider_id, purchase_token_hash),
    CONSTRAINT ux_store_purchase_owner UNIQUE (id, user_id)
);

CREATE UNIQUE INDEX ux_store_purchase_original_transaction
    ON store_purchases (provider_id, original_transaction_id)
    WHERE original_transaction_id IS NOT NULL;

CREATE INDEX ix_store_purchases_user_status
    ON store_purchases (user_id, purchase_status);

CREATE INDEX ix_store_purchases_unacknowledged
    ON store_purchases (purchased_at)
    WHERE acknowledged_at IS NULL AND purchase_status = 'ACTIVE';

-- Append-only store lifecycle (RTDN / App Store Server Notifications V2).
CREATE TABLE store_purchase_events (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_purchase_id       UUID NOT NULL REFERENCES store_purchases(id) ON DELETE RESTRICT,
    webhook_event_id        UUID REFERENCES payment_webhook_events(id) ON DELETE RESTRICT,
    notification_type       VARCHAR(80) NOT NULL,
    previous_status         VARCHAR(20),
    new_status              VARCHAR(20),
    event_time              TIMESTAMPTZ NOT NULL,
    event_details           JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_store_purchase_events_purchase
    ON store_purchase_events (store_purchase_id, event_time DESC);

-- ---------------------------------------------------------------------------
-- 14. Subscriptions (users and organisations)
--
-- A subscription is an entitlement period, not a gateway agreement.
-- MANUAL_RENEW is the default for local one-off payments; AUTO_RENEW requires
-- an approved mandate; STORE_MANAGED is driven by the store.
-- ---------------------------------------------------------------------------

CREATE TABLE user_subscriptions (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    plan_id                     UUID NOT NULL REFERENCES subscription_plans(id) ON DELETE RESTRICT,
    billing_product_id          UUID REFERENCES billing_products(id) ON DELETE RESTRICT,
    billing_price_id            UUID REFERENCES billing_prices(id) ON DELETE RESTRICT,
    source_type                 VARCHAR(20) NOT NULL DEFAULT 'PAYMENT'
                                CHECK (source_type IN ('PAYMENT', 'STORE', 'ADMIN_GRANT', 'PROMOTION', 'PARTNER')),
    billing_mode                VARCHAR(20) NOT NULL
                                CHECK (billing_mode IN ('MANUAL_RENEW', 'AUTO_RENEW', 'STORE_MANAGED', 'GRANT', 'LIFETIME')),
    store_purchase_id           UUID,
    provider_id                 UUID REFERENCES payment_providers(id) ON DELETE RESTRICT,
    provider_account_id         UUID,
    provider_payment_method_id  UUID,
    provider_account_method_id  UUID,
    payment_mandate_id          UUID,
    provider_subscription_reference VARCHAR(255),
    subscription_status         VARCHAR(20) NOT NULL
                                CHECK (subscription_status IN ('PENDING', 'ACTIVE', 'GRACE_PERIOD', 'PAST_DUE', 'PAUSED', 'CANCELLED', 'EXPIRED', 'REVOKED', 'UPGRADED')),
    current_period_start        TIMESTAMPTZ,
    current_period_end          TIMESTAMPTZ,
    grace_period_end            TIMESTAMPTZ,
    auto_renew_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    cancel_at_period_end        BOOLEAN NOT NULL DEFAULT FALSE,
    next_billing_at             TIMESTAMPTZ,
    started_at                  TIMESTAMPTZ,
    cancelled_at                TIMESTAMPTZ,
    revoked_at                  TIMESTAMPTZ,
    last_verified_at            TIMESTAMPTZ,
    metadata                    JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_user_subscription_owner UNIQUE (id, user_id),
    CONSTRAINT ck_subscription_period CHECK (
        current_period_start IS NULL OR current_period_end IS NULL OR current_period_end > current_period_start
    ),
    CONSTRAINT fk_subscription_store_purchase_owner FOREIGN KEY (store_purchase_id, user_id)
        REFERENCES store_purchases (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_account_method_binding
        FOREIGN KEY (provider_account_method_id, provider_account_id, provider_id, provider_payment_method_id)
        REFERENCES payment_provider_account_methods (id, provider_account_id, provider_id, provider_payment_method_id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_mandate_full_binding
        FOREIGN KEY (payment_mandate_id, user_id, provider_id, provider_payment_method_id, provider_account_id, provider_account_method_id)
        REFERENCES payment_mandates (id, user_id, provider_id, provider_payment_method_id, provider_account_id, provider_account_method_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_subscription_store_source CHECK (
        (source_type = 'STORE') = (store_purchase_id IS NOT NULL)
        AND (source_type <> 'STORE' OR billing_mode = 'STORE_MANAGED')
    ),
    CONSTRAINT ck_subscription_mandate_complete CHECK (
        payment_mandate_id IS NULL
        OR num_nonnulls(provider_id, provider_account_id, provider_payment_method_id, provider_account_method_id) = 4
    ),
    CONSTRAINT ck_subscription_auto_renew_mode CHECK (
        auto_renew_enabled = FALSE
        OR billing_mode = 'STORE_MANAGED'
        OR (billing_mode = 'AUTO_RENEW' AND payment_mandate_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX ux_user_subscription_store_purchase
    ON user_subscriptions (store_purchase_id)
    WHERE store_purchase_id IS NOT NULL;

CREATE UNIQUE INDEX ux_user_subscription_provider_reference
    ON user_subscriptions (provider_id, provider_subscription_reference)
    WHERE provider_subscription_reference IS NOT NULL;

CREATE INDEX ix_user_subscriptions_user_status
    ON user_subscriptions (user_id, subscription_status, current_period_end DESC);

CREATE INDEX ix_user_subscriptions_renewal_due
    ON user_subscriptions (next_billing_at)
    WHERE auto_renew_enabled = TRUE AND subscription_status = 'ACTIVE';

CREATE INDEX ix_user_subscriptions_period_end
    ON user_subscriptions (current_period_end)
    WHERE subscription_status IN ('ACTIVE', 'GRACE_PERIOD');

ALTER TABLE payment_orders
    ADD CONSTRAINT fk_payment_order_upgrade_from_owner
    FOREIGN KEY (upgrade_from_subscription_id, user_id)
    REFERENCES user_subscriptions (id, user_id) ON DELETE RESTRICT;

-- Links each paid order to the term it activates/extends. Both sides must
-- belong to the same user.
CREATE TABLE subscription_payment_allocations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL,
    subscription_id         UUID NOT NULL,
    payment_order_id        UUID NOT NULL UNIQUE,
    allocation_type         VARCHAR(20) NOT NULL
                            CHECK (allocation_type IN ('INITIAL', 'RENEWAL', 'UPGRADE', 'RECOVERY')),
    period_start            TIMESTAMPTZ NOT NULL,
    period_end              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_allocation_subscription_owner FOREIGN KEY (subscription_id, user_id)
        REFERENCES user_subscriptions (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_allocation_order_owner FOREIGN KEY (payment_order_id, user_id)
        REFERENCES payment_orders (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_subscription_allocation_period CHECK (period_end > period_start)
);

CREATE INDEX ix_subscription_allocations_subscription
    ON subscription_payment_allocations (subscription_id, period_start DESC);

-- Append-only subscription audit trail.
CREATE TABLE subscription_events (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id         UUID NOT NULL REFERENCES user_subscriptions(id) ON DELETE RESTRICT,
    payment_order_id        UUID REFERENCES payment_orders(id) ON DELETE RESTRICT,
    store_purchase_event_id UUID REFERENCES store_purchase_events(id) ON DELETE RESTRICT,
    event_type              VARCHAR(30) NOT NULL
                            CHECK (event_type IN ('CREATED', 'ACTIVATED', 'RENEWED', 'EXTENDED', 'UPGRADED', 'DOWNGRADED',
                                                  'GRACE_STARTED', 'PAST_DUE', 'PAUSED', 'RESUMED', 'AUTO_RENEW_ENABLED',
                                                  'AUTO_RENEW_DISABLED', 'CANCELLED', 'EXPIRED', 'REVOKED', 'REFUNDED',
                                                  'STORE_NOTIFICATION', 'ADMIN_ADJUSTMENT')),
    previous_status         VARCHAR(20),
    new_status              VARCHAR(20),
    actor_type              VARCHAR(20) NOT NULL DEFAULT 'SYSTEM'
                            CHECK (actor_type IN ('SYSTEM', 'USER', 'ADMIN', 'PROVIDER')),
    event_details           JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_subscription_events_subscription_created
    ON subscription_events (subscription_id, created_at DESC);

-- Seat-based organisation plans, invoiced through PARTNER/ADMIN channels.
CREATE TABLE organization_subscriptions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    plan_id                 UUID NOT NULL REFERENCES subscription_plans(id) ON DELETE RESTRICT,
    seat_count              INTEGER NOT NULL CHECK (seat_count > 0),
    subscription_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                            CHECK (subscription_status IN ('PENDING', 'ACTIVE', 'GRACE_PERIOD', 'PAST_DUE', 'CANCELLED', 'EXPIRED')),
    sales_channel           VARCHAR(24) NOT NULL DEFAULT 'PARTNER' CHECK (sales_channel IN ('PARTNER', 'ADMIN', 'WEB')),
    billing_reference       VARCHAR(255),      -- contract / invoice reference
    current_period_start    TIMESTAMPTZ,
    current_period_end      TIMESTAMPTZ,
    notes                   TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_org_subscription_owner UNIQUE (id, organization_id),
    CONSTRAINT ck_org_subscription_period CHECK (
        current_period_start IS NULL OR current_period_end IS NULL OR current_period_end > current_period_start
    )
);

CREATE INDEX ix_org_subscriptions_org_status
    ON organization_subscriptions (organization_id, subscription_status);

CREATE TABLE organization_seat_assignments (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_subscription_id UUID NOT NULL,
    organization_id             UUID NOT NULL,
    user_id                     UUID NOT NULL,
    assignment_status           VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (assignment_status IN ('ACTIVE', 'REVOKED')),
    assigned_by_user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    assigned_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at                  TIMESTAMPTZ,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_seat_org_subscription FOREIGN KEY (organization_subscription_id, organization_id)
        REFERENCES organization_subscriptions (id, organization_id) ON DELETE CASCADE,
    -- A seat can only be held by a current member of the same organisation.
    CONSTRAINT fk_seat_org_member FOREIGN KEY (organization_id, user_id)
        REFERENCES organization_members (organization_id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_seat_revoked CHECK ((assignment_status = 'REVOKED') = (revoked_at IS NOT NULL))
);

CREATE UNIQUE INDEX ux_seat_one_active_per_user
    ON organization_seat_assignments (organization_subscription_id, user_id)
    WHERE assignment_status = 'ACTIVE';

CREATE INDEX ix_seat_assignments_user
    ON organization_seat_assignments (user_id)
    WHERE assignment_status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- 15. AI credits and usage metering (D6, D7)
--
-- Order of consumption: plan allowance -> AI credits -> QUOTA_EXCEEDED.
-- Credits are lot-based with FIFO-by-expiry consumption. Usage is reserved,
-- then settled or released, so concurrent requests cannot overspend.
-- ---------------------------------------------------------------------------

CREATE TABLE ai_credit_accounts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID REFERENCES users(id) ON DELETE RESTRICT,
    organization_id     UUID REFERENCES organizations(id) ON DELETE RESTRICT,
    currency_unit       VARCHAR(20) NOT NULL DEFAULT 'AI_CREDIT',
    -- Denormalised sum of remaining lot units; maintained in the same
    -- transaction as lot/ledger writes under SELECT ... FOR UPDATE.
    balance_units       NUMERIC(18,6) NOT NULL DEFAULT 0 CHECK (balance_units >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_credit_account_one_owner CHECK (num_nonnulls(user_id, organization_id) = 1)
);

CREATE UNIQUE INDEX ux_credit_account_user
    ON ai_credit_accounts (user_id, currency_unit) WHERE user_id IS NOT NULL;

CREATE UNIQUE INDEX ux_credit_account_org
    ON ai_credit_accounts (organization_id, currency_unit) WHERE organization_id IS NOT NULL;

CREATE TABLE ai_credit_lots (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    credit_account_id   UUID NOT NULL REFERENCES ai_credit_accounts(id) ON DELETE RESTRICT,
    lot_source          VARCHAR(12) NOT NULL CHECK (lot_source IN ('PURCHASE', 'GRANT', 'PROMOTION', 'REFUND', 'ADJUSTMENT')),
    initial_units       NUMERIC(18,6) NOT NULL CHECK (initial_units > 0),
    remaining_units     NUMERIC(18,6) NOT NULL CHECK (remaining_units >= 0),
    expires_at          TIMESTAMPTZ,
    payment_order_id    UUID REFERENCES payment_orders(id) ON DELETE RESTRICT,
    granted_by_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_credit_lot_account UNIQUE (id, credit_account_id),
    CONSTRAINT ck_credit_lot_remaining CHECK (remaining_units <= initial_units),
    CONSTRAINT ck_credit_lot_purchase_order CHECK (lot_source <> 'PURCHASE' OR payment_order_id IS NOT NULL)
);

-- FIFO-by-expiry consumption order (NULL expiry consumed last).
CREATE INDEX ix_credit_lots_consumption
    ON ai_credit_lots (credit_account_id, expires_at NULLS LAST, created_at)
    WHERE remaining_units > 0;

-- Append-only credit ledger: one row per lot movement.
CREATE TABLE ai_credit_transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    credit_account_id   UUID NOT NULL REFERENCES ai_credit_accounts(id) ON DELETE RESTRICT,
    lot_id              UUID NOT NULL,
    ai_request_id       UUID,   -- logical -> ai_requests.id
    payment_order_id    UUID REFERENCES payment_orders(id) ON DELETE RESTRICT,
    payment_transaction_id UUID REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    transaction_type    VARCHAR(20) NOT NULL
                        CHECK (transaction_type IN ('PURCHASE', 'GRANT', 'CONSUMPTION', 'REFUND', 'EXPIRY', 'ADJUSTMENT', 'REVERSAL')),
    delta_units         NUMERIC(18,6) NOT NULL CHECK (delta_units <> 0),
    balance_after       NUMERIC(18,6) NOT NULL CHECK (balance_after >= 0),
    idempotency_key     VARCHAR(128),
    description         VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_credit_tx_lot_account FOREIGN KEY (lot_id, credit_account_id)
        REFERENCES ai_credit_lots (id, credit_account_id) ON DELETE RESTRICT,
    CONSTRAINT ck_credit_tx_sign CHECK (
        (transaction_type IN ('PURCHASE', 'GRANT', 'REFUND') AND delta_units > 0)
        OR (transaction_type IN ('CONSUMPTION', 'EXPIRY', 'REVERSAL') AND delta_units < 0)
        OR transaction_type = 'ADJUSTMENT'
    )
);

CREATE UNIQUE INDEX ux_ai_credit_transaction_idempotency
    ON ai_credit_transactions (credit_account_id, lot_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_ai_credit_transactions_account_created
    ON ai_credit_transactions (credit_account_id, created_at DESC);

CREATE INDEX ix_ai_credit_transactions_payment_order
    ON ai_credit_transactions (payment_order_id)
    WHERE payment_order_id IS NOT NULL;

-- Current usage per subject/feature/window. Hot rows are locked with
-- SELECT ... FOR UPDATE during reserve/settle. Windows: DAILY in the user's
-- market time zone; MONTHLY anchored to the paid period start (Free: calendar
-- month in market time zone).
CREATE TABLE usage_counters (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type        VARCHAR(16) NOT NULL CHECK (subject_type IN ('USER', 'ORGANIZATION', 'API_CLIENT')),
    user_id             UUID REFERENCES users(id) ON DELETE CASCADE,
    organization_id     UUID REFERENCES organizations(id) ON DELETE CASCADE,
    api_client_id       UUID REFERENCES api_clients(id) ON DELETE CASCADE,
    feature_code        VARCHAR(40) NOT NULL REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,
    period_start        TIMESTAMPTZ NOT NULL,
    period_end          TIMESTAMPTZ NOT NULL,
    used_units          BIGINT NOT NULL DEFAULT 0 CHECK (used_units >= 0),
    reserved_units      BIGINT NOT NULL DEFAULT 0 CHECK (reserved_units >= 0),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_usage_period CHECK (period_end > period_start),
    CONSTRAINT ck_usage_subject CHECK (
        (subject_type = 'USER' AND user_id IS NOT NULL AND organization_id IS NULL AND api_client_id IS NULL)
        OR (subject_type = 'ORGANIZATION' AND organization_id IS NOT NULL AND user_id IS NULL AND api_client_id IS NULL)
        OR (subject_type = 'API_CLIENT' AND api_client_id IS NOT NULL AND user_id IS NULL AND organization_id IS NULL)
    ),
    CONSTRAINT ux_usage_subject_feature_period UNIQUE NULLS NOT DISTINCT
        (subject_type, user_id, organization_id, api_client_id, feature_code, period_start, period_end)
);

CREATE INDEX ix_usage_counters_user_feature
    ON usage_counters (user_id, feature_code, period_start DESC)
    WHERE user_id IS NOT NULL;

-- Append-only reservation ledger (high volume, app-generated UUIDv7, no FK
-- target, logical references only). RESERVE carries expires_at; exactly one
-- SETTLE or RELEASE may close a reservation (reservation_id = RESERVE row id).
-- A sweeper RELEASEs reservations past expires_at (default 15 min).
CREATE TABLE usage_events (
    id                  UUID PRIMARY KEY,
    event_type          VARCHAR(8) NOT NULL CHECK (event_type IN ('RESERVE', 'SETTLE', 'RELEASE', 'ADJUST')),
    reservation_id      UUID,
    usage_counter_id    UUID NOT NULL,    -- logical -> usage_counters.id
    subject_type        VARCHAR(16) NOT NULL CHECK (subject_type IN ('USER', 'ORGANIZATION', 'API_CLIENT')),
    subject_id          UUID NOT NULL,    -- logical user/org/api-client id
    feature_code        VARCHAR(40) NOT NULL REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,
    units               BIGINT NOT NULL,
    ai_request_id       UUID,             -- logical -> ai_requests.id
    idempotency_key     VARCHAR(128),
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_usage_event_units CHECK (units <> 0 AND (event_type = 'ADJUST' OR units > 0)),
    CONSTRAINT ck_usage_event_reservation CHECK (
        (event_type = 'RESERVE' AND reservation_id IS NULL AND expires_at IS NOT NULL)
        OR (event_type IN ('SETTLE', 'RELEASE') AND reservation_id IS NOT NULL)
        OR event_type = 'ADJUST'
    )
);

CREATE UNIQUE INDEX ux_usage_events_one_close_per_reservation
    ON usage_events (reservation_id)
    WHERE event_type IN ('SETTLE', 'RELEASE');

CREATE UNIQUE INDEX ux_usage_events_idempotency
    ON usage_events (subject_id, event_type, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_usage_events_open_reservations
    ON usage_events (expires_at)
    WHERE event_type = 'RESERVE';

CREATE INDEX brin_usage_events_created
    ON usage_events USING BRIN (created_at);

-- ---------------------------------------------------------------------------
-- 16. Feature flags and remote configuration
-- ---------------------------------------------------------------------------

CREATE TABLE feature_flags (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    flag_code           VARCHAR(80) NOT NULL UNIQUE,
    description         TEXT,
    feature_code        VARCHAR(40) REFERENCES feature_catalog(feature_code) ON DELETE SET NULL,
    is_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    rollout_percentage  SMALLINT NOT NULL DEFAULT 0 CHECK (rollout_percentage BETWEEN 0 AND 100),
    platform_scope      VARCHAR(20) NOT NULL DEFAULT 'ALL'
                        CHECK (platform_scope IN ('ALL', 'ANDROID', 'IOS', 'WEB')),
    min_app_version     VARCHAR(40),
    -- e.g. {"markets":["ET"],"plans":["PREMIUM"],"distributionChannels":["DIRECT_DOWNLOAD"]}
    targeting           JSONB NOT NULL DEFAULT '{}'::JSONB,
    configuration       JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_feature_overrides (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature_flag_id     UUID NOT NULL REFERENCES feature_flags(id) ON DELETE CASCADE,
    is_enabled          BOOLEAN NOT NULL,
    reason              VARCHAR(255),
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_user_feature_override UNIQUE (user_id, feature_flag_id)
);

CREATE TABLE app_configurations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key          VARCHAR(120) NOT NULL,
    platform_scope      VARCHAR(20) NOT NULL DEFAULT 'ALL'
                        CHECK (platform_scope IN ('ALL', 'ANDROID', 'IOS', 'WEB', 'BACKEND')),
    config_version      INTEGER NOT NULL DEFAULT 1 CHECK (config_version > 0),
    config_value        JSONB NOT NULL,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_app_config_version UNIQUE (config_key, platform_scope, config_version)
);

CREATE UNIQUE INDEX ux_app_config_active
    ON app_configurations (config_key, platform_scope)
    WHERE is_active = TRUE;

-- ---------------------------------------------------------------------------
-- 17. Admin roles, audit trail and transactional outbox
--
-- Admins sign in via WORKFORCE_OIDC with MFA. Four-eyes actions (D25) record
-- both actor and approver in audit_events: activating a payment provider or
-- merchant account, approving a market/channel policy, publishing a STABLE
-- language resource, activating a transliteration profile, changing prices.
-- ---------------------------------------------------------------------------

CREATE TABLE admin_roles (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_code           VARCHAR(40) NOT NULL UNIQUE,
    description         TEXT,
    permissions         TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    requires_mfa        BOOLEAN NOT NULL DEFAULT TRUE CHECK (requires_mfa = TRUE),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO admin_roles (role_code, description, permissions)
VALUES
    ('SUPER_ADMIN', 'Break-glass administration; every use is audited.', ARRAY['*']),
    ('LANGUAGE_EDITOR', 'Prepares language resources, terms and profiles.',
     ARRAY['languages:read', 'resources:upload', 'resources:edit', 'terms:edit', 'suggestions:triage', 'profiles:edit']),
    ('LANGUAGE_REVIEWER', 'Native-speaker reviewer; approves resources and profiles (second pair of eyes).',
     ARRAY['languages:read', 'resources:approve', 'resources:publish', 'profiles:approve', 'terms:approve',
           'suggestions:review', 'evaluations:record']),
    ('AI_OPERATOR', 'Manages AI providers, routing, prompts and capability tiers.',
     ARRAY['ai:read', 'ai:configure', 'ai:routing', 'prompts:edit', 'prompts:approve', 'capabilities:edit', 'evaluations:read']),
    ('FINANCE', 'Prices, providers, refunds, receipts and reconciliation.',
     ARRAY['billing:read', 'prices:edit', 'prices:approve', 'providers:configure', 'providers:activate',
           'refunds:execute', 'reconciliation:manage', 'manual_payments:review', 'organizations:billing']),
    ('SUPPORT', 'Customer support with minimal data access.',
     ARRAY['users:read_limited', 'subscriptions:read', 'orders:read', 'manual_payments:triage', 'privacy_requests:assist']),
    ('POLICY_REVIEWER', 'Approves market/channel billing policies and consent text versions.',
     ARRAY['billing_policies:read', 'billing_policies:approve', 'consents:manage_versions']),
    ('AUDITOR', 'Read-only access to audit and configuration.',
     ARRAY['audit:read', 'billing:read', 'ai:read', 'languages:read', 'organizations:read'])
ON CONFLICT (role_code) DO NOTHING;

CREATE TABLE user_admin_roles (
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    admin_role_id       UUID NOT NULL REFERENCES admin_roles(id) ON DELETE RESTRICT,
    assigned_by         UUID REFERENCES users(id) ON DELETE SET NULL,
    assigned_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ,
    PRIMARY KEY (user_id, admin_role_id)
);

-- Append-only audit trail (high volume, UUIDv7, no FK target, logical refs).
CREATE TABLE audit_events (
    id                  UUID PRIMARY KEY,
    actor_type          VARCHAR(16) NOT NULL DEFAULT 'USER'
                        CHECK (actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'API_CLIENT', 'PROVIDER')),
    actor_user_id       UUID,     -- logical -> users.id
    actor_api_client_id UUID,     -- logical -> api_clients.id
    approved_by_user_id UUID,     -- four-eyes approver (logical -> users.id)
    action_code         VARCHAR(100) NOT NULL,
    entity_type         VARCHAR(80) NOT NULL,
    entity_id           VARCHAR(100),
    outcome             VARCHAR(20) NOT NULL DEFAULT 'SUCCESS'
                        CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    correlation_id      VARCHAR(100),
    ip_address          INET,
    metadata            JSONB NOT NULL DEFAULT '{}'::JSONB,   -- never user text, tokens or secrets
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_audit_four_eyes CHECK (approved_by_user_id IS NULL OR approved_by_user_id IS DISTINCT FROM actor_user_id)
);

CREATE INDEX brin_audit_events_created
    ON audit_events USING BRIN (created_at);

CREATE INDEX ix_audit_events_actor_created
    ON audit_events (actor_user_id, created_at DESC)
    WHERE actor_user_id IS NOT NULL;

CREATE INDEX ix_audit_events_entity
    ON audit_events (entity_type, entity_id, created_at DESC);

-- Transactional outbox (D19): written in the same transaction as the state
-- change, published by a poller (FOR UPDATE SKIP LOCKED). Consumers must be
-- idempotent on id. Payloads carry ids and metadata, never user text.
CREATE TABLE outbox_events (
    id                  UUID PRIMARY KEY,
    aggregate_type      VARCHAR(80) NOT NULL,
    aggregate_id        VARCHAR(100) NOT NULL,
    event_type          VARCHAR(120) NOT NULL,
    payload             JSONB NOT NULL DEFAULT '{}'::JSONB,
    event_status        VARCHAR(12) NOT NULL DEFAULT 'PENDING'
                        CHECK (event_status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED', 'DEAD')),
    attempts            SMALLINT NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts        SMALLINT NOT NULL DEFAULT 10 CHECK (max_attempts > 0),
    available_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    locked_until        TIMESTAMPTZ,
    last_error          VARCHAR(1000),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at        TIMESTAMPTZ
);

CREATE INDEX ix_outbox_events_pending
    ON outbox_events (available_at)
    WHERE event_status IN ('PENDING', 'FAILED');

CREATE INDEX brin_outbox_events_created
    ON outbox_events USING BRIN (created_at);

-- ---------------------------------------------------------------------------
-- 18. Generic triggers: updated_at maintenance and append-only guards
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

-- Attach set_updated_at to every base table in this schema that has an
-- updated_at column. New tables in later migrations must attach it explicitly.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.table_name
        FROM information_schema.columns c
        JOIN information_schema.tables t
          ON t.table_schema = c.table_schema AND t.table_name = c.table_name
        WHERE c.table_schema = current_schema()
          AND c.column_name = 'updated_at'
          AND t.table_type = 'BASE TABLE'
        ORDER BY c.table_name
    LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION set_updated_at()',
            'trg_' || r.table_name || '_updated_at', r.table_name);
    END LOOP;
END $$;

-- Append-only guard for ledgers and audit trails.
--   * UPDATE/DELETE issued by application code is rejected.
--   * Referential actions (ON DELETE CASCADE/SET NULL fired from a parent row)
--     run at trigger depth > 1 and are allowed, so account deletion works.
--   * The retention-purge job may delete expired rows after
--     SET LOCAL app.retention_purge = 'on' (DELETE only, never UPDATE).
CREATE OR REPLACE FUNCTION fn_forbid_update_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF pg_trigger_depth() > 1 THEN
        RETURN COALESCE(NEW, OLD);
    END IF;
    IF TG_OP = 'DELETE' AND COALESCE(current_setting('app.retention_purge', TRUE), '') = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% is append-only; % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'payment_transactions', 'ai_credit_transactions', 'usage_events', 'audit_events',
        'subscription_events', 'payment_mandate_events', 'store_purchase_events', 'user_consents'
    ]
    LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION fn_forbid_update_delete()',
            'trg_' || t || '_append_only', t);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 19. Business-rule triggers
-- ---------------------------------------------------------------------------

-- 19.1 An ACTIVE transliteration profile must reference a PUBLISHED, signed
--      TRANSLITERATION_RULES resource of the same language, signed by a
--      non-revoked key.
CREATE OR REPLACE FUNCTION validate_transliteration_profile_activation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_resource_type       VARCHAR(30);
    v_publication_status  VARCHAR(20);
    v_signature_key_id    UUID;
    v_manifest_signature  TEXT;
    v_key_status          VARCHAR(12);
BEGIN
    IF NEW.rules_resource_id IS NOT NULL THEN
        SELECT r.resource_type, r.publication_status, r.signature_key_id, r.manifest_signature, k.key_status
          INTO v_resource_type, v_publication_status, v_signature_key_id, v_manifest_signature, v_key_status
          FROM language_resources r
          LEFT JOIN resource_signing_keys k ON k.id = r.signature_key_id
         WHERE r.id = NEW.rules_resource_id
           AND r.language_id = NEW.language_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Transliteration rules resource must belong to the profile language';
        END IF;
        IF v_resource_type <> 'TRANSLITERATION_RULES' THEN
            RAISE EXCEPTION 'Transliteration profile resource must have resource_type TRANSLITERATION_RULES';
        END IF;
    END IF;

    IF NEW.profile_status = 'ACTIVE' THEN
        IF NEW.rules_resource_id IS NULL THEN
            RAISE EXCEPTION 'An active transliteration profile requires a rules resource';
        END IF;
        IF v_publication_status IS DISTINCT FROM 'PUBLISHED' THEN
            RAISE EXCEPTION 'An active transliteration profile requires a published rules resource';
        END IF;
        IF v_signature_key_id IS NULL OR v_manifest_signature IS NULL OR v_key_status = 'REVOKED' THEN
            RAISE EXCEPTION 'An active transliteration profile requires a validly signed resource manifest';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_transliteration_profile_activation
    BEFORE INSERT OR UPDATE ON transliteration_profiles
    FOR EACH ROW EXECUTE FUNCTION validate_transliteration_profile_activation();

-- 19.2 A rules resource backing an ACTIVE profile cannot be unpublished or
--      stripped of its signature; retire/replace the profile first.
CREATE OR REPLACE FUNCTION fn_protect_active_transliteration_resource()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.publication_status <> 'PUBLISHED' OR NEW.signature_key_id IS NULL)
       AND EXISTS (
           SELECT 1 FROM transliteration_profiles p
           WHERE p.rules_resource_id = OLD.id AND p.profile_status = 'ACTIVE'
       ) THEN
        RAISE EXCEPTION 'Resource % backs an ACTIVE transliteration profile; retire the profile first', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_protect_active_transliteration_resource
    BEFORE UPDATE ON language_resources
    FOR EACH ROW EXECUTE FUNCTION fn_protect_active_transliteration_resource();

-- 19.3 A user may only SELECT an active profile matching the typing language.
--      F14 fix: validate only on INSERT or when the mode/profile/language
--      changes, so retiring a profile never blocks unrelated edits (e.g.
--      reordering). Clients fall back to DIRECT_SCRIPT_LAYOUT if the cached
--      profile was retired.
CREATE OR REPLACE FUNCTION validate_user_typing_transliteration_profile()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_check          BOOLEAN;
    v_profile_status VARCHAR(20);
BEGIN
    IF NEW.input_mode <> 'PHONETIC_LATIN_TO_NATIVE' THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        v_check := TRUE;
    ELSE
        v_check := NEW.input_mode IS DISTINCT FROM OLD.input_mode
                OR NEW.transliteration_profile_id IS DISTINCT FROM OLD.transliteration_profile_id
                OR NEW.language_id IS DISTINCT FROM OLD.language_id;
    END IF;

    IF v_check THEN
        SELECT profile_status INTO v_profile_status
          FROM transliteration_profiles
         WHERE id = NEW.transliteration_profile_id
           AND language_id = NEW.language_id;

        IF NOT FOUND OR v_profile_status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'Selected transliteration profile must be active and match the typing language';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_user_typing_transliteration_profile
    BEFORE INSERT OR UPDATE ON user_typing_languages
    FOR EACH ROW EXECUTE FUNCTION validate_user_typing_transliteration_profile();

-- 19.4 New orders: product ACTIVE and type-compatible, sellable plan, price
--      active at creation time, tax only on EXCLUSIVE prices. Composite FKs
--      already enforce product/price/market/channel/currency/list amount.
CREATE OR REPLACE FUNCTION fn_validate_payment_order_business_rules()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_product_type      VARCHAR(24);
    v_product_status    VARCHAR(20);
    v_plan_sellable     BOOLEAN;
    v_plan_status       VARCHAR(20);
    v_price_active      BOOLEAN;
    v_price_valid_from  TIMESTAMPTZ;
    v_price_valid_until TIMESTAMPTZ;
    v_tax_behavior      VARCHAR(16);
BEGIN
    SELECT bp.product_type, bp.product_status, sp.is_sellable, sp.plan_status
      INTO v_product_type, v_product_status, v_plan_sellable, v_plan_status
      FROM billing_products bp
      LEFT JOIN subscription_plans sp ON sp.id = bp.plan_id
     WHERE bp.id = NEW.billing_product_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Billing product does not exist';
    END IF;
    IF v_product_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'Billing product is not ACTIVE';
    END IF;
    IF v_product_type = 'SUBSCRIPTION' AND (v_plan_sellable IS NOT TRUE OR v_plan_status <> 'ACTIVE') THEN
        RAISE EXCEPTION 'Subscription plan is not sellable';
    END IF;

    SELECT is_active, valid_from, valid_until, tax_behavior
      INTO v_price_active, v_price_valid_from, v_price_valid_until, v_tax_behavior
      FROM billing_prices WHERE id = NEW.billing_price_id;
    IF NOT FOUND OR v_price_active IS DISTINCT FROM TRUE
       OR v_price_valid_from > NOW()
       OR (v_price_valid_until IS NOT NULL AND v_price_valid_until <= NOW()) THEN
        RAISE EXCEPTION 'The selected billing price is not active at order creation time';
    END IF;

    IF v_tax_behavior <> 'EXCLUSIVE' AND NEW.tax_minor <> 0 THEN
        RAISE EXCEPTION 'tax_minor must be 0 unless the price is tax EXCLUSIVE';
    END IF;

    IF (NEW.order_type IN ('SUBSCRIPTION_PURCHASE', 'SUBSCRIPTION_RENEWAL', 'SUBSCRIPTION_UPGRADE') AND v_product_type <> 'SUBSCRIPTION')
       OR (NEW.order_type = 'AI_CREDIT_TOPUP' AND v_product_type <> 'AI_CREDIT_PACK')
       OR (NEW.order_type = 'ADD_ON_PURCHASE' AND v_product_type <> 'ADD_ON') THEN
        RAISE EXCEPTION 'Order type does not match the billing product type';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_order_validate_product_price
    BEFORE INSERT ON payment_orders
    FOR EACH ROW EXECUTE FUNCTION fn_validate_payment_order_business_rules();

-- 19.5 New attempts: owned, unpaid, unexpired order; provider, merchant
--      account, base method and account method all active/available for this
--      market/currency/environment; order amount within both methods' limits.
CREATE OR REPLACE FUNCTION fn_require_active_payment_account_for_attempt()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_order_status     VARCHAR(24);
    v_order_expires_at TIMESTAMPTZ;
    v_amount           BIGINT;
BEGIN
    SELECT order_status, expires_at, amount_minor
      INTO v_order_status, v_order_expires_at, v_amount
      FROM payment_orders
     WHERE id = NEW.payment_order_id AND user_id = NEW.user_id
       FOR UPDATE;

    IF NOT FOUND OR v_order_status NOT IN ('CREATED', 'AWAITING_PAYMENT')
       OR (v_order_expires_at IS NOT NULL AND v_order_expires_at <= NOW()) THEN
        RAISE EXCEPTION 'Payment attempt requires an owned, unpaid, non-expired order';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM payment_providers p
        JOIN payment_provider_accounts a ON a.provider_id = p.id
        JOIN payment_provider_account_methods am ON am.provider_account_id = a.id
        JOIN provider_payment_methods pm ON pm.id = am.provider_payment_method_id
        WHERE p.id = NEW.provider_id
          AND p.is_active = TRUE AND p.provider_status = 'ACTIVE'
          AND a.id = NEW.provider_account_id
          AND a.country_code = NEW.market_country_code
          AND a.currency_code = NEW.currency_code
          AND a.environment = NEW.environment
          AND a.is_active = TRUE AND a.account_status = 'ACTIVE'
          AND am.id = NEW.provider_account_method_id
          AND am.provider_payment_method_id = NEW.provider_payment_method_id
          AND am.is_enabled = TRUE AND am.availability_status = 'AVAILABLE'
          AND pm.provider_id = p.id
          AND pm.is_enabled = TRUE AND pm.availability_status = 'AVAILABLE'
          AND (am.min_amount_minor IS NULL OR v_amount >= am.min_amount_minor)
          AND (am.max_amount_minor IS NULL OR v_amount <= am.max_amount_minor)
          AND (pm.min_amount_minor IS NULL OR v_amount >= pm.min_amount_minor)
          AND (pm.max_amount_minor IS NULL OR v_amount <= pm.max_amount_minor)
    ) THEN
        RAISE EXCEPTION 'The selected payment provider, merchant account or payment method is not currently enabled for this market/currency/environment/amount';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_attempt_requires_active_merchant_account
    BEFORE INSERT ON payment_attempts
    FOR EACH ROW EXECUTE FUNCTION fn_require_active_payment_account_for_attempt();

-- 19.6 New mandates: the referenced consent must be a granted PAYMENT_MANDATE
--      decision for this user that has not been superseded by a later
--      revocation (append-only consent log), and both method rows must be
--      approved for automatic renewal on an active merchant account.
CREATE OR REPLACE FUNCTION fn_require_recurring_payment_account_method()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM user_consents c
        WHERE c.id = NEW.consent_record_id
          AND c.user_id = NEW.user_id
          AND c.consent_code = 'PAYMENT_MANDATE'
          AND c.is_granted = TRUE
          AND c.recorded_at <= NEW.consented_at
          AND NOT EXISTS (
              SELECT 1 FROM user_consents later
              WHERE later.user_id = c.user_id
                AND later.consent_code = 'PAYMENT_MANDATE'
                AND later.recorded_at > c.recorded_at
                AND later.is_granted = FALSE
          )
    ) THEN
        RAISE EXCEPTION 'An unrevoked PAYMENT_MANDATE consent for this user is required';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM payment_provider_account_methods am
        JOIN payment_provider_accounts a ON a.id = am.provider_account_id
        JOIN provider_payment_methods pm ON pm.id = am.provider_payment_method_id
        JOIN payment_providers p ON p.id = am.provider_id
        WHERE am.id = NEW.provider_account_method_id
          AND am.provider_account_id = NEW.provider_account_id
          AND am.provider_id = NEW.provider_id
          AND am.provider_payment_method_id = NEW.provider_payment_method_id
          AND am.is_enabled = TRUE AND am.availability_status = 'AVAILABLE'
          AND am.supports_auto_renew = TRUE
          AND a.is_active = TRUE AND a.account_status = 'ACTIVE'
          AND pm.is_enabled = TRUE AND pm.availability_status = 'AVAILABLE'
          AND pm.supports_auto_renew = TRUE
          AND p.is_active = TRUE AND p.provider_status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'The selected merchant account method is not approved for automatic renewal';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_mandate_requires_recurring_account_method
    BEFORE INSERT ON payment_mandates
    FOR EACH ROW EXECUTE FUNCTION fn_require_recurring_payment_account_method();

-- 19.7 Seat count: active seats may not exceed the subscription's seat_count.
--      Locks the subscription row so concurrent assignments serialise.
CREATE OR REPLACE FUNCTION fn_enforce_org_seat_count()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_seat_count INTEGER;
    v_active     INTEGER;
BEGIN
    IF NEW.assignment_status <> 'ACTIVE' THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.assignment_status = 'ACTIVE'
       AND OLD.organization_subscription_id = NEW.organization_subscription_id THEN
        RETURN NEW;
    END IF;

    SELECT seat_count INTO v_seat_count
      FROM organization_subscriptions
     WHERE id = NEW.organization_subscription_id
       FOR UPDATE;

    SELECT COUNT(*) INTO v_active
      FROM organization_seat_assignments
     WHERE organization_subscription_id = NEW.organization_subscription_id
       AND assignment_status = 'ACTIVE'
       AND id <> NEW.id;

    IF v_active + 1 > v_seat_count THEN
        RAISE EXCEPTION 'All % seats of organisation subscription % are assigned', v_seat_count, NEW.organization_subscription_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enforce_org_seat_count
    BEFORE INSERT OR UPDATE ON organization_seat_assignments
    FOR EACH ROW EXECUTE FUNCTION fn_enforce_org_seat_count();

-- 19.8 Plan entitlements: the composite FK fk_plan_entitlement_feature_binding
--      already rejects TYPING/ungated features and unit mismatches
--      declaratively (no trigger needed).

-- ---------------------------------------------------------------------------
-- 20. Seed: provisional plan entitlements (quotaPolicyVersion PROVISIONAL_V2)
--
-- Units: REQUEST = count; SECOND = audio seconds (rounded up per request to a
-- whole second, shown to users as minutes); CHARACTER = synthesized chars.
-- Rows listed below are ENABLED; every other gated feature is inserted as
-- disabled for every plan so the matrix is explicit and complete.
-- ---------------------------------------------------------------------------

WITH enabled_seed(plan_code, feature_code, included_units, reset_period) AS (
    VALUES
    -- GUEST (attested anonymous installation)
    ('GUEST', 'TRANSLATE',          3::BIGINT,    'DAILY'),
    ('GUEST', 'GRAMMAR_CORRECTION', 3::BIGINT,    'DAILY'),
    ('GUEST', 'REWRITE',            1::BIGINT,    'DAILY'),
    ('GUEST', 'SCRIPT_CONVERSION',  2::BIGINT,    'DAILY'),

    -- FREE
    ('FREE', 'TRANSLATE',           10::BIGINT,   'DAILY'),
    ('FREE', 'GRAMMAR_CORRECTION',  10::BIGINT,   'DAILY'),
    ('FREE', 'REWRITE',             3::BIGINT,    'DAILY'),
    ('FREE', 'EXPLAIN_TEXT',        3::BIGINT,    'DAILY'),
    ('FREE', 'SCRIPT_CONVERSION',   5::BIGINT,    'DAILY'),
    ('FREE', 'SPEECH_TO_TEXT',      600::BIGINT,  'MONTHLY'),
    ('FREE', 'TEXT_TO_SPEECH',      5000::BIGINT, 'MONTHLY'),
    ('FREE', 'HISTORY_SYNC',        NULL::BIGINT, 'NONE'),
    ('FREE', 'DICTIONARY_SYNC',     NULL::BIGINT, 'NONE'),

    -- PREMIUM
    ('PREMIUM', 'TRANSLATE',           600::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'GRAMMAR_CORRECTION',  600::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'REWRITE',             300::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'TONE_ADJUST',         300::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'SMART_REPLY',         150::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'SUMMARIZE',           100::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'EXPLAIN_TEXT',        100::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'DRAFT_DOCUMENT',      50::BIGINT,     'MONTHLY'),
    ('PREMIUM', 'SCRIPT_CONVERSION',   300::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'SPEECH_TO_TEXT',      3600::BIGINT,   'MONTHLY'),
    ('PREMIUM', 'SPEECH_TRANSLATE',    1800::BIGINT,   'MONTHLY'),
    ('PREMIUM', 'TEXT_TO_SPEECH',      100000::BIGINT, 'MONTHLY'),
    ('PREMIUM', 'VOICE_TO_VOICE',      900::BIGINT,    'MONTHLY'),
    ('PREMIUM', 'HISTORY_SYNC',        NULL::BIGINT,   'NONE'),
    ('PREMIUM', 'DICTIONARY_SYNC',     NULL::BIGINT,   'NONE'),
    ('PREMIUM', 'PERSONAL_STYLE',      NULL::BIGINT,   'NONE'),
    ('PREMIUM', 'ON_DEVICE_AI_MODELS', NULL::BIGINT,   'NONE'),

    -- PREMIUM_PLUS
    ('PREMIUM_PLUS', 'TRANSLATE',           2000::BIGINT,   'MONTHLY'),
    ('PREMIUM_PLUS', 'GRAMMAR_CORRECTION',  2000::BIGINT,   'MONTHLY'),
    ('PREMIUM_PLUS', 'REWRITE',             1000::BIGINT,   'MONTHLY'),
    ('PREMIUM_PLUS', 'TONE_ADJUST',         1000::BIGINT,   'MONTHLY'),
    ('PREMIUM_PLUS', 'SMART_REPLY',         500::BIGINT,    'MONTHLY'),
    ('PREMIUM_PLUS', 'SUMMARIZE',           500::BIGINT,    'MONTHLY'),
    ('PREMIUM_PLUS', 'EXPLAIN_TEXT',        500::BIGINT,    'MONTHLY'),
    ('PREMIUM_PLUS', 'DRAFT_DOCUMENT',      200::BIGINT,    'MONTHLY'),
    ('PREMIUM_PLUS', 'SCRIPT_CONVERSION',   1000::BIGINT,   'MONTHLY'),
    ('PREMIUM_PLUS', 'SPEECH_TO_TEXT',      18000::BIGINT,  'MONTHLY'),
    ('PREMIUM_PLUS', 'SPEECH_TRANSLATE',    9000::BIGINT,   'MONTHLY'),
    ('PREMIUM_PLUS', 'TEXT_TO_SPEECH',      500000::BIGINT, 'MONTHLY'),
    ('PREMIUM_PLUS', 'VOICE_TO_VOICE',      5400::BIGINT,   'MONTHLY'),
    ('PREMIUM_PLUS', 'LIVE_CONVERSATION',   3600::BIGINT,   'MONTHLY'),
    ('PREMIUM_PLUS', 'HISTORY_SYNC',        NULL::BIGINT,   'NONE'),
    ('PREMIUM_PLUS', 'DICTIONARY_SYNC',     NULL::BIGINT,   'NONE'),
    ('PREMIUM_PLUS', 'PERSONAL_STYLE',      NULL::BIGINT,   'NONE'),
    ('PREMIUM_PLUS', 'CUSTOM_GLOSSARY',     NULL::BIGINT,   'NONE'),
    ('PREMIUM_PLUS', 'ON_DEVICE_AI_MODELS', NULL::BIGINT,   'NONE'),

    -- BUSINESS (per seat)
    ('BUSINESS', 'TRANSLATE',           3000::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'GRAMMAR_CORRECTION',  3000::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'REWRITE',             2000::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'TONE_ADJUST',         2000::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'SMART_REPLY',         1000::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'SUMMARIZE',           1000::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'EXPLAIN_TEXT',        1000::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'DRAFT_DOCUMENT',      500::BIGINT,     'MONTHLY'),
    ('BUSINESS', 'SCRIPT_CONVERSION',   2000::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'SPEECH_TO_TEXT',      36000::BIGINT,   'MONTHLY'),
    ('BUSINESS', 'SPEECH_TRANSLATE',    18000::BIGINT,   'MONTHLY'),
    ('BUSINESS', 'TEXT_TO_SPEECH',      1000000::BIGINT, 'MONTHLY'),
    ('BUSINESS', 'VOICE_TO_VOICE',      10800::BIGINT,   'MONTHLY'),
    ('BUSINESS', 'LIVE_CONVERSATION',   7200::BIGINT,    'MONTHLY'),
    ('BUSINESS', 'HISTORY_SYNC',        NULL::BIGINT,    'NONE'),
    ('BUSINESS', 'DICTIONARY_SYNC',     NULL::BIGINT,    'NONE'),
    ('BUSINESS', 'PERSONAL_STYLE',      NULL::BIGINT,    'NONE'),
    ('BUSINESS', 'CUSTOM_GLOSSARY',     NULL::BIGINT,    'NONE'),
    ('BUSINESS', 'ON_DEVICE_AI_MODELS', NULL::BIGINT,    'NONE')
)
INSERT INTO plan_entitlements
    (plan_id, feature_code, metering_unit, is_entitlement_gated, access_enabled, included_units, reset_period, configuration)
SELECT p.id, f.feature_code, f.metering_unit, TRUE, TRUE, e.included_units, e.reset_period,
       jsonb_build_object('quotaPolicyVersion', 'PROVISIONAL_V2', 'requiresServerEnforcement', TRUE)
FROM enabled_seed e
JOIN subscription_plans p ON p.plan_code = e.plan_code
JOIN feature_catalog f ON f.feature_code = e.feature_code
ON CONFLICT (plan_id, feature_code) DO NOTHING;

-- Explicit "disabled" rows for every remaining (plan, gated feature) pair.
INSERT INTO plan_entitlements
    (plan_id, feature_code, metering_unit, is_entitlement_gated, access_enabled, included_units, reset_period, configuration)
SELECT p.id, f.feature_code, f.metering_unit, TRUE, FALSE,
       CASE WHEN f.metering_unit = 'NONE' THEN NULL ELSE 0 END,
       'NONE',
       jsonb_build_object('quotaPolicyVersion', 'PROVISIONAL_V2', 'requiresServerEnforcement', TRUE)
FROM subscription_plans p
CROSS JOIN feature_catalog f
WHERE f.is_entitlement_gated = TRUE
  AND p.plan_code IN ('GUEST', 'FREE', 'PREMIUM', 'PREMIUM_PLUS', 'BUSINESS')
ON CONFLICT (plan_id, feature_code) DO NOTHING;

COMMIT;

-- ============================================================================
-- End of v2.0 baseline.
--
-- Implementation notes (binding for the backend; see System Design v2.0):
--
--  1. Flyway: ship this file as V1__baseline.sql. Never edit it after it has
--     been applied to a shared environment; add forward-only migrations.
--  2. IDs: ai_requests, usage_events, audit_events and outbox_events have no
--     DB default — the application generates UUIDv7 (or uses PG18 uuidv7()).
--     These tables are never FK targets. Introduce monthly range partitioning
--     on created_at when a table passes ~50M rows or purges become expensive.
--  3. Usage algorithm (D6), in one DB transaction per step:
--       a) resolve effective plan (highest plan_rank over ACTIVE/GRACE_PERIOD
--          user_subscriptions + ACTIVE organization_seat_assignments; else
--          FREE for registered users, GUEST for attested guests);
--       b) upsert + SELECT ... FOR UPDATE the usage_counters row for the
--          window; if used+reserved+estimate <= included_units, RESERVE
--          (insert usage_events RESERVE, bump reserved_units);
--       c) otherwise fall back to credits: lock ai_credit_accounts, consume
--          lots FIFO by expires_at (ai_credit_transactions per lot,
--          balance_after maintained), usage_source = CREDITS;
--       d) otherwise return QUOTA_EXCEEDED (RFC 9457) with resetAt;
--       e) on completion SETTLE actual units (move reserved -> used), on
--          failure RELEASE; a sweeper RELEASEs RESERVE rows past expires_at.
--  4. Credits (D7): purchases/grants create ai_credit_lots; expiry job writes
--     EXPIRY ledger rows and zeroes remaining_units. Never edit ledger rows.
--  5. Entitlement token (D8): GET /me/entitlements returns an Ed25519 JWS
--     (24h validity, 72h offline grace) for OFFLINE gates only (e.g.
--     ON_DEVICE_AI_MODELS). Every server-metered feature is re-checked here.
--  6. Idempotency (F3): AI idempotency keys guarantee at-most-once charging.
--     Replays come from an encrypted <=10-minute ephemeral cache (e.g. Redis)
--     or are recomputed with usage_source = UNMETERED. PostgreSQL never
--     stores AI input/output text.
--  7. Fulfilment: never create a subscription or credits from a client
--     redirect or an unverified webhook. Verify server-to-server (gateway API,
--     Play Developer API, App Store Server API), then in ONE transaction:
--     attempt/order status, payment_transactions row, subscription/allocation
--     or credit lot + ledger, subscription_events, payment_receipts,
--     outbox_events. Use row locks + idempotency keys.
--  8. Market policy: a missing/expired/unapproved billing_market_channel_
--     policies row means DENY. Do not infer platform permission from gateway
--     capability. Keep MANUAL_RENEW the Ethiopian default; a one-off wallet
--     charge is never consent to debit again.
--  9. Store purchases: acknowledge Google Play purchases within 3 days
--     (ix_store_purchases_unacknowledged drives the job). Keep
--     purchase_token_ciphertext only while re-verification is needed.
-- 10. Retention purges run with SET LOCAL app.retention_purge = 'on' and only
--     DELETE: expired user_history_items, otp_challenges, password reset
--     tokens, temporary audio objects (delete the object, then set
--     audio_deleted_at — a DB row deletion does not delete a file), old
--     webhook payloads, and ledger/audit rows past their legal retention.
-- 11. Account deletion: revoke sessions, cancel mandates/auto-renew, DESTROY
--     user_data_keys (crypto-shred), delete personal rows (history,
--     dictionary, profiles, style, glossaries, consents via cascade), and
--     anonymise the users row (email/phone/display_name NULL, status DELETED).
--     Financial records are retained under RESTRICT FKs for legal periods.
-- 12. Four-eyes (D25): activating providers/accounts, approving market
--     policies, publishing STABLE resources, activating transliteration
--     profiles and changing prices require a second admin; record both
--     actor_user_id and approved_by_user_id in audit_events.
-- 13. Transliteration: AM/TI profiles are seeded DRAFT. Activate only after
--     native-speaker approval and both mobile bindings of keyboard-core pass
--     the shared golden vectors. PHONETIC_LATIN_TO_NATIVE is a local input
--     mode — never an AI request, never a keystroke upload.
-- 14. Licence gate: a model/dataset may ship only if language_datasets /
--     language_resources.license_code permit COMMERCIAL use.
-- 15. AI providers stay DISABLED until a DPA is signed and
--     trains_on_customer_data is contractually FALSE (ck_ai_provider_active_contract).
-- 16. The seed language list is a starting catalogue, not a statement that
--     every listed language is production-ready (see support_level).
-- ============================================================================
