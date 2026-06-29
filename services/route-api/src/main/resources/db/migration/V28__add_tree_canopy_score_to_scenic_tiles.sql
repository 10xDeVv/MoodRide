ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS tree_canopy_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS tree_canopy_score_range;

ALTER TABLE scenic_score_tiles
    ADD CONSTRAINT tree_canopy_score_range CHECK (tree_canopy_score >= 0.0 AND tree_canopy_score <= 1.0);
