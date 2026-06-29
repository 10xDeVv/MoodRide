ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS road_stress_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS road_stress_score_range;

ALTER TABLE scenic_score_tiles
    ADD CONSTRAINT road_stress_score_range CHECK (road_stress_score >= 0.0 AND road_stress_score <= 1.0);
