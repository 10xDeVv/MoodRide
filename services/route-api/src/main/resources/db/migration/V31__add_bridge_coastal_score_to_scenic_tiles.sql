ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS bridge_coastal_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS bridge_coastal_score_range;
ALTER TABLE scenic_score_tiles
    ADD CONSTRAINT bridge_coastal_score_range CHECK (bridge_coastal_score >= 0.0 AND bridge_coastal_score <= 1.0);
