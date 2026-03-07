-- ═══════════════════════════════════════════════════════════════════
-- Floww Exchange — Database Schema (HFT & MACRO TIMESCALE OPTIMIZED)
-- ═══════════════════════════════════════════════════════════════════
-- 1. Foreign Keys REMOVED for max write throughput.
-- 2. Partial Index added for sub-millisecond Order Book queries.
-- 3. Macro resolutions ONLY (1d to 5y) for 5-Year Historical Data.
-- ═══════════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- 🚨 NUKE EXISTING SCHEMA
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

-- ═══════════════════════════════════════════════════════════════════
-- SEED: 100 Fictional Silicon Valley Tech Giants
-- ═══════════════════════════════════════════════════════════════════
INSERT INTO ticker (symbol, name, session_open_price) VALUES 
('SYNTH', 'Synthetix Neural Systems', 12450),
('QUBIT', 'Quantum Bridge Tech', 89200),
('VOX', 'Voxell Cloud Infrastructure', 4500),
('LUMEN', 'Lumen Biosciences', 31200),
('NEXUS', 'Nexus Logistics Global', 15600),
('ZENO', 'Zeno Payments & Fintech', 6200),
('APEX', 'Apex Cyber Defense', 44300),
('DRIFT', 'Drift Autonomy Systems', 22100),
('SOLO', 'Solon Green Energy', 1850),
('ORBT', 'Orbit Satcom Services', 54000),
('AEON', 'Aeon Virtual Reality', 8900),
('KRYP', 'Krypton Blockchain Corp', 125000),
('VRTX', 'Vertex Aerospace', 34500),
('NOVA', 'Nova Quantum Computing', 76200),
('FLUX', 'Flux Data Streaming', 1050),
('ECHO', 'Echo Acoustics & AI', 21500),
('AURA', 'Aura Smart Grids', 6700),
('CYBR', 'Cyberdine Security Solutions', 49000),
('HALO', 'Halo Genomics', 11200),
('OMNI', 'Omni Global Networks', 88500),
('PLSR', 'Pulsar Clean Energy', 3400),
('VANG', 'Vanguard Robotics', 51200),
('STRM', 'Streamline Media Group', 1450),
('TESS', 'Tesseract Fusion', 98000),
('ION', 'Ion Battery Tech', 24500),
('PRME', 'Prime Logistics AI', 17800),
('BLTZ', 'Blitz Delivery Systems', 9200),
('ZEPT', 'Zeptosecond Microchips', 65400),
('CRUX', 'Crux Financial Systems', 28900),
('GLBT', 'Globalnet Telecommunications', 11400),
('MNTH', 'Monolith Data Vaults', 43200),
('PXL', 'Pixel Perfect Displays', 5500),
('WAVE', 'Waveform Therapeutics', 39000),
('RFT', 'Rift Propulsion Labs', 71000),
('ZENI', 'Zenith Capital Markets', 14200),
('VELO', 'Velocity Transport', 26500),
('SPRK', 'Spark Electric Vehicles', 8800),
('ALPH', 'Alpha Autonomous', 67000),
('NXUS', 'Nexus Bio-Engineering', 42100),
('CLD', 'Cloudflare Analytics', 33500),
('OASIS', 'Oasis Deep Water Solutions', 12300),
('VCTR', 'Vector Graphics Inc.', 19800),
('MTRX', 'Matrix Holographics', 27600),
('AXIS', 'Axis Financial Partners', 41200),
('CORT', 'Cortex AI Architectures', 53400),
('DYN', 'Dynamics Motion Systems', 16500),
('FRGE', 'Forge Additive Manufacturing', 9400),
('HYPR', 'Hyperloop Transit', 82000),
('KNET', 'Kinetix Wearables', 21000),
('LGCY', 'Legacy Trust Bank', 30500),
('MNTA', 'Manta Ray Oceanics', 15400),
('NVNT', 'Noventis Pharmaceuticals', 61200),
('ORCL', 'Oracle Data Mining', 48900),
('PHLN', 'Phalanx Defense Contractors', 29000),
('QANT', 'Qantus Machine Learning', 56700),
('RDNT', 'Radiant Solar Panels', 8300),
('SYNC', 'Syncronicity ERP', 25500),
('TLON', 'Talon Mining Corp', 13400),
('UMBR', 'Umbra Weather Systems', 11000),
('VLT', 'Volt Energy Storage', 37000),
('WRP', 'Warp Drive Technologies', 91000),
('XEN', 'Xenon Gas Extractors', 19000),
('YLD', 'Yield Farming Protocols', 14200),
('ZRO', 'Zero Point Energy', 74000),
('AER', 'Aero Drone Delivery', 18500),
('BLNK', 'Blink Fast Charging', 6400),
('COGN', 'Cognitive Behavior Tech', 42000),
('DAT', 'Datum Distributed Ledgers', 28500),
('EQX', 'Equinox Space Tourism', 59000),
('FNT', 'Frontier Exploration', 11800),
('GALA', 'Galaxy Entertainment', 8700),
('HPT', 'Haptic Touch Interfaces', 34500),
('INV', 'Invert Robotics', 23100),
('JMP', 'Jump Start EdTech', 4500),
('KRX', 'Krux Architecture', 41000),
('LZR', 'Lazer Precision Optics', 62500),
('MST', 'Mystic Gaming Studios', 12000),
('NXT', 'NextGen Semiconductors', 78900),
('OSM', 'Osmium Heavy Industries', 5600),
('PNX', 'Phoenix Reusables', 20500),
('QRC', 'Quark Processors', 85000),
('REV', 'Revolve Wind Turbines', 16000),
('SLR', 'Solaris Farming', 9300),
('TRK', 'Trek Off-Road Vehicles', 27400),
('UNT', 'Unity Collaborative', 35500),
('VIV', 'Viva Health Systems', 49000),
('WYR', 'Wyre Communications', 10500),
('XCEL', 'Xcelerate Fitness Tech', 13200),
('YRN', 'Yarn Textile Synthetics', 6100),
('ZNT', 'Zentih Orbital', 68000),
('AQUA', 'Aqua Purifications', 22500),
('BION', 'Bionic Prosthetics', 51000),
('CRB', 'Carbon Capture Inc', 17400),
('DOX', 'Doxil Document Solutions', 8900),
('EXO', 'Exo-Skeleton Suites', 44000),
('FLR', 'Flora Synthetic Meats', 31500),
('GNT', 'Genetic Splicing Labs', 81000),
('HLX', 'Helix Structural Metals', 19900),
('INF', 'Infinity Loop Transport', 72000),
('JUP', 'Jupiter Mining Consortium', 12500),
('KDM', 'Kaldor Dark Matter', 104000),
('LYX', 'Lyra Exoplanet Mining', 46000),
('MYR', 'Myriad Synthetics', 31200),
('NEX', 'Nexar Robotics', 28000),
('OPX', 'Opus X Genomics', 16000),
('PRX', 'Praxis Algorithms', 48500),
('QRT', 'Quartz Encryption', 19000),
('RYN', 'Ryzen Power Systems', 27000),
('SGN', 'Signus Agritech', 8900),
('TCH', 'Tachyon Aerospace', 115000);
