-- B3.1: discovery metadata that the OpenAPI LanguageResourceManifest and SigningKey schemas carry but V1 has no column for.
--
-- container_size_bytes: size of the whole .pack container file (docs/specs/pack-format-v1.md section 10). It cannot live in
-- the signed manifest (the manifest would have to contain the size of the file that contains it), so the publisher (B3.2)
-- records it next to size_bytes (the artifact size inside the container). NULL = unknown; the API then omits the field.
--
-- endorsed_by_key_id / endorsement_signature: GET /resource-signing-keys announces an additional key only with an Ed25519
-- endorsement by an already pinned key (System Design 7.6); the endorsement is produced offline and stored verbatim. Both
-- or neither. A key without an endorsement is still listed (clients decide; pinned keys need none).
ALTER TABLE language_resources
    ADD COLUMN container_size_bytes BIGINT CHECK (container_size_bytes IS NULL OR container_size_bytes >= 0);

ALTER TABLE resource_signing_keys
    ADD COLUMN endorsed_by_key_id VARCHAR(120) REFERENCES resource_signing_keys (key_id) ON DELETE RESTRICT,
    ADD COLUMN endorsement_signature TEXT,
    ADD CONSTRAINT ck_signing_key_endorsement_pair CHECK ((endorsed_by_key_id IS NULL) = (endorsement_signature IS NULL)),
    ADD CONSTRAINT ck_signing_key_not_self_endorsed CHECK (endorsed_by_key_id IS NULL OR endorsed_by_key_id <> key_id);
