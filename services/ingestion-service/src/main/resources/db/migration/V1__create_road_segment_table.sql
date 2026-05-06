-- V1__create_road_segment_table.sql
-- Initial road segment table with PostGIS spatial types
-- For Ingestion Service

-- Enable PostGIS extension if not already enabled
CREATE EXTENSION IF NOT EXISTS postgis;

-- Road segments table - represents individual road ways from OSM
CREATE TABLE IF NOT EXISTS road_segments (
    id BIGSERIAL PRIMARY KEY,
    osm_way_id BIGINT NOT NULL UNIQUE,
    geometry geometry(LINESTRING, 4326) NOT NULL,
    h3_tile_index VARCHAR(15) NOT NULL,
    length_meters DOUBLE PRECISION NOT NULL,
    speed_limit_kmh INTEGER NOT NULL DEFAULT 50,
    road_type VARCHAR(50),
    surface VARCHAR(100),
    curvature DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    elevation_change DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for optimal query performance
CREATE INDEX IF NOT EXISTS idx_road_geom ON road_segments USING GIST(geometry);
CREATE INDEX IF NOT EXISTS idx_road_h3_tile ON road_segments(h3_tile_index);
CREATE INDEX IF NOT EXISTS idx_road_osm_way ON road_segments(osm_way_id);
CREATE INDEX IF NOT EXISTS idx_road_type ON road_segments(road_type);

-- Constraint to ensure valid geometries
ALTER TABLE road_segments ADD CONSTRAINT IF NOT EXISTS valid_road_geometry
    CHECK (ST_IsValid(geometry));

