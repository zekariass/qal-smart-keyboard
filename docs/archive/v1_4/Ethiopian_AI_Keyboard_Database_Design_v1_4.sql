-- ============================================================================
-- Ethiopian AI Keyboard — PostgreSQL schema design
-- Version: 1.4 (merchant accounts, billing integrity and starter entitlements)
-- Target: PostgreSQL 17+ (uses gen_random_uuid() and the btree_gist extension)
--
-- This is a production-oriented starting schema, not a substitute for reviewed
-- Flyway migrations. Review constraints, indexes, retention, and store purchase
-- verification rules before deployment.
--
-- Privacy rules encoded by design:
--   * No ordinary keystroke or raw draft table.
--   * ai_requests stores operational metadata, not input/output text.
--   * voice_jobs stores metadata only; audio is not retained by default.
--   * Optional saved AI results use encrypted ciphertext fields.
--   * Contact references are opaque hashes, not uploaded address-book records.
--   * Billing separates products/prices/entitlements from payment providers.
--   * ETB and country/channel-specific price books are first-class.
--   * Payment orders, attempts, transactions and webhooks are separate records.
--   * Payment providers are extensible registry rows with a global Active/Inactive toggle.
--   * Provider methods can be enabled/disabled per merchant account, country and currency.
--   * Orders, prices, attempts, transactions, mandates and merchant accounts use composite integrity bindings.
--   * Active price validity ranges cannot overlap for the same product/market/channel/currency/term.
--   * Store and local gateway purchases converge on the same entitlement model.
--   * Per-language input modes can select Latin QWERTY -> native-script transliteration.
--   * Transliteration rule packs are language-specific, versioned local resources.
-- ============================================================================

BEGIN;

-- gen_random_uuid() is built into PostgreSQL 13+; v1.4 installs btree_gist for active-price exclusion.

-- ---------------------------------------------------------------------------
-- 1. Identity and authentication
-- ---------------------------------------------------------------------------

CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_type        VARCHAR(20) NOT NULL DEFAULT 'REGISTERED'
                        CHECK (account_type IN ('REGISTERED', 'GUEST')),
    email               VARCHAR(320),
    email_normalized    VARCHAR(320),
    display_name        VARCHAR(120),
    account_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                        CHECK (account_status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DELETION_PENDING', 'DELETED')),
    email_verified_at   TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT ck_users_email_normalized
        CHECK (email_normalized IS NULL OR email_normalized = LOWER(BTRIM(email_normalized))),
    CONSTRAINT ck_users_email_pair
        CHECK ((email IS NULL AND email_normalized IS NULL) OR (email IS NOT NULL AND email_normalized IS NOT NULL))
);

CREATE UNIQUE INDEX ux_users_email_normalized
    ON users (email_normalized)
    WHERE email_normalized IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX ix_users_status_created
    ON users (account_status, created_at DESC);

CREATE TABLE user_password_credentials (
    user_id             UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    password_hash       TEXT NOT NULL,
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
                        CHECK (identity_provider IN ('EMAIL', 'GOOGLE', 'APPLE')),
    provider_subject    VARCHAR(255) NOT NULL,
    provider_email      VARCHAR(320),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at        TIMESTAMPTZ,
    CONSTRAINT ux_auth_provider_subject UNIQUE (identity_provider, provider_subject),
    CONSTRAINT ux_auth_user_provider UNIQUE (user_id, identity_provider)
);

CREATE INDEX ix_auth_identities_user
    ON user_auth_identities (user_id);

CREATE TABLE user_devices (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    installation_id     UUID NOT NULL,
    platform            VARCHAR(12) NOT NULL CHECK (platform IN ('ANDROID', 'IOS')),
    device_name         VARCHAR(160),
    app_version         VARCHAR(40),
    os_version          VARCHAR(80),
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
    )
);

CREATE INDEX ix_user_devices_user_status
    ON user_devices (user_id, device_status);

CREATE UNIQUE INDEX ux_user_devices_push_token
    ON user_devices (push_token)
    WHERE push_token IS NOT NULL AND device_status = 'ACTIVE';

CREATE TABLE user_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id           UUID REFERENCES user_devices(id) ON DELETE SET NULL,
    refresh_token_hash  TEXT NOT NULL UNIQUE,
    session_status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (session_status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    ip_address          INET,
    user_agent          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    CONSTRAINT ck_session_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_user_sessions_user_status
    ON user_sessions (user_id, session_status, expires_at DESC);

CREATE TABLE password_reset_tokens (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash          TEXT NOT NULL UNIQUE,
    expires_at          TIMESTAMPTZ NOT NULL,
    consumed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_password_reset_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_password_reset_user_expiry
    ON password_reset_tokens (user_id, expires_at DESC);

-- ---------------------------------------------------------------------------
-- 2. Supported languages and language resources
-- ---------------------------------------------------------------------------

CREATE TABLE supported_languages (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    language_code       VARCHAR(35) NOT NULL UNIQUE,
    english_name        VARCHAR(100) NOT NULL,
    native_name         VARCHAR(100),
    script_code         VARCHAR(20),
    writing_direction   VARCHAR(3) NOT NULL DEFAULT 'LTR'
                        CHECK (writing_direction IN ('LTR', 'RTL')),
    market_group        VARCHAR(40) NOT NULL DEFAULT 'ETHIOPIA',
    is_launch_language  BOOLEAN NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order          INTEGER NOT NULL DEFAULT 100,
    metadata            JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_supported_languages_active_order
    ON supported_languages (is_active, sort_order, english_name);

-- Initial catalogue. Validate names, script variants and launch status with
-- native speakers before production. `om` is used here as the language code
-- for Afaan Oromoo; use a more specific BCP 47 tag if product requirements
-- later need a regional/script distinction.
INSERT INTO supported_languages
    (language_code, english_name, native_name, script_code, market_group, is_launch_language, sort_order)
VALUES
    ('am', 'Amharic', 'አማርኛ', 'Ethi', 'ETHIOPIA', TRUE, 10),
    ('ti', 'Tigrinya', 'ትግርኛ', 'Ethi', 'ETHIOPIA', TRUE, 20),
    ('om', 'Afaan Oromoo', 'Afaan Oromoo', 'Latn', 'ETHIOPIA', TRUE, 30),
    ('en', 'English', 'English', 'Latn', 'GLOBAL', TRUE, 40),
    ('ar', 'Arabic', 'العربية', 'Arab', 'REGIONAL_EXPANSION', FALSE, 50),
    ('so', 'Somali', 'Soomaali', 'Latn', 'AFRICA_EXPANSION', FALSE, 60),
    ('aa', 'Afar', 'Qafár af', 'Latn', 'ETHIOPIA_EXPANSION', FALSE, 70),
    ('sid', 'Sidama', 'Sidaamu Afoo', 'Latn', 'ETHIOPIA_EXPANSION', FALSE, 80)
ON CONFLICT (language_code) DO NOTHING;

CREATE TABLE language_resources (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    language_id         UUID NOT NULL REFERENCES supported_languages(id) ON DELETE CASCADE,
    resource_type       VARCHAR(30) NOT NULL
                        CHECK (resource_type IN (
                            'KEYBOARD_LAYOUT', 'TRANSLITERATION_RULES', 'DICTIONARY',
                            'FREQUENCY_LIST', 'PREDICTION_MODEL', 'AUTOCORRECTION_MODEL',
                            'PROTECTED_TERMS', 'PRONUNCIATION', 'OTHER'
                        )),
    resource_version    VARCHAR(40) NOT NULL,
    platform            VARCHAR(12) NOT NULL DEFAULT 'SHARED'
                        CHECK (platform IN ('SHARED', 'ANDROID', 'IOS')),
    artifact_uri        TEXT NOT NULL,
    sha256_checksum     CHAR(64) NOT NULL,
    signature_key_id    VARCHAR(120),
    manifest_signature  TEXT,
    size_bytes          BIGINT CHECK (size_bytes IS NULL OR size_bytes >= 0),
    min_app_version     VARCHAR(40),
    manifest_json       JSONB NOT NULL DEFAULT '{}'::JSONB,
    release_notes       TEXT,
    publication_status  VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                        CHECK (publication_status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    published_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_language_resource_version UNIQUE (language_id, resource_type, resource_version, platform),
    CONSTRAINT ux_language_resource_id_language UNIQUE (id, language_id),
    CONSTRAINT ck_language_resource_sha256 CHECK (sha256_checksum ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT ck_language_resource_signature_pair CHECK (
        (signature_key_id IS NULL AND manifest_signature IS NULL)
        OR (signature_key_id IS NOT NULL AND manifest_signature IS NOT NULL)
    )
);

CREATE INDEX ix_language_resources_published
    ON language_resources (language_id, publication_status, resource_type, published_at DESC);

-- Language-specific input/transliteration profiles. The Latin-QWERTY -> Ge'ez
-- conversion is performed locally by the native keyboard engine using a
-- published TRANSLITERATION_RULES resource. Profiles are separate for Amharic
-- and Tigrinya even though both use Ethiopic script; mappings are not assumed
-- to be interchangeable. Profiles remain DRAFT until native-speaker review and
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
    ON transliteration_profiles (language_id, profile_status, profile_code)
    WHERE profile_status = 'ACTIVE';

-- Draft profile records define the intended product capability without
-- pretending that an unreviewed mapping table is production-ready. Publish a
-- signed TRANSLITERATION_RULES resource and activate the profile only after
-- the Amharic/Tigrinya language acceptance gates have passed.
INSERT INTO transliteration_profiles
    (language_id, profile_code, display_name, source_script_code, target_script_code,
     input_scheme, profile_version, profile_status, metadata)
SELECT l.id, seed.profile_code, seed.display_name, 'Latn', 'Ethi', 'PHONETIC', '1.0.0', 'DRAFT',
       jsonb_build_object(
           'keyLayout', 'QWERTY',
           'conversion', 'LATIN_PHONETIC_TO_GEEZ',
           'requiresNativeSpeakerApproval', TRUE,
           'requiresGoldenTestVectors', TRUE,
           'description', seed.description
       )
FROM (VALUES
    ('am', 'AM_PHONETIC_QWERTY_TO_ETHIOPIC', 'Amharic phonetic QWERTY to Ge''ez',
     'Latin QWERTY phonetic input converted locally to Amharic Ethiopic composing text.'),
    ('ti', 'TI_PHONETIC_QWERTY_TO_ETHIOPIC', 'Tigrinya phonetic QWERTY to Ge''ez',
     'Latin QWERTY phonetic input converted locally to Tigrinya Ethiopic composing text.')
) AS seed(language_code, profile_code, display_name, description)
JOIN supported_languages l ON l.language_code = seed.language_code
ON CONFLICT (language_id, profile_code, profile_version) DO NOTHING;

-- Only activate profiles backed by a published, signed transliteration rules
-- resource for the same target language.
CREATE OR REPLACE FUNCTION validate_transliteration_profile_activation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_resource_type       VARCHAR(30);
    v_publication_status  VARCHAR(20);
    v_signature_key_id    VARCHAR(120);
    v_manifest_signature  TEXT;
BEGIN
    IF NEW.rules_resource_id IS NOT NULL THEN
        SELECT resource_type, publication_status, signature_key_id, manifest_signature
          INTO v_resource_type, v_publication_status, v_signature_key_id, v_manifest_signature
          FROM language_resources
         WHERE id = NEW.rules_resource_id
           AND language_id = NEW.language_id;

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
        IF v_publication_status <> 'PUBLISHED' THEN
            RAISE EXCEPTION 'An active transliteration profile requires a published rules resource';
        END IF;
        IF v_signature_key_id IS NULL OR v_manifest_signature IS NULL THEN
            RAISE EXCEPTION 'An active transliteration profile requires a signed resource manifest';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_transliteration_profile_activation
    BEFORE INSERT OR UPDATE ON transliteration_profiles
    FOR EACH ROW EXECUTE FUNCTION validate_transliteration_profile_activation();

-- A user may only select a profile that is active and matches that typing
-- language. The composite FK enforces language matching; this trigger checks
-- publication status.
CREATE OR REPLACE FUNCTION validate_user_typing_transliteration_profile()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_profile_status VARCHAR(20);
BEGIN
    IF NEW.input_mode = 'PHONETIC_LATIN_TO_NATIVE' THEN
        SELECT profile_status
          INTO v_profile_status
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

CREATE TABLE device_language_resource_installations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id           UUID NOT NULL REFERENCES user_devices(id) ON DELETE CASCADE,
    resource_id         UUID NOT NULL REFERENCES language_resources(id) ON DELETE CASCADE,
    installed_version   VARCHAR(40) NOT NULL,
    installation_status VARCHAR(20) NOT NULL DEFAULT 'INSTALLED'
                        CHECK (installation_status IN ('PENDING', 'INSTALLED', 'FAILED', 'REMOVED')),
    checksum_verified   BOOLEAN NOT NULL DEFAULT FALSE,
    installed_at        TIMESTAMPTZ,
    last_error_code     VARCHAR(80),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_device_resource_install UNIQUE (device_id, resource_id)
);

CREATE INDEX ix_resource_installations_device_status
    ON device_language_resource_installations (device_id, installation_status);

-- Curated terms only. Large dictionaries and language models belong in
-- versioned resource files/CDN, not as millions of rows in this table.
CREATE TABLE language_terms (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    language_id         UUID NOT NULL REFERENCES supported_languages(id) ON DELETE CASCADE,
    surface_form        TEXT NOT NULL,
    normalized_form     TEXT NOT NULL,
    term_category       VARCHAR(30) NOT NULL DEFAULT 'GENERAL'
                        CHECK (term_category IN ('GENERAL', 'PERSON_NAME', 'PLACE_NAME', 'CULTURAL', 'TECHNICAL', 'BRAND', 'PROTECTED')),
    is_protected        BOOLEAN NOT NULL DEFAULT FALSE,
    source              VARCHAR(30) NOT NULL DEFAULT 'ADMIN'
                        CHECK (source IN ('ADMIN', 'CURATED_CORPUS', 'USER_SUGGESTION')),
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'RETIRED')),
    metadata            JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_language_term UNIQUE (language_id, normalized_form, term_category)
);

CREATE INDEX ix_language_terms_lookup
    ON language_terms (language_id, normalized_form)
    WHERE status = 'ACTIVE';

CREATE TABLE user_dictionary_entries (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    language_id         UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    word                TEXT NOT NULL,
    normalized_word     TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_user_dictionary_word UNIQUE (user_id, language_id, normalized_word)
);

CREATE INDEX ix_user_dictionary_user_language
    ON user_dictionary_entries (user_id, language_id);

-- ---------------------------------------------------------------------------
-- 3. User language preferences and settings
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
    voice_input_language_mode   VARCHAR(20) NOT NULL DEFAULT 'MANUAL'
                                CHECK (voice_input_language_mode IN ('AUTO', 'MANUAL')),
    preferred_voice_language_id UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    ai_history_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
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
    -- DIRECT_SCRIPT_LAYOUT: use the target language's native keyboard layout.
    -- PHONETIC_LATIN_TO_NATIVE: keep Latin QWERTY keycaps but render the
    -- selected language's native script (e.g. `selam` -> `ሰላም`).
    -- LATIN_PASSTHROUGH: preserve Latin input unchanged (English/Qubee).
    input_mode                  VARCHAR(32) NOT NULL DEFAULT 'DIRECT_SCRIPT_LAYOUT'
                                CHECK (input_mode IN ('DIRECT_SCRIPT_LAYOUT', 'PHONETIC_LATIN_TO_NATIVE', 'LATIN_PASSTHROUGH')),
    transliteration_profile_id UUID,
    conversion_trigger          VARCHAR(24) NOT NULL DEFAULT 'LIVE_COMPOSING'
                                CHECK (conversion_trigger IN ('LIVE_COMPOSING', 'WORD_BOUNDARY', 'MANUAL')),
    show_transliteration_candidates BOOLEAN NOT NULL DEFAULT TRUE,
    unknown_token_behavior     VARCHAR(24) NOT NULL DEFAULT 'PRESERVE_LATIN'
                                CHECK (unknown_token_behavior IN ('PRESERVE_LATIN', 'SUGGEST_ONLY')),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_user_typing_language UNIQUE (user_id, language_id),
    CONSTRAINT fk_user_typing_transliteration_profile
        FOREIGN KEY (transliteration_profile_id, language_id)
        REFERENCES transliteration_profiles (id, language_id) ON DELETE RESTRICT,
    CONSTRAINT ck_user_typing_transliteration_mode CHECK (
        (input_mode = 'PHONETIC_LATIN_TO_NATIVE' AND transliteration_profile_id IS NOT NULL)
        OR (input_mode <> 'PHONETIC_LATIN_TO_NATIVE' AND transliteration_profile_id IS NULL)
    )
);

CREATE TRIGGER trg_validate_user_typing_transliteration_profile
    BEFORE INSERT OR UPDATE ON user_typing_languages
    FOR EACH ROW EXECUTE FUNCTION validate_user_typing_transliteration_profile();

CREATE UNIQUE INDEX ux_user_one_primary_typing_language
    ON user_typing_languages (user_id)
    WHERE is_primary = TRUE AND is_enabled = TRUE;

CREATE INDEX ix_user_typing_languages_order
    ON user_typing_languages (user_id, is_enabled, sort_order);

-- Optional preference configured by the user. Do not upload the phone's
-- address book. contact_reference_hash should be generated client-side from
-- a user-approved opaque reference and must not be a raw phone number/name.
CREATE TABLE user_contact_language_preferences (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    contact_reference_hash  VARCHAR(128) NOT NULL,
    user_defined_label      VARCHAR(120),
    destination_language_id UUID NOT NULL REFERENCES supported_languages(id) ON DELETE RESTRICT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_user_contact_language_pref UNIQUE (user_id, contact_reference_hash)
);

CREATE INDEX ix_contact_language_preferences_user
    ON user_contact_language_preferences (user_id);

-- ---------------------------------------------------------------------------
-- 4. Consent and optional saved AI history
-- ---------------------------------------------------------------------------

CREATE TABLE user_consents (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    consent_code        VARCHAR(40) NOT NULL
                        CHECK (consent_code IN (
                            'TERMS_ACCEPTANCE', 'PRIVACY_NOTICE', 'AI_PROCESSING', 'PAYMENT_MANDATE',
                            'SAVE_AI_HISTORY', 'SAVE_AUDIO_HISTORY', 'ANALYTICS', 'MARKETING'
                        )),
    consent_version     VARCHAR(40) NOT NULL,
    is_granted          BOOLEAN NOT NULL,
    granted_at          TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    source_platform     VARCHAR(12) CHECK (source_platform IN ('ANDROID', 'IOS', 'WEB', 'OTHER')),
    metadata            JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT ck_consent_timestamps CHECK (
        (is_granted = FALSE AND granted_at IS NULL)
        OR (is_granted = TRUE AND granted_at IS NOT NULL)
    ),
    CONSTRAINT ux_user_consents_id_user UNIQUE (id, user_id)
);

CREATE INDEX ix_user_consents_latest
    ON user_consents (user_id, consent_code, recorded_at DESC);

-- This table is only for explicit, user-enabled history. Ciphertext is
-- application-encrypted; do not put plaintext drafts or results here.
CREATE TABLE user_saved_ai_results (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    task_code               VARCHAR(40) NOT NULL,
    source_language_id      UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    destination_language_id UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    input_ciphertext        BYTEA NOT NULL,
    output_ciphertext       BYTEA NOT NULL,
    encryption_key_version  VARCHAR(40) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at              TIMESTAMPTZ,
    CONSTRAINT ck_saved_ai_result_expiry CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE INDEX ix_user_saved_ai_results_user_created
    ON user_saved_ai_results (user_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 5. AI providers, models, prompts and operational requests
-- ---------------------------------------------------------------------------

CREATE TABLE ai_providers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code       VARCHAR(50) NOT NULL UNIQUE,
    display_name        VARCHAR(120) NOT NULL,
    provider_type       VARCHAR(30) NOT NULL
                        CHECK (provider_type IN ('TRANSLATION', 'LLM', 'SPEECH_TO_TEXT', 'TEXT_TO_SPEECH', 'MULTI')),
    provider_status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (provider_status IN ('ACTIVE', 'DEGRADED', 'DISABLED')),
    configuration       JSONB NOT NULL DEFAULT '{}'::JSONB,
    secret_reference    VARCHAR(255), -- Secret-manager key/reference, never a plaintext secret.
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ai_models (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id         UUID NOT NULL REFERENCES ai_providers(id) ON DELETE RESTRICT,
    model_code          VARCHAR(120) NOT NULL,
    display_name        VARCHAR(160) NOT NULL,
    supported_tasks     TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    supported_languages TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    model_status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (model_status IN ('ACTIVE', 'DEGRADED', 'DISABLED')),
    configuration       JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_ai_model_provider_code UNIQUE (provider_id, model_code)
);

CREATE INDEX ix_ai_models_provider_status
    ON ai_models (provider_id, model_status);

-- Explicit capability matrix. This prevents the application from advertising
-- a language pair/task merely because a provider accepts a generic language code.
CREATE TABLE ai_language_capabilities (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_id                UUID NOT NULL REFERENCES ai_models(id) ON DELETE CASCADE,
    task_code               VARCHAR(40) NOT NULL
                            CHECK (task_code IN (
                                'TRANSLATE', 'GRAMMAR_CORRECTION', 'SPELLING_CORRECTION',
                                'REWRITE_NATURAL', 'REWRITE_PROFESSIONAL', 'REWRITE_FRIENDLY',
                                'REWRITE_POLITE', 'SHORTEN_TEXT', 'EXPAND_TEXT', 'SMART_REPLY',
                                'SUMMARIZE_TEXT', 'VOICE_TRANSCRIPTION', 'VOICE_TRANSLATE', 'TEXT_TO_SPEECH'
                            )),
    source_language_id      UUID REFERENCES supported_languages(id) ON DELETE CASCADE,
    destination_language_id UUID REFERENCES supported_languages(id) ON DELETE CASCADE,
    is_enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    supports_auto_detection BOOLEAN NOT NULL DEFAULT FALSE,
    quality_tier            VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
                            CHECK (quality_tier IN ('UNVERIFIED', 'EXPERIMENTAL', 'BETA', 'PRODUCTION')) ,
    max_input_characters    INTEGER CHECK (max_input_characters IS NULL OR max_input_characters > 0),
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_ai_language_capability UNIQUE NULLS NOT DISTINCT
        (model_id, task_code, source_language_id, destination_language_id)
);

CREATE INDEX ix_ai_language_capabilities_lookup
    ON ai_language_capabilities (task_code, source_language_id, destination_language_id, is_enabled);

CREATE TABLE ai_prompt_templates (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_code           VARCHAR(40) NOT NULL,
    template_version    INTEGER NOT NULL CHECK (template_version > 0),
    language_scope      VARCHAR(35) NOT NULL DEFAULT '*',
    template_body       TEXT NOT NULL,
    output_schema       JSONB NOT NULL DEFAULT '{}'::JSONB,
    is_active           BOOLEAN NOT NULL DEFAULT FALSE,
    created_by          UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_ai_prompt_version UNIQUE (task_code, template_version, language_scope)
);

CREATE UNIQUE INDEX ux_ai_prompt_one_active_scope
    ON ai_prompt_templates (task_code, language_scope)
    WHERE is_active = TRUE;

-- This is an operational ledger only. Do not add plaintext input_text or
-- output_text columns. `content_retained` must remain false unless a separately
-- consented and encrypted saved result is written to user_saved_ai_results.
CREATE TABLE ai_requests (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID REFERENCES users(id) ON DELETE SET NULL,
    device_id               UUID REFERENCES user_devices(id) ON DELETE SET NULL,
    task_code               VARCHAR(40) NOT NULL
                            CHECK (task_code IN (
                                'TRANSLATE', 'GRAMMAR_CORRECTION', 'SPELLING_CORRECTION',
                                'REWRITE_NATURAL', 'REWRITE_PROFESSIONAL', 'REWRITE_FRIENDLY',
                                'REWRITE_POLITE', 'SHORTEN_TEXT', 'EXPAND_TEXT', 'SMART_REPLY',
                                'SUMMARIZE_TEXT', 'VOICE_TRANSCRIPTION', 'VOICE_TRANSLATE', 'TEXT_TO_SPEECH'
                            )),
    source_language_id      UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    destination_language_id UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    provider_id             UUID REFERENCES ai_providers(id) ON DELETE SET NULL,
    model_id                UUID REFERENCES ai_models(id) ON DELETE SET NULL,
    prompt_template_id      UUID REFERENCES ai_prompt_templates(id) ON DELETE SET NULL,
    request_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                            CHECK (request_status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    input_character_count   INTEGER NOT NULL DEFAULT 0 CHECK (input_character_count >= 0),
    output_character_count  INTEGER NOT NULL DEFAULT 0 CHECK (output_character_count >= 0),
    input_token_count       INTEGER CHECK (input_token_count IS NULL OR input_token_count >= 0),
    output_token_count      INTEGER CHECK (output_token_count IS NULL OR output_token_count >= 0),
    duration_ms             INTEGER CHECK (duration_ms IS NULL OR duration_ms >= 0),
    estimated_cost_micros   BIGINT CHECK (estimated_cost_micros IS NULL OR estimated_cost_micros >= 0),
    cost_currency           CHAR(3) NOT NULL DEFAULT 'USD',
    idempotency_key         VARCHAR(128),
    content_retained        BOOLEAN NOT NULL DEFAULT FALSE,
    error_code              VARCHAR(100),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    CONSTRAINT ck_ai_request_completed_time CHECK (completed_at IS NULL OR completed_at >= created_at)
);

CREATE UNIQUE INDEX ux_ai_request_user_idempotency
    ON ai_requests (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL AND user_id IS NOT NULL;

CREATE INDEX ix_ai_requests_user_created
    ON ai_requests (user_id, created_at DESC);

CREATE INDEX ix_ai_requests_task_status_created
    ON ai_requests (task_code, request_status, created_at DESC);

CREATE INDEX ix_ai_requests_provider_created
    ON ai_requests (provider_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 6. Speech jobs and optional audio object lifecycle
-- ---------------------------------------------------------------------------

CREATE TABLE voice_jobs (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID REFERENCES users(id) ON DELETE SET NULL,
    device_id               UUID REFERENCES user_devices(id) ON DELETE SET NULL,
    ai_request_id           UUID REFERENCES ai_requests(id) ON DELETE SET NULL,
    job_type                VARCHAR(25) NOT NULL
                            CHECK (job_type IN ('TRANSCRIPTION', 'TRANSCRIBE_TRANSLATE', 'SYNTHESIS')),
    source_language_id      UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    destination_language_id UUID REFERENCES supported_languages(id) ON DELETE SET NULL,
    job_status              VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                            CHECK (job_status IN ('PENDING', 'UPLOADING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    audio_object_key        TEXT, -- Null by default; private temporary object only when required.
    audio_retention_until   TIMESTAMPTZ,
    audio_deleted_at        TIMESTAMPTZ,
    audio_duration_ms       INTEGER CHECK (audio_duration_ms IS NULL OR audio_duration_ms >= 0),
    audio_size_bytes        BIGINT CHECK (audio_size_bytes IS NULL OR audio_size_bytes >= 0),
    transcript_character_count INTEGER CHECK (transcript_character_count IS NULL OR transcript_character_count >= 0),
    provider_job_reference  VARCHAR(255),
    error_code              VARCHAR(100),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    CONSTRAINT ck_voice_audio_retention CHECK (audio_retention_until IS NULL OR audio_retention_until > created_at)
);

CREATE INDEX ix_voice_jobs_user_created
    ON voice_jobs (user_id, created_at DESC);

CREATE INDEX ix_voice_jobs_status_created
    ON voice_jobs (job_status, created_at);

CREATE INDEX ix_voice_jobs_audio_retention
    ON voice_jobs (audio_retention_until)
    WHERE audio_object_key IS NOT NULL AND audio_deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 7. Plans, entitlements and provider-neutral billing
--
-- Important design rule:
--   A product/entitlement is independent from the payment rail used to buy it.
--   A user can receive the same plan through Chapa, ArifPay, a direct wallet
--   provider, a permitted app-store purchase, a web checkout, or an admin grant.
--
-- Prices are stored in integer minor units, never floating point. ETB is a
-- first-class currency. A one-time ETB purchase can grant a fixed subscription
-- term with MANUAL_RENEW; automatic debit is enabled only after a payment
-- provider/method has an approved mandate capability and the user has consented.
-- ---------------------------------------------------------------------------

CREATE TABLE subscription_plans (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code           VARCHAR(30) NOT NULL UNIQUE,
    display_name        VARCHAR(100) NOT NULL,
    description         TEXT,
    plan_status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (plan_status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,
    display_order       INTEGER NOT NULL DEFAULT 100,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_subscription_one_default_plan
    ON subscription_plans (is_default)
    WHERE is_default = TRUE;

INSERT INTO subscription_plans (plan_code, display_name, description, is_default, display_order)
VALUES
    ('FREE', 'Free', 'Core keyboard and basic included AI usage.', TRUE, 10),
    ('PREMIUM', 'Premium', 'Expanded translation, writing and voice allowances.', FALSE, 20),
    ('PREMIUM_PLUS', 'Premium Plus', 'Higher AI and speech allowances.', FALSE, 30)
ON CONFLICT (plan_code) DO NOTHING;

CREATE TABLE plan_entitlements (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id             UUID NOT NULL REFERENCES subscription_plans(id) ON DELETE CASCADE,
    feature_code        VARCHAR(60) NOT NULL,
    access_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    included_units      BIGINT CHECK (included_units IS NULL OR included_units >= 0),
    reset_period        VARCHAR(20) NOT NULL DEFAULT 'NONE'
                        CHECK (reset_period IN ('NONE', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    unit_name           VARCHAR(30) NOT NULL DEFAULT 'REQUEST',
    configuration      JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_plan_entitlement_feature UNIQUE (plan_id, feature_code)
);

-- Currency exponent makes minor-unit handling explicit (ETB 2, UGX 0, etc.).
CREATE TABLE billing_currencies (
    currency_code       CHAR(3) PRIMARY KEY,
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
    ('KES', 'Kenyan Shilling', 2),
    ('UGX', 'Ugandan Shilling', 0),
    ('TZS', 'Tanzanian Shilling', 2),
    ('GHS', 'Ghanaian Cedi', 2),
    ('NGN', 'Nigerian Naira', 2),
    ('ZAR', 'South African Rand', 2)
ON CONFLICT (currency_code) DO NOTHING;

-- A billing product is the commercial item. It is not a gateway/store SKU.
CREATE TABLE billing_products (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_code            VARCHAR(60) NOT NULL UNIQUE,
    product_type            VARCHAR(24) NOT NULL
                            CHECK (product_type IN ('SUBSCRIPTION', 'AI_CREDIT_PACK', 'ADD_ON')),
    plan_id                 UUID REFERENCES subscription_plans(id) ON DELETE RESTRICT,
    credit_units            BIGINT CHECK (credit_units IS NULL OR credit_units > 0),
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
SELECT 'PLAN_' || plan_code, 'SUBSCRIPTION', id, display_name, description,
       CASE WHEN plan_code = 'FREE' THEN 'ACTIVE' ELSE 'DRAFT' END
FROM subscription_plans
ON CONFLICT (product_code) DO NOTHING;

-- Market- and channel-specific price book. Use ISO 3166-1 alpha-2 country
-- codes; ZZ means a generic fallback price. Each price change gets a new version.
CREATE TABLE billing_prices (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_product_id      UUID NOT NULL REFERENCES billing_products(id) ON DELETE RESTRICT,
    market_country_code     CHAR(2) NOT NULL DEFAULT 'ZZ'
                            CHECK (market_country_code = 'ZZ' OR market_country_code ~ '^[A-Z]{2}$'),
    sales_channel           VARCHAR(24) NOT NULL
                            CHECK (sales_channel IN ('WEB', 'ANDROID_GOOGLE_PLAY', 'ANDROID_DIRECT', 'IOS_APP_STORE', 'IOS_DIRECT', 'ADMIN', 'PARTNER')),
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code),
    amount_minor            BIGINT NOT NULL CHECK (amount_minor >= 0),
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
    CONSTRAINT ux_billing_price_version UNIQUE (
        billing_product_id, market_country_code, sales_channel,
        currency_code, billing_period, renewal_model, price_version
    )
);

CREATE INDEX ix_billing_prices_market_active
    ON billing_prices (market_country_code, sales_channel, currency_code, is_active, valid_from DESC);

-- Payment providers are adapters. Do not store API secrets here; secret_reference
-- points to the deployment secret manager. Providers stay disabled until configured
-- and passed sandbox/live merchant onboarding checks.
CREATE TABLE payment_providers (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_code           VARCHAR(40) NOT NULL UNIQUE,
    display_name            VARCHAR(120) NOT NULL,
    provider_type           VARCHAR(24) NOT NULL,
    provider_status         VARCHAR(28) NOT NULL DEFAULT 'CONFIGURATION_REQUIRED'
                            CHECK (provider_status IN ('CONFIGURATION_REQUIRED', 'UNDER_REVIEW', 'TESTING', 'ACTIVE', 'DEGRADED', 'DISABLED')),
    -- Global operator-controlled checkout switch. All seed rows start inactive.
    -- This is separate from provider_status (onboarding/health lifecycle).
    is_active               BOOLEAN NOT NULL DEFAULT FALSE,
    supports_webhooks       BOOLEAN NOT NULL DEFAULT FALSE,
    supports_refunds        BOOLEAN NOT NULL DEFAULT FALSE,
    supports_payment_verification BOOLEAN NOT NULL DEFAULT TRUE,
    secret_reference        VARCHAR(255),
    documentation_url       TEXT,
    configuration           JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_payment_provider_type CHECK (
        provider_type IN ('PAYMENT_GATEWAY', 'WALLET_DIRECT', 'MOBILE_MONEY_NETWORK', 'APP_STORE', 'MANUAL')
    )
);

CREATE INDEX ix_payment_providers_active_status
    ON payment_providers (is_active, provider_status, provider_type);

COMMENT ON COLUMN payment_providers.is_active IS
    'Global Active/Inactive operator switch. Checkout also requires provider_status=ACTIVE, an enabled/available method, market eligibility and a registered adapter.';
COMMENT ON COLUMN payment_providers.provider_status IS
    'Onboarding and operational lifecycle state; independent from the global is_active switch.';
COMMENT ON COLUMN payment_providers.provider_code IS
    'Stable adapter registry key. Add providers as rows; do not add provider brands as schema enums.';

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

-- A method is scoped to a provider, country and currency. The enabled list should
-- be refreshed from provider capabilities where possible (e.g. Chapa GET
-- /v2/payments/methods). Never show an UNVERIFIED method in checkout.
CREATE TABLE provider_payment_methods (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id             UUID NOT NULL REFERENCES payment_providers(id) ON DELETE CASCADE,
    method_code             VARCHAR(40) NOT NULL,
    provider_method_code    VARCHAR(100),
    display_name            VARCHAR(120) NOT NULL,
    country_code            CHAR(2) NOT NULL DEFAULT 'ZZ'
                            CHECK (country_code = 'ZZ' OR country_code ~ '^[A-Z]{2}$'),
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code),
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
    CONSTRAINT ck_provider_payment_method_amount_range CHECK (
        min_amount_minor IS NULL OR max_amount_minor IS NULL OR max_amount_minor >= min_amount_minor
    ),
    CONSTRAINT ck_method_auto_renew_capability CHECK (
        supports_auto_renew = FALSE OR supports_direct_charge = TRUE OR method_type = 'APP_STORE'
    )
);

CREATE INDEX ix_provider_payment_methods_available
    ON provider_payment_methods (country_code, currency_code, availability_status, is_enabled);

-- Maps internal prices/products to external store SKUs or provider product IDs.
CREATE TABLE external_product_mappings (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_price_id        UUID NOT NULL REFERENCES billing_prices(id) ON DELETE RESTRICT,
    provider_id             UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    external_product_id     VARCHAR(255) NOT NULL,
    external_offer_id       VARCHAR(255),
    environment             VARCHAR(16) NOT NULL DEFAULT 'PRODUCTION'
                            CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_external_product_mapping UNIQUE (provider_id, external_product_id, external_offer_id, environment)
);

-- Storefront/payment-policy decisions are explicit, country-scoped and fail closed.
-- A missing or REVIEW_REQUIRED policy means no external in-app checkout UI is shown.
-- Legal/platform policy must be checked before marking a rule APPROVED.
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
        approval_status <> 'APPROVED'
        OR (reviewed_at IS NOT NULL AND reviewed_by_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_billing_policy_external_flow CHECK (
        external_payment_allowed = FALSE
        OR (approval_status = 'APPROVED' AND purchase_flow IN ('LOCAL_GATEWAY_IN_APP', 'EXTERNAL_WEB_CHECKOUT'))
    )
);

CREATE INDEX ix_billing_market_policy_lookup
    ON billing_market_channel_policies (country_code, client_platform, distribution_channel, approval_status, effective_from DESC);

-- A payment order is the amount/product the customer intends to buy. The server
-- resolves the price and snapshots amount/currency; never trust a client amount.
CREATE TABLE payment_orders (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_reference         VARCHAR(40) NOT NULL UNIQUE,
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    billing_product_id      UUID NOT NULL REFERENCES billing_products(id) ON DELETE RESTRICT,
    billing_price_id        UUID NOT NULL REFERENCES billing_prices(id) ON DELETE RESTRICT,
    order_type              VARCHAR(28) NOT NULL
                            CHECK (order_type IN ('SUBSCRIPTION_PURCHASE', 'SUBSCRIPTION_RENEWAL', 'AI_CREDIT_TOPUP', 'ADD_ON_PURCHASE')),
    sales_channel           VARCHAR(24) NOT NULL
                            CHECK (sales_channel IN ('WEB', 'ANDROID_GOOGLE_PLAY', 'ANDROID_DIRECT', 'IOS_APP_STORE', 'IOS_DIRECT', 'ADMIN', 'PARTNER')),
    market_country_code     CHAR(2) NOT NULL CHECK (market_country_code ~ '^[A-Z]{2}$'),
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code),
    amount_minor            BIGINT NOT NULL CHECK (amount_minor >= 0),
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
    CONSTRAINT ux_payment_order_owner_binding UNIQUE (id, user_id)
);

CREATE INDEX ix_payment_orders_user_created
    ON payment_orders (user_id, created_at DESC);

CREATE INDEX ix_payment_orders_status_created
    ON payment_orders (order_status, created_at);

-- An order may have multiple attempts (e.g. user changes from Telebirr to CBE).
CREATE TABLE payment_attempts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_order_id            UUID NOT NULL,
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    attempt_number              SMALLINT NOT NULL CHECK (attempt_number > 0),
    provider_id                 UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    provider_payment_method_id  UUID REFERENCES provider_payment_methods(id) ON DELETE RESTRICT,
    attempt_status              VARCHAR(24) NOT NULL DEFAULT 'INITIATED'
                                CHECK (attempt_status IN ('INITIATED', 'PENDING', 'REQUIRES_ACTION', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'UNKNOWN')),
    provider_payment_reference  VARCHAR(255),
    provider_customer_reference VARCHAR(255),
    provider_subscription_reference VARCHAR(255),
    provider_mandate_reference  VARCHAR(255),
    checkout_url                TEXT,
    store_purchase_token_hash   VARCHAR(255),
    store_purchase_token_ciphertext BYTEA,
    store_original_transaction_reference VARCHAR(255),
    store_environment           VARCHAR(16) CHECK (store_environment IS NULL OR store_environment IN ('SANDBOX', 'PRODUCTION')),
    idempotency_key             VARCHAR(128) NOT NULL,
    failure_code                VARCHAR(100),
    failure_message_safe        VARCHAR(500),
    expires_at                  TIMESTAMPTZ,
    verified_at                 TIMESTAMPTZ,
    metadata                    JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_payment_attempt_number UNIQUE (payment_order_id, attempt_number),
    CONSTRAINT ux_payment_attempt_idempotency UNIQUE (provider_id, idempotency_key),
    CONSTRAINT fk_payment_attempt_order_owner FOREIGN KEY (payment_order_id, user_id)
        REFERENCES payment_orders (id, user_id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ux_payment_attempt_provider_reference
    ON payment_attempts (provider_id, provider_payment_reference)
    WHERE provider_payment_reference IS NOT NULL;

CREATE INDEX ix_payment_attempts_order_status
    ON payment_attempts (payment_order_id, attempt_status, created_at DESC);

-- A recurring mandate is a distinct, user-authorised permission from a one-off
-- payment. Create it only when the exact provider method supports approved
-- recurring charges. The application must additionally verify that consent_record_id
-- points to a granted, unrevoked PAYMENT_MANDATE consent for this same user.
-- Store the provider reference, never wallet PIN/card secrets.
CREATE TABLE payment_mandates (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    provider_id             UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    provider_payment_method_id UUID NOT NULL REFERENCES provider_payment_methods(id) ON DELETE RESTRICT,
    consent_record_id       UUID NOT NULL REFERENCES user_consents(id) ON DELETE RESTRICT,
    provider_mandate_reference VARCHAR(255) NOT NULL,
    mandate_status          VARCHAR(24) NOT NULL DEFAULT 'PENDING'
                            CHECK (mandate_status IN ('PENDING', 'SIGNING_REQUIRED', 'ACTIVE', 'PAUSED', 'CANCELLED', 'EXPIRED', 'FAILED', 'REVOKED')),
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code),
    max_amount_minor        BIGINT CHECK (max_amount_minor IS NULL OR max_amount_minor > 0),
    interval_unit           VARCHAR(16) NOT NULL
                            CHECK (interval_unit IN ('DAY', 'WEEK', 'MONTH', 'YEAR')),
    interval_count          SMALLINT NOT NULL DEFAULT 1 CHECK (interval_count > 0),
    consent_text_version    VARCHAR(40) NOT NULL,
    consented_at            TIMESTAMPTZ NOT NULL,
    provider_approved_at    TIMESTAMPTZ,
    next_charge_at          TIMESTAMPTZ,
    last_charge_at          TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    expires_at              TIMESTAMPTZ,
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_payment_mandate_provider_reference UNIQUE (provider_id, provider_mandate_reference),
    CONSTRAINT ux_payment_mandate_binding UNIQUE (id, user_id, provider_id, provider_payment_method_id),
    CONSTRAINT fk_payment_mandate_method_currency FOREIGN KEY (provider_payment_method_id, provider_id, currency_code)
        REFERENCES provider_payment_methods (id, provider_id, currency_code) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_mandate_consent_owner FOREIGN KEY (consent_record_id, user_id)
        REFERENCES user_consents (id, user_id) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_mandate_expiry CHECK (expires_at IS NULL OR expires_at > consented_at),
    CONSTRAINT ck_payment_mandate_approval CHECK (
        mandate_status <> 'ACTIVE' OR provider_approved_at IS NOT NULL
    )
);

CREATE INDEX ix_payment_mandates_user_status
    ON payment_mandates (user_id, mandate_status, expires_at);

CREATE INDEX ix_payment_mandates_due
    ON payment_mandates (next_charge_at)
    WHERE mandate_status = 'ACTIVE';

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

ALTER TABLE payment_attempts
    ADD COLUMN payment_mandate_id UUID,
    ADD CONSTRAINT fk_payment_attempt_mandate_binding
        FOREIGN KEY (payment_mandate_id, user_id, provider_id, provider_payment_method_id)
        REFERENCES payment_mandates (id, user_id, provider_id, provider_payment_method_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_payment_attempt_mandate_has_method
        CHECK (payment_mandate_id IS NULL OR provider_payment_method_id IS NOT NULL);

CREATE INDEX ix_payment_attempts_mandate
    ON payment_attempts (payment_mandate_id)
    WHERE payment_mandate_id IS NOT NULL;

-- Immutable-style transaction ledger for captures, refunds, reversals and disputes.
-- Amounts are positive; transaction_kind determines their accounting meaning.
CREATE TABLE payment_transactions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_attempt_id      UUID NOT NULL REFERENCES payment_attempts(id) ON DELETE RESTRICT,
    provider_id             UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    parent_transaction_id   UUID REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    transaction_kind        VARCHAR(20) NOT NULL
                            CHECK (transaction_kind IN ('CAPTURE', 'REFUND', 'REVERSAL', 'CHARGEBACK', 'FEE')),
    transaction_status     VARCHAR(20) NOT NULL
                            CHECK (transaction_status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'REVERSED')),
    provider_transaction_reference VARCHAR(255),
    amount_minor            BIGINT NOT NULL CHECK (amount_minor > 0),
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code),
    idempotency_key         VARCHAR(128) NOT NULL,
    occurred_at             TIMESTAMPTZ,
    verified_at             TIMESTAMPTZ,
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_payment_transaction_idempotency UNIQUE (provider_id, idempotency_key)
);

CREATE UNIQUE INDEX ux_payment_transaction_provider_reference
    ON payment_transactions (provider_id, provider_transaction_reference, transaction_kind)
    WHERE provider_transaction_reference IS NOT NULL;

CREATE INDEX ix_payment_transactions_attempt
    ON payment_transactions (payment_attempt_id, created_at DESC);

CREATE TABLE payment_refunds (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    capture_transaction_id  UUID NOT NULL REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    refund_transaction_id   UUID REFERENCES payment_transactions(id) ON DELETE RESTRICT,
    refund_reference         VARCHAR(100) NOT NULL UNIQUE,
    amount_minor            BIGINT NOT NULL CHECK (amount_minor > 0),
    currency_code           CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code),
    refund_status           VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
                            CHECK (refund_status IN ('REQUESTED', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    reason_code             VARCHAR(60),
    reason_text             VARCHAR(500),
    requested_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    metadata                JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE payment_webhook_events (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id             UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    external_event_id       VARCHAR(255) NOT NULL,
    event_type              VARCHAR(120) NOT NULL,
    signature_status        VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
                            CHECK (signature_status IN ('VALID', 'INVALID', 'NOT_APPLICABLE', 'UNKNOWN')),
    event_status            VARCHAR(20) NOT NULL DEFAULT 'RECEIVED'
                            CHECK (event_status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'FAILED', 'IGNORED')),
    payload_sha256          CHAR(64) NOT NULL,
    redacted_payload        JSONB NOT NULL DEFAULT '{}'::JSONB,
    processing_error        VARCHAR(500),
    received_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at            TIMESTAMPTZ,
    CONSTRAINT ux_payment_webhook_provider_event UNIQUE (provider_id, external_event_id)
);

CREATE INDEX ix_payment_webhook_status_received
    ON payment_webhook_events (event_status, received_at);

-- Manual bank-transfer review supports customers who cannot use wallet checkout.
-- Evidence files must be private, access-controlled and treated as claims only;
-- a screenshot by itself is not proof of settlement. Reconcile with bank records.
CREATE TABLE manual_payment_reviews (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_attempt_id      UUID NOT NULL UNIQUE REFERENCES payment_attempts(id) ON DELETE RESTRICT,
    payer_name              VARCHAR(160),
    bank_reference           VARCHAR(160),
    transfer_date            DATE,
    evidence_object_key      TEXT,
    review_status            VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED'
                            CHECK (review_status IN ('SUBMITTED', 'UNDER_REVIEW', 'MATCHED', 'REJECTED')),
    reviewed_by_user_id      UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewer_note            VARCHAR(500),
    submitted_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- A subscription is an entitlement period, not a particular gateway subscription.
-- MANUAL_RENEW is the safe default for local one-off payments. AUTO_RENEW is only
-- allowed when provider_payment_methods.supports_auto_renew is verified and a
-- provider mandate/store-managed subscription is recorded.
CREATE TABLE user_subscriptions (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    plan_id                     UUID NOT NULL REFERENCES subscription_plans(id) ON DELETE RESTRICT,
    billing_product_id          UUID REFERENCES billing_products(id) ON DELETE SET NULL,
    billing_price_id            UUID REFERENCES billing_prices(id) ON DELETE SET NULL,
    source_type                 VARCHAR(20) NOT NULL DEFAULT 'PAYMENT'
                                CHECK (source_type IN ('PAYMENT', 'STORE', 'ADMIN_GRANT', 'PROMOTION', 'PARTNER')),
    billing_mode                VARCHAR(20) NOT NULL
                                CHECK (billing_mode IN ('MANUAL_RENEW', 'AUTO_RENEW', 'STORE_MANAGED', 'GRANT', 'LIFETIME')),
    provider_id                 UUID REFERENCES payment_providers(id) ON DELETE RESTRICT,
    provider_payment_method_id  UUID REFERENCES provider_payment_methods(id) ON DELETE RESTRICT,
    provider_subscription_reference VARCHAR(255),
    payment_mandate_id           UUID REFERENCES payment_mandates(id) ON DELETE RESTRICT,
    subscription_status         VARCHAR(20) NOT NULL
                                CHECK (subscription_status IN ('PENDING', 'ACTIVE', 'GRACE_PERIOD', 'PAST_DUE', 'PAUSED', 'CANCELLED', 'EXPIRED', 'REVOKED')),
    current_period_start        TIMESTAMPTZ,
    current_period_end          TIMESTAMPTZ,
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
    CONSTRAINT ck_subscription_period CHECK (
        current_period_start IS NULL OR current_period_end IS NULL OR current_period_end > current_period_start
    ),
    CONSTRAINT fk_subscription_mandate_owner_provider_method
        FOREIGN KEY (payment_mandate_id, user_id, provider_id, provider_payment_method_id)
        REFERENCES payment_mandates (id, user_id, provider_id, provider_payment_method_id) ON DELETE RESTRICT,
    CONSTRAINT ck_subscription_auto_renew_mode CHECK (
        (payment_mandate_id IS NULL OR (provider_id IS NOT NULL AND provider_payment_method_id IS NOT NULL))
        AND (auto_renew_enabled = FALSE
             OR billing_mode = 'STORE_MANAGED'
             OR (billing_mode = 'AUTO_RENEW' AND payment_mandate_id IS NOT NULL))
    )
);

CREATE UNIQUE INDEX ux_user_subscription_provider_reference
    ON user_subscriptions (provider_id, provider_subscription_reference)
    WHERE provider_subscription_reference IS NOT NULL;

CREATE INDEX ix_user_subscriptions_user_status
    ON user_subscriptions (user_id, subscription_status, current_period_end DESC);

CREATE INDEX ix_user_subscriptions_renewal_due
    ON user_subscriptions (next_billing_at)
    WHERE auto_renew_enabled = TRUE AND subscription_status = 'ACTIVE';

-- Links each paid order to the subscription term it activates/extends.
CREATE TABLE subscription_payment_allocations (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id         UUID NOT NULL REFERENCES user_subscriptions(id) ON DELETE RESTRICT,
    payment_order_id        UUID NOT NULL UNIQUE REFERENCES payment_orders(id) ON DELETE RESTRICT,
    allocation_type         VARCHAR(20) NOT NULL
                            CHECK (allocation_type IN ('INITIAL', 'RENEWAL', 'UPGRADE', 'RECOVERY')),
    period_start            TIMESTAMPTZ NOT NULL,
    period_end              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_subscription_allocation_period CHECK (period_end > period_start)
);

CREATE INDEX ix_subscription_allocations_subscription
    ON subscription_payment_allocations (subscription_id, period_start DESC);

CREATE TABLE subscription_events (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id         UUID NOT NULL REFERENCES user_subscriptions(id) ON DELETE RESTRICT,
    payment_order_id        UUID REFERENCES payment_orders(id) ON DELETE SET NULL,
    event_type              VARCHAR(60) NOT NULL,
    previous_status         VARCHAR(20),
    new_status              VARCHAR(20),
    actor_type              VARCHAR(20) NOT NULL DEFAULT 'SYSTEM'
                            CHECK (actor_type IN ('SYSTEM', 'USER', 'ADMIN', 'PROVIDER')),
    event_details           JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_subscription_events_subscription_created
    ON subscription_events (subscription_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 8. AI credits and usage accounting
-- ---------------------------------------------------------------------------

CREATE TABLE ai_credit_accounts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    currency_unit       VARCHAR(20) NOT NULL DEFAULT 'AI_CREDIT',
    balance_units       BIGINT NOT NULL DEFAULT 0 CHECK (balance_units >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_ai_credit_account_user_unit UNIQUE (user_id, currency_unit)
);

CREATE TABLE ai_credit_transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    credit_account_id   UUID NOT NULL REFERENCES ai_credit_accounts(id) ON DELETE CASCADE,
    ai_request_id       UUID REFERENCES ai_requests(id) ON DELETE SET NULL,
    payment_order_id    UUID REFERENCES payment_orders(id) ON DELETE SET NULL,
    payment_transaction_id UUID REFERENCES payment_transactions(id) ON DELETE SET NULL,
    transaction_type    VARCHAR(20) NOT NULL
                        CHECK (transaction_type IN ('PURCHASE', 'GRANT', 'CONSUMPTION', 'REFUND', 'EXPIRY', 'ADJUSTMENT')),
    delta_units         BIGINT NOT NULL CHECK (delta_units <> 0),
    balance_after       BIGINT NOT NULL CHECK (balance_after >= 0),
    idempotency_key     VARCHAR(128),
    description         VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_ai_credit_transaction_idempotency
    ON ai_credit_transactions (credit_account_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_ai_credit_transactions_account_created
    ON ai_credit_transactions (credit_account_id, created_at DESC);

CREATE INDEX ix_ai_credit_transactions_payment_order
    ON ai_credit_transactions (payment_order_id)
    WHERE payment_order_id IS NOT NULL;

CREATE TABLE usage_counters (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature_code        VARCHAR(60) NOT NULL,
    period_start        TIMESTAMPTZ NOT NULL,
    period_end          TIMESTAMPTZ NOT NULL,
    used_units          BIGINT NOT NULL DEFAULT 0 CHECK (used_units >= 0),
    reserved_units      BIGINT NOT NULL DEFAULT 0 CHECK (reserved_units >= 0),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_usage_period CHECK (period_end > period_start),
    CONSTRAINT ux_usage_user_feature_period UNIQUE (user_id, feature_code, period_start, period_end)
);

CREATE INDEX ix_usage_counters_user_feature
    ON usage_counters (user_id, feature_code, period_start DESC);

-- ---------------------------------------------------------------------------
-- 9. Feature flags and application configuration
-- ---------------------------------------------------------------------------

CREATE TABLE feature_flags (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    flag_code           VARCHAR(80) NOT NULL UNIQUE,
    description         TEXT,
    is_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    rollout_percentage  SMALLINT NOT NULL DEFAULT 0 CHECK (rollout_percentage BETWEEN 0 AND 100),
    platform_scope      VARCHAR(20) NOT NULL DEFAULT 'ALL'
                        CHECK (platform_scope IN ('ALL', 'ANDROID', 'IOS', 'WEB')),
    configuration       JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_feature_overrides (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature_flag_id     UUID NOT NULL REFERENCES feature_flags(id) ON DELETE CASCADE,
    is_enabled          BOOLEAN NOT NULL,
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
-- 10. Admin roles and audit events
-- ---------------------------------------------------------------------------

CREATE TABLE admin_roles (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_code           VARCHAR(40) NOT NULL UNIQUE,
    description         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE user_admin_roles (
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    admin_role_id       UUID NOT NULL REFERENCES admin_roles(id) ON DELETE RESTRICT,
    assigned_by         UUID REFERENCES users(id) ON DELETE SET NULL,
    assigned_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, admin_role_id)
);

CREATE TABLE audit_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id       UUID REFERENCES users(id) ON DELETE SET NULL,
    action_code         VARCHAR(100) NOT NULL,
    entity_type         VARCHAR(80) NOT NULL,
    entity_id           VARCHAR(100),
    outcome             VARCHAR(20) NOT NULL DEFAULT 'SUCCESS'
                        CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    correlation_id      VARCHAR(100),
    ip_address          INET,
    metadata            JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_audit_events_created
    ON audit_events (created_at DESC);

CREATE INDEX ix_audit_events_actor_created
    ON audit_events (actor_user_id, created_at DESC);

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
    CONSTRAINT ck_user_deletion_schedule CHECK (scheduled_purge_at IS NULL OR scheduled_purge_at >= requested_at),
    CONSTRAINT ck_user_deletion_completed CHECK (completed_at IS NULL OR completed_at >= requested_at)
);

CREATE UNIQUE INDEX ux_user_one_open_deletion_request
    ON user_deletion_requests (user_id)
    WHERE request_status IN ('REQUESTED', 'SCHEDULED', 'PROCESSING');

CREATE INDEX ix_user_deletion_requests_status_schedule
    ON user_deletion_requests (request_status, scheduled_purge_at);

-- ---------------------------------------------------------------------------
-- 11. Updated-at trigger helper
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

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_password_credentials_updated_at
    BEFORE UPDATE ON user_password_credentials
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_user_devices_updated_at
    BEFORE UPDATE ON user_devices
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_supported_languages_updated_at
    BEFORE UPDATE ON supported_languages
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_language_resources_updated_at
    BEFORE UPDATE ON language_resources
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_transliteration_profiles_updated_at
    BEFORE UPDATE ON transliteration_profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_language_terms_updated_at
    BEFORE UPDATE ON language_terms
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_user_preferences_updated_at
    BEFORE UPDATE ON user_preferences
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_user_typing_languages_updated_at
    BEFORE UPDATE ON user_typing_languages
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_contact_language_preferences_updated_at
    BEFORE UPDATE ON user_contact_language_preferences
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_ai_providers_updated_at
    BEFORE UPDATE ON ai_providers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_ai_models_updated_at
    BEFORE UPDATE ON ai_models
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_ai_language_capabilities_updated_at
    BEFORE UPDATE ON ai_language_capabilities
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_ai_prompt_templates_updated_at
    BEFORE UPDATE ON ai_prompt_templates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_subscription_plans_updated_at
    BEFORE UPDATE ON subscription_plans
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_billing_currencies_updated_at
    BEFORE UPDATE ON billing_currencies
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_billing_products_updated_at
    BEFORE UPDATE ON billing_products
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_billing_prices_updated_at
    BEFORE UPDATE ON billing_prices
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payment_providers_updated_at
    BEFORE UPDATE ON payment_providers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_provider_payment_methods_updated_at
    BEFORE UPDATE ON provider_payment_methods
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_external_product_mappings_updated_at
    BEFORE UPDATE ON external_product_mappings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_billing_market_channel_policies_updated_at
    BEFORE UPDATE ON billing_market_channel_policies
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payment_orders_updated_at
    BEFORE UPDATE ON payment_orders
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payment_attempts_updated_at
    BEFORE UPDATE ON payment_attempts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payment_mandates_updated_at
    BEFORE UPDATE ON payment_mandates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_payment_refunds_updated_at
    BEFORE UPDATE ON payment_refunds
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_manual_payment_reviews_updated_at
    BEFORE UPDATE ON manual_payment_reviews
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_user_subscriptions_updated_at
    BEFORE UPDATE ON user_subscriptions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_plan_entitlements_updated_at
    BEFORE UPDATE ON plan_entitlements
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_ai_credit_accounts_updated_at
    BEFORE UPDATE ON ai_credit_accounts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_usage_counters_updated_at
    BEFORE UPDATE ON usage_counters
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_feature_flags_updated_at
    BEFORE UPDATE ON feature_flags
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_app_configurations_updated_at
    BEFORE UPDATE ON app_configurations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- Version 1.4 hardening: merchant accounts, relational billing integrity,
-- active-price overlap prevention and starter plan entitlements.
-- ---------------------------------------------------------------------------

-- Used by the exclusion constraint that prevents overlapping active prices.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Each actual merchant relationship / store configuration is an account.
-- Account credentials remain in a secret manager; only opaque references live here.
CREATE TABLE payment_provider_accounts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id                 UUID NOT NULL REFERENCES payment_providers(id) ON DELETE RESTRICT,
    account_code                VARCHAR(60) NOT NULL,
    display_name                VARCHAR(160) NOT NULL,
    country_code                CHAR(2) NOT NULL CHECK (country_code = 'ZZ' OR country_code ~ '^[A-Z]{2}$'),
    currency_code               CHAR(3) NOT NULL REFERENCES billing_currencies(currency_code),
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
    CONSTRAINT ux_provider_account_code UNIQUE (provider_id, account_code),
    CONSTRAINT ux_provider_account_scope UNIQUE (id, provider_id, country_code, currency_code, environment),
    CONSTRAINT ck_provider_account_activation CHECK (is_active = FALSE OR account_status = 'ACTIVE')
);

CREATE INDEX ix_provider_accounts_lookup
    ON payment_provider_accounts (provider_id, country_code, currency_code, environment, is_active, account_status);

COMMENT ON TABLE payment_provider_accounts IS
    'Merchant/store accounts are scoped by provider, market, currency and environment. Never store raw credentials; store secret-manager references only.';

-- A gateway may expose different methods for different merchant accounts.
-- This table is the final method-availability gate used by checkout.
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

-- Composite unique keys make it possible for child records to enforce that
-- provider, method, market, currency and environment all belong together.
ALTER TABLE provider_payment_methods
    ADD CONSTRAINT ux_provider_payment_method_scope
    UNIQUE (id, provider_id, country_code, currency_code);

ALTER TABLE billing_prices
    ADD CONSTRAINT ux_billing_price_scope
    UNIQUE (id, billing_product_id, currency_code, market_country_code, sales_channel);

ALTER TABLE payment_orders
    ADD CONSTRAINT ux_payment_order_scope
    UNIQUE (id, user_id, market_country_code, currency_code);

-- Fail with actionable errors before adding stricter foreign keys if v1.3
-- history contains inconsistent order/price or transaction/attempt snapshots.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM payment_orders o
        JOIN billing_prices bp ON bp.id = o.billing_price_id
        WHERE bp.billing_product_id <> o.billing_product_id
           OR bp.currency_code <> o.currency_code
           OR bp.market_country_code <> o.market_country_code
           OR bp.sales_channel <> o.sales_channel
           OR bp.amount_minor <> o.amount_minor
    ) THEN
        RAISE EXCEPTION 'Cannot install v1.4: existing payment orders do not match their product/price/country/channel/currency/amount snapshots.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM payment_attempts a
        JOIN payment_orders o ON o.id = a.payment_order_id
        LEFT JOIN provider_payment_methods pm ON pm.id = a.provider_payment_method_id
        WHERE a.provider_payment_method_id IS NOT NULL
          AND (pm.id IS NULL OR pm.provider_id <> a.provider_id
               OR pm.country_code <> o.market_country_code
               OR pm.currency_code <> o.currency_code)
    ) THEN
        RAISE EXCEPTION 'Cannot install v1.4: existing payment attempts reference a method from a different provider/market/currency.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM payment_transactions t
        JOIN payment_attempts a ON a.id = t.payment_attempt_id
        JOIN payment_orders o ON o.id = a.payment_order_id
        WHERE t.provider_id <> a.provider_id OR t.currency_code <> o.currency_code
    ) THEN
        RAISE EXCEPTION 'Cannot install v1.4: existing payment transactions do not match their attempt provider/currency.';
    END IF;
END $$;

ALTER TABLE payment_orders
    ADD CONSTRAINT fk_payment_order_price_product_scope
    FOREIGN KEY (billing_price_id, billing_product_id, currency_code, market_country_code, sales_channel)
    REFERENCES billing_prices (id, billing_product_id, currency_code, market_country_code, sales_channel) ON DELETE RESTRICT;

-- Include the quoted amount in the relational binding so a client/server bug cannot
-- attach a valid price ID to a different amount snapshot.
ALTER TABLE billing_prices
    ADD CONSTRAINT ux_billing_price_scope_amount
    UNIQUE (id, billing_product_id, currency_code, market_country_code, sales_channel, amount_minor);

ALTER TABLE payment_orders
    ADD CONSTRAINT fk_payment_order_price_amount_scope
    FOREIGN KEY (billing_price_id, billing_product_id, currency_code, market_country_code, sales_channel, amount_minor)
    REFERENCES billing_prices (id, billing_product_id, currency_code, market_country_code, sales_channel, amount_minor) ON DELETE RESTRICT;

-- Reject overlapping date ranges for active prices with the same product,
-- market, channel, currency, billing period and renewal model.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM billing_prices a
        JOIN billing_prices b
          ON a.id < b.id
         AND a.billing_product_id = b.billing_product_id
         AND a.market_country_code = b.market_country_code
         AND a.sales_channel = b.sales_channel
         AND a.currency_code = b.currency_code
         AND a.billing_period = b.billing_period
         AND a.renewal_model = b.renewal_model
         AND tstzrange(a.valid_from, COALESCE(a.valid_until, 'infinity'::timestamptz), '[)')
             && tstzrange(b.valid_from, COALESCE(b.valid_until, 'infinity'::timestamptz), '[)')
        WHERE a.is_active = TRUE AND b.is_active = TRUE
    ) THEN
        RAISE EXCEPTION 'Cannot install v1.4: overlapping active billing_prices exist. Retire or adjust overlapping price rows first.';
    END IF;
END $$;

ALTER TABLE billing_prices
    ADD CONSTRAINT ex_billing_prices_no_active_overlap
    EXCLUDE USING gist (
        billing_product_id WITH =,
        market_country_code WITH =,
        sales_channel WITH =,
        currency_code WITH =,
        billing_period WITH =,
        renewal_model WITH =,
        tstzrange(valid_from, COALESCE(valid_until, 'infinity'::timestamptz), '[)') WITH &&
    ) WHERE (is_active = TRUE);

-- Validate that new orders use a currently active price and the matching product type.
-- Composite FKs below also enforce exact product/price/market/channel/currency/amount.
CREATE OR REPLACE FUNCTION fn_validate_payment_order_business_rules()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_product_type VARCHAR(24);
    v_price_active BOOLEAN;
    v_price_valid_from TIMESTAMPTZ;
    v_price_valid_until TIMESTAMPTZ;
BEGIN
    SELECT product_type INTO v_product_type
    FROM billing_products WHERE id = NEW.billing_product_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Billing product does not exist';
    END IF;

    SELECT is_active, valid_from, valid_until
      INTO v_price_active, v_price_valid_from, v_price_valid_until
    FROM billing_prices WHERE id = NEW.billing_price_id;
    IF NOT FOUND OR v_price_active IS DISTINCT FROM TRUE
       OR v_price_valid_from > NOW()
       OR (v_price_valid_until IS NOT NULL AND v_price_valid_until <= NOW()) THEN
        RAISE EXCEPTION 'The selected billing price is not active at order creation time';
    END IF;

    IF (NEW.order_type IN ('SUBSCRIPTION_PURCHASE', 'SUBSCRIPTION_RENEWAL') AND v_product_type <> 'SUBSCRIPTION')
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

-- Payment attempts snapshot their order's country/currency and select a specific
-- merchant account + account-enabled method. Existing v1.3 rows are backfilled;
-- new attempts are required by a guard trigger to provide the full account binding.
ALTER TABLE payment_attempts
    ADD COLUMN market_country_code CHAR(2),
    ADD COLUMN currency_code CHAR(3),
    ADD COLUMN environment VARCHAR(16),
    ADD COLUMN provider_account_id UUID,
    ADD COLUMN provider_account_method_id UUID;

UPDATE payment_attempts a
SET market_country_code = o.market_country_code,
    currency_code = o.currency_code,
    environment = 'PRODUCTION'
FROM payment_orders o
WHERE o.id = a.payment_order_id;

ALTER TABLE payment_attempts
    ALTER COLUMN market_country_code SET NOT NULL,
    ALTER COLUMN currency_code SET NOT NULL,
    ALTER COLUMN environment SET NOT NULL;

ALTER TABLE payment_attempts
    ADD CONSTRAINT ck_payment_attempt_environment CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    ADD CONSTRAINT fk_payment_attempt_order_market_currency
        FOREIGN KEY (payment_order_id, user_id, market_country_code, currency_code)
        REFERENCES payment_orders (id, user_id, market_country_code, currency_code) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_payment_attempt_provider_method_scope
        FOREIGN KEY (provider_payment_method_id, provider_id, market_country_code, currency_code)
        REFERENCES provider_payment_methods (id, provider_id, country_code, currency_code) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_payment_attempt_provider_account_scope
        FOREIGN KEY (provider_account_id, provider_id, market_country_code, currency_code, environment)
        REFERENCES payment_provider_accounts (id, provider_id, country_code, currency_code, environment) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_payment_attempt_account_method_binding
        FOREIGN KEY (provider_account_method_id, provider_account_id, provider_id, provider_payment_method_id)
        REFERENCES payment_provider_account_methods (id, provider_account_id, provider_id, provider_payment_method_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ux_payment_attempt_provider_currency_binding
        UNIQUE (id, provider_id, currency_code);

CREATE INDEX ix_payment_attempts_provider_account
    ON payment_attempts (provider_account_id, provider_account_method_id)
    WHERE provider_account_id IS NOT NULL;

-- A financial transaction cannot claim a provider or currency different from
-- the payment attempt it belongs to.
ALTER TABLE payment_transactions
    ADD CONSTRAINT fk_payment_transaction_attempt_provider_currency
    FOREIGN KEY (payment_attempt_id, provider_id, currency_code)
    REFERENCES payment_attempts (id, provider_id, currency_code) ON DELETE RESTRICT;

-- Mandates are tied to the exact merchant account and enabled merchant method.
ALTER TABLE payment_mandates
    ADD COLUMN provider_account_id UUID,
    ADD COLUMN provider_account_method_id UUID;

ALTER TABLE payment_mandates
    ADD CONSTRAINT fk_payment_mandate_account_method_binding
    FOREIGN KEY (provider_account_method_id, provider_account_id, provider_id, provider_payment_method_id)
    REFERENCES payment_provider_account_methods (id, provider_account_id, provider_id, provider_payment_method_id) ON DELETE RESTRICT,
    ADD CONSTRAINT ux_payment_mandate_full_binding
    UNIQUE (id, user_id, provider_id, provider_payment_method_id, provider_account_id, provider_account_method_id);

-- Subscriptions retain the same merchant account context as their mandate.
ALTER TABLE user_subscriptions
    ADD COLUMN provider_account_id UUID,
    ADD COLUMN provider_account_method_id UUID;

UPDATE user_subscriptions s
SET provider_account_id = m.provider_account_id,
    provider_account_method_id = m.provider_account_method_id
FROM payment_mandates m
WHERE s.payment_mandate_id = m.id;

ALTER TABLE user_subscriptions
    ADD CONSTRAINT fk_subscription_account_method_binding
    FOREIGN KEY (provider_account_method_id, provider_account_id, provider_id, provider_payment_method_id)
    REFERENCES payment_provider_account_methods (id, provider_account_id, provider_id, provider_payment_method_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_subscription_mandate_full_binding
    FOREIGN KEY (payment_mandate_id, user_id, provider_id, provider_payment_method_id, provider_account_id, provider_account_method_id)
    REFERENCES payment_mandates (id, user_id, provider_id, provider_payment_method_id, provider_account_id, provider_account_method_id) ON DELETE RESTRICT;

-- Guard new payment attempts: an operator-enabled provider, active merchant
-- account, enabled account method and enabled base method are all mandatory.
CREATE OR REPLACE FUNCTION fn_require_active_payment_account_for_attempt()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_order_status VARCHAR(24);
    v_order_expires_at TIMESTAMPTZ;
BEGIN
    SELECT order_status, expires_at INTO v_order_status, v_order_expires_at
    FROM payment_orders
    WHERE id = NEW.payment_order_id AND user_id = NEW.user_id;

    IF NOT FOUND OR v_order_status NOT IN ('CREATED', 'AWAITING_PAYMENT')
       OR (v_order_expires_at IS NOT NULL AND v_order_expires_at <= NOW()) THEN
        RAISE EXCEPTION 'Payment attempt requires an owned, unpaid, non-expired order';
    END IF;

    IF NEW.provider_account_id IS NULL OR NEW.provider_account_method_id IS NULL
       OR NEW.provider_payment_method_id IS NULL THEN
        RAISE EXCEPTION 'A payment attempt must select a merchant account and an account-enabled payment method';
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
    ) THEN
        RAISE EXCEPTION 'The selected payment provider, merchant account or payment method is not currently enabled for this market/currency/environment';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_attempt_requires_active_merchant_account
    BEFORE INSERT ON payment_attempts
    FOR EACH ROW EXECUTE FUNCTION fn_require_active_payment_account_for_attempt();

-- A new recurring mandate also requires an account-level method approved for
-- recurring charges; one-time payment capability is not enough.
CREATE OR REPLACE FUNCTION fn_require_recurring_payment_account_method()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.provider_account_id IS NULL OR NEW.provider_account_method_id IS NULL THEN
        RAISE EXCEPTION 'A payment mandate must reference a merchant account and account-enabled payment method';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM user_consents c
        WHERE c.id = NEW.consent_record_id
          AND c.user_id = NEW.user_id
          AND c.consent_code = 'PAYMENT_MANDATE'
          AND c.is_granted = TRUE
          AND c.revoked_at IS NULL
          AND c.granted_at IS NOT NULL
          AND c.granted_at <= NEW.consented_at
    ) THEN
        RAISE EXCEPTION 'An unrevoked PAYMENT_MANDATE consent for this user is required';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM payment_provider_account_methods am
        JOIN payment_provider_accounts a ON a.id = am.provider_account_id
        JOIN provider_payment_methods pm ON pm.id = am.provider_payment_method_id
        WHERE am.id = NEW.provider_account_method_id
          AND am.provider_account_id = NEW.provider_account_id
          AND am.provider_id = NEW.provider_id
          AND am.provider_payment_method_id = NEW.provider_payment_method_id
          AND am.is_enabled = TRUE AND am.availability_status = 'AVAILABLE'
          AND am.supports_auto_renew = TRUE
          AND a.is_active = TRUE AND a.account_status = 'ACTIVE'
          AND pm.is_enabled = TRUE AND pm.availability_status = 'AVAILABLE'
          AND pm.supports_auto_renew = TRUE
    ) THEN
        RAISE EXCEPTION 'The selected merchant account method is not approved for automatic renewal';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payment_mandate_requires_recurring_account_method
    BEFORE INSERT ON payment_mandates
    FOR EACH ROW EXECUTE FUNCTION fn_require_recurring_payment_account_method();

-- Starter entitlements: quotas are explicitly provisional product defaults.
-- Admins may tune these values before launch; usage enforcement remains server-side.
WITH entitlement_seed(plan_code, feature_code, access_enabled, included_units, reset_period, unit_name) AS (
    VALUES
    ('FREE', 'OFFLINE_TYPING', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('FREE', 'DIRECT_SCRIPT_LAYOUT', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('FREE', 'PHONETIC_TRANSLITERATION', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('FREE', 'TRANSLATE', TRUE, 10::BIGINT, 'DAILY', 'REQUEST'),
    ('FREE', 'GRAMMAR_CORRECTION', TRUE, 10::BIGINT, 'DAILY', 'REQUEST'),
    ('FREE', 'REWRITE_NATURAL', TRUE, 3::BIGINT, 'DAILY', 'REQUEST'),
    ('FREE', 'REWRITE_PROFESSIONAL', FALSE, 0::BIGINT, 'NONE', 'REQUEST'),
    ('FREE', 'REWRITE_FRIENDLY', FALSE, 0::BIGINT, 'NONE', 'REQUEST'),
    ('FREE', 'REWRITE_POLITE', FALSE, 0::BIGINT, 'NONE', 'REQUEST'),
    ('FREE', 'SMART_REPLY', FALSE, 0::BIGINT, 'NONE', 'REQUEST'),
    ('FREE', 'SUMMARIZE_TEXT', FALSE, 0::BIGINT, 'NONE', 'REQUEST'),
    ('FREE', 'VOICE_TRANSCRIPTION', FALSE, 0::BIGINT, 'NONE', 'MINUTE'),
    ('FREE', 'VOICE_TRANSLATE', FALSE, 0::BIGINT, 'NONE', 'MINUTE'),
    ('FREE', 'TEXT_TO_SPEECH', FALSE, 0::BIGINT, 'NONE', 'MINUTE'),

    ('PREMIUM', 'OFFLINE_TYPING', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM', 'DIRECT_SCRIPT_LAYOUT', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM', 'PHONETIC_TRANSLITERATION', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM', 'TRANSLATE', TRUE, 600::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'GRAMMAR_CORRECTION', TRUE, 600::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'REWRITE_NATURAL', TRUE, 300::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'REWRITE_PROFESSIONAL', TRUE, 150::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'REWRITE_FRIENDLY', TRUE, 150::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'REWRITE_POLITE', TRUE, 150::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'SMART_REPLY', TRUE, 100::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'SUMMARIZE_TEXT', TRUE, 100::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM', 'VOICE_TRANSCRIPTION', TRUE, 60::BIGINT, 'MONTHLY', 'MINUTE'),
    ('PREMIUM', 'VOICE_TRANSLATE', TRUE, 30::BIGINT, 'MONTHLY', 'MINUTE'),
    ('PREMIUM', 'TEXT_TO_SPEECH', TRUE, 60::BIGINT, 'MONTHLY', 'MINUTE'),

    ('PREMIUM_PLUS', 'OFFLINE_TYPING', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM_PLUS', 'DIRECT_SCRIPT_LAYOUT', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM_PLUS', 'PHONETIC_TRANSLITERATION', TRUE, NULL::BIGINT, 'NONE', 'SESSION'),
    ('PREMIUM_PLUS', 'TRANSLATE', TRUE, 2000::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'GRAMMAR_CORRECTION', TRUE, 2000::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'REWRITE_NATURAL', TRUE, 1000::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'REWRITE_PROFESSIONAL', TRUE, 600::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'REWRITE_FRIENDLY', TRUE, 600::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'REWRITE_POLITE', TRUE, 600::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'SMART_REPLY', TRUE, 500::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'SUMMARIZE_TEXT', TRUE, 500::BIGINT, 'MONTHLY', 'REQUEST'),
    ('PREMIUM_PLUS', 'VOICE_TRANSCRIPTION', TRUE, 300::BIGINT, 'MONTHLY', 'MINUTE'),
    ('PREMIUM_PLUS', 'VOICE_TRANSLATE', TRUE, 150::BIGINT, 'MONTHLY', 'MINUTE'),
    ('PREMIUM_PLUS', 'TEXT_TO_SPEECH', TRUE, 300::BIGINT, 'MONTHLY', 'MINUTE')
)
INSERT INTO plan_entitlements
    (plan_id, feature_code, access_enabled, included_units, reset_period, unit_name, configuration)
SELECT p.id, e.feature_code, e.access_enabled, e.included_units, e.reset_period, e.unit_name,
       jsonb_build_object('quotaPolicyVersion', 'INITIAL_PROPOSAL_V1', 'requiresServerEnforcement', TRUE)
FROM entitlement_seed e
JOIN subscription_plans p ON p.plan_code = e.plan_code
ON CONFLICT (plan_id, feature_code) DO NOTHING;

COMMENT ON TABLE plan_entitlements IS
    'Plan feature access and allowances. Initial v1.4 quotas are provisional launch proposals and must be reviewed against AI cost, market pricing and product policy.';

-- End of v1.4 hardening block.

COMMIT;

-- ============================================================================
-- End of initial schema.
--
-- Important implementation notes:
-- 1. Run this through Flyway in a disposable database first and validate every
--    migration before applying it to staging/production.
-- 2. Do not store provider secrets in ai_providers.secret_reference; that field
--    is only a pointer to a secret manager/environment secret.
-- 3. Enforce credit balance and usage-counter changes in a database transaction
--    with row locking (SELECT ... FOR UPDATE) and idempotency checks.
-- 4. Implement scheduled deletion of expired audio objects and expired saved
--    AI results. Deleting a DB row alone does not delete an object-store file.
-- 5. Store purchase-token ciphertext only if required for verification and
--    protect it using application-managed encryption and key rotation.
-- 6. Never create a user_subscriptions row or grant AI credits from a client
--    success screen or webhook body alone. Verify the payment server-to-server,
--    validate amount/currency/reference, then commit order, transaction, ledger
--    and entitlement changes atomically.
-- 7. Treat missing billing_market_channel_policies rows as DENY. External
--    checkout buttons must remain disabled until the relevant country/platform/
--    distribution policy has been reviewed and marked APPROVED.
-- 8. Do not set provider_payment_methods.supports_auto_renew until the provider
--    has confirmed the mandate/recurring capability for the specific method,
--    country, currency and merchant account. Create a payment_mandates row only
--    after explicit user consent and provider confirmation/approval.
-- 9. Provider availability is merchant-account, country, currency and method specific. Refresh or
--    verify provider methods and limits; do not assume every documented method
--    is enabled for this business account.
-- 10. For Ethiopia, prefer ETB fixed-term purchases with MANUAL_RENEW unless an
--     approved recurring mandate exists. A one-off wallet charge is not consent
--     to debit the wallet again in the future.
-- 11. The seed language list is a starting catalogue, not a statement that every
--     listed language is production-ready.
-- 12. Amharic/Tigrinya phonetic transliteration profiles are seeded as DRAFT.
--     Do not make them selectable in production until native speakers approve
--     the profile mappings and both mobile engines pass the same golden tests.
-- 13. PHONETIC_LATIN_TO_NATIVE is a local input mode: Latin QWERTY key events
--     are converted into native-script composing text. It is not translation,
--     is not an AI request, and must never send keystrokes to the backend.
-- 14. `user_typing_languages.input_mode` is synced as a preference, then cached
--     on-device. The keyboard must not make a network request for each keypress.
-- 15. Only ACTIVE transliteration profiles may be assigned to users. An ACTIVE
--     profile must reference a PUBLISHED TRANSLITERATION_RULES resource with a
--     signed manifest. Clients must verify the signature and checksum locally.
-- 16. A user selecting English/LATIN_PASSTHROUGH must get ordinary Latin text;
--     do not silently transliterate English words. Users can switch to/from
--     phonetic mode using an in-keyboard mode control.
-- 17. Transliteration profile rows are metadata only; the actual mappings,
--     exceptions, candidate priorities and test vectors live in versioned
--     TRANSLITERATION_RULES language-resource artifacts.
-- ============================================================================
