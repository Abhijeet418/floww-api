-- ═══════════════════════════════════════════════════════════════════
-- Floww Exchange — Database Schema (HFT & MACRO TIMESCALE OPTIMIZED)
-- ═══════════════════════════════════════════════════════════════════
-- 1. Foreign Keys REMOVED for max write throughput.
-- 2. Partial Index added for sub-millisecond Order Book queries.
-- 3. Macro resolutions ONLY (1d to 5y) for 5-Year Historical Data.
-- ═══════════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS timescaledb;

DROP TABLE IF EXISTS webhook_delivery CASCADE;
DROP TABLE IF EXISTS candle CASCADE;
DROP TABLE IF EXISTS trade CASCADE;
DROP TABLE IF EXISTS exchange_order CASCADE;
DROP TABLE IF EXISTS ticker CASCADE;
DROP TABLE IF EXISTS registered_app CASCADE;
DROP TABLE IF EXISTS ticker_sequence CASCADE;

-- ═══════════════════════════════════════════════════════════════════
-- TABLE: registered_app
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE registered_app (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL UNIQUE,
    contact_email   VARCHAR(255) NOT NULL,
    description     TEXT,
    webhook_url     VARCHAR(2048),
    api_key_hash    VARCHAR(255) UNIQUE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW'
                    CHECK (status IN ('PENDING_REVIEW','ACTIVE','SUSPENDED','REJECTED')),
    rate_limit      INT NOT NULL DEFAULT 50,
    admin_notes     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_app_api_key_hash ON registered_app(api_key_hash);

-- ═══════════════════════════════════════════════════════════════════
-- TABLE: ticker
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE ticker (
    ticker_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol            VARCHAR(10) NOT NULL UNIQUE,
    name              VARCHAR(255) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE','HALTED')),
    lot_size          BIGINT NOT NULL DEFAULT 1,
    session_open_price  BIGINT,
    halt_reason       TEXT,
    halted_until      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ticker_symbol ON ticker(symbol);

-- ═══════════════════════════════════════════════════════════════════
-- TABLE: exchange_order (NO FOREIGN KEYS)
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE exchange_order (
    order_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_order_id VARCHAR(64) NOT NULL,
    app_id          UUID NOT NULL, -- FK Removed for performance
    trader_id       VARCHAR(64) NOT NULL,
    ticker          VARCHAR(10) NOT NULL, -- FK Removed for performance
    side            VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    order_type      VARCHAR(10) NOT NULL CHECK (order_type IN ('MARKET','LIMIT','STOP')),
    price           BIGINT,
    qty             BIGINT NOT NULL,
    filled_qty      BIGINT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN','PARTIALLY_FILLED','FILLED','CANCELLED', 'REJECTED')),
    sequence_number BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(app_id, client_order_id)
);

-- THE MAGIC INDEX: Only index active liquidity to keep the b-tree in RAM
CREATE INDEX idx_active_order_book ON exchange_order (ticker, side, price) 
WHERE status IN ('OPEN', 'PARTIALLY_FILLED');

CREATE INDEX idx_order_app_id ON exchange_order(app_id);

-- ═══════════════════════════════════════════════════════════════════
-- TABLE: trade (NO FOREIGN KEYS)
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE trade (
    trade_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticker        VARCHAR(10) NOT NULL,
    price         BIGINT NOT NULL,
    qty           BIGINT NOT NULL,
    buy_order_id  UUID NOT NULL, -- FK Removed
    sell_order_id UUID NOT NULL, -- FK Removed
    buyer_app_id  UUID NOT NULL,
    seller_app_id UUID NOT NULL,
    traded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trade_ticker_time ON trade(ticker, traded_at DESC);

-- ═══════════════════════════════════════════════════════════════════
-- TABLE: candle (TimescaleDB hypertable with MACRO resolutions ONLY)
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE candle (
    ticker      VARCHAR(10) NOT NULL,
    -- Strictly Macro timeframes for 5-Year historical data
    resolution  VARCHAR(5) NOT NULL CHECK (resolution IN ('1d','1w','1mo','3mo','6mo','1y','3y','5y')),
    bucket      TIMESTAMPTZ NOT NULL,
    open        BIGINT NOT NULL,
    high        BIGINT NOT NULL,
    low         BIGINT NOT NULL,
    close       BIGINT NOT NULL,
    volume      BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (ticker, resolution, bucket)
);

-- Note: Since we are dealing with macro data (daily to 5-yearly chunks),
-- a 1-month chunk interval is perfect for partition size.
SELECT create_hypertable('candle', 'bucket', chunk_time_interval => INTERVAL '1 month', if_not_exists => TRUE);

-- ═══════════════════════════════════════════════════════════════════
-- TABLE: webhook_delivery (NO FOREIGN KEYS)
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE webhook_delivery (
    delivery_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_id        UUID NOT NULL, -- FK Removed
    trade_id      UUID NOT NULL, -- FK Removed
    payload       JSONB NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING','DELIVERED','FAILED')),
    attempts      INT NOT NULL DEFAULT 0,
    last_attempt  TIMESTAMPTZ,
    next_retry    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_pending ON webhook_delivery(status, next_retry) WHERE status = 'PENDING';

-- ═══════════════════════════════════════════════════════════════════
-- Sequence function (atomic per-ticker sequence numbers)
-- ═══════════════════════════════════════════════════════════════════
CREATE TABLE ticker_sequence (
    ticker VARCHAR(10) PRIMARY KEY,
    seq    BIGINT NOT NULL DEFAULT 0
);

CREATE OR REPLACE FUNCTION next_sequence(p_ticker VARCHAR)
RETURNS BIGINT AS $$
DECLARE
    v_seq BIGINT;
BEGIN
    INSERT INTO ticker_sequence (ticker, seq) VALUES (p_ticker, 1)
    ON CONFLICT (ticker)
    DO UPDATE SET seq = ticker_sequence.seq + 1
    RETURNING seq INTO v_seq;
    RETURN v_seq;
END;
$$ LANGUAGE plpgsql;
