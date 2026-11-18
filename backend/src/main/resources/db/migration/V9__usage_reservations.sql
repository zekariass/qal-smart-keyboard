-- B4.2 metering engine (ADR 0011): one mutable state row per usage reservation.
--
-- usage_events stays the append-only ledger (RESERVE, then exactly one SETTLE or RELEASE). It has no place for what
-- the engine must remember between reserve and settle: how a reservation is split between the plan allowance and AI
-- credits (SD 15.3 step 3), the credit price it was held at, the allowance it was checked against, and the state the
-- expiry sweeper and idempotent replays need. Scanning usage_events for open RESERVE rows would also grow with the
-- whole ledger; the partial index below only holds open reservations.
--
-- id equals the usage_events id of the RESERVE row (= reservation_id of its SETTLE/RELEASE row). Rows are kept as
-- long as the idempotency key must be honoured; retention is B6.3.

CREATE TABLE usage_reservations (
    id                      UUID PRIMARY KEY,
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature_code            VARCHAR(40) NOT NULL REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,
    usage_counter_id        UUID NOT NULL REFERENCES usage_counters(id) ON DELETE CASCADE,
    idempotency_key         VARCHAR(128) NOT NULL CHECK (length(idempotency_key) > 0),
    metering_unit           VARCHAR(12) NOT NULL CHECK (metering_unit IN ('REQUEST', 'SECOND', 'CHARACTER')),
    reservation_status      VARCHAR(10) NOT NULL DEFAULT 'RESERVED'
                            CHECK (reservation_status IN ('RESERVED', 'SETTLED', 'RELEASED')),
    estimated_units         BIGINT NOT NULL CHECK (estimated_units > 0),
    -- split of estimated_units: allowance_units are in usage_counters.reserved_units until closed
    allowance_units         BIGINT NOT NULL CHECK (allowance_units >= 0),
    credit_units            BIGINT NOT NULL CHECK (credit_units >= 0),
    -- the plan allowance of the window at reserve time (NULL = unlimited); over-settles are checked against it
    included_units          BIGINT CHECK (included_units IS NULL OR included_units >= 0),
    credit_cost_per_unit    NUMERIC(12,6) NOT NULL CHECK (credit_cost_per_unit >= 0),
    credits_held            NUMERIC(18,6) NOT NULL CHECK (credits_held >= 0),
    expires_at              TIMESTAMPTZ NOT NULL,
    -- SETTLED only
    actual_units            BIGINT CHECK (actual_units IS NULL OR actual_units > 0),
    settled_allowance_units BIGINT CHECK (settled_allowance_units IS NULL OR settled_allowance_units >= 0),
    settled_credit_units    BIGINT CHECK (settled_credit_units IS NULL OR settled_credit_units >= 0),
    credits_charged         NUMERIC(18,6) CHECK (credits_charged IS NULL OR credits_charged >= 0),
    uncovered_units         BIGINT CHECK (uncovered_units IS NULL OR uncovered_units >= 0),
    -- RELEASED only
    release_reason          VARCHAR(16) CHECK (release_reason IN ('FAILED', 'CANCELLED', 'EXPIRED', 'NOTHING_USED')),
    created_at              TIMESTAMPTZ NOT NULL,
    closed_at               TIMESTAMPTZ,
    CONSTRAINT ck_usage_reservation_split CHECK (allowance_units + credit_units = estimated_units),
    CONSTRAINT ck_usage_reservation_credits_held CHECK (credits_held = credit_units * credit_cost_per_unit),
    CONSTRAINT ck_usage_reservation_state CHECK (
        (reservation_status = 'RESERVED' AND closed_at IS NULL AND release_reason IS NULL
            AND num_nonnulls(actual_units, settled_allowance_units, settled_credit_units, credits_charged,
                             uncovered_units) = 0)
        OR (reservation_status = 'SETTLED' AND closed_at IS NOT NULL AND release_reason IS NULL
            AND num_nonnulls(actual_units, settled_allowance_units, settled_credit_units, credits_charged,
                             uncovered_units) = 5
            AND settled_allowance_units + settled_credit_units + uncovered_units = actual_units
            AND settled_allowance_units + settled_credit_units > 0
            AND credits_charged = settled_credit_units * credit_cost_per_unit)
        OR (reservation_status = 'RELEASED' AND closed_at IS NOT NULL AND release_reason IS NOT NULL
            AND num_nonnulls(actual_units, settled_allowance_units, settled_credit_units, credits_charged,
                             uncovered_units) = 0)
    )
);

COMMENT ON TABLE usage_reservations IS
    'B4.2: state of each usage reservation (allowance/credit split, idempotency key, expiry); usage_events is the ledger.';

-- At-most-once charging per Idempotency-Key and user (same scope as ux_usage_events_idempotency).
CREATE UNIQUE INDEX ux_usage_reservations_idempotency
    ON usage_reservations (user_id, idempotency_key);

-- The expiry sweeper only ever looks at open reservations.
CREATE INDEX ix_usage_reservations_open_expiry
    ON usage_reservations (expires_at)
    WHERE reservation_status = 'RESERVED';

CREATE INDEX ix_usage_reservations_counter
    ON usage_reservations (usage_counter_id);
