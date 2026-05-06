-- V15__add_geography_gist_index_road_segments.sql
-- Phase 1 performance hardening for geography-based ST_DWithin queries.

CREATE INDEX IF NOT EXISTS idx_road_geom_geography
    ON road_segments USING GIST ((geometry::geography));
