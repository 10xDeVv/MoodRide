-- V13__add_traffic_signal_score_to_scenic_tiles.sql
-- Store traffic influence as an explicit scenic tile component.

ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS traffic_signal_score DOUBLE PRECISION NOT NULL DEFAULT 0.5;

ALTER TABLE scenic_score_tiles
    DROP CONSTRAINT IF EXISTS scenic_score_range;

ALTER TABLE scenic_score_tiles
    ADD CONSTRAINT scenic_score_range
    CHECK (scenic_score >= 0.0 AND scenic_score <= 1.0);

ALTER TABLE scenic_score_tiles
    ADD CONSTRAINT IF NOT EXISTS traffic_signal_score_range
    CHECK (traffic_signal_score >= 0.0 AND traffic_signal_score <= 1.0);

