ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS scenic_poi_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS scenic_poi_score_range;
ALTER TABLE scenic_score_tiles
    ADD CONSTRAINT scenic_poi_score_range CHECK (scenic_poi_score >= 0.0 AND scenic_poi_score <= 1.0);
