-- V14__create_traffic_refresh_queue_tables.sql
-- Queue and idempotency ledger for Kafka-driven traffic->scenic targeted refresh.

CREATE TABLE IF NOT EXISTS processed_kafka_events (
    event_id VARCHAR(64) PRIMARY KEY,
    source VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS scenic_refresh_tile_queue (
    h3_index VARCHAR(15) PRIMARY KEY,
    source VARCHAR(64) NOT NULL,
    last_event_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    not_before TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT scenic_refresh_tile_queue_state_chk CHECK (state IN ('PENDING', 'PROCESSING'))
);

CREATE INDEX IF NOT EXISTS idx_scenic_refresh_queue_state_not_before
    ON scenic_refresh_tile_queue (state, not_before);

