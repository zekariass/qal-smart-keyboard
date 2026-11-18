-- B5.2 (ADR 0014, "Routing (B5.2)"): provider unit prices, daily spend budgets and the daily spend ledger.
--
-- V1 has the routing policy table (ai_routing_policies), provider status and the feature flag / app configuration
-- tables, so routing order, kill switch and DEGRADED handling need no schema. Cost control did not exist: the router
-- estimates the cost of each successful call as usage x price per unit and blocks a provider whose estimated spend for
-- the UTC day reached its hard limit. Costs are micro-USD (1 000 000 = 1 USD) and may carry fractions of a micro:
-- 20 USD per million characters is 20 micros per character, but a single 40-character call must not round to zero.
--
-- No vendor prices or budgets are seeded (gate G0): a provider without a price row costs nothing and a provider
-- without a budget row is uncapped. Circuit breaker state is deliberately not stored (in process, see the ADR).

CREATE TABLE ai_provider_prices (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id         UUID NOT NULL REFERENCES ai_providers(id) ON DELETE CASCADE,
    feature_code        VARCHAR(40) REFERENCES feature_catalog(feature_code) ON DELETE RESTRICT,  -- NULL = every feature
    price_unit          VARCHAR(20) NOT NULL
                        CHECK (price_unit IN ('REQUEST', 'INPUT_CHARACTER', 'OUTPUT_CHARACTER', 'INPUT_TOKEN',
                                              'OUTPUT_TOKEN', 'AUDIO_SECOND')),
    unit_price_micros   NUMERIC(18,6) NOT NULL CHECK (unit_price_micros >= 0),
    valid_from          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until         TIMESTAMPTZ,
    notes               VARCHAR(500),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ai_provider_price_validity CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE INDEX ix_ai_provider_prices_provider ON ai_provider_prices (provider_id, price_unit, valid_from DESC);

COMMENT ON TABLE ai_provider_prices IS
    'What a provider charges us per unit (micro-USD). A feature-specific row overrides the NULL-feature row for the same unit.';

CREATE TABLE ai_provider_budgets (
    provider_id             UUID PRIMARY KEY REFERENCES ai_providers(id) ON DELETE CASCADE,
    is_enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    daily_soft_limit_micros NUMERIC(20,6) CHECK (daily_soft_limit_micros IS NULL OR daily_soft_limit_micros >= 0),
    daily_hard_limit_micros NUMERIC(20,6) CHECK (daily_hard_limit_micros IS NULL OR daily_hard_limit_micros >= 0),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_ai_provider_budget_order CHECK (
        daily_soft_limit_micros IS NULL OR daily_hard_limit_micros IS NULL
        OR daily_soft_limit_micros <= daily_hard_limit_micros)
);

COMMENT ON TABLE ai_provider_budgets IS
    'Soft limit raises an alert (log + metric); hard limit removes the provider from routing until the next UTC day or a raise. NULL = no limit.';

CREATE TABLE ai_provider_daily_spend (
    provider_id     UUID NOT NULL REFERENCES ai_providers(id) ON DELETE CASCADE,
    spend_date      DATE NOT NULL,   -- UTC day
    cost_micros     NUMERIC(20,6) NOT NULL DEFAULT 0 CHECK (cost_micros >= 0),
    call_count      BIGINT NOT NULL DEFAULT 0 CHECK (call_count >= 0),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (provider_id, spend_date)
);

COMMENT ON TABLE ai_provider_daily_spend IS
    'Estimated provider spend per UTC day (successful calls only), incremented by the router. Metadata only, never content.';
