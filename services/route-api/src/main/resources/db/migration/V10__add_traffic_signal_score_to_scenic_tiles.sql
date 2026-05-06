-- V10__add_traffic_signal_score_to_scenic_tiles.sql
-- Keep route-api schema aligned with shared scenic tile model.

ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS traffic_signal_score DOUBLE PRECISION NOT NULL DEFAULT 0.5;

ALTER TABLE scenic_score_tiles
    DROP CONSTRAINT IF EXISTS traffic_signal_score_range;

ALTER TABLE scenic_score_tiles
    ADD CONSTRAINT traffic_signal_score_range
    CHECK (traffic_signal_score >= 0.0 AND traffic_signal_score <= 1.0);

