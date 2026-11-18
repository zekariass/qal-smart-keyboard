-- B3.2: provenance of published language packs (rule N10). Every pack uploaded through /admin/v1 names the
-- language_datasets rows it was built from (language-data datasets.manifest.json, L1.2); the publisher re-checks their
-- licences when the pack is staged or published, so a dataset later marked REJECTED or RESTRICTED blocks release.
-- V1 has no table linking resources to datasets.
CREATE TABLE language_resource_datasets (
    resource_id         UUID NOT NULL REFERENCES language_resources(id) ON DELETE CASCADE,
    dataset_id          UUID NOT NULL REFERENCES language_datasets(id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (resource_id, dataset_id)
);

CREATE INDEX ix_language_resource_datasets_dataset
    ON language_resource_datasets (dataset_id);
