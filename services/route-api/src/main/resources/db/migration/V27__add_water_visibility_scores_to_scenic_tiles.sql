ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS water_visibility_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS water_crossing_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS coastal_road_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS water_visibility_score_range;
ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS water_crossing_score_range;
ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS coastal_road_score_range;

ALTER TABLE scenic_score_tiles
    ADD CONSTRAINT water_visibility_score_range CHECK (water_visibility_score >= 0.0 AND water_visibility_score <= 1.0),
    ADD CONSTRAINT water_crossing_score_range CHECK (water_crossing_score >= 0.0 AND water_crossing_score <= 1.0),
    ADD CONSTRAINT coastal_road_score_range CHECK (coastal_road_score >= 0.0 AND coastal_road_score <= 1.0);
