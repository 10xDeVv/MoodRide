ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS viewpoint_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS viewpoint_score_range;
ALTER TABLE scenic_score_tiles
    ADD CONSTRAINT viewpoint_score_range CHECK (viewpoint_score >= 0.0 AND viewpoint_score <= 1.0);
