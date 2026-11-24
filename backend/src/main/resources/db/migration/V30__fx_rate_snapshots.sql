-- T8.3 (ADR 0048): daily currency-rate snapshot behind GET /api/v1/tools/fx-rates.
-- One row per (base, quote, as_of). The API serves the rows of the greatest as_of for base USD.
-- The seed below is a DEV/TEST table (source = 'seed'), dated at migration time; it is NOT live market data.
-- A real provider (open question: FX source and licence, SD 26.2) writes new snapshots or replaces the FxRateSource port.

CREATE TABLE fx_rate_snapshots (
    base_currency  CHAR(3)         NOT NULL,
    quote_currency CHAR(3)         NOT NULL,
    rate           NUMERIC(20, 10) NOT NULL,
    as_of          TIMESTAMPTZ     NOT NULL,
    source         VARCHAR(100)    NOT NULL,
    PRIMARY KEY (base_currency, quote_currency, as_of),
    CONSTRAINT ck_fx_rate_positive CHECK (rate > 0),
    CONSTRAINT ck_fx_rate_distinct CHECK (base_currency <> quote_currency)
);

CREATE INDEX ix_fx_rate_snapshots_latest ON fx_rate_snapshots (base_currency, as_of DESC);

INSERT INTO fx_rate_snapshots (base_currency, quote_currency, rate, as_of, source) VALUES
    ('USD', 'ETB', 155.0000000000, now(), 'seed'),
    ('USD', 'EUR',   0.8600000000, now(), 'seed'),
    ('USD', 'GBP',   0.7600000000, now(), 'seed'),
    ('USD', 'AED',   3.6725000000, now(), 'seed'),
    ('USD', 'SAR',   3.7500000000, now(), 'seed'),
    ('USD', 'KES', 129.0000000000, now(), 'seed'),
    ('USD', 'CAD',   1.4000000000, now(), 'seed'),
    ('USD', 'AUD',   1.5400000000, now(), 'seed'),
    ('USD', 'CNY',   7.1000000000, now(), 'seed'),
    ('USD', 'INR',  88.0000000000, now(), 'seed'),
    ('USD', 'JPY', 155.0000000000, now(), 'seed');
