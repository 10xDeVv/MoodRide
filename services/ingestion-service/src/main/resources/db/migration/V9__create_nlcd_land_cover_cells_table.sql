-- V9__create_nlcd_land_cover_cells_table.sql
-- Phase 2: NLCD-native land-use source table for scenic scoring.

CREATE TABLE IF NOT EXISTS nlcd_land_cover_cells (
    id BIGSERIAL PRIMARY KEY,
    geometry geometry(POLYGON, 4326) NOT NULL,
    nlcd_class INTEGER NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT 'NLCD',
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_nlcd_geom
    ON nlcd_land_cover_cells USING GIST (geometry);

CREATE INDEX IF NOT EXISTS idx_nlcd_class
    ON nlcd_land_cover_cells (nlcd_class);

ALTER TABLE nlcd_land_cover_cells
    ADD CONSTRAINT IF NOT EXISTS chk_nlcd_class_range
    CHECK (nlcd_class BETWEEN 11 AND 95);

