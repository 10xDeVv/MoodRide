-- V4__create_spatial_indexes.sql
-- Advanced spatial indexes for optimal query performance

-- BRIN (Block Range Index) for large spatial tables
-- Better for full table scans on road_segments
CREATE INDEX idx_road_geom_brin ON road_segments USING BRIN(geometry);

-- Partial index for major roads (faster for common queries)
CREATE INDEX idx_major_roads ON road_segments(h3_tile_index) 
    WHERE road_type IN ('highway', 'primary', 'secondary');

-- Spatial index for route clustering queries
CREATE INDEX idx_scenic_tiles_cluster ON scenic_score_tiles(h3_index) 
    WHERE scenic_score > 0.5;

-- Analysis function for PostGIS index usage
ANALYZE road_segments;
ANALYZE scenic_score_tiles;
ANALYZE routes;
ANALYZE route_jobs;
ANALYZE route_waypoints;

-- Gather statistics for query planner
SELECT COUNT(*) FROM road_segments;
SELECT COUNT(*) FROM scenic_score_tiles;
