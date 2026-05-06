-- V10__create_natural_earth_water_bodies_table.sql
-- Phase 2: Natural Earth water polygons for scenic water proximity scoring.

CREATE TABLE IF NOT EXISTS natural_earth_water_bodies (
    id BIGSERIAL PRIMARY KEY,
    geometry geometry(MULTIPOLYGON, 4326) NOT NULL,
    name VARCHAR(255),
    feature_class VARCHAR(128),
    source VARCHAR(64) NOT NULL DEFAULT 'NaturalEarth',
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ne_water_geom
    ON natural_earth_water_bodies USING GIST (geometry);

CREATE INDEX IF NOT EXISTS idx_ne_water_feature_class
    ON natural_earth_water_bodies (feature_class);

