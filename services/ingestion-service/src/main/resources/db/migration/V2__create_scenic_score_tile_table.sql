-- V2__create_scenic_score_tile_table.sql
-- Scenic score tiles using H3 hexagonal grid (resolution 9)

CREATE TABLE IF NOT EXISTS scenic_score_tiles (
    h3_index VARCHAR(15) PRIMARY KEY,
    geometry geometry(POLYGON, 4326) NOT NULL,
    scenic_score DOUBLE PRECISION NOT NULL,
    water_proximity DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    elevation_variance DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    natural_land_use DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    road_density DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    poi_density DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    visual_complexity DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    last_scored TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    scoring_version VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for efficient querying
CREATE INDEX IF NOT EXISTS idx_scenic_h3 ON scenic_score_tiles(h3_index);
CREATE INDEX IF NOT EXISTS idx_scenic_score ON scenic_score_tiles(scenic_score DESC);
CREATE INDEX IF NOT EXISTS idx_scenic_geom ON scenic_score_tiles USING GIST(geometry);

-- Constraint to ensure valid scores
ALTER TABLE scenic_score_tiles ADD CONSTRAINT IF NOT EXISTS scenic_score_range
    CHECK (scenic_score >= 0.0 AND scenic_score <= 1.0);

