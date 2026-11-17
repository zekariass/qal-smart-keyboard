-- B1.4: outbox dispatcher infrastructure (forward-only; V1 is immutable).
--
-- 1. scheduled_tasks: owned by db-scheduler (github.com/kagkarlsson/db-scheduler),
--    schema copied from its postgresql_tables.sql for the pinned library version.
-- 2. processed_events: dedupe ledger giving idempotent outbox consumers. The
--    dispatcher inserts (consumer, event_id) in the SAME transaction as the
--    handler's own writes, so a handler effect is applied once per event id even
--    though delivery is at-least-once. Never an FK target; purge belongs to the
--    retention scheduler (B6.3).
-- 3. ix_outbox_events_dead: keeps the "dead events" gauge cheap.

CREATE TABLE scheduled_tasks (
    task_name            TEXT NOT NULL,
    task_instance        TEXT NOT NULL,
    task_data            BYTEA,
    execution_time       TIMESTAMPTZ NOT NULL,
    picked               BOOLEAN NOT NULL,
    picked_by            TEXT,
    last_success         TIMESTAMPTZ,
    last_failure         TIMESTAMPTZ,
    consecutive_failures INT,
    last_heartbeat       TIMESTAMPTZ,
    version              BIGINT NOT NULL,
    priority             SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);

CREATE TABLE processed_events (
    consumer      VARCHAR(120) NOT NULL,
    event_id      UUID NOT NULL,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer, event_id)
);

CREATE INDEX brin_processed_events_processed
    ON processed_events USING BRIN (processed_at);

CREATE INDEX ix_outbox_events_dead
    ON outbox_events (created_at)
    WHERE event_status = 'DEAD';
