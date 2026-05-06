-- V15__add_component_scores_to_scenic_tiles.sql
-- Week 1 foundation: explicit component scores for preference-driven hybrid routing.

ALTER TABLE scenic_score_tiles
    ADD COLUMN IF NOT EXISTS water_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS green_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS elevation_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS solitude_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS curve_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS poi_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

UPDATE scenic_score_tiles
SET
    water_score = LEAST(1.0, GREATEST(0.0, COALESCE(water_proximity, 0.0))),
    green_score = LEAST(1.0, GREATEST(0.0, COALESCE(natural_land_use, 0.0))),
    elevation_score = LEAST(
        1.0,
        GREATEST(
            0.0,
            CASE
                WHEN COALESCE(elevation_variance, 0.0) <= 1.0 THEN COALESCE(elevation_variance, 0.0)
                ELSE COALESCE(elevation_variance, 0.0) / 40.0
            END
        )
    ),
    solitude_score = LEAST(
        1.0,
        GREATEST(0.0, (1.0 - COALESCE(road_density, 0.0) + COALESCE(traffic_signal_score, 0.5)) / 2.0)
    ),
    curve_score = LEAST(1.0, GREATEST(0.0, COALESCE(visual_complexity, 0.0))),
    poi_score = LEAST(1.0, GREATEST(0.0, COALESCE(poi_density, 0.0)));

ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS water_score_range;
ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS green_score_range;
ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS elevation_score_range;
ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS solitude_score_range;
ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS curve_score_range;
ALTER TABLE scenic_score_tiles DROP CONSTRAINT IF EXISTS poi_score_range;

ALTER TABLE scenic_score_tiles
    ADD CONSTRAINT water_score_range CHECK (water_score >= 0.0 AND water_score <= 1.0),
    ADD CONSTRAINT green_score_range CHECK (green_score >= 0.0 AND green_score <= 1.0),
    ADD CONSTRAINT elevation_score_range CHECK (elevation_score >= 0.0 AND elevation_score <= 1.0),
    ADD CONSTRAINT solitude_score_range CHECK (solitude_score >= 0.0 AND solitude_score <= 1.0),
    ADD CONSTRAINT curve_score_range CHECK (curve_score >= 0.0 AND curve_score <= 1.0),
    ADD CONSTRAINT poi_score_range CHECK (poi_score >= 0.0 AND poi_score <= 1.0);
