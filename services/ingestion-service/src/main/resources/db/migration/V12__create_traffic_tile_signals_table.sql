-- V12__create_traffic_tile_signals_table.sql
-- Optional v1 traffic signal storage keyed by H3 tile.

CREATE TABLE IF NOT EXISTS traffic_tile_signals (
    h3_index VARCHAR(15) PRIMARY KEY,
    traffic_score DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    provider VARCHAR(64) NOT NULL DEFAULT 'manual',
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_traffic_score_range CHECK (traffic_score >= 0.0 AND traffic_score <= 1.0)
);

CREATE INDEX IF NOT EXISTS idx_traffic_score
    ON traffic_tile_signals (traffic_score);

